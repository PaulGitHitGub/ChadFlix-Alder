package dev.alder

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.text.TextUtils
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewTreeObserver
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.TextClock
import org.json.JSONObject

internal fun synopsisAtSentence(text: String, limit: Int): String {
    if (limit >= text.length) return text
    // A sentence ends at . ! or ? before a space, but not after "Sgt.", "Dr.", "U.S." and the like.
    val end = (limit - 1 downTo 0).firstOrNull { i ->
        text[i] in ".!?" && text.getOrNull(i + 1) == ' ' && !(text[i] == '.' && text.substring(text.lastIndexOf(' ', i) + 1, i)
            .let { word -> '.' in word || word.length <= 3 && word.firstOrNull()?.isUpperCase() == true })
    }
    if (end != null) return text.substring(0, end + 1)
    val clipped = text.take(limit).trimEnd()
    val whole = if (text.getOrNull(limit - 1)?.isLetterOrDigit() == true && text.getOrNull(limit)?.isLetterOrDigit() == true)
        clipped.substringBeforeLast(' ', clipped) else clipped
    return whole.trimEnd(' ', ',', ';', ':', '-', '.', '!', '?') + "…"
}

/** "Because you watched Crayon Shin-chan: Pursuit" → "More like Crayon Shin-chan". */
internal fun homeRowName(name: String): String {
    fun cap(s: String, max: Int): String {
        val clean = s.trim()
        if (clean.length <= max) return clean
        val cut = clean.take(max).substringBeforeLast(' ', "").trimEnd()
        return (if (cut.isNotEmpty()) cut else clean.take(max).trimEnd()) + "…"
    }
    val prefix = "Because you watched "
    if (!name.startsWith(prefix, ignoreCase = true)) return cap(name, 36)
    val title = name.substring(prefix.length).substringBefore(":").substringBefore(" (").substringBefore(" - ")
    return "More like ${cap(title, 26)}"
}

internal fun seasons(count: Int) = if (count == 1) "1 season" else "$count seasons"

internal fun genreLabel(name: String) = when {
    name.trim().equals("Science Fiction", true) || name.trim().equals("Sci-Fi", true) -> "Sci-Fi"
    else -> name.trim()
}

internal fun genreOption(options: List<String>, label: String) =
    options.firstOrNull { genreLabel(it).equals(label, true) }

internal fun catalogGenres(names: List<String>): List<String> {
    val preferred = listOf("Drama", "Comedy", "Crime", "Sci-Fi", "Anime", "Documentary")
    return names.filterNot { it.equals("All", true) }.distinctBy { it.lowercase() }
        .sortedWith(compareBy<String> { name ->
            preferred.indexOfFirst { it.equals(name, true) }.let { if (it < 0) preferred.size else it }
        }.thenBy { it })
}

internal fun settledGenres(names: List<String>, results: Map<String, Boolean>): List<String>? =
    if (names.any { it !in results }) null else names.filter { results[it] == true }

/**
 * A row's short name: personal picks are "For you", charts "Popular", and a long themed name ("Short & Sweet
 * Adventure") keeps only the [genres] it names, so it can merge with that genre's row.
 */
internal fun shortRowName(name: String, genres: Collection<String> = emptyList()): String {
    val n = name.replace("Science Fiction", "Sci-Fi", ignoreCase = true).trim()
    return when {
        n.contains(" in India", true) && n.contains("recommend", true) -> n // a region's picks, not the viewer's
        n.contains("pick", true) || n.contains("recommend", true) -> "For you"
        n.contains("popular", true) || n.contains("trending", true) -> "Popular"
        n.length <= 18 -> n
        else -> genres.map(::genreLabel).distinct().filter { n.contains(it, true) }.take(2).joinToString(" & ").ifEmpty { n.take(28).trimEnd() }
    }
}

internal fun isAddonMessage(name: String) = name.contains("Authentication Required", ignoreCase = true)

internal data class CardVisibilityWindow(
    val loadStart: Int,
    val loadEnd: Int,
    val farLeftEnd: Int,
    val farRightStart: Int,
    val visibleFirst: Int = loadStart,
    val visibleLast: Int = loadEnd,
)

/**
 * The order to ask for a row's pictures: the cards just off the screen first, then the visible ones from the right.
 * Images' pool runs the newest request first, so the first card on the screen loads first.
 */
internal fun cardLoadOrder(w: CardVisibilityWindow): List<Int> =
    (w.loadStart until w.loadEnd).filter { it < w.visibleFirst || it >= w.visibleLast } +
        (w.visibleLast - 1 downTo w.visibleFirst)

internal fun cardLoadWindow(scrollX: Int, viewWidth: Int, step: Int, total: Int): CardVisibilityWindow {
    if (step <= 0 || total <= 0) return CardVisibilityWindow(0, 0, 0, total)
    val visibleFirst = (scrollX / step).coerceAtLeast(0)
    val visibleLast = ((scrollX + viewWidth + step - 1) / step).coerceAtMost(total)
    val loadStart = (visibleFirst - 4).coerceAtLeast(0)
    val loadEnd = (visibleLast + 6).coerceAtMost(total)
    val farLeftEnd = (visibleFirst - 12).coerceAtLeast(0)
    val farRightStart = (visibleLast + 12).coerceAtMost(total)
    return CardVisibilityWindow(loadStart, loadEnd, farLeftEnd, farRightStart, visibleFirst.coerceAtMost(visibleLast), visibleLast)
}

// Cards a row starts with: past the screen's width (1,280 dp at most on a TV), so the first press already slides the
// row. 9 posters (1,000 dp with the margins) barely passed 960 dp, so the remote walked across a still row and the row
// jumped when the next page came. Wide cards pass it at 9 already.
internal const val ROW_START_CARDS = 14
internal const val WIDE_ROW_START_CARDS = 9

internal fun initialRowCards(wide: Boolean): Int = if (wide) WIDE_ROW_START_CARDS else ROW_START_CARDS

internal fun shouldSkipRepeat(held: Boolean, wide: Boolean, repeatCount: Int): Boolean =
    wide && held && repeatCount % 2 != 0

internal fun rowGlideDuration(held: Boolean, wide: Boolean, repeatDelay: Long): Long =
    if (held) repeatDelay * (if (wide) 2 else 1) else 200L

internal fun artDelay(held: Boolean, cached: Boolean, ready: Boolean): Long =
    if (ready) 0L else if (held) 280L else if (cached) 0L else 120L

internal fun libraryPosterWidth(width: Int, height: Int, gap: Int, topRoom: Int, peek: Int): Int =
    minOf((width - gap * 5) / 6, (height - topRoom * 2 - gap * 2 - peek) / 3).coerceAtLeast(1)

internal fun gridScrollTarget(scrollY: Int, viewportHeight: Int, rowTop: Int, rowHeight: Int): Int = when {
    rowTop < scrollY -> rowTop
    rowTop + rowHeight > scrollY + viewportHeight -> rowTop
    else -> scrollY
}.coerceAtLeast(0)

internal fun nextCatalogSection(total: Int, loaded: Int) = minOf(total, loaded + 4)

/**
 * The card Up or Down lands on in the next row: in My List's grid, the same column; in a row of titles, the card that
 * row had last (the first, if new). Each catalog is its own list, so the place in one says nothing about another.
 */
internal fun landingCard(from: Int, nextSelected: Int, nextSize: Int, grid: Boolean) =
    if (grid) minOf(from, nextSize - 1) else nextSelected

/** Keep the same filter when a service list appears or disappears; otherwise use the active media type. */
internal fun discoverChipIndex(keys: List<String>, remembered: String?, type: String): Int? =
    keys.indexOf(remembered).takeIf { it >= 0 } ?: keys.indexOf(type).takeIf { it >= 0 } ?: keys.indices.firstOrNull()

/** A horizontal strip only mirrors D-pad edges when the manifest enables RTL layout support. */
internal fun isStripRtl(supportsRtl: Boolean, configLayoutDirection: Int): Boolean =
    supportsRtl && configLayoutDirection == View.LAYOUT_DIRECTION_RTL

internal fun homeSectionTitle(title: String, isMovie: Boolean): String {
    if (title.contains("movie", true) || title.contains("film", true) ||
        title.contains("show", true) || title.contains("series", true)) return title
    val kind = if (isMovie) "Movies" else "Shows"
    val singular = if (isMovie) "Movie" else "Show"
    return when {
        title.equals("For you", true) -> if (isMovie) "Movies for you" else "Shows for you"
        title.equals("Top Picks for You", true) -> "Top $singular Picks"
        title.equals("Recommended for You", true) -> "Recommended $kind"
        title.contains(" in ", true) -> title.replace(" in ", " $kind in ", ignoreCase = true)
        title.contains(" on ", true) -> title.replace(" on ", " $kind on ", ignoreCase = true)
        title.endsWith(" Now", true) -> title.removeSuffix(" Now") + " $kind"
        else -> "$title $kind"
    }
}

internal fun catalogAnswerKey(page: String, title: String, genre: String?, sources: List<String>) =
    (listOf(page, title, genre.orEmpty()) + sources).joinToString("\u0000")

/**
 * Catalog and title types that are live: TV channels, sport and events. Other types (anime, YouTube's video channels,
 * podcasts) get a page each ([extraPageTypes]).
 */
internal fun isLiveType(type: String) = type.lowercase() in setOf("tv", "live", "sport", "sports", "event", "events")

/**
 * A channel logo's colour, for the wash behind it when the channel has no art: the average of its colourful opaque
 * pixels, dimmed so white words still read over it. Null for a logo with no colour to speak of (white, black, grey).
 */
internal fun logoTint(pixels: IntArray): Int? {
    val opaque = pixels.filter { it ushr 24 >= 128 }
    val colourful = opaque.filter { p ->
        val r = p ushr 16 and 255; val g = p ushr 8 and 255; val b = p and 255
        val max = maxOf(r, g, b); val min = minOf(r, g, b)
        max >= 40 && (max - min) >= 0.35f * max
    }
    if (colourful.isEmpty() || colourful.size * 20 < opaque.size) return null
    val r = colourful.sumOf { it ushr 16 and 255 } / colourful.size
    val g = colourful.sumOf { it ushr 8 and 255 } / colourful.size
    val b = colourful.sumOf { it and 255 } / colourful.size
    val scale = 120f / maxOf(r, g, b, 1)
    return (0xFF shl 24) or ((r * scale).toInt().coerceAtMost(255) shl 16) or
        ((g * scale).toInt().coerceAtMost(255) shl 8) or (b * scale).toInt().coerceAtMost(255)
}

// Home for the profile in use, built to feel like a cinema rather than a catalogue. The whole screen is one title's
// art: sharp where nothing sits on it, with light shading behind text and cards.
//  - Browse (every page, Home opening on Continue Watching): one row of wide 16:9 art cards along the bottom, the
//    focused title's logo, facts and description top left. Down and up slide whole rows in.
//  - Cinema (the screensaver): after IDLE without a key, one Continue Watching title fills the screen with its logo,
//    where you are and Resume, moving on to the next every ADVANCE. OK resumes; any other key returns to where you were.
//    The TV's own screensaver takes over later (10 min on the test TV), so nothing stays still for hours.
class MainActivity : Screen() {
    /** [label] names a page in intents and saved answers; [words] is what the top bar shows. */
    /** A page in the top bar: the five fixed ones, then one per other catalog type the add-ons list ([Page.of]). */
    private class Page private constructor(val label: String, val words: Int, val type: String? = null) {
        companion object {
            val HOME = Page(HomeStyle.HOME, R.string.tab_home)
            val DISCOVER = Page("Discover", R.string.tab_discover)
            val SHOWS = Page("Shows", R.string.tab_shows)
            val MOVIES = Page("Movies", R.string.tab_movies)
            val LIVE = Page("Live", R.string.tab_live)
            val LIBRARY = Page("My Alder", R.string.tab_my_list)
            val SERVICES = Page(HomeStyle.SERVICES, R.string.tab_services) // ChadFlix: one row of shows per streaming service
            val fixed = listOf(HOME, DISCOVER, SHOWS, MOVIES, LIVE, SERVICES, LIBRARY)
            private val byType = mutableMapOf<String, Page>()
            // warmPages asks for pages off the main thread: one lock, so a type never gets two pages (two tabs).
            fun of(type: String) = synchronized(byType) { byType.getOrPut(type) { Page(type, 0, type) } }
            fun find(label: String?) = fixed.firstOrNull { it.label == label } ?: synchronized(byType) { byType[label] }
        }
    }
    private enum class Mode { CINEMA, BROWSE }
    private enum class ListFilter { ALL, MOVIES, SHOWS }
    private enum class ListSort(val label: Int) {
        RECENT(R.string.list_sort_recent), NAME(R.string.list_sort_name), UNWATCHED(R.string.list_sort_unwatched)
    }

    private lateinit var profile: Profile
    override val backdrop get() = art
    private lateinit var scroller: ScrollView
    private lateinit var content: LinearLayout
    private lateinit var header: FrameLayout
    private var updateDot: View? = null
    private lateinit var genreStrip: HorizontalScrollView
    private val genreChips = mutableMapOf<String?, TextView>()
    private val listFilterChips = mutableMapOf<ListFilter, TextView>()
    private lateinit var listSortChip: TextView
    private var lastListChip: TextView? = null
    private val navItems = mutableMapOf<Page, TextView>()
    private var page = Page.HOME
    private val Page.isCatalog get() = this == Page.SHOWS || this == Page.MOVIES || type != null
    private val Page.hasGenreShortcuts get() = this == Page.SHOWS || this == Page.MOVIES
    private val Page.polishedRail get() = this == Page.HOME || hasGenreShortcuts || this == Page.SERVICES
    private val held get() = verticalHeld || rows.any { it.rail.held }
    private var verticalHeld = false // ChadFlix: Up or Down held: the rows passed don't widen a card or fetch backdrops
    /** One row of cards under the focused title's art: every page but My Alder's and Discover's grids. */
    private val Page.isBrowse get() = this == Page.HOME || isCatalog || this == Page.LIVE || this == Page.SERVICES
    /** A grid of large posters under the focused title's art: My Alder and Discover. */
    private val Page.isGrid get() = this == Page.LIBRARY || this == Page.DISCOVER
    private var discover = DiscoverQuery()
    private var discoverLoaded = false // the profile's saved choice has been read
    private val discoverChips = mutableListOf<TextView>()
    private var pageWarning: View? = null
    private lateinit var warningHost: FrameLayout
    private var mode = Mode.BROWSE
    private var library: List<JSONObject> = emptyList()
    // ponytail: row answers this session, including their catalogs; unbounded, but a few hundred titles per page.
    private val answers = java.util.concurrent.ConcurrentHashMap<String, List<Pair<List<Meta>, Boolean>>>()
    private var genre: String? = null
    private var jumpTo: String? = null // a chip's row that wasn't made yet when OK was pressed
    private var listFilter = ListFilter.ALL
    private var listSort = ListSort.RECENT
    private var listGenre: String? = null // My Alder's Genre and Year chips; null is All
    private var listDecade: Int? = null
    private var listYear: Int? = null
    private var librarySlot: FrameLayout? = null
    private var listBuild = 0
    private lateinit var listShade: View
    private var listPosterWidth = 0
    private var search: ImageView? = null
    private val rows = mutableListOf<Row>()
    private var focusedSomething = false
    private val fillingListDetails = mutableSetOf<String>()
    private val metas = mutableMapOf<String, Meta>() // full details, cached with fetch time for series TTL refresh
    private val metaFetchedAt = mutableMapOf<String, Long>()

    private fun cacheMeta(id: String, meta: Meta, fetchedAtMs: Long = System.currentTimeMillis()) {
        metas[id] = meta
        metaFetchedAt[id] = fetchedAtMs
    }

    // The art, at full strength and full bleed, over the previous title's art while it crossfades in.
    private lateinit var art: ImageView
    private lateinit var artBack: ImageView
    // Not glass: a light darkening of the art's top edge while the top bar shows. The left and bottom shades are painted
    // into the art itself ([shaded]).
    private lateinit var topShade: View

    // A chevron in the right margin while the row the remote is in has more cards past the screen's edge.
    private lateinit var moreHint: ImageView
    private lateinit var nextRowHint: TextView
    private lateinit var cardCaption: TextView
    private lateinit var cardUnderline: View
    private val captionAnchor = Rect()
    private var browseRowTop = 0
    private var browseRowH = 0
    private var browseEndPad = 0 // ChadFlix: room under the last row, so it can rise to the top like the others
    private var browseRowBlock = 0 // ChadFlix: one row's height, title to the next row's title
    private var rowBottom = 0 // ChadFlix: px under a row's cards: the info line and the gaps around it
    private var factsH = 0 // ChadFlix: the facts line's fixed height (as tall as a rating beside it), so it never shifts

    private var artTask = Runnable {}
    private var heroTask: Runnable? = null
    private val artW by lazy { resources.displayMetrics.widthPixels }

    /**
     * The art cropped to the screen with its shades painted in, once per picture: the left darkening behind the words
     * and the long one down to the cards. As views over the art they were two more layers the GPU blended every frame,
     * a third of this TV's frame time.
     */
    private val shaded = Images.Look("shaded") { src ->
        val w = artW
        val h = resources.displayMetrics.heightPixels
        val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(out)
        val scale = maxOf(w / src.width.toFloat(), h / src.height.toFloat())
        val dw = src.width * scale
        val dh = src.height * scale
        canvas.drawBitmap(src, null, RectF((w - dw) / 2, (h - dh) / 2, (w + dw) / 2, (h + dh) / 2), Paint(Paint.FILTER_BITMAP_FLAG))
        val bright = samplePixels(src, 16, 24)?.let { brightLeft(it, 16) } ?: false
        shade(canvas, w, h, bright)
        src.recycle()
        out.toGpu()
    }

    /**
     * A live channel with no art of its own: the dark base with a soft glow of its logo's colour (the logo itself sits
     * top left, where a film's title goes), shaded like any other art. A logo without colour leaves the plain base.
     */
    private val washed = Images.Look("washed") { src ->
        val w = artW
        val h = resources.displayMetrics.heightPixels
        val tint = samplePixels(src, 24, 24)?.let(::logoTint)
        src.recycle()
        val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(out)
        canvas.drawColor(BASE)
        if (tint != null) canvas.drawRect(0f, 0f, w.toFloat(), h.toFloat(), Paint().apply {
            shader = RadialGradient(w * 0.7f, h * 0.32f, w * 0.7f, tint and 0x00FFFFFF or (0xB3 shl 24), tint and 0x00FFFFFF, Shader.TileMode.CLAMP)
        })
        shade(canvas, w, h, false)
        out.toGpu()
    }

    /** The left darkening behind the words, the long one down to the cards, and a light one under the top bar. */
    private fun shade(canvas: Canvas, w: Int, h: Int, bright: Boolean) {
        val shade = Paint().apply {
            shader = LinearGradient(0f, 0f, w * 0.66f, 0f,
                intArrayOf(0xF70E0C0A.toInt(), if (bright) 0xBF0E0C0A.toInt() else 0x990E0C0A.toInt(), 0x000E0C0A),
                floatArrayOf(0f, 0.62f, 1f), Shader.TileMode.CLAMP)
        }
        canvas.drawRect(0f, 0f, w * 0.66f, h.toFloat(), shade)
        shade.shader = LinearGradient(0f, h.toFloat(), 0f, h * 0.55f, 0xF70E0C0A.toInt(), 0x000E0C0A, Shader.TileMode.CLAMP)
        canvas.drawRect(0f, h * 0.55f, w.toFloat(), h.toFloat(), shade)
        shade.shader = LinearGradient(0f, 0f, 0f, h * 0.14f, 0x8C0E0C0A.toInt(), 0x000E0C0A, Shader.TileMode.CLAMP)
        canvas.drawRect(0f, 0f, w.toFloat(), h * 0.14f, shade)
        for (edge in listOf(GradientDrawable.Orientation.LEFT_RIGHT, GradientDrawable.Orientation.RIGHT_LEFT,
            GradientDrawable.Orientation.TOP_BOTTOM, GradientDrawable.Orientation.BOTTOM_TOP)) {
            GradientDrawable(edge, intArrayOf(0x73000000, 0x00000000, 0x00000000, 0x00000000, 0x00000000))
                .apply { setBounds(0, 0, w, h); draw(canvas) }
        }
    }

    // Browse: the focused title, top left.
    private var heroItem: Item? = null
    private lateinit var browseInfo: LinearLayout
    private lateinit var heroName: TextView
    private lateinit var heroLogo: ImageView
    private lateinit var heroYear: TextView
    private lateinit var heroFacts: TextView
    private lateinit var heroRatings: LinearLayout
    private var heroBottom = 0 // px from the screen's foot to the hero block, for a poster row
    private var wideRow = false // the row the remote is in has wide cards (Continue Watching): the hero sits lower
    private lateinit var heroAbout: TextView
    private lateinit var heroActions: LinearLayout
    private lateinit var heroResume: TextView

    // Cinema: one title, low on the left; the stage takes the remote's keys while it shows.
    private var cinema: List<Item> = emptyList()
    private var cinemaAt = 0
    private lateinit var stage: FrameLayout
    private lateinit var cinemaInfo: LinearLayout
    private lateinit var cinemaName: TextView
    private lateinit var cinemaLogo: ImageView
    private lateinit var cinemaClock: TextClock // the cinema's own large clock; the top bar has the browsing one
    private lateinit var cinemaLine: TextView
    private lateinit var cinemaPlay: TextView
    private lateinit var dots: LinearLayout

    /** A title in a row or the cinema. Details the catalog didn't send are filled in from the meta addon. */
    private class Item(
        val id: String, val type: String, val name: String, val poster: String?,
        var art: String?, var logo: String?, var about: String, var facts: List<String>,
        val videoId: String?, val progress: Float,
        val resume: Boolean, // has watch progress (Continue Watching, Library): its facts come from the meta addon
        val timeLeft: String? = null,
        val resumeAt: Long = 0,
        val nextEpisodePending: Boolean = false,
        val finished: Boolean = false,
        var newEpisodeCount: Int = 0,
    ) {
        val live get() = isLiveType(type) // a channel or event: plays from its card, no title page
        var filled = false
        var settled = false // its details are in (or failed): what it has now is all it will get
        var warming = false
        val onFill = mutableListOf<() -> Unit>()
    }

    private var generation = 0 // which showPage answers belong to: late ones from an earlier page are dropped
    private var rowDrawListener: ViewTreeObserver.OnPreDrawListener? = null
    private var restoreCatalogFocus: (() -> Unit)? = null
    private var loadMoreSections: (() -> Unit)? = null
    private var touched = false // a key was pressed since the page opened: fresh data then waits for the next visit
    private var cinemaReady = false // the cinema's title has its art and logo (or gave up waiting) and may show
    private var cinemaToken = Any()
    private var beforeCinema: View? = null // where the remote was when the cinema took over
    private var cinemaAwake = false
    private var cinemaWakeKey = -1
    private val idle = Runnable { enterCinema() }
    private val idleDim = Runnable { dimHome(true) }
    private var homeDimmed = false
    private val advance = Runnable { if (mode == Mode.CINEMA && cinema.size > 1) showCinema((cinemaAt + 1) % cinema.size) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        profile = Profiles.active(this) ?: return switchProfile()
        HomeStyle.forgetPlatformsOnMoviesAndShows(this) // ChadFlix: the platform rows moved to the Services tab
        ratingPick = Settings.ratings(this)
        main.postDelayed({ if (!isFinishing) whatsNewOnce() }, WHATS_NEW_WAIT)
        // Last launch's first cinema title: start its art and logo now, while the library is still being read.
        getSharedPreferences("home", MODE_PRIVATE).let { prefs ->
            Images.prefetch(prefs.getString("art:${profile.id}", null), artW, urgent = true, look = shaded)
            Images.prefetch(prefs.getString("logo:${profile.id}", null), dp(LOGO_W), urgent = true)
        }

        val screenH = resources.displayMetrics.heightPixels
        // A row heading's real height: the TV's font size setting can make it taller than any fixed guess.
        val headingH = text("", HomeStyle.ROW_TITLE, bold = true).lineHeight
        // ChadFlix: the info line under the row, measured (the TV's font size changes it): facts, then a fixed 3-line plot.
        factsH = maxOf(HomeStyle.height(text("", HomeStyle.INFO, bold = true)), HomeStyle.height(text("", SMALL)))
        rowBottom = dp(HomeStyle.INFO_ABOVE) + factsH + dp(8) +
            HomeStyle.height(text("", BODY).apply { setLines(HomeStyle.PLOT_LINES) }) + dp(HomeStyle.INFO_GAP)
        val rowH = headingH + dp(ROW_TOP_ROOM + CARD_H) + rowBottom
        browseRowBlock = rowH
        // ChadFlix: the row the remote is in sits under the top bar with its info line below it; the next row peeks in.
        val rowTop = dp(HomeStyle.ROWS_TOP)
        browseRowTop = rowTop
        browseRowH = screenH - rowTop
        browseEndPad = browseRowH - rowH
        content = column(Gravity.START)
        scroller = object : ScrollView(this) {
            override fun requestChildFocus(child: View, focused: View) {
                super.requestChildFocus(child, focused)
                last = focused
                snapTo(focused)
            }

            // Rows snap into place (below) instead of scrolling just far enough, which left them cut off.
            override fun computeScrollDeltaToGetChildRectOnScreen(rect: Rect) = 0

            override fun requestChildRectangleOnScreen(child: View, rectangle: Rect, immediate: Boolean): Boolean =
                if (page.isGrid) false else super.requestChildRectangleOnScreen(child, rectangle, immediate)

            // The remote moves focus, and focus moves the rows; this never scrolls on its own. Otherwise Down on the
            // last row scrolled it away into the empty space below.
            override fun executeKeyEvent(event: KeyEvent) = false

            // Coming down from the top bar, the remote returns to where it was.
            override fun addFocusables(views: ArrayList<View>, direction: Int, focusableMode: Int) {
                val back = lastOrTitle()?.takeIf { it.isAttachedToWindow && !hasFocus() && direction == View.FOCUS_DOWN }
                if (back != null) views.add(back) else super.addFocusables(views, direction, focusableMode)
            }
        }.apply {
            isVerticalScrollBarEnabled = false
            // Grid rows scrolled up drew over the chips and the top bar: clip to the scroller's own box.
            outlineProvider = android.view.ViewOutlineProvider.BOUNDS
            clipToOutline = true
            addView(content)
            setOnScrollChangeListener { _, _, _, _, _ -> placeCaption(); updateImages(); loadMoreSections?.invoke() }
        }

        art = ImageView(this).apply { scaleType = ImageView.ScaleType.CENTER_CROP; alpha = 0f }
        artBack = ImageView(this).apply { scaleType = ImageView.ScaleType.CENTER_CROP }
        topShade = View(this).apply { background = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(0xCC000000.toInt(), 0x66000000, 0x1A000000, 0)); alpha = 0f }

        // Black from the window alone: a black layout too would be one more full screen painted under the art.
        window.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(HomeStyle.BLACK))
        setContentView(FrameLayout(this).apply {
            addView(artBack, FrameLayout.LayoutParams(-1, -1))
            addView(art, FrameLayout.LayoutParams(-1, -1))
            addView(topShade, FrameLayout.LayoutParams(-1, dp(HEADER_H + 180)))
            listShade = View(context).apply {
                background = GradientDrawable(GradientDrawable.Orientation.TL_BR,
                    intArrayOf(0xDC2C211B.toInt(), 0xB3171412.toInt(), 0xE60E0C0A.toInt()))
                visibility = View.GONE
            }
            addView(listShade, FrameLayout.LayoutParams(-1, -1))
            addView(scroller, FrameLayout.LayoutParams(-1, browseRowH).apply { topMargin = rowTop })
            moreHint = ImageView(context).apply { setImageResource(R.drawable.ic_back); scaleX = -1f; alpha = 0f }
            addView(moreHint, FrameLayout.LayoutParams(dp(32), dp(32), Gravity.END).apply {
                rightMargin = (dp(PAD) - dp(32)) / 2; topMargin = rowTop + headingH + dp(ROW_TOP_ROOM + CARD_H / 2 - 16)
            })
            cardCaption = caption("", keepPrevious = false).apply { visibility = View.GONE }
            addView(cardCaption, FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM or Gravity.START).apply {
                leftMargin = dp(PAD); bottomMargin = dp(CAPTION_BOTTOM)
            })
            cardUnderline = View(context).apply {
                background = rounded(TUNGSTEN, 2)
                visibility = View.INVISIBLE
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            }
            addView(cardUnderline, FrameLayout.LayoutParams(dp(28), dp(3)))
            nextRowHint = text("", SMALL, bold = true, color = TEXT_2).onArt().apply {
                visibility = View.GONE
                setSingleLine()
                ellipsize = TextUtils.TruncateAt.END
                maxWidth = artW / 2 - dp(PAD)
            }
            addView(nextRowHint, FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM or Gravity.END).apply {
                rightMargin = dp(PAD); bottomMargin = dp(CAPTION_BOTTOM + 5)
            })
            warningHost = FrameLayout(context).apply { clipChildren = false; clipToPadding = false }
            addView(warningHost, FrameLayout.LayoutParams(-2, -2, Gravity.TOP or Gravity.END).apply {
                topMargin = dp(HEADER_H + 54); rightMargin = dp(PAD)
            })
            // Sits on the row band, so a larger font size grows it upwards into the art, never down into the rows.
            addView(browseInfo(), FrameLayout.LayoutParams(dp(HERO_W + 2 * FOCUS_ROOM), -2, Gravity.BOTTOM).apply {
                heroBottom = screenH - rowTop - rowH + dp(HomeStyle.INFO_GAP) // ChadFlix: under the row
                // Room inside the block for the focused Resume to grow into (it was cut flat on the left).
                leftMargin = dp(HomeStyle.INSET - FOCUS_ROOM); bottomMargin = heroBottom
            })
            addView(stage(), FrameLayout.LayoutParams(-1, -1))
            // ChadFlix: no genre chips on Movies and Shows, whose rows sit right under the top bar; My Lists keeps its chips.
            genreStrip = object : HorizontalScrollView(context) {
                override fun setVisibility(v: Int) = super.setVisibility(if (v == View.VISIBLE && page.isCatalog) View.GONE else v)
            }.apply {
                isHorizontalScrollBarEnabled = false
                visibility = View.GONE
            }
            addView(genreStrip, FrameLayout.LayoutParams(-1, dp(44), Gravity.TOP).apply {
                topMargin = dp(HEADER_H + 6)
            })
            addView(header().apply { alpha = 0f }, FrameLayout.LayoutParams(-1, dp(HEADER_H))) // fades in if the page wants it
            val (loading, loadingText) = loadingOverlay()
            loadingView = loading
            loadingStatus = loadingText
            addView(loadingView, FrameLayout.LayoutParams(-1, -1))
        })

        window.decorView.viewTreeObserver.addOnGlobalFocusChangeListener { _, now ->
            rows.filter { it.active && (now == null || !isInside(now, it.view)) }.forEach { it.light(false) }
            if (mode == Mode.CINEMA) chrome() // the top bar shows only while the remote is on it
            // The caption names the focused card: none while the remote is up in the top bar.
            if (now !is MediaCardView) {
                cardCaption.visibility = View.GONE
                cardUnderline.visibility = View.INVISIBLE
            }
            // Rows settle after a focus move (the next heading can peek in): name it once, whichever shows.
            main.removeCallbacks(nextRowCheck); main.postDelayed(nextRowCheck, 300)
        }
        val initialPage = Page.find(intent.getStringExtra("page"))
            ?: when (Settings.startTab(this)) {
                "movies" -> if (Settings.tabShown(this, "movies")) Page.MOVIES else Page.HOME
                "shows" -> if (Settings.tabShown(this, "shows")) Page.SHOWS else Page.HOME
                "library" -> if (Settings.tabShown(this, "library")) Page.LIBRARY else Page.HOME
                "discover" -> if (Settings.tabShown(this, "discover")) Page.DISCOVER else Page.HOME
                else -> Page.HOME
            }
        showPage(initialPage)
        main.postDelayed(::warmPages, WARM_AFTER)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        Page.find(intent.getStringExtra("page"))
            ?.takeIf { it != page }?.let(::showPage)
        // ChadFlix: back from the search page by its top bar: the remote lands on the page's name, not on Search again.
        if (search?.isFocused == true) navItems[page]?.requestFocus()
    }

    /**
     * Once Home has settled: the top rows' first posters on Shows and Movies, from last time's catalogs, so opening those
     * pages from the top bar draws them at once instead of as blank tiles.
     */
    /**
     * A page's rows: catalogs with the same purpose share a row, and Shows and Movies end with a row per main genre (the
     * first catalog that filters by it) unless a row of that name is already there; the chips jump to these rows.
     */
    private fun sections(p: Page, shelves: List<Pair<Addon.Catalog, Shelf>>, featuredHome: Boolean = false): List<Triple<String, List<Pair<Addon.Catalog, Shelf>>, String?>> {
        if (featuredHome) {
            val homeShelves = shelves.filter { it.second.page == Page.HOME }
            val movieShelves = shelves.filter { it.second.page == Page.MOVIES }
            val showShelves = shelves.filter { it.second.page == Page.SHOWS }
            val homeSections = homeShelves.map { Triple(it.second.title, listOf(it), null as String?) }
            val genres = emptyList<String>()
            val movieMerged = movieShelves.groupBy { shortRowName(it.second.title, genres) }
                .map { (name, list) -> Triple(homeSectionTitle(name, isMovie = true), list, null as String?) }
                .take(3)
            val showMerged = showShelves.groupBy { shortRowName(it.second.title, genres) }
                .map { (name, list) -> Triple(homeSectionTitle(name, isMovie = false), list, null as String?) }
                .take(3)
            val combined = mutableListOf<Triple<String, List<Pair<Addon.Catalog, Shelf>>, String?>>()
            val maxCount = maxOf(movieMerged.size, showMerged.size)
            for (i in 0 until maxCount) {
                if (i < movieMerged.size) combined += movieMerged[i]
                if (i < showMerged.size) combined += showMerged[i]
            }
            return homeSections + combined
        }
        val genreShortcuts = p.hasGenreShortcuts
        val genres = catalogGenres(shelves.flatMap { it.first.genres }
            .map { if (genreShortcuts) genreLabel(it) else it })
        return if (!p.isCatalog) shelves.map { Triple(it.second.title, listOf(it), null as String?) } else {
            val merged = shelves.groupBy { (_, shelf) ->
                if (genreShortcuts && shelf.rank > 2) genres.firstOrNull { it.equals(genreLabel(shelf.title), true) } ?: shelf.title
                else shortRowName(shelf.title, genres)
            }.map { (name, list) -> Triple(name, list, null as String?) }
            val genreShelves = genres.take(GENRE_ROWS).filter { g -> merged.none { it.first.equals(g, true) } }
                .mapNotNull { g ->
                    val sources = if (genreShortcuts) shelves.filter { genreOption(it.first.genres, g) != null }
                        else listOfNotNull(shelves.firstOrNull { g in it.first.genres })
                    sources.takeIf { it.isNotEmpty() }?.let { Triple(g, it, g as String?) }
                }
            if (genreShortcuts) merged else merged + genreShelves // Discover has the genres now
        }
    }

    /**
     * Shows' and Movies' rows read from disk into memory ahead, so the first switch to them builds at once too; and My
     * List's first three rows of posters fetched, so its grid doesn't open dark on the left while they download.
     */
    private fun warmPages() = background({
        Stremio.savedLibrary(profile.authKey).orEmpty()
            .filter { !it.optBoolean("removed") && !isLiveType(it.optString("type")) }
            .sortedByDescending { it.optString("_ctime") }.take(LIST_COLUMNS * 3)
            .forEach { it.text("poster")?.let { url -> Images.prefetch(url, (libraryPosterWidth() * CARD_GROW).toInt()) } }
        val catalogs = Stremio.savedAddons(profile.authKey).orEmpty().flatMap { it.catalogs }
        for (p in listOf(Page.SHOWS, Page.MOVIES)) {
            val shelves = catalogs.mapNotNull { c -> shelf(c)?.takeIf { it.page == p }?.let { c to it } }.sortedBy { it.second.rank }
            val rowsOn = HomeStyle.rowsOn(this, p.label) // ChadFlix: add-on rows that are off aren't warmed
            sections(p, shelves).filter { HomeStyle.isOn(rowsOn, it.first, p == Page.MOVIES) }.forEachIndexed { i, (title, sources, picked) ->
                if (p.hasGenreShortcuts && picked != null) return@forEachIndexed
                val key = catalogAnswerKey(p.label, title, picked, sources.map { (c, _) ->
                    "${c.addon.transportUrl}/${c.type}/${c.id}"
                })
                if (answers.containsKey(key)) return@forEachIndexed
                val kept = sources.map { (c, _) -> (c.addon.savedCatalog(c, picked) ?: return@forEachIndexed) to true }
                answers.putIfAbsent(key, kept)
                if (i < 2) kept.flatMap { it.first }.take(FIRST).forEach { Images.prefetch(it.poster ?: it.background, dp(CARD_W)) }
                // The first title's full-screen art too, so the page opens on its own picture, not the last page's.
                if (i == 0) kept.firstOrNull()?.first?.firstOrNull()?.background?.let { Images.prefetch(it, artW, look = shaded) }
            }
        }
    }) {}

    private var lastFeaturedHome = false
    private var lastHiddenRows: Set<String> = emptySet()
    private var lastHomeRows = "" // ChadFlix: Customize Home's choices this page was built with

    // Back from a title or the player: Continue Watching may have moved on, so this asks Stremio, not the disk. Only
    // the library's rows are redrawn, so the remote stays where it was on the rest of the page.
    override fun onRestart() {
        super.onRestart()
        val featured = Settings.featuredHome(this)
        val hiddenRows = Settings.hiddenRows(this)
        val hiddenRowsChanged = hiddenRows != lastHiddenRows || HomeStyle.signature(this, page.label) != lastHomeRows
        lastHiddenRows = hiddenRows
        arrangeTabs()
        if (page != Page.HOME && !Settings.tabShown(this, page.tabId)) { // its tab was hidden in Settings › Tabs
            showPage(Page.HOME)
            return
        }
        if (hiddenRowsChanged || (page == Page.HOME && featured != lastFeaturedHome)) {
            lastFeaturedHome = featured
            showPage(page)
        } else {
            refreshLibrary?.invoke()
            refreshAddons?.invoke()
        }
    }

    private var refreshAddons: (() -> Unit)? = null // the open page's add-on check, run again on coming back to Alder
    private var rebuildRows: (() -> Unit)? = null // ChadFlix: the open page's rows again, after Move Up / Move Down
    private var pageRows: List<String> = emptyList() // ChadFlix: the open page's row titles, in order
    private var showingFor = "" // ChadFlix: "page:row" whose Showing menu was opened last
    private var showingDialog: android.app.Dialog? = null // and that menu
    private val showingChanged = mutableMapOf<String, Pair<String, String>>() // ChadFlix: "page:row" to (row key, the choice last shown; "!" = Exclusives)

    private var refreshLibrary: (() -> Unit)? = null // the open page's library rows (Home, Library), asked again
    private var last: View? = null // where the remote last was in the rows

    /**
     * ChadFlix: the backdrop of the card [row] would widen when the remote comes into it, fetched at the wide size. A
     * fetch still queued when the remote has moved on to other rows is dropped ([landingTargets]).
     */
    private fun prefetchLanding(row: Row?, round: Int) {
        val item = row?.takeIf { page.isBrowse && it.homeRow }?.landing ?: return
        val still = { round == landingTargets }
        val url = item.art
        if (url != null) return Images.prefetch(url, dp(HomeStyle.WIDE_W), wide = true, still = still)
        if (item.filled || HomeStyle.isTile(item.id)) return
        item.onFill += { if (still()) item.art?.let { Images.prefetch(it, dp(HomeStyle.WIDE_W), wide = true, still = still) } }
        fill(item)
    }

    @Volatile private var landingTargets = 0 // ChadFlix: which round of landing prefetches is current

    // ChadFlix: the title page's full-screen art and logo, fetched once the remote has rested on a home card a moment,
    // at the title page's own sizes, so OK opens on pictures already in memory. One title at a time: moving on drops
    // what was fetched for the last one.
    @Volatile private var titleWanted: Item? = null
    private var titleFetched: Item? = null
    private val titleTask = Runnable { fetchTitle() }

    private fun fetchTitleSoon(item: Item?) {
        main.removeCallbacks(titleTask)
        titleWanted = item?.takeIf { !it.live && !HomeStyle.isTile(it.id) }
        if (titleWanted != null) main.postDelayed(titleTask, TITLE_AHEAD_MS)
    }

    private fun fetchTitle() {
        val item = titleWanted ?: return
        if (isFinishing || !hasWindowFocus()) return
        titleFetched?.takeIf { it !== item }?.let { old ->
            old.art?.takeIf { it != item.art }?.let { Images.forget(it, artW) }
            old.logo?.takeIf { it != item.logo }?.let { Images.forget(it, Images.TITLE_LOGO_PX, Images.trimmedLogo) }
        }
        titleFetched = item
        val still = { titleWanted === item }
        item.art?.let { Images.prefetch(it, artW, still = still) }
        item.logo?.let { Images.prefetch(it, Images.TITLE_LOGO_PX, look = Images.trimmedLogo, still = still) }
    }

    /** ChadFlix: the landing cards of the rows above and below [row]. */
    private fun prefetchNeighbours(row: Row) {
        val round = ++landingTargets
        val ordered = rows.filter { it.view.isAttachedToWindow && !it.grid }.sortedBy { offsetIn(it.view) }
        val at = ordered.indexOf(row).takeIf { it >= 0 } ?: return
        prefetchLanding(ordered.getOrNull(at - 1), round)
        prefetchLanding(ordered.getOrNull(at + 1), round)
    }

    // ChadFlix: Settings › Show FPS. Off, nothing is registered. On, Android reports each drawn frame on a thread of its
    // own, where a counter goes up (nothing allocated per frame), and the corner's words change once a second.
    private var fpsView: TextView? = null
    private var fpsThread: android.os.HandlerThread? = null
    private var fpsListener: Any? = null // a Window.OnFrameMetricsAvailableListener (Android 7+)
    private val fpsFrames = java.util.concurrent.atomic.AtomicInteger()
    private var fpsShown = -1
    private val fpsTick = object : Runnable {
        override fun run() {
            val n = fpsFrames.getAndSet(0)
            fpsView?.let { v ->
                // Updating the words draws a frame itself: a second with at most that one is idle, the last count dimmed.
                if (n <= 1) v.alpha = 0.35f
                else { if (n != fpsShown) { fpsShown = n; v.text = getString(R.string.fps, n) }; v.alpha = 0.7f }
            }
            main.postDelayed(this, 1000L)
        }
    }

    private fun startFps() {
        if (android.os.Build.VERSION.SDK_INT < 24 || fpsListener != null || !Settings.showFps(this)) return
        val view = fpsView ?: text("", 10f, color = HomeStyle.WHITE).also { v ->
            fpsView = v
            findViewById<FrameLayout>(android.R.id.content).addView(v, FrameLayout.LayoutParams(dp(48), -2, Gravity.TOP or Gravity.START) // fixed width: a new number never re-lays out the screen
                .apply { leftMargin = dp(8); topMargin = dp(4) }) // the corner, left of and above the avatar
        }
        view.alpha = 0.35f
        view.visibility = View.VISIBLE
        val thread = android.os.HandlerThread("fps").also { it.start() }
        val listener = android.view.Window.OnFrameMetricsAvailableListener { _, _, dropped -> fpsFrames.addAndGet(1 + dropped) } // count frames dropped before delivery too
        window.addOnFrameMetricsAvailableListener(listener, android.os.Handler(thread.looper))
        fpsThread = thread
        fpsListener = listener
        fpsFrames.set(0)
        main.postDelayed(fpsTick, 1000L)
    }

    private fun stopFps() {
        main.removeCallbacks(fpsTick)
        val listener = fpsListener ?: return
        if (android.os.Build.VERSION.SDK_INT >= 24) runCatching {
            window.removeOnFrameMetricsAvailableListener(listener as android.view.Window.OnFrameMetricsAvailableListener)
        }
        fpsListener = null
        fpsThread?.quitSafely()
        fpsThread = null
        fpsView?.visibility = View.GONE
    }

    /** ChadFlix: where the remote goes back to in the rows: the last card, but a genre row's title rather than its tile. */
    private fun lastOrTitle(): View? {
        val card = last as? MediaCardView ?: return last
        if (!HomeStyle.isTile((card.payload as? Item)?.id.orEmpty())) return card
        val row = rows.firstOrNull { isInside(card, it.view) } ?: return card
        return row.cards.getOrNull(row.selected) ?: card
    }

    override fun onResume() {
        super.onResume()
        if (!::profile.isInitialized) return
        startFps()
        // ChadFlix: Settings › Ratings may have changed: the focused card's badge follows (no score hides it).
        Settings.ratings(this).takeIf { it != ratingPick }?.let { pick ->
            ratingPick = pick
            rows.firstOrNull { it.active }?.let { r -> r.cards.getOrNull(r.selected)?.let { c -> r.hideBadge(); r.rate(c, r.items[r.selected], fade = false) } }
        }
        washOn = Settings.wash(this) // back from Settings: the choice there applies
        if (!washOn && page.isBrowse) { main.removeCallbacks(artTask); resetArt() }
        val key = profile.authKey
        background({ syncWatchProgress(key) }) {}
        // ponytail: idle dim and the cinema screensaver are off (2026-10-26, not wanted yet); post `idle` here to bring the cinema back.
        updateDot?.visibility = if (Updates.hasUpdate(this)) View.VISIBLE else View.GONE
        // Back from Settings: the hero shows the ratings picked there (Letterboxd needs a redraw to ask for its score).
        if (mode == Mode.BROWSE) heroItem?.let(::showHero)
        if (Updates.due(this)) background({ Updates.latest(applicationContext) }) { r ->
            val release = r.getOrNull()?.takeIf { newerVersion(it.version, Updates.mine(this)) } ?: return@background
            updateDot?.visibility = View.VISIBLE
            if (Updates.offerOnce(this, release.version)) choose(getString(R.string.updates_ready, release.version),
                listOf(getString(R.string.action_later), getString(R.string.action_install)),
                getString(R.string.updates_ready_desc)) { if (it == 1) installUpdate(release) }
        }
    }

    private var resolvingStreams: Cancellable? = null
    private lateinit var loadingView: FrameLayout
    private lateinit var loadingStatus: TextView

    private fun cancelStreamResolution() {
        resolvingStreams?.cancel()
        resolvingStreams = null
        if (::loadingView.isInitialized) loadingView.visibility = View.GONE
    }

    override fun onPause() {
        main.removeCallbacks(openSearch) // ChadFlix: resting on Search, then gone (Back, a dialog): it doesn't open behind
        cancelStreamResolution()
        stopFps()
        super.onPause()
        main.removeCallbacks(idle)
        main.removeCallbacks(idleDim)
        main.removeCallbacks(advance)
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.keyCode == KeyEvent.KEYCODE_DPAD_UP || event.keyCode == KeyEvent.KEYCODE_DPAD_DOWN) {
            if (event.action == KeyEvent.ACTION_DOWN) verticalHeld = event.repeatCount > 0
            else if (verticalHeld) { verticalHeld = false; rows.firstOrNull { it.active }?.settle(animate = false); showInfo() } // where the hold ends
        }
        if (event.action == KeyEvent.ACTION_DOWN) {
            restoreCatalogFocus = null
            if (event.keyCode == KeyEvent.KEYCODE_BACK && resolvingStreams != null) {
                cancelStreamResolution()
                return true
            }
            touched = true
            dimHome(false)
        }
        val ok = event.keyCode == KeyEvent.KEYCODE_DPAD_CENTER || event.keyCode == KeyEvent.KEYCODE_ENTER
        if (mode == Mode.CINEMA) {
            if (event.keyCode == cinemaWakeKey) {
                if (event.action == KeyEvent.ACTION_UP) cinemaWakeKey = -1
                return true
            }
            if (!cinemaAwake) {
                if (event.action == KeyEvent.ACTION_DOWN) {
                    cinemaAwake = true
                    cinemaWakeKey = event.keyCode
                    cinemaPlay.isFocusable = true
                    cinemaPlay.requestFocus()
                }
                return true
            }
            if (event.keyCode == KeyEvent.KEYCODE_BACK) {
                if (event.action == KeyEvent.ACTION_UP) enterBrowse()
                return true
            }
            if (!ok) return true
        }
        // Back from the rows goes up, not out: to the chips on a page that has them (a genre jump left you deep in
        // the page), else to the open page's name. Back from there leaves as usual.
        if (event.keyCode == KeyEvent.KEYCODE_BACK && mode == Mode.BROWSE && scroller.hasFocus()) {
            if (event.action == KeyEvent.ACTION_UP) {
                val chip = pageChip()
                if (chip?.isShown != true || !chip.requestFocus()) navItems[page]?.requestFocus()
            }
            return true
        }
        if (event.action == KeyEvent.ACTION_DOWN && (page.isCatalog || page.isGrid) && mode == Mode.BROWSE) {
            if (event.keyCode == KeyEvent.KEYCODE_DPAD_DOWN && header.hasFocus()) {
                if (warning()?.requestFocus() == true) return true
                pageChip()?.requestFocus()?.let { if (it) return true }
                if (page.isCatalog && genreStrip.visibility != View.VISIBLE && firstRow()?.focusFirst() == true) return true
            }
            if (event.keyCode == KeyEvent.KEYCODE_DPAD_DOWN && genreStrip.hasFocus()) {
                (lastOrTitle()?.takeIf { it.isAttachedToWindow && it.isShown } ?: firstRow()?.cards?.firstOrNull()
                    ?: librarySlot?.findViewWithTag<View>("clear_filters"))
                    ?.requestFocus()?.let { if (it) return true }
            }
            if (event.keyCode == KeyEvent.KEYCODE_DPAD_DOWN && heroResume.isFocused) {
                if (firstRow()?.focusFirst() == true) return true
            }
            if (event.keyCode == KeyEvent.KEYCODE_DPAD_UP && heroResume.isFocused) {
                pageChip()?.requestFocus()?.let { if (it) return true }
                if (navItems[page]?.requestFocus() == true) return true
            }
            if (event.keyCode == KeyEvent.KEYCODE_DPAD_UP && currentFocus?.let { focused ->
                    firstRow()?.view?.let { isInside(focused, it) }
                } == true) {
                if (heroActions.visibility == View.VISIBLE && heroResume.requestFocus()) return true
                pageChip()?.requestFocus()?.let { if (it) return true }
                if (page.isCatalog && genreStrip.visibility != View.VISIBLE && navItems[page]?.requestFocus() == true) return true
            }
        }
        if (event.action == KeyEvent.ACTION_DOWN && scroller.hasFocus() && mode == Mode.BROWSE &&
            (event.keyCode == KeyEvent.KEYCODE_DPAD_UP || event.keyCode == KeyEvent.KEYCODE_DPAD_DOWN)) {
            val ordered = rows.filter { it.view.isAttachedToWindow }.sortedBy { offsetIn(it.view) }
            val at = ordered.indexOfFirst { r -> currentFocus?.let { isInside(it, r.view) } == true }
            if (at >= 0) {
                val next = ordered.getOrNull(at + if (event.keyCode == KeyEvent.KEYCODE_DPAD_DOWN) 1 else -1)
                if (next != null) {
                    next.view.visibility = View.VISIBLE
                    val to = landingCard(ordered[at].selected, next.selected, next.cards.size, next.grid)
                    if (next.cards.getOrNull(to)?.requestFocus() == true) return true
                }
            }
        }
        // Up from Home's row goes to the top bar by hand: while the row slides after fast Right presses, the focus
        // search measures the moving card and sometimes finds nothing above it.
        if (event.keyCode == KeyEvent.KEYCODE_DPAD_UP && event.action == KeyEvent.ACTION_DOWN && page == Page.HOME &&
            mode == Mode.BROWSE && currentFocus?.let { f -> firstRow()?.view?.let { isInside(f, it) } } == true) {
            if (navItems[page]?.requestFocus() == true) return true
        }
        // Down from the top bar goes to the titles, past Resume (one Up from the cards), to where the remote last was.
        if (event.keyCode == KeyEvent.KEYCODE_DPAD_DOWN && event.action == KeyEvent.ACTION_DOWN && header.hasFocus()) {
            if (warning()?.requestFocus() == true) return true
            val to = lastOrTitle()?.takeIf { it.isAttachedToWindow && it.isShown } ?: firstRow()?.let { it.cards[it.selected] }
            if (to != null) { to.requestFocus(); return true }
        }
        return super.dispatchKeyEvent(event)
    }

    private fun dimHome(on: Boolean) {
        if ((!page.isBrowse && !page.isGrid) || mode != Mode.BROWSE || homeDimmed == on) return
        homeDimmed = on
        val alpha = if (on) 0.3f else 1f
        header.animate().alpha(alpha).setDuration(IDLE_MS).start()
        scroller.animate().alpha(alpha).setDuration(IDLE_MS).start()
        browseInfo.animate().alpha(if (heroItem == null) 0f else alpha).setDuration(IDLE_MS).start()
        cardCaption.animate().alpha(alpha).setDuration(IDLE_MS).start()
        nextRowHint.animate().alpha(alpha).setDuration(IDLE_MS).start()
        genreStrip.animate().alpha(alpha).setDuration(IDLE_MS).start()
    }

    private fun switchProfile() {
        WatchNext.invalidate()
        startActivity(Intent(this, ProfilesActivity::class.java).putExtra("pick", true)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
        finish()
    }

    // ---------- top bar ----------

    private fun header(): View {
        header = object : FrameLayout(this) {
            // Coming up from below, the remote lands on the page you're on.
            override fun addFocusables(views: ArrayList<View>, direction: Int, focusableMode: Int) {
                val current = navItems[page]
                if (hasFocus() || direction != View.FOCUS_UP || current == null) super.addFocusables(views, direction, focusableMode)
                else views.add(current)
            }
        }.apply { setPadding(dp(PAD), 0, dp(PAD), dp(12)) } // ChadFlix: the bar's centre 32 dp down, as on Netflix

        // The profile: a small round avatar on the margin. OK opens a small menu (switch profile, settings).
        val avatar = text(profile.name.take(1).uppercase(), SMALL, bold = true, color = TUNGSTEN).apply {
            gravity = Gravity.CENTER
            contentDescription = getString(R.string.nav_profile)
            val rest = rounded(HomeStyle.AVATAR, 20) // ChadFlix: a grey circle, white while focused
            fun style(focused: Boolean) {
                background = if (focused) rounded(HomeStyle.WHITE, 20) else rest
                setTextColor(if (focused) HomeStyle.BLACK else HomeStyle.WHITE)
                grow(focused).start()
            }
            style(false)
            isFocusable = true
            setOnFocusChangeListener { _, has -> style(has); styleNav() }
            setOnClickListener { profileMenu() }
        }
        val dot = View(this).apply {
            background = rounded(TUNGSTEN, 4)
            elevation = dp(4).toFloat()
            visibility = if (Updates.hasUpdate(this@MainActivity)) View.VISIBLE else View.GONE
        }
        updateDot = dot
        val avatarBox = FrameLayout(this).apply {
            clipChildren = false
            clipToPadding = false
            addView(avatar, FrameLayout.LayoutParams(dp(30), dp(30)))
            addView(dot, FrameLayout.LayoutParams(dp(8), dp(8), Gravity.TOP or Gravity.END).apply {
                topMargin = dp(1)
                marginEnd = dp(1)
            })
        }
        // Every destination in one dark capsule, Search first as an icon (Left from Home), like Google TV's top bar.
        val nav = LinearLayout(this).apply {
            navBar = this
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(4), dp(4), dp(4), dp(4))
        }
        // A gap between pills, so the open page's and the focused one never touch.
        fun <V : View> V.inNav(width: Int = -2, height: Int = -2) = apply {
            isFocusable = true
            nav.addView(this, LinearLayout.LayoutParams(width, height).apply { if (nav.childCount > 0) marginStart = dp(NAV_GAP) })
        }
        // As tall as the words' pills (match parent), the glyph 18 dp inside it.
        search = ImageView(this).apply {
            setImageResource(R.drawable.ic_search)
            contentDescription = getString(R.string.nav_search)
            setPadding(dp(13), dp(7), dp(13), dp(7))
            // ChadFlix: like the tabs, resting on Search opens it (no OK needed).
            setOnFocusChangeListener { _, has ->
                styleNav()
                main.removeCallbacks(openSearch)
                if (has) main.postDelayed(openSearch, SWITCH_WAIT)
            }
            setOnClickListener { main.removeCallbacks(openSearch); startActivity(Intent(this@MainActivity, SearchActivity::class.java)) }
        }.inNav(width = dp(44), height = -1)
        // A page opens as soon as the remote rests on its name. OK on Shows or Movies goes to its genres. Live shows only
        // once the profile's add-ons have a live catalog; order and hidden tabs come from Settings › Tabs ([arrangeTabs]).
        Page.fixed.forEach { p -> navItem(p).inNav() }
        arrangeTabs()
        header.addView(avatarBox, FrameLayout.LayoutParams(dp(30), dp(30), Gravity.START or Gravity.CENTER_VERTICAL))
        header.addView(nav, FrameLayout.LayoutParams(-2, -2, Gravity.CENTER))
        // ChadFlix: a gear for Settings where the clock was.
        header.addView(ImageView(this).apply {
            setImageResource(R.drawable.ic_settings)
            contentDescription = getString(R.string.menu_settings)
            setPadding(dp(12), dp(7), dp(12), dp(7))
            isFocusable = true
            fun style(on: Boolean) {
                background = if (on) HomeStyle.pill(HomeStyle.WHITE) else null
                setColorFilter(if (on) HomeStyle.BLACK else HomeStyle.WHITE)
            }
            style(false)
            setOnFocusChangeListener { _, has -> style(has); styleNav() }
            setOnClickListener { startActivity(Intent(this@MainActivity, SettingsActivity::class.java)) }
        }, FrameLayout.LayoutParams(dp(44), dp(32), Gravity.END or Gravity.CENTER_VERTICAL))
        styleNav()
        return header
    }

    private var lastAddons: List<Addon> = emptyList()
    private lateinit var navBar: LinearLayout

    /** A page's name in the top bar; OK on it opens it, or goes down into it when it's already open. */
    private fun navItem(p: Page) = text(pageName(p), HomeStyle.TAB_SP).apply {
        id = View.generateViewId()
        navItems[p] = this
        gravity = Gravity.CENTER
        compoundDrawablePadding = dp(2)
        setPadding(dp(16), dp(5), dp(16), dp(5))
        setOnFocusChangeListener { _, has ->
            styleNav()
            main.removeCallbacks(switchPage)
            if (has && p != page) { pending = p; main.postDelayed(switchPage, SWITCH_WAIT) }
        }
        setOnClickListener {
            main.removeCallbacks(switchPage)
            when {
                p != page -> { jump = true; showPage(p) }
                p.isCatalog -> (genreChips[genre]?.takeIf { it.isShown } ?: warning() ?: firstRow()?.cards?.firstOrNull())?.requestFocus()
                p.isGrid -> pageChip()?.requestFocus()
                else -> firstRow()?.focusFirst()
            }
        }
    }

    private fun pageName(p: Page) = p.type?.let { typeTabName(this, it) } ?: getString(p.words)

    /** The page's id in Settings › Tabs ([tabsFor]). */
    private val Page.tabId get() = when (this) {
        Page.SHOWS -> "shows"
        Page.MOVIES -> "movies"
        Page.LIVE -> "live"
        Page.LIBRARY -> "library"
        Page.SERVICES -> "services"
        Page.DISCOVER -> "discover"
        else -> type?.let { "type:$it" } ?: "home"
    }

    /**
     * The tabs after Home as this profile arranged them in Settings › Tabs, hidden ones gone. Live, and a page for each
     * other catalog type (Anime, Trakt…), are there only while the add-ons list such catalogs. The open page keeps its tab.
     */
    private fun arrangeTabs() {
        val types = lastAddons.flatMap { a -> a.catalogs.map { it.type } }
        val added = extraPageTypes(types).map(Page::of).filter { it !in navItems }
        added.forEach { p ->
            navBar.addView(navItem(p).apply { isFocusable = true }, LinearLayout.LayoutParams(-2, -2).apply { marginStart = dp(NAV_GAP) })
        }
        // A tab added once the add-ons answer gets the others' colour and empty dot too; without the dot it sat lower.
        if (added.isNotEmpty()) styleNav()
        val order = orderTabs(Settings.tabOrder(this), tabsFor(types))
        var at = navBar.indexOfChild(navItems[Page.HOME]) + 1
        order.mapNotNull { id -> navItems.entries.firstOrNull { it.key.tabId == id }?.value }.forEach { item ->
            if (navBar.indexOfChild(item) != at) {
                val focused = item.isFocused
                navBar.removeView(item)
                navBar.addView(item, at)
                if (focused) item.requestFocus()
            }
            at++
        }
        navItems.forEach { (p, item) ->
            if (p != Page.HOME) item.visibility = if ((p.tabId in order && Settings.tabShown(this, p.tabId)) || p == page) View.VISIBLE else View.GONE
        }
        // ChadFlix: Home shows only when it's the start page (Settings › Default tab) or open.
        navItems[Page.HOME]?.visibility = if (page == Page.HOME || Settings.startTab(this) == "home") View.VISIBLE else View.GONE
    }

    private fun showLiveTab(addons: List<Addon>) {
        lastAddons = addons
        arrangeTabs()
    }

    private var pending = Page.HOME
    private var jump = false // OK on a page's name: go down into its rows once they're in
    private val openSearch = Runnable { if (search?.isFocused == true && hasWindowFocus()) startActivity(Intent(this, SearchActivity::class.java)) }
    private val switchPage = Runnable { if (pending != page && navItems[pending]?.isFocused == true) { jump = false; showPage(pending) } }

    private fun profileMenu() {
        val update = Updates.available(this)
        val usual = listOf(getString(R.string.menu_switch_profile), getString(R.string.menu_settings))
        val options = if (update != null) listOf(getString(R.string.menu_install_update, update.version)) + usual else usual
        choose(profile.name, options) { i ->
            if (update != null) {
                when (i) {
                    0 -> installUpdate(update)
                    1 -> switchProfile()
                    2 -> startActivity(Intent(this, SettingsActivity::class.java))
                }
            } else {
                if (i == 0) switchProfile() else startActivity(Intent(this, SettingsActivity::class.java))
            }
        }
    }

    /**
     * ChadFlix: a white pill with black words follows the remote; the open page keeps its pill, white while the remote is
     * down in the rows and faint while it's on another item up here.
     */
    private fun styleNav() {
        val inBar = header.findFocus() != null
        if (inBar) prefetchLanding(firstRow(), ++landingTargets) // ChadFlix: Down from here lands on a wide card: its backdrop comes ahead
        navItems.forEach { (p, item) ->
            val lit = item.isFocused || (p == page && !inBar)
            item.background = if (lit) HomeStyle.pill(HomeStyle.WHITE) else if (p == page) HomeStyle.pill(HomeStyle.OPEN_TAB) else null
            item.setTextColor(if (lit) HomeStyle.BLACK else HomeStyle.WHITE)
            item.text = pageName(p)
        }
        search?.let {
            it.background = if (it.isFocused) HomeStyle.pill(HomeStyle.WHITE) else null
            it.setColorFilter(if (it.isFocused) HomeStyle.BLACK else HomeStyle.WHITE)
        }
    }

    // ---------- the art, and what shows over it ----------

    /**
     * Shows [item]'s art across the screen, crossfading from the last title's: the old picture stays underneath until
     * the new one is in, so nothing flashes dark or soft. Art that's already here shows at once; otherwise it waits a
     * beat, so racing along a row doesn't start a download per card.
     */
    private fun showArt(item: Item, ready: (() -> Unit)? = null) {
        main.removeCallbacks(artTask)
        if (page.isBrowse && mode == Mode.BROWSE) return run { showWash(item); ready?.invoke() } // ChadFlix: a soft wash, not the art
        val settledBrowse = page.polishedRail && mode == Mode.BROWSE
        val libraryGrid = page.isGrid
        if (item.live && item.art == null && item.poster == null) return run { if (item.settled) clearArt(); ready?.invoke() }
        val (url, look) = backdropOf(item) ?: run { if (settledBrowse) resetArt(); return }
        if (url == art.tag) return run { ready?.invoke() }
        val cached = Images.cached(url, artW, look)
        if (settledBrowse && !cached) resetArt()
        artTask = Runnable {
            art.animate().cancel()
            // Racing along a row cancels fades midway: only a picture that's mostly in replaces the one behind,
            // otherwise the back keeps the last full picture and nothing flashes black.
            if (art.drawable != null && art.alpha > 0.5f) {
                artBack.setImageDrawable(art.drawable)
                artBack.scaleX = art.scaleX
                artBack.scaleY = art.scaleY
            }
            art.scaleX = 1f
            art.scaleY = 1f
            art.alpha = 0f
            Images.load(url, art, artW, urgent = true, look = look, failed = ready) {
                // Once the new art covers it, the old one lets go: otherwise the GPU paints a hidden full screen every frame.
                art.enter(if (settledBrowse) HOME_ART_MS else if (artBack.drawable == null) LIGHTS_MS else ART_MS).alpha(1f).withEndAction {
                    artBack.setImageDrawable(null)
                }.start()
                refrost()
                ready?.invoke()
            }
        }
        val delay = if (settledBrowse || libraryGrid) 0L else artDelay(held, cached, ready != null)
        if (delay == 0L) artTask.run() else main.postDelayed(artTask, delay)
    }

    /**
     * ChadFlix: Home's Soft Wash ([HomeStyle.wash]) in the art views Alder already has, crossfading as its art did. Only
     * once the remote rests on a title (WASH_WAIT), never while a D-pad is held or the rows slide: one tiny decode and one
     * crossfade per stop. A title with no backdrop (the Showing tile) keeps the wash there is. In steady state only one of
     * the two views holds a picture, so the screen gets one extra layer.
     */
    private var washOn = true
    private var ratingPick = "" // ChadFlix: Settings › Ratings, for the focused card's badge (read in onCreate and onResume)

    private fun showWash(item: Item) {
        if (!washOn) return // Settings › Background wash off: black, and nothing runs
        val url = item.art ?: return
        if (url == art.tag) return
        artTask = Runnable {
            if (held || slideAnim != null) return@Runnable run { main.postDelayed(artTask, HomeStyle.WASH_WAIT) }
            if (heroItem !== item || !page.isBrowse) return@Runnable
            art.animate().cancel()
            if (art.drawable != null && art.alpha > 0.5f) artBack.setImageDrawable(art.drawable)
            art.alpha = 0f
            Images.load(url, art, HomeStyle.WASH_W, look = HomeStyle.wash) {
                if (heroItem !== item || held) { art.tag = null; return@load } // moved on while it loaded: no stale wash
                art.enter(HOME_ART_MS).alpha(1f).withEndAction { artBack.setImageDrawable(null) }.start()
            }
        }
        main.postDelayed(artTask, HomeStyle.WASH_WAIT)
    }

    /**
     * What fills the screen behind [item]: its art; once it's certain there's none, a live channel's logo as a colour
     * wash, or else the poster (a poster cropped to the screen is blurry and half a face, so only as a last resort).
     */
    private fun backdropOf(item: Item): Pair<String, Images.Look>? = item.art?.let { it to shaded }
        ?: item.poster?.takeIf { item.settled }?.let { it to if (item.live) washed else shaded }

    /** A live channel with no pictures at all: the plain dark base, rather than the last title's art staying on. */
    private fun clearArt() {
        art.animate().cancel()
        art.tag = null
        artBack.setImageDrawable(null)
        art.exit(PAGE_MS).alpha(0f).withEndAction { if (art.tag == null) art.setImageDrawable(null) }.start()
    }

    private fun resetArt() {
        main.removeCallbacks(artTask)
        art.animate().cancel()
        art.tag = null
        art.alpha = 0f
        art.setImageDrawable(null)
        artBack.setImageDrawable(null)
        refrost()
    }

    /** Gets [item]'s full-screen art ready ahead (fetching its details first if need be), so moving to it is instant. */
    private fun warm(item: Item) {
        if (page.isBrowse && mode == Mode.BROWSE) return fill(item) // ChadFlix: no art to fetch ahead, only the info line
        if (item.warming) return
        item.warming = true
        item.logo?.let { Images.prefetch(it, dp(LOGO_W)) }
        if (item.art != null) return Images.prefetch(item.art, artW, look = shaded)
        item.onFill += { backdropOf(item)?.let { (url, look) -> Images.prefetch(url, artW, look = look) }; item.logo?.let { Images.prefetch(it, dp(LOGO_W)) } }
        fill(item)
    }

    /** What's on screen for the mode. */
    private fun chrome() {
        val cinemaOn = mode == Mode.CINEMA
        val headerOn = !cinemaOn || header.hasFocus()
        header.animate().alpha(if (headerOn) 1f else 0f).setDuration(ENTER_MS).start()
        topShade.animate().alpha(if (headerOn) 1f else 0f).setDuration(ENTER_MS).start()
        // Lighter in the cinema (only its few words sit low on the left), full while browsing under the cards.
        browseInfo.animate().alpha(if (cinemaOn || heroItem == null) 0f else 1f).setDuration(ENTER_MS).start()
        cardCaption.alpha = if (cinemaOn || page.isGrid) 0f else 1f
        nextRowHint.alpha = if (cinemaOn || page.isGrid) 0f else 1f
        genreStrip.alpha = if (cinemaOn) 0f else 1f
        if (cinemaOn) {
            moreHint.exit(ENTER_MS).alpha(0f).start()
        }
        cinemaInfo.animate().alpha(if (cinemaOn && cinemaReady) 1f else 0f).setDuration(ENTER_MS).start()
        cinemaClock.animate().alpha(if (cinemaOn && !headerOn) 1f else 0f).setDuration(ENTER_MS).start()
        dots.animate().alpha(if (cinemaOn && cinemaReady && cinema.size > 1) 1f else 0f).setDuration(ENTER_MS).start()
    }

    // ---------- cinema ----------

    private fun stage(): View {
        cinemaName = text("", SERIF_HERO, serif = true).onArt().apply { maxLines = 2; ellipsize = TextUtils.TruncateAt.END; maxWidth = dp(520) }
        cinemaLogo = ImageView(this).apply { adjustViewBounds = true; scaleType = ImageView.ScaleType.FIT_START; maxHeight = dp(LOGO_H); maxWidth = dp(LOGO_W) }
        cinemaLine = text("", SMALL, color = TEXT).onArt().apply { maxLines = 1; ellipsize = TextUtils.TruncateAt.END; maxWidth = dp(INFO_W) }
        cinemaPlay = pill(R.string.action_resume, R.drawable.ic_play).apply {
            isFocusable = false
            setOnClickListener { cinema.getOrNull(cinemaAt)?.let(::play) }
        }
        cinemaInfo = column(Gravity.START,
            FrameLayout(this).apply { addView(cinemaName); addView(cinemaLogo) },
            cinemaLine.marginTop(12),
            cinemaPlay.marginTop(24),
        ).apply { alpha = 0f }
        // Page dots as tvOS draws them: small and quiet, the current one a short pill.
        dots = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL; alpha = 0f }
        stage = FrameLayout(this).apply {
            addView(cinemaInfo, FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM or Gravity.START).apply { leftMargin = dp(PAD); bottomMargin = dp(64) })
            addView(dots, FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL).apply { bottomMargin = dp(30) })
            cinemaClock = TextClock(context).apply {
                alpha = 0f
                format12Hour = "h:mm a"; format24Hour = "HH:mm"
                typeface = text("", SERIF_TITLE, serif = true).typeface
                textSize = SERIF_TITLE
                setTextColor(TEXT)
            }
            addView(cinemaClock, FrameLayout.LayoutParams(-2, -2, Gravity.TOP or Gravity.END).apply { topMargin = dp(27); rightMargin = dp(PAD) })
            isFocusable = false
        }
        return stage
    }

    /** Cinema title [index]: its art, logo, where you are (episode, its name, time left) and Resume. */
    private fun showCinema(index: Int) {
        val item = cinema.getOrNull(index) ?: return
        val same = cinemaAt == index && cinemaReady && cinemaName.text.toString() == item.name
        cinemaAt = index
        fill(item)
        // A new title's words come in with its picture, once both its art and logo are here (or after a moment
        // without them), instead of the name, then the logo, then the art popping in one after another.
        val token = Any().also { cinemaToken = it }
        if (!same) { cinemaReady = false; chrome() }
        var waiting = 2
        fun part() {
            if (cinemaToken !== token || --waiting > 0 || cinemaReady) return
            cinemaReady = true
            chrome()
        }
        main.postDelayed({ if (cinemaToken === token && !cinemaReady && item.settled) { waiting = 1; part() } }, REVEAL_WAIT)
        cinemaName.text = item.name
        cinemaName.visibility = View.INVISIBLE
        if (!same) { cinemaLogo.setImageDrawable(null); cinemaLogo.tag = null }
        val logoUrl = item.logo
        if (logoUrl == null) { cinemaName.visibility = View.VISIBLE; part() }
        else Images.load(logoUrl, cinemaLogo, dp(LOGO_W), urgent = true, failed = { cinemaName.visibility = View.VISIBLE; part() }) { part() }
        cinemaLine.text = where(item)
        cinemaPlay.text = getString(if (item.resume) R.string.action_resume else R.string.action_play)
        dots.removeAllViews()
        cinema.indices.forEach { i ->
            dots.addView(View(this).apply { background = rounded(if (i == index) TUNGSTEN else TEXT_3, 3) },
                LinearLayout.LayoutParams(dp(if (i == index) 18 else 6), dp(6)).apply { marginStart = dp(if (i > 0) 8 else 0) })
        }
        if (item.art != null || item.settled) showArt(item) { part() } // else its details call this again
        cinema.getOrNull((index + 1) % cinema.size)?.let(::warm)
        main.removeCallbacks(advance)
        if (mode == Mode.CINEMA) main.postDelayed(advance, ADVANCE)
    }

    private fun enterCinema() {
        if (cinema.isEmpty() || mode == Mode.CINEMA) return
        cancelBrowseHero()
        beforeCinema = currentFocus
        cinemaAt = 0
        cinemaAwake = false
        cinemaWakeKey = -1
        cinemaPlay.isFocusable = false
        mode = Mode.CINEMA
        stage.isFocusable = true
        stage.requestFocus()
        scroller.exit(PAGE_MS).translationY(dp(RISE).toFloat()).alpha(0f).withEndAction {
            if (mode == Mode.CINEMA) scroller.visibility = View.INVISIBLE
        }.start()
        chrome()
        showCinema(cinemaAt)
    }

    /** Wakes from the cinema to where the remote was; the card taking focus brings its own art back. */
    private fun enterBrowse() {
        mode = Mode.BROWSE
        dimHome(false)
        main.removeCallbacks(advance)
        scroller.visibility = View.VISIBLE
        scroller.enter(PAGE_MS).translationY(0f).alpha(1f).start()
        chrome()
        val back = beforeCinema?.takeIf { it.isAttachedToWindow && it.isShown } ?: firstRow()?.let { it.cards[it.selected] }
        stage.isFocusable = false
        back?.requestFocus()
        heroItem?.let(::showHero) // a card taking focus does this itself; the top bar doesn't
    }

    // ---------- browse: the focused title, top left ----------

    private fun browseInfo(): View {
        heroName = text("", SERIF_HERO, serif = true).onArt().apply { maxLines = 2; ellipsize = TextUtils.TruncateAt.END }
        // adjustViewBounds resets the scale type to FIT_CENTER, so FIT_START comes after it (logo on the margin).
        heroLogo = ImageView(this).apply { adjustViewBounds = true; scaleType = ImageView.ScaleType.FIT_START; maxHeight = dp(55) }
        heroYear = text("", SERIF_TITLE * 0.65f, serif = true, color = TEXT_2).onArt().apply { visibility = View.GONE }
        heroFacts = text("", HomeStyle.INFO, bold = true, color = HomeStyle.WHITE).onArt().apply {
            maxLines = 1; ellipsize = TextUtils.TruncateAt.END; minHeight = factsH; gravity = Gravity.CENTER_VERTICAL
        }
        // Small tags, as tall as the letters: SMALL's leading and font padding made them blocks twice the mock's.
        heroRatings = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        heroAbout = text("", BODY, color = HomeStyle.PLOT).onArt().apply { setLines(HomeStyle.PLOT_LINES); ellipsize = TextUtils.TruncateAt.END }
        heroResume = pill(R.string.action_resume, R.drawable.ic_play).apply {
            setOnClickListener { heroItem?.let(::play) }
            setOnLongClickListener { heroItem?.let { play(it, pick = true) }; true }
        }
        // Only Resume: OK on any card already opens its title page. It shows only for a title in progress, which lives in
        // the first row, so Up from any other row goes straight to the row above.
        heroActions = LinearLayout(this).apply { clipChildren = false; visibility = View.GONE; addView(heroResume) }
        // No progress line here: the focused card already shows it, right below.
        browseInfo = column(Gravity.START,
            FrameLayout(this).apply {
                addView(heroName, FrameLayout.LayoutParams(-1, -2, Gravity.CENTER_VERTICAL))
                addView(LinearLayout(this@MainActivity).apply {
                    gravity = Gravity.CENTER_VERTICAL
                    addView(heroLogo, LinearLayout.LayoutParams(-2, -2))
                    addView(heroYear, LinearLayout.LayoutParams(-2, -2))
                }, FrameLayout.LayoutParams(-1, -2, Gravity.CENTER_VERTICAL))
            },
            LinearLayout(this).apply {
                gravity = Gravity.CENTER_VERTICAL
                addView(heroFacts)
                addView(heroRatings)
            }.marginTop(8),
            heroAbout.marginTop(8),
            heroActions.marginTop(16),
        ).apply {
            alpha = 0f
            setPadding(dp(FOCUS_ROOM), 0, dp(FOCUS_ROOM), 0)
            // Too tall for the art above the rows (a large font size, a two-line name): the description gives up lines
            // until the block clears the top bar's words (they end well above its 76 dp). showHero gives them back for
            // the next title.
            addOnLayoutChangeListener { _, _, top, _, _, _, _, _, _ ->
                if (page.isGrid) return@addOnLayoutChangeListener
                val minTop = if (genreStrip.visibility == View.VISIBLE) dp(HEADER_H + 44) else dp(HEADER_H - 12)
                nextHeroMaxLines(top, minTop, heroAbout.maxLines)?.let { next -> heroAbout.post { heroAbout.maxLines = next } }
            }
        }
        return browseInfo
    }

    /** The focused title: its art across the screen, and logo, facts (and time left) and description top left. */
    private fun showHero(item: Item) {
        val changed = heroItem !== item
        heroItem = item
        if (page.isGrid) {
            val year = metas[item.id]?.year ?: item.facts.firstOrNull { it.firstOrNull()?.isDigit() == true }
            val title = android.text.SpannableString(item.name + year?.let { "$DOT$it" }.orEmpty()).apply {
                if (year != null) {
                    val start = item.name.length
                    setSpan(android.text.style.ForegroundColorSpan(TEXT_2), start, length, 0)
                    setSpan(android.text.style.RelativeSizeSpan(0.65f), start, length, 0)
                }
            }
            showHeroTitle(item, title, year?.let { "$DOT$it" })
            browseInfo.animate().alpha(1f).setDuration(FOCUS_MS).start()
            main.post(::placeCaption)
            if (changed) {
                cancelBrowseHero()
                if (art.drawable == null) art.tag = null
                heroTask = Runnable {
                    heroTask = null
                    if (page.isGrid && heroItem === item && !isDestroyed) {
                        fill(item)
                        if (item.settled) showLibraryArt(item)
                    }
                }.also { main.postDelayed(it, HOME_HERO_WAIT) }
            } else if (heroTask == null) showLibraryArt(item)
            return
        }
        // ChadFlix: no art to wait for on the home pages, so the words switch in the same frame as the focus (what's known
        // now; details refine them in place). They used to wait HOME_HERO_WAIT (150 ms) for the art, then fade 220 ms.
        // Along a row they come in from 0.4 quickly; while the rows slide they stay hidden ([slideRows]).
        if (page.isBrowse) {
            heroTask?.let(main::removeCallbacks)
            heroTask = null
            renderHero(item)
            if (slideAnim != null) { browseInfo.animate().cancel(); browseInfo.alpha = 0f }
            else if (changed && mode == Mode.BROWSE) {
                browseInfo.animate().cancel()
                browseInfo.alpha = minOf(browseInfo.alpha, 0.4f)
                browseInfo.animate().alpha(1f).setDuration(INFO_STEP_MS).setInterpolator(EASE_OUT).start()
            }
        } else if (page.polishedRail && changed) {
            main.removeCallbacks(artTask)
            // Keep the last complete hero visible until the new focus settles.
            if (art.drawable == null) art.tag = null
            queueBrowseHero(item)
        } else if (heroTask == null) {
            renderHero(item)
        }
    }

    private fun showLibraryArt(item: Item) {
        if (backdropOf(item) != null) showArt(item)
        else if (item.settled) clearArt()
    }

    private fun cancelBrowseHero() {
        heroTask?.let(main::removeCallbacks)
        heroTask = null
        if (page.polishedRail && mode == Mode.BROWSE) browseInfo.animate().cancel()
    }

    private fun queueBrowseHero(item: Item) {
        cancelBrowseHero()
        heroTask = Runnable {
            if (heroItem === item && page.polishedRail && mode == Mode.BROWSE && !isDestroyed) {
                val cachedArt = backdropOf(item)?.let { (url, look) -> Images.cached(url, artW, look) } == true
                if (page.isBrowse || browseInfo.alpha == 0f || cachedArt || (art.drawable != null && backdropOf(item)?.first == art.tag)) {
                    heroTask = null
                    if (cachedArt && backdropOf(item)?.first != art.tag) browseInfo.alpha = 0f
                    renderHero(item)
                } else {
                    // Fade through the dark base so the new title never appears over the previous picture.
                    art.exit(HOME_ART_EXIT_MS).alpha(0f).start()
                    browseInfo.exit(HOME_ART_EXIT_MS).alpha(0f).withEndAction {
                        if (heroItem === item && page.polishedRail && mode == Mode.BROWSE && !isDestroyed) {
                            heroTask = null
                            renderHero(item)
                        }
                    }.start()
                }
            }
        }.also { main.postDelayed(it, HOME_HERO_WAIT) }
    }

    private fun showHeroTitle(item: Item, title: CharSequence, logoYear: String? = null) {
        val logoChanged = heroLogo.tag != item.logo
        heroName.text = title
        heroYear.text = logoYear.orEmpty()
        if (logoChanged || heroLogo.drawable == null) {
            heroName.visibility = View.VISIBLE
            heroLogo.visibility = View.GONE
            heroYear.visibility = View.GONE
            Images.load(item.logo, heroLogo, dp(LOGO_W)) {
                heroLogo.visibility = View.VISIBLE
                heroYear.visibility = if (logoYear != null) View.VISIBLE else View.GONE
                heroName.visibility = View.INVISIBLE
            }
        } else {
            heroYear.visibility = if (heroLogo.visibility == View.VISIBLE && logoYear != null) View.VISIBLE else View.GONE
        }
    }

    private fun renderHero(item: Item) {
        showArt(item) // ChadFlix: no art, title or logo on the home pages, only the info line under the row
        val meta = metas[item.id]
        val year = meta?.year ?: item.facts.firstOrNull { it.firstOrNull()?.isDigit() == true }
        val genre = meta?.genres?.firstOrNull() ?: item.facts.firstOrNull { it.firstOrNull()?.isDigit() == false && !it.startsWith("IMDb") }?.substringBefore(",")
        val length = if (hasEpisodes(item.type, meta?.videos)) meta?.videos?.map { it.season }?.filter { it > 0 }?.distinct()?.size?.takeIf { it > 0 }
            ?.let { plural(R.plurals.seasons, it) }
            else meta?.runtime
        // A show in progress describes its next episode, so the line names that episode rather than the show.
        val code = episodeLabel(item)
        val factsList = mutableListOf<String>()
        // ChadFlix: "Drama • 2014 • 1h 41m • PG-13", with Continue Watching's time left (its card only has the bar).
        val left = item.timeLeft ?: HomeStyle.runtimeMs(meta?.runtime).takeIf { item.resumeAt in 1L until it }?.let { timeLeft(it, item.resumeAt) }
        if (code != null) factsList += listOfNotNull(code, episode(item)?.title?.takeIf { it.isNotEmpty() }, left)
        // No "Movie"/"Series" (the tab says it). A film's time left goes in brackets after its runtime, "1h 59m (1h 56m
        // left)"; otherwise (a show's seasons, no runtime) it's its own item, "3 Seasons • 40m left".
        else {
            val bracket = item.type == "movie" && length != null && left != null
            factsList += listOfNotNull(genre, year, if (bracket) "$length ($left)" else length, meta?.certification, left.takeIf { !bracket })
        }
        heroFacts.text = factsList.joinToString(HomeStyle.SEP)
        // ChadFlix: the ratings (views, and any MDBList request) only for the card the remote stops on: racing past films
        // with Letterboxd on would spend MDBList's daily requests. The words above switch at once.
        main.removeCallbacks(ratingsTask)
        if (ratingsItem !== item) heroRatings.removeAllViews() // another title's ratings go at once; the same title's stay
        ratingsTask = Runnable { if (heroItem === item) showRatings(item, meta) }
        if (page.isBrowse) main.postDelayed(ratingsTask, HOME_HERO_WAIT) else ratingsTask.run()
        val showResume = false // ChadFlix: OK on the card opens the title page; no Resume between the rows
        heroActions.visibility = if (showResume) View.VISIBLE else View.GONE
        // ChadFlix: only the words change; the plot's box is a fixed 3 lines, ending in "…".
        heroAbout.text = episode(item)?.overview?.takeIf { it.isNotBlank() } ?: item.about
        if (mode == Mode.BROWSE && !page.isBrowse) browseInfo.enter(if (page.polishedRail) HOME_ART_MS else FOCUS_MS) // ChadFlix: showHero fades home's
            .alpha(if (homeDimmed && (page.isBrowse || page.isGrid)) 0.3f else 1f).start()
    }

    private var ratingsTask = Runnable {}
    private var ratingsItem: Item? = null // whose ratings show

    private fun showRatings(item: Item, meta: Meta?) {
        val rating = meta?.rating ?: item.facts.firstOrNull { it.startsWith("IMDb") }?.removePrefix("IMDb ")
        // Letterboxd comes from this session or the disk at once; a film never asked yet fills in when MDBList answers.
        // ChadFlix: on the home pages only "Letterboxd" asks MDBList (with "Both" the card's badge shows IMDb).
        val wantsLetterboxd = Settings.wantsLetterboxd(this, item.type, item.id) && (!page.isBrowse || ratingPick == "letterboxd")
        val letterboxdScore = if (wantsLetterboxd) knownLetterboxd(item.id) else null
        if (wantsLetterboxd && letterboxdScore == null) {
            val key = Settings.mdblistKey(this)
            background({ letterboxd(item.id, key) }) { r ->
                if (r.getOrNull() == null || mode != Mode.BROWSE) return@background
                if (page.isBrowse) rows.forEach { row -> row.cards.forEach { if (it.payload === item) row.rate(it, item, fade = true) } }
                else if (heroItem === item) showHero(item)
            }
        }
        // ChadFlix: on the home pages the rating sits on the card (its corner badge), not in the info line.
        if (page.isBrowse) { heroRatings.removeAllViews(); ratingsItem = item; return }
        val ratings = shownRatings(Settings.ratings(this), rating, letterboxdScore)
        heroRatings.removeAllViews()
        addRatings(heroRatings, ratings)
        HomeStyle.plainRatings(heroRatings)
        ratingsItem = item
    }

    private fun showCaption(item: Item) {
        if (((currentFocus as? MediaCardView)?.payload as? Item) !== item) {
            cardCaption.visibility = View.GONE
            cardUnderline.visibility = View.INVISIBLE
            return
        }
        val meta = metas[item.id]
        val detail = if (page == Page.HOME) meta?.year ?: item.facts.firstOrNull { it.firstOrNull()?.isDigit() == true }
            else if (hasEpisodes(item.type, meta?.videos)) meta?.videos?.map { it.season }?.filter { it > 0 }?.distinct()?.size?.takeIf { it > 0 }
                ?.let { plural(R.plurals.seasons, it) }
            else meta?.runtime
        val captionText = item.timeLeft
            ?: getString(R.string.player_next_episode).takeIf { item.nextEpisodePending }
            ?: if (page == Page.HOME) null else listOfNotNull(item.name, detail).joinToString(DOT)
        if (captionText == null) {
            cardCaption.visibility = View.GONE
            placeCaption()
            return
        }
        cardCaption.text = captionText
        cardCaption.setCompoundDrawablesRelative(null, null, null, null)
        cardCaption.visibility = View.VISIBLE
        placeCaption()
        cardCaption.post(::placeCaption)
    }

    private fun placeCaption() {
        val card = currentFocus as? MediaCardView ?: return
        val parent = cardCaption.parent as FrameLayout
        if (!card.isAttachedToWindow || !isInside(card, parent)) return
        captionAnchor.set(0, 0, card.width, card.height)
        parent.offsetDescendantRectToMyCoords(card, captionAnchor)
        cardUnderline.visibility = View.INVISIBLE
        if (page.isGrid && mode == Mode.BROWSE) {
            cardUnderline.translationX = captionAnchor.exactCenterX() - cardUnderline.width / 2f
            cardUnderline.translationY = captionAnchor.bottom + dp(6).toFloat()
            cardUnderline.visibility = View.VISIBLE
        }
        if (cardCaption.visibility != View.VISIBLE) return
        cardCaption.translationX = (captionAnchor.left - cardCaption.left)
            .coerceIn(0, (parent.width - 2 * dp(PAD) - cardCaption.width).coerceAtLeast(0)).toFloat()
        cardCaption.translationY = (captionAnchor.bottom + dp(12) - cardCaption.top).toFloat()
        clearHint()
    }

    /** The next row's name and the caption share one line: a long caption that reaches the name hides it. */
    private fun clearHint() {
        if (nextRowHint.text.isEmpty() || cardCaption.visibility != View.VISIBLE) return
        val captionEnd = cardCaption.x + cardCaption.width
        val sameLine = cardCaption.y < nextRowHint.bottom && cardCaption.y + cardCaption.height > nextRowHint.top
        nextRowHint.visibility = if (sameLine && captionEnd + dp(SECTION_GAP) > nextRowHint.left) View.INVISIBLE else View.VISIBLE
    }

    /** One quiet line for a title in progress: the episode and its name (or, for a film, its facts), then the time left. */
    private fun where(item: Item): String {
        val code = episodeLabel(item)
        val about = if (code != null) listOfNotNull(code, episode(item)?.title?.takeIf { it.isNotEmpty() }) else item.facts
        return (about + listOfNotNull(item.timeLeft)).joinToString(DOT)
    }

    /** "Series · 2022 · Crime": the kind, the year and the first genre, nothing that needs reading twice. */
    private fun facts(item: Item): String {
        val year = item.facts.firstOrNull { it.firstOrNull()?.isDigit() == true }
        val genre = item.facts.firstOrNull { it.firstOrNull()?.isDigit() == false && !it.startsWith("IMDb") }?.substringBefore(",")
        val kind = when (item.type) { "series" -> "Series"; "movie" -> "Movie"; else -> item.type.replaceFirstChar { it.uppercase() } }
        return listOfNotNull(kind, year, genre).joinToString(DOT)
    }

    /** The episode Continue Watching points at, once the title's details are in. */
    private fun episode(item: Item) = item.videoId?.let { v -> metas[item.id]?.videos?.firstOrNull { it.id == v } }

    private fun episodeLabel(item: Item): String? {
        if (item.type == "movie" || item.live) return null
        return episode(item)?.let { episodeNumber(it.season, it.episode) }
            ?: episodeNumbers(item.videoId)?.let { (season, episode) -> episodeNumber(season, episode) }
    }

    // ---------- pages ----------

    /** Opens [p] with saved answers and refreshes them in the background. */
    private fun showPage(p: Page) {
        val opened = android.os.SystemClock.uptimeMillis()
        cancelBrowseHero()
        if (page.polishedRail || p.polishedRail || page.isGrid || p.isGrid) resetArt()
        dimHome(false)
        if (p != page) {
            genre = null // a genre belongs to the page it was picked on
            jumpTo = null
            genreStrip.removeAllViews()
            genreChips.clear()
        }
        page = p
        val grid = p.isGrid
        if (grid) listPosterWidth = libraryPosterWidth()
        scroller.layoutParams = (scroller.layoutParams as FrameLayout.LayoutParams).apply {
            topMargin = if (grid) dp(GRID_TOP) else browseRowTop
            height = if (grid) resources.displayMetrics.heightPixels - dp(GRID_TOP) else browseRowH
        }
        content.setPadding(0, 0, 0, if (grid) 0 else browseEndPad)
        // ChadFlix: the last page's info line goes at once. Fading out (~240 ms) it was already moved to the new page's
        // spot below (translationX 0, or My Lists' top), so the old words jumped left and lingered there.
        browseInfo.animate().cancel()
        browseInfo.alpha = 0f
        browseInfo.layoutParams = (browseInfo.layoutParams as FrameLayout.LayoutParams).apply {
            leftMargin = dp((if (grid) PAD else HomeStyle.INSET) - FOCUS_ROOM)
            gravity = if (grid) Gravity.TOP else Gravity.BOTTOM
            topMargin = if (grid) dp(GRID_HERO_TOP) else 0
            bottomMargin = if (grid) 0 else heroBottom
            width = if (grid) artW - dp(2 * (PAD - FOCUS_ROOM)) else dp(HERO_W + 2 * FOCUS_ROOM)
        }
        val titleSize = if (grid) SERIF_TITLE else SERIF_HERO
        heroName.textSize = titleSize
        val heroLeading = android.util.TypedValue.applyDimension(android.util.TypedValue.COMPLEX_UNIT_SP,
            LEADING.getValue(titleSize), resources.displayMetrics)
        heroName.setLineSpacing(heroLeading - heroName.paint.getFontMetricsInt(null), 1f)
        heroName.maxLines = 2
        heroLogo.maxHeight = dp(if (grid) 46 else 55)
        heroLogo.maxWidth = dp(if (grid) 280 else Int.MAX_VALUE)
        browseInfo.getChildAt(0).layoutParams = LinearLayout.LayoutParams(if (grid) -1 else -2, -2)
        browseInfo.getChildAt(0).visibility = if (grid) View.VISIBLE else View.GONE // ChadFlix: no title on the home pages
        browseInfo.translationX = 0f
        for (i in 1 until browseInfo.childCount) browseInfo.getChildAt(i).visibility = if (grid) View.GONE else View.VISIBLE
        heroAbout.maxWidth = if (p == Page.HOME) dp(HOME_SYNOPSIS_W) else if (p.hasGenreShortcuts) dp(HERO_W) else Int.MAX_VALUE
        wideRow = false
        librarySlot = null
        listShade.visibility = if (grid) View.VISIBLE else View.GONE
        listShade.background = GradientDrawable(GradientDrawable.Orientation.TL_BR,
            intArrayOf(0xF20E0C0A.toInt(), 0xE6191512.toInt(), 0xF20E0C0A.toInt()))
        val g = ++generation
        loadMoreSections = null
        restoreCatalogFocus = null
        refreshAddons = null
        rebuildRows = null
        showingChanged.clear()
        main.removeCallbacks(focusTop)
        main.removeCallbacks(heroTop)
        refreshLibrary = null
        touched = false
        arrangeTabs()
        styleNav()
        content.removeAllViews()
        warningHost.removeAllViews()
        pageWarning = null
        rows.forEach { r -> r.cards.forEach { it.unload() } }
        rows.clear()
        genreStrip.animate().cancel()
        genreStrip.alpha = 1f
        genreStrip.isFillViewport = grid
        genreStrip.visibility = if (p.isCatalog) View.INVISIBLE else if (grid) View.VISIBLE else View.GONE
        if (p == Page.LIBRARY) showListChips()
        else if (p == Page.DISCOVER) { genreStrip.removeAllViews(); genreChips.clear(); discoverChips.clear() }
        else if (!p.isCatalog) { genreStrip.removeAllViews(); genreChips.clear() }
        cardCaption.visibility = View.GONE
        cardUnderline.visibility = View.INVISIBLE
        nextRowHint.visibility = View.GONE
        moreHint.alpha = 0f
        lastHiddenRows = Settings.hiddenRows(this)
        lastHomeRows = HomeStyle.signature(this, p.label)
        if (p == Page.HOME) { lastFeaturedHome = Settings.featuredHome(this); cinema = emptyList() } // other pages keep Home's, so the screensaver works there too
        focusedSomething = false
        // The last page's title goes with it; an empty page (no library yet, no catalogs) shows none.
        heroItem = null
        slideAnim.also { slideAnim = null }?.cancel()
        scroller.scrollTo(0, 0)
        mode = Mode.BROWSE
        main.removeCallbacks(advance)
        scroller.visibility = View.VISIBLE
        scroller.translationY = 0f
        scroller.alpha = 1f
        stage.isFocusable = false
        chrome()
        measureFirstRow(g, opened)
        val key = profile.authKey

        // Keep cached rows visible if a refresh fails.
        var failure: View? = null
        fun failedToLoad(error: Throwable) {
            forgetIfSessionGone(error)
            if (failure != null || isFinishing) return
            val view = failed(failureMessage(if (p == Page.HOME || grid) R.string.library_load_failed else R.string.home_unreachable,
                failureOf(error))) { showPage(page) }
            failure = view
            content.addView(view, 0, LinearLayout.LayoutParams(-2, -2).apply { marginStart = dp(PAD) })
            view.post { if (view.isAttachedToWindow && !focusedSomething && !header.hasFocus()) view.getChildAt(1).requestFocus() }
        }
        fun clearFailure() {
            failure?.let(content::removeView)
            failure = null
        }

        if (p == Page.HOME || p == Page.LIBRARY || p.hasGenreShortcuts) { // ChadFlix: Continue Watching tops Movies and Shows too
            val slot = slot()
            if (grid) librarySlot = slot
            var shown: String? = null // what's on screen, to tell whether the fresh library changed anything
            var fresh = false
            fun show(prepared: HomeLibrary, force: Boolean = false) {
                val (list, cw, metadata) = prepared
                val now = System.currentTimeMillis()
                metadata.forEach { (id, resolved) ->
                    cacheMeta(id, resolved.meta, preservedMetaTimestamp(resolved.fresh, metaFetchedAt[id], now))
                }
                val what = (if (!grid) cw else list).joinToString {
                    val state = it.state
                    "${it.optString("_id")}:${it.optString("_mtime")}:${it.optBoolean("removed")}:${state.optLong("timeOffset")}:${state.optLong("duration")}:${state.text("video_id")}"
                }
                // Saved copies wait once the remote is in use; Stremio's own answer always goes in (focus stays on
                // its title below), or a stale disk copy could hide a show for the whole visit.
                if (what == shown || (shown != null && touched && !force)) return
                val firstShow = shown == null
                shown = what
                // The remote was on one of these titles: it stays on that title wherever it moved (watched: to the front).
                val inSlot = currentFocus?.let { isInside(it, slot) } == true
                val was = if (inSlot) currentFocus?.tag else null
                library = list
                if (!grid) Profiles.active(this)?.let { me ->
                    val syncGeneration = WatchNext.currentGeneration()
                    background({ WatchNext.sync(applicationContext, me.authKey, cw, me.pin != null, syncGeneration) }) {}
                }
                rows.filter { isInside(it.view, slot) }.forEach { r -> r.cards.forEach { it.unload() }; rows.remove(r) }
                slot.removeAllViews()
                if (!grid) {
                    val items = cw.filter { p == Page.HOME || (it.optString("type") == "movie") == (p == Page.MOVIES) }.map(::libraryItem)
                    if (items.isNotEmpty()) {
                        slot.addView(row(getString(R.string.row_continue), items.take(initialRowCards(false)), later(items), continueRow = true).view)
                        android.util.Log.d("Alder", "page ${p.label}: Continue in ${android.os.SystemClock.uptimeMillis() - opened} ms")
                        startCinema(items.take(CINEMA_MAX))
                    } else if (inSlot && firstRow() == null) {
                        cancelBrowseHero()
                        heroItem = null
                        browseInfo.alpha = 0f
                        resetArt()
                    }
                } else {
                    showLibrary(slot, was)
                }
                if (inSlot && !grid) {
                    val target = was?.let { slot.findViewWithTag<View>(it) } ?: rows.firstOrNull { isInside(it.view, slot) }?.cards?.firstOrNull()
                    if (target?.requestFocus() != true && firstRow()?.focusFirst() != true) navItems[page]?.requestFocus()
                }
                // Continue came in after a quicker row below took the first focus: the remote starts on Continue.
                else if (!grid && firstShow && !touched && focusedSomething && scroller.hasFocus())
                    rows.firstOrNull { isInside(it.view, slot) }?.focusFirst()
            }
            val remembered = library.takeIf { it.isNotEmpty() }
            if (grid && remembered != null) show(HomeLibrary(remembered))
            else {
                val known = if (grid) emptyMap() else metas.toMap()
                val knownFetchedAt = if (grid) emptyMap() else metaFetchedAt.toMap()
                background({
                    (Stremio.savedLibrary(key) ?: remembered)?.let { prepareLibrary(it, known, knownFetchedAt, key, grid) }
                }, cacheReads) { r -> if (g == generation && !fresh) r.getOrNull()?.let { show(it) } }
            }
            var libraryRequest = 0
            fun ask() {
                val request = ++libraryRequest
                val known = if (grid) emptyMap() else metas.toMap()
                val knownFetchedAt = if (grid) emptyMap() else metaFetchedAt.toMap()
                background({ prepareLibrary(Stremio.library(key), known, knownFetchedAt, key, grid) }) { result ->
                    if (g != generation || request != libraryRequest) return@background
                    result.fold({
                        fresh = true
                        clearFailure()
                        show(it, force = true)
                    }) { failedToLoad(it) }
                }
            }
            ask()
            refreshLibrary = ::ask
        }
        if (p == Page.LIBRARY) return
        if (p == Page.DISCOVER) return showDiscover(g)

        val catalogSlots = linkedMapOf<String, FrameLayout>()
        var catalogBuild = 0
        var noCatalogs: View? = null
        var catalogWarning: View? = null
        val catalogFailures = linkedMapOf<String, Failure>()
        val failedAddons = linkedMapOf<String, String>() // row key to the add-on whose catalog failed
        var builtFrom: List<Pair<String, String>>? = null
        // The card names the add-ons that failed and what went wrong, so the viewer knows which one to fix in Stremio.
        fun catalogMessage(): String? {
            val reason = catalogFailures.values.firstOrNull() ?: return null
            val names = failedAddons.values.filter { it.isNotBlank() }.distinct().joinToString(", ")
            if (names.isEmpty()) return failureMessage(R.string.catalog_incomplete, reason)
            return "${getString(R.string.catalog_addon_failed, names)}: ${getString(reason.message)}\n" +
                getString(reason.advice)
        }
        fun catalogStatus(key: String, failure: Failure?, addon: String? = null) {
            val oldMessage = catalogMessage()
            val previous = if (failure == null) catalogFailures.remove(key) else catalogFailures.put(key, failure)
            if (failure == null || addon == null) failedAddons.remove(key) else failedAddons[key] = addon
            if (previous == failure) return
            if (failure != null) FailureLog.record(FailureLog.Operation.CATALOG, failure)
            else if (previous != null) FailureLog.record(FailureLog.Operation.CATALOG, previous, recovered = true)
            val message = catalogMessage()
            if (message == oldMessage) return
            val restoreFocus = catalogWarning?.hasFocus() == true
            catalogWarning?.let(warningHost::removeView)
            catalogWarning = message?.let {
                failed(it) { showPage(p) }.also { card ->
                    warningHost.addView(card, FrameLayout.LayoutParams(dp(320), -2))
                    if (restoreFocus) card.getChildAt(1).requestFocus()
                }
            }
            pageWarning = catalogWarning?.let { (it as android.view.ViewGroup).getChildAt(1) }
        }
        fun build(addons: List<Addon>) {
            if (g != generation) return
            showLiveTab(addons)
            val ids = addons.map { it.id to it.transportUrl }
            if (builtFrom == ids) return
            builtFrom = ids
            val build = ++catalogBuild
            loadMoreSections = null
            val oldSlots = catalogSlots.toMutableMap()
            val focusedRow = rows.firstOrNull { it.view.hasFocus() && it.view.parent in oldSlots.values }
            val focused = currentFocus as? MediaCardView
            val focusedTag = focused?.tag?.takeIf { focusedRow != null }
            catalogSlots.clear()
            noCatalogs?.let(content::removeView)
            noCatalogs = null
            catalogWarning?.let(warningHost::removeView)
            catalogWarning = null
            catalogFailures.clear()
            failedAddons.clear()
            val featuredHome = p == Page.HOME && Settings.featuredHome(this@MainActivity)
            // Each catalog on its page under a plain name, personal rows first; the sort keeps the addons' order within a rank.
            val shelves = addons.flatMap { it.catalogs }.mapNotNull { c ->
                shelf(c)?.takeIf { it.page == p || (featuredHome && (it.page == Page.MOVIES || it.page == Page.SHOWS) && it.rank <= 2) }?.let { c to it }
            }.sortedBy { it.second.rank }
            val catalogs = shelves.map { it.first }
            val genres = catalogGenres(catalogs.flatMap { it.genres }.map { if (p.hasGenreShortcuts) genreLabel(it) else it })
            val hiddenRows = Settings.hiddenRows(this@MainActivity)
            // ChadFlix: Movies and Shows are TMDb genre rows, then the add-on rows, which stay off until Customize Home
            // turns them on; rows that are off are never built or fetched. Move Up / Move Down set the order.
            val movie = p == Page.MOVIES
            val services = p == Page.SERVICES // ChadFlix: the Services tab: one row of shows per streaming service
            val home = p == Page.HOME // ChadFlix: Home's All Genres row first (after Continue Watching), then the add-on rows
            val allGenres = listOf(HomeStyle.ALL_GENRES, HomeStyle.rowName(this@MainActivity, HomeStyle.ALL_GENRES)) // an add-on row of that name gives way
            val candidates = if (services) HomeStyle.platforms.mapIndexed { i, platform ->
                    Triple(platform.name, emptyList<Pair<Addon.Catalog, Shelf>>(), HomeStyle.platformSource(this@MainActivity, p.label, i, false) as String?)
                } else if (home) listOf(Triple(HomeStyle.ALL_GENRES, emptyList<Pair<Addon.Catalog, Shelf>>(), HomeStyle.allGenresSource(this@MainActivity) as String?)) +
                    sections(p, shelves, featuredHome).filterNot { s -> allGenres.any { it.equals(s.first, true) } }
                else if (!p.hasGenreShortcuts) sections(p, shelves, featuredHome) else
                HomeStyle.genres(movie).map { (id, name) ->
                    Triple(name, emptyList<Pair<Addon.Catalog, Shelf>>(), HomeStyle.genreSource(this@MainActivity, p.label, id) as String?)
                } + Triple(HomeStyle.ANIME, emptyList<Pair<Addon.Catalog, Shelf>>(), HomeStyle.animeSource(this@MainActivity, p.label, movie) as String?) +
                    sections(p, shelves, featuredHome).filterNot { s ->
                        HomeStyle.genres(movie).any { it.second.equals(s.first, true) } || s.first.equals(HomeStyle.ANIME, true)
                    }
            if (p.hasGenreShortcuts || services || home) HomeStyle.rememberRows(this@MainActivity, p.label, candidates.map { it.first })
            val rowsOn = HomeStyle.rowsOn(this@MainActivity, p.label)
            val sections = HomeStyle.ordered(this@MainActivity, p.label, movie, candidates.filter {
                it.first !in hiddenRows && (!(p.hasGenreShortcuts || services || home) || HomeStyle.isOn(rowsOn, it.first, movie, services || home))
            }) { it.first }
            pageRows = sections.map { it.first }
            // ChadFlix: Movies and Shows have their genre rows; Home says so only when no row is on (not even All Genres).
            if (!p.hasGenreShortcuts && p != Page.SERVICES && showsNoCatalogs(home, catalogs.isEmpty(), sections.size)) {
                val none = when (p) {
                    Page.HOME -> R.string.home_no_picks
                    Page.MOVIES -> R.string.page_no_movie_catalogs
                    Page.LIVE -> R.string.page_no_live_catalogs
                    else -> R.string.page_no_show_catalogs
                }
                // Home holds only Continue and Watchly's picks, so a new account sees nothing there without a word.
                noCatalogs = text(none, BODY, color = TEXT_2).apply {
                    setPadding(dp(PAD), 0, dp(PAD), 0)
                    content.addView(this)
                }
            }
            val initialSections = maxOf(nextCatalogSection(sections.size, 0), minOf(oldSlots.size, sections.size))
            val genreNames = (if (p.isCatalog) sections.take(initialSections).map { it.first }.filter { name -> genres.any { it.equals(name, true) } }
                else emptyList()).toMutableList()
            val genreResults = mutableMapOf<String, Boolean>()
            if (p.isCatalog && !p.hasGenreShortcuts && genreNames.isEmpty()) {
                showGenreChips(emptyList())
                genreStrip.visibility = View.VISIBLE
            }
            // Keep request and view work bounded even when an account has many catalogs.
            var batchPending = 0
            fun buildSection(i: Int) {
                val section = sections[i]
                val (title, sources, picked) = section
                if (p.isCatalog && genres.any { it.equals(title, true) } && title !in genreNames) genreNames += title
                val rowCatalogs = sources.map { it.first }
                val genreFallback = p.hasGenreShortcuts && picked != null
                val key = catalogAnswerKey(p.label, title, picked, rowCatalogs.map { c ->
                    "${c.addon.transportUrl}/${c.type}/${c.id}"
                })
                // ChadFlix: a genre row keeps its slot when its Showing list changes; only its answers are per list.
                val slotKey = if (picked?.startsWith(HomeStyle.TMDB) == true) catalogAnswerKey(p.label, title, HomeStyle.slotSource(picked), emptyList()) else key
                val reused = oldSlots.remove(slotKey)
                val slot = (reused ?: slot()).also { catalogSlots[slotKey] = it }
                val kept = answers[key]
                val sourceAnswers = arrayOfNulls<Pair<List<Meta>, Boolean>>(rowCatalogs.size)
                val failures = arrayOfNulls<Failure>(rowCatalogs.size)
                var completed = 0
                var reported = false
                var rowVersion = 0
                lateinit var onAnswer: (Result<List<Pair<List<Meta>, Boolean>>>) -> Unit
                onAnswer = onAnswer@{ answer ->
                    if (g != generation || build != catalogBuild) return@onAnswer
                    val version = ++rowVersion
                    val results = answer.getOrNull() ?: emptyList()
                    val items = results.flatMap { it.first }.filterNot { isAddonMessage(it.name) }.distinctBy { it.id }.map(::metaItem)
                    if (p == Page.HOME && cinema.isEmpty() && items.any { !HomeStyle.isTile(it.id) }) startCinema(items.filterNot { HomeStyle.isTile(it.id) }.take(CINEMA_MAX))
                    if (completed == rowCatalogs.size && !reported) {
                        reported = true
                        batchPending--
                        if (batchPending == 0) main.post { loadMoreSections?.invoke() }
                    }
                    if (completed == rowCatalogs.size && title in genreNames) {
                        // ChadFlix: a failed Showing pick that kept the old row still has titles
                        genreResults[title] = items.isNotEmpty() || rows.any { it.title == title && it.view.parent === slot }
                        // Resolve empty rows before showing chips; off-screen row views arrive later.
                        val readyGenres = if (p.hasGenreShortcuts) genreNames.filter { genreResults[it] == true }
                            else settledGenres(genreNames, genreResults)
                        readyGenres?.takeIf { !p.hasGenreShortcuts || it.isNotEmpty() &&
                            genreChips.keys.filterNotNull() != it }?.let { ready ->
                            val wasVisible = genreStrip.visibility == View.VISIBLE
                            showGenreChips(ready)
                            rows.firstOrNull { row -> currentFocus?.let { isInside(it, row.view) } == true }
                                ?.title?.let(::markChip)
                            genreStrip.visibility = View.VISIBLE
                            if (!wasVisible) {
                                genreStrip.alpha = 0f
                                genreStrip.enter(QUICK_MS).alpha(1f).start()
                            }
                        }
                    }
                    // The rest of the first page first, then the addon's next pages when it takes `skip`.
                    // shortcut: genre rows use each source's first page; add genre-aware paging if those rows feel short.
                    val more: ((Int) -> List<Item>)? = if (rowCatalogs.size == 1) {
                        val catalog = rowCatalogs[0]
                        if (catalog.pages && picked == null) { from ->
                            if (from < items.size) items.drop(from).take(PAGE)
                            else catalog.addon.catalog(catalog, from).filterNot { isAddonMessage(it.name) }.map(::metaItem)
                        } else later(items)
                    } else {
                        val all = items.toMutableList()
                        val counts = results.map { it.first.size }.toMutableList()
                        val exhausted = BooleanArray(rowCatalogs.size)
                        val nextPage: (Int) -> List<Item> = { from ->
                            if (from >= all.size && picked == null) for (j in rowCatalogs.indices) {
                                val catalog = rowCatalogs[j]
                                if (!catalog.pages || exhausted[j]) continue
                                val next = catalog.addon.catalog(catalog, counts[j])
                                counts[j] += next.size
                                exhausted[j] = next.isEmpty()
                                val known = all.map { it.id }.toSet()
                                all += next.filter { it.id !in known && !isAddonMessage(it.name) }.distinctBy { it.id }.map(::metaItem)
                                if (all.size > from) break
                            }
                            all.drop(from).take(PAGE)
                        }
                        nextPage
                    }
                    // Build nearby rows first, spacing the others within this small batch across frames.
                    fun add() {
                        if (g != generation || build != catalogBuild || version != rowVersion) return
                        if (items.isEmpty()) { if (completed == rowCatalogs.size) main.post { loadMoreSections?.invoke() }; return }
                        val old = rows.firstOrNull { it.view.parent === slot }
                        if (old != null && showingChanged.remove("${p.label}:$title") != null) { // ChadFlix: the new list replaces the row, focus and all
                            val made = row(title, items.take(initialRowCards(false)), more)
                            slot.addView(made.view)
                            if (old.view.hasFocus()) made.cards.firstOrNull()?.requestFocus() // back on its tile
                            old.cards.forEach { card -> card.unload() }
                            rows.remove(old)
                            slot.removeView(old.view)
                            return
                        }
                        if (old != null && reused != null && old.pendingUpdate == null && results == kept) return
                        if (old?.view?.hasFocus() == true) { old.pendingUpdate = ::add; return }
                        old?.let { it.cards.forEach { card -> card.unload() }; rows.remove(it); slot.removeAllViews() }
                        val restoreIndex = if (focusedTag != null && title == focusedRow?.title &&
                            (focused.isAttachedToWindow || restoreCatalogFocus != null))
                            items.indexOfFirst { "card:${it.id}" == focusedTag } else -1
                        val initial = maxOf(initialRowCards(p == Page.LIVE), restoreIndex + 1)
                        val made = row(title, items.take(maxOf(initial, old?.items?.size ?: initial)), more, wide = (p == Page.LIVE))
                        slot.addView(made.view)
                        restoreCatalogFocus?.invoke()
                        updateNextRow()
                        if (jumpTo == title) { jumpTo = null; made.view.post { made.focusFirst() } }
                    }
                    if (i < 2) add() else main.postDelayed(::add, (i % 4) * ROW_STAGGER)
                    // Nothing to continue: the cinema shows the first catalog's top titles instead.
                    main.post(::updateImages)
                }
                // Answers already read this session (or warmed from disk) build the row at once: switching to a page
                // doesn't wait for the thread pool behind the last page's refreshes. A disk answer (true) refreshes
                // once; after that the row is marked done for the session.
                fun update() {
                    val current = sourceAnswers.map { it ?: (emptyList<Meta>() to true) }
                    val failed = failures.indexOfFirst { it != null }
                    val failure = failures.getOrNull(failed)
                    if (failure != null) answers.remove(key)
                    else if (current.any { it.first.isNotEmpty() }) answers[key] = current
                    catalogStatus(key, failure, rowCatalogs.getOrNull(failed)?.addon?.name)
                    onAnswer(Result.success(current))
                }
                fun refresh(index: Int) {
                    val catalog = rowCatalogs[index]
                    val option = if (genreFallback) genreOption(catalog.genres, picked!!) else picked
                    background({ catalog.addon.catalog(catalog, option, CATALOG_WAIT) }) { fresh ->
                        if (g != generation || build != catalogBuild) return@background
                        failures[index] = fresh.exceptionOrNull()?.let(::failureOf)
                        fresh.getOrNull()?.let { sourceAnswers[index] = it to false }
                        update()
                    }
                }
                if (picked?.startsWith(HomeStyle.TMDB) == true) { // ChadFlix: a TMDb genre row, kept for the session
                    if (kept != null) return onAnswer(Result.success(kept))
                    background({ HomeStyle.genreTitles(applicationContext, p == Page.MOVIES, picked) }, HomeStyle.rowPool) { r ->
                        // Kept even if the page moved on; a row whose titles mostly lacked IMDb ids is asked again next time.
                        r.getOrNull()?.takeIf { it.second }?.let { answers[key] = listOf(it.first to false) }
                        if (g != generation || build != catalogBuild) return@background
                        if (r.getOrNull()?.first.orEmpty().size <= 1) showingChanged.remove("${p.label}:$title")?.let { (rowKey, was) ->
                            // the new choice failed: the old row stays,
                            HomeStyle.restoreChoice(this@MainActivity, p.label, rowKey, p == Page.MOVIES, was)
                            // The row's Showing menu, if it's still open, shows the choice put back (on its Genre line).
                            if (showingFor == "${p.label}:$title" && showingDialog?.isShowing == true) {
                                showingDialog?.dismiss()
                                rows.firstOrNull { it.title == title }?.let { showingMenu(it, GENRE_LINE) }
                            }
                            return@background onAnswer(Result.success(emptyList())) // focus and all (nothing to add)
                        }
                        onAnswer(r.map { listOf(it.first to false) })
                    }
                    return
                }
                if (kept != null && kept.size == rowCatalogs.size) {
                    kept.forEachIndexed { index, answer -> sourceAnswers[index] = answer }
                    completed = rowCatalogs.size
                    update()
                    rowCatalogs.indices.filter { kept[it].second }.forEach(::refresh)
                } else if (genreFallback) {
                    fun accept(answer: List<Pair<List<Meta>, Boolean>>) {
                        if (g != generation || build != catalogBuild) return
                        answer.forEachIndexed { index, result -> sourceAnswers[index] = result }
                        completed = rowCatalogs.size
                        update()
                    }
                    fun firstGenre(read: (Addon.Catalog, String) -> List<Meta>): Pair<List<Pair<List<Meta>, Boolean>>, Failure?> {
                        val results = MutableList(rowCatalogs.size) { emptyList<Meta>() to false }
                        var failure: Failure? = null
                        for ((index, catalog) in rowCatalogs.withIndex()) {
                            val option = genreOption(catalog.genres, picked!!) ?: continue
                            val items = runCatching { read(catalog, option) }.getOrElse {
                                if (failure == null) failure = failureOf(it)
                                emptyList()
                            }
                                .filterNot { isAddonMessage(it.name) }
                            if (items.isNotEmpty()) {
                                results[index] = items to false
                                break
                            }
                        }
                        return results to failure
                    }
                    background({ firstGenre { catalog, option -> catalog.addon.savedCatalog(catalog, option).orEmpty() } }, cacheReads) { answer ->
                        if (g != generation || build != catalogBuild) return@background
                        val saved = answer.getOrNull()?.first.orEmpty()
                        if (saved.any { it.first.isNotEmpty() }) accept(saved.map { it.first to it.first.isNotEmpty() })
                        background({ firstGenre { catalog, option -> catalog.addon.catalog(catalog, option, CATALOG_WAIT) } }) { result ->
                            if (g != generation || build != catalogBuild) return@background
                            val (fresh, failure) = result.getOrElse { emptyList<Pair<List<Meta>, Boolean>>() to failureOf(it) }
                            failures[0] = failure
                            if (saved.none { it.first.isNotEmpty() } || fresh.any { it.first.isNotEmpty() }) accept(fresh)
                            else update()
                        }
                    }
                } else rowCatalogs.forEachIndexed { index, catalog ->
                    fun accept(items: List<Meta>, cached: Boolean, failure: Failure? = null) {
                        if (g != generation || build != catalogBuild) return
                        sourceAnswers[index] = items to cached
                        failures[index] = failure
                        completed++
                        update()
                        if (cached) refresh(index)
                    }
                    background({ catalog.addon.savedCatalog(catalog, picked) }, cacheReads) { answer ->
                        if (g != generation || build != catalogBuild) return@background
                        val saved = answer.getOrNull()
                        if (saved != null) accept(saved, cached = true)
                        else background({ catalog.addon.catalog(catalog, picked, CATALOG_WAIT) }) { result ->
                            accept(result.getOrDefault(emptyList()), cached = false, failure = result.exceptionOrNull()?.let(::failureOf))
                        }
                    }
                }
            }
            var nextSection = initialSections
            batchPending = nextSection
            for (i in 0 until nextSection) buildSection(i)
            oldSlots.values.forEach { slot ->
                rows.firstOrNull { it.view.parent === slot }?.let { row ->
                    row.cards.forEach { it.unload() }
                    rows.remove(row)
                }
                content.removeView(slot)
            }
            val first = content.childCount - catalogSlots.size
            catalogSlots.values.forEachIndexed { index, slot ->
                if (content.indexOfChild(slot) != first + index) {
                    content.removeView(slot)
                    content.addView(slot, first + index)
                }
            }
            oldSlots.clear()
            if (focusedTag != null && !(focused.isAttachedToWindow && focused.isFocused)) {
                navItems[p]?.requestFocus()
                val fallback = currentFocus
                restoreCatalogFocus = restore@{
                    if (currentFocus !== fallback) {
                        restoreCatalogFocus = null
                        return@restore
                    }
                    val sameRow = rows.firstNotNullOfOrNull { row ->
                        row.view.takeIf { row.title == focusedRow?.title }?.findViewWithTag<View>(focusedTag)
                    }
                    val card = sameRow ?: rows.firstNotNullOfOrNull { it.view.findViewWithTag<View>(focusedTag) }
                    if (card?.requestFocus() == true) {
                        restoreCatalogFocus = null
                        android.util.Log.d("Alder", "page ${p.label}: restored catalog focus")
                    }
                }
                restoreCatalogFocus?.invoke()
            }
            main.post { updateNextRow(); updateImages() }
            loadMoreSections = {
                val focusedSlot = rows.firstOrNull { it.view.hasFocus() }?.view?.parent as? View
                val nearFocus = focusedSlot != null && content.indexOfChild(focusedSlot) >= content.childCount - 3
                val nearEnd = batchPending == 0 && scroller.scrollY + scroller.height >= content.height - dp(350)
                if (g == generation && build == catalogBuild && nextSection < sections.size && (nearFocus || nearEnd)) {
                    val end = nextCatalogSection(sections.size, nextSection)
                    batchPending = end - nextSection
                    for (i in nextSection until end) buildSection(i)
                    nextSection = end
                }
            }
        }
        background({ Stremio.savedAddons(key) }, cacheReads) { r -> if (builtFrom == null) r.getOrNull()?.let(::build) }
        var addonRequest = 0
        fun checkAddons() {
            val request = ++addonRequest
            background({ Stremio.addons(key, refresh = true) }) { r ->
                if (g == generation && request == addonRequest) r.fold({
                    catalogStatus("add-ons", null)
                    build(it)
                }) {
                    if (builtFrom == null && p != Page.HOME) failedToLoad(it)
                    else {
                        forgetIfSessionGone(it)
                        if (!isFinishing) catalogStatus("add-ons", failureOf(it))
                    }
                }
            }
        }
        checkAddons()
        refreshAddons = ::checkAddons
        rebuildRows = { builtFrom = null; build(lastAddons) } // the same rows in a new order: built rows are kept and moved
    }

    private class Shelf(val page: Page, val title: String, val rank: Int)

    private fun buildGridChips(
        tab: Page,
        rememberedKey: String? = null,
        fallbackKey: String? = null,
        build: (add: (key: String, label: String, selected: Boolean, margin: Int, list: Boolean, click: () -> Unit) -> TextView) -> Unit,
    ) {
        genreStrip.removeAllViews()
        genreChips.clear()
        val chips = mutableListOf<TextView>()
        val line = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL; setPadding(dp(PAD), dp(2), dp(PAD), dp(2)) }
        fun add(key: String, label: String, selected: Boolean, margin: Int = 6, list: Boolean = false, click: () -> Unit): TextView =
            (if (list) dropdown(label) else chip(label, selected)).apply {
                tag = key
                id = View.generateViewId()
                nextFocusUpId = navItems[tab]?.id ?: View.NO_ID
                setOnFocusChangeListener { v, has ->
                    v.background = listChipBackground(has)
                    v.grow(has, CHIP_GROW).start()
                    setTextColor(if (has || v.isSelected || list) TEXT else TEXT_2)
                    if (has) lastListChip = this
                }
                background = listChipBackground(false)
                setOnClickListener { click() }
                line.addView(this, LinearLayout.LayoutParams(-2, dp(CHIP_H)).apply { if (line.childCount > 0) marginStart = dp(margin) })
                chips += this
            }
        build(::add)
        val supportsRtl = (applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_SUPPORTS_RTL) != 0
        val rtl = isStripRtl(supportsRtl, resources.configuration.layoutDirection)
        chips.firstOrNull()?.let { if (rtl) it.nextFocusRightId = it.id else it.nextFocusLeftId = it.id }
        chips.lastOrNull()?.let { if (rtl) it.nextFocusLeftId = it.id else it.nextFocusRightId = it.id }
        genreStrip.addView(line)
        genreStrip.visibility = View.VISIBLE
        if (rememberedKey != null) {
            discoverChipIndex(chips.map { it.tag as String }, rememberedKey, fallbackKey.orEmpty())?.let { index ->
                chips[index].let { lastListChip = it; it.requestFocus() }
            }
        }
    }

    private fun showListChips(focusKey: String? = null) {
        val focused = focusKey ?: lastListChip?.takeIf { it.isAttachedToWindow && it.hasFocus() }?.tag as? String
        listFilterChips.clear()
        lastListChip = null
        buildGridChips(Page.LIBRARY, focused) { add ->
            listOf(
                Triple("all", ListFilter.ALL, getString(R.string.filter_all)),
                Triple("movie", ListFilter.MOVIES, getString(R.string.tab_movies)),
                Triple("series", ListFilter.SHOWS, getString(R.string.tab_shows)),
            ).forEach { (key, value, label) ->
                listFilterChips[value] = add(key, label, value == listFilter, 6, false) {
                    if (listFilter != value) { listFilter = value; styleListChips(); redrawLibrary() }
                }
            }
            // Genre and Year come from each title's details (on disk after the first visit), read in the background.
            fun pick(key: String, name: Int, allLabel: Int, value: Any?, options: () -> List<Pair<String, Any?>>, margin: Int, set: (Any?) -> Unit) {
                val current = options().firstOrNull { it.second == value }?.first
                val unset = value == null || value == (null to null) || current == null
                add(key, if (unset) getString(allLabel) else "${getString(name)}: $current", false, margin, true) {
                    val shown = options()
                    choose(getString(name), shown.map { it.first }, checked = shown.indexOfFirst { it.second == value }.coerceAtLeast(0)) { i ->
                        if (shown[i].second != value) {
                            set(shown[i].second)
                            showListChips(key)
                            redrawLibrary()
                        }
                    }
                }
            }
            val all = getString(R.string.filter_all)
            pick("genre", R.string.discover_genre, R.string.discover_all_genres, listGenre, {
                listOf(all to null) + listTitles().flatMap { metas[it.optString("_id")]?.genres.orEmpty() }
                    .groupingBy { it }.eachCount().entries.sortedByDescending { it.value }.map { it.key to it.key }
            }, margin = 24) { listGenre = it as String? }
            pick("decade", R.string.discover_year, R.string.discover_all_decades, listDecade to listYear, ::decadeOptions, margin = 6) {
                val choice = it as Pair<*, *>
                listDecade = choice.first as Int?; listYear = choice.second as Int?
            }
            fillListDetails()
            listSortChip = add("sort", listSortLabel(), false, 6, true) {
                val options = ListSort.entries
                choose(getString(R.string.list_sort), options.map { getString(it.label) }, checked = listSort.ordinal) { index ->
                    val value = options[index]
                    if (listSort != value) {
                        listSort = value
                        listSortChip.text = listSortLabel()
                        redrawLibrary()
                    }
                }
            }
        }
    }

    /** Discover's and My Alder's Year choices: All, recent years, the 2020s back to the 1980s, then before 1980 (0). */
    private fun decadeOptions(): List<Pair<String, Any?>> {
        val currentYear = java.util.Calendar.getInstance().get(java.util.Calendar.YEAR)
        return listOf(getString(R.string.filter_all) to (null to null)) +
            (currentYear downTo 2020).map { "$it" to (null to it) } +
            listOf(2020, 2010, 2000, 1990, 1980).map { "${it}s" to (it to null) } +
            (getString(R.string.discover_before_1980) to (0 to null))
    }

    private fun listTitles() = library.filter { !it.optBoolean("removed") && !isLiveType(it.optString("type")) }

    /**
     * My List's titles' details for Genre and Year: from disk when Alder has seen the title, else from the add-ons.
     * ponytail: one title at a time on one thread; the first visit to a long list takes a while, later ones read disk.
     */
    private fun fillListDetails() {
        val missing = listTitles().filter {
            val id = it.optString("_id")
            val type = it.optString("type")
            (id !in metas || isMetaExpired(type, metaFetchedAt[id], META_REFRESH_MS)) && id !in fillingListDetails
        }
        if (missing.isEmpty()) return
        val ids = missing.map { it.optString("_id") }
        fillingListDetails += ids
        val key = profile.authKey
        background({
            val saved by lazy { Stremio.savedAddons(key).orEmpty() }
            val remote by lazy { runCatching { Stremio.addons(key) } }
            missing.mapNotNull { t ->
                val id = t.optString("_id"); val type = t.optString("type")
                val ttl = if (type != "movie") META_REFRESH_MS else null
                resolveMetaWithOfflineFallback(
                    ttl = ttl,
                    readSaved = { age -> saved.savedMeta(type, id, age) },
                    fetchRemote = { remote.getOrThrow().meta(type, id) },
                )?.let { id to it }
            }
        }, metaPool) { r ->
            fillingListDetails -= ids
            val now = System.currentTimeMillis()
            r.getOrNull()?.forEach { (id, resolved) ->
                cacheMeta(id, resolved.meta, preservedMetaTimestamp(resolved.fresh, metaFetchedAt[id], now))
                val count = newEpisodeCount(id, resolved.meta)
                rows.forEach { row ->
                    row.cards.forEach { card ->
                        val item = card.payload as? Item
                        if (item?.id == id) {
                            item.newEpisodeCount = count
                            card.setNewEpisodes(count)
                        }
                    }
                }
            }
        }
    }

    /** The open page's "Some catalogs couldn't load" Try again, while it shows: Down from the top bar goes there first. */
    private fun warning() = pageWarning?.takeIf { it.isAttachedToWindow && it.isShown }

    /** Where Up from a page's first row, Down from the top bar and Back from the rows go: the page's chips. */
    private fun pageChip(): View? = when (page) {
        Page.LIBRARY -> lastListChip ?: listFilterChips[listFilter]
        Page.DISCOVER -> discoverChipIndex(discoverChips.map { it.tag as String },
            lastListChip?.takeIf { it in discoverChips }?.tag as? String, discover.type)?.let(discoverChips::get)
        // ChadFlix: Movies and Shows hide their genre chips, and a view in a hidden strip can still take focus: none then.
        else -> genreChips[genre]?.takeIf { genreStrip.visibility == View.VISIBLE }
    }

    // ---------- Discover ----------

    /** TMDB's genre and service lists, read once per type for this visit. */
    private val discoverLists = java.util.concurrent.ConcurrentHashMap<String, Pair<List<Pair<Int, String>>, List<Pair<Int, String>>>>()

    private fun discoverKey() = "discover:${profile.id}"

    /**
     * Discover: every film and show from TMDB, not only the add-ons' catalogs (Shows and Movies have those). Chips pick
     * the type, genre, decade, streaming service and order; the choice is kept per profile. Cards carry IMDb ids, so
     * they open, play and save like any other title.
     */
    private fun showDiscover(g: Int) {
        if (g != generation) return
        if (!discoverLoaded) {
            discoverLoaded = true
            getSharedPreferences("home", MODE_PRIVATE).getString(discoverKey(), null)?.let { saved ->
                runCatching { JSONObject(saved) }.getOrNull()?.let { j ->
                    discover = DiscoverQuery(j.optString("type").takeIf { it in setOf("movie", "series", "anime") } ?: "movie", j.optInt("genre").takeIf { it > 0 },
                        j.optInt("decade", -1).takeIf { it >= 0 }, j.optInt("service").takeIf { it > 0 }, j.optString("sort", "popular"),
                        j.optInt("year").takeIf { it > 0 })
                }
            }
        }
        rows.forEach { r -> r.cards.forEach { it.unload() } }
        rows.clear()
        content.removeAllViews()
        val media = discover.media
        background({
            discoverLists.getOrPut(media) { Tmdb.genres(media) to runCatching { Tmdb.services(media) }.getOrDefault(emptyList()) }
        }) { r ->
            if (g != generation) return@background
            r.fold({ (genres, services) -> discoverGrid(g, genres, services) }) { e ->
                FailureLog.record(FailureLog.Operation.CATALOG, failureOf(e))
                content.removeAllViews()
                val retry = failed(failureMessage(R.string.catalog_incomplete, failureOf(e))) { showDiscover(++generation) }
                content.addView(retry, LinearLayout.LayoutParams(-2, -2).apply { marginStart = dp(PAD) })
                retry.post { if (retry.isAttachedToWindow && currentFocus == null) retry.getChildAt(1).requestFocus() }
            }
        }
    }

    private fun chooseDiscover(query: DiscoverQuery) {
        if (query == discover) return
        discover = query
        getSharedPreferences("home", MODE_PRIVATE).edit().putString(discoverKey(), JSONObject()
            .put("type", query.type).putOpt("genre", query.genre).putOpt("decade", query.decade)
            .putOpt("year", query.year).putOpt("service", query.service).put("sort", query.sort).toString()).apply()
        showDiscover(++generation)
    }

    private fun showDiscoverChips(genres: List<Pair<Int, String>>, services: List<Pair<Int, String>>) {
        val q = discover
        val focused = lastListChip?.takeIf { it in discoverChips }?.tag as? String
        discoverChips.clear()
        buildGridChips(Page.DISCOVER, focused, q.type) { add ->
            fun addChip(key: String, label: String, selected: Boolean, margin: Int = 6, list: Boolean = false, click: () -> Unit) {
                discoverChips += add(key, label, selected, margin, list, click)
            }
            fun pick(name: Int, allLabel: Int, options: List<Pair<String, Any?>>, value: Any?, margin: Int = 6, set: (Any?) -> DiscoverQuery) {
                val current = options.firstOrNull { it.second == value }?.first
                val unset = value == null || value == (null to null) || current == null
                addChip(resources.getResourceEntryName(name), if (unset) getString(allLabel) else "${getString(name)}: $current", false, margin, list = true) {
                    choose(getString(name), options.map { it.first }, checked = options.indexOfFirst { it.second == value }.coerceAtLeast(0)) { i ->
                        chooseDiscover(set(options[i].second))
                    }
                }
            }
            listOf("movie" to R.string.tab_movies, "series" to R.string.tab_shows, "anime" to R.string.tab_anime).forEach { (type, words) ->
                addChip(type, getString(words), type == q.type) {
                    if (type != q.type) chooseDiscover(q.copy(type = type, genre = null, service = null))
                }
            }
            val all = getString(R.string.filter_all)
            val genreOptions = listOf(all to null) + genres.filter { q.type != "anime" || it.first != ANIMATION }.map { it.second to it.first }
            pick(R.string.discover_genre, R.string.discover_all_genres, genreOptions, q.genre, margin = 24) { q.copy(genre = it as Int?) }
            pick(R.string.discover_year, R.string.discover_all_decades, decadeOptions(), q.decade to q.year) {
                val choice = it as Pair<*, *>
                q.copy(decade = choice.first as Int?, year = choice.second as Int?)
            }
            if (services.isNotEmpty()) pick(R.string.discover_on, R.string.discover_all_services, listOf(all to null) + services.map { it.second to it.first }, q.service) {
                q.copy(service = it as Int?)
            }
            val sorts = listOf(getString(R.string.discover_sort_popular) to "popular", getString(R.string.discover_sort_top) to "top",
                getString(R.string.discover_sort_new) to "new")
            addChip("sort", "${getString(R.string.list_sort)}: ${sorts.first { it.second == q.sort }.first}", false, list = true) {
                choose(getString(R.string.list_sort), sorts.map { it.first }, checked = sorts.indexOfFirst { it.second == q.sort }) { i ->
                    chooseDiscover(q.copy(sort = sorts[i].second))
                }
            }
        }
    }

    /**
     * The chosen titles, six to a row; TMDB's next page (20 titles) comes as the remote nears the last rows. A short last
     * row waits for the next page, so rows stay full.
     */
    private fun discoverGrid(g: Int, genres: List<Pair<Int, String>>, services: List<Pair<Int, String>>) {
        if (g != generation) return
        showDiscoverChips(genres, services)
        rows.forEach { r -> r.cards.forEach { it.unload() } }
        rows.clear()
        content.removeAllViews()
        warningHost.removeAllViews()
        pageWarning = null
        scroller.scrollTo(0, 0)
        last = null
        cardUnderline.visibility = View.INVISIBLE
        cancelBrowseHero()
        heroItem = null
        browseInfo.alpha = 0f
        resetArt()
        val build = ++listBuild
        val query = discover
        val names = genres.toMap()
        val column = column(Gravity.START).apply { setPadding(0, 0, 0, dp(SAFE_BOTTOM)) }
        content.addView(column)
        val known = mutableSetOf<String>()
        val waiting = mutableListOf<Item>() // a short last row, held for the next page
        var next = 1
        var loading = false
        var done = false
        fun current() = g == generation && build == listBuild && page == Page.DISCOVER
        fun note(words: Int) = content.addView(text(words, BODY, color = TEXT_2).apply { setPadding(dp(PAD), dp(30), dp(PAD), 0) })
        fun more() {
            if (loading || done || !current()) return
            loading = true
            val at = next
            background({ Tmdb.discover(query, at, names) }) { r ->
                if (!current()) return@background
                loading = false
                r.fold({ answer ->
                    next = at + 1
                    done = answer.last
                    val fresh = answer.titles.filter { known.add(it.id) }.map(::metaItem)
                    val chunks = (waiting + fresh).chunked(LIST_COLUMNS).toMutableList()
                    waiting.clear()
                    if (!done && chunks.lastOrNull()?.let { it.size < LIST_COLUMNS } == true) waiting += chunks.removeAt(chunks.lastIndex)
                    val first = rows.isEmpty()
                    chunks.forEach { column.addView(row("", it, grid = true).view) }
                    if (rows.isEmpty()) {
                        if (done) note(R.string.discover_empty) else more() // a page of titles without IMDb ids
                        return@fold
                    }
                    if (first) showHero(rows.first().items.first())
                    main.post(::updateImages)
                }) { e ->
                    val reason = failureOf(e)
                    FailureLog.record(FailureLog.Operation.CATALOG, reason)
                    if (rows.isEmpty()) {
                        content.removeAllViews()
                        content.addView(failed(failureMessage(R.string.catalog_incomplete, reason)) { discoverGrid(g, genres, services) },
                            LinearLayout.LayoutParams(-2, -2).apply { marginStart = dp(PAD) })
                    }
                }
            }
        }
        // Row.focus calls this: two rows from the end, the next page comes.
        loadMoreSections = {
            val at = rows.indexOfFirst { it.view.hasFocus() }
            if (at >= 0 && at >= rows.size - 2) more()
        }
        more()
    }

    private fun styleListChips() {
        listFilterChips.forEach { (value, view) -> view.select(getString(when (value) {
            ListFilter.ALL -> R.string.filter_all; ListFilter.MOVIES -> R.string.tab_movies; ListFilter.SHOWS -> R.string.tab_shows
        }), value == listFilter) }
    }

    private fun listSortLabel() = "${getString(R.string.list_sort)}: ${getString(listSort.label)}"

    /** All, then one chip per genre row on the page; a chip jumps to its row, and the dot follows the row in use. */
    private fun showGenreChips(names: List<String>) {
        val hadFocus = genreStrip.hasFocus()
        val focusedGenre = genreChips.entries.firstOrNull { it.value.hasFocus() }?.key
        genreStrip.removeAllViews()
        genreChips.clear()
        val line = LinearLayout(this).apply {
            clipChildren = false
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(PAD), dp(2), dp(PAD), dp(2))
        }
        (listOf<String?>(null) + names).forEach { value ->
            val control = chip(value ?: genreStartLabel(), value == null).apply {
                setOnClickListener {
                    val target = if (value == null) firstRow() else rows.firstOrNull { it.title == value && it.view.isAttachedToWindow }
                    // Rows far down the page are still being made: the remote goes there as soon as it's in.
                    if (target != null) target.focusFirst() else jumpTo = value
                }
            }
            genreChips[value] = control
            line.addView(control, LinearLayout.LayoutParams(-2, dp(CHIP_H)).apply { if (line.childCount > 0) marginStart = dp(6) })
        }
        genreStrip.addView(line)
        if (hadFocus) (genreChips[focusedGenre] ?: genreChips[null])?.requestFocus()
    }

    private fun genreStartLabel() = getString(R.string.filter_all)

    private fun markChip(rowTitle: String) {
        val open = rowTitle.takeIf { it in genreChips }
        genre = open // Back and Down from the top bar return to this chip
        genreChips.forEach { (value, chip) ->
            val selected = if (page.hasGenreShortcuts && value == null) rowTitle == firstRow()?.title else value == open
            chip.select(value ?: genreStartLabel(), selected)
        }
    }

    /**
     * Where a catalog's row goes and what it's called, like Netflix: Home is what you're watching and what it led to
     * ("Because you watched…"); everything else sits on Movies or Shows by type, without the addon's name or a type
     * suffix. Personal picks come first (rank 0–1), then what's popular (2), then themed lists (3). TV channels, sport
     * and events go to Live, one row per catalog under its own name; any other type (anime, video channels) gets a page
     * of its own, built like Shows.
     */
    private fun shelf(c: Addon.Catalog): Shelf? {
        val typePage = when {
            c.type == "movie" -> Page.MOVIES
            c.type == "series" -> Page.SHOWS
            isLiveType(c.type) -> return Shelf(Page.LIVE, tidy(c.name), 3)
            else -> Page.of(c.type.trim().lowercase())
        }
        val india = mapOf(
            "trendingmovies" to "Trending in India", "trendingtv" to "Trending in India",
            "popmov" to "Popular in India",
            "nfxprm" to "Popular on Netflix & Prime", "nfxprmtv" to "Popular on Netflix & Prime",
            "hstzee" to "Popular on Hotstar, SonyLIV & Zee5", "hstzeetv" to "Popular on Hotstar, SonyLIV & Zee5",
            "recmov" to "Recommended in India", "atpmub" to "Recommended in India",
        )
        val lowerId = c.id.lowercase()
        return when {
            c.addon.id.contains("watchly") && c.id.startsWith("watchly.item") -> Shelf(Page.HOME, homeRowName(c.name), 0)
            c.addon.id.contains("watchly") && c.id == "watchly.rec" -> Shelf(typePage, "Top Picks for You", 0)
            c.addon.id.startsWith("trakt.addon") && c.id.startsWith("recommendations") -> Shelf(typePage, "Recommended for You", 1)
            c.addon.id.startsWith("trakt.addon") && c.id.startsWith("trending") -> Shelf(typePage, "Trending Now", 2)
            c.addon.id == "in.rdata.indiastreams" -> Shelf(typePage, india[c.id] ?: c.name, 2)
            lowerId in setOf("top", "popular", "trending") || c.name.equals("Top", true) || c.name.equals("Popular", true) || c.name.equals("Trending", true) -> Shelf(typePage, tidy(c.name), 2)
            else -> Shelf(typePage, tidy(c.name), 3)
        }
    }

    /** Addon names as a person would write them: no "TV Shows:" label, no run-together keyword tags. */
    private fun tidy(name: String) = name.removePrefix("TV Shows: ").removePrefix("MyTrakt - ")
        // ponytail: Watchly glues TMDB keyword tags onto theme names ("Comedy Aftercreditsstinger"); a word this long is one.
        .split(" ").filter { it.length < 16 }.joinToString(" ")

    private fun startCinema(items: List<Item>) {
        cinema = items
        cinemaAt = 0
        if (mode != Mode.CINEMA) return
        showCinema(0)
        stage.requestFocus()
        chrome()
    }

    private fun slot() = FrameLayout(this).also { content.addView(it, LinearLayout.LayoutParams(-1, -2)) }

    private fun redrawLibrary(removedIndex: Int? = null) {
        val slot = librarySlot ?: return
        val was = currentFocus?.takeIf { isInside(it, slot) }?.tag
        rows.filter { isInside(it.view, slot) }.forEach { row -> row.cards.forEach { it.unload() }; rows.remove(row) }
        slot.removeAllViews()
        scroller.scrollTo(0, 0)
        last = null
        showLibrary(slot, was, removedIndex)
    }

    private fun showLibrary(slot: FrameLayout, focusTag: Any? = null, removedIndex: Int? = null) {
        fillListDetails()
        val all = listTitles()
            .filter { listFilter == ListFilter.ALL || (it.optString("type") == "movie") == (listFilter == ListFilter.MOVIES) }
            .filter { t -> listGenre == null || metas[t.optString("_id")]?.genres?.contains(listGenre) == true }
            .filter { t ->
                val y = metas[t.optString("_id")]?.year
                (listYear == null || inYear(y, listYear!!)) && (listDecade == null || inDecade(y, listDecade!!))
            }
        val ordered = when (listSort) {
            ListSort.RECENT -> all.sortedByDescending { it.optString("_ctime") }
            ListSort.NAME -> all.sortedBy { it.optString("name").lowercase() }
            ListSort.UNWATCHED -> all.sortedWith(compareBy<JSONObject> { libraryFinished(it) }.thenByDescending { it.optString("_ctime") })
        }
        val items = ordered.map(::libraryItem)
        val build = ++listBuild
        if (items.isEmpty()) {
            cancelBrowseHero()
            heroItem = null
            browseInfo.alpha = 0f
            cardUnderline.visibility = View.INVISIBLE
            resetArt()
            val message = when {
                listGenre != null || listDecade != null || listYear != null -> R.string.discover_empty
                all.isEmpty() && listFilter == ListFilter.ALL -> R.string.list_empty
                listFilter == ListFilter.MOVIES -> R.string.list_empty_movies
                listFilter == ListFilter.SHOWS -> R.string.list_empty_shows
                else -> R.string.list_empty_unwatched
            }
            val emptyBox = column(Gravity.START).apply { setPadding(dp(PAD), dp(30), dp(PAD), 0) }
            emptyBox.addView(text(message, BODY, color = TEXT_2))
            if (listFilter != ListFilter.ALL || listGenre != null || listDecade != null || listYear != null) {
                val clear = chip(getString(R.string.list_clear_filters), false).apply {
                    tag = "clear_filters"
                    background = listChipBackground(false)
                    setTextColor(TEXT)
                    nextFocusUpId = pageChip()?.id ?: View.NO_ID
                    setOnFocusChangeListener { v, has ->
                        v.background = listChipBackground(has)
                        v.grow(has, CHIP_GROW).start()
                    }
                    setOnClickListener {
                        listFilter = ListFilter.ALL
                        listGenre = null
                        listDecade = null
                        listYear = null
                        showListChips("all")
                        redrawLibrary()
                    }
                }
                emptyBox.addView(clear, LinearLayout.LayoutParams(-2, dp(CHIP_H)).apply { topMargin = dp(16) })
                if (focusTag == "clear_filters") clear.requestFocus()
            }
            slot.addView(emptyBox)
            if ((focusTag != null && focusTag != "clear_filters") || removedIndex != null) listFilterChips[listFilter]?.requestFocus()
            return
        }
        val column = column(Gravity.START).apply { setPadding(0, 0, 0, dp(SAFE_BOTTOM)) }
        slot.addView(column)
        val restore = items.indexOfFirst { "card:${it.id}" == focusTag }.takeIf { it >= 0 }
            ?: removedIndex?.coerceIn(items.indices) ?: 0.takeIf { focusTag != null }
        if (restore == null) showHero(items.first())
        val previousFocus = currentFocus
        items.chunked(LIST_COLUMNS).forEachIndexed { index, cards ->
            if (page != Page.LIBRARY || slot !== librarySlot || build != listBuild) return
            val row = row("", cards, grid = true)
            column.addView(row.view)
            if (restore != null && index == restore / LIST_COLUMNS &&
                (currentFocus == null || currentFocus === previousFocus)) {
                row.cards[restore % LIST_COLUMNS].requestFocus()
            }
        }
        main.post(::updateImages)
    }

    private fun libraryFinished(item: JSONObject): Boolean {
        val state = item.optJSONObject("state") ?: return false
        return state.optInt("timesWatched") > 0 && state.optLong("timeOffset") == 0L
    }

    private fun listMenu(item: Item) {
        val original = library.firstOrNull { it.optString("_id") == item.id } ?: return
        val index = rows.firstOrNull { it.view.hasFocus() }?.let { rows.indexOf(it) * LIST_COLUMNS + it.selected }
        val removeLabel = getString(R.string.action_remove_from_list)
        val options = (if (item.finished) emptyList() else listOf(getString(R.string.action_mark_watched))) + removeLabel
        fun save(remove: Boolean, previous: Failure? = null) {
            val key = profile.authKey
            val known = metas[item.id]
            background({
                // A show's episodes are needed to mark it watched; without them only the title would be, so say why.
                val videos = if (remove) emptyList() else (known ?: runCatching {
                        Stremio.addons(key).let { it.savedMeta(item.type, item.id) ?: it.meta(item.type, item.id) }
                    }.getOrElse { if (item.type == "movie") null else throw it })?.videos.orEmpty()
                Stremio.editLibrary(key, item.id, JSONObject(original.toString())) { updated ->
                    if (remove) Library.setInList(updated, false) else Library.markTitleWatched(updated, videos)
                }
            }) { result ->
                result.fold({ updated ->
                    previous?.let { FailureLog.record(FailureLog.Operation.LIBRARY_EDIT, it, recovered = true) }
                    library = library.map { if (it.optString("_id") == item.id) updated else it }
                    if (page == Page.LIBRARY) {
                        val stillOnTitle = ((currentFocus as? MediaCardView)?.payload as? Item)?.id == item.id || currentFocus == null
                        redrawLibrary(index.takeIf { remove && stillOnTitle })
                    }
                }) { error ->
                    forgetIfSessionGone(error)
                    val reason = failureOf(error)
                    FailureLog.record(FailureLog.Operation.LIBRARY_EDIT, reason)
                    if (page == Page.LIBRARY && !isFinishing) {
                        choose(getString(if (remove) R.string.list_update_failed else R.string.library_edit_failed),
                            listOf(getString(R.string.action_try_again), getString(R.string.action_cancel)),
                            "${getString(reason.message)}\n${getString(reason.advice)}") {
                            if (it == 0) save(remove, reason)
                        }
                    }
                }
            }
        }
        choose(item.name, options) { selected ->
            if (selected != options.lastIndex) save(false)
            else choose(removeLabel, listOf(getString(R.string.action_cancel), removeLabel), item.name) {
                if (it == 1) save(true)
            }
        }
    }

    /** Hold OK on a Continue Watching title: choose a source yourself, or take the title off the row. */
    private fun continueMenu(item: Item) {
        choose(item.name, listOf(getString(R.string.action_choose_source), getString(R.string.action_remove_from_continue))) { selected ->
            if (selected == 0) return@choose play(item, pick = true)
            val original = library.firstOrNull { it.optString("_id") == item.id } ?: return@choose
            val updated = JSONObject(original.toString())
            val offset = Library.hideFromContinueWatching(updated)
            background({ Stremio.saveLibrary(profile.authKey, listOf(updated)) }) { result ->
                result.fold({
                    Settings.setHidden(this, Settings.hidden(this)
                        .put(item.id, JSONObject().put("name", item.name).put("timeOffset", offset)))
                    library = library.map { if (it.optString("_id") == item.id) updated else it }
                    refreshLibrary?.invoke() // the row (and Google TV's) is drawn again without it
                }) { error ->
                    forgetIfSessionGone(error)
                    if (!isFinishing) choose(getString(R.string.remove_failed), listOf(getString(R.string.action_ok)), getString(R.string.check_connection)) { }
                }
            }
        }
    }

    /** Hold OK on a catalog card: choose a source yourself, move the row (ChadFlix), or take the whole row off the page. */
    private fun rowMenu(row: Row, item: Item) {
        val at = pageRows.indexOf(row.title)
        val actions = listOfNotNull<Pair<String, () -> Unit>>(
            (getString(R.string.action_choose_source) to { play(item, pick = true) }).takeIf { !HomeStyle.isTile(item.id) },
            (getString(R.string.row_move_up) to { moveRow(row.title, -1) }).takeIf { at > 0 },
            (getString(R.string.row_move_down) to { moveRow(row.title, 1) }).takeIf { at >= 0 && at < pageRows.lastIndex },
            (getString(R.string.action_hide_row, HomeStyle.rowName(this, row.title)) to {
                // ChadFlix: on Movies and Shows a row is turned off for that page only (Drama on Movies, not on Shows)
                if (page.hasGenreShortcuts || page == Page.SERVICES) HomeStyle.setRowOn(this, page.label, row.title, false) else Settings.hideRow(this, row.title)
                showPage(page)
            })
                .takeIf { row.title.isNotEmpty() },
        )
        choose(item.name.ifEmpty { HomeStyle.rowName(this, row.title) }, actions.map { it.first }) { actions[it].second() }
    }

    /**
     * ChadFlix: the Anime row's Showing card, like the Services one: a "Genre  All  ›" line (OK or Right opens All, then
     * genres that go with Animation; a pick comes back here on the line, Left or Back without one), then the sorts, where
     * OK applies genre and sort together.
     */
    private fun animeMenu(row: Row, focus: Int = -1) {
        val movie = page == Page.MOVIES
        val genres = listOf(0 to getString(R.string.filter_all)) + HomeStyle.animeGenres(movie)
        val now = HomeStyle.animeGenre(this, page.label)
        val genre = now
        val changing = "${page.label}:${row.title}" // picking again before it answered: back to the choice last shown
        val genreName = genres.firstOrNull { it.first == genre }?.second ?: genres[0].second
        val lists = HomeStyle.listsFor(movie, "a")
        val current = HomeStyle.showing(this, page.label, "a")
        fun genreMenu() = choose("${HomeStyle.ANIME} · ${getString(R.string.discover_genre)}", genres.map { it.second }, header = true,
            checked = genres.indexOfFirst { it.first == genre }.coerceAtLeast(0),
            left = { animeMenu(row, 0) }, back = { animeMenu(row, 0) }) { g ->
            // A genre applies at once with the row's sort (an empty answer puts the old one back); the menu stays, on its line.
            if (genres[g].first != now) {
                showingChanged[changing] = "a" to (showingChanged[changing]?.second ?: HomeStyle.choiceOf(this, page.label, "a", movie))
                HomeStyle.setAnimeGenre(this, page.label, genres[g].first)
                rebuildRows?.invoke() ?: showPage(page)
            }
            animeMenu(row, 0)
        }
        val names = listOf("${getString(R.string.discover_genre)}   $genreName  ›") +
            lists.mapIndexed { i, (_, words) -> getString(words) + if (i == 0) " " + getString(R.string.showing_default) else "" }
        val checked = 1 + lists.indexOfFirst { it.first == current }.coerceAtLeast(0)
        val at = if (focus >= 0) focus else if (focus == GENRE_LINE) 0 else checked
        showingFor = changing
        choose("${HomeStyle.ANIME} · ${getString(R.string.showing)}", names, checked = checked, focus = at, header = true,
            right = { i -> if (i == 0) ({ genreMenu() }) else null }) { i ->
            if (i == 0) return@choose genreMenu() // the Genre line: its own list, then back here on it
            val list = lists[i - 1].first
            if (list == current) return@choose
            showingChanged[changing] = "a" to (showingChanged[changing]?.second ?: HomeStyle.choiceOf(this, page.label, "a", movie))
            HomeStyle.setShowing(this, page.label, "a", list)
            rebuildRows?.invoke() ?: showPage(page)
        }
        showingDialog = lastChoose // the menu just opened, so a choice put back can show in it
    }

    /** ChadFlix: OK on a genre row's Showing tile picks the list the row shows; only that row loads again. */
    private fun showingMenu(row: Row, focus: Int = -1) {
        val movie = page == Page.MOVIES
        val key = HomeStyle.rowKey(movie, row.title, page.label) ?: return
        if (key == "a") return animeMenu(row, focus)
        val lists = HomeStyle.listsFor(movie, key)
        val current = HomeStyle.showing(this, page.label, key)
        // ChadFlix: a platform row's picker starts with its Exclusives Only switch (where the tab has a filter for it).
        val platform = key.takeIf { it.startsWith("p") }?.drop(1)?.toInt()?.takeIf { HomeStyle.canExclusive(it, movie) }
        val excl = platform?.let { HomeStyle.exclusive(this, page.label, it, movie) }
        val switch = excl?.let { listOf("${getString(R.string.showing_exclusives_only)}  ·  ${getString(if (it) R.string.state_on else R.string.state_off)}") }.orEmpty()
        // Services rows: a "Genre  All  ›" line after the switch, as on the Anime row: OK or Right opens All, then TMDb's TV
        // genres; a pick applies at once with the row's sort and comes back here on the line, Left or Back without one.
        val service = key.takeIf { it.startsWith("p") && page == Page.SERVICES }?.drop(1)?.toInt()
        val genreNow = service?.let { HomeStyle.serviceGenre(this, page.label, it) } ?: 0
        val genre = genreNow
        val changing = "${page.label}:${row.title}" // picking again before it answered: back to the choice last shown
        val genres = listOf(0 to getString(R.string.filter_all)) + HomeStyle.genres(false)
        val genreName = genres.firstOrNull { it.first == genre }?.second ?: genres[0].second
        val genreLine = service?.let { listOf("${getString(R.string.discover_genre)}   $genreName  ›") }.orEmpty()
        val head = switch + genreLine
        fun genreMenu() = choose("${row.title} · ${getString(R.string.discover_genre)}", genres.map { it.second }, header = true,
            checked = genres.indexOfFirst { it.first == genre }.coerceAtLeast(0),
            left = { showingMenu(row, switch.size) }, back = { showingMenu(row, switch.size) }) { g ->
            if (service != null && genres[g].first != genreNow) { // applies at once; an empty answer puts the old one back
                showingChanged[changing] = key to (showingChanged[changing]?.second ?: HomeStyle.choiceOf(this, page.label, key, movie))
                HomeStyle.setServiceGenre(this, page.label, service, genres[g].first)
                rebuildRows?.invoke() ?: showPage(page)
            }
            showingMenu(row, switch.size)
        }
        val names = head + lists.mapIndexed { i, (_, words) -> getString(words) + if (i == 0) " " + getString(R.string.showing_default) else "" }
        val checked = head.size + lists.indexOfFirst { it.first == current }.coerceAtLeast(0)
        val at = if (focus >= 0) focus else if (focus == GENRE_LINE) switch.size else checked
        showingFor = changing
        choose("${HomeStyle.rowName(this, row.title)} · ${getString(R.string.showing)}", names, checked = checked, focus = at, header = true,
            right = { i -> if (service != null && i == switch.size) ({ genreMenu() }) else null }) { picked ->
            val shown = showingChanged[changing]?.second ?: HomeStyle.choiceOf(this, page.label, key, movie)
            if (platform != null && picked == 0) {
                showingChanged[changing] = key to shown
                HomeStyle.setExclusive(this, page.label, platform, excl != true)
                rebuildRows?.invoke() ?: showPage(page)
                main.post { showingMenu(row, 0) } // the switch stays in reach to flip back or pick a sort
                return@choose
            }
            if (service != null && picked == switch.size) return@choose genreMenu()
            val list = lists[picked - head.size].first
            if (list == current) return@choose
            showingChanged[changing] = key to shown
            HomeStyle.setShowing(this, page.label, key, list)
            rebuildRows?.invoke() ?: showPage(page)
        }
        showingDialog = lastChoose // the menu just opened, so a choice put back can show in it
    }

    /** ChadFlix: moves a row a place on this page, for this profile; the remote stays on the same card. */
    private fun moveRow(title: String, by: Int) {
        // Among every row the page could show, so rows that are off keep their places.
        val all = if (page.hasGenreShortcuts || page == Page.SERVICES || page == Page.HOME) HomeStyle.ordered(this, page.label, page == Page.MOVIES,
            HomeStyle.knownRows(this, page.label, page == Page.MOVIES)) { it } else pageRows
        HomeStyle.saveRowOrder(this, page.label, movedAmong(all, pageRows, title, by))
        lastHomeRows = HomeStyle.signature(this, page.label)
        rebuildRows?.invoke() ?: showPage(page)
        main.post { currentFocus?.let(::snapTo) }
    }

    // ---------- rows ----------

    /** The rest of a list already in hand, a page at a time: a row of hundreds of cards would be slow to build. */
    private fun later(all: List<Item>): ((Int) -> List<Item>)? =
        if (all.size <= START) null else { from -> all.drop(from).take(PAGE) }

    private fun row(title: String, items: List<Item>, more: ((Int) -> List<Item>)? = null, wide: Boolean = false, grid: Boolean = false,
                    continueRow: Boolean = false): Row = Row(title, items, more, wide, grid, continueRow).also {
        rows += it
        main.post(::updateImages)
        if (!focusedSomething && mode == Mode.BROWSE) {
            // Rows answer in any order: give slower ones above a moment, then start at the top row, not the quickest.
            main.removeCallbacks(focusTop)
            main.postDelayed(focusTop, 400)
        }
        // A page opened by resting on its name: the remote stays in the top bar and the top row's first title shows
        // above as soon as the top rows are in (they answer within a few tens of ms of each other).
        if (header.hasFocus()) { main.removeCallbacks(heroTop); main.postDelayed(heroTop, 150) }
        main.post(::updateNextRow)
    }

    private val nextRowCheck = Runnable { updateNextRow() }

    private fun updateNextRow() {
        if (!page.isBrowse) return
        val visible = rows.filter { it.view.isAttachedToWindow }.sortedBy { offsetIn(it.view) }
        // No row in use (the remote is on the chips or the top bar): the row sitting in the band counts.
        val active = visible.indexOfFirst { it.active }.takeIf { it >= 0 }
            ?: visible.indexOfFirst { offsetIn(it.view) >= scroller.scrollY - dp(2) }.coerceAtLeast(0)
        // Rows below the active one keep their names hidden: one peeking in at the bottom sat under the caption.
        // The hint on the right names the next row instead.
        visible.forEachIndexed { i, row -> row.peek(i <= active + 1) } // ChadFlix: the next row's title peeks in, dimmed
        val next = visible.getOrNull(active + 1)
        nextRowHint.text = next?.let { "⌄  ${HomeStyle.rowName(this, it.title)}" } ?: ""
        if (page.polishedRail) next?.let { it.items.getOrNull(it.selected)?.let(::warm) }
        nextRowHint.visibility = View.GONE // ChadFlix: the next row shows itself
        nextRowHint.post(::clearHint)
    }

    private val focusTop = Runnable {
        if (focusedSomething || mode != Mode.BROWSE) return@Runnable
        focusedSomething = true
        if (header.hasFocus() && !jump) return@Runnable
        jump = false
        firstRow()?.focusFirst()
    }

    private val heroTop = Runnable {
        val first = firstRow()?.takeIf { header.hasFocus() && mode == Mode.BROWSE } ?: return@Runnable
        // ChadFlix: placed under its card before it shows, as Down will place it (a Showing tile row starts at card 1);
        // it used to show at translationX 0, under the tile, then jump one card right on Down.
        first.items[first.selected].takeIf { it !== heroItem }?.let { first.pin(first.selected, snap = true); fill(it); showHero(it) }
    }

    /** The row nearest the top of the page: where Up goes back to the cinema from. */
    private fun firstRow(): Row? = rows.filter { it.view.isAttachedToWindow }.minByOrNull { offsetIn(it.view) }

    private fun measureFirstRow(g: Int, opened: Long) {
        val decor = window.decorView
        val observer = decor.viewTreeObserver
        rowDrawListener?.let(observer::removeOnPreDrawListener)
        val label = page.label
        val bounds = Rect()
        val listener = object : ViewTreeObserver.OnPreDrawListener {
            override fun onPreDraw(): Boolean {
                if (scroller.alpha <= 0f || !rows.any { row ->
                        row.view.isShown && row.view.alpha > 0f &&
                            row.cards.firstOrNull()?.getGlobalVisibleRect(bounds) == true
                    }) return true
                decor.viewTreeObserver.removeOnPreDrawListener(this)
                rowDrawListener = null
                decor.postOnAnimation {
                    if (g == generation && !isDestroyed) {
                        android.util.Log.d("Alder", "page $label: first row visible in ${android.os.SystemClock.uptimeMillis() - opened} ms")
                        if (g == 1) reportFullyDrawn()
                    }
                }
                return true
            }
        }
        rowDrawListener = listener
        observer.addOnPreDrawListener(listener)
    }

    /**
     * One row of wide art cards along the bottom of the screen; only the focused row shows, the art fills the rest.
     * Left and right are handled here rather than by Android's focus search, so the row slides in one smooth motion,
     * one card per press, and its ends stop the remote.
     */
    /**
     * A row of cards. With [more], it asks for the titles after the ones it has when the remote gets near its end (a
     * catalog's next page, or the rest of a long library), and stops once an answer brings nothing new. A [wide] row
     * (Continue Watching) shows 16:9 art with the name, time left and progress on the card, two poster columns wide, and
     * ends on the same foot line as the poster rows.
     */
    private inner class Row(val title: String, first: List<Item>, private var more: ((Int) -> List<Item>)?, val wide: Boolean = false,
                            val grid: Boolean = false, val continueRow: Boolean = false) {
        private val cardWidth = if (grid) listPosterWidth else dp(if (wide) WIDE_W else CARD_W)
        private val cardHeight = if (grid) listPosterWidth * 3 / 2 else dp(if (wide) WIDE_H else CARD_H)
        val homeRow = !wide && !grid // ChadFlix: Netflix's row: posters, the focused one widened, pinned left
        private val polishedRail = page.polishedRail
        private val gap = if (grid) LIST_GAP else HomeStyle.CARD_GAP
        val cardStep = cardWidth + dp(gap)
        val items = first.toMutableList()
        private var fetching = false
        var pendingUpdate: (() -> Unit)? = null
        var active = false
        var loaded = false

        private val inset = if (grid) (artW - cardWidth * LIST_COLUMNS - dp(gap) * (LIST_COLUMNS - 1)) / 2 else dp(if (homeRow) HomeStyle.INSET else PAD)
        val rail = HorizontalCardRail(
            screen = this@MainActivity,
            cardGapPx = dp(gap),
            paddingStartPx = inset,
            paddingTopPx = dp(if (grid) LIST_TOP_ROOM else ROW_TOP_ROOM),
            paddingEndPx = inset,
            paddingBottomPx = if (grid) dp(GRID_ROW_BOTTOM) else rowBottom,
            wideMotion = wide,
            rightFadePx = if (polishedRail) dp(30) else 0,
            cullLeft = !homeRow, // the card before the focused one peeks in at the left edge
        ).apply {
            onScroll = {
                if (scroller.hasFocus()) placeCaption()
                loadRest(this@Row)
            }
            onHoldEnd = { sel ->
                if (!grid && !polishedRail) items.getOrNull(sel)?.let(::showArt)
                // ChadFlix: racing along a row passes the cards as posters; the one the remote stops on widens.
                if (homeRow) settle()
            }
        }
        val cards: MutableList<MediaCardView> get() = rail.cards
        val scroller: HorizontalScrollView get() = rail.scroller
        val selected: Int get() = rail.selected

        // The row the remote is in has a bright title; the next one, peeking in below, is a quiet hint.
        // One line, always: the row band is measured for one, and a second would push the cards off the screen.
        // ChadFlix: a genre row's title names its list, "Action · Trending".
        private val list = if (homeRow && (page.hasGenreShortcuts || page == Page.SERVICES || page == Page.HOME)) HomeStyle.rowList(this@MainActivity, page.label, page == Page.MOVIES, title) else null
        private val shownTitle = HomeStyle.titleCase(HomeStyle.rowName(this@MainActivity, title)) // All Genres in the app's language
        private val quietTitle = list?.let { HomeStyle.rowTitle(shownTitle, it, lit = false) }
        private val litTitle = list?.let { HomeStyle.rowTitle(shownTitle, it, lit = true) }
        private val heading = text(shownTitle, HomeStyle.ROW_TITLE, bold = true, color = HomeStyle.WHITE).onArt().apply {
            quietTitle?.let { text = it }
            setPadding(inset, 0, dp(PAD), 0)
            setSingleLine()
            ellipsize = TextUtils.TruncateAt.END
            maxWidth = dp(PAD + HERO_W) // a long "Because you watched…" stops at the hero's width
            alpha = QUIET
            if (grid) visibility = View.GONE
        }
        val view: View = column(Gravity.START, heading, scroller).apply { if (wide) setPadding(0, dp(CARD_H - WIDE_H), 0, 0) }

        // ChadFlix: a genre row starts with its Showing tile: Up and Down land on the first title after it, and while the
        // remote is on the tile or that first title the row doesn't scroll, so the tile stays in view.
        private val tileRow = homeRow && first.firstOrNull()?.id?.let(HomeStyle::isTile) == true
        private var tileLook: ((Int) -> Unit)? = null
        private fun pinOf(i: Int) = if (tileRow && i <= 1) 0 else i
        private fun styleTile() = tileLook?.invoke(when { cards.firstOrNull()?.isFocused == true -> 2; active -> 1; else -> 0 })

        init {
            items.forEachIndexed { i, item -> rail.appendCard(makeCard(item, i)) }
            if (tileRow && cards.size > 1) rail.selectIndex(1)
        }

        private fun makeCard(item: Item, index: Int) = MediaCardView(
            screen = this@MainActivity,
            titleName = item.name,
            posterUrl = item.poster,
            artUrl = item.art,
            widthPx = cardWidth,
            heightPx = cardHeight,
            wide = wide,
            grid = grid,
            dimAlpha = if (grid) GRID_DIM else HomeStyle.ROW_DIM,
            progress = item.progress,
            finished = item.finished,
            newEpisodes = item.newEpisodeCount,
            live = item.live,
            settled = item.settled,
            liftShadow = !page.hasGenreShortcuts,
            home = homeRow,
            wideW = if (homeRow && !HomeStyle.isTile(item.id)) dp(HomeStyle.WIDE_W) else 0,
        ).apply {
            logoUrl = item.logo
            if (HomeStyle.isTile(item.id)) tileLook = HomeStyle.dressTile(this@MainActivity, this, getString(R.string.showing), list.orEmpty())
            dim(grid || !active, animate = false)
            payload = item
            tag = "card:${item.id}"
            onRequestMeta = { fill(item) }
            item.onFill += {
                setNewEpisodes(item.newEpisodeCount)
                logoUrl = item.logo
                refresh(item.poster, item.art, item.settled)
                rate(this, item, fade = true) // its score came with its details (focused card only)
                if (homeRow && active && cards.getOrNull(selected) === this) pin(selected) // widened by a late backdrop: the row's end allows for it
            }
            // ChadFlix: no length saved and the details weren't in yet: the runtime from them gives the bar.
            if (continueRow && item.progress == 0f && item.resumeAt > 0) {
                item.onFill += {
                    val ms = HomeStyle.runtimeMs(metas[item.id]?.runtime)
                    if (ms > item.resumeAt) setProgress(item.resumeAt.toFloat() / ms)
                }
                fill(item)
            }
            setOnFocusChanged { has ->
                if (has) {
                    focus(index)
                    showHero(item)
                    if (homeRow && !held) fetchTitleSoon(item)
                } else if (titleWanted === item) fetchTitleSoon(null)
                if (!has) main.post { if (!view.hasFocus()) pendingUpdate?.let { pendingUpdate = null; it() } }
                styleTile()
            }
            // A channel has no title page to show: OK plays it, and holding OK lists its sources.
            setOnClickListener { if (HomeStyle.isTile(item.id)) showingMenu(this@Row) else if (item.live) play(item) else openDetails(item) }
            if (grid && page == Page.LIBRARY) setOnLongClickListener { listMenu(item); true }
            else if (continueRow) setOnLongClickListener { continueMenu(item); true }
            else setOnLongClickListener { rowMenu(this@Row, item); true }
        }

        fun peek(show: Boolean) { if (!grid) heading.visibility = if (show) View.VISIBLE else View.INVISIBLE } // a grid row has no name

        /** In or out of this row: its title brightens or quiets, its cards come forward or settle back. */
        fun light(on: Boolean) {
            if (active == on) return
            active = on
            if (on) peek(true) else {
                rail.clearHeld()
                hideBadge()
                cards.getOrNull(selected)?.expand(false, animate = false) // leaving the row: a poster again at once
                if (tileRow && selected == 0 && cards.size > 1) rail.selectIndex(1) // back in, the remote lands on a title
                if (homeRow) rail.scrollToCard(selected, 0, pinOf(selected), snap = true) // and it stays put, near its end too
            }
            styleTile()
            heading.animate().alpha(if (on) 1f else QUIET).setDuration(FOCUS_MS).start()
            (if (on) litTitle else quietTitle)?.let { heading.text = it }
            cards.forEachIndexed { i, c -> c.dim(!on || (grid && i != selected)) } // ChadFlix: the whole row lights up
        }

        fun focusFirst() = rail.focusSelected()

        private var badge: LinearLayout? = null // ChadFlix: the row's one rating badge, on its widened card

        /**
         * ChadFlix: the widened card's rating in its bottom-right corner (its foot is shaded already), from what's known
         * without asking anyone: IMDb (also for "Both"), or Letterboxd from this session's MDBList answers. Nothing until
         * it's known. One badge per row moves to the card the remote is on; other cards have none.
         */
        fun rate(card: MediaCardView, item: Item, fade: Boolean) {
            if (!homeRow || HomeStyle.isTile(item.id) || !active || cards.getOrNull(selected) !== card || card.widensBy <= 0) return
            val (mark, score) = if (ratingPick == "letterboxd") R.drawable.ic_rating_letterboxd to knownLetterboxd(item.id)
                else R.drawable.ic_rating_imdb to (metas[item.id]?.rating ?: item.facts.firstOrNull { it.startsWith("IMDb") }?.removePrefix("IMDb "))
            if (score.isNullOrBlank()) return hideBadge()
            val b = badge ?: HomeStyle.badge(this@MainActivity).also { badge = it }
            if (b.parent !== card) {
                (b.parent as? android.view.ViewGroup)?.removeView(b)
                card.addView(b, FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM or Gravity.END).apply { rightMargin = dp(8) })
            }
            // Above Continue Watching's bar (6-10 dp up) whenever it's there, even if it came after the badge.
            (b.layoutParams as FrameLayout.LayoutParams).let { lp ->
                val bottom = dp(if (card.hasBar) 16 else 8)
                if (lp.bottomMargin != bottom) { lp.bottomMargin = bottom; b.layoutParams = lp }
            }
            (b.getChildAt(0) as ImageView).setImageResource(mark)
            (b.getChildAt(1) as TextView).text = score
            b.animate().cancel()
            if (fade) { b.alpha = 0f; b.animate().alpha(1f).setDuration(FOCUS_MS).start() } else b.alpha = 1f
        }

        fun hideBadge() { badge?.let { (it.parent as? android.view.ViewGroup)?.removeView(it) } }

        /** ChadFlix: scrolls to card [index], and the info line starts under it (after the Showing tile, further right). */
        fun pin(index: Int, snap: Boolean = false): Int {
            val target = rail.scrollToCard(index, if (homeRow) cards[index].widensBy else 0, pinOf(index), snap)
            // Kept on screen: near a row's end the card sits far right, and the info box is wider than what's left.
            // The set width, not the measured one: right after a page switch the box may still measure at the old page's width.
            val info = browseInfo.layoutParams as FrameLayout.LayoutParams
            val room = artW - (info.width.takeIf { it > 0 } ?: browseInfo.width) - info.leftMargin - dp(HomeStyle.INSET)
            browseInfo.translationX = if (homeRow) (index * cardStep - target).coerceAtMost(room.coerceAtLeast(0)).toFloat() else 0f
            return target
        }

        /** ChadFlix: a hold ended here: the card the remote stopped on widens (at once after Up/Down, sliding after Left/Right). */
        fun settle(animate: Boolean = true) {
            if (!homeRow) return
            cards.getOrNull(selected)?.expand(true, animate) ?: return
            items.getOrNull(selected)?.let { rate(cards[selected], it, fade = false) }
            pin(selected, snap = !animate)
            if (cards[selected].isFocused) fetchTitleSoon(items.getOrNull(selected))
            prefetchNeighbours(this)
        }

        /** ChadFlix: the card the remote lands on coming into this row from above or below. */
        val landing: Item? get() = items.getOrNull(selected)

        /**
         * The remote reached card [index]: slide it to the row's start, where the eye already is. Near the end the row
         * stops, so the last card rests at the right margin instead of leaving a hole. Cards that slid into the left
         * margin hide: everything lines up on the margin, with no sliver at the screen's edge. On the right, the next
         * card runs off the screen on purpose, to say there's more.
         */
        fun focus(index: Int) {
            val previous = selected
            val entering = !active // ChadFlix: from another row or the top bar: the card is wide at once, no slide
            rail.selectIndex(index)
            main.post { loadMoreSections?.invoke() }
            if (active) {
                if (previous != index && grid) { cards[previous].dim(true); cards[index].dim(false) }
            } else light(true)
            if (homeRow) { // ChadFlix: the old card goes back to a poster, the new one widens; its neighbours' backdrops come ahead
                if (previous != index) cards.getOrNull(previous)?.expand(false)
                if (!held) cards[index].expand(true, animate = !entering)
                hideBadge()
                rate(cards[index], items[index], fade = false)
                if (!held) listOf(index - 1, index + 1).mapNotNull { items.getOrNull(it)?.art }.forEach { Images.prefetch(it, dp(HomeStyle.WIDE_W), wide = true) }
            }
            if (!held && entering) prefetchNeighbours(this) // ChadFlix: also beside rows that never widen
            if (page.isBrowse) updateNextRow()
            if (page.isCatalog) markChip(title)
            if (!grid) fill(items[index])
            cards[index].load()
            if (index >= items.size - AHEAD) fetchMore()
            // Racing past, the cards beside aren't where the remote will stop: fetching their art would only queue
            // full-screen downloads and decodes that compete with the row's own pictures.
            if (!held && !grid) listOf(index - 1, index + 1).mapNotNull { items.getOrNull(it) }.forEach(::warm)
            if (grid) {
                cards[index].show(true)
                moreHint.alpha = 0f
                return
            }
            val targetScroll = pin(index, snap = entering)
            loadRest(this@Row)
            val beyond = rail.hasCardsBeyond(targetScroll, dp(PAD) / 2)
            moreHint.animate().alpha(if (beyond && mode == Mode.BROWSE && !page.isBrowse) 0.9f else 0f).setDuration(FOCUS_MS).start()
            moreHint.translationY = if (wide) dp(CARD_H - WIDE_H + WIDE_H / 2 - CARD_H / 2).toFloat() else 0f
            if (!grid && wideRow != wide) {
                wideRow = wide
                browseInfo.layoutParams = (browseInfo.layoutParams as FrameLayout.LayoutParams).apply {
                    bottomMargin = heroBottom // ChadFlix: wide cards end on the posters' foot line, so the info line stays put
                }
            }
        }

        private fun fetchMore() {
            val more = more ?: return
            if (fetching) return
            fetching = true
            val from = items.size
            background({ more(from) }) { result ->
                fetching = false
                val known = items.map { it.id }.toSet()
                val fresh = result.getOrElse { return@background }.filter { it.id !in known } // a failed page can be asked again
                if (fresh.isEmpty()) { this.more = null; return@background }
                for (item in fresh) {
                    val card = makeCard(item, items.size)
                    items += item
                    rail.appendCard(card)
                    card.show(homeRow) // shows once focus moves near, like the cards past the right margin (home rows don't cull)
                }
                if (loaded) loadRest(this@Row)
                if (active && !grid) rail.strip.post {
                    if (active) {
                        placeCaption()
                        val beyond = rail.hasCardsBeyond(scroller.scrollX, dp(PAD) / 2)
                        moreHint.animate().alpha(if (beyond && mode == Mode.BROWSE && !page.isBrowse) 0.9f else 0f).setDuration(FOCUS_MS).start()
                    }
                }
            }
        }
    }

    // ---------- items ----------

    /** Art, logo and facts the catalog didn't send, fetched once per title from the meta addon. */
    private fun fill(item: Item) {
        if (item.filled || HomeStyle.isTile(item.id)) return // ChadFlix: the Showing tile has no details to fetch
        item.filled = true
        if (!item.resume || !isMetaExpired(item.type, metaFetchedAt[item.id], META_REFRESH_MS)) {
            metas[item.id]?.let { applyMeta(item, it); return }
        }
        val key = profile.authKey
        metaPool.execute {
            // Last time's details from disk when fresh; the network for unseen titles or library series past TTL.
            val ttl = if (item.resume && item.type != "movie") META_REFRESH_MS else null
            val saved by lazy { Stremio.savedAddons(key).orEmpty() }
            val remote by lazy { runCatching { Stremio.addons(key) } }
            val resolved = resolveMetaWithOfflineFallback(
                ttl = ttl,
                readSaved = { age -> saved.savedMeta(item.type, item.id, age) },
                fetchRemote = { remote.getOrThrow().meta(item.type, item.id) },
            )
            main.post {
                if (isDestroyed) return@post
                if (resolved == null) return@post metas[item.id]?.let { applyMeta(item, it) } ?: settle(item)
                cacheMeta(item.id, resolved.meta, preservedMetaTimestamp(resolved.fresh, metaFetchedAt[item.id]))
                applyMeta(item, resolved.meta)
            }
        }
    }

    private fun applyMeta(item: Item, m: Meta) {
        if (item.art == null) item.art = m.background
        if (item.logo == null) item.logo = m.logo
        if (item.about.isEmpty()) item.about = m.description ?: ""
        if (item.facts.isEmpty() || item.resume) item.facts = facts(m)
        if (item.resume) item.newEpisodeCount = newEpisodeCount(item.id, m)
        // Home's first title (the first card the remote lands on): next launch starts on its art and logo.
        if (cinema.firstOrNull() === item && item.art != null) getSharedPreferences("home", MODE_PRIVATE).edit()
            .putString("art:${profile.id}", item.art).putString("logo:${profile.id}", item.logo).apply()
        settle(item)
    }

    /** [item] has all it will get: cards, the hero and the cinema waiting on it go ahead. */
    private fun settle(item: Item) {
        item.settled = true
        item.onFill.forEach { it() }
        if (heroItem === item && mode == Mode.BROWSE) showHero(item)
        if (mode == Mode.CINEMA && cinema.getOrNull(cinemaAt) === item) showCinema(cinemaAt)
    }

    private fun facts(m: Meta) = listOfNotNull(
        m.genres.take(2).joinToString(", ").ifEmpty { null }, m.year, m.rating?.let { "IMDb $it" },
    )

    // A channel's square or wide picture is its logo, and stands in for the title's logo above the cards.
    private fun metaItem(m: Meta) = Item(
        m.id, m.type, m.name, m.poster, m.background,
        m.logo ?: Images.logoFor(m.id) ?: m.poster?.takeIf { isLiveType(m.type) && m.posterShape in listOf("square", "landscape") },
        m.description ?: "", facts(m),
        null, 0f, resume = false,
    )

    private data class HomeLibrary(
        val list: List<JSONObject>,
        val continuing: List<JSONObject> = emptyList(),
        val metadata: Map<String, MetaResolution> = emptyMap(),
    )

    private fun prepareLibrary(
        list: List<JSONObject>,
        known: Map<String, Meta>,
        knownFetchedAt: Map<String, Long>,
        key: String,
        grid: Boolean,
    ): HomeLibrary {
        if (grid) return HomeLibrary(list)
        val addons by lazy { Stremio.savedAddons(key).orEmpty() }
        val remote by lazy { runCatching { Stremio.addons(key) } }
        val found = mutableMapOf<String, MetaResolution>()
        val continuing = Library.continueWatching(list) { item, videoId ->
            val id = item.optString("_id")
            val type = item.optString("type")
            val ttl = if (type != "movie") META_REFRESH_MS else null
            val freshKnown = known[id]?.takeIf { !isMetaExpired(type, knownFetchedAt[id], META_REFRESH_MS) }
            val meta = freshKnown ?: found[id]?.meta ?: resolveMetaWithOfflineFallback(
                ttl = ttl,
                readSaved = { age -> addons.savedMeta(type, id, age) },
                fetchRemote = { remote.getOrThrow().meta(type, id) },
            )?.also { found[id] = it }?.meta ?: known[id]
            val video = meta?.videos?.firstOrNull { it.id == videoId }
            video != null && !isEpisodeAired(video.released)
        }
        return HomeLibrary(list, continuing, found)
    }

    private fun libraryItem(item: JSONObject): Item {
        val s = item.optJSONObject("state") ?: JSONObject()
        val videoId = Library.savedVideoId(item)
        val id = item.getString("_id")
        // ChadFlix: a player that saved no length (duration 0) leaves no bar or time left; the title's runtime stands in.
        val duration = s.optLong("duration").takeIf { it > 0 } ?: HomeStyle.runtimeMs(metas[id]?.runtime)
        val offset = Library.resumeAt(item, videoId)
        val pending = Library.nextEpisodePending(item)
        val progress = if (duration > 0) offset.toFloat() / duration else 0f
        val type = item.optString("type")
        return Item(
            id, type, item.text("name")?.takeIf { it.isNotBlank() } ?: getString(R.string.addons_unavailable), item.text("poster"),
            null, Images.logoFor(id), "", emptyList(), videoId, progress,
            resume = offset > 0 || library.any { it.optString("_id") == id },
            timeLeft = timeLeft(duration, offset), resumeAt = offset, nextEpisodePending = pending,
            finished = libraryFinished(item),
            newEpisodeCount = newEpisodeCount(id, metas[id], item),
        )
    }

    private fun newEpisodeCount(id: String, meta: Meta?, entry: JSONObject? = library.firstOrNull { it.optString("_id") == id }): Int {
        if (meta == null || entry == null) return 0
        return Library.newEpisodeCount(entry, meta.videos, ::isEpisodeAired)
    }

    /** Resume: straight to the sources for the episode Continue Watching points at; with [pick] (held OK) they're all listed. */
    private fun play(item: Item, pick: Boolean = false) {
        if (resolvingStreams != null) return
        val videoId = item.videoId ?: item.id
        val videos = metas[item.id]?.videos.orEmpty()
        val next = videos.getOrNull(videos.indexOfFirst { it.id == videoId } + 1)?.takeIf { videos.any { it.id == videoId } }
        val episode = episodeLabel(item)
        val intent = Intent(this, StreamsActivity::class.java)
            .putExtra("type", item.type).putExtra("id", item.id).putExtra("name", item.name).putExtra("poster", item.poster)
            .putExtra("videoId", videoId).putExtra("nextVideoId", next?.id).putExtra("pick", pick)
            .putExtra("background", metas[item.id]?.background ?: item.art)
            .putExtra("logo", metas[item.id]?.logo ?: item.logo)
            .putExtra("still", videos.firstOrNull { it.id == videoId }?.thumbnail)
            .putExtra("runtime", metas[item.id]?.runtime)
            .putExtra("episode", listOfNotNull(episode, episode(item)?.title?.takeIf { it.isNotBlank() }).joinToString(DOT).ifEmpty { null })
            .putExtra("label", listOfNotNull(item.name, episode).joinToString("  ·  "))
            .apply { if (item.resumeAt > 0L) putExtra("resumeAt", item.resumeAt) }

        if (pick || Settings.alwaysList(this)) {
            startActivity(intent)
            return
        }

        startActivity(Intent(this, PlayerActivity::class.java).putExtras(intent))
    }

    private fun openDetails(item: Item) {
        main.removeCallbacks(titleTask) // ChadFlix: the title page asks for its pictures itself now
        startActivity(Intent(this, DetailsActivity::class.java)
            .putExtra("type", item.type).putExtra("id", item.id).putExtra("name", item.name)
            .putExtra("poster", item.poster).putExtra("background", item.art).putExtra("videoId", item.videoId)
            .putExtra("logo", item.logo).putExtra("fromHome", true))
    }

    // ---------- vertical movement ----------

    /** Shows the focused row in the row band. */
    private fun snapTo(focused: View) {
        val rowView = rows.firstOrNull { isInside(focused, it.view) }?.view ?: return
        val top = offsetIn(rowView)
        val viewportHeight = scroller.height - if (page.isGrid) dp(SAFE_BOTTOM) else 0
        val target = if (page.isGrid) gridScrollTarget(scroller.scrollY, viewportHeight, top, rowView.height) else top.coerceAtLeast(0)
        if (page.isGrid) scroller.smoothScrollTo(0, target) else slideRows(target)
    }

    private var slideAnim: android.animation.ValueAnimator? = null
    private var slideTarget = -1

    /** ChadFlix: the info line fades back in once the rows have stopped. */
    private fun showInfo() {
        if (heroItem != null && mode == Mode.BROWSE && slideAnim == null)
            browseInfo.animate().alpha(1f).setDuration(INFO_IN_MS).setInterpolator(EASE_OUT).start()
    }

    /**
     * ChadFlix: the rows slide to [target] in [ROW_SLIDE_MS] (ScrollView's own smooth scroll took 250 ms). The info line
     * hides at once and fades in as soon as the slide ends, so the last title's words never sit over the incoming card.
     */
    private fun slideRows(target: Int) {
        if (slideAnim != null && target == slideTarget) return // already going there (Left/Right mid-slide)
        if (scroller.scrollY == target && slideAnim == null) return
        slideAnim.also { slideAnim = null }?.cancel()
        browseInfo.animate().cancel()
        browseInfo.alpha = 0f
        // Up/Down held: the rows keep up with the remote, no eased slide per repeat; the words come back on key-up.
        if (verticalHeld) return scroller.scrollTo(0, target)
        slideTarget = target
        slideAnim = android.animation.ValueAnimator.ofInt(scroller.scrollY, target).setDuration(ROW_SLIDE_MS).apply {
            interpolator = EASE_OUT
            addUpdateListener { scroller.scrollTo(0, it.animatedValue as Int) }
            addListener(object : android.animation.AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: android.animation.Animator) {
                    if (slideAnim !== animation) return
                    slideAnim = null
                    showInfo()
                }
            })
            start()
        }
    }

    private fun libraryPosterWidth() = (artW - dp(2 * PAD + LIST_GAP * (LIST_COLUMNS - 1))) / LIST_COLUMNS

    // ---------- memory: pictures only near the screen ----------

    private fun updateImages() {
        val top = scroller.scrollY
        // ChadFlix: home rows go by one row's height, not the taller scroller's.
        val height = if (!page.isGrid) browseRowBlock else scroller.height.takeIf { it > 0 } ?: resources.displayMetrics.heightPixels
        // Requests run newest first, so the bottom rows are asked for first and the top row last: it loads first.
        for (r in rows.filter { it.view.isAttachedToWindow }.sortedByDescending { offsetIn(it.view) }) {
            val y = offsetIn(r.view)
            val near = r.view.hasFocus() || y < top + height * 3 && y + r.view.height > top - height
            val far = !r.view.hasFocus() && (y > top + height * 5 || y + r.view.height < top - height * 3)
            // Only the row on screen and its neighbours (where the remote can go next) stay in the drawing. The rest,
            // hundreds of cards, were walked by the RenderThread every frame though none of them shows.
            r.view.visibility = if (r.view.hasFocus() || y < top + height * (if (page.isGrid) 3 else 2) && y + r.view.height > top - height) View.VISIBLE else View.INVISIBLE
            // An old row stays out of the title area after a genre jump; the focused row remains visible while it slides in.
            r.view.alpha = if (!r.view.hasFocus() && !page.isGrid && y + r.view.height <= top + dp(2)) 0f else 1f
            if (near && !r.loaded) {
                r.loaded = true
                loadRest(r)
            } else if (far && r.loaded) {
                r.loaded = false
                r.cards.forEach { it.unload() }
            }
        }
    }

    /** A row scrolled sideways: its cards near the screen load, and cards far away let pictures go. */
    private fun loadRest(r: Row) {
        if (!r.loaded) return
        val step = r.cardStep
        val w = if (r.scroller.width > 0) r.scroller.width else resources.displayMetrics.widthPixels
        val window = cardLoadWindow(r.scroller.scrollX, w, step, r.cards.size)
        for (i in 0 until window.farLeftEnd) {
            if (i != r.selected) r.cards[i].unload()
        }
        cardLoadOrder(window).forEach { r.cards[it].load() }
        for (i in window.farRightStart until r.cards.size) {
            if (i != r.selected) r.cards[i].unload()
        }
    }

    private fun offsetIn(v: View): Int {
        var y = 0
        var cur: View? = v
        while (cur != null && cur !== content) {
            y += cur.top
            cur = cur.parent as? View
        }
        return y
    }

    /**
     * "What's new" once after an update, on whichever profile opens Home first: never on a fresh install, and once per
     * version. Versions before this one kept no record, so an update is told from an install by the package's dates.
     */
    private fun whatsNewOnce() {
        // Alder stopped since the last Home visit: ask once to send the report, before any "What's new".
        if (Crashes.unseen(this)) return choose(getString(R.string.report_crashed_title),
            listOf(getString(R.string.report_send), getString(R.string.report_no)), getString(R.string.report_crashed_desc)) {
            if (it == 0) sendReport()
        }
        val prefs = getSharedPreferences("app", MODE_PRIVATE)
        val version = Updates.mine(this)
        val seen = prefs.getString("seenVersion", null)
        if (seen == version) return
        prefs.edit().putString("seenVersion", version).apply()
        val info = packageManager.getPackageInfo(packageName, 0)
        if (seen == null && info.firstInstallTime == info.lastUpdateTime) return // installed, not updated
        whatsNew(version, quiet = true)
    }

    // Signed out elsewhere (or the session expired): the profile stays, with its settings, and signs in again.
    private fun forgetIfSessionGone(error: Throwable) {
        if ((error as? Stremio.ApiException)?.code != Stremio.NO_SESSION || isFinishing) return
        Profiles.markSignedOut(this, profile.id)
        startActivity(Intent(this, ProfilesActivity::class.java).putExtra("signIn", profile.id)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
        finish()
    }

    private companion object {
        val cacheReads = java.util.concurrent.Executors.newSingleThreadExecutor()
        // Title details, newest request first, so the cards on screen get theirs before the ones scrolled past.
        val metaPool = Images.newestFirst(6)
        const val META_REFRESH_MS = 6 * 60 * 60 * 1000L // 6h: pick up new seasons/episodes for library series
        const val HEADER_H = 76 // dp
        const val WHATS_NEW_WAIT = 1_500L // ms after Home opens: its first row draws before the pop-up covers it
        // 2:3 posters, six and a bit across (56 + 7 × 120 + 6 × 16 = 992 of 960 dp): the seventh runs off the edge to
        // say there's more, like the TV apps the layout follows.
        const val CARD_W = HomeStyle.CARD_W
        const val CARD_H = HomeStyle.CARD_H
        // Continue Watching: 16:9, four across (4 × 200 + 3 × 16 = 848 dp, the row between the margins).
        const val WIDE_W = 200
        const val WIDE_H = WIDE_W * 9 / 16
        const val LIST_GAP = 12
        const val LIST_COLUMNS = 6
        const val LIST_TOP_ROOM = 8 // dp above a grid row for the focused poster's growth
        const val GRID_HERO_TOP = HEADER_H + 46
        const val GRID_TOP = GRID_HERO_TOP + 68
        const val GRID_ROW_BOTTOM = 20
        const val INFO_W = 360 // the cinema's line
        const val NAV_R = 18 // dp: the top bar's pills
        const val NAV_GAP = 10 // dp between them
        const val WARM_AFTER = 3000L // ms after launch before other pages' posters are fetched ahead
        const val SWITCH_WAIT = 300L // ms the remote rests on a page's name before it opens: passing over names opens nothing
        const val LOGO_W = 360 // the cinema's title logo
        const val LOGO_H = 100
        const val ROW_TOP_ROOM = 10 // dp between the row title box and its cards (was 2: the title nearly touched the focus border); ROWS_TOP and INFO_GAP give the 8 back, so the cards, info line and peek stay put
        const val SAFE_BOTTOM = 28
        const val CAPTION_BOTTOM = 16 // dp: low enough to leave the focused card a clear gap
        const val HOME_SYNOPSIS_W = HERO_W
        const val HOME_HERO_WAIT = 150L
        const val GENRE_LINE = -2 // ChadFlix: open a Showing menu on its Genre line
        const val ROW_SLIDE_MS = 200L // ChadFlix: Up/Down between rows
        const val INFO_IN_MS = 150L // ChadFlix: the info line, once the rows stop
        const val INFO_STEP_MS = 120L // ChadFlix: the info line, along a row
        const val HOME_ART_EXIT_MS = 100L
        const val HOME_ART_MS = 220L
        const val TITLE_AHEAD_MS = 400L // ChadFlix: resting on a home card this long fetches its title page's pictures
        const val QUIET = 0.75f // the next row remains visible without competing with the active one
        const val HERO_GAP = SECTION_GAP // dp between the focused title's block and the row heading below it
        const val RISE = 200 // dp the rows travel when they come up from the cinema
        // Cards a row starts with (a screenful and a bit), then how many come at a time as the remote nears its end.
        // Building every row's 25 cards at once held the main thread for over a second on a page switch (about 3.6 ms
        // a card), so the first row's posters, already decoded, didn't show until every row was made.
        const val FOCUS_ROOM = 8 // dp around the title block for a focused pill to grow into
        const val START = ROW_START_CARDS
        const val ROW_LATER = 350L // ms after a page opens before its rows out of sight are made
        const val ROW_STAGGER = 60L // ms between them
        const val PAGE = 12
        const val AHEAD = 8 // cards from a row's end when the next page is asked for: early enough that a row of 9 grows before it would stop short of the margin
        const val FIRST = 10 // cards per row loaded before it's scrolled sideways (a screenful, the one past the edge, one more)
        const val CINEMA_MAX = 8
        const val IDLE = 5 * 60_000L // ms without a key before the cinema takes over (the TV's own screensaver: 10 min)
        const val DIM_AFTER = 10_000L
        const val ANIMATION = 16 // TMDB's Animation genre: every anime has it, so the anime genre list leaves it out
        const val GENRE_ROWS = 6 // genre rows (and chips) at most on Shows and Movies
        const val CATALOG_WAIT = 6_000
        const val ADVANCE = 20_000L // ms each cinema title shows
        const val REVEAL_WAIT = 1200L // ms the cinema waits for a title's art and logo before showing its words anyway
    }
}

/** A catalog type's tab name: Anime in the viewer's language, any other type as the add-on writes it. */
internal fun typeTabName(c: Context, type: String) =
    if (type == "anime") c.getString(R.string.tab_anime) else type.replaceFirstChar { it.uppercase() }

/**
 * The tabs after Home, in Alder's own order, for add-ons that list catalogs of these [types]: Discover, Shows and Movies always,
 * Live for channels, sport or events, one "type:<type>" per other type ([extraPageTypes]), then My List ("library").
 */
internal fun tabsFor(types: List<String>): List<String> = // ChadFlix: Movies, Shows, Services, My Lists first; the rest start hidden
    listOf("movies", "shows", "services", "library", "discover") + listOfNotNull("live".takeIf { types.any(::isLiveType) }) +
        extraPageTypes(types).map { "type:$it" }

/**
 * [available] tabs in the order the viewer [saved] in Settings › Tabs. One they never placed (a new add-on's) goes next
 * to the tab it follows in [available]; a saved one that's gone (its add-on removed) is left out.
 */
internal fun orderTabs(saved: List<String>, available: List<String>): List<String> {
    val out = saved.filter { it in available }.distinct().toMutableList()
    available.forEachIndexed { i, id ->
        if (id in out) return@forEachIndexed
        val after = available.take(i).lastOrNull { it in out }
        out.add(if (after == null) 0 else out.indexOf(after) + 1, id)
    }
    return out
}

/** Keep the saved places of add-on tabs that are unavailable in the current menu. */
internal fun saveTabOrder(saved: List<String>, reordered: List<String>): List<String> {
    val remaining = reordered.iterator()
    return saved.distinct().map { if (it in reordered) remaining.next() else it } + remaining.asSequence().toList()
}

/**
 * The catalog types that get a page of their own, in the add-ons' order: all but movie and series (Movies and Shows)
 * and the live ones (Live). A type is a word an add-on picks, so a new one gets a page without a code change.
 */
internal fun extraPageTypes(types: List<String>): List<String> =
    types.map { it.trim().lowercase() }.filter { it.isNotEmpty() && it != "movie" && it != "series" && !isLiveType(it) }.distinct()

internal fun inYear(titleYear: String?, year: Int): Boolean =
    titleYear?.take(4)?.toIntOrNull() == year

/** A title's [year] ("1999", "2019–2023") in [decade]: 2010 is 2010–2019, 0 is before 1980. No year is in none. */
internal fun inDecade(year: String?, decade: Int): Boolean {
    val y = year?.take(4)?.toIntOrNull() ?: return false
    return if (decade == 0) y < 1980 else y in decade..decade + 9
}

internal fun nextHeroMaxLines(top: Int, minTop: Int, maxLines: Int): Int? =
    (maxLines - 1).takeIf { top < minTop && maxLines > 1 }

/** Returns true when cached in-memory metadata should be refreshed against [ttlMs]. */
internal fun isMetaExpired(
    type: String,
    fetchedAtMs: Long?,
    ttlMs: Long,
    nowMs: Long = System.currentTimeMillis(),
): Boolean = type != "movie" && (fetchedAtMs == null || nowMs - fetchedAtMs >= ttlMs)

internal data class MetaResolution(val meta: Meta, val fresh: Boolean)

/** Returns [nowMs] for freshly loaded metadata, or preserves [previousFetchedAtMs] (defaulting to 0L) for expired offline fallback. */
internal fun preservedMetaTimestamp(
    fresh: Boolean,
    previousFetchedAtMs: Long?,
    nowMs: Long = System.currentTimeMillis(),
): Long = if (fresh) nowMs else previousFetchedAtMs ?: 0L

/** Reads fresh saved meta within [ttl], refreshes online if needed, and falls back to expired cache offline. */
internal fun resolveMetaWithOfflineFallback(
    ttl: Long?,
    readSaved: (Long?) -> Meta?,
    fetchRemote: () -> Meta?,
): MetaResolution? =
    readSaved(ttl)?.let { MetaResolution(it, fresh = true) }
        ?: runCatching { fetchRemote() }.getOrNull()?.let { MetaResolution(it, fresh = true) }
        ?: if (ttl != null) readSaved(null)?.let { MetaResolution(it, fresh = false) } else null
