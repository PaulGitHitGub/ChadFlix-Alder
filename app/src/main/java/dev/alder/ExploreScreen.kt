package dev.alder

import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Rect
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

/** The catalogue and its filter panel live in Search's results area. */
abstract class ExploreScreen : Screen() {
    protected abstract val results: LinearLayout
    protected abstract val resultsScroll: ScrollView
    /** Holds [results] and, over them, the resting poster's pop-out; it scrolls with them. */
    protected abstract val popLayer: FrameLayout
    protected abstract fun lookup(facet: String?, restoreTitle: Boolean = true)
    protected abstract fun refreshCatalogue()
    private var query = ExploreQuery()
    protected lateinit var panel: LinearLayout
    protected lateinit var peopleButton: View
    protected lateinit var panelScroll: ScrollView
    protected lateinit var sortStrip: LinearLayout
    private lateinit var headingField: EditText
    private val quickSorts = listOf("popular", "top", "new")
    private val sortChips = mutableListOf<TextView>()
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
    private var preview: View? = null
    private var previewDim: View? = null
    private var pending: Runnable? = null
    private var delayed = Runnable { refreshCatalogue() }
    private val medium by lazy { text("", SMALL).typeface }
    private val semiBold by lazy { text("", SMALL, bold = true).typeface }
    protected val heading: String get() {
        val sort = value(Facet.SORT)
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
        Facet.SORT -> query.choices[f]?.label ?: getString(R.string.discover_sort_popular)
        else -> query.choices[f]?.label
    } ?: getString(R.string.filter_all)

    /** The heading's sort chips (the chosen "Heading Strip"): Popular, Top Rated, Newest, then More for the rest. */
    protected fun buildSortStrip(field: EditText, topBar: View): LinearLayout {
        headingField = field
        sortStrip = LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            addView(caps(R.string.list_sort, HomeStyle.LABEL_GREY).apply { textSize = 10.5f; setPadding(0,0,dp(6),0) })
        }
        val choices = options(Facet.SORT)
        for (key in quickSorts + "more") {
            val pick = choices.find { it.key == key }
            if (pick == null) sortStrip.addView(View(this).apply { setBackgroundColor(GLASS_EDGE) },
                LinearLayout.LayoutParams(dp(1), dp(16)).apply { marginStart = dp(5); marginEnd = dp(5) })
            val chip = text(pick?.label ?: getString(R.string.menu_more), SMALL).apply {
                id = View.generateViewId()
                isFocusable = true
                isSingleLine = true
                gravity = Gravity.CENTER
                includeFontPadding = false
                setPadding(dp(12),0,dp(12),0)
                if (pick == null) {
                    setCompoundDrawablesRelative(null, null, getDrawable(R.drawable.ic_chevron_down)?.mutate()?.apply {
                        setBounds(0, 0, dp(10), dp(10))
                    }, null)
                    compoundDrawablePadding = dp(4)
                }
                nextFocusUpId = topBar.id
                setOnClickListener { if (pick == null) open(Facet.SORT) else applySort(pick) }
                setOnFocusChangeListener { _, has ->
                    downWhileLoading = false
                    styleSortChip(this, key)
                    colorHeading(has)
                }
                setOnKeyListener { _, code, event ->
                    if (code != KeyEvent.KEYCODE_DPAD_DOWN || event.action != KeyEvent.ACTION_DOWN) false
                    else catalogueCards.firstOrNull()?.requestFocus() ?: firstPageLoading.also { downWhileLoading = it }
                }
            }
            sortStrip.addView(chip,LinearLayout.LayoutParams(-2,dp(24)))
            sortChips += chip
        }
        sortChips.forEachIndexed { i, chip ->
            chip.nextFocusLeftId = sortChips[maxOf(0,i-1)].id
            chip.nextFocusRightId = sortChips[minOf(sortChips.lastIndex,i+1)].id
        }
        return sortStrip
    }

    private fun applySort(pick: Pick) {
        if (query.sort == pick.key && sortStrip.visibility == View.VISIBLE) return
        query = query.copy(choices = query.choices + (Facet.SORT to pick))
        refreshCatalogue()
    }

    private fun activeChip() = quickSorts.indexOf(query.sort).takeIf { it >= 0 } ?: quickSorts.size

    private fun styleSortChip(chip: TextView, key: String) {
        val extra = key == "more"
        val selected = if (extra) query.sort !in quickSorts else query.sort == key
        val focused = chip.hasFocus()
        if (extra) chip.text = if (selected) value(Facet.SORT) else getString(R.string.menu_more)
        chip.background = when {
            focused -> HomeStyle.pill(HomeStyle.WHITE)
            selected -> HomeStyle.pill(HomeStyle.OPEN_TAB)
            else -> null
        }
        val color = when { focused -> HomeStyle.BLACK; selected -> HomeStyle.WHITE; else -> TEXT_2 }
        chip.setTextColor(color)
        chip.typeface = if (focused || selected) semiBold else medium
        chip.compoundDrawablesRelative[2]?.setTint(color)
        chip.isSelected = selected
    }

    private fun colorHeading(highlight: Boolean) {
        if (sortStrip.visibility != View.VISIBLE) return
        val words = SpannableString(heading)
        val sort = value(Facet.SORT)
        val start = words.toString().lastIndexOf(sort)
        if (highlight && start >= 0) words.setSpan(ForegroundColorSpan(HomeStyle.LIST_RED),start,start+sort.length,0)
        headingField.hint = words
    }

    /** The filter panel (Neon's B1): Movies | Shows, People, then the filters under a hairline. */
    protected fun buildPanel(): ScrollView {
        panel = column(Gravity.START).apply { setPadding(dp(8),dp(8),dp(8),dp(8)) }
        val types = LinearLayout(this).apply {
            background = GradientDrawable().apply { setColor(SEGMENT); cornerRadius = 999f; setStroke(1, GLASS_EDGE) }
            setPadding(dp(3),dp(3),dp(3),dp(3))
        }
        listOf("movie" to R.string.tab_movies, "series" to R.string.tab_shows).forEach { (type, name) ->
            val b = text(name, HomeStyle.INFO).apply {
                gravity = Gravity.CENTER
                isFocusable = true
                includeFontPadding = false
                setOnFocusChangeListener { _, _ -> styleTypes() }
                setOnClickListener {
                    if (query.type != type) {
                        genreNames = emptyMap()
                        val validGenres = HomeStyle.genres(type=="movie").map { it.first.toString() }.toSet()
                        val choices = query.choices - Facet.STUDIO
                        change(query.copy(type=type, genres=query.genres.filter { it.key in validGenres },
                            choices=if (query.sort=="box") choices-Facet.SORT else choices))
                    }
                }
            }
            typeButtons += b
            types.addView(b, LinearLayout.LayoutParams(0,dp(22),1f))
        }
        panel.addView(types, LinearLayout.LayoutParams(-1,-2).apply { bottomMargin = dp(8) })
        Facet.values().forEach { f ->
            if (f == Facet.GENRE) panel.addView(View(this).apply { setBackgroundColor(DIVIDER) },
                LinearLayout.LayoutParams(-1, 1).apply { setMargins(dp(6),dp(5),dp(6),dp(5)) })
            val icon = if (f == Facet.PEOPLE) R.drawable.ic_person else 0
            val key = text(title(f), HomeStyle.INFO, color = TEXT).apply {
                isSingleLine = true
                if (icon != 0) {
                    setCompoundDrawablesRelative(getDrawable(icon)?.mutate()?.apply { setBounds(0,0,dp(11),dp(11)) }, null, null, null)
                    compoundDrawablePadding = dp(6)
                }
            }
            val label = text("", PANEL_VALUE, color = HomeStyle.LABEL_GREY).apply {
                gravity = Gravity.END or Gravity.CENTER_VERTICAL
                isSingleLine = true
                ellipsize = TextUtils.TruncateAt.END
            }
            val chevron = ImageView(this).apply { setImageResource(R.drawable.ic_chevron_end) }
            val row = LinearLayout(this).apply {
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(9),0,dp(7),0)
                isFocusable = true
                addView(key)
                addView(label, LinearLayout.LayoutParams(0,-2,1f).apply { marginStart = dp(8) })
                addView(chevron, LinearLayout.LayoutParams(dp(9),dp(9)).apply { marginStart = dp(3) })
                setOnFocusChangeListener { _, on ->
                    if (f == Facet.SORT) colorHeading(on)
                    styleRow(f)
                }
                setOnClickListener { open(f) }
            }
            panel.addView(row, -1, dp(30))
            rows[f] = Row(row, key, label, chevron)
            if (f == Facet.PEOPLE) peopleButton = row
        }
        clearFilters = text(R.string.ex_clear_filters, HomeStyle.INFO, color = TEXT).apply {
            gravity = Gravity.CENTER
            isFocusable = true
            fun style(on: Boolean) {
                background = if (on) HomeStyle.pill(HomeStyle.WHITE) else GradientDrawable().apply { cornerRadius = 999f; setStroke(dp(1), GLASS_EDGE) }
                setTextColor(if (on) HomeStyle.BLACK else TEXT)
            }
            style(false)
            setOnFocusChangeListener { _, has -> style(has) }
            setOnClickListener {
                peopleButton.requestFocus()
                change(ExploreQuery(type=query.type, choices=query.choices.filterKeys { it==Facet.SORT }))
            }
        }
        panel.addView(clearFilters, LinearLayout.LayoutParams(-1,dp(28)).apply { topMargin = dp(8) })
        updatePanel()
        return ScrollView(this).apply {
            isVerticalScrollBarEnabled = false
            isFillViewport = true
            // A quiet rounded surface on the black page, not frosted glass: a warm near-black and a hairline edge.
            background = GradientDrawable().apply { setColor(PANEL_FILL); cornerRadius = dp(13).toFloat(); setStroke(1, PANEL_EDGE) }
            addView(panel)
        }.also { panelScroll = it }
    }

    /** A panel row: a white rounded row while focused; a chosen value in tungsten. */
    private fun styleRow(f: Facet) {
        val r = rows[f] ?: return
        val on = r.view.isFocused
        val lit = active(f) || (f == Facet.SORT && query.choices[Facet.SORT] != null)
        r.view.background = if (on) rounded(HomeStyle.WHITE, 8) else null
        r.key.setTextColor(if (on) HomeStyle.BLACK else TEXT)
        r.key.typeface = if (on) semiBold else medium
        r.key.compoundDrawablesRelative[0]?.setTint(if (on) HomeStyle.BLACK else TEXT)
        r.value.setTextColor(when { on -> FOCUSED_VALUE; lit -> TUNGSTEN; else -> HomeStyle.LABEL_GREY })
        r.value.typeface = if (on || lit) semiBold else medium
        r.chevron.imageTintList = ColorStateList.valueOf(if (on) FOCUSED_VALUE else HomeStyle.LABEL_GREY)
    }

    private fun styleTypes() = typeButtons.forEachIndexed { i, b ->
        val selected = (i==0) == query.movie
        b.background = when { b.isFocused -> HomeStyle.pill(HomeStyle.WHITE); selected -> HomeStyle.pill(HomeStyle.OPEN_TAB); else -> null }
        b.setTextColor(when { b.isFocused -> HomeStyle.BLACK; selected -> HomeStyle.WHITE; else -> TEXT_2 })
        b.typeface = if (selected || b.isFocused) semiBold else medium
    }

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
        sortChips.forEachIndexed { i, chip -> styleSortChip(chip,quickSorts.getOrNull(i) ?: "more") }
        colorHeading(sortStrip.hasFocus() || rows[Facet.SORT]?.view?.hasFocus() == true)
    }

    private fun active(f: Facet) = query.without(f) != query && f != Facet.SORT &&
        !(query.person != null && !query.movie && f == Facet.STUDIO)

    private fun change(q: ExploreQuery) {
        query = q
        invalidateCatalogue()
        updatePanel()
        main.postDelayed(delayed, 300)
    }

    private fun chooseNear(name: String, options: List<String>, checked: Int = -1, pick: (Int)->Unit) =
        choose(name, options, checked = checked, widthDp = 220, anchorStartDp = PICKER_X, pick = pick)

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
                    }, { toastFailure() })
                }
            }
            Facet.SORT -> {
                val sorts = options(f)
                chooseNear(title(f), sorts.map { it.label }, sorts.indexOfFirst { it.key == query.sort }) { applySort(sorts[it]) }
            }
            Facet.YEAR -> years()
            else -> {
                val options = options(f)
                chooseNear(title(f), listOf(getString(R.string.filter_all)) + options.map { it.label },
                    options.indexOfFirst { it.key == query.choices[f]?.key }+1) { index ->
                    val option = options.getOrNull(index - 1)
                    change(if (option == null) query.without(f) else query.copy(choices = query.choices + (f to option)))
                }
            }
        }
    }

    /**
     * The Genre picker beside the panel (A3): Match All | Any, then the genres with ticks. It stays open while genres
     * are ticked, changing its rows in place, and the results beside it update live.
     */
    private fun genrePicker(list: List<Pair<Int,String>>) {
        val dialog = android.app.Dialog(this).apply { requestWindowFeature(android.view.Window.FEATURE_NO_TITLE) }
        val body = column(Gravity.START).apply { setPadding(dp(12), dp(14), dp(12), dp(12)) }
        body.addView(text(title(Facet.GENRE), BODY + 1.5f, bold = true, color = HomeStyle.WHITE).apply { gravity = Gravity.CENTER },
            LinearLayout.LayoutParams(-1, -2))
        val summary = text("", SMALL, color = TUNGSTEN).apply {
            gravity = Gravity.CENTER; isSingleLine = true; ellipsize = TextUtils.TruncateAt.END
        }
        body.addView(summary, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(2); bottomMargin = dp(8) })
        val all = text(R.string.filter_all, SMALL).apply { gravity = Gravity.CENTER; setPadding(dp(11), 0, dp(11), 0) }
        val any = text(R.string.ex_any, SMALL).apply { gravity = Gravity.CENTER; setPadding(dp(11), 0, dp(11), 0) }
        var genreRows = emptyList<TextView>()
        fun refresh() {
            summary.text = if (query.genres.isEmpty()) "" else value(Facet.GENRE)
            listOf(all to !query.any, any to query.any).forEach { (t, on) ->
                t.background = if (on) HomeStyle.pill(HomeStyle.OPEN_TAB) else null
                t.setTextColor(if (on) HomeStyle.WHITE else TEXT_2)
                t.typeface = if (on) semiBold else medium
            }
            genreRows.forEachIndexed { i, row ->
                val ticked = if (i == 0) query.genres.isEmpty() else query.genres.any { it.key == list[i - 1].first.toString() }
                row.setTextColor(if (ticked) TUNGSTEN else TEXT)
                row.setCompoundDrawablesRelative(null, null, if (!ticked) null else getDrawable(R.drawable.ic_check)?.mutate()?.apply {
                    setTint(TUNGSTEN); setBounds(0, 0, dp(16), dp(16))
                }, null)
            }
        }
        val match = LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(16), 0, dp(8), 0)
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
        body.addView(match, LinearLayout.LayoutParams(-1, dp(38)))
        body.addView(View(this).apply { setBackgroundColor(GLASS_EDGE) }, LinearLayout.LayoutParams(-1, 1).apply { setMargins(dp(16), dp(6), dp(16), dp(6)) })
        val names = listOf(getString(R.string.filter_all)) + list.map { it.second }
        genreRows = names.mapIndexed { i, label ->
            text(label, BODY).apply {
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(16), dp(10), dp(16), dp(10))
                rowFocus()
                setOnClickListener {
                    val pick = list.getOrNull(i - 1)?.let { Pick(it.first.toString(), it.second) }
                    change(when {
                        pick == null -> query.without(Facet.GENRE)
                        pick in query.genres -> query.copy(genres = query.genres - pick)
                        else -> query.copy(genres = query.genres + pick)
                    })
                    refresh()
                }
                body.addView(this, LinearLayout.LayoutParams(-1, -2))
            }
        }
        refresh()
        val maxHeight = resources.displayMetrics.heightPixels - dp(PICKER_Y) - dp(27)
        dialog.setContentView(object : ScrollView(this) {
            override fun onMeasure(w: Int, h: Int) = super.onMeasure(w, MeasureSpec.makeMeasureSpec(maxHeight, MeasureSpec.AT_MOST))
        }.apply {
            isVerticalScrollBarEnabled = false
            isVerticalFadingEdgeEnabled = true
            setFadingEdgeLength(dp(44))
            background = glassOver(this, 16, shade = FROST_READING)
            addView(body)
        })
        dialog.window?.apply {
            setBackgroundDrawable(android.graphics.drawable.ColorDrawable(0))
            setLayout(dp(260), -2)
            setDimAmount(PICKER_DIM)
            setGravity(Gravity.LEFT or Gravity.TOP)
            attributes = attributes.apply { x = dp(PICKER_X); y = dp(PICKER_Y) }
        }
        dialog.show()
        // Opens on the first ticked genre, so flipping a pair to Any or adding one is a few presses away.
        (list.indexOfFirst { g -> query.genres.any { it.key == g.first.toString() } }.takeIf { it >= 0 }?.let { genreRows[it + 1] }
            ?: genreRows[0]).requestFocus()
        lastChoose = dialog
    }

    private fun years() {
        val now = java.util.Calendar.getInstance().get(java.util.Calendar.YEAR)
        val decades = (now/10*10 downTo 1970 step 10).toList()
        fun set(low: Int?, high: Int, label: String) = change(query.copy(choices = query.choices +
            (Facet.YEAR to Pick("${low ?: ""}:$high",label))))
        fun singles(decade: Int) {
            val list = (minOf(now,decade+9) downTo decade).toList()
            chooseNear(title(Facet.YEAR),list.map(Int::toString)) { set(list[it],list[it],list[it].toString()) }
        }
        choose(title(Facet.YEAR),listOf(getString(R.string.filter_all)) + decades.map { "${it}–${it+9}  ›" } +
            listOf(getString(R.string.ex_before),getString(R.string.ex_range)),widthDp = 220,anchorStartDp = PICKER_X,
            right = { i -> decades.getOrNull(i-1)?.let { { singles(it) } } }) { i ->
            when(i) {
                0 -> change(query.without(Facet.YEAR))
                decades.size+1 -> set(null,1969,getString(R.string.ex_before))
                decades.size+2 -> {
                    val from = (now downTo 1900).toList()
                    chooseNear(getString(R.string.ex_from),from.map(Int::toString)) { a ->
                        val to = (now downTo from[a]).toList()
                        chooseNear(getString(R.string.ex_to),to.map(Int::toString)) { b -> set(from[a],to[b],"${from[a]}–${to[b]}") }
                    }
                }
                else -> decades[i-1].let { set(it,it+9,"$it–${it+9}") }
            }
        }
    }

    private fun options(f: Facet): List<Pick> = when(f) {
        Facet.SORT -> query.sortOptions().map { (key, words) -> Pick(key,HomeStyle.titleCase(getString(words))) }
        // TMDb: 1632 is Lionsgate (The Hunger Games, John Wick), 35 the older "Lions Gate Films"; either matches.
        Facet.STUDIO -> if (query.movie) listOf(Pick("41077","A24"),Pick("420","Marvel Studios"),Pick("3","Pixar"),
            Pick("2","Walt Disney Pictures"),Pick("174","Warner Bros."),Pick("33","Universal Pictures"),Pick("4","Paramount Pictures"),
            Pick("521","DreamWorks Animation"),Pick("10342","Studio Ghibli"),Pick("1632|35","Lionsgate"),Pick("3172","Blumhouse"))
            else HomeStyle.platforms.map { Pick(it.networks,it.name) }
        Facet.LANGUAGE -> listOf("en","ko","ja","es","fr","hi","de","it","pt","ar","zh").map { Pick(it,Locale.forLanguageTag(it).displayLanguage) }
        Facet.COUNTRY -> listOf("US","GB","KR","JP","IN","FR","ES","DE","IT","BR","CA","AU","CN").map { Pick(it,Locale.Builder().setRegion(it).build().displayCountry) }
        else -> emptyList()
    }

    protected fun invalidateCatalogue() {
        downWhileLoading = false
        firstPageLoading = false
        generation++
        main.removeCallbacks(delayed)
        closePreview()
    }

    private fun closePreview() {
        pending?.let(main::removeCallbacks)
        pending = null
        listOfNotNull(preview, previewDim).forEach { it.animate().cancel(); popLayer.removeView(it) }
        preview = null
        previewDim = null
    }

    private fun toastFailure() = Toast.makeText(this,R.string.ex_unavailable,Toast.LENGTH_SHORT).show()

    protected fun clearGrid() {
        invalidateCatalogue()
        catalogueCards.forEach { it.unload() }
        catalogueCards.clear()
        loadedBand = -1
    }

    /** The grid's width: the results area less the page's right margin, as the panel's left. */
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
                        if (bandIndex == 0) card.nextFocusUpId = sortChips[activeChip()].id
                        catalogueCards += card
                        // The first column keeps RING_ROOM/2 at its left, inside the scroller's clip, for the ring.
                        band.addView(card,LinearLayout.LayoutParams(width,width*3/2).apply { marginStart = if (column > 0) gap else dp(RING_ROOM / 2) })
                        card.setOnFocusChanged { has ->
                            if (has) {
                                closePreview()
                                if (bandIndex != loadedBand) {
                                    val previous = loadedBand
                                    loadedBand = bandIndex
                                    if (previous >= 0) for (i in maxOf(0,previous-2)*5 until minOf(catalogueCards.size,(previous+3)*5)) {
                                        if (kotlin.math.abs(i/5-bandIndex)>2) catalogueCards[i].unload()
                                    }
                                    for (i in maxOf(0,bandIndex-2)*5 until minOf(catalogueCards.size,(bandIndex+3)*5)) catalogueCards[i].load()
                                }
                                band.post { if (card.hasFocus()) resultsScroll.scrollTo(0,(band.top - dp(RING_ROOM)).coerceAtLeast(0)) }
                                pending = Runnable { if (card.hasFocus() && hasWindowFocus() && g == generation) popOut(card,column,meta) }
                                    .also { main.postDelayed(it,HomeStyle.WASH_WAIT) }
                                if (results.indexOfChild(band) >= results.childCount-2) more()
                            } else closePreview()
                        }
                        fun resolve(save: Boolean) {
                            background({ ExploreCatalogue.details(meta).optJSONObject("external_ids")?.text("imdb_id") }) { answer ->
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
                        if (downWhileLoading && sortStrip.hasFocus()) catalogueCards.firstOrNull()?.requestFocus()
                        downWhileLoading = false
                    }
                    if (before == seen.size && !done) more()
                    else if (seen.isEmpty()) emptySuggestions(g,q)
                }, {
                    firstPageLoading = false
                    downWhileLoading = false
                    // Retry rebuilds the results under the button: the remote waits on People, not the avatar.
                    results.addView(failed(getString(R.string.search_popular_unavailable)) { peopleButton.requestFocus(); refreshCatalogue() })
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
        results.addView(text(R.string.discover_empty,BODY,color = HomeStyle.WHITE),
            LinearLayout.LayoutParams(-2,-2).apply { topMargin = dp(10); bottomMargin = dp(10) })
        val alternatives = Facet.values().filter(::active).map { getString(R.string.ex_remove,title(it)) to q.without(it) } +
            if (!q.any && q.genres.size>1) listOf(getString(R.string.ex_match)+" · "+getString(R.string.ex_any) to q.copy(any=true)) else emptyList()
        alternatives.forEach { (name,alternative) ->
            background({
                var page = ExploreCatalogue.page(alternative,1)
                if (page.count >= 0) page.count else {
                    var n = 1
                    var count = page.titles.size
                    while (!page.last && generation == g) {
                        page = ExploreCatalogue.page(alternative,++n)
                        count += page.titles.size
                    }
                    count
                }
            }) { answer ->
                if (g == generation) answer.getOrNull()?.takeIf { it>0 }?.let { count ->
                    results.addView(listRow(name, String.format(Locale.getDefault(), "%,d", count)).apply {
                        setOnClickListener { peopleButton.requestFocus(); change(alternative) }
                    }, LinearLayout.LayoutParams(dp(380), dp(30)).apply { bottomMargin = dp(4) })
                }
            }
        }
    }

    /**
     * The Showing Wall preview (B6): resting on a poster grows it into a small title page over its neighbours, three
     * posters wide and two rows tall, with the home ring around it; the rest of the grid dims and nothing moves. What
     * TMDb's discover answer holds shows at once; ratings and cast keep fixed-size placeholders until they come (B7).
     */
    private fun popOut(card: MediaCardView, column: Int, meta: Meta) {
        closePreview()
        val w = card.width * 3 + dp(GRID_GAP) * 2
        val h = card.height * 2 + dp(GRID_ROW_GAP)
        val spot = Rect(0, 0, card.width, card.height)
        popLayer.offsetDescendantRectToMyCoords(card, spot)
        val rightward = column <= 2
        val downward = spot.top - resultsScroll.scrollY + h <= resultsScroll.height
        val left = if (rightward) spot.left else spot.right - w
        val top = if (downward) spot.top else spot.bottom - h
        val r = HomeStyle.ringOut(this)

        val dim = View(this).apply { setBackgroundColor(POP_DIM); alpha = 0f }
        popLayer.addView(dim, FrameLayout.LayoutParams(-1, maxOf(popLayer.height, top + h + r)))
        val holder = FrameLayout(this).apply { clipChildren = false }
        val pop = FrameLayout(this).apply {
            background = GradientDrawable().apply { setColor(POP_FILL); cornerRadius = HomeStyle.CARD_RADIUS * resources.displayMetrics.density }
            outlineProvider = ViewOutlineProvider.BACKGROUND
            clipToOutline = true
        }
        holder.addView(pop, FrameLayout.LayoutParams(w, h).apply { setMargins(r, r, r, r) })
        holder.addView(HomeStyle.ring(this).apply { visibility = View.VISIBLE }, FrameLayout.LayoutParams(w + 2 * r, h + 2 * r))
        popLayer.addView(holder, FrameLayout.LayoutParams(w + 2 * r, h + 2 * r).apply { leftMargin = left - r; topMargin = top - r })

        val artH = (h * 0.62f).toInt()
        val art = ImageView(this).apply { scaleType = ImageView.ScaleType.CENTER_CROP }
        pop.addView(art, FrameLayout.LayoutParams(-1, artH))
        Images.load(meta.background?.replace("/w1280/", "/w780/"), art, w)
        val fadeH = (artH * 0.6f).toInt()
        pop.addView(View(this).apply {
            background = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(POP_FILL and 0x00FFFFFF, POP_FILL))
        }, FrameLayout.LayoutParams(-1, fadeH).apply { topMargin = artH - fadeH })

        val info = column(Gravity.START).apply { setPadding(dp(19), 0, dp(19), dp(16)) }
        pop.addView(info, FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM))
        val titleSlot = FrameLayout(this)
        val name = text(meta.name, BODY + 5f, bold = true, color = HomeStyle.WHITE).apply {
            isSingleLine = true; ellipsize = TextUtils.TruncateAt.END
        }
        titleSlot.addView(name, FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM or Gravity.START))
        info.addView(titleSlot, LinearLayout.LayoutParams(-1, dp(46)))
        val factsRow = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        val facts = text(listOfNotNull(meta.year, *meta.genres.take(2).toTypedArray()).joinToString(DOT), SMALL, color = HomeStyle.WHITE)
            .apply { isSingleLine = true; ellipsize = TextUtils.TruncateAt.END }
        factsRow.addView(facts)
        info.addView(factsRow, LinearLayout.LayoutParams(-1, dp(22)).apply { topMargin = dp(8) })
        val scores = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        repeat(2) {
            scores.addView(View(this).apply { background = rounded(PLACEHOLDER, 4) },
                LinearLayout.LayoutParams(dp(56), dp(14)).apply { marginEnd = dp(10) })
        }
        info.addView(scores, LinearLayout.LayoutParams(-1, dp(18)).apply { topMargin = dp(6) })
        info.addView(text(meta.description.orEmpty(), SMALL, color = OVERVIEW).apply {
            setLines(3); ellipsize = TextUtils.TruncateAt.END
        }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) })
        val chips = LinearLayout(this)
        repeat(3) {
            chips.addView(View(this).apply { background = HomeStyle.pill(PLACEHOLDER) },
                LinearLayout.LayoutParams(dp(104), dp(24)).apply { marginEnd = dp(7) })
        }
        info.addView(chips, LinearLayout.LayoutParams(-1, dp(24)).apply { topMargin = dp(10) })

        // The poster grows from where it sits: the pop-out starts at the card's size, pinned at its corner.
        holder.pivotX = (if (rightward) r else w + r).toFloat()
        holder.pivotY = (if (downward) r else h + r).toFloat()
        holder.scaleX = card.width.toFloat() / w
        holder.scaleY = card.height.toFloat() / h
        holder.enter().scaleX(1f).scaleY(1f).start()
        dim.enter().alpha(1f).start()
        preview = holder
        previewDim = dim

        background({ ExploreCatalogue.details(meta) }) { answer ->
            if (preview !== holder) return@background
            val d = answer.getOrNull() ?: return@background
            val logo = d.optJSONObject("images")?.optJSONArray("logos").objects().firstNotNullOfOrNull { it.text("file_path") }
            if (logo != null) {
                val image = ImageView(this).apply { adjustViewBounds = true; maxWidth = dp(250); maxHeight = dp(46) }
                titleSlot.addView(image, FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM or Gravity.START))
                Images.load("https://image.tmdb.org/t/p/w500$logo", image, dp(250), done = { name.visibility = View.INVISIBLE })
            }
            val certificate = if (meta.type=="movie") d.optJSONObject("release_dates")?.optJSONArray("results").objects()
                .firstOrNull { it.text("iso_3166_1")=="US" }?.optJSONArray("release_dates").objects().firstNotNullOfOrNull { it.text("certification") }
                else d.optJSONObject("content_ratings")?.optJSONArray("results").objects().firstOrNull { it.text("iso_3166_1")=="US" }?.text("rating")
            val duration = if (meta.type=="movie") d.optInt("runtime").takeIf { it>0 }?.let { getString(R.string.info_runtime_minutes,it) }
                else d.optInt("number_of_seasons").takeIf { it>0 }?.let { resources.getQuantityString(R.plurals.seasons,it,it) }
            certificate?.let { factsRow.addView(tag(it, HomeStyle.WHITE), 0, LinearLayout.LayoutParams(-2, -2).apply { marginEnd = dp(8) }) }
            facts.text = (listOfNotNull(meta.year)+d.optJSONArray("genres").objects().take(2).map { it.optString("name") }+listOfNotNull(duration)).joinToString(DOT)
            val filtered = query.person
            val people = listOfNotNull(filtered?.let { it.name to it.photo }) +
                d.optJSONObject("credits")?.optJSONArray("cast").objects().mapNotNull { member ->
                    member.text("name")?.let { it to member.text("profile_path") }
                }
            chips.removeAllViews()
            people.distinctBy { it.first }.take(3).forEachIndexed { i, (person, photo) ->
                val chip = LinearLayout(this).apply {
                    gravity = Gravity.CENTER_VERTICAL
                    background = GradientDrawable().apply {
                        setColor(PLACEHOLDER); cornerRadius = 999f
                        if (i == 0 && filtered != null) setStroke(dp(1), HomeStyle.WHITE)
                    }
                    setPadding(dp(3), 0, dp(10), 0)
                }
                val face = ImageView(this).apply {
                    scaleType = ImageView.ScaleType.CENTER_CROP
                    background = rounded(PLACEHOLDER, 9)
                    clipToOutline = true
                }
                chip.addView(face, dp(18), dp(18))
                Images.load(photo?.let { "https://image.tmdb.org/t/p/w185$it" }, face, dp(18))
                chip.addView(text(person, HomeStyle.INFO, color = HomeStyle.WHITE).apply {
                    isSingleLine = true; ellipsize = TextUtils.TruncateAt.END; maxWidth = dp(84); setPadding(dp(5), 0, 0, 0)
                })
                chips.addView(chip, LinearLayout.LayoutParams(-2, dp(24)).apply { marginEnd = dp(7) })
            }
            val id = d.optJSONObject("external_ids")?.text("imdb_id") ?: return@background scores.removeAllViews()
            val key = Settings.mdblistKey(this)
            background({
                val ratings = mdbRatings(id,key,meta.type).orEmpty()
                val imdb = Stremio.savedAddons(authKey.orEmpty()).orEmpty().firstNotNullOfOrNull { addon ->
                    addon.savedMeta(meta.type,id)?.rating
                } ?: runCatching {
                    JSONObject(Http.get("https://v3-cinemeta.strem.io/meta/${meta.type}/$id.json",timeoutMs=5000))
                        .optJSONObject("meta")?.text("imdbRating")
                }.getOrNull()
                shownRatings("both",imdb,ratings["letterboxd"],ratings["tomatoes"])
            }) {
                if (preview !== holder) return@background
                scores.removeAllViews()
                addRatingLogos(scores,it.getOrNull().orEmpty())
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
        background({ if (words.isBlank()) ExploreCatalogue.popularPeople(movie) else ExploreCatalogue.people(words) }) {
            if (g==generation) it.fold({ found -> faces(if (words.isBlank()) getString(R.string.ex_popular) else words,found) },{toastFailure()})
        }
    }

    private companion object {
        // dp, from the chosen mockups at 1080p (CSS px ÷ 2): the panel at 33 from the left and 196 wide; five 128 dp
        // posters with 10 dp gutters, 9 dp between rows; the grid stops 33 dp from the right edge, as the panel does.
        const val GRID_GAP = 10
        const val GRID_ROW_GAP = 9
        const val GRID_RIGHT = 33
        const val RING_ROOM = 4 // above the first row, for Home's focus ring
        const val PICKER_X = 241 // a picker opens 12 dp right of the panel
        const val PICKER_Y = 88
        const val PICKER_DIM = 0.32f // the results beside a picker stay readable while they update
        const val FACES = 6
        const val FACE = 72
        const val PANEL_VALUE = 11f // sp, a filter's value: a step under its name
        const val PANEL_FILL = 0xFF15120F.toInt()
        const val PANEL_EDGE = 0x29FFF0DC
        const val SEGMENT = 0x12FFFFFF
        const val DIVIDER = 0x1CFFF0DC
        const val FOCUSED_VALUE = 0xAD000000.toInt()
        const val POP_FILL = 0xFF13110F.toInt()
        const val POP_DIM = 0x8C000000.toInt()
        const val PLACEHOLDER = 0x14FFFFFF
        const val OVERVIEW = 0xFFD4CFC7.toInt()
    }
}
