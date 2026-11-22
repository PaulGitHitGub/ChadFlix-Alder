package dev.alder

import android.content.Context
import android.graphics.Outline
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.InsetDrawable
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.ViewOutlineProvider
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView

/**
 * ChadFlix fork: the home page's look (MainActivity's top bar, rows, cards and info line), in one file so merging
 * upstream Alder stays easy. Only the home page uses these; Screen.kt's shared tokens are left alone. Sizes are dp,
 * ChadFlix's 1080p pixels halved (220 x 330 px posters are 110 x 165 dp).
 */
internal object HomeStyle {
    const val BLACK = 0xFF000000.toInt()
    const val WHITE = 0xFFFFFFFF.toInt()
    const val PLOT = 0xFFCFCFCF.toInt()
    const val NOTE = 0xFFDDDDDD.toInt()
    const val PLACEHOLDER = 0xFF1A1A1A.toInt()
    const val AVATAR = 0xFF2A2A2A.toInt()
    const val OPEN_TAB = 0x33FFFFFF // the open page's pill while the remote is on another item in the top bar
    const val RED = 0xFFE50914.toInt()
    const val TRACK = 0xFF4A4A4A.toInt()

    const val CARD_W = 110
    const val CARD_H = 165
    const val CARD_GAP = 8
    const val CARD_RADIUS = 3.5f
    const val RING = 2.5f // the focused card's white border
    const val CONTINUE_UNDER = 28 // under a Continue Watching poster: the red bar, then the time left
    const val ROWS_TOP = 70 // the first row's title, under the top bar
    const val INFO_GAP = 14 // between the info line and the next row's title
    const val ROW_TITLE = 17f // sp
    const val INFO = 13.5f // sp, the "Genre • Year • Runtime • Rating" line
    const val NOTE_SP = 12f
    const val ROW_DIM = 0.62f // rows the remote isn't in sit at about 38% brightness
    const val SEP = "  •  "

    private val small = setOf("a", "an", "and", "as", "at", "by", "for", "in", "of", "on", "or", "the", "to", "with")

    /** "Continue watching" → "Continue Watching", "Movies for you" → "Movies for You"; words already capitalised stay. */
    fun titleCase(s: String): String = s.split(" ").mapIndexed { i, w ->
        if (i > 0 && w.lowercase() in small) w.lowercase() else w.replaceFirstChar { it.uppercaseChar() }
    }.joinToString(" ")

    private fun px(c: Context, dp: Float) = dp * c.resources.displayMetrics.density

    /** A top bar pill: white for the focused item (black words on it), a faint one for the open page. */
    fun pill(color: Int) = GradientDrawable().apply { setColor(color); cornerRadius = 999f }

    /**
     * A home poster card: the poster sits on top, rounded on its own, so the space under it ([under] px, Continue
     * Watching's bar and time left) is outside the poster's rounding and placeholder.
     */
    fun posterCard(card: FrameLayout, face: View, under: Int) {
        val c = card.context
        val r = px(c, CARD_RADIUS)
        card.background = InsetDrawable(GradientDrawable().apply { setColor(PLACEHOLDER); cornerRadius = r }, 0, 0, 0, under)
        card.clipToOutline = false
        face.outlineProvider = object : ViewOutlineProvider() {
            override fun getOutline(view: View, outline: Outline) = outline.setRoundRect(0, 0, view.width, view.height, r)
        }
        face.clipToOutline = true
    }

    /** The focused card's white border, drawn over the poster. */
    fun ring(c: Context) = View(c).apply {
        background = GradientDrawable().apply {
            cornerRadius = px(c, CARD_RADIUS)
            setStroke(px(c, RING).toInt().coerceAtLeast(2), WHITE)
        }
        visibility = View.INVISIBLE // INVISIBLE, not GONE: showing it then needs no layout pass
    }

    /**
     * Under a Continue Watching poster: a red watched-so-far bar on a grey track, then "1h 10m left" or "S2 E4". The views
     * come back with their layout set; the card adds them.
     */
    fun under(screen: Screen, card: FrameLayout, posterH: Int, progress: Float, note: String?): List<View> {
        val c = card.context
        val out = mutableListOf<View>()
        if (progress > 0f) out += LinearLayout(c).apply {
            val p = progress.coerceIn(0f, 1f)
            setBackgroundColor(TRACK)
            addView(View(c).apply { setBackgroundColor(RED) }, LinearLayout.LayoutParams(0, -1, p))
            addView(View(c), LinearLayout.LayoutParams(0, -1, 1f - p))
            layoutParams = FrameLayout.LayoutParams(-1, px(c, RING).toInt().coerceAtLeast(2), Gravity.TOP).apply { topMargin = posterH + px(c, 5f).toInt() }
        }
        if (!note.isNullOrEmpty()) out += screen.text(note, NOTE_SP, bold = true, color = NOTE).apply {
            setSingleLine()
            ellipsize = TextUtils.TruncateAt.END
            includeFontPadding = false
            layoutParams = FrameLayout.LayoutParams(-1, -2, Gravity.TOP).apply {
                topMargin = posterH + px(c, 11f).toInt()
                leftMargin = px(c, 2f).toInt()
            }
        }
        return out
    }

    /** A title's runtime as Alder writes it ("2h 07m", "47 min") in milliseconds; 0 when it has none. */
    fun runtimeMs(text: String?): Long {
        val t = text?.trim() ?: return 0
        val minutes = Regex("""^(\d+)\s*h\s*(\d+)\s*m""").find(t)?.let { it.groupValues[1].toLong() * 60 + it.groupValues[2].toLong() }
            ?: Regex("""^(\d+)\s*min""").find(t)?.let { it.groupValues[1].toLong() }
            ?: Regex("""^(\d+)\s*h""").find(t)?.let { it.groupValues[1].toLong() * 60 }
            ?: return 0
        return minutes * 60_000
    }

    /** The IMDb and Letterboxd tags in white and grey instead of Alder's warm tungsten. */
    fun plainRatings(row: LinearLayout) {
        for (i in 0 until row.childCount) (row.getChildAt(i) as? TextView)?.let { t ->
            t.setTextColor(WHITE)
            if (t.background != null) t.background = GradientDrawable().apply {
                cornerRadius = px(t.context, 3f)
                setStroke(px(t.context, 1f).toInt().coerceAtLeast(1), 0x80FFFFFF.toInt())
            }
        }
    }

    // ---------- row order (Move Up / Move Down in a row's hold-OK menu), per profile and page ----------

    private fun key(c: Context, page: String) = Settings.profileKey(c, "rowOrder:$page")
    private fun prefs(c: Context) = c.getSharedPreferences("settings", Context.MODE_PRIVATE)

    fun rowOrder(c: Context, page: String): List<String> =
        runCatching { org.json.JSONArray(prefs(c).getString(key(c, page), "[]")).strings() }.getOrDefault(emptyList())

    /** Moves [title] [by] places among the page's [shown] rows and keeps that order. */
    fun moveRow(c: Context, page: String, shown: List<String>, title: String, by: Int) =
        prefs(c).edit().putString(key(c, page), org.json.JSONArray(movedRow(shown, title, by)).toString()).apply()

    // ---------- genre rows from TMDb (Movies and Shows), and which rows are on, per profile and page ----------

    const val TMDB = "tmdb:" // a genre row's source in its section: "tmdb:<TMDb genre id>:<list>"

    // TMDb's genre ids (stable) with English names; the first 10 (films) and 9 (shows) are on until Customize Home says otherwise.
    private val movieGenres = listOf(28 to "Action", 35 to "Comedy", 18 to "Drama", 53 to "Thriller", 27 to "Horror",
        878 to "Science Fiction", 16 to "Animation", 80 to "Crime", 10749 to "Romance", 99 to "Documentary",
        12 to "Adventure", 10751 to "Family", 14 to "Fantasy", 36 to "History", 10402 to "Music", 9648 to "Mystery",
        10770 to "TV Movie", 10752 to "War", 37 to "Western")
    private val showGenres = listOf(18 to "Drama", 35 to "Comedy", 80 to "Crime", 10759 to "Action & Adventure",
        10765 to "Sci-Fi & Fantasy", 16 to "Animation", 9648 to "Mystery", 99 to "Documentary", 10764 to "Reality",
        10751 to "Family", 10762 to "Kids", 10763 to "News", 10766 to "Soap", 10767 to "Talk", 10768 to "War & Politics",
        37 to "Western")

    fun genres(movie: Boolean) = if (movie) movieGenres else showGenres

    private fun onByDefault(title: String, movie: Boolean) = genres(movie).take(if (movie) 10 else 9).any { it.second == title }

    /** This page's on/off choices by row title; a row never chosen is on only if it's a default genre. */
    fun rowsOn(c: Context, page: String): org.json.JSONObject =
        runCatching { org.json.JSONObject(prefs(c).getString(Settings.profileKey(c, "rowsOn:$page"), null)!!) }.getOrDefault(org.json.JSONObject())

    fun isOn(state: org.json.JSONObject, title: String, movie: Boolean) =
        if (state.has(title)) state.optBoolean(title) else onByDefault(title, movie)

    /** What the page's rows depend on in Customize Home, to tell when coming back that they changed. */
    fun signature(c: Context, page: String) = rowsOn(c, page).toString() + rowOrder(c, page)

    fun setRowOn(c: Context, page: String, title: String, on: Boolean) =
        prefs(c).edit().putString(Settings.profileKey(c, "rowsOn:$page"), rowsOn(c, page).put(title, on).toString()).apply()

    /** Every row the page could show, on or off, for Customize Home; kept when the page is built. */
    fun rememberRows(c: Context, page: String, titles: List<String>) =
        prefs(c).edit().putString(Settings.profileKey(c, "rowsKnown:$page"), org.json.JSONArray(titles).toString()).apply()

    fun knownRows(c: Context, page: String, movie: Boolean): List<String> =
        runCatching { org.json.JSONArray(prefs(c).getString(Settings.profileKey(c, "rowsKnown:$page"), null)!!).strings() }
            .getOrNull() ?: genres(movie).map { it.second }

    /** What genre [id]'s row shows on [page] ("trending" until the Showing tile picks another list). */
    fun showing(c: Context, page: String, id: Int): String =
        prefs(c).getString(Settings.profileKey(c, "showing:$page:$id"), "trending").orEmpty()

    fun genreSource(c: Context, page: String, id: Int) = "$TMDB$id:${showing(c, page, id)}"

    fun setShowing(c: Context, page: String, id: Int, list: String) =
        prefs(c).edit().putString(Settings.profileKey(c, "showing:$page:$id"), list).apply()

    /** The lists a genre row can show, the default first: key to its name. */
    fun lists(movie: Boolean): List<Pair<String, Int>> = if (movie) listOf("trending" to R.string.showing_trending,
        "new" to R.string.showing_new_releases, "blockbusters" to R.string.showing_blockbusters,
        "most_watched" to R.string.showing_most_watched, "top_rated" to R.string.showing_top_rated)
    else listOf("trending" to R.string.showing_trending, "new" to R.string.showing_new_series,
        "most_watched" to R.string.showing_most_watched, "top_rated" to R.string.showing_top_rated,
        "complete" to R.string.showing_complete)

    private const val TILE = "chadflix:showing:"

    /** A genre row's first card, the Showing tile: it opens the list picker instead of a title. */
    fun isTile(id: String) = id.startsWith(TILE)

    /**
     * A genre row's titles, one TMDb page, after its Showing tile (a card with no picture: "Showing" over the list's
     * name). Blocks: run it off the main thread.
     */
    fun genreTitles(c: Context, movie: Boolean, source: String): List<Meta> {
        val parts = source.removePrefix(TMDB).split(":")
        val list = lists(movie).firstOrNull { it.first == parts.getOrElse(1) { "" } } ?: lists(movie)[0]
        val tile = Meta(org.json.JSONObject().put("id", TILE + parts[0]).put("type", if (movie) "movie" else "series")
            .put("name", c.getString(R.string.showing) + "\n" + c.getString(list.second)))
        val day = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US)
        val since = java.util.Calendar.getInstance().apply { add(java.util.Calendar.MONTH, if (movie) -6 else -12) }.time
        return listOf(tile) + Tmdb.discoverRow(movie, genreParams(movie, parts[0].toInt(), parts.getOrElse(1) { "trending" },
            day.format(java.util.Date()), day.format(since)), genres(movie).toMap())
    }
}

/** [items] in the [saved] order of their titles; rows it doesn't name (a new add-on's) follow in their own order. */
internal fun <T> orderedRows(saved: List<String>, items: List<T>, title: (T) -> String): List<T> =
    if (saved.isEmpty()) items else items.sortedBy { saved.indexOf(title(it)).let { i -> if (i < 0) Int.MAX_VALUE else i } }

internal fun movedRow(titles: List<String>, title: String, by: Int): List<String> {
    val from = titles.indexOf(title)
    val to = from + by
    if (from < 0 || to !in titles.indices) return titles
    return titles.toMutableList().apply { add(to, removeAt(from)) }
}

/**
 * TMDb discover filters for a genre row's list: Trending (popular and recent), New Releases / New Series, Blockbusters,
 * Most Watched, Top Rated, Complete Series. [today] and [since] are yyyy-MM-dd.
 */
internal fun genreParams(movie: Boolean, genre: Int, list: String, today: String, since: String): Map<String, String> {
    val date = if (movie) "primary_release_date" else "first_air_date"
    val p = linkedMapOf("with_genres" to "$genre")
    when (list) {
        "new" -> { p["sort_by"] = "$date.desc"; p["$date.lte"] = today; p["vote_count.gte"] = "20" }
        "blockbusters" -> p["sort_by"] = "revenue.desc"
        "most_watched" -> p["sort_by"] = "vote_count.desc"
        "top_rated" -> { p["sort_by"] = "vote_average.desc"; p["vote_count.gte"] = if (movie) "500" else "200" }
        "complete" -> { p["sort_by"] = "popularity.desc"; p["with_status"] = "3" }
        else -> { // trending: popular among the last months' releases (films) or what aired lately (shows)
            val aired = if (movie) date else "air_date"
            p["sort_by"] = "popularity.desc"; p["$aired.gte"] = since; p["$aired.lte"] = today
        }
    }
    return p
}
