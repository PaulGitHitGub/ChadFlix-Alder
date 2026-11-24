package dev.alder

import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.drawable.GradientDrawable
import android.text.SpannableString
import android.text.TextUtils
import android.text.style.ForegroundColorSpan
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewOutlineProvider
import android.widget.*
import java.util.Locale
import org.json.JSONArray
import org.json.JSONObject

/** Search's catalogue: its filter bar over the posters, Sort on the heading, the focused title's info panel at the left. */
abstract class ExploreScreen : Screen() {
    protected abstract val results: LinearLayout
    protected abstract val resultsScroll: ScrollView
    protected abstract fun lookup(facet: String?, restoreTitle: Boolean = true)
    protected abstract fun refreshCatalogue()
    private var query = ExploreQuery()
    protected lateinit var peopleButton: View
    private lateinit var filterBar: View
    private lateinit var infoPanel: View
    protected lateinit var sortControl: TextView
    private lateinit var headingField: EditText
    private var upFromSort = View.NO_ID // the top bar, when there's no filter bar over Sort
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
    private lateinit var infoScores: LinearLayout
    private lateinit var infoOverview: TextView
    private lateinit var infoCast: TextView
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

    /** The heading's sort drop-down, "Sort: Popular ⌄": OK opens the reliable sorts under it; Down goes to the posters. */
    protected fun buildSortControl(field: EditText, topBar: View): TextView {
        headingField = field
        upFromSort = topBar.id
        sortControl = text("", SMALL).apply {
            id = View.generateViewId()
            isFocusable = true
            isSingleLine = true
            gravity = Gravity.CENTER
            includeFontPadding = false
            setPadding(dp(12),0,dp(11),0)
            setCompoundDrawablesRelative(null, null, getDrawable(R.drawable.ic_chevron_down)?.mutate()?.apply {
                setBounds(0, 0, dp(10), dp(10))
            }, null)
            compoundDrawablePadding = dp(6)
            nextFocusLeftId = id // not into the heading's words
            nextFocusRightId = id
            setOnClickListener { sortPicker() }
            setOnFocusChangeListener { _, has ->
                downWhileLoading = false
                styleSort()
                colorHeading(has)
            }
            setOnKeyListener(downToPosters)
        }
        styleSort()
        return sortControl
    }

    /** Down from the filter bar or Sort: the first row's poster under it (or the first, once the first page comes). */
    private val downToPosters = View.OnKeyListener { v, code, event ->
        if (code != KeyEvent.KEYCODE_DPAD_DOWN || event.action != KeyEvent.ACTION_DOWN) return@OnKeyListener false
        val middle = { view: View -> IntArray(2).also(view::getLocationOnScreen)[0] + view.width / 2 }
        catalogueCards.take(COLUMNS).minByOrNull { kotlin.math.abs(middle(it) - middle(v)) }?.requestFocus()
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

    private fun colorHeading(highlight: Boolean) {
        if (sortControl.visibility != View.VISIBLE) return
        val words = SpannableString(heading)
        val sort = sortLabel()
        val start = words.toString().lastIndexOf(sort)
        if (highlight && start >= 0) words.setSpan(ForegroundColorSpan(HomeStyle.LIST_RED),start,start+sort.length,0)
        headingField.hint = words
    }

    /**
     * The filter bar over the posters (Neon's Approach A): Movies | Shows, then a pill per filter in [Facet]'s order
     * showing its value ("Genre Horror + Comedy"), and Clear All Filters while any is set. It scrolls sideways if long.
     */
    protected fun buildFilterBar(): View {
        val bar = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        val types = LinearLayout(this).apply {
            background = outlined(SEGMENT, GLASS_EDGE)
            setPadding(dp(3),dp(3),dp(3),dp(3))
        }
        listOf("movie" to R.string.tab_movies, "series" to R.string.tab_shows).forEach { (type, name) ->
            val b = text(name, SMALL).apply {
                gravity = Gravity.CENTER
                isFocusable = true
                includeFontPadding = false
                setPadding(dp(12),0,dp(12),0)
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
            val icon = if (f == Facet.PEOPLE) R.drawable.ic_person else 0
            val key = text(title(f), SMALL).apply {
                isSingleLine = true
                includeFontPadding = false
                if (icon != 0) {
                    setCompoundDrawablesRelative(getDrawable(icon)?.mutate()?.apply { setBounds(0,0,dp(11),dp(11)) }, null, null, null)
                    compoundDrawablePadding = dp(5)
                }
            }
            val label = text("", SMALL, bold = true).apply {
                isSingleLine = true
                includeFontPadding = false
                ellipsize = TextUtils.TruncateAt.END
                maxWidth = dp(VALUE_MAX)
            }
            val chevron = ImageView(this).apply { setImageResource(R.drawable.ic_chevron_down) }
            val chip = LinearLayout(this).apply {
                id = View.generateViewId()
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(12),0,dp(10),0)
                isFocusable = true
                addView(key)
                addView(label, LinearLayout.LayoutParams(-2,-2).apply { marginStart = dp(5) })
                addView(chevron, LinearLayout.LayoutParams(dp(10),dp(10)).apply { marginStart = dp(5) })
                setOnFocusChangeListener { _, _ -> styleRow(f) }
                setOnClickListener { open(f) }
                setOnKeyListener(downToPosters)
            }
            bar.addView(chip, LinearLayout.LayoutParams(-2,dp(BAR_H)).apply { marginStart = dp(BAR_GAP) })
            rows[f] = Row(chip, key, label, chevron)
            if (f == Facet.PEOPLE) peopleButton = chip
        }
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
                rows.values.first().view.requestFocus() // this pill goes once nothing is set
                change(ExploreQuery(type=query.type, sort=query.sort))
            }
            setOnKeyListener(downToPosters)
        }
        bar.addView(clearFilters, LinearLayout.LayoutParams(-2,dp(BAR_H)).apply { marginStart = dp(BAR_GAP) })
        filterBar = HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            addView(bar)
        }
        updatePanel()
        return filterBar
    }

    private fun outlined(fill: Int, edge: Int) = GradientDrawable().apply { setColor(fill); cornerRadius = 999f; setStroke(dp(1), edge) }

    /** A filter pill: white while focused; a chosen value in tungsten on a warm tint; else a grey name and a white value. */
    private fun styleRow(f: Facet) {
        val r = rows[f] ?: return
        val on = r.view.isFocused
        val lit = active(f)
        r.view.background = when { on -> HomeStyle.pill(HomeStyle.WHITE); lit -> outlined(CHOSEN_FILL, CHOSEN_EDGE); else -> outlined(SEGMENT, GLASS_EDGE) }
        val name = when { on -> FOCUSED_VALUE; lit -> CHOSEN_NAME; else -> HomeStyle.LABEL_GREY }
        r.key.setTextColor(name)
        r.key.compoundDrawablesRelative[0]?.setTint(name)
        r.value.setTextColor(when { on -> HomeStyle.BLACK; lit -> TUNGSTEN; else -> HomeStyle.WHITE })
        r.chevron.imageTintList = ColorStateList.valueOf(name)
    }

    /**
     * The bar and Sort go with the catalogue (not typed words or the people picker); the info panel too, unless the
     * keys have the left column.
     */
    protected fun showCatalogueControls(bar: Boolean, panel: Boolean) {
        filterBar.visibility = if (bar) View.VISIBLE else View.GONE
        sortControl.visibility = filterBar.visibility
        infoPanel.visibility = if (panel) View.VISIBLE else View.GONE
        linkSort()
    }

    /** Up from Sort: the bar's last pill, not the Settings gear straight above; with no bar, the top bar. */
    private fun linkSort() {
        sortControl.nextFocusUpId = if (filterBar.visibility != View.VISIBLE) upFromSort
            else (if (clearFilters.visibility == View.VISIBLE) clearFilters else rows.values.last().view).id
    }

    /** The catalogue's first poster, for Right from Keyboard Search. */
    protected fun firstPoster(): View? = catalogueCards.firstOrNull()

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
        colorHeading(sortControl.hasFocus())
        linkSort()
    }

    private fun active(f: Facet) = query.without(f) != query &&
        !(query.person != null && !query.movie && f == Facet.STUDIO)

    private fun change(q: ExploreQuery) {
        query = q
        invalidateCatalogue()
        updatePanel()
        main.postDelayed(delayed, 300)
    }

    /** [f]'s list, hanging under its pill. */
    private fun chooseNear(f: Facet, options: List<String>, checked: Int = -1, focus: Int = maxOf(checked, 0), name: String = title(f),
                           widthDp: Int = PICKER_W, back: (() -> Unit)? = null, right: ((Int) -> (() -> Unit)?)? = null, pick: (Int)->Unit) {
        val (x, y) = under(rows.getValue(f).view, widthDp)
        choose(name, options, checked = checked, focus = focus, widthDp = widthDp, anchorStartDp = x, anchorTopDp = y,
            right = right, back = back, pick = pick)
    }

    private fun open(f: Facet) {
        when(f) {
            Facet.PEOPLE -> lookup("person")
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
            setPadding(dp(16), dp(8), dp(8), dp(8))
            addView(text(R.string.ex_match, BODY), LinearLayout.LayoutParams(0, -2, 1f))
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
        val (x, y) = under(this.rows.getValue(Facet.GENRE).view, GENRE_W)
        choose(title(Facet.GENRE), names, header = true, widthDp = GENRE_W, anchorStartDp = x, anchorTopDp = y, extra = match, stay = true,
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
        shown = null // a new catalogue's first title fills the info panel afresh
    }

    /** A title's page or save couldn't be reached ([words] for a list that couldn't load: People, Genre). */
    private fun toastFailure(words: Int = R.string.ex_unavailable) = Toast.makeText(this,words,Toast.LENGTH_SHORT).show()

    protected fun clearGrid() {
        invalidateCatalogue()
        catalogueCards.forEach { it.unload() }
        catalogueCards.clear()
        loadedBand = -1
    }

    /** The grid's width: the results area less the page's right margin, as the left column's left. */
    private val gridWidth get() = resultsScroll.layoutParams.width - dp(GRID_RIGHT)

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
        val gap = dp(GRID_GAP)
        val width = ((gridWidth - 4 * gap) / 5).coerceAtLeast(dp(48))
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
                        if (row == null || row!!.childCount == 5) {
                            // Home's ring sits just outside each card: the row lets it draw there.
                            row = LinearLayout(this).apply { clipChildren = false; clipToPadding = false }.also {
                                results.addView(it, LinearLayout.LayoutParams(-2,-2).apply {
                                    if (results.childCount == 0) topMargin = dp(RING_ROOM)
                                    bottomMargin = dp(GRID_ROW_GAP)
                                })
                            }
                        }
                        val band = row!!
                        val column = band.childCount
                        val card = MediaCardView(this,meta.name,meta.poster,null,widthPx = width,heightPx = width*3/2,
                            dimAlpha = 0f,fadeIn = false,home = true)
                        val bandIndex = catalogueCards.size / 5
                        if (bandIndex == 0) card.nextFocusUpId = sortControl.id
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
                                    if (previous >= 0) for (i in maxOf(0,previous-2)*5 until minOf(catalogueCards.size,(previous+3)*5)) {
                                        if (kotlin.math.abs(i/5-bandIndex)>2) catalogueCards[i].unload()
                                    }
                                    for (i in maxOf(0,bandIndex-2)*5 until minOf(catalogueCards.size,(bandIndex+3)*5)) catalogueCards[i].load()
                                }
                                band.post { if (card.hasFocus()) resultsScroll.scrollTo(0,(band.top - dp(RING_ROOM)).coerceAtLeast(0)) }
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

    /**
     * The focused poster's title page in brief, left of the grid (Neon's B5 side panel, for one title): its art fading
     * into the panel, logo or name, year · genres · length with the certificate, ratings, overview and cast. What TMDb's
     * discover answer holds shows at once; the rest comes after a rest on the poster, into fixed-size slots.
     */
    protected fun buildInfoPanel(): View {
        infoArt = ImageView(this).apply { scaleType = ImageView.ScaleType.CENTER_CROP }
        infoName = text("", BODY + 1f, bold = true, color = HomeStyle.WHITE).apply { maxLines = 2; ellipsize = TextUtils.TruncateAt.END }
        infoLogo = ImageView(this).apply { adjustViewBounds = true; maxWidth = dp(INFO_LOGO_W); maxHeight = dp(INFO_TITLE_H) }
        infoCert = tag("", HomeStyle.WHITE).apply { visibility = View.GONE }
        infoFacts = text("", SMALL, color = TEXT_2).apply { isSingleLine = true; ellipsize = TextUtils.TruncateAt.END }
        infoScores = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        infoOverview = text("", SMALL, color = OVERVIEW).apply {
            ellipsize = TextUtils.TruncateAt.END
            // As many whole lines as its slot holds, the last ending in "…".
            addOnLayoutChangeListener { _, _, top, _, bottom, _, oldTop, _, oldBottom ->
                if (bottom - top != oldBottom - oldTop) maxLines = ((bottom - top) / lineHeight).coerceAtLeast(1)
            }
        }
        infoCast = text("", SMALL, color = TEXT_2).apply { setLines(2); gravity = Gravity.BOTTOM; ellipsize = TextUtils.TruncateAt.END }
        infoBody = column(Gravity.START).apply {
            setPadding(dp(INFO_PAD), 0, dp(INFO_PAD), dp(INFO_PAD))
            addView(FrameLayout(this@ExploreScreen).apply {
                addView(infoName, FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM or Gravity.START))
                addView(infoLogo, FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM or Gravity.START))
            }, LinearLayout.LayoutParams(-1, dp(INFO_TITLE_H)))
            addView(LinearLayout(this@ExploreScreen).apply {
                gravity = Gravity.CENTER_VERTICAL
                addView(infoCert, LinearLayout.LayoutParams(-2, -2).apply { marginEnd = dp(7) })
                addView(infoFacts)
            }, LinearLayout.LayoutParams(-1, dp(20)).apply { topMargin = dp(7) })
            addView(infoScores, LinearLayout.LayoutParams(-1, dp(16)).apply { topMargin = dp(6) })
            addView(infoOverview, LinearLayout.LayoutParams(-1, 0, 1f).apply { topMargin = dp(10) })
            addView(infoCast, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) })
            visibility = View.INVISIBLE
        }
        return FrameLayout(this).apply {
            // The filter panel's quiet surface it replaces: a warm near-black and a hairline edge; the art clipped to it.
            background = GradientDrawable().apply { setColor(PANEL_FILL); cornerRadius = dp(13).toFloat(); setStroke(1, PANEL_EDGE) }
            outlineProvider = ViewOutlineProvider.BACKGROUND
            clipToOutline = true
            addView(infoArt, FrameLayout.LayoutParams(-1, dp(INFO_ART_H)))
            addView(View(this@ExploreScreen).apply {
                background = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(PANEL_FILL and 0x00FFFFFF, PANEL_FILL))
            }, FrameLayout.LayoutParams(-1, dp(INFO_FADE_H)).apply { topMargin = dp(INFO_ART_H - INFO_FADE_H) })
            addView(infoBody, FrameLayout.LayoutParams(-1, -1).apply { topMargin = dp(INFO_ART_H - INFO_OVERLAP) })
        }.also { infoPanel = it }
    }

    /** [meta] in the info panel: what the discover answer holds now, the rest after a rest on it ([HomeStyle.WASH_WAIT]). */
    private fun showInfo(meta: Meta) {
        if (shown === meta) return
        pending?.let(main::removeCallbacks)
        shown = meta
        infoBody.visibility = View.VISIBLE
        Images.load(meta.background?.replace("/w1280/", "/w780/"), infoArt, dp(INFO_ART_W))
        Images.load(null, infoLogo, 0) // and a late logo for the title before is dropped
        infoName.text = meta.name
        infoName.visibility = View.VISIBLE
        infoCert.visibility = View.GONE
        infoFacts.text = listOfNotNull(meta.year, *meta.genres.take(2).toTypedArray()).joinToString(DOT)
        infoScores.removeAllViews()
        repeat(2) {
            infoScores.addView(View(this).apply { background = rounded(PLACEHOLDER, 4) },
                LinearLayout.LayoutParams(dp(48), dp(12)).apply { marginEnd = dp(10) })
        }
        infoOverview.text = meta.description.orEmpty()
        infoCast.text = ""
        pending = Runnable { if (shown === meta) infoDetails(meta) }.also { main.postDelayed(it, HomeStyle.WASH_WAIT) }
    }

    /** Nothing for the info panel: no matches, or the first page failed. */
    private fun clearInfo() {
        shown = null
        infoBody.visibility = View.INVISIBLE
        Images.load(null, infoArt, 0)
    }

    /** The info panel's logo, certificate, length, cast and ratings; each answer is dropped once another title has the panel. */
    private fun infoDetails(meta: Meta) {
        background({ ExploreCatalogue.details(meta) }) { answer ->
            if (shown !== meta) return@background
            val d = answer.getOrNull() ?: return@background infoScores.removeAllViews()
            val id = d.optJSONObject("external_ids")?.text("imdb_id")
            // The logo as the title page shows it (metahub, by IMDb id); the name stays when there is none.
            id?.let(Images::logoFor)?.let { logo ->
                Images.load(logo, infoLogo, dp(INFO_LOGO_W), done = { infoName.visibility = View.INVISIBLE })
            }
            val certificate = if (meta.type=="movie") d.optJSONObject("release_dates")?.optJSONArray("results").objects()
                .firstOrNull { it.text("iso_3166_1")=="US" }?.optJSONArray("release_dates").objects().firstNotNullOfOrNull { it.text("certification") }
                else d.optJSONObject("content_ratings")?.optJSONArray("results").objects().firstOrNull { it.text("iso_3166_1")=="US" }?.text("rating")
            val duration = if (meta.type=="movie") d.optInt("runtime").takeIf { it>0 }?.let { runtime("$it min") } // "2h 06m", as the title page
                else d.optInt("number_of_seasons").takeIf { it>0 }?.let { resources.getQuantityString(R.plurals.seasons,it,it) }
            certificate?.let { infoCert.text = it; infoCert.visibility = View.VISIBLE }
            infoFacts.text = (listOfNotNull(meta.year)+d.optJSONArray("genres").objects().take(2).map { it.optString("name") }+listOfNotNull(duration)).joinToString(DOT)
            // The person the catalogue is filtered by first, then the top-billed cast.
            val names = (listOfNotNull(query.person?.name) +
                d.optJSONObject("credits")?.optJSONArray("cast").objects().mapNotNull { it.text("name") }).distinct().take(3)
            if (names.isNotEmpty()) {
                val label = getString(R.string.details_cast)
                infoCast.text = SpannableString("$label  ${names.joinToString(", ")}").apply {
                    setSpan(ForegroundColorSpan(TEXT_3), 0, label.length, 0)
                }
            }
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
                addRatingLogos(infoScores,it.getOrNull().orEmpty())
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

    private fun selectPerson(p: Person) {
        val array = JSONArray()
        (listOf(p)+recentPeople()).distinctBy { it.id }.take(8).forEach {
            array.put(JSONObject().put("id",it.id).put("name",it.name).put("role",it.role).putOpt("photo",it.photo))
        }
        recentPrefs.edit().putString(recentKey,array.toString()).apply()
        query = query.copy(person=p)
        lookup(null,restoreTitle=false)
    }

    /** The People picker (A2): Recent and Popular Now faces, or the typed name's matches; All clears the person. */
    protected fun findPeople(words: String, focusFirst: Boolean) {
        val g = generation
        results.removeAllViews()
        val originalFocus = currentFocus
        var firstChoice = focusFirst
        fun focusChoice(view: View) {
            if (firstChoice && currentFocus === originalFocus) view.requestFocus()
            firstChoice = false
        }
        val all = listRow(getString(R.string.filter_all), rest = PLACEHOLDER).apply { setOnClickListener {
            query = query.without(Facet.PEOPLE)
            lookup(null,restoreTitle=false)
        } }
        results.addView(all, LinearLayout.LayoutParams(-2, dp(28)).apply { topMargin = dp(RING_ROOM); bottomMargin = dp(6) })
        val tileW = gridWidth / FACES
        val r = HomeStyle.ringOut(this)
        fun faces(label: String, people: List<Person>) {
            if (people.isEmpty()) return
            results.addView(text(label, HomeStyle.ROW_TITLE, bold = true, color = HomeStyle.WHITE),
                LinearLayout.LayoutParams(-2,-2).apply { topMargin = dp(10); bottomMargin = dp(8) })
            people.take(FACES * 3).chunked(FACES).forEach { group ->
                val row = LinearLayout(this).apply { clipChildren = false; clipToPadding = false }
                results.addView(row, LinearLayout.LayoutParams(-2,-2).apply { bottomMargin = dp(10) })
                group.forEach { p ->
                    val ring = View(this).apply {
                        background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setStroke(r * 2, HomeStyle.RING_COLOR) }
                        visibility = View.INVISIBLE
                    }
                    val name = text(p.name, SMALL, bold = true, color = TEXT_2).apply { gravity = Gravity.CENTER; maxLines = 2 }
                    val tile = column(Gravity.CENTER_HORIZONTAL).apply {
                        isFocusable = true
                        setOnFocusChangeListener { _, has ->
                            ring.visibility = if (has) View.VISIBLE else View.INVISIBLE
                            name.setTextColor(if (has) HomeStyle.WHITE else TEXT_2)
                        }
                        setOnClickListener { selectPerson(p) }
                        setOnLongClickListener {
                            val roles = listOf("actor" to R.string.ex_actor,"director" to R.string.ex_director,
                                "writer" to R.string.ex_writer,"any" to R.string.ex_role)
                            choose(p.name,roles.map { getString(it.second) },checked=roles.indexOfFirst { it.first==p.role }) {
                                selectPerson(p.copy(role=roles[it].first))
                            }
                            true
                        }
                    }
                    val face = ImageView(this).apply {
                        scaleType = ImageView.ScaleType.CENTER_CROP
                        background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(PLACEHOLDER) }
                        clipToOutline = true
                    }
                    val faceBox = FrameLayout(this).apply {
                        addView(face, FrameLayout.LayoutParams(dp(FACE), dp(FACE), Gravity.CENTER))
                        addView(ring, FrameLayout.LayoutParams(-1, -1))
                    }
                    tile.addView(faceBox, dp(FACE) + 6 * r, dp(FACE) + 6 * r)
                    Images.load(p.photo?.let { "https://image.tmdb.org/t/p/w185$it" },face,dp(FACE))
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
            if (g==generation) it.fold({ found -> faces(if (words.isBlank()) getString(R.string.ex_popular) else words,found) },{toastFailure(R.string.search_unavailable)})
        }
    }

    private companion object {
        // dp, from the chosen mockups at 1080p (CSS px ÷ 2): the left column at 33 from the left and 196 wide; five
        // 128 dp posters with 10 dp gutters, 9 dp between rows; the grid stops 33 dp from the right edge.
        const val GRID_GAP = 10
        const val GRID_ROW_GAP = 9
        const val GRID_RIGHT = 33
        const val COLUMNS = 5
        const val RING_ROOM = 4 // above the first row, for Home's focus ring
        const val BAR_H = 28 // the filter bar's pills (Neon A's 54 px, Voice Search's height)
        const val BAR_GAP = 6
        const val VALUE_MAX = 130 // a pill's value past this ends in "…"
        const val PICKER_W = 220 // the lists hanging under a pill or Sort
        const val GENRE_W = 260
        const val EDGE = 33 // a list never comes nearer the screen's sides
        const val DROP = 6 // between a pill and its list
        // The info panel (B5 at half size): 16:9 art over the panel's 196 dp, the title rising 20 dp into its fade.
        const val INFO_ART_W = 196
        const val INFO_ART_H = 110
        const val INFO_FADE_H = 70
        const val INFO_OVERLAP = 20
        const val INFO_TITLE_H = 44
        const val INFO_PAD = 12
        const val INFO_LOGO_W = 172
        const val RESOLVING_ALPHA = 0.6f // a poster waiting for its title page
        const val COUNT_PAGES = 5 // pages a no-results suggestion counts at most
        const val FACES = 6
        const val FACE = 72
        const val PANEL_FILL = 0xFF15120F.toInt()
        const val PANEL_EDGE = 0x29FFF0DC
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
