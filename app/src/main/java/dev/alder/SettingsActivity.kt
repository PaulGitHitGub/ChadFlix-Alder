package dev.alder

import android.content.Context
import android.content.SharedPreferences
import android.app.ActivityManager
import android.content.Intent
import android.content.res.ColorStateList
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import java.util.Locale

/**
 * Choices kept on this TV. Most are shared by every profile (they're about the TV and the sound system); taste (release
 * type, dubs and audio language) is kept per profile under "key@profileId".
 */
object Settings {
    private fun prefs(c: Context) = c.getSharedPreferences("settings", Context.MODE_PRIVATE)
    /** The language Alder shows ("es", "pt-BR"), for the whole TV; empty follows the TV's own language. */
    fun appLanguage(c: Context): String =
        if (android.os.Build.VERSION.SDK_INT >= 33) // the system keeps it, and its own per-app language page can change it
            c.getSystemService(android.app.LocaleManager::class.java)?.applicationLocales?.takeIf { !it.isEmpty }?.get(0)?.toLanguageTag().orEmpty()
        else prefs(c).getString("appLanguage", "").orEmpty()
    fun setAppLanguage(c: Context, tag: String) {
        prefs(c).edit().putString("appLanguage", tag).apply()
        // Android 13 and later keep the choice themselves, so their own per-app language page shows it too.
        if (android.os.Build.VERSION.SDK_INT >= 33) c.getSystemService(android.app.LocaleManager::class.java)?.applicationLocales =
            if (tag.isEmpty()) android.os.LocaleList.getEmptyLocaleList() else android.os.LocaleList.forLanguageTags(tag)
    }
    fun maxHeight(c: Context) = prefs(c).getInt("maxHeight", 0) // 0: no limit
    fun displayHeight(c: Context) = prefs(c).getInt("displayHeight", 0) // 0: auto
    fun maxSizeGb(c: Context) = prefs(c).getInt("maxSizeGb", 0) // 0: no limit
    /** "": as the addon orders them, "dv" or "hdr10": that first, "off": plain releases only. */
    fun hdrPref(c: Context): String {
        val p = prefs(c)
        if (!p.contains("hdrPref")) p.edit().putString("hdrPref", if (p.getBoolean("hdr", true)) "" else "off").remove("hdr").apply() // was On/Off
        return p.getString("hdrPref", "").orEmpty()
    }
    fun hdr(c: Context) = hdrPref(c) != "off"
    /** "": no preference, "remux", "encode" (smaller BluRay and web encodes) or "web". */
    fun release(c: Context) = prefs(c).getString(profileKey(c, "release"), "").orEmpty()
    /** "": no preference, "original": dubs last, "mine": the audio language first. */
    fun dub(c: Context) = prefs(c).getString(profileKey(c, "dub"), "").orEmpty()
    internal fun watchlists(c: Context) = Watchlists(prefs(c), profileKey(c, "watchlists"))
    fun profileKey(c: Context, key: String) = "$key@${Profiles.active(c)?.id}"
    /**
     * Titles this profile hid from Continue Watching on this TV: id to {name, timeOffset}, so Settings can put them
     * back. ponytail: this TV only; titles hidden in Stremio elsewhere aren't listed.
     */
    fun hidden(c: Context) = runCatching { org.json.JSONObject(prefs(c).getString(profileKey(c, "hidden"), null)!!) }
        .getOrDefault(org.json.JSONObject())
    fun setHidden(c: Context, all: org.json.JSONObject) = prefs(c).edit().putString(profileKey(c, "hidden"), all.toString()).apply()
    fun blurThumbnails(c: Context) = prefs(c).getBoolean(profileKey(c, "blurThumbnails"), prefs(c).getBoolean("blurThumbnails", false))
    fun hiddenRows(c: Context): Set<String> = prefs(c).getStringSet(profileKey(c, "hiddenRows"), emptySet()) ?: emptySet()
    fun hideRow(c: Context, rowTitle: String) {
        val current = hiddenRows(c).toMutableSet()
        current.add(rowTitle)
        prefs(c).edit().putStringSet(profileKey(c, "hiddenRows"), current).apply()
    }
    fun unhideRow(c: Context, rowTitle: String) {
        val current = hiddenRows(c).toMutableSet()
        current.remove(rowTitle)
        prefs(c).edit().putStringSet(profileKey(c, "hiddenRows"), current).apply()
    }
    fun startTab(c: Context) = prefs(c).getString(profileKey(c, "startTab"), "movies").orEmpty() // ChadFlix: opens on Movies
    /** Whether a tab after Home shows in the top bar, by its id in [tabsFor]; hiding an add-on's tab keeps the add-on. */
    fun tabShown(c: Context, id: String) = prefs(c).getBoolean(profileKey(c, tabKey(id)), id != "discover" && id != "live" && !id.startsWith("type:")) // ChadFlix: Movies, Shows, My Lists
    fun setTabShown(c: Context, id: String, shown: Boolean) = prefs(c).edit().putBoolean(profileKey(c, tabKey(id)), shown).apply()
    // Movies, Shows and Live keep the keys of the switches they had before Settings › Tabs, so those choices carry over.
    private fun tabKey(id: String) = when (id) {
        "movies" -> "showMovies"
        "shows" -> "showShows"
        "live" -> "showLive"
        "library" -> "showLibrary"
        else -> "showTab:" + id.removePrefix("type:")
    }
    /** The tabs after Home in this profile's order ([orderTabs] fills in the ones it never placed). */
    fun tabOrder(c: Context): List<String> =
        runCatching { org.json.JSONArray(prefs(c).getString(profileKey(c, "tabOrder"), "[]")).strings() }.getOrDefault(emptyList())
    fun setTabOrder(c: Context, ids: List<String>) = prefs(c).edit().putString(profileKey(c, "tabOrder"), org.json.JSONArray(ids).toString()).apply()
    fun featuredHome(c: Context) = prefs(c).getBoolean(profileKey(c, "featuredHome"), false)
    /** "imdb", "letterboxd" or "both"; Letterboxd needs [mdblistKey] and covers films only. */
    fun ratings(c: Context) = prefs(c).getString("ratings", "imdb").orEmpty().ifEmpty { "imdb" }
    /** Series badge rule for [Library.newEpisodeCount]: "aired", "season" or "off". */
    fun episodeBadges(c: Context) = prefs(c).getString("episodeBadges", "aired").orEmpty().ifEmpty { "aired" }
    fun mdblistKey(c: Context) = prefs(c).getString("mdblistKey", "").orEmpty()
    fun setMdblistKey(c: Context, key: String) = prefs(c).edit().putString("mdblistKey", key.trim()).apply()
    /** Letterboxd is asked for only when it can show: a film, a key, and a pick that includes it. */
    fun wantsLetterboxd(c: Context, type: String, id: String) =
        type == "movie" && id.startsWith("tt") && ratings(c) != "imdb" && mdblistKey(c).isNotEmpty()
    /** A removed profile's taste goes with it, so a new profile in its place starts with none. */
    fun forget(c: Context, profileId: String) = forgetPrefs(prefs(c), profileId)

    internal fun forgetPrefs(prefs: SharedPreferences, profileId: String) = prefs.edit().apply {
        listOf(
            "release", "dub", "audioLang", "audioFallback", "subLang", "subPref", "hidden", "blurThumbnails",
            "hiddenRows", "startTab", "showMovies", "showShows", "showLive", "showLibrary",
            "watchlists", "tabOrder", "featuredHome", "recentSubLangs", "recentAudioLangs",
        ).forEach { remove("$it@$profileId") }
        prefs.all.keys.filter { (it.startsWith("title:") || it.startsWith("showTab:") || it.startsWith("rowOrder:") ||
            it.startsWith("rowsOn:") || it.startsWith("rowsKnown:") || it.startsWith("showing:") ||
            it.startsWith("excl:") || it.startsWith("sgenre:") || it.startsWith("agenre:")) && it.endsWith("@$profileId") }.forEach(::remove)
    }.apply()

    /**
     * What the viewer picked in the player's Audio & subtitles and speed for one title (a series' episodes share it), so the next
     * episode and a resume start the same way. Language codes; subtitles "" is Off; null where nothing was picked.
     * [subtitle]: the exact subtitle picked for video [subtitleVideo] ("addon:<url>" or "file:<track id>"), as Stremio
     * keeps it per video. [speed] is the playback speed, 1 when never changed.
     */
    class TitleChoice(val audio: String?, val subtitles: String?, val subtitleVideo: String? = null, val subtitle: String? = null, val speed: Float = 1f)

    fun titleChoice(c: Context, metaId: String): TitleChoice? = prefs(c).getString(profileKey(c, "title:$metaId"), null)
        ?.let { runCatching { org.json.JSONObject(it) }.getOrNull() }
        ?.let { TitleChoice(it.text("audio"), if (it.has("subtitles")) it.optString("subtitles") else null,
            it.text("subtitleVideo"), it.text("subtitle"), it.optDouble("speed", 1.0).toFloat()) }

    fun pickedInPlayer(c: Context, metaId: String, audio: String? = null, subtitles: String? = null,
                       video: String? = null, subtitle: String? = null, speed: Float? = null) {
        val key = profileKey(c, "title:$metaId")
        val json = prefs(c).getString(key, null)?.let { runCatching { org.json.JSONObject(it) }.getOrNull() } ?: org.json.JSONObject()
        audio?.let { json.put("audio", it) }
        subtitles?.let { json.put("subtitles", it) }
        speed?.let { json.put("speed", it.toDouble()) }
        video?.let { json.put("subtitleVideo", it).put("subtitle", subtitle.orEmpty()) }
        prefs(c).edit().putString(key, json.toString()).apply()
    }
    fun alwaysList(c: Context) = prefs(c).getBoolean("alwaysList", false)
    /** Settings › Source list: true shows each source as its add-on wrote it, in a wider list. */
    fun addonText(c: Context) = prefs(c).getBoolean("addonText", false)
    fun matchFrameRate(c: Context) = prefs(c).getBoolean("matchFrameRate", true)
    fun decodeAudio(c: Context) = prefs(c).getBoolean("decodeAudio", false)
    /** Settings › Text and poster size, for this TV: 1 is the House Lights size, more makes every screen bigger. */
    fun uiScale(c: Context) = prefs(c).getInt("uiScale", 100).coerceIn(100, 115) / 100f
    /** Settings › Video player: null plays in Alder, else the package of the video app each source goes to. */
    fun externalPlayer(c: Context) = prefs(c).getString("videoPlayer", "").orEmpty().ifEmpty { null }
    fun clearDialogue(c: Context) = prefs(c).getBoolean("clearDialogue", false)
    /** ChadFlix: Settings › Picture & Sound › Show FPS, for this TV: a frame counter in Home's top-left corner. */
    fun showFps(c: Context) = prefs(c).getBoolean("showFps", false)
    /** ChadFlix: Settings › Picture & Sound › Background wash, for this TV: Home's soft wash behind the rows, or black. */
    fun wash(c: Context) = prefs(c).getBoolean("wash", true)
    fun setClearDialogue(c: Context, on: Boolean) = prefs(c).edit().putBoolean("clearDialogue", on).apply()
    /** "on", "foreign" (only when audio differs or forced), "off". Migrates old Boolean "subtitles". */
    fun subPref(c: Context): String {
        val p = prefs(c)
        val key = profileKey(c, "subPref")
        val oldKey = "subtitles"
        if (!p.contains(key)) {
            val fallback = if (p.contains(oldKey)) {
                val oldBool = runCatching { p.getBoolean(oldKey, true) }.getOrDefault(true)
                if (oldBool) "on" else "off"
            } else "on"
            p.edit().putString(key, fallback).remove(oldKey).apply()
        }
        return p.getString(key, "on").orEmpty().ifEmpty { "on" }
    }
    fun subtitles(c: Context) = subPref(c) != "off"
    /** Two-letter code of the dub to start with: the TV's language unless one is picked. */
    fun audioLanguage(c: Context): String = prefs(c).getString(profileKey(c, "audioLang"), prefs(c).getString("audioLang", ""))
        .orEmpty().ifEmpty { Languages.code(Locale.getDefault().toLanguageTag()) ?: "en" }
    fun preferredAudio(c: Context, metaId: String?): String = metaId?.let { titleChoice(c, it)?.audio } ?: audioLanguage(c)
    fun fallbackAudio(c: Context): String = prefs(c).getString(profileKey(c, "audioFallback"), "en").orEmpty()
    /** Two-letter code of the subtitles to start with: the TV's language unless one is picked. */
    fun subtitleLanguage(c: Context): String = prefs(c).getString(profileKey(c, "subLang"), "")
        .orEmpty().ifEmpty { Languages.code(Locale.getDefault().toLanguageTag()) ?: "en" }
    fun subtitleSize(c: Context) = prefs(c).getInt("subSize", 100) // percent of Media3's default size
    fun subtitleColorName(c: Context): String = prefs(c).getString("subColor", "white").orEmpty().ifEmpty { "white" }
    fun subtitleColor(c: Context) = subtitleColorValue(subtitleColorName(c))
    /** The player's picture: "fit" (whole picture, bars), "zoom" (fills the screen, crops) or "stretch". */
    fun pictureFit(c: Context) = prefs(c).getString("pictureFit", "fit").orEmpty().ifEmpty { "fit" }
    fun setPictureFit(c: Context, fit: String) = prefs(c).edit().putString("pictureFit", fit).apply()
    fun subtitleBackground(c: Context) = prefs(c).getBoolean("subBackground", true)
    fun setSubtitleSize(c: Context, size: Int) = prefs(c).edit().putInt("subSize", size).apply()
    fun setSubtitleColor(c: Context, color: String) = prefs(c).edit().putString("subColor", color).apply()
    fun setSubtitleBackground(c: Context, on: Boolean) = prefs(c).edit().putBoolean("subBackground", on).apply()

    fun recentSubtitles(c: Context): List<String> = runCatching {
        val raw = prefs(c).getString(profileKey(c, "recentSubLangs"), null) ?: return emptyList()
        val arr = org.json.JSONArray(raw)
        (0 until arr.length()).map { arr.getString(it) }
    }.getOrDefault(emptyList())

    fun addRecentSubtitle(c: Context, langCode: String) {
        val updated = updateRecentLanguages(recentSubtitles(c), langCode, 3)
        prefs(c).edit().putString(profileKey(c, "recentSubLangs"), org.json.JSONArray(updated).toString()).apply()
    }

    fun recentAudio(c: Context): List<String> = runCatching {
        val raw = prefs(c).getString(profileKey(c, "recentAudioLangs"), null) ?: return emptyList()
        val arr = org.json.JSONArray(raw)
        (0 until arr.length()).map { arr.getString(it) }
    }.getOrDefault(emptyList())

    fun addRecentAudio(c: Context, langCode: String) {
        val updated = updateRecentLanguages(recentAudio(c), langCode, 3)
        prefs(c).edit().putString(profileKey(c, "recentAudioLangs"), org.json.JSONArray(updated).toString()).apply()
    }
}

/** ARGB color for subtitle text from a preference name. */
fun subtitleColorValue(name: String): Int = when (name.trim().lowercase()) {
    "gray" -> 0xFFBFBFBF.toInt()
    "yellow" -> 0xFFFFE600.toInt()
    "cyan" -> 0xFF00FFFF.toInt()
    "cream" -> 0xFFFFF5C0.toInt()
    "warm_white", "warm white", "warmwhite" -> 0xFFF3EEE6.toInt()
    else -> 0xFFFFFFFF.toInt()
}

/**
 * Every source writes languages its own way: the TV "es", a file's track "spa" or "es-MX", OpenSubtitles "spa", "ger"
 * or "pob", some add-ons "Spanish". [code] turns any of them into one code, so all comparisons go through it: two
 * letters, except Brazilian Portuguese ("pt-BR"), which OpenSubtitles keeps apart from Portugal's.
 */
object Languages {
    const val BRAZIL = "pt-BR"
    // ISO 639-2/B codes (OpenSubtitles sends these) where they differ from 639-2/T, OpenSubtitles' own, and Java's old ones.
    private val aliases = mapOf("alb" to "sq", "arm" to "hy", "baq" to "eu", "bur" to "my", "chi" to "zh", "cze" to "cs",
        "dut" to "nl", "fre" to "fr", "geo" to "ka", "ger" to "de", "gre" to "el", "ice" to "is", "mac" to "mk", "mao" to "mi",
        "may" to "ms", "per" to "fa", "spl" to "es", "rum" to "ro", "slo" to "sk", "tib" to "bo", "wel" to "cy", "pom" to "pt",
        "scc" to "sr", "scr" to "hr", "ze" to "zh", "zht" to "zh", "zhe" to "zh", "iw" to "he", "in" to "id", "ji" to "yi")
    private val brazilian = setOf("pob", "pb", "ptbr", "brazilian", "brazilian portuguese", "portuguese (brazil)",
        "portuguese (brazilian)", "portuguese (br)")
    private val iso by lazy { Locale.getISOLanguages().map { aliases[it] ?: it }.distinct() }
    private val threeLetter by lazy { iso.associateBy { runCatching { Locale(it).isO3Language }.getOrDefault(it) } }
    private val englishNames by lazy { iso.associateBy { Locale(it).getDisplayLanguage(Locale.ENGLISH).lowercase() } }
    private val common = listOf("en", "es", "fr", "de", "pt", BRAZIL, "it", "hi", "ar", "ja", "ko", "zh", "ru")

    /** The code ("es", "pt-BR"), or null for none or unknown ("und"). */
    fun code(raw: String?): String? {
        val s = raw?.trim()?.lowercase()?.replace('_', '-')?.ifEmpty { null } ?: return null
        if (s in brazilian) return BRAZIL
        val parts = s.split('-')
        val base = parts[0].let { aliases[it] ?: when (it.length) {
            2 -> it.takeIf { it in iso }
            3 -> threeLetter[it]
            else -> englishNames[it]
        } } ?: return null
        return if (base == "pt" && parts.getOrNull(1) == "br") BRAZIL else base
    }
    /** "pt-BR" -> "pt". */
    fun base(code: String) = code.substringBefore('-')
    fun same(a: String?, b: String?) = code(a).let { it != null && it == code(b) }
    /** 2 for the same language, 1 for the same language from another country (Portugal's for Brazil's), else 0. */
    fun match(a: String?, b: String?): Int {
        val x = code(a) ?: return 0
        val y = code(b) ?: return 0
        return if (x == y) 2 else if (base(x) == base(y)) 1 else 0
    }
    /** "Spanish", "Portuguese (Brazil)", in the TV's language. */
    fun name(raw: String): String = (code(raw) ?: raw).let { c ->
        val l = Locale.forLanguageTag(c)
        (if (l.country.isEmpty()) l.getDisplayLanguage(Locale.getDefault()) else l.getDisplayName(Locale.getDefault()))
            .ifBlank { c }.replaceFirstChar { it.titlecase(Locale.getDefault()) }
    }
    /** Every language, the common ones first, the rest by name. */
    val all: List<String> by lazy { common + iso.filter { it !in common && name(it) != it }.sortedBy(::name) }
}

/** OK or Right steps a setting to its next value, wrapping; a value not in the list (custom) starts at the first. */
internal fun nextSettingIndex(current: Int, size: Int): Int {
    require(size > 0)
    return if (current < 0) 0 else (current + 1) % size
}

class SettingsActivity : Screen() {
    private class Choice(val key: String, val label: String, val hint: String, val values: List<Pair<Any, String>>, val perProfile: Boolean = false) {
        init { require(values.isNotEmpty() && values.all { it.first.javaClass == values[0].first.javaClass }) }
    }
    private enum class Group(val words: Int, val icon: Int) {
        STREAMS(R.string.group_streams, R.drawable.ic_play),
        PICTURE(R.string.group_picture, R.drawable.ic_tv),
        SUBTITLES(R.string.group_subtitles, R.drawable.ic_subtitles),
        LANGUAGE(R.string.group_language, R.drawable.ic_language), // ChadFlix: app, audio and dubbing languages together
        CUSTOMIZE(R.string.customize_home, R.drawable.ic_home), // ChadFlix: General (Home's settings), then each page's rows
        PROFILE(R.string.group_profile_addons, R.drawable.ic_person), // ChadFlix: who's watching and their add-ons
        ADVANCED(R.string.group_advanced, R.drawable.ic_settings), // ChadFlix: what most people never need to change
        ABOUT(R.string.group_about, R.drawable.ic_info),
    }

    // Built on first use: the words come from resources, which an Activity has only once it's attached.
    private val on get() = getString(R.string.state_on)
    private val off get() = getString(R.string.state_off)
    private fun onOff() = listOf(true to on, false to off)

    private val streaming by lazy { listOf(
        Choice("alwaysList", getString(R.string.play_press), getString(R.string.play_press_hint),
            listOf(false to getString(R.string.play_best_source), true to getString(R.string.play_show_sources))),
        Choice("maxHeight", getString(R.string.max_quality), getString(R.string.max_quality_hint),
            listOf(0 to getString(R.string.no_limit), 2160 to "4K", 1080 to "1080p", 720 to "720p")),
        Choice("maxSizeGb", getString(R.string.max_size), getString(R.string.max_size_hint),
            listOf(0 to getString(R.string.no_limit), 40 to "40 GB", 30 to "30 GB", 20 to "20 GB", 10 to "10 GB", 5 to "5 GB")),
    ) }
    // ChadFlix: Advanced. The Source Choice presets still set Release Type and HDR (see choice()).
    private val advanced by lazy { listOf(
        // Alder, then each video app on this TV by its own name.
        Choice("videoPlayer", getString(R.string.video_player), getString(R.string.video_player_hint),
            listOf("" to getString(R.string.video_player_alder)) + ExternalPlayer.installed(this)),
        Choice("release", getString(R.string.release_type), getString(R.string.release_type_hint),
            listOf("" to getString(R.string.no_preference), "remux" to getString(R.string.release_remux),
                "encode" to getString(R.string.release_encode), "web" to getString(R.string.release_web)), true),
        Choice("addonText", getString(R.string.source_list), getString(R.string.source_list_hint),
            listOf(false to getString(R.string.source_list_clean), true to getString(R.string.source_list_addon))),
        Choice("hdrPref", getString(R.string.hdr_choice), getString(R.string.hdr_choice_hint),
            listOf("" to on, "dv" to getString(R.string.hdr_dv_first), "hdr10" to getString(R.string.hdr_hdr10_first), "off" to off)),
        Choice("matchFrameRate", getString(R.string.frame_rate), getString(R.string.frame_rate_hint), onOff()),
        Choice("decodeAudio", getString(R.string.audio_output), getString(R.string.audio_output_hint),
            listOf(false to getString(R.string.streams_auto), true to "PCM")),
        Choice("showFps", getString(R.string.show_fps), getString(R.string.show_fps_hint), listOf(false to off, true to on)),
    ) }
    private val pictureAndSound by lazy { listOf(
        Choice("displayHeight", getString(R.string.display_resolution), getString(R.string.display_resolution_hint),
            listOf(0 to getString(R.string.streams_auto), 1080 to "1080p", 2160 to "4K")),
        Choice("uiScale", getString(R.string.ui_size), getString(R.string.ui_size_hint),
            // ponytail: two steps; 130% pushed Home's title logo into the top bar on a 1080p TV.
            listOf(100 to getString(R.string.ui_size_normal), 115 to getString(R.string.size_large))),
        Choice("clearDialogue", getString(R.string.clear_dialogue), getString(R.string.clear_dialogue_setting_hint),
            listOf(false to off, true to on)),
    ) }
    private val dubbed by lazy { listOf(
        Choice("dub", getString(R.string.dubbed), getString(R.string.dubbed_hint),
            listOf("" to getString(R.string.no_preference), "original" to getString(R.string.dubbed_original),
                "mine" to getString(R.string.dubbed_mine)), true),
    ) }
    private val subtitles by lazy { listOf(
        Choice("subPref", getString(R.string.subs_default), getString(R.string.subs_default_hint),
            listOf("on" to getString(R.string.subs_always), "foreign" to getString(R.string.subs_foreign), "off" to off), true),
    ) }
    private val subtitleLooks by lazy { listOf(
        Choice("subSize", getString(R.string.subtitle_size), getString(R.string.subtitle_size_hint),
            listOf(100 to getString(R.string.size_medium), 130 to getString(R.string.size_large),
                160 to getString(R.string.size_extra_large), 80 to getString(R.string.size_small))),
        Choice("subColor", getString(R.string.subtitle_color), getString(R.string.subtitle_color_hint),
            listOf("white" to getString(R.string.color_white), "gray" to getString(R.string.color_gray),
                "yellow" to getString(R.string.color_yellow),
                "cyan" to getString(R.string.color_cyan), "cream" to getString(R.string.color_cream),
                "warm_white" to getString(R.string.color_warm_white))),
        Choice("subBackground", getString(R.string.subtitle_background), getString(R.string.subtitle_background_hint), onOff()),
    ) }

    private lateinit var groups: LinearLayout
    private lateinit var detailRows: LinearLayout
    private lateinit var rowsScroll: ScrollView
    private var rowsMaxPx = 0 // ChadFlix: the settings list's height, for Customize Home's columns
    private lateinit var detailTitle: TextView
    private lateinit var hint: TextView
    private lateinit var profileName: TextView
    private lateinit var avatar: TextView
    private val groupViews = mutableMapOf<Group, View>()
    private val groupDots = mutableMapOf<Group, TextView>()
    private val groupLabels = mutableMapOf<Group, TextView>()
    private val groupBadges = mutableMapOf<Group, View>()
    private val refresh = mutableListOf<() -> Unit>()
    private var selectedGroup: Group? = null
    private var reopenGroup: Group? = null
    private var firstDetail: View? = null
    private var addonValue: TextView? = null
    private val prefs by lazy { getSharedPreferences("settings", MODE_PRIVATE) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Settings.hdrPref(this) // migrate the old HDR switch before any row reads it
        Settings.subPref(this) // migrate the old subtitles switch before any row reads it
        val width = resources.displayMetrics.widthPixels / resources.displayMetrics.density
        val height = resources.displayMetrics.heightPixels / resources.displayMetrics.density
        val root = FrameLayout(this).apply { setBackgroundColor(BASE); clipChildren = false; clipToPadding = false }

        val header = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        avatar = text(Profiles.active(this)?.name?.take(1)?.uppercase() ?: "A", BODY, bold = true, color = TUNGSTEN).apply {
            gravity = Gravity.CENTER
            background = glass(15)
        }
        header.addView(avatar, LinearLayout.LayoutParams(dp(30), dp(30)))
        header.addView(text(R.string.settings_title, SERIF_TITLE, serif = true), LinearLayout.LayoutParams(-2, -2).apply { marginStart = dp(12) })
        profileName = text("", SMALL, color = TEXT_3).apply { setPadding(dp(14), dp(8), 0, 0) }
        header.addView(profileName)
        header.addView(View(this), LinearLayout.LayoutParams(0, 1, 1f))
        header.addView(android.widget.TextClock(this).apply {
            format12Hour = "h:mm a"
            format24Hour = "HH:mm"
            textSize = SMALL
            setTextColor(TEXT_2)
            typeface = text("", SMALL).typeface
        })
        root.addView(header, FrameLayout.LayoutParams(-1, dp(40), Gravity.TOP).apply {
            leftMargin = dp(48); rightMargin = dp(48); topMargin = dp(24)
        })

        groups = column(Gravity.START).apply {
            setPadding(dp(7), dp(9), dp(7), dp(9))
            background = glassOver(this, 14)
        }
        Group.entries.forEach { group ->
            if (group == Group.ADVANCED) groups.addView(View(this).apply { setBackgroundColor(GLASS_EDGE) },
                LinearLayout.LayoutParams(-1, dp(1)).apply { setMargins(dp(6), dp(8), dp(6), dp(8)) })
            addGroup(group)
        }
        root.addView(groups, FrameLayout.LayoutParams(dp(200), dp((height - 115).toInt()), Gravity.TOP or Gravity.START).apply {
            leftMargin = dp(48); topMargin = dp(88)
        })

        // ChadFlix: centred and heavier than the rows, so the group's name reads as a heading, not an option.
        detailTitle = text("", BODY, bold = true).apply { typeface = heavyFace; gravity = Gravity.CENTER_HORIZONTAL; setPadding(dp(12), 0, dp(12), 0) }
        detailRows = column(Gravity.START)
        // A long group (Profile) scrolls in its own space: drawn past it, rows ran over the group's title and pushed the
        // help off the screen. The padding keeps room for the focused row's glow inside that space.
        val rowsMax = dp((height - 210).toInt())
        rowsMaxPx = rowsMax
        rowsScroll = BarScroll(rowsMax).apply {
            isVerticalScrollBarEnabled = false
            clipToPadding = false
            setPadding(dp(12), dp(6), dp(12), dp(12))
            // Its parent lets children draw past it (the glow), so the frame clips the rows itself.
            outlineProvider = android.view.ViewOutlineProvider.BOUNDS
            clipToOutline = true
            addView(detailRows)
        }
        hint = text("", SMALL, color = TEXT_2).onArt().apply { setPadding(dp(12), 0, dp(20), 0) }
        // The help follows the rows, so a long group (Streams has six) never runs under it.
        val detail = column(Gravity.START).apply {
            clipChildren = false
            addView(detailTitle, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(3) })
            addView(rowsScroll, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(4); marginStart = -dp(12); marginEnd = -dp(12) })
            addView(hint, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) })
        }
        root.addView(detail, FrameLayout.LayoutParams(dp((width - 328).toInt()), dp((height - 110).toInt()), Gravity.TOP or Gravity.START).apply {
            leftMargin = dp(280); topMargin = dp(88)
        })
        window.setBackgroundDrawable(null)
        setContentView(root)
        profileName.text = Profiles.active(this)?.name?.let { getString(R.string.settings_profile_name, it) } ?: ""
        // Rebuilt for a new Text and poster size: back on the same page, on the row that changed it.
        val reopened = savedInstanceState?.getString("group")?.let { name -> Group.entries.firstOrNull { it.name == name } }
        val start = reopened ?: Group.STREAMS
        showGroup(start)
        // Now, not posted: otherwise Android gives first focus to the Streams button, and focus on a page button opens it.
        groupViews[start]?.requestFocus()
        if (reopened != null) detailRows.post {
            val row = savedInstanceState?.getString("focusChoice")?.let { detailRows.findViewWithTag<View>(it) }
            (row ?: firstDetail)?.requestFocus()
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        // The page the size was changed on: focus moving during the rebuild can select another one first.
        if (reopenGroup != null) outState.putString("focusChoice", "uiScale")
        (reopenGroup ?: selectedGroup)?.let { outState.putString("group", it.name) }
    }

    override fun onPause() {
        customStop?.invoke() // ChadFlix: Customize Home's edit mode ends (and its rows stop wobbling)
        super.onPause()
    }

    override fun onResume() {
        super.onResume()
        groupBadges[Group.ABOUT]?.visibility = if (Updates.hasUpdate(this)) View.VISIBLE else View.GONE
        if (::profileName.isInitialized) {
            val name = Profiles.active(this)?.name
            profileName.text = name?.let { getString(R.string.settings_profile_name, it) } ?: ""
            avatar.text = name?.take(1)?.uppercase() ?: "A"
            refresh.forEach { it() }
        }
        val key = authKey ?: return
        background({ Stremio.addons(key).size }) { result ->
            addonValue?.text = installed(result.getOrNull(), R.string.addons_unavailable)
        }
    }

    private fun installed(count: Int?, otherwise: Int) =
        (count?.let { getString(R.string.addons_installed, it) } ?: getString(otherwise)) + "  ›"

    override fun dispatchKeyEvent(event: android.view.KeyEvent): Boolean {
        if (event.action == android.view.KeyEvent.ACTION_DOWN) {
            if (event.keyCode == android.view.KeyEvent.KEYCODE_DPAD_RIGHT &&
                groupViews.values.any { it.hasFocus() }) {
                if (firstDetail?.requestFocus() == true) return true
            }
            if (event.keyCode == android.view.KeyEvent.KEYCODE_DPAD_LEFT && !customOwnsLeft(currentFocus) &&
                currentFocus?.let { isInside(it, detailRows) } == true) {
                if (selectedGroup?.let { groupViews[it]?.requestFocus() } == true) return true
            }
            // The rows end where they end: Down past the last one stays put instead of falling into the group list.
            if (event.keyCode == android.view.KeyEvent.KEYCODE_DPAD_DOWN && detailRows.childCount > 0 &&
                currentFocus === detailRows.getChildAt(detailRows.childCount - 1)) return true
            // ChadFlix: in Customize Home, Back drops a picked-up row or leaves edit mode before it leaves the panel.
            if (event.keyCode == android.view.KeyEvent.KEYCODE_BACK && currentFocus?.let { isInside(it, detailRows) } == true &&
                customBack?.invoke() == true) return true
            if (event.keyCode == android.view.KeyEvent.KEYCODE_BACK &&
                currentFocus?.let { isInside(it, detailRows) } == true) {
                if (selectedGroup?.let { groupViews[it]?.requestFocus() } == true) return true
            }
        }
        return super.dispatchKeyEvent(event)
    }

    private fun addGroup(group: Group) {
        val dot = text("•", SMALL, color = TUNGSTEN).apply { gravity = Gravity.CENTER; visibility = View.INVISIBLE }
        val icon = ImageView(this).apply {
            setImageResource(group.icon)
            imageTintList = ColorStateList.valueOf(TEXT_3)
        }
        val label = text(group.words, BODY, bold = true, color = TEXT_2)
        val badge = View(this).apply {
            background = rounded(TUNGSTEN, 3)
            visibility = if (group == Group.ABOUT && Updates.hasUpdate(this@SettingsActivity)) View.VISIBLE else View.GONE
        }
        val row = LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            addView(dot, LinearLayout.LayoutParams(dp(10), -1))
            addView(icon, LinearLayout.LayoutParams(dp(14), dp(14)).apply { marginStart = dp(4) })
            addView(label, LinearLayout.LayoutParams(0, -2, 1f).apply { marginStart = dp(10) })
            addView(badge, LinearLayout.LayoutParams(dp(6), dp(6)).apply { marginEnd = dp(8) })
            setPadding(dp(2), 0, dp(4), 0)
            rowFocus(8, rest = { if (selectedGroup == group) tile(8) else null }) { has ->
                if (has) showGroup(group)
            }
            setOnClickListener { showGroup(group); firstDetail?.requestFocus() }
        }
        groupViews[group] = row
        groupDots[group] = dot
        groupLabels[group] = label
        groupBadges[group] = badge
        groups.addView(row, LinearLayout.LayoutParams(-1, dp(34)).apply { bottomMargin = dp(1) })
    }

    private fun showGroup(group: Group) {
        if (selectedGroup == group && detailRows.childCount > 0) return
        selectedGroup = group
        groupViews.forEach { (item, view) ->
            groupDots[item]?.visibility = if (item == group) View.VISIBLE else View.INVISIBLE
            groupLabels[item]?.setTextColor(if (item == group) TEXT else if (item == Group.ADVANCED || item == Group.ABOUT) TEXT_3 else TEXT_2)
            if (!view.hasFocus()) view.background = if (item == group) tile(8) else null
        }
        detailTitle.text = getString(group.words)
        customStop?.invoke() // ChadFlix: Customize Home's edit mode ends with its group
        customStop = null; customBack = null; customOwnsLeft = { false }
        detailRows.removeAllViews()
        rowsScroll.scrollTo(0, 0)
        refresh.clear()
        firstDetail = null
        addonValue = null
        hint.text = getString(when (group) {
            Group.STREAMS -> R.string.hint_streams
            Group.PICTURE -> R.string.hint_picture
            Group.SUBTITLES -> R.string.hint_subtitles
            Group.CUSTOMIZE -> R.string.hint_home_display // General opens first
            Group.PROFILE -> R.string.hint_profile_addons
            Group.LANGUAGE -> R.string.hint_language
            Group.ADVANCED -> R.string.hint_advanced
            Group.ABOUT -> R.string.hint_about
        })
        when (group) {
            Group.STREAMS -> {
                presetRow()
                streaming.forEach(::choiceRow)
            }
            Group.PICTURE -> {
                pictureAndSound.forEach(::choiceRow)
                choiceRow(Choice("wash", getString(R.string.background_wash), getString(R.string.background_wash_hint), listOf(true to on, false to off))) // ChadFlix
            }
            Group.SUBTITLES -> {
                subtitles.forEach(::choiceRow)
                languageRow("subLang", getString(R.string.subtitle_language), getString(R.string.subtitle_language_hint))
                subtitleLooks.forEach(::choiceRow)
            }
            Group.CUSTOMIZE -> customizeHome()
            Group.PROFILE -> {
                actionRow(getString(R.string.menu_switch_profile), "", getString(R.string.switch_profile_hint)) {
                    startActivity(Intent(this, ProfilesActivity::class.java).putExtra("pick", true)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
                }
                actionRow(getString(R.string.my_addons), "…", getString(R.string.my_addons_hint)) {
                    if (authKey != null) startActivity(Intent(this, AddonsActivity::class.java))
                }.also { addonValue = it }
                val key = authKey
                if (key != null) background({ Stremio.addons(key).size }) { result ->
                    addonValue?.text = installed(result.getOrNull(), R.string.addons_unavailable)
                }
                Profiles.active(this)?.let { me ->
                    lateinit var value: TextView
                    value = actionRow(getString(R.string.share_streams), if (Profiles.sharesStreams(this, me.id)) on else off,
                        getString(R.string.share_streams_hint)) {
                        val shares = !Profiles.sharesStreams(this, me.id)
                        Profiles.setSharesStreams(this, me.id, shares)
                        value.text = (if (shares) on else off) + "  ›"
                    }
                }
                Profiles.streamLenders(this).forEach { lender ->
                    val value = actionRow(getString(R.string.shared_by, lender.name), "…",
                        getString(R.string.shared_by_hint, lender.name)) {}
                    background({ (Stremio.savedAddons(lender.authKey) ?: Stremio.addons(lender.authKey)).filter { it.shareable } }) { result ->
                        value.text = result.getOrNull()?.map { addonShortName(it.name) }?.ifEmpty { null }?.joinToString(", ")
                            ?: getString(if (result.isSuccess) R.string.no_stream_addons else R.string.load_failed)
                    }
                }
            }
            Group.LANGUAGE -> {
                appLanguageRow()
                languageRow("audioLang", getString(R.string.audio_language), getString(R.string.audio_language_hint))
                languageRow("audioFallback", getString(R.string.audio_fallback), getString(R.string.audio_fallback_hint), R.string.state_off)
                dubbed.forEach(::choiceRow)
            }
            Group.ADVANCED -> {
                advanced.forEach(::choiceRow)
                lateinit var keyValue: TextView
                fun keyState() = getString(if (Settings.mdblistKey(this).isEmpty()) R.string.key_not_set else R.string.key_set)
                keyValue = actionRow(getString(R.string.mdblist_key), keyState(), getString(R.string.mdblist_key_hint)) {
                    askOnPhone(R.string.mdblist_key, initial = Settings.mdblistKey(this)) { typed ->
                        Settings.setMdblistKey(this, typed)
                        forgetLetterboxd()
                        keyValue.text = keyState() + "  ›"
                    }
                }
                lateinit var reloadValue: TextView
                reloadValue = actionRow(getString(R.string.reload_addons), "", getString(R.string.reload_addons_hint)) {
                    val key = authKey ?: return@actionRow
                    reloadValue.text = getString(R.string.reloading)
                    background({ Stremio.addons(key, refresh = true).size }) { result ->
                        reloadValue.text = installed(result.getOrNull(), R.string.reload_failed)
                    }
                }
                actionRow(getString(R.string.clear_storage), "", getString(R.string.clear_storage_hint)) {
                    choose(getString(R.string.clear_storage_title), listOf(getString(R.string.action_cancel), getString(R.string.action_clear)),
                        getString(R.string.clear_storage_desc)) { choice ->
                        if (choice != 1) return@choose
                        // Clearing removes every profile, so a PIN-locked one asks for its PIN first, as removing it does.
                        if (Profiles.all(this).none { it.pin != null }) clearStorage() else askPinToClear()
                    }
                }
            }
            Group.ABOUT -> {
                detailRows.addView(brand(28.5f), LinearLayout.LayoutParams(-2, -2).apply {
                    marginStart = dp(12); bottomMargin = dp(14)
                })
                actionRow(getString(R.string.app_name), getString(R.string.version, Updates.mine(this)), getString(R.string.about_hint)) {
                    choose(getString(R.string.app_name), listOf(getString(R.string.action_ok)), getString(R.string.about_desc) + "\n\n" + getString(R.string.tmdb_notice)) {}
                }
                actionRow(getString(R.string.about_credit), "", getString(R.string.about_credit_desc)) {} // ChadFlix: GPL credit
                actionRow(getString(R.string.whats_new), getString(R.string.version, Updates.mine(this)), getString(R.string.whats_new_hint)) { whatsNew() }
                // Only while a crash waits to be sent: answered "No" on Home, or the send failed.
                if (Crashes.sendable(this)) actionRow(getString(R.string.report_send), "", getString(R.string.report_send_hint)) {
                    sendReport { showGroup(Group.ABOUT) }
                }
                if (Updates.ENABLED) { // ChadFlix fork: the switch for self-updates is in Updates.kt
                var found: Updates.Release? = null
                val value = actionRow(getString(R.string.updates), getString(R.string.updates_checking), getString(R.string.updates_hint)) {
                    found?.let(::installUpdate) ?: showGroup(Group.ABOUT)
                }
                background({ Updates.latest(applicationContext) }) { r ->
                    val release = r.getOrNull()
                    found = release?.takeIf { newerVersion(it.version, Updates.mine(this)) }
                    groupBadges[Group.ABOUT]?.visibility = if (found != null) View.VISIBLE else View.GONE
                    value.text = when {
                        found != null -> getString(R.string.updates_install, found!!.version)
                        release == null -> getString(R.string.updates_check_failed)
                        else -> getString(R.string.updates_up_to_date)
                    }
                }
                }
            }
        }
    }

    private fun settingRow(label: String, value: View, help: String, perProfile: Boolean = false,
                           focus: (Boolean) -> Unit = {}, click: () -> Unit): View {
        val line = LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            addView(text(label, BODY, bold = true), LinearLayout.LayoutParams(-2, -2))
            if (perProfile) addView(text(getString(R.string.this_profile).uppercase(), 8f, color = TUNGSTEN).apply {
                setPadding(dp(5), dp(2), dp(5), dp(2))
                background = glass(6)
            }, LinearLayout.LayoutParams(-2, -2).apply { marginStart = dp(8) })
            addView(View(this@SettingsActivity), LinearLayout.LayoutParams(0, 1, 1f))
            addView(value, LinearLayout.LayoutParams(-2, -2))
            setPadding(dp(12), 0, dp(12), 0)
        }
        val row = FrameLayout(this).apply {
            addView(line, FrameLayout.LayoutParams(-1, -1))
            rowFocus(9) { has ->
                if (has) hint.text = help
                elevation = if (has) dp(8).toFloat() else 0f
                if (has && android.os.Build.VERSION.SDK_INT >= 28) outlineSpotShadowColor = TUNGSTEN_GLOW
                focus(has)
            }
            setOnClickListener { click() }
        }
        detailRows.addView(row, LinearLayout.LayoutParams(-1, dp(42)))
        if (firstDetail == null) firstDetail = row
        return row
    }

    /** The titles hidden from Continue Watching; OK on one puts it back with its resume point, here and in Stremio. */
    private fun unhideMenu(done: () -> Unit) {
        val all = Settings.hidden(this)
        val ids = all.keys().asSequence().toList()
        if (ids.isEmpty()) return choose(getString(R.string.nothing_hidden), listOf(getString(R.string.action_ok)), getString(R.string.nothing_hidden_desc)) {}
        val key = authKey ?: return
        choose(getString(R.string.hidden_continue), ids.map { all.getJSONObject(it).optString("name", it) }, getString(R.string.unhide_title_hint)) { i ->
            val id = ids[i]
            background({
                val item = Stremio.library(key, listOf(id)).firstOrNull() ?: error("not in the library")
                Library.unhide(item, all.getJSONObject(id).optLong("timeOffset"))
                Stremio.saveLibrary(key, listOf(item))
            }) { result ->
                result.fold({
                    all.remove(id)
                    Settings.setHidden(this, all)
                    done()
                }) { choose(getString(R.string.unhide_failed), listOf(getString(R.string.action_ok)), getString(R.string.check_connection)) {} }
            }
        }
    }

    /** This profile's tabs after Home, in its order; its add-ons say whether Live and their own tabs (Trakt…) exist. */
    private fun withTabs(then: (List<String>) -> Unit) {
        val key = authKey ?: return
        background({
            val addons = runCatching { Stremio.addons(key) }.getOrElse { Stremio.savedAddons(key).orEmpty() }
            tabsFor(addons.flatMap { a -> a.catalogs.map { it.type } })
        }) { result ->
            then(orderTabs(Settings.tabOrder(this), result.getOrElse { tabsFor(emptyList()) }))
        }
    }

    private fun tabsSummary(tabs: List<String>) = getString(R.string.tabs_count, tabs.count { Settings.tabShown(this, it) }, tabs.size)

    private fun tabName(id: String) = when (id) {
        "discover" -> getString(R.string.tab_discover)
        "shows" -> getString(R.string.tab_shows)
        "movies" -> getString(R.string.tab_movies)
        "live" -> getString(R.string.tab_live)
        "library" -> getString(R.string.tab_my_list)
        "services" -> getString(R.string.tab_services)
        else -> typeTabName(this, id.removePrefix("type:"))
    }

    /**
     * Settings › Tabs: the tabs after Home, left to right as in the top bar. OK on one shows or hides it, or moves it a
     * place; the list then opens again on that tab, so moving it several places is OK, OK, OK.
     */
    private fun tabsMenu(focus: Int = 0, done: (List<String>) -> Unit): Unit = withTabs { tabs ->
        choose(getString(R.string.tabs), tabs.map { "${tabName(it)}  ·  ${if (Settings.tabShown(this, it)) on else off}" },
            getString(R.string.tabs_desc), focus = focus.coerceIn(0, tabs.lastIndex)) { i ->
            val id = tabs[i]
            val shown = Settings.tabShown(this, id)
            fun move(to: Int) {
                val reordered = tabs.toMutableList().apply { add(to, removeAt(i)) }
                Settings.setTabOrder(this, saveTabOrder(Settings.tabOrder(this), reordered))
            }
            val actions = listOfNotNull(
                getString(if (shown) R.string.tab_hide else R.string.tab_show) to { Settings.setTabShown(this, id, !shown); i },
                if (i > 0) getString(R.string.tab_move_left) to { move(i - 1); i - 1 } else null,
                if (i < tabs.lastIndex) getString(R.string.tab_move_right) to { move(i + 1); i + 1 } else null,
            )
            choose(tabName(id), actions.map { it.first }) { a ->
                val at = actions[a].second()
                withTabs(done)
                tabsMenu(at, done)
            }
        }
    }

    // ---------- ChadFlix: Customize Home v2: an On and an Off column, and an iPhone-style edit mode ----------

    private var customOwnsLeft: (View?) -> Boolean = { false } // views where Left stays in the panel (not back to the menu)
    private var customBack: (() -> Boolean)? = null // Back: drops a picked-up row, or leaves edit mode
    private var customStop: (() -> Unit)? = null // stops the jiggle (leaving the group, pausing)

    /** A list that scrolls in its own space, with the thin scroll bar whenever it runs past it; [maxH] caps its height. */
    /** [clipRows]: rows scrolled past the top or bottom are cut at its edges (its parents don't clip, for focus growth),
     *  so they never draw over the column's label above; sideways they may still grow past it. */
    private inner class BarScroll(private val maxH: Int = 0, private val clipRows: Boolean = false) : ScrollView(this@SettingsActivity) {
        private val track = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply { color = 0x1AFFFFFF }
        private val thumb = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply { color = 0x59FFFFFF }
        private val box = android.graphics.RectF()

        override fun onMeasure(widthSpec: Int, heightSpec: Int) =
            super.onMeasure(widthSpec, if (maxH > 0) MeasureSpec.makeMeasureSpec(maxH, MeasureSpec.AT_MOST) else heightSpec)

        override fun dispatchDraw(canvas: android.graphics.Canvas) {
            if (clipRows) {
                val saved = canvas.save()
                canvas.clipRect(scrollX - dp(24), scrollY - dp(2), scrollX + width + dp(24), scrollY + height + dp(2)) // a picked-up row's outline
                super.dispatchDraw(canvas)
                canvas.restoreToCount(saved)
            } else super.dispatchDraw(canvas)
            val content = getChildAt(0)?.height ?: return
            val range = content + paddingTop + paddingBottom - height
            if (range <= 0) return
            val w = dp(3).toFloat()
            val r = w / 2
            val x = width - dp(6) - w // in the rows' right padding
            val top = scrollY + paddingTop.toFloat()
            val trackH = height - paddingTop - paddingBottom.toFloat()
            box.set(x, top, x + w, top + trackH)
            canvas.drawRoundRect(box, r, r, track)
            val thumbH = maxOf(dp(24).toFloat(), trackH * height / (content + paddingTop + paddingBottom))
            val thumbTop = top + (trackH - thumbH) * scrollY / range
            box.set(x, thumbTop, x + w, thumbTop + thumbH)
            canvas.drawRoundRect(box, r, r, thumb)
        }
    }

    /** Customize Home › General: what Home & Display held. Its rows go straight into [detailRows], under the pills. */
    private fun generalRows() {
        choiceRow(Choice("startTab", getString(R.string.default_tab), getString(R.string.default_tab_hint),
            listOf("movies" to getString(R.string.tab_movies), "home" to getString(R.string.tab_home), // ChadFlix: Movies first, the default
                "shows" to getString(R.string.tab_shows), "library" to getString(R.string.tab_my_list)), perProfile = true))
        lateinit var tabsValue: TextView
        tabsValue = actionRow(getString(R.string.tabs), "…", getString(R.string.tabs_hint), perProfile = true) {
            tabsMenu { tabs -> tabsValue.text = tabsSummary(tabs) + "  ›" }
        }
        withTabs { tabs -> tabsValue.text = tabsSummary(tabs) + "  ›" }
        choiceRow(Choice("featuredHome", getString(R.string.featured_home), getString(R.string.featured_home_hint),
            listOf(false to off, true to on), perProfile = true))
        choiceRow(Choice("ratings", getString(R.string.ratings_source), getString(R.string.ratings_source_hint),
            listOf("imdb" to "IMDb", "letterboxd" to LETTERBOXD, "both" to getString(R.string.ratings_both))))
        choiceRow(Choice("episodeBadges", getString(R.string.episode_badges), getString(R.string.episode_badges_hint),
            listOf("aired" to getString(R.string.episode_badges_aired), "season" to getString(R.string.episode_badges_season), "off" to off)))
        choiceRow(Choice("blurThumbnails", getString(R.string.blur_thumbnails), getString(R.string.blur_thumbnails_hint),
            listOf(false to off, true to on), perProfile = true))
        var hiddenRowsValue: TextView? = null
        fun countRows() { hiddenRowsValue?.text = "${Settings.hiddenRows(this).size}  ›" }
        hiddenRowsValue = actionRow(getString(R.string.hidden_rows), "", getString(R.string.hidden_rows_hint)) {
            unhideRowsMenu(::countRows)
        }
        countRows()
        var hiddenValue: TextView? = null
        fun count() { hiddenValue?.text = "${Settings.hidden(this).length()}  ›" }
        hiddenValue = actionRow(getString(R.string.hidden_continue), "", getString(R.string.hidden_continue_hint)) {
            unhideMenu(::count)
        }
        count()
    }

    /**
     * General | Movies | Shows | Services pills, focus opening each. General: [generalRows], straight under the pills.
     * The others: two columns. On: "Continue Watching · Always First", then the page's rows in their order,
     * each with a grip. Off: every other row it could show (genres, then add-on rows), A to Z. OK moves a row to the other
     * column (turned on, it goes to the bottom of On); Left / Right switch columns. Hold OK in On: edit mode, every On row
     * wobbles; OK picks one up (red outline) and drops it, Up / Down move it; hold OK again or Back leaves (Back drops a
     * picked-up row first). Saved at once, in the order and on/off the home rows' hold-OK menu uses too.
     */
    private fun customizeHome() {
        var tab = 0 // 0 General, 1 Movies, 2 Shows, 3 Services
        var movie = true
        var edit = false
        var picked = -1 // the On row picked up in edit mode
        var onRows = mutableListOf<String>()
        var offRows = listOf<String>()
        val onViews = mutableListOf<View>()
        val offViews = mutableListOf<View>()
        val jiggles = HashMap<View, android.animation.ValueAnimator>()
        val still = android.provider.Settings.Global.getFloat(contentResolver, android.provider.Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f
        val tabs = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        // General, then one pill per page.
        val tabViews = listOf(R.string.customize_general, R.string.tab_movies, R.string.tab_shows, R.string.tab_services).map { words ->
            text(words, BODY, bold = true).apply { id = View.generateViewId(); gravity = Gravity.CENTER; isFocusable = true; setPadding(dp(18), 0, dp(18), 0) }
        }
        fun openPill() = tabViews[tab]
        fun styleTabs() = tabViews.forEach { t ->
            val open = t === openPill()
            t.background = if (t.isFocused) HomeStyle.pill(HomeStyle.WHITE) else if (open) HomeStyle.pill(0x40FFFFFF) else null
            t.setTextColor(if (t.isFocused) HomeStyle.BLACK else if (open) HomeStyle.WHITE else 0xB0FFFFFF.toInt())
        }
        val colH = rowsMaxPx - dp(80) // under the pills (38 dp), the On / Off labels (22 dp) and the list's padding (18 dp)
        fun column(header: String): Pair<LinearLayout, BarScroll> {
            val list = column(Gravity.START).apply { clipChildren = false }
            val scroll = BarScroll(clipRows = true).apply { isVerticalScrollBarEnabled = false; clipToPadding = false; setPadding(0, dp(4), dp(12), dp(4)); addView(list) }
            return list to scroll.also { it.tag = header }
        }
        val (onList, onScroll) = column(on)
        val (offList, offScroll) = column(off)
        val columns = LinearLayout(this).apply {
            listOf(onScroll, offScroll).forEachIndexed { i, sc ->
                addView(column(Gravity.START,
                    text(sc.tag as String, SMALL, bold = true, color = TEXT_3).apply {
                        gravity = Gravity.CENTER_HORIZONTAL; setPadding(dp(12), 0, dp(12), dp(4))
                        layoutParams = LinearLayout.LayoutParams(-1, -2) // the column's full width, so the header centres over it
                    },
                ).apply { addView(sc, LinearLayout.LayoutParams(-1, colH)) },
                    LinearLayout.LayoutParams(0, -2, 1f).apply { if (i == 1) marginStart = dp(8) })
            }
        }
        fun page() = listOf("Movies", "Shows", HomeStyle.SERVICES)[tab - 1] // the page's label in MainActivity
        fun shown(t: String) = HomeStyle.isOn(HomeStyle.rowsOn(this, page()), t, movie, services = tab == 3) && t !in Settings.hiddenRows(this)
        fun save() = HomeStyle.saveRowOrder(this, page(), onRows + offRows)
        fun jiggle() = onViews.forEachIndexed { i, v -> // the visible On rows only; transforms on the render thread, no layout
            val visible = v.bottom > onScroll.scrollY && v.top < onScroll.scrollY + onScroll.height
            val want = edit && visible && i != picked && !still
            val a = jiggles[v]
            if (want && a == null) jiggles[v] = android.animation.ValueAnimator.ofFloat(-1f, 1f).apply {
                v.animate().cancel() // a settle still running would fight the new wobble
                duration = (250L..300L).random()
                repeatCount = android.animation.ValueAnimator.INFINITE
                repeatMode = android.animation.ValueAnimator.REVERSE
                interpolator = android.view.animation.AccelerateDecelerateInterpolator()
                val lift = resources.displayMetrics.density * 0.5f
                addUpdateListener { val f = it.animatedValue as Float; v.rotation = 0.8f * f; v.translationY = lift * f }
                start()
                currentPlayTime = (0L until duration).random() // out of phase
            } else if (!want && a != null) {
                a.cancel(); jiggles.remove(v)
                v.animate().rotation(0f).translationY(0f).setDuration(Screen.FOCUS_MS).setInterpolator(Screen.EASE_OUT).start()
            }
        }
        fun stopJiggle() { jiggles.forEach { (v, a) -> a.cancel(); v.animate().cancel(); v.rotation = 0f; v.translationY = 0f }; jiggles.clear() }
        fun style(v: View, name: TextView, grip: ImageView?, lit: Boolean, isOn: Boolean, up: Boolean) {
            v.background = when {
                up -> android.graphics.drawable.GradientDrawable().apply { setColor(HomeStyle.WHITE); cornerRadius = 999f; setStroke(dp(2), HomeStyle.LIST_RED) }
                lit -> HomeStyle.pill(HomeStyle.WHITE)
                edit && still && isOn -> android.graphics.drawable.GradientDrawable().apply { cornerRadius = 999f; setStroke(dp(1), 0x66FFFFFF) }
                else -> null
            }
            v.scaleX = if (up) 1.03f else 1f; v.scaleY = v.scaleX
            name.setTextColor(if (lit || up) HomeStyle.BLACK else if (isOn) HomeStyle.WHITE else 0xB3FFFFFF.toInt())
            name.paint.isFakeBoldText = lit || up
            grip?.imageTintList = ColorStateList.valueOf(if (lit || up) 0xFF3A3A3A.toInt() else 0x80FFFFFF.toInt())
        }
        lateinit var render: (Boolean, Int, Boolean) -> Unit // (in On, at index, move the remote there)
        val restyles = mutableListOf<() -> Unit>() // every row's own restyle, for edit mode in and out without a rebuild
        var swallowUp = false // a held OK's release: it clicks nothing
        fun editMode(on: Boolean) { edit = on; picked = -1; restyles.forEach { it() }; jiggle() } // rows settle (150 ms) as they stop
        fun row(title: String, isOn: Boolean, index: Int): View {
            val name = text(HomeStyle.rowName(this, title), BODY).apply { maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END }
            val grip = if (isOn) ImageView(this).apply { setImageResource(R.drawable.ic_grip) } else null
            val v = LinearLayout(this).apply {
                gravity = Gravity.CENTER_VERTICAL
                isFocusable = true
                setPadding(dp(12), 0, dp(14), 0)
                addView(name, LinearLayout.LayoutParams(0, -2, 1f))
                grip?.let { addView(it, LinearLayout.LayoutParams(dp(14), dp(14))) }
            }
            fun restyle() = style(v, name, grip, v.isFocused, isOn, isOn && index == picked)
            restyles += ::restyle
            v.setOnFocusChangeListener { _, has -> restyle(); if (has) hint.text = getString(R.string.hint_customize) }
            v.setOnClickListener {
                when {
                    isOn && edit -> { picked = if (picked == index) -1 else index; restyle(); jiggle() } // pick it up / drop it
                    isOn -> { // off: out of the order, into Off
                        HomeStyle.setRowOn(this, page(), title, false)
                        onRows.removeAt(index); offRows = (offRows + title).sortedBy { HomeStyle.rowName(this, it).lowercase() }; save()
                        render(true, index, true)
                    }
                    else -> { // on: at the bottom of On
                        HomeStyle.setRowOn(this, page(), title, true); Settings.unhideRow(this, title)
                        onRows.add(title); offRows = offRows - title; save()
                        render(false, index, true)
                    }
                }
            }
            v.setOnKeyListener { _, code, event ->
                val confirm = code == android.view.KeyEvent.KEYCODE_DPAD_CENTER || code == android.view.KeyEvent.KEYCODE_ENTER
                if (event.action == android.view.KeyEvent.ACTION_UP && confirm && swallowUp) { swallowUp = false; v.isPressed = false; return@setOnKeyListener true }
                if (event.action != android.view.KeyEvent.ACTION_DOWN) return@setOnKeyListener false
                // Hold OK: OK repeating (the remote held) toggles edit mode once; the lists are rebuilt, so the key's
                // release lands on a fresh row and clicks nothing.
                if (confirm && event.repeatCount > 0) {
                    swallowUp = true // in either column, a held OK's release is no click
                    if (isOn && event.repeatCount == 1) editMode(!edit)
                    return@setOnKeyListener true
                }
                val list = if (isOn) onRows else offRows
                when (code) {
                    android.view.KeyEvent.KEYCODE_DPAD_RIGHT -> {
                        if (isOn && !edit) offViews.getOrNull(index.coerceAtMost(offViews.size - 1))?.requestFocus()
                        true
                    }
                    android.view.KeyEvent.KEYCODE_DPAD_LEFT -> {
                        if (!isOn) onViews.getOrNull(index.coerceAtMost(onViews.size - 1))?.requestFocus()
                        !isOn || edit // in On (not editing) Left goes back to the menu, as elsewhere in Settings
                    }
                    android.view.KeyEvent.KEYCODE_DPAD_UP, android.view.KeyEvent.KEYCODE_DPAD_DOWN -> {
                        val up = code == android.view.KeyEvent.KEYCODE_DPAD_UP
                        val j = index + if (up) -1 else 1
                        when {
                            isOn && edit && picked == index -> { // move the picked-up row
                                if (j in onRows.indices) {
                                    onRows[index] = onRows[j].also { onRows[j] = onRows[index] }
                                    picked = j; save(); render(true, j, true)
                                }
                                true
                            }
                            isOn && edit && j < 0 -> true // while rows wobble, the remote stays in the column
                            j > list.lastIndex -> true // the list ends here
                            else -> false
                        }
                    }
                    else -> false
                }
            }
            restyle()
            return v
        }
        render = { inOn, at, refocus ->
            if (refocus) openPill().requestFocus() // focus waits on the panel's own pill while the lists are rebuilt
            stopJiggle()
            onList.removeAllViews(); offList.removeAllViews(); onViews.clear(); offViews.clear(); restyles.clear()
            if (tab < 3) onList.addView(LinearLayout(this).apply { // Continue Watching: always first, can't move or turn off (not on Services)
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(12), 0, dp(14), 0)
                addView(ImageView(context).apply { setImageResource(R.drawable.ic_lock); imageTintList = ColorStateList.valueOf(0x80FFFFFF.toInt()) },
                    LinearLayout.LayoutParams(dp(13), dp(13)))
                addView(text("${getString(R.string.row_continue)}  ·  ${getString(R.string.always_first)}", SMALL, color = 0x99FFFFFF.toInt()).apply {
                    maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END
                }, LinearLayout.LayoutParams(-2, -2).apply { marginStart = dp(10) })
            }, LinearLayout.LayoutParams(-1, dp(34)))
            onRows.forEachIndexed { i, t -> row(t, true, i).also { onViews += it; onList.addView(it, LinearLayout.LayoutParams(-1, dp(34))) } }
            offRows.forEachIndexed { i, t -> row(t, false, i).also { offViews += it; offList.addView(it, LinearLayout.LayoutParams(-1, dp(34))) } }
            // Up from the top of either column goes to the open list's pill.
            listOfNotNull(onViews.firstOrNull(), offViews.firstOrNull()).forEach { it.nextFocusUpId = openPill().id }
            if (refocus) ((if (inOn) onViews else offViews).let { it.getOrNull(at.coerceAtMost(it.size - 1)) }
                ?: (if (inOn) offViews else onViews).firstOrNull())?.requestFocus()
            onScroll.post { jiggle() }
        }
        fun load() {
            val all = HomeStyle.ordered(this, page(), movie, HomeStyle.knownRows(this, page(), movie)) { it }
            onRows = all.filter(::shown).toMutableList()
            offRows = all.filterNot(::shown).sortedBy { HomeStyle.rowName(this, it).lowercase() }
            edit = false; picked = -1
            render(true, 0, false) // the remote stays where it is (a pill, or the menu)
        }
        /** The open pill's content under the pills: General's rows, or the On and Off columns. Focus stays on the pill. */
        fun showTab() {
            edit = false; picked = -1; stopJiggle()
            detailRows.removeViews(1, detailRows.childCount - 1) // everything under the pills
            refresh.clear() // the rows that just went don't redraw on resume
            rowsScroll.scrollTo(0, 0)
            firstDetail = openPill() // Right from the menu comes back to this pill
            if (tab == 0) {
                generalRows()
                detailRows.getChildAt(1)?.nextFocusUpId = tabViews[0].id // Up: General, not the pill nearest the row's middle
            } else {
                detailRows.addView(columns, LinearLayout.LayoutParams(-1, -2))
                load()
            }
        }
        onScroll.setOnScrollChangeListener { _, _, _, _, _ -> if (edit) jiggle() }
        tabViews.forEachIndexed { i, t ->
            t.setOnFocusChangeListener { _, has ->
                if (has && tab != i) { tab = i; movie = i == 1; showTab() }
                if (has) hint.text = getString(if (i == 0) R.string.hint_home_display else R.string.hint_customize)
                styleTabs()
            }
            // OK or Down: the open pill's first row (General's first; else On's first, or Off's when On is empty).
            t.setOnClickListener { (if (tab == 0) detailRows.getChildAt(1) else onViews.firstOrNull() ?: offViews.firstOrNull())?.requestFocus() }
            t.setOnKeyListener { _, code, event ->
                event.action == android.view.KeyEvent.ACTION_DOWN && when (code) {
                    android.view.KeyEvent.KEYCODE_DPAD_DOWN -> t.callOnClick()
                    android.view.KeyEvent.KEYCODE_DPAD_UP -> true // nothing above the pills (Android would pick a menu page and open it)
                    android.view.KeyEvent.KEYCODE_DPAD_RIGHT -> i == tabViews.lastIndex // Services: no diagonal jump into Off
                    else -> false
                }
            }
            tabs.addView(t, LinearLayout.LayoutParams(-2, dp(30)).apply { marginEnd = dp(8) })
        }
        detailRows.addView(tabs, LinearLayout.LayoutParams(-2, -2).apply { marginStart = dp(12); bottomMargin = dp(8) })
        customOwnsLeft = { v -> (v in tabViews && v !== tabViews[0]) || v in offViews || (edit && v in onViews) }
        customBack = {
            when {
                picked >= 0 -> { picked = -1; restyles.forEach { it() }; jiggle(); true } // drop it first
                edit -> { editMode(false); true } // leave edit mode
                else -> false
            }
        }
        customStop = { edit = false; picked = -1; stopJiggle(); restyles.forEach { it() } } // no picked-up style left either
        showTab()
        styleTabs()
    }

    /** The catalog rows hidden from home, movies or shows; OK on one puts it back. */
    private fun unhideRowsMenu(done: () -> Unit) {
        val all = Settings.hiddenRows(this).toList().sortedBy { HomeStyle.rowName(this, it) } // by the name shown; unhidden by its key
        if (all.isEmpty()) return choose(getString(R.string.no_hidden_rows), listOf(getString(R.string.action_ok)), getString(R.string.no_hidden_rows_desc)) {}
        choose(getString(R.string.hidden_rows), all.map { HomeStyle.rowName(this, it) }, getString(R.string.unhide_row_hint)) { i ->
            Settings.unhideRow(this, all[i])
            done()
        }
    }

    private fun actionRow(label: String, value: String, help: String, perProfile: Boolean = false,
                          openOnRight: Boolean = false, click: () -> Unit): TextView {
        val display = text(if (value.isEmpty()) "›" else "$value  ›", BODY, color = TEXT_2)
        val row = settingRow(label, display, help, perProfile, focus = { display.setTextColor(if (it) TUNGSTEN else TEXT_2) }, click = click)
        if (openOnRight) row.clickOnRight()
        return display
    }

    private fun key(c: Choice) = if (c.perProfile) Settings.profileKey(this, c.key) else c.key
    private fun stored(c: Choice): Any = when (val default = c.values[0].first) {
        is Boolean -> prefs.getBoolean(key(c), default)
        is String -> prefs.getString(key(c), default).orEmpty()
        else -> prefs.getInt(key(c), default as Int)
    }
    private fun store(c: Choice, value: Any) = prefs.edit().apply {
        when (value) {
            is Boolean -> putBoolean(key(c), value)
            is String -> putString(key(c), value)
            else -> putInt(key(c), value as Int)
        }
    }.apply().also { if (c.key == "uiScale") { reopenGroup = selectedGroup; main.post { recreate() } } } // the new size shows at once
    private fun choice(key: String) = (streaming + pictureAndSound + advanced).first { it.key == key }

    private fun View.clickOnRight() {
        setOnKeyListener { row, code, event ->
            if (code != android.view.KeyEvent.KEYCODE_DPAD_RIGHT) return@setOnKeyListener false
            if (event.action == android.view.KeyEvent.ACTION_DOWN && event.repeatCount == 0) row.performClick()
            true
        }
    }

    private fun choiceRow(c: Choice) {
        fun current() = c.values.indexOfFirst { it.first == stored(c) }.coerceAtLeast(0)
        // A switch only where the choice is On or Off; "Best source / Show sources" is a choice with words.
        val isSwitch = c.values.map { it.second }.toSet() == setOf(on, off)
        val words = text("", BODY, color = TEXT_2)
        val track = FrameLayout(this).apply { background = rounded(GLASS_EDGE, 9) }
        val knob = View(this)
        if (isSwitch) track.addView(knob)
        val value = if (isSwitch) LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            addView(words, LinearLayout.LayoutParams(-2, -2).apply { marginEnd = dp(8) })
            addView(track, LinearLayout.LayoutParams(dp(32), dp(17)))
        } else words
        var focused = false
        fun draw() {
            val index = current()
            val on = isSwitch && (c.values[index].first == true || c.values[index].second == this@SettingsActivity.on)
            words.setTextColor(when {
                !focused -> TEXT_2
                isSwitch -> TEXT
                else -> TUNGSTEN
            })
            words.text = if (isSwitch) c.values[index].second else c.values[index].second + "  ›"
            if (isSwitch) {
                track.background = rounded(if (on) TUNGSTEN else GLASS_EDGE, 9)
                knob.background = rounded(if (on) BASE else TEXT_2, 7)
                knob.layoutParams = FrameLayout.LayoutParams(dp(13), dp(13),
                    Gravity.CENTER_VERTICAL or (if (on) Gravity.END else Gravity.START)).apply {
                    leftMargin = dp(2); rightMargin = dp(2)
                }
            }
        }
        fun select(index: Int) {
            val selected = c.values[index].first
            if (selected == stored(c)) return
            store(c, selected)
            refresh.forEach { it() }
        }
        val row = settingRow(c.label, value, c.hint, c.perProfile, focus = { has -> focused = has; draw() }) {
            select(nextSettingIndex(current(), c.values.size))
        }
        row.tag = c.key
        row.clickOnRight()
        refresh += ::draw
        draw()
    }

    /** A language for this profile, picked from a list. */
    private fun languageRow(key: String, label: String, help: String, emptyLabel: Int = R.string.tv_language) {
        val codes = listOf("") + Languages.all
        // Audio language was once shared by every profile: that pick stands until this profile makes its own.
        fun current() = if (key == "audioFallback") Settings.fallbackAudio(this)
            else prefs.getString(Settings.profileKey(this, key), if (key == "audioLang") prefs.getString(key, "") else "").orEmpty()
        fun name(code: String) = if (code.isEmpty()) getString(emptyLabel) else Languages.name(code)
        val value = text("", BODY, color = TEXT_2)
        fun draw() { value.text = name(current()) + "  ›" }
        settingRow(label, value, help, perProfile = true, focus = { value.setTextColor(if (it) TUNGSTEN else TEXT_2) }) {
            choose(label, codes.map(::name), checked = codes.indexOf(current()).coerceAtLeast(0)) { i ->
                prefs.edit().putString(Settings.profileKey(this, key), codes[i]).apply()
                refresh.forEach { it() }
            }
        }.clickOnRight()
        refresh += ::draw
        draw()
    }

    private fun presetRow() {
        val value = text("", BODY, color = TEXT_2)
        fun current() = PRESETS.indexOfFirst { (_, rows) -> rows.all { (key, v) -> stored(choice(key)) == v } }
        fun draw() { value.text = getString(PRESETS.getOrNull(current())?.first ?: R.string.custom) + "  ›" }
        val label = getString(R.string.source_choice)
        settingRow(label, value, getString(R.string.source_choice_hint),
            focus = { value.setTextColor(if (it) TUNGSTEN else TEXT_2) }) {
            val next = nextSettingIndex(current(), PRESETS.size)
            PRESETS[next].second.forEach { (key, v) -> store(choice(key), v) }
            refresh.forEach { it() }
        }.clickOnRight()
        refresh += ::draw
        draw()
    }

    /**
     * The language Alder shows, picked here so TVs without Android 13's per-app language page (Fire OS, Android 8–12)
     * can change it too. Each language is named in its own words, so anyone can find theirs.
     */
    private fun appLanguageRow() {
        val current = Settings.appLanguage(this)
        val system = android.content.res.Resources.getSystem().configuration
        @Suppress("DEPRECATION") val tv = if (android.os.Build.VERSION.SDK_INT >= 24) system.locales[0] else system.locale
        val names = listOf("" to getString(R.string.tv_language_named, tv.getDisplayLanguage(tv).replaceFirstChar { it.titlecase(tv) })) +
            APP_LANGUAGES.map { tag -> tag to Locale.forLanguageTag(tag).let { it.getDisplayName(it).replaceFirstChar { c -> c.titlecase(it) } } }
        // "es-MX" picked on Android's own page is Spanish here.
        val at = names.indexOfFirst { it.first == current }.takeIf { it >= 0 }
            ?: names.indexOfFirst { it.first.isNotEmpty() && it.first.substringBefore('-') == current.substringBefore('-') }.coerceAtLeast(0)
        actionRow(getString(R.string.app_language), names[at].second, getString(R.string.app_language_hint), openOnRight = true) {
            choose(getString(R.string.app_language), names.map { it.second }, checked = at) { i ->
                val picked = names[i].first
                if (picked == current) return@choose
                Settings.setAppLanguage(this, picked)
                // Android 13 and later redraw every screen in the new language by themselves; before that, start over.
                if (android.os.Build.VERSION.SDK_INT < 33) {
                    finishAffinity()
                    packageManager.getLaunchIntentForPackage(packageName)?.let(::startActivity)
                }
            }
        }
    }

    private companion object {
        const val UNLOCK_TO_CLEAR = 7
        /** The languages Alder has words for, besides English; res/xml/locales_config.xml lists the same. */
        val APP_LANGUAGES = listOf("en", "es", "pt-BR", "fr", "de", "it", "hi", "ar")
        val PRESETS = listOf(
            R.string.preset_addon_order to mapOf("release" to "", "hdrPref" to "", "maxHeight" to 0, "maxSizeGb" to 0),
            R.string.preset_best_picture to mapOf("release" to "remux", "hdrPref" to "dv", "maxHeight" to 0, "maxSizeGb" to 0),
            R.string.preset_balanced to mapOf("release" to "encode", "hdrPref" to "", "maxHeight" to 0, "maxSizeGb" to 20),
            R.string.preset_fast_start to mapOf("release" to "web", "hdrPref" to "", "maxHeight" to 1080, "maxSizeGb" to 10),
        )
    }

    @Suppress("DEPRECATION")
    private fun askPinToClear() =
        startActivityForResult(Intent(this, ProfilesActivity::class.java).putExtra(ProfilesActivity.UNLOCK, true), UNLOCK_TO_CLEAR)

    private fun clearStorage() = (getSystemService(ACTIVITY_SERVICE) as ActivityManager).clearApplicationUserData()

    @Deprecated("Activity result callback")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        @Suppress("DEPRECATION")
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == UNLOCK_TO_CLEAR && resultCode == RESULT_OK) clearStorage()
    }

}
