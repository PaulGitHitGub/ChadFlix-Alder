package dev.alder

import android.content.Context
import android.graphics.Outline
import android.graphics.drawable.GradientDrawable
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
    const val PLACEHOLDER = 0xFF1A1A1A.toInt()
    const val AVATAR = 0xFF2A2A2A.toInt()
    const val OPEN_TAB = 0x33FFFFFF // the open page's pill while the remote is on another item in the top bar
    const val RED = 0xFFE50914.toInt()
    const val TRACK = 0xFF4A4A4A.toInt()

    // Netflix's TV home, measured from the owner's photo at 1080p and halved: 152 x 248 dp posters, the focused one
    // 441 dp wide (16:9), pinned 33 dp from the left with the card before it peeking in.
    const val CARD_W = 152
    const val CARD_H = 248
    const val WIDE_W = 441
    const val INSET = 33
    const val CARD_GAP = 8
    // As ChadFlix on Kodi draws it (measured at 4K): pictures rounded 6.5 dp, and focus a thin soft #F2F2F2 line just
    // outside the card, 8 dp round on its outer edge.
    const val CARD_RADIUS = 6.5f
    const val RING = 1.5f // the focus border's width, outside the card
    const val RING_COLOR = 0xFFF2F2F2.toInt()
    const val NAV_WAIT = 300L // ms resting on a top bar item before it opens (Home's SWITCH_WAIT)
    const val ROWS_TOP = 76 // the first row's title, under the top bar
    const val INFO_ABOVE = 14 // between the cards and the info line
    const val INFO_GAP = 20 // between the info line and the next row's title
    const val PLOT_LINES = 3 // a fixed-height box: moving along a row never moves the rows
    const val ROW_TITLE = 16.5f // sp
    const val TAB_SP = 13f
    const val INFO = 12f // sp, the "Movie • Drama • 2014 • 1h 41m • PG-13" line
    const val ROW_DIM = 0.25f // rows the remote isn't in are dimmed only lightly
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
     * A home card: a rounded placeholder until the picture comes, and the picture rounded on its own, so the card can
     * widen ([MediaCardView.expand]) without a clip of its own.
     */
    fun posterCard(card: FrameLayout, face: View, shade: View) {
        val c = card.context
        val r = px(c, CARD_RADIUS)
        shade.background = GradientDrawable().apply { setColor(BLACK); cornerRadius = r } // the dimming keeps the corners round
        card.background = GradientDrawable().apply { setColor(PLACEHOLDER); cornerRadius = r }
        card.clipToOutline = false
        face.outlineProvider = object : ViewOutlineProvider() {
            override fun getOutline(view: View, outline: Outline) = outline.setRoundRect(0, 0, view.width, view.height, r)
        }
        face.clipToOutline = true
    }

    /**
     * The focus border: a static drawable on a view [ringOut] px bigger than the card all round. Its stroke is drawn inside
     * its bounds, so the line runs from the card's edge (6.5 dp round) outwards (8 dp round).
     */
    fun ring(c: Context) = View(c).apply {
        background = GradientDrawable().apply {
            val w = ringOut(c)
            cornerRadius = px(c, CARD_RADIUS) + w / 2f
            setStroke(w, RING_COLOR)
        }
        visibility = View.INVISIBLE // INVISIBLE, not GONE: showing it then needs no layout pass
    }

    /** The focused card's rating: the source's white mark and the score, no box, with a soft shadow. */
    fun badge(screen: Screen) = LinearLayout(screen).apply {
        gravity = Gravity.CENTER_VERTICAL
        addView(android.widget.ImageView(screen))
        addView(screen.text("", 12.5f, bold = true, color = WHITE).apply {
            includeFontPadding = false
            setShadowLayer(px(screen, 2f), 0f, px(screen, 1f), 0x99000000.toInt())
        }, LinearLayout.LayoutParams(-2, -2).apply { marginStart = px(screen, 4f).toInt() })
    }

    /** Continue Watching's watched-so-far bar: red on a grey track, along the foot of the card. */
    fun bar(c: Context, progress: Float) = LinearLayout(c).apply {
        val p = progress.coerceIn(0f, 1f)
        setBackgroundColor(TRACK)
        addView(View(c).apply { setBackgroundColor(RED) }, LinearLayout.LayoutParams(0, -1, p))
        addView(View(c), LinearLayout.LayoutParams(0, -1, 1f - p))
    }

    /** The focus border's width in px (at least 1). */
    fun ringOut(c: Context) = Math.round(px(c, RING)).coerceAtLeast(1)

    /** A view's natural height, measured before it's on screen (the TV's font size changes it). */
    fun height(v: View): Int = v.run { measure(0, 0); measuredHeight }

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

    // ---------- Soft Wash: the focused title's backdrop as a dim blur behind the rows ----------

    const val WASH_W = 120 // px: the wash's width; the GPU scales it up to the screen
    const val WASH_WAIT = 300L // ms the remote rests on a title before its wash comes

    /**
     * The focused title's backdrop as Home's background: decoded tiny from the copy the widened card already downloaded
     * (1280 px, from the disk cache), frosted, dimmed to about 70%, its colour lifted a little, and shaded to black
     * towards the bottom so the plot and the next row stay readable. About 120 x 68 px; off the main thread.
     */
    val wash = Images.Look("wash", fetchPx = 1000) { src ->
        val f = Images.frost(src, WASH_W).also { if (it !== src) src.recycle() }
        val w = f.width; val h = f.height
        val px = IntArray(w * h).also { f.getPixels(it, 0, w, 0, 0, w, h) }
        for (y in 0 until h) {
            val shade = 0.7f * washShade(y / (h - 1f).coerceAtLeast(1f))
            for (x in 0 until w) px[y * w + x] = washPixel(px[y * w + x], shade)
        }
        val out = if (f.isMutable) f else f.copy(android.graphics.Bitmap.Config.ARGB_8888, true).also { f.recycle() }
        out.setPixels(px, 0, w, 0, 0, w, h)
        out.toGpu()
    }

    // ---------- row order (Move Up / Move Down in a row's hold-OK menu), per profile and page ----------

    private fun key(c: Context, page: String) = Settings.profileKey(c, "rowOrder:$page")
    private fun prefs(c: Context) = c.getSharedPreferences("settings", Context.MODE_PRIVATE)

    fun rowOrder(c: Context, page: String): List<String> =
        runCatching { org.json.JSONArray(prefs(c).getString(key(c, page), "[]")).strings() }.getOrDefault(emptyList())

    /** Moves [title] [by] places among the page's [shown] rows and keeps that order. */
    fun moveRow(c: Context, page: String, shown: List<String>, title: String, by: Int) = saveRowOrder(c, page, movedRow(shown, title, by))

    fun saveRowOrder(c: Context, page: String, titles: List<String>) =
        prefs(c).edit().putString(key(c, page), org.json.JSONArray(titles).toString()).apply()

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

    /**
     * [items] in this page's saved order ([orderedRows]); platform rows that order has never seen (an order saved before
     * they existed) go after the genres, where the page puts them, not at the end.
     */
    fun <T> ordered(c: Context, page: String, movie: Boolean, items: List<T>, title: (T) -> String): List<T> {
        val saved = rowOrder(c, page)
        val out = orderedRows(saved, items, title)
        val fresh = out.filter { t -> title(t) !in saved && platforms.any { it.name == title(t) } }
        if (saved.isEmpty() || fresh.isEmpty()) return out
        val rest = out - fresh.toSet()
        val at = rest.indexOfLast { t -> genres(movie).any { it.second == title(t) } } + 1
        return rest.take(at) + fresh + rest.drop(at)
    }

    fun setRowOn(c: Context, page: String, title: String, on: Boolean) =
        prefs(c).edit().putString(Settings.profileKey(c, "rowsOn:$page"), rowsOn(c, page).put(title, on).toString()).apply()

    /** Every row the page could show, on or off, for Customize Home; kept when the page is built. */
    fun rememberRows(c: Context, page: String, titles: List<String>) =
        prefs(c).edit().putString(Settings.profileKey(c, "rowsKnown:$page"), org.json.JSONArray(titles).toString()).apply()

    fun knownRows(c: Context, page: String, movie: Boolean): List<String> {
        val known = runCatching { org.json.JSONArray(prefs(c).getString(Settings.profileKey(c, "rowsKnown:$page"), null)!!).strings() }
            .getOrNull() ?: genres(movie).map { it.second }
        // Platform rows the page hasn't been built with yet (before this version) go after the genres, as the page puts them.
        val missing = platforms.map { it.name }.filter { it !in known }
        if (missing.isEmpty()) return known
        val at = known.indexOfLast { t -> genres(movie).any { it.second == t } } + 1
        return known.take(at) + missing + known.drop(at)
    }

    /** What a genre row ([key] its TMDb genre id) or platform row ("p<n>") shows on [page]; "trending" until the Showing tile picks another. */
    fun showing(c: Context, page: String, key: String): String =
        prefs(c).getString(Settings.profileKey(c, "showing:$page:$key"), "trending").orEmpty()

    fun genreSource(c: Context, page: String, id: Int) = "$TMDB$id:${showing(c, page, "$id")}"

    // ---------- platform rows: what streams on a service (TMDb watch providers, US), off until Customize Home ----------

    /**
     * A streaming service: its TMDb watch provider ids (what streams there), TV network ids and film company ids (what it
     * made: "Exclusives Only"). Checked against TMDb on 2026-11-22 through the app's key. No company makes a reliable
     * film filter for Disney+, Hulu, Paramount+ or Peacock (TMDb has no such company, or only a foreign one), so their
     * film rows have no Exclusives switch.
     */
    class Platform(val name: String, val providers: String, val networks: String, val companies: String?)

    val platforms = listOf(
        Platform("Netflix", "8", "213", "178464|145174|171251"), // Netflix, Netflix International Pictures, Netflix Animation Studios
        Platform("Apple TV+", "350", "2552", "194232"), // Apple Studios (TMDb's "Apple Original Films")
        Platform("Disney+", "337", "2739", null),
        Platform("Prime Video", "9", "1024", "210099|20580"), // Amazon MGM Studios, Amazon Studios
        Platform("Max", "1899", "3186|49", "7429|158691|138252"), // HBO Max and HBO networks; HBO Films, HBO Max, Warner Max
        Platform("Hulu", "15", "453", null),
        Platform("Paramount+", "2303|2616", "4330", null), // Premium and Essential
        Platform("Peacock", "386|387", "3353", null), // Premium and Premium Plus
    )

    fun platformSource(c: Context, page: String, i: Int, movie: Boolean) =
        "${TMDB}p$i:${showing(c, page, "p$i")}${if (exclusive(c, page, i, movie)) "!" else ""}"

    /** Whether platform row [i] has an Exclusives Only switch on this tab (films need a company to filter by). */
    fun canExclusive(i: Int, movie: Boolean) = !movie || platforms[i].companies != null

    /** Platform row [i]'s Exclusives Only switch: on by default for shows, off for films (their "made by" data is thinner). */
    fun exclusive(c: Context, page: String, i: Int, movie: Boolean) = canExclusive(i, movie) &&
        prefs(c).getBoolean(Settings.profileKey(c, "excl:$page:p$i"), !movie)

    fun setExclusive(c: Context, page: String, i: Int, on: Boolean) =
        prefs(c).edit().putBoolean(Settings.profileKey(c, "excl:$page:p$i"), on).apply()

    /** A row's Showing key: its TMDb genre id, or "p<n>" for a platform row; null for any other row. */
    fun rowKey(movie: Boolean, title: String): String? =
        genres(movie).firstOrNull { it.second == title }?.first?.toString()
            ?: platforms.indexOfFirst { it.name == title }.takeIf { it >= 0 }?.let { "p$it" }

    /** The lists a row can show, the default first: genre rows [lists]; platform rows Trending, New Releases, Top Rated (and Most Watched for films). */
    fun listsFor(movie: Boolean, key: String): List<Pair<String, Int>> = if (!key.startsWith("p")) lists(movie)
        else listOfNotNull("trending" to R.string.showing_trending,
            "new" to if (movie) R.string.showing_new_releases else R.string.showing_new_series,
            "top_rated" to R.string.showing_top_rated, ("most_watched" to R.string.showing_most_watched).takeIf { movie })

    /** A genre row's place on the page, whatever list it shows: "tmdb:<id>". */
    fun slotSource(source: String) = source.substringBeforeLast(':')

    /** Genre rows fetch on two threads of their own, so title pages and sources never wait behind them. */
    val rowPool: java.util.concurrent.ExecutorService = java.util.concurrent.Executors.newFixedThreadPool(2)

    fun setShowing(c: Context, page: String, key: String, list: String) =
        prefs(c).edit().putString(Settings.profileKey(c, "showing:$page:$key"), list).apply()

    /** The lists a genre row can show, the default first: key to its name. */
    fun lists(movie: Boolean): List<Pair<String, Int>> = if (movie) listOf("trending" to R.string.showing_trending,
        "new" to R.string.showing_new_releases, "blockbusters" to R.string.showing_blockbusters,
        "most_watched" to R.string.showing_most_watched, "top_rated" to R.string.showing_top_rated)
    else listOf("trending" to R.string.showing_trending, "new" to R.string.showing_new_series,
        "most_watched" to R.string.showing_most_watched, "top_rated" to R.string.showing_top_rated,
        "complete" to R.string.showing_complete)

    private const val TILE = "chadflix:showing:"
    const val LIST_RED = 0xFFFF3B47.toInt() // #EF4B55 at full saturation
    const val LABEL_GREY = 0xFF9A9A9A.toInt()

    /** What genre or platform row [title] shows on [page] ("Trending", "Trending · Exclusives"), or null for any other row. */
    fun rowList(c: Context, page: String, movie: Boolean, title: String): String? {
        val key = rowKey(movie, title) ?: return null
        val lists = listsFor(movie, key)
        val list = c.getString((lists.firstOrNull { it.first == showing(c, page, key) } ?: lists[0]).second)
        val excl = key.startsWith("p") && exclusive(c, page, key.drop(1).toInt(), movie)
        return if (excl) "$list · ${c.getString(R.string.showing_exclusives)}" else list
    }

    /** "Action · Trending": the list in red on the row the remote is in, all of it quiet on the others. */
    fun rowTitle(genre: String, list: String, lit: Boolean): CharSequence = android.text.SpannableString("$genre · $list").apply {
        if (lit) setSpan(android.text.style.ForegroundColorSpan(LIST_RED), genre.length, length, 0)
    }

    /**
     * The Showing tile as ChadFlix draws it: "SHOWING" in small grey capitals over the list's name, a third of the way
     * down, and a small red list mark near the foot. Under the card's shade and border, so it dims and focuses like a poster.
     */
    /**
     * Returns how to show the tile's state: 2 focused (grey label, white list, red mark), 1 its row in use (both grey,
     * no mark), 0 another row (blank).
     */
    fun dressTile(screen: Screen, card: MediaCardView, label: String, list: String): (Int) -> Unit {
        val pad = px(screen, 12f).toInt()
        card.contentDescription = "$label $list"
        val words = LinearLayout(screen).apply {
            orientation = LinearLayout.VERTICAL
            addView(screen.text(label.uppercase(), 10.5f, bold = true, color = LABEL_GREY).apply { letterSpacing = 0.08f })
            addView(screen.text(list, 15f, bold = true, color = WHITE).apply { maxLines = 2 },
                LinearLayout.LayoutParams(-2, -2).apply { topMargin = px(screen, 2f).toInt() })
            visibility = View.INVISIBLE
        }
        val name = words.getChildAt(1) as TextView
        card.addView(words, 0, FrameLayout.LayoutParams(-1, -2, Gravity.TOP).apply {
            leftMargin = pad; rightMargin = pad; topMargin = card.heightPx * 32 / 100
        })
        val size = px(screen, 10f).toInt()
        val mark = object : View(screen) {
            private val paint = android.graphics.Paint().apply { color = LIST_RED }
            override fun onDraw(canvas: android.graphics.Canvas) { // three short lines
                val h = height / 7f
                for (i in 0 until 3) canvas.drawRect(0f, (1 + 2 * i) * h, width.toFloat(), (2 + 2 * i) * h, paint)
            }
        }.apply { visibility = View.INVISIBLE }
        card.addView(mark, 1, FrameLayout.LayoutParams(size, size, Gravity.TOP).apply { leftMargin = pad; topMargin = card.heightPx * 80 / 100 - size / 2 })
        return { state ->
            words.visibility = if (state > 0) View.VISIBLE else View.INVISIBLE
            name.setTextColor(if (state == 2) WHITE else LABEL_GREY)
            mark.visibility = if (state == 2) View.VISIBLE else View.INVISIBLE
        }
    }

    /** A genre row's first card, the Showing tile: it opens the list picker instead of a title. */
    fun isTile(id: String) = id.startsWith(TILE)

    /**
     * A genre row's titles, one TMDb page, after its Showing tile (a card with no picture: "Showing" over the list's
     * name), and whether it's worth keeping (most titles found their IMDb id). Blocks: run it off the main thread.
     */
    fun genreTitles(c: Context, movie: Boolean, source: String): Pair<List<Meta>, Boolean> {
        val parts = source.removePrefix(TMDB).split(":")
        // No name: the card's own title would draw over the tile's words ([dressTile]).
        val tile = Meta(org.json.JSONObject().put("id", TILE + parts[0]).put("type", if (movie) "movie" else "series").put("name", ""))
        val day = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US)
        val since = java.util.Calendar.getInstance().apply { add(java.util.Calendar.MONTH, if (movie) -6 else -12) }.time
        val list = parts.getOrElse(1) { "trending" }
        val params = if (parts[0].startsWith("p")) platforms[parts[0].drop(1).toInt()].let { p -> // a platform row
            platformParams(movie, p.providers, p.networks, p.companies, list.removeSuffix("!"), list.endsWith("!"), Tmdb.region,
                day.format(java.util.Date()))
        } else genreParams(movie, parts[0].toInt(), list, day.format(java.util.Date()), day.format(since))
        val (titles, complete) = Tmdb.discoverRow(movie, params, genres(movie).toMap())
        return listOf(tile) + titles to complete
    }
}

/** [items] in the [saved] order of their titles; rows it doesn't name (a new add-on's) follow in their own order. */
internal fun <T> orderedRows(saved: List<String>, items: List<T>, title: (T) -> String): List<T> =
    if (saved.isEmpty()) items else items.sortedBy { saved.indexOf(title(it)).let { i -> if (i < 0) Int.MAX_VALUE else i } }

/**
 * [title] moved [by] places among the rows on screen ([shown]), in the order of every row the page could show ([all]):
 * it trades places with its nearest shown neighbour, so rows that are off keep theirs.
 */
internal fun movedAmong(all: List<String>, shown: List<String>, title: String, by: Int): List<String> {
    val other = shown.getOrNull(shown.indexOf(title).takeIf { it >= 0 }?.plus(by) ?: return all) ?: return all
    if (title !in all || other !in all) return movedRow(shown, title, by)
    return all.map { when (it) { title -> other; other -> title; else -> it } }
}

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

/** ChadFlix's wash: full strength in the top 40%, then shading down to 15% at the bottom. */
internal fun washShade(y: Float): Float = if (y <= 0.4f) 1f else 1f - 0.85f * ((y - 0.4f) / 0.6f)

/** One wash pixel: its colour lifted 20% away from its grey, then scaled by [shade]. */
internal fun washPixel(p: Int, shade: Float): Int {
    val r = p shr 16 and 255; val g = p shr 8 and 255; val b = p and 255
    val l = 0.299f * r + 0.587f * g + 0.114f * b
    fun c(v: Int) = ((l + (v - l) * 1.2f) * shade).toInt().coerceIn(0, 255)
    return (255 shl 24) or (c(r) shl 16) or (c(g) shl 8) or c(b)
}

/**
 * TMDb discover filters for a platform row: what streams on the service by subscription in [region], sorted by [list]
 * (Trending = most popular, New Releases, Top Rated, Most Watched); [exclusive] keeps what it made itself: shows by its
 * networks, films by its companies.
 */
internal fun platformParams(movie: Boolean, providers: String, networks: String, companies: String?, list: String,
                            exclusive: Boolean, region: String, today: String): Map<String, String> {
    val date = if (movie) "primary_release_date" else "first_air_date"
    val p = linkedMapOf("with_watch_providers" to providers, "watch_region" to region, "with_watch_monetization_types" to "flatrate")
    if (exclusive) { if (!movie) p["with_networks"] = networks else if (companies != null) p["with_companies"] = companies }
    when (list) {
        "new" -> { p["sort_by"] = "$date.desc"; p["$date.lte"] = today; p["vote_count.gte"] = "20" }
        "top_rated" -> { p["sort_by"] = "vote_average.desc"; p["vote_count.gte"] = if (movie) "500" else "200" }
        "most_watched" -> p["sort_by"] = "vote_count.desc"
        else -> p["sort_by"] = "popularity.desc"
    }
    return p
}
