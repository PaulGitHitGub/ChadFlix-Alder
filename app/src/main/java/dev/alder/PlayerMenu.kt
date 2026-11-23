package dev.alder

import android.view.KeyEvent
import androidx.media3.common.C
import androidx.media3.common.MimeTypes

// ChadFlix: what the player's Audio & Subtitles menu and Info pop-up decide, kept apart from the views so it can be tested.

/** One audio or subtitle track as the menu sees it. [plain]: not forced, SDH or commentary. [off]: the Subtitles' Off row. */
internal data class MenuTrack(
    val id: String,
    val language: String?,
    val selected: Boolean = false,
    val plain: Boolean = true,
    val addon: Boolean = false,
    val off: Boolean = false,
    /** Audio only: the format Alder itself would pick for this language (see [preferredAudioFormats]). */
    val preferred: Boolean = false,
)

/**
 * The rows a column shows before "More": Off, the track in use, the best plain English track, and the best plain track
 * in the title's [originalLanguage] (null: unknown, so only English and the track in use). "English" is literal, not the
 * TV's language. The best track of a language is a plain one, then the one Alder would play ([MenuTrack.preferred]), from the file before an add-on, then in file order; a
 * language with no plain track gives its first. A language never shows twice: when the track in use is of that language
 * it is the one shown. A track with no language shows only when it is in use. Every track not listed here is behind More.
 */
internal fun defaultTrackIds(tracks: List<MenuTrack>, originalLanguage: String?): Set<String> {
    val shown = HashSet<String>()
    tracks.forEach { if (it.off || it.selected) shown += it.id }
    fun best(language: String?) = language?.let { code ->
        if (tracks.any { it.id in shown && !it.off && Languages.match(it.language, code) > 0 }) null
        else tracks.filter { !it.off && Languages.match(it.language, code) > 0 }.sortedWith(compareBy({ !it.plain }, { !it.preferred }, { it.addon })).firstOrNull()
    }
    best("en")?.let { shown += it.id }
    best(originalLanguage)?.let { shown += it.id }
    return shown
}

// Whole words only: a letter on either side ("Atmosphere", "Accessible") never counts, but "_", "." and digits do ("TrueHD_Atmos_7.1").
private fun word(re: String) = Regex("(?<![a-z])(?:$re)(?![a-z])", RegexOption.IGNORE_CASE)

// A role word with "non", "no" or "not" before it ("Non-Forced", "No SDH", "not forced") says the track is not that kind.
private fun roleLabel(re: String) = Regex("(?<![a-z])(?<!\\b(?:no|non|not)[-_. ])(?:$re)(?![a-z])", RegexOption.IGNORE_CASE)

private val LABEL_COMMENTARY = roleLabel("commentary")
private val LABEL_DESCRIPTIVE = roleLabel("descriptive|descriptions?|audio[-_. ]?descriptions?")
private val LABEL_SDH = roleLabel("sdh|cc|hoh|hearing[-_. ]?impaired|(?:closed[-_. ]?)?captions?")
private val LABEL_FORCED = roleLabel("forced")
private val ODD_LABEL = roleLabel("sdh|cc|hoh|hearing|forced|commentary|description|descriptive|captions?")

/** A track that is not forced-only, for the deaf and hard of hearing, a commentary or a description for the blind. */
internal fun plainTrack(selectionFlags: Int, roleFlags: Int, label: String?): Boolean {
    if (selectionFlags and C.SELECTION_FLAG_FORCED != 0) return false
    val odd = C.ROLE_FLAG_COMMENTARY or C.ROLE_FLAG_DESCRIBES_MUSIC_AND_SOUND or C.ROLE_FLAG_DESCRIBES_VIDEO or C.ROLE_FLAG_CAPTION
    return roleFlags and odd == 0 && !ODD_LABEL.containsMatchIn(label.orEmpty())
}

/** When the title ends in wall-clock ms, or null for a trailer, live TV or a length not known. */
internal fun endsAtTime(now: Long, duration: Long, position: Long, speed: Float, trailer: Boolean, live: Boolean): Long? =
    if (trailer || live || duration <= 0) null else now + ((duration - position).coerceAtLeast(0) / speed).toLong()

/** "2 hr 52 mins"; null for no length. The screen passes its words. */
internal fun runtimeWords(ms: Long, hoursMinutes: String = "%1\$d hr %2\$d mins", hours: String = "%1\$d hr",
                          minutes: String = "%1\$d mins"): String? {
    val total = (ms / 60_000).toInt()
    return when {
        total <= 0 -> null
        total < 60 -> minutes.format(total)
        total % 60 == 0 -> hours.format(total / 60)
        else -> hoursMinutes.format(total / 60, total % 60)
    }
}

/** The Info pop-up's facts line, "2026 • R • 2 hr 52 mins": the year, the content rating and the length, whichever are known. */
internal fun infoFacts(year: String?, certificate: String?, runtime: String?): String =
    listOfNotNull(year, certificate, runtime).map { it.trim() }.filter { it.isNotEmpty() }.joinToString(" • ")

/** The Info pop-up's rows, top to bottom: the logo (or name) is always there, the others only when there is something to show. */
internal enum class InfoRow { LOGO, FACTS, PLOT, RATINGS, BADGES }

internal const val INFO_META_H = 20 // dp, a ratings or badges row
internal const val INFO_BADGE_GAP = 8 // dp between badges
internal const val INFO_BOTTOM = 183 // dp from the screen's bottom: 48 above the seek bar's top (SEEK_BAR_BOTTOM + SEEK_BAR_H)

/**
 * dp between a row and the row above it that is showing, on a 4 / 8 / 12 / 16 dp scale: the logo then the facts 12, the facts
 * and plot one block 8, a 16 break before the ratings and badges, which sit 8 apart as one group. Asked of the row above that
 * is actually there, so a missing row (no ratings, no plot) leaves no gap behind it.
 */
internal fun infoGap(above: InfoRow, row: InfoRow): Int = when {
    above == InfoRow.LOGO -> 12
    row == InfoRow.BADGES && above == InfoRow.RATINGS -> 8
    row == InfoRow.RATINGS || row == InfoRow.BADGES -> 16
    else -> 8
}

/** The number in "Track N": the track's place among the tracks of its column (1 for the first), never the file's own id ("2", "aac:Main", "1/257"). */
internal fun trackNumber(position: Int): String = (position + 1).toString()

/** A role the viewer may care about. Only the fixed role words ever show, never the file's own label text. */
internal enum class TrackRole { COMMENTARY, DESCRIPTIVE, FORCED, SDH }

/**
 * The role of a track, from its [selectionFlags] and [roleFlags], else from whole role words in its [label]: Matroska does not
 * report the commentary, hearing impaired or visual impaired flags to Media3. Audio can be a commentary or a description;
 * subtitles forced or SDH. Only the role comes out of the label, none of its text.
 */
internal fun trackRole(audio: Boolean, selectionFlags: Int, roleFlags: Int, label: String? = null): TrackRole? = when {
    audio && roleFlags and C.ROLE_FLAG_COMMENTARY != 0 -> TrackRole.COMMENTARY
    audio && roleFlags and C.ROLE_FLAG_DESCRIBES_VIDEO != 0 -> TrackRole.DESCRIPTIVE
    !audio && selectionFlags and C.SELECTION_FLAG_FORCED != 0 -> TrackRole.FORCED
    !audio && roleFlags and (C.ROLE_FLAG_CAPTION or C.ROLE_FLAG_DESCRIBES_MUSIC_AND_SOUND) != 0 -> TrackRole.SDH
    label == null -> null
    audio && LABEL_COMMENTARY.containsMatchIn(label) -> TrackRole.COMMENTARY
    audio && LABEL_DESCRIPTIVE.containsMatchIn(label) -> TrackRole.DESCRIPTIVE
    !audio && LABEL_FORCED.containsMatchIn(label) -> TrackRole.FORCED
    !audio && LABEL_SDH.containsMatchIn(label) -> TrackRole.SDH
    else -> null
}

/**
 * The width of the Audio or Subtitles column in dp: its widest row ([widestRowDp]: a name and, after it, a role word or an audio format,
 * measured with the row's own paint) plus [LANGUAGE_GAP_DP], so the white space from a column's widest row to the next column's text is the
 * same for Audio and for Subtitles, whatever the rows are ("English SDH" no longer pushes Appearance away). Never narrower than
 * [LANGUAGE_MIN_DP] and never wider than [LANGUAGE_MAX_DP] (a longer row is cut with an ellipsis).
 */
internal fun languageColumnDp(widestRowDp: Int): Int = (widestRowDp + LANGUAGE_GAP_DP).coerceIn(LANGUAGE_MIN_DP, LANGUAGE_MAX_DP)
internal const val LANGUAGE_GAP_DP = 118 // dp from a column's widest row to the next column's text; "English" (52) makes the 170 the menu was approved at
internal const val LANGUAGE_MIN_DP = 140
internal const val LANGUAGE_MAX_DP = 250

/** Alder's own choices for the subtitle look, in the order the Appearance column steps through them. */
internal val SUBTITLE_SIZES = listOf(80, 100, 130, 160) // percent of Media3's default size: Small, Medium, Large, Extra Large
internal val SUBTITLE_COLORS = listOf("white", "gray", "yellow", "cyan", "cream", "warm_white")
internal val SUBTITLE_BACKGROUNDS = listOf(true, false) // On, Off

/** The place of [current] in [choices]; a value that is no choice (an old setting) counts as the first. */
internal fun <T> choiceIndex(choices: List<T>, current: T): Int = choices.indexOf(current).coerceAtLeast(0)

/** One step along [count] choices from [index], wrapping at both ends: after the last comes the first, so both arrows always work. */
internal fun steppedIndex(index: Int, delta: Int, count: Int): Int = if (count <= 0) 0 else Math.floorMod(index + delta, count)

/** What a key does on an Appearance row. */
internal enum class AppearanceKey { NEXT_VALUE, TO_SUBTITLES, UP, DOWN, TO_BUTTON, NOTHING }

/**
 * OK or Right steps to the next value, wrapping; Left goes back to the checked subtitle, as it does from Subtitles to Audio. In a right-to-left
 * layout the arrows mirror: Left steps and Right goes back. A held key ([repeat] above 0) never steps or goes back, so the Right that brought
 * focus here does not go on to change a value. Up and Down move between the rows; Up on the first row stays there, Down on the last goes to the
 * button. Null: a key an Appearance row does not use.
 */
internal fun appearanceKeyAction(code: Int, rtl: Boolean, index: Int, lastIndex: Int, repeat: Int): AppearanceKey? {
    val forward = if (rtl) KeyEvent.KEYCODE_DPAD_LEFT else KeyEvent.KEYCODE_DPAD_RIGHT
    val back = if (rtl) KeyEvent.KEYCODE_DPAD_RIGHT else KeyEvent.KEYCODE_DPAD_LEFT
    return when (code) {
        KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER, forward ->
            if (repeat > 0) AppearanceKey.NOTHING else AppearanceKey.NEXT_VALUE
        back -> if (repeat > 0) AppearanceKey.NOTHING else AppearanceKey.TO_SUBTITLES
        KeyEvent.KEYCODE_DPAD_UP -> if (index > 0) AppearanceKey.UP else AppearanceKey.NOTHING
        KeyEvent.KEYCODE_DPAD_DOWN -> if (index < lastIndex) AppearanceKey.DOWN else AppearanceKey.TO_BUTTON
        else -> null
    }
}

// One Appearance row, from its left edge in dp: [APPEARANCE_INDENT] (the other columns' text starts here), the label cell (as wide as the widest of the
// three labels), a fixed gap, then the value and one "›" right after it. Every row has the same label cell, so the values start at one x; the Color
// row's dot hangs in the gap before its value. Nothing floats at the far end: the column ends [APPEARANCE_END] after the widest value and its arrow.
internal const val APPEARANCE_INDENT = 42 // 10 row margin + 32: where a name starts in every column
internal const val APPEARANCE_LABEL_GAP = 24 // dp from the widest label to where the values start
internal const val APPEARANCE_CHEVRON_GAP = 2 // dp between a value and its arrow
internal const val APPEARANCE_END = 22 // dp after the arrow: 12 row padding + 10 row margin
internal const val APPEARANCE_MAX = 340 // the three columns must fit one screen

/** The width of the Appearance column in dp: indent, widest label, gap, the widest value of all rows, its arrow and the end. At most [APPEARANCE_MAX]. */
internal fun appearanceColumnDp(widestLabelDp: Int, widestValueDp: Int, chevronDp: Int = 16): Int =
    (APPEARANCE_INDENT + widestLabelDp + APPEARANCE_LABEL_GAP + widestValueDp + APPEARANCE_CHEVRON_GAP + chevronDp + APPEARANCE_END)
        .coerceAtMost(APPEARANCE_MAX)

/** What the label cell gets of a column [columnDp] wide, so a long translation shortens the label and not the values. */
internal fun appearanceLabelDp(columnDp: Int, widestValueDp: Int, chevronDp: Int = 16): Int =
    columnDp - APPEARANCE_INDENT - APPEARANCE_LABEL_GAP - widestValueDp - APPEARANCE_CHEVRON_GAP - chevronDp - APPEARANCE_END

/**
 * The menu's dim over the picture: black at 0.6 (0x99); over HDR video 0.9 (0xE6). The menu is drawn at SDR white, which on an HDR
 * picture is no brighter than the highlights behind it, and 0.6 leaves those at 40% of their light: the grey text (#C4C4C8) sits on a
 * backdrop as bright as itself (contrast 1.05 measured on the owner's photo). At 0.9 a highlight keeps 10%, so the text has about 3:1
 * against the picture before its shadow, and more with it.
 */
internal fun menuDimColor(hdr: Boolean): Int = if (hdr) 0xE6000000.toInt() else 0x99000000.toInt()

/**
 * Where the sample subtitle's bottom edge sits, in px from the top: 280 of a 1080 px screen, but never lower than [gapPx] above the
 * menu's top ([menuTopPx]), so a tall menu (many tracks) pushes it up and it never touches the menu.
 */
internal fun sampleBottomPx(screenPx: Int, menuTopPx: Int, gapPx: Int): Int =
    minOf(screenPx * 280 / 1080, menuTopPx - gapPx).coerceAtLeast(0)

/**
 * An audio track's codec and channels for under More: "Dolby TrueHD Atmos 7.1". Matroska reports Atmos as plain TrueHD or E-AC-3
 * and DTS:X and DTS-HD MA as DTS, so those three words (and only those) are taken from the file's [label] when it has them as
 * whole words. Nothing else from the label ever shows, so "BTM TrueHD Atmos 7.1" gives no "BTM".
 */
internal fun audioFormatText(codec: String?, mime: String?, label: String?, channels: String?): String {
    fun has(re: String) = label != null && word(re).containsMatchIn(label)
    val dts = mime == MimeTypes.AUDIO_DTS || mime == MimeTypes.AUDIO_DTS_HD
    val named = when {
        codec == null -> null
        (mime == MimeTypes.AUDIO_E_AC3 || mime == MimeTypes.AUDIO_TRUEHD) && has("atmos") -> "$codec Atmos"
        dts && has("dts[-_.: ]?x(?!26[45])") -> "DTS:X"
        dts && has("dts[-_.: ]?hd[-_.: ]?(?:ma|master[-_.: ]?audio)") -> "DTS-HD MA"
        else -> codec
    }
    return listOfNotNull(named, channels).joinToString(" ")
}

/**
 * What follows a track's name on its row. Before More nothing: a row is only the language (or "Track N"). Under More the [role] word
 * ("Commentary", "Forced", "SDH") and an audio track's [format]: "Commentary · Dolby Digital 5.1". The file's own label (where uploaders put
 * release group names) never shows.
 */
internal fun rowDetail(expanded: Boolean, role: String, format: String): String =
    if (!expanded) "" else listOf(role, format).filter { it.isNotEmpty() }.joinToString(" · ")

internal const val SEEK_BAR_BOTTOM = 111 // dp from the screen's bottom to the seek bar
internal const val SEEK_BAR_H = 24 // dp, the seek bar's height
