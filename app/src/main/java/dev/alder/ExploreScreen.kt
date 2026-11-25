package dev.alder

import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.drawable.GradientDrawable
import android.text.TextUtils
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.widget.*
import java.util.Locale
import org.json.JSONArray
import org.json.JSONObject

/** Search's places at 1080p (dp, the owner's chosen Marquee mockup's CSS px ÷ 2, in ChadFlix's own sizes). */
internal object SearchLayout {
    // Voice and Keyboard are in the top bar (placement B), so the hero starts right under it: 48 dp (96 px) higher.
    const val ROW_TOP = 60 // while typing: the heading and results
    const val KEYS_TOP = 66 // while typing: the keys at the left
    const val SIDE = 36 // the hero's words, the controls row and the posters stop this far from the sides (72 px)
    const val COLUMNS = 9 // 92 dp posters
    const val HERO_H = 234 // the hero
    const val CONTROLS_TOP = 238 // the controls row, under the hero; the posters scroll under it
}

/**
 * Search's catalogue (the owner's Marquee): the focused title as a hero across the top, the controls row (Movies |
 * Shows, the filters, Clear All Filters and Sort) under it, then the posters, which scroll while the hero and the row
 * stay put. The filters' pickers open in the hero's place, the posters below changing live.
 */
abstract class ExploreScreen : Screen() {
    /** True while the catalogue's own arrangement shows (the keys closed); false while typing (keys left, results right). */
    protected var wide = false
    private var gridWide: Boolean? = null // the [wide] the grid on screen was built for
    private var gridColumns = COLUMNS
    private var gridStep = 0 // px from one poster row's top to the next's
    protected abstract val results: LinearLayout
    protected abstract val resultsScroll: ScrollView
    protected abstract fun lookup(facet: String?, restoreTitle: Boolean = true)
    protected abstract fun refreshCatalogue()
    private var query = ExploreQuery()
    protected lateinit var peopleButton: View
    private lateinit var filterBar: View
    private lateinit var barRow: LinearLayout // the bar's pills, inside [filterBar]
    private lateinit var headingControls: View // Clear All Filters and Sort
    private lateinit var infoPanel: View
    private var unseen: Meta? = null // focused while the hero was hidden (typing): shown once it's back
    protected lateinit var sortControl: TextView
    private var downWhileLoading = false
    private var firstPageLoading = false
    private class Row(val view: LinearLayout, val key: TextView, val value: TextView, val chevron: ImageView)
    private val rows = linkedMapOf<Facet, Row>()
    private lateinit var clearFilters: TextView
    private val typeButtons = mutableListOf<TextView>()
    private var genreNames = emptyMap<Int,String>()
    private var loadedBand = -1
    @Volatile private var generation = 0
    private val catalogueCards = mutableListOf<MediaCardView>()
    private val popularTitles = mutableMapOf<Boolean, List<JSONObject>>() // the unfiltered catalogue's first page, by movie
    @Volatile private var shown: Meta? = null // the info panel's title; read off the main thread by its rating lookups
    private var pending: Runnable? = null // the info panel's lookups, after a rest on the poster
    private lateinit var infoBody: View
    private lateinit var infoArt: ImageView
    private lateinit var infoName: TextView
    private lateinit var infoLogo: ImageView
    private lateinit var infoCert: TextView
    private lateinit var infoFacts: TextView
    private lateinit var infoGenres: TextView
    private lateinit var infoScores: LinearLayout
    private lateinit var infoOverview: TextView
    private lateinit var infoFaces: LinearLayout // the top-billed cast's faces
    // The hero, and the picker that takes its place.
    private lateinit var heroFull: View
    private lateinit var pickerHost: FrameLayout
    private var pickerFacet: Facet? = null // the facet whose picker has the hero
    private var pickerBack: (() -> Unit)? = null
    private var pickedRole: String? = null // the role chosen in the hero's Directors & Cast, for the next face picked
    private var peopleToken = 0
    private var delayed = Runnable { refreshCatalogue() }
    private val medium by lazy { text("", SMALL).typeface }
    private val semiBold by lazy { text("", SMALL, bold = true).typeface }
    protected val heading: String get() {
        val sort = sortLabel()
        val type = getString(if (query.movie) R.string.tab_movies else R.string.tab_shows)
        return when {
            query.sort=="box" -> type + DOT + sort
            Locale.getDefault().language != "en" -> type + DOT + sort
            else -> "$sort $type"
        }
    }

    private fun title(f: Facet) = getString(if (f == Facet.STUDIO && !query.movie) R.string.ex_network else f.title)
    private fun value(f: Facet): String = when (f) {
        Facet.PEOPLE -> query.person?.name
        Facet.GENRE -> query.genres.joinToString(if (query.any) " ${getString(R.string.ex_or)} " else " + ") { it.label }.ifEmpty { null }
        else -> query.choices[f]?.label
    } ?: getString(R.string.filter_all)

    private fun sorts() = query.sortOptions().map { (key, words) -> Pick(key, HomeStyle.titleCase(getString(words))) }
    private fun sortLabel() = sorts().let { all -> (all.firstOrNull { it.key == query.sort } ?: all.first()).label }

    /**
     * The controls row's right end: Clear All Filters while any is set, then the sort drop-down, "Sort: Popular ⌄",
     * whose OK opens the reliable sorts under it. Down goes to the posters.
     */
    protected fun buildSortControl(): View {
        clearFilters = text(R.string.ex_clear_filters, SMALL, color = TEXT).apply {
            id = View.generateViewId()
            gravity = Gravity.CENTER
            includeFontPadding = false
            setPadding(dp(12),0,dp(12),0)
            isFocusable = true
            fun style(on: Boolean) {
                background = if (on) HomeStyle.pill(HomeStyle.WHITE) else outlined(0, GLASS_EDGE)
                setTextColor(if (on) HomeStyle.BLACK else TEXT)
            }
            style(false)
            setOnFocusChangeListener { _, has -> style(has) }
            setOnClickListener {
                sortControl.requestFocus() // this pill goes once nothing is set
                change(ExploreQuery(type=query.type, sort=query.sort))
            }
            setOnKeyListener(downToPosters)
        }
        sortControl = text("", SMALL).apply {
            id = View.generateViewId()
            isFocusable = true
            isSingleLine = true
            gravity = Gravity.CENTER
            includeFontPadding = false
            setPadding(dp(11),0,dp(11),0)
            setCompoundDrawablesRelative(null, null, getDrawable(R.drawable.ic_chevron_down)?.mutate()?.apply {
                setBounds(0, 0, dp(10), dp(10))
            }, null)
            compoundDrawablePadding = dp(6)
            nextFocusRightId = id
            setOnClickListener { sortPicker() }
            setOnFocusChangeListener { _, _ ->
                downWhileLoading = false
                styleSort()
            }
            setOnKeyListener(downToPosters)
        }
        clearFilters.nextFocusRightId = sortControl.id
        styleSort()
        headingControls = LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            addView(clearFilters, LinearLayout.LayoutParams(-2, -1).apply { marginEnd = dp(BAR_GAP) })
            addView(sortControl, LinearLayout.LayoutParams(-2, -1))
        }
        return headingControls
    }

    /** Down from the filter bar or Sort: the first row's poster under it (or the first, once the first page comes). */
    private val downToPosters = View.OnKeyListener { v, code, event ->
        if (code != KeyEvent.KEYCODE_DPAD_DOWN || event.action != KeyEvent.ACTION_DOWN) return@OnKeyListener false
        val middle = { view: View -> IntArray(2).also(view::getLocationOnScreen)[0] + view.width / 2 }
        catalogueCards.take(gridColumns).minByOrNull { kotlin.math.abs(middle(it) - middle(v)) }?.requestFocus()
            ?: firstPageLoading.also { downWhileLoading = it }
    }

    /** Where a list hangs under [v] (dp from the left and top): its start on [v]'s, or its end with [alignEnd]. */
    private fun under(v: View, widthDp: Int, alignEnd: Boolean = false): Pair<Int, Int> {
        val at = IntArray(2).also(v::getLocationOnScreen)
        val density = resources.displayMetrics.density
        val start = (if (alignEnd) (at[0] + v.width) / density - widthDp else at[0] / density).toInt()
        return start.coerceIn(EDGE, (resources.displayMetrics.widthPixels / density).toInt() - EDGE - widthDp) to
            ((at[1] + v.height) / density).toInt() + DROP
    }

    /** The sorts as a list hanging under the control, its right edge on the control's; a pick restarts the catalogue. */
    private fun sortPicker() {
        val sorts = sorts()
        val (x, y) = under(sortControl, PICKER_W, alignEnd = true)
        choose(getString(R.string.list_sort), sorts.map { it.label }, checked = sorts.indexOfFirst { it.key == query.sort },
            widthDp = PICKER_W, anchorStartDp = x, anchorTopDp = y) { applySort(sorts[it]) }
    }

    private fun applySort(pick: Pick) {
        if (query.sort == pick.key) return
        query = query.copy(sort = pick.key)
        refreshCatalogue()
    }

    private fun styleSort() {
        sortControl.text = getString(R.string.list_sort) + ": " + sortLabel()
        sortControl.pillState(sortControl.hasFocus(), selected = true)
    }

    /**
     * The controls row's filters: Movies | Shows, then a pill per filter in [Facet]'s order showing its value ("Genre
     * Horror + Comedy"). It fits its width ([fitBar]); only a language too long for even cut words scrolls sideways.
     */
    protected fun buildFilterBar(): View {
        val bar = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        val types = LinearLayout(this).apply {
            background = outlined(SEGMENT, GLASS_EDGE)
            setPadding(dp(3),dp(3),dp(3),dp(3))
        }
        listOf("movie" to R.string.tab_movies, "series" to R.string.tab_shows).forEach { (type, name) ->
            val b = text(name, HomeStyle.INFO).apply {
                gravity = Gravity.CENTER
                isFocusable = true
                includeFontPadding = false
                setPadding(dp(10),0,dp(10),0)
                setOnKeyListener(downToPosters)
                setOnFocusChangeListener { _, _ -> styleTypes() }
                setOnClickListener {
                    if (query.type != type) {
                        genreNames = emptyMap()
                        val validGenres = HomeStyle.genres(type=="movie").map { it.first.toString() }.toSet()
                        change(query.copy(type=type, genres=query.genres.filter { it.key in validGenres },
                            choices=query.choices - Facet.STUDIO, sort=if (query.sort=="box") "popular" else query.sort))
                    }
                }
            }
            typeButtons += b
            types.addView(b, LinearLayout.LayoutParams(-2,dp(BAR_H - 6)))
        }
        bar.addView(types, LinearLayout.LayoutParams(-2,dp(BAR_H)))
        Facet.values().forEach { f ->
            val key = text(title(f), HomeStyle.INFO).apply {
                isSingleLine = true
                includeFontPadding = false
                ellipsize = TextUtils.TruncateAt.END // only when the values alone can't make the bar fit ([fitBar])
            }
            val label = text("", HomeStyle.INFO, bold = true).apply {
                isSingleLine = true
                includeFontPadding = false
                ellipsize = TextUtils.TruncateAt.END
            }
            val chevron = ImageView(this).apply { setImageResource(R.drawable.ic_chevron_down) }
            val chip = LinearLayout(this).apply {
                id = View.generateViewId()
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(8),0,dp(8),0)
                isFocusable = true
                addView(key)
                addView(label, LinearLayout.LayoutParams(-2,-2).apply { marginStart = dp(4) })
                addView(chevron, LinearLayout.LayoutParams(dp(9),dp(9)).apply { marginStart = dp(4) })
                setOnFocusChangeListener { _, _ -> downWhileLoading = false; styleRow(f) }
                setOnClickListener { open(f) }
                setOnKeyListener(downToPosters)
            }
            bar.addView(chip, LinearLayout.LayoutParams(-2,dp(BAR_H)).apply { marginStart = dp(BAR_GAP) })
            rows[f] = Row(chip, key, label, chevron)
            if (f == Facet.PEOPLE) peopleButton = chip
        }
        barRow = bar
        filterBar = HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            addView(bar)
            addOnLayoutChangeListener { _, left, _, right, _, oldLeft, _, oldRight, _ ->
                if (right - left != oldRight - oldLeft) post { fitBar() }
            }
        }
        updatePanel()
        return filterBar
    }

    /**
     * The whole bar in its width: when the values don't fit, the longest are cut to one width (ending in "…"), never
     * shorter than [VALUE_MIN].
     */
    private fun fitBar() {
        val room = filterBar.width
        if (room == 0) return
        val values = rows.values.map { it.value }
        val keys = rows.values.map { it.key }
        (values + keys).forEach { it.maxWidth = Int.MAX_VALUE }
        val any = View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
        barRow.measure(any, any)
        val natural = values.map { it.measuredWidth }
        var cap = natural.maxOrNull() ?: return
        while (barRow.measuredWidth - natural.sumOf { it - minOf(it, cap) } > room && cap > dp(VALUE_MIN)) cap -= dp(2)
        values.forEach { it.maxWidth = cap }
        // Still too long (a long language beside Sort): the longest filter names are cut the same way
        // ("Directors & C…"), never shorter than [KEY_MIN].
        barRow.measure(any, any)
        if (barRow.measuredWidth <= room) return
        val names = keys.map { it.measuredWidth }
        var nameCap = names.maxOrNull() ?: return
        while (barRow.measuredWidth - names.sumOf { it - minOf(it, nameCap) } > room && nameCap > dp(KEY_MIN)) nameCap -= dp(2)
        keys.forEach { it.maxWidth = nameCap }
    }

    protected fun outlined(fill: Int, edge: Int = GLASS_EDGE) = GradientDrawable().apply { setColor(fill); cornerRadius = 999f; setStroke(dp(1), edge) }

    /** A filter pill: white while focused; a chosen value in tungsten on a warm tint; else a grey name and a white value. */
    private fun styleRow(f: Facet) {
        val r = rows[f] ?: return
        val on = r.view.isFocused
        val lit = active(f)
        r.view.background = when { on -> HomeStyle.pill(HomeStyle.WHITE); lit -> outlined(CHOSEN_FILL, CHOSEN_EDGE); else -> outlined(SEGMENT, GLASS_EDGE) }
        val name = when { on -> FOCUSED_VALUE; lit -> CHOSEN_NAME; else -> HomeStyle.LABEL_GREY }
        r.key.setTextColor(name)
        r.value.setTextColor(when { on -> HomeStyle.BLACK; lit -> TUNGSTEN; else -> HomeStyle.WHITE })
        r.chevron.imageTintList = ColorStateList.valueOf(name)
    }

    /** The controls row and the hero go with the catalogue; typed words, the keys and the people picker hide them. */
    protected fun showCatalogueControls(shown: Boolean) {
        filterBar.visibility = if (shown) View.VISIBLE else View.GONE
        headingControls.visibility = filterBar.visibility
        infoPanel.visibility = filterBar.visibility
        if (shown) unseen?.let(::showInfo) else closePicker(focusChip = false)
    }

    private fun styleTypes() = typeButtons.forEachIndexed { i, b -> b.pillState(b.isFocused, (i==0) == query.movie) }

    private fun updatePanel() {
        clearFilters.visibility = if (Facet.values().any(::active)) View.VISIBLE else View.GONE
        styleTypes()
        rows.forEach { (f, r) ->
            r.key.text = title(f)
            r.value.text = value(f)
            // People on Shows comes from the person's credits, which don't name a network.
            val enabled = query.movie || query.person == null || f != Facet.STUDIO
            r.view.isEnabled = enabled
            r.view.isFocusable = enabled
            r.view.alpha = if (enabled) 1f else .38f
            styleRow(f)
        }
        styleSort()
        filterBar.post { fitBar() }
    }

    private fun active(f: Facet) = query.without(f) != query &&
        !(query.person != null && !query.movie && f == Facet.STUDIO)

    private fun change(q: ExploreQuery) {
        query = q
        invalidateCatalogue()
        updatePanel()
        main.postDelayed(delayed, 300)
    }

    /** [f]'s list in the hero's place ([heroChoose]). */
    private fun chooseNear(f: Facet, options: List<String>, checked: Int = -1, focus: Int = maxOf(checked, 0), name: String = title(f),
                           back: (() -> Unit)? = null, right: ((Int) -> (() -> Unit)?)? = null, extra: View? = null,
                           stay: Boolean = false, checkedRows: Set<Int> = emptySet(), shown: (List<TextView>) -> Unit = {}, pick: (Int)->Unit) =
        heroChoose(f, name, options, checked, focus, checkedRows, right, back, extra, stay, shown, pick)

    private fun open(f: Facet) {
        when(f) {
            Facet.PEOPLE -> heroPeople()
            Facet.GENRE -> {
                if (genreNames.isNotEmpty()) return genrePicker(genreNames.toList())
                val media = query.media
                background({ Tmdb.genres(media) }) { answer ->
                    if (query.media == media && hasWindowFocus()) answer.fold({ list ->
                        genreNames = list.toMap()
                        genrePicker(list)
                    }, { toastFailure(R.string.search_unavailable) })
                }
            }
            Facet.YEAR -> years()
            else -> {
                val options = options(f)
                chooseNear(f, listOf(getString(R.string.filter_all)) + options.map { it.label },
                    options.indexOfFirst { it.key == query.choices[f]?.key }+1) { index ->
                    val option = options.getOrNull(index - 1)
                    change(if (option == null) query.without(f) else query.copy(choices = query.choices + (f to option)))
                }
            }
        }
    }

    /**
     * The Genre picker under its pill (A3): Match All | Any, then the genres with ticks. It stays open while genres
     * are ticked, changing its rows in place, and the results behind it update live.
     */
    private fun genrePicker(list: List<Pair<Int,String>>) {
        fun ticked(i: Int) = if (i == 0) query.genres.isEmpty() else query.genres.any { it.key == list[i - 1].first.toString() }
        val all = text(R.string.filter_all, SMALL).apply { gravity = Gravity.CENTER; setPadding(dp(11), 0, dp(11), 0) }
        val any = text(R.string.ex_any, SMALL).apply { gravity = Gravity.CENTER; setPadding(dp(11), 0, dp(11), 0) }
        var rows = emptyList<TextView>()
        fun refresh() {
            all.pillState(focused = false, selected = !query.any)
            any.pillState(focused = false, selected = query.any)
            rows.forEachIndexed { i, row -> row.tick(ticked(i)) }
        }
        val match = LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(8), dp(8), dp(8), dp(8))
            addView(text(R.string.ex_match, BODY), wrap().apply { marginEnd = dp(8) })
            addView(LinearLayout(this@ExploreScreen).apply {
                background = HomeStyle.pill(SEGMENT)
                setPadding(dp(2), dp(2), dp(2), dp(2))
                addView(all, LinearLayout.LayoutParams(-2, dp(22)))
                addView(any, LinearLayout.LayoutParams(-2, dp(22)))
            })
            rowFocus()
            setOnClickListener { change(query.copy(any = !query.any)); refresh() }
        }
        // Opens on the first ticked genre, so flipping a pair to Any or adding one is a few presses away.
        val names = listOf(getString(R.string.filter_all)) + list.map { it.second }
        chooseNear(Facet.GENRE, names, extra = match, stay = true,
            focus = names.indices.firstOrNull { it > 0 && ticked(it) } ?: 0, checkedRows = names.indices.filter(::ticked).toSet(),
            shown = { rows = it; refresh() }) { i ->
            val pick = list.getOrNull(i - 1)?.let { Pick(it.first.toString(), it.second) }
            change(when {
                pick == null -> query.without(Facet.GENRE)
                pick in query.genres -> query.copy(genres = query.genres - pick)
                else -> query.copy(genres = query.genres + pick)
            })
            refresh()
        }
    }

    /** The app's pill states for chips and segments: white while focused, a faint pill when chosen, else bare words. */
    private fun TextView.pillState(focused: Boolean, selected: Boolean) {
        background = when { focused -> HomeStyle.pill(HomeStyle.WHITE); selected -> HomeStyle.pill(HomeStyle.OPEN_TAB); else -> null }
        val color = when { focused -> HomeStyle.BLACK; selected -> HomeStyle.WHITE; else -> TEXT_2 }
        setTextColor(color)
        compoundDrawablesRelative[2]?.setTint(color)
        typeface = if (focused || selected) semiBold else medium
    }

    /** The Year picker; [at]: the row the remote starts on, when Back comes out of one of its lists. */
    private fun years(at: Int? = null) {
        val now = java.util.Calendar.getInstance().get(java.util.Calendar.YEAR)
        val decades = (now/10*10 downTo 1970 step 10).toList()
        fun set(low: Int?, high: Int, label: String) = change(query.copy(choices = query.choices +
            (Facet.YEAR to Pick("${low ?: ""}:$high",label))))
        // Back steps out one list at a time: a decade's years to its decades, To to From, From to the Year list.
        fun singles(name: String, decade: Int, low: Int = decade, back: () -> Unit, done: (Int) -> Unit) {
            val list = (minOf(now,decade+9) downTo maxOf(decade,low)).toList()
            chooseNear(Facet.YEAR,list.map(Int::toString),name = name,back = back) { done(list[it]) }
        }
        // Custom Range: decades first (OK takes the decade's first or last year, Right opens its years), not ~125 rows.
        fun yearFrom(name: String, low: Int, end: Boolean, back: () -> Unit, done: (Int) -> Unit) {
            val tens = (now/10*10 downTo low/10*10 step 10).toList()
            chooseNear(Facet.YEAR, tens.map { "${it}–${it+9}  ›" }, name = name, back = back,
                right = { i -> { singles(name, tens[i], low, back = { yearFrom(name, low, end, back, done) }, done) } }) { i ->
                done(if (end) minOf(now, tens[i]+9) else maxOf(low, tens[i]))
            }
        }
        fun range() {
            yearFrom(getString(R.string.ex_from), 1900, end = false, back = { years(decades.size+2) }) { a ->
                yearFrom(getString(R.string.ex_to), a, end = true, back = { range() }) { b -> set(a,b,"$a–$b") }
            }
        }
        // The choice in use is ticked, All too when there's none (as Genre's): one year ticks its decade, a range Custom Range.
        val chosen = query.choices[Facet.YEAR]?.key?.split(":")
        val checked = when {
            chosen == null -> 0
            chosen[0].isEmpty() -> decades.size+1
            else -> (decades.indexOfFirst { d -> chosen == listOf("$d","${d+9}") ||
                (chosen[0] == chosen[1] && chosen[0].toInt()/10*10 == d) } + 1).takeIf { it > 0 } ?: decades.size+2
        }
        chooseNear(Facet.YEAR,listOf(getString(R.string.filter_all)) + decades.map { "${it}–${it+9}  ›" } +
            listOf(getString(R.string.ex_before),getString(R.string.ex_range)),checked,focus = at ?: checked,
            right = { i -> decades.getOrNull(i-1)?.let { d -> { singles(title(Facet.YEAR), d, back = { years(i) }) { set(it,it,it.toString()) } } } }) { i ->
            when(i) {
                0 -> change(query.without(Facet.YEAR))
                decades.size+1 -> set(null,1969,getString(R.string.ex_before))
                decades.size+2 -> range()
                else -> decades[i-1].let { set(it,it+9,"$it–${it+9}") }
            }
        }
    }

    private fun options(f: Facet): List<Pick> = when(f) {
        // TMDb: 1632 is Lionsgate (The Hunger Games, John Wick), 35 the older "Lions Gate Films"; either matches.
        Facet.STUDIO -> if (query.movie) listOf(Pick("41077","A24"),Pick("420","Marvel Studios"),Pick("3","Pixar"),
            Pick("2","Walt Disney Pictures"),Pick("174","Warner Bros."),Pick("33","Universal Pictures"),Pick("4","Paramount Pictures"),
            Pick("521","DreamWorks Animation"),Pick("10342","Studio Ghibli"),Pick("1632|35","Lionsgate"),Pick("3172","Blumhouse"))
            else HomeStyle.platforms.map { Pick(it.networks,it.name) }
        Facet.LANGUAGE -> listOf("en","ko","ja","es","fr","hi","de","it","pt","ar","zh").map { Pick(it,Locale.forLanguageTag(it).displayLanguage) }
        else -> emptyList()
    }

    protected fun invalidateCatalogue() {
        downWhileLoading = false
        firstPageLoading = false
        generation++
        main.removeCallbacks(delayed)
        pending?.let(main::removeCallbacks)
        pending = null
        shown = null // a new catalogue's first title fills the hero afresh
    }

    /** A title's page or save couldn't be reached ([words] for a list that couldn't load: People, Genre). */
    private fun toastFailure(words: Int = R.string.ex_unavailable) = Toast.makeText(this,words,Toast.LENGTH_SHORT).show()

    protected fun clearGrid() {
        invalidateCatalogue()
        catalogueCards.forEach { it.unload() }
        catalogueCards.clear()
        loadedBand = -1
    }

    /** The grid's width: between the page's sides; while typing, the results area less the page's right margin. */
    private val gridWidth get() = if (wide) resources.displayMetrics.widthPixels - 2 * dp(SearchLayout.SIDE)
        else resultsScroll.layoutParams.width - dp(GRID_RIGHT)

    /** The catalogue was built for the other arrangement (the keys opened or closed over it): build it again. */
    protected fun gridOutOfDate() = gridWide != null && gridWide != wide

    protected fun catalogue() {
        firstPageLoading = true
        val g = generation
        val q = query
        updatePanel()
        val seen = mutableSetOf<String>()
        var next = 1
        var done = false
        var loading = false
        var failure: View? = null
        var row: LinearLayout? = null
        val wall = wide // this grid's arrangement, whatever happens to the page while its pages load
        gridWide = wall
        val cols = if (wall) SearchLayout.COLUMNS else COLUMNS
        gridColumns = cols
        val gap = dp(if (wall) WIDE_GAP else GRID_GAP)
        val rowGap = dp(if (wall) WIDE_GAP else GRID_ROW_GAP)
        val width = ((gridWidth - (cols - 1) * gap) / cols).coerceAtLeast(dp(48))
        gridStep = width * 3 / 2 + rowGap
        fun more() {
            if (loading || done || generation != g) return
            loading = true
            background({ ExploreCatalogue.page(q,next) }) { result ->
                if (generation != g) return@background
                loading = false
                if (next == 1) results.removeAllViews()
                val names = genreNames.ifEmpty { if (Locale.getDefault().language == "en") HomeStyle.genres(q.movie).toMap() else emptyMap() }
                result.fold({ page ->
                    failure?.let(results::removeView)
                    failure = null
                    if (next == 1 && q == ExploreQuery(type = q.type)) popularTitles[q.movie] = page.titles // for Popular Now
                    done = page.last
                    next++
                    val before = seen.size
                    page.titles.forEach { json ->
                        val meta = discoverMeta(json,"tmdb:${json.getInt("id")}",q.movie,names)
                        if (!seen.add(meta.id)) return@forEach
                        if (row == null || row!!.childCount == cols) {
                            // Home's ring sits just outside each card: the row lets it draw there.
                            row = LinearLayout(this).apply { clipChildren = false; clipToPadding = false }.also {
                                results.addView(it, LinearLayout.LayoutParams(-2,-2).apply {
                                    if (results.childCount == 0) topMargin = dp(RING_ROOM)
                                    bottomMargin = rowGap
                                })
                            }
                        }
                        val band = row!!
                        val column = band.childCount
                        val card = MediaCardView(this,meta.name,meta.poster,null,widthPx = width,heightPx = width*3/2,
                            dimAlpha = 0f,fadeIn = false,home = true)
                        val bandIndex = catalogueCards.size / cols
                        if (catalogueCards.isEmpty()) showInfo(meta) // until a poster has the remote: the first result
                        catalogueCards += card
                        // The first column keeps RING_ROOM/2 at its left, inside the scroller's clip, for the ring.
                        band.addView(card,LinearLayout.LayoutParams(width,width*3/2).apply { marginStart = if (column > 0) gap else dp(RING_ROOM / 2) })
                        card.setOnFocusChanged { has ->
                            if (has) {
                                showInfo(meta)
                                if (bandIndex != loadedBand) {
                                    val previous = loadedBand
                                    loadedBand = bandIndex
                                    if (previous >= 0) for (i in maxOf(0,previous-2)*cols until minOf(catalogueCards.size,(previous+3)*cols)) {
                                        if (kotlin.math.abs(i/cols-bandIndex)>2) catalogueCards[i].unload()
                                    }
                                    for (i in maxOf(0,bandIndex-2)*cols until minOf(catalogueCards.size,(bandIndex+3)*cols)) catalogueCards[i].load()
                                }
                                // The focused row to the top of the posters, every row the same step (rows are all one
                                // height, so it's counted); the hero and the controls row stay where they are.
                                if (!wall) band.post { if (card.hasFocus()) resultsScroll.scrollTo(0,(band.top - dp(RING_ROOM)).coerceAtLeast(0)) }
                                else resultsScroll.scrollTo(0, bandIndex * gridStep)
                                if (results.indexOfChild(band) >= results.childCount-2) more()
                            }
                        }
                        var resolving = false
                        fun resolve(save: Boolean) {
                            // One lookup at a time (a second OK opened the title page twice); the poster dims while
                            // the IMDb id is found (up to 6 s on a cold cache).
                            if (resolving) return
                            resolving = true
                            card.alpha = RESOLVING_ALPHA
                            background({ ExploreCatalogue.details(meta).optJSONObject("external_ids")?.text("imdb_id") }) { answer ->
                                resolving = false
                                card.alpha = 1f
                                if (generation != g) return@background
                                // Save comes from the hold-OK menu, whose window gives focus back late: only opening the
                                // title page waits for the remote to still be on this poster.
                                if (!save && (!card.hasFocus() || !hasWindowFocus())) return@background
                                val id = answer.getOrNull() ?: return@background toastFailure()
                                if (save) saveToWatchlist(WatchTitle(id,meta.type,meta.name,meta.poster,meta.background))
                                else startActivity(Intent(this,DetailsActivity::class.java).putExtra("id",id).putExtra("type",meta.type)
                                    .putExtra("name",meta.name).putExtra("poster",meta.poster).putExtra("background",meta.background))
                            }
                        }
                        card.setOnClickListener { resolve(false) }
                        card.setOnLongClickListener { choose(meta.name,listOf(getString(R.string.save_to_watchlist))) { resolve(true) }; true }
                        if (kotlin.math.abs(bandIndex-maxOf(loadedBand,0)) <= 2) card.load()
                    }
                    if (seen.isNotEmpty() || done) {
                        firstPageLoading = false
                        if (downWhileLoading && (sortControl.hasFocus() || filterBar.hasFocus())) catalogueCards.firstOrNull()?.requestFocus()
                        downWhileLoading = false
                    }
                    if (before == seen.size && !done) more()
                    else if (seen.isEmpty()) emptySuggestions(g,q)
                }, {
                    firstPageLoading = false
                    downWhileLoading = false
                    if (catalogueCards.isEmpty()) clearInfo()
                    // One card for a failed page, whichever page. Retry on a later page asks for that page again and
                    // keeps the viewer's place; on the first it rebuilds the results, so the remote waits in the bar.
                    if (failure == null) failure = failed(getString(R.string.search_popular_unavailable)) {
                        if (next == 1) { peopleButton.requestFocus(); refreshCatalogue() }
                        else { catalogueCards.lastOrNull()?.requestFocus(); more() }
                    }.also { results.addView(it) }
                })
            }
        }
        if (genreNames.isEmpty()) background({ Tmdb.genres(q.media).toMap() }) { answer ->
            if (query.media == q.media) answer.getOrNull()?.let { genreNames = it }
        }
        more()
    }

    /** A row in the panel's language for lists on the page: words (and a count) on the dark, a white pill focused. */
    private fun listRow(label: String, value: String? = null, rest: Int = 0): LinearLayout {
        val key = text(label, HomeStyle.INFO, color = TEXT).apply { isSingleLine = true; ellipsize = TextUtils.TruncateAt.END }
        val count = value?.let { text(it, HomeStyle.INFO, color = HomeStyle.LABEL_GREY).apply { isSingleLine = true } }
        return LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(14),0,dp(14),0)
            addView(key, if (count == null) LinearLayout.LayoutParams(-2,-2) else LinearLayout.LayoutParams(0,-2,1f))
            count?.let { addView(it, LinearLayout.LayoutParams(-2,-2).apply { marginStart = dp(12) }) }
            rowFocus(rest = { if (rest != 0) HomeStyle.pill(rest) else null }, also = { on ->
                if (on) background = HomeStyle.pill(HomeStyle.WHITE)
                key.setTextColor(if (on) HomeStyle.BLACK else TEXT)
                key.typeface = if (on) semiBold else medium
                count?.setTextColor(if (on) FOCUSED_VALUE else HomeStyle.LABEL_GREY)
            })
        }
    }

    private fun emptySuggestions(g: Int,q: ExploreQuery) {
        clearInfo()
        results.addView(text(R.string.discover_empty,BODY,color = HomeStyle.WHITE),
            LinearLayout.LayoutParams(-2,-2).apply { topMargin = dp(10); bottomMargin = dp(10) })
        val alternatives = Facet.values().filter(::active).map { getString(R.string.search_remove_query,title(it)) to q.without(it) } +
            if (!q.any && q.genres.size>1) listOf(getString(R.string.ex_match)+" · "+getString(R.string.ex_any) to q.copy(any=true)) else emptyList()
        alternatives.forEach { (name,alternative) ->
            background({
                var page = ExploreCatalogue.page(alternative,1)
                // Director/writer lists have no total: count at most a few pages and say "+" past them.
                if (page.count >= 0) page.count to false else {
                    var n = 1
                    var count = page.titles.size
                    while (!page.last && n < COUNT_PAGES && generation == g) {
                        page = ExploreCatalogue.page(alternative,++n)
                        count += page.titles.size
                    }
                    count to !page.last
                }
            }) { answer ->
                if (g == generation) answer.getOrNull()?.takeIf { it.first>0 }?.let { (count, more) ->
                    results.addView(listRow(name, String.format(Locale.getDefault(), "%,d", count) + if (more) "+" else "").apply {
                        setOnClickListener { peopleButton.requestFocus(); change(alternative) }
                    }, LinearLayout.LayoutParams(dp(380), dp(30)).apply { bottomMargin = dp(4) })
                }
            }
        }
    }

    /** Five top-billed faces with their names, slots that keep their places while the remote moves. */
    private fun castFaces() = LinearLayout(this).apply {
        repeat(CAST_FACES) { i ->
            addView(column(Gravity.CENTER_HORIZONTAL).apply {
                addView(FaceView(this@ExploreScreen).apply {
                    background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(PLACEHOLDER) }
                    clipToOutline = true
                }, LinearLayout.LayoutParams(dp(HERO_FACE), dp(HERO_FACE)))
                addView(text("", HERO_CAST_SP, color = TEXT).onArt().apply {
                    gravity = Gravity.CENTER; maxLines = 2; ellipsize = TextUtils.TruncateAt.END
                }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(4) })
                visibility = View.INVISIBLE
            }, LinearLayout.LayoutParams(dp(HERO_FACE + 20), -2).apply { if (i > 0) marginStart = dp(5) })
        }
    }

    /**
     * The focused title as the hero across the top: its art fading into the black at the left and bottom, its logo
     * (or name), certificate · year · length · genres, scores and overview at the left, the top-billed cast at the
     * right, under the top bar. It never moves or changes size; a filter's picker takes its place while open
     * ([heroChoose]). What TMDb's discover answer holds shows at once; the rest comes after a rest on the poster, into
     * fixed-size slots.
     */
    protected fun buildHero(): View {
        infoArt = FadedArt(this, 0.45f, 1f).apply { scaleType = ImageView.ScaleType.CENTER_CROP; alpha = 0f }
        infoName = text("", HERO_NAME_SP, bold = true, color = HomeStyle.WHITE).apply { maxLines = 1; ellipsize = TextUtils.TruncateAt.END }
        infoLogo = ImageView(this).apply { adjustViewBounds = true; maxWidth = dp(HERO_LOGO_W); maxHeight = dp(HERO_TITLE_H) }
        infoCert = tag("", HomeStyle.WHITE).apply { visibility = View.GONE }
        infoFacts = text("", SMALL, color = TEXT_2).apply { isSingleLine = true; ellipsize = TextUtils.TruncateAt.END }
        infoGenres = text("", SMALL, color = TEXT_2).apply { isSingleLine = true; ellipsize = TextUtils.TruncateAt.END }
        infoScores = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        infoOverview = text("", HERO_OVERVIEW_SP, color = OVERVIEW).apply {
            ellipsize = TextUtils.TruncateAt.END
            // As many whole lines as its slot holds (never more than three), the last ending in "…".
            addOnLayoutChangeListener { _, _, top, _, bottom, _, oldTop, _, oldBottom ->
                if (bottom - top != oldBottom - oldTop) maxLines = ((bottom - top) / lineHeight).coerceIn(1, HERO_OVERVIEW_LINES)
            }
        }
        infoBody = column(Gravity.START).apply {
            addView(FrameLayout(this@ExploreScreen).apply {
                addView(infoName, FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM or Gravity.START))
                addView(infoLogo, FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM or Gravity.START))
            }, LinearLayout.LayoutParams(dp(HERO_TEXT_W), dp(HERO_TITLE_H)))
            addView(LinearLayout(this@ExploreScreen).apply {
                gravity = Gravity.CENTER_VERTICAL
                addView(infoCert, LinearLayout.LayoutParams(-2, -2).apply { marginEnd = dp(8) })
                addView(infoFacts, LinearLayout.LayoutParams(-2, -2))
                addView(infoGenres, LinearLayout.LayoutParams(-2, -2).apply { marginStart = dp(14) })
            }, LinearLayout.LayoutParams(dp(HERO_TEXT_W), dp(20)).apply { topMargin = dp(6) })
            addView(infoScores, LinearLayout.LayoutParams(dp(HERO_TEXT_W), dp(18)).apply { topMargin = dp(6) })
            addView(infoOverview, LinearLayout.LayoutParams(dp(HERO_TEXT_W), dp(HERO_OVERVIEW_H)).apply { topMargin = dp(8) })
            visibility = View.INVISIBLE
        }
        infoFaces = castFaces()
        heroFull = FrameLayout(this).apply {
            addView(infoArt, FrameLayout.LayoutParams(dp(HERO_ART_W), -1, Gravity.END))
            // The top bar's words stay readable over bright art.
            addView(View(this@ExploreScreen).apply {
                background = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(0x99000000.toInt(), 0))
            }, FrameLayout.LayoutParams(-1, dp(96)))
            addView(infoBody, FrameLayout.LayoutParams(-2, -2).apply { leftMargin = dp(SearchLayout.SIDE); topMargin = dp(HERO_BODY_TOP) })
            addView(infoFaces, FrameLayout.LayoutParams(-2, -2, Gravity.END or Gravity.BOTTOM).apply {
                rightMargin = dp(SearchLayout.SIDE); bottomMargin = dp(18)
            })
        }
        pickerHost = FrameLayout(this).apply { visibility = View.GONE }
        return FrameLayout(this).apply {
            addView(heroFull, FrameLayout.LayoutParams(-1, -1))
            addView(pickerHost, FrameLayout.LayoutParams(-1, -1).apply {
                leftMargin = dp(SearchLayout.SIDE); rightMargin = dp(SearchLayout.SIDE); topMargin = dp(PICKER_TOP)
            })
        }.also { infoPanel = heroFull }
    }

    /** [meta] in the hero: what the discover answer holds now, the rest after a rest on it ([HomeStyle.WASH_WAIT]). */
    private fun showInfo(meta: Meta) {
        // Hidden (typing): no art or lookups for it until it's back.
        if (infoPanel.visibility != View.VISIBLE) { unseen = meta; return }
        unseen = null
        if (shown === meta) return
        pending?.let(main::removeCallbacks)
        shown = meta
        infoBody.visibility = View.VISIBLE
        // The art waits for the rest with the other lookups, so the large backdrop isn't decoded for every poster
        // passed; the last one fades out meanwhile.
        infoArt.animate().alpha(0f).setDuration(QUICK_MS).start()
        Images.load(null, infoLogo, 0) // and a late logo for the title before is dropped
        for (i in 0 until infoFaces.childCount) infoFaces.getChildAt(i).visibility = View.INVISIBLE
        infoName.text = meta.name
        infoName.visibility = View.VISIBLE
        infoCert.visibility = View.GONE
        infoFacts.text = meta.year.orEmpty()
        infoGenres.text = meta.genres.take(2).joinToString(DOT)
        infoScores.removeAllViews()
        repeat(2) {
            infoScores.addView(View(this).apply { background = rounded(PLACEHOLDER, 4) },
                LinearLayout.LayoutParams(dp(48), dp(12)).apply { marginEnd = dp(10) })
        }
        infoOverview.text = meta.description.orEmpty()
        pending = Runnable { if (shown === meta) { heroArt(meta); infoDetails(meta) } }.also { main.postDelayed(it, HomeStyle.WASH_WAIT) }
    }

    /** The hero's backdrop (TMDb's large one), fading in once it's here. */
    private fun heroArt(meta: Meta) =
        Images.load(meta.background, infoArt, dp(HERO_ART_W), done = { if (shown === meta) infoArt.enter().alpha(1f).start() })

    /** The logo cropped to its ink, as the title page does, sized by its shape to fill the title's slot. */
    private fun sizeLogo() {
        val bitmap = (infoLogo.drawable as? android.graphics.drawable.BitmapDrawable)?.bitmap ?: return
        if (bitmap.width < 1 || bitmap.height < 1) return
        val aspect = bitmap.width.toDouble() / bitmap.height
        var h = minOf(HERO_TITLE_H.toDouble(), Math.sqrt(HERO_LOGO_AREA / aspect))
        var w = aspect * h
        if (w > HERO_LOGO_W) { w = HERO_LOGO_W.toDouble(); h = w / aspect }
        val density = resources.displayMetrics.density
        infoLogo.layoutParams = FrameLayout.LayoutParams((w * density).toInt().coerceAtLeast(1), (h * density).toInt().coerceAtLeast(1),
            Gravity.BOTTOM or Gravity.START)
    }

    /** Nothing for the hero: no matches, or the first page failed. */
    private fun clearInfo() {
        shown = null
        unseen = null
        infoBody.visibility = View.INVISIBLE
        Images.load(null, infoArt, 0)
    }

    /** The hero's logo, certificate, length, cast and ratings; each answer is dropped once another title has the hero. */
    private fun infoDetails(meta: Meta) {
        background({ ExploreCatalogue.details(meta) }) { answer ->
            if (shown !== meta) return@background
            val d = answer.getOrNull() ?: return@background infoScores.removeAllViews()
            val id = d.optJSONObject("external_ids")?.text("imdb_id")
            // The logo as the title page shows it (metahub, by IMDb id); the name stays when there is none.
            id?.let(Images::logoFor)?.let { logo ->
                Images.load(logo, infoLogo, Images.TITLE_LOGO_PX, look = Images.trimmedLogo,
                    done = { if (shown === meta) { sizeLogo(); infoName.visibility = View.INVISIBLE } })
            }
            // The top-billed cast's faces (TMDb's small profile pictures), in fixed slots.
            val cast = d.optJSONObject("credits")?.optJSONArray("cast").objects().take(CAST_FACES)
            for (i in 0 until infoFaces.childCount) {
                val slot = infoFaces.getChildAt(i) as LinearLayout
                val person = cast.getOrNull(i)
                slot.visibility = if (person == null) View.INVISIBLE else View.VISIBLE
                (slot.getChildAt(1) as TextView).text = person?.text("name").orEmpty()
                Images.load(person?.text("profile_path")?.let { "https://image.tmdb.org/t/p/w185$it" }, slot.getChildAt(0) as ImageView, dp(HERO_FACE))
            }
            val certificate = if (meta.type=="movie") d.optJSONObject("release_dates")?.optJSONArray("results").objects()
                .firstOrNull { it.text("iso_3166_1")=="US" }?.optJSONArray("release_dates").objects().firstNotNullOfOrNull { it.text("certification") }
                else d.optJSONObject("content_ratings")?.optJSONArray("results").objects().firstOrNull { it.text("iso_3166_1")=="US" }?.text("rating")
            val duration = if (meta.type=="movie") d.optInt("runtime").takeIf { it>0 }?.let { runtime("$it min") } // "2h 06m", as the title page
                else d.optInt("number_of_seasons").takeIf { it>0 }?.let { resources.getQuantityString(R.plurals.seasons,it,it) }
            certificate?.let { infoCert.text = it; infoCert.visibility = View.VISIBLE }
            infoFacts.text = listOfNotNull(meta.year, duration).joinToString(DOT)
            infoGenres.text = d.optJSONArray("genres").objects().take(2).joinToString(DOT) { it.optString("name") }
            if (id == null) return@background infoScores.removeAllViews()
            val key = Settings.mdblistKey(this)
            background({
                // Each lookup waits on the shared pool: stop as soon as the remote has moved to another poster.
                val ratings = mdbRatings(id,key,meta.type).orEmpty()
                val imdb = if (shown !== meta) null else Stremio.savedAddons(authKey.orEmpty()).orEmpty().firstNotNullOfOrNull { addon ->
                    addon.savedMeta(meta.type,id)?.rating
                } ?: if (shown !== meta) null else runCatching {
                    JSONObject(Http.get("https://v3-cinemeta.strem.io/meta/${meta.type}/$id.json",timeoutMs=5000))
                        .optJSONObject("meta")?.text("imdbRating")
                }.getOrNull()
                shownRatings("both",imdb,ratings["letterboxd"],ratings["tomatoes"])
            }) {
                if (shown !== meta) return@background
                infoScores.removeAllViews()
                addRatingLogos(infoScores,it.getOrNull().orEmpty(),gap = 9) // all three, whole, in the hero's width
            }
        }
    }

    private val recentPrefs get() = getSharedPreferences("explore_people", MODE_PRIVATE)
    private val recentKey get() = Settings.profileKey(this,"recent")
    private fun recentPeople(): List<Person> = runCatching {
        JSONArray(recentPrefs.getString(recentKey,"[]")).objects().map {
            Person(it.getInt("id"),it.getString("name"),it.getString("role"),it.text("photo"))
        }
    }.getOrDefault(emptyList())

    private fun rememberPerson(p: Person) {
        val array = JSONArray()
        (listOf(p)+recentPeople()).distinctBy { it.id }.take(8).forEach {
            array.put(JSONObject().put("id",it.id).put("name",it.name).put("role",it.role).putOpt("photo",it.photo))
        }
        recentPrefs.edit().putString(recentKey,array.toString()).apply()
    }

    private fun selectPerson(p: Person) {
        rememberPerson(p)
        query = query.copy(person=p)
        lookup(null,restoreTitle=false)
    }

    /** The People picker (A2): Recent and Popular Now faces, or the typed name's matches; All clears the person. */
    protected fun findPeople(words: String, focusFirst: Boolean) {
        val g = generation
        val token = ++peopleToken
        findPeople(words, focusFirst, results, FACES, FACE, gridWidth / FACES, alive = { g == generation && token == peopleToken },
            clear = { query = query.without(Facet.PEOPLE); lookup(null,restoreTitle=false) }, pick = ::selectPerson)
    }

    /**
     * The faces into [into], [perRow] to a row of [tileW] px: All, then Recent and Popular Now, or [words]' matches.
     * [chosen] marks the person in use (the hero's picker, which stays open); [alive] drops a late answer.
     */
    private fun findPeople(words: String, focusFirst: Boolean, into: LinearLayout, perRow: Int, faceDp: Int, tileW: Int,
                           alive: () -> Boolean, clear: () -> Unit, pick: (Person) -> Unit, chosen: (Person) -> Boolean = { false },
                           held: (Person) -> Unit = pick) { // held: a face's hold-OK role list picked a role
        into.removeAllViews()
        val originalFocus = currentFocus
        var firstChoice = focusFirst
        fun focusChoice(view: View) {
            if (firstChoice && currentFocus === originalFocus) view.requestFocus()
            firstChoice = false
        }
        val all = listRow(getString(R.string.filter_all), rest = PLACEHOLDER).apply { setOnClickListener { clear() } }
        into.addView(all, LinearLayout.LayoutParams(-2, dp(28)).apply { topMargin = dp(RING_ROOM); bottomMargin = dp(6) })
        val r = HomeStyle.ringOut(this)
        fun faces(label: String, people: List<Person>) {
            if (people.isEmpty()) return
            into.addView(text(label, HomeStyle.ROW_TITLE, bold = true, color = HomeStyle.WHITE),
                LinearLayout.LayoutParams(-2,-2).apply { topMargin = dp(10); bottomMargin = dp(8) })
            people.take(perRow * 3).chunked(perRow).forEach { group ->
                val row = LinearLayout(this).apply { clipChildren = false; clipToPadding = false }
                into.addView(row, LinearLayout.LayoutParams(-2,-2).apply { bottomMargin = dp(10) })
                group.forEach { p ->
                    val ring = View(this).apply {
                        background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setStroke(r * 2, HomeStyle.RING_COLOR) }
                        visibility = View.INVISIBLE
                    }
                    val name = text(p.name, SMALL, bold = true, color = TEXT_2).apply { gravity = Gravity.CENTER; maxLines = 2 }
                    fun style(has: Boolean) = name.setTextColor(when { has -> HomeStyle.WHITE; chosen(p) -> TUNGSTEN; else -> TEXT_2 })
                    val tile = column(Gravity.CENTER_HORIZONTAL).apply {
                        isFocusable = true
                        tag = Runnable { style(isFocused) } // restyled when the person in use changes
                        setOnFocusChangeListener { _, has ->
                            ring.visibility = if (has) View.VISIBLE else View.INVISIBLE
                            style(has)
                        }
                        setOnClickListener { pick(p) }
                        setOnLongClickListener {
                            val roles = listOf("actor" to R.string.ex_actor,"director" to R.string.ex_director,
                                "writer" to R.string.ex_writer,"any" to R.string.ex_role)
                            choose(p.name,roles.map { getString(it.second) },checked=roles.indexOfFirst { it.first==p.role }) {
                                held(p.copy(role=roles[it].first))
                            }
                            true
                        }
                    }
                    style(false)
                    val face = FaceView(this).apply {
                        background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(PLACEHOLDER) }
                        clipToOutline = true
                    }
                    val faceBox = FrameLayout(this).apply {
                        addView(face, FrameLayout.LayoutParams(dp(faceDp), dp(faceDp), Gravity.CENTER))
                        addView(ring, FrameLayout.LayoutParams(-1, -1))
                    }
                    tile.addView(faceBox, dp(faceDp) + 6 * r, dp(faceDp) + 6 * r)
                    Images.load(p.photo?.let { "https://image.tmdb.org/t/p/w185$it" },face,dp(faceDp))
                    tile.addView(name, LinearLayout.LayoutParams(-1,-2).apply { topMargin = dp(4) })
                    row.addView(tile,LinearLayout.LayoutParams(tileW,-2))
                    focusChoice(tile)
                }
            }
        }
        if (words.isEmpty()) faces(getString(R.string.search_recent),recentPeople())
        val movie = query.movie
        val loaded = popularTitles[movie]
        background({ if (words.isBlank()) ExploreCatalogue.popularPeople(movie, loaded) else ExploreCatalogue.people(words) }) {
            if (alive()) it.fold({ found -> faces(if (words.isBlank()) getString(R.string.ex_popular) else words,found) },{toastFailure(R.string.search_unavailable)})
        }
    }

    /** A picker in the hero's place hides the hero; closed, it's back. */
    private fun styleHero() = heroFull.enter(PAGE_MS).alpha(if (pickerFacet != null) 0f else 1f).start()

    /** Up from anywhere in the controls row goes to [id] (Voice, in the top bar), not to whatever is straight above. */
    protected fun upFromControls(id: Int) =
        (typeButtons + rows.values.map { it.view } + listOf(clearFilters, sortControl)).forEach { it.nextFocusUpId = id }

    /** A hero picker's choice: white while focused, a chosen one in tungsten on the chosen filter's tint with its tick. */
    private fun styleTile(t: TextView) {
        val on = t.isFocused
        val ticked = t.compoundDrawablesRelative[2] != null
        t.background = when { on -> HomeStyle.pill(HomeStyle.WHITE); ticked -> outlined(CHOSEN_FILL, CHOSEN_EDGE); else -> outlined(SEGMENT, GLASS_EDGE) }
        val color = when { on -> HomeStyle.BLACK; ticked -> TUNGSTEN; else -> TEXT }
        t.setTextColor(color)
        t.compoundDrawablesRelative[2]?.setTint(color)
        t.typeface = if (on || ticked) semiBold else medium
    }

    /** The hero's picker: [title] and [extra] along the top, the choices below in columns, the hero hidden behind it. */
    private fun showPicker(f: Facet, title: String, extra: View?, body: View, back: (() -> Unit)?) {
        val host = pickerHost
        host.removeAllViews()
        host.addView(column(Gravity.START).apply {
            addView(LinearLayout(this@ExploreScreen).apply {
                gravity = Gravity.CENTER_VERTICAL
                addView(text(title, HomeStyle.ROW_TITLE + 2f, bold = true, color = HomeStyle.WHITE))
                extra?.let { addView(it, LinearLayout.LayoutParams(-2, -2).apply { marginStart = dp(20) }) }
            }, LinearLayout.LayoutParams(-1, dp(34)))
            addView(body, LinearLayout.LayoutParams(-1, -1).apply { topMargin = dp(10) })
        }, FrameLayout.LayoutParams(-1, -1))
        pickerFacet = f
        pickerBack = back
        if (host.visibility != View.VISIBLE) { host.visibility = View.VISIBLE; host.alpha = 0f; host.enter().alpha(1f).start() }
        styleHero()
    }

    /**
     * [chooseNear]'s list in the hero's place, its choices in columns of [PICK_ROWS], the poster rows below
     * changing live. A pick closes it unless [stay]; Right on a row opens [right]'s list there; Back runs [back] (the
     * list before) or closes it, and the remote goes back to [f]'s pill.
     */
    private fun heroChoose(f: Facet, title: String, items: List<String>, checked: Int, focus: Int, checkedRows: Set<Int>,
                           right: ((Int) -> (() -> Unit)?)?, back: (() -> Unit)?, extra: View?, stay: Boolean,
                           shown: (List<TextView>) -> Unit, pick: (Int) -> Unit) {
        val tiles = mutableListOf<TextView>()
        val columns = LinearLayout(this)
        // A list whose rows open lists of their own (Year's decades, Custom Range's From and To) is one scrolling
        // column, as the drop-down is: Right on a row opens its list, and Up and Down reach every row.
        val perColumn = if (right != null) items.size.coerceAtLeast(1) else PICK_ROWS
        items.chunked(perColumn).forEachIndexed { c, chunk ->
            columns.addView(column(Gravity.START).apply {
                chunk.forEachIndexed { r, label ->
                    val i = c * perColumn + r
                    val tile = text(label, SMALL).apply {
                        isFocusable = true
                        isSingleLine = true
                        ellipsize = TextUtils.TruncateAt.END
                        gravity = Gravity.CENTER_VERTICAL
                        includeFontPadding = false
                        setPadding(dp(14), 0, dp(12), 0)
                        if (i == checked || i in checkedRows) tick(true)
                        setOnFocusChangeListener { v, _ -> styleTile(v as TextView) }
                        setOnClickListener {
                            if (!stay) closePicker(focusChip = true)
                            pick(i)
                            tiles.forEach(::styleTile)
                        }
                        if (right != null) setOnKeyListener { _, code, e ->
                            if (code != KeyEvent.KEYCODE_DPAD_RIGHT || e.action != KeyEvent.ACTION_DOWN) return@setOnKeyListener false
                            right(i)?.let { it(); true } ?: false
                        }
                    }
                    tiles += tile
                    addView(tile, LinearLayout.LayoutParams(dp(PICK_W), dp(PICK_H)).apply { if (r > 0) topMargin = dp(PICK_GAP) })
                }
            }, LinearLayout.LayoutParams(-2, -2).apply { if (c > 0) marginStart = dp(PICK_GAP) })
        }
        val body = if (right != null) ScrollView(this).apply {
            isVerticalScrollBarEnabled = false
            isVerticalFadingEdgeEnabled = true
            setFadingEdgeLength(dp(24))
            addView(columns)
        } else HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            clipToPadding = false
            addView(columns)
        }
        showPicker(f, title, extra, body, back)
        tiles.forEach(::styleTile)
        tiles.getOrNull(focus)?.requestFocus()
        shown(tiles)
        tiles.forEach(::styleTile)
    }

    /**
     * Directors & Cast in the hero (b-2): the role the person is matched as, Keyboard for a name (the
     * keys and today's people picker), then the faces. A face is picked at once and the picker stays, its posters below.
     */
    private fun heroPeople() {
        pickedRole = null // each opening starts from the person in use's own role
        val roles = listOf("any" to R.string.ex_role, "actor" to R.string.ex_actor, "director" to R.string.ex_director)
        val segment = LinearLayout(this).apply {
            background = outlined(SEGMENT, GLASS_EDGE)
            setPadding(dp(3), dp(3), dp(3), dp(3))
        }
        val faces = column(Gravity.START)
        fun role() = pickedRole ?: query.person?.role ?: "any"
        fun restyle() {
            for (i in 0 until segment.childCount) (segment.getChildAt(i) as TextView).let { it.pillState(it.isFocused, roles[i].first == role()) }
            fun walk(v: View) {
                (v.tag as? Runnable)?.run()
                if (v is android.view.ViewGroup) for (i in 0 until v.childCount) walk(v.getChildAt(i))
            }
            walk(faces)
        }
        roles.forEach { (key, words) ->
            segment.addView(text(words, HomeStyle.INFO).apply {
                gravity = Gravity.CENTER
                isFocusable = true
                includeFontPadding = false
                setPadding(dp(10), 0, dp(10), 0)
                setOnFocusChangeListener { _, _ -> restyle() }
                setOnClickListener {
                    pickedRole = key
                    query.person?.let { change(query.copy(person = it.copy(role = key))) }
                    restyle()
                }
            }, LinearLayout.LayoutParams(-2, dp(BAR_H - 6)))
        }
        val byName = text(R.string.ex_keyboard, HomeStyle.INFO).apply {
            gravity = Gravity.CENTER
            isFocusable = true
            includeFontPadding = false
            setPadding(dp(12), 0, dp(12), 0)
            setCompoundDrawablesRelative(getDrawable(R.drawable.ic_search)?.mutate()?.apply { setBounds(0, 0, dp(12), dp(12)) }, null, null, null)
            compoundDrawablePadding = dp(7)
            fun style(on: Boolean) {
                background = if (on) HomeStyle.pill(HomeStyle.WHITE) else outlined(SEGMENT, GLASS_EDGE)
                setTextColor(if (on) HomeStyle.BLACK else TEXT)
                compoundDrawablesRelative[0]?.setTint(if (on) HomeStyle.BLACK else TEXT)
            }
            style(false)
            setOnFocusChangeListener { _, has -> style(has) }
            setOnClickListener { closePicker(focusChip = false); lookup("person") }
        }
        val extra = LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            addView(segment, LinearLayout.LayoutParams(-2, dp(BAR_H)))
            addView(byName, LinearLayout.LayoutParams(-2, dp(BAR_H)).apply { marginStart = dp(12) })
        }
        val scroll = ScrollView(this).apply {
            isVerticalScrollBarEnabled = false
            isVerticalFadingEdgeEnabled = true
            setFadingEdgeLength(dp(24))
            addView(faces)
        }
        showPicker(Facet.PEOPLE, title(Facet.PEOPLE), extra, scroll, back = null)
        val token = ++peopleToken
        val width = resources.displayMetrics.widthPixels - 2 * dp(SearchLayout.SIDE)
        findPeople("", true, faces, HERO_FACES, HERO_PICK_FACE, width / HERO_FACES, alive = { token == peopleToken && pickerFacet == Facet.PEOPLE },
            clear = { change(query.without(Facet.PEOPLE)); restyle() },
            pick = { p ->
                rememberPerson(p)
                change(query.copy(person = p.copy(role = pickedRole ?: p.role)))
                restyle()
            }, chosen = { it.id == query.person?.id },
            held = { p ->
                // Hold OK's role wins over the switch, which then shows it.
                pickedRole = null
                rememberPerson(p)
                change(query.copy(person = p))
                restyle()
            })
        restyle()
    }

    /** The picker leaves the hero; with [focusChip] the remote goes back to its pill. */
    protected fun closePicker(focusChip: Boolean) {
        val f = pickerFacet ?: return
        val host = pickerHost
        pickerFacet = null
        pickerBack = null
        peopleToken++
        host.visibility = View.GONE
        host.removeAllViews()
        styleHero()
        if (focusChip) rows[f]?.view?.requestFocus()
    }

    /** Back with a hero picker open: its list before, or closed. */
    protected fun backFromPicker(): Boolean {
        pickerFacet ?: return false
        val back = pickerBack
        if (back != null && currentFocus?.let { isInside(it, pickerHost) } == true) back() else closePicker(focusChip = true)
        return true
    }

    /** The remote moved to [now]: off the picker and the controls row, the picker closes. */
    protected fun focusMoved(now: View?) {
        if (now != null && pickerFacet != null && !isInside(now, pickerHost) && !isInside(now, filterBar) && !isInside(now, headingControls))
            closePicker(focusChip = false)
    }

    private companion object {
        // dp at 1080p. While typing: five ~126 dp posters with 10 dp gutters, 9 dp between rows, the grid stopping
        // 33 dp from the right edge (the keys at the left).
        const val GRID_GAP = 10
        const val GRID_ROW_GAP = 9
        const val GRID_RIGHT = 33
        const val COLUMNS = 5
        const val WIDE_GAP = 8 // the catalogue's posters: 8 dp apart both ways, the mockup's 16 px
        const val CAST_FACES = 5
        // The hero: a 28 sp title (or logo), the overview at body size, the cast at its lower right.
        const val HERO_NAME_SP = 28f
        // The title's slot starts 11 dp under the top bar (its avatar and capsule end at 47 dp), so no logo or name ever
        // reaches up to them; the logo is fitted inside it (down, never up), bottom-aligned on the facts line.
        const val HERO_TITLE_H = 52
        const val HERO_BODY_TOP = 58
        const val HERO_LOGO_W = 300
        const val HERO_LOGO_AREA = 8800.0 // about the title page's logo (8976 dp², Images.logoSizePx), for the smaller slot
        const val HERO_TEXT_W = 430
        const val HERO_OVERVIEW_SP = 13.5f // a step under body size (14.5)
        const val HERO_OVERVIEW_LINES = 3
        const val HERO_OVERVIEW_H = 52 // three lines at 13.5 sp
        const val HERO_ART_W = 680
        const val HERO_FACE = 49 // owner: the cast 10% smaller (54 dp faces, 12.5 sp names before)
        const val HERO_CAST_SP = 11.25f
        // The pickers in the hero's place, under the top bar: choices in columns of four; faces ten across.
        const val PICKER_TOP = 50
        const val PICK_ROWS = 4
        const val PICK_W = 164
        const val PICK_H = 26
        const val PICK_GAP = 6
        const val HERO_FACES = 10
        const val HERO_PICK_FACE = 44
        const val RING_ROOM = 4 // above the first row, for Home's focus ring
        const val BAR_H = 28 // the filter bar's pills (Neon A's 54 px, Voice Search's height)
        const val BAR_GAP = 5
        const val VALUE_MIN = 40 // the shortest a pill's value is cut to so the bar fits
        const val KEY_MIN = 56 // the shortest a pill's name is cut to, after the values
        const val PICKER_W = 220 // Sort's list, hanging under it
        const val EDGE = 33 // a list never comes nearer the screen's sides
        const val DROP = 6 // between a pill and its list
        const val RESOLVING_ALPHA = 0.6f // a poster waiting for its title page
        const val COUNT_PAGES = 5 // pages a no-results suggestion counts at most
        const val FACES = 6
        const val FACE = 72
        const val SEGMENT = 0x12FFFFFF
        const val FOCUSED_VALUE = 0xAD000000.toInt()
        // A chosen filter's pill (Neon A's .fpill.active): a warm tint, a tungsten edge, its name in faded tungsten.
        const val CHOSEN_FILL = 0x24F3D9B1
        const val CHOSEN_EDGE = 0x61F3D9B1
        const val CHOSEN_NAME = 0xB8F3D9B1.toInt()
        const val PLACEHOLDER = 0x14FFFFFF
        const val OVERVIEW = 0xFFD4CFC7.toInt()
    }
}
