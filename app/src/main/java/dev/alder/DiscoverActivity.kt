package dev.alder

import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.StateListDrawable
import android.os.Bundle
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.widget.BaseAdapter
import android.widget.FrameLayout
import android.widget.GridView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView

class DiscoverActivity : Screen() {
    private lateinit var picker: TextView
    private lateinit var filters: TextView
    private lateinit var pin: TextView
    private lateinit var grid: GridView
    private lateinit var status: FrameLayout
    private lateinit var more: TextView
    private lateinit var adapter: Posters
    private var catalogs = emptyList<Addon.Catalog>()
    private var selected: Addon.Catalog? = null
    private var options = emptyMap<String, String>()
    private var request = 0
    private var loading = false
    private var pages: CatalogPager<Meta, Pair<String, String>>? = null
    private val titles = mutableListOf<Meta>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val key = authKey ?: return finish()
        picker = action(R.string.discover_catalog) { chooseCatalog() }.apply {
            maxWidth = dp(270)
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        }
        filters = action(R.string.discover_filters) { chooseFilter() }
        pin = action(R.string.discover_pin) {
            selected?.let { catalog ->
                Settings.pinCatalog(this, catalog.key.takeUnless { it == Settings.pinnedCatalog(this) })
                controls()
            }
        }
        more = action(R.string.discover_load_more) { loadMore() }.apply { visibility = View.GONE }
        adapter = Posters()
        grid = GridView(this).apply {
            adapter = this@DiscoverActivity.adapter
            numColumns = GridView.AUTO_FIT
            columnWidth = dp(112)
            stretchMode = GridView.STRETCH_COLUMN_WIDTH
            gravity = Gravity.CENTER
            horizontalSpacing = dp(12)
            verticalSpacing = dp(12)
            isVerticalScrollBarEnabled = false
            setPadding(dp(3), dp(3), dp(3), dp(3))
            selector = StateListDrawable().apply {
                addState(intArrayOf(android.R.attr.state_focused), GradientDrawable().apply {
                    cornerRadius = dp(8).toFloat()
                    setColor(Color.TRANSPARENT)
                    setStroke(dp(2), TUNGSTEN)
                })
                addState(intArrayOf(), ColorDrawable(Color.TRANSPARENT))
            }
            setDrawSelectorOnTop(true)
            setOnItemClickListener { _, _, position, _ -> titles.getOrNull(position)?.let(::openCatalogTitle) }
        }
        status = FrameLayout(this)
        val viewport = FrameLayout(this).apply {
            outlineProvider = ViewOutlineProvider.BOUNDS
            clipToOutline = true // focus treatment elsewhere may loosen clipping on ancestors
            addView(grid, FrameLayout.LayoutParams(-1, -1))
            addView(status, FrameLayout.LayoutParams(-1, -1))
        }
        val bar = LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            listOf(picker, filters, pin).forEach { addView(it, wrap().apply { marginEnd = dp(12) }) }
        }
        setWindowed(column(Gravity.START).apply {
            setPadding(dp(36), dp(20), dp(36), dp(16))
            addView(text(R.string.nav_discover, SERIF_TITLE, serif = true))
            addView(bar, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8); bottomMargin = dp(14) })
            addView(viewport, LinearLayout.LayoutParams(-1, 0, 1f))
            addView(more, wrap().apply { topMargin = dp(10) })
        })
        controls()
        picker.requestFocus()
        fun load() {
            showStatus(text(R.string.player_loading, BODY, color = TEXT_2))
            background({ Stremio.addons(key, refresh = true) }) { answer ->
                answer.onSuccess {
                    catalogs = it.flatMap { addon -> addon.catalogs }.distinctBy { catalog -> catalog.key }
                    val pinned = Settings.pinnedCatalog(this)
                    val catalog = catalogs.firstOrNull { it.key == pinned }
                        ?: catalogs.firstOrNull { it.type == "movie" || it.type == "series" }
                        ?: catalogs.firstOrNull()
                    if (catalog == null) showStatus(text(R.string.discover_no_catalogs, BODY))
                    else select(catalog)
                }.onFailure { showError(it, ::load) }
            }
        }
        load()
    }

    private fun action(label: Int, click: () -> Unit) = chip(getString(label)).apply { setOnClickListener { click() } }

    private fun controls() {
        val catalog = selected
        picker.text = catalog?.name ?: getString(R.string.discover_catalog)
        filters.visibility = if (catalog?.filters?.isNotEmpty() == true) View.VISIBLE else View.GONE
        filters.text = if (options.isEmpty()) getString(R.string.discover_filters)
            else getString(R.string.discover_filter_count, options.size)
        pin.visibility = if (catalog == null) View.GONE else View.VISIBLE
        pin.text = getString(if (catalog != null && catalog.key == Settings.pinnedCatalog(this)) R.string.discover_unpin else R.string.discover_pin)
    }

    private fun chooseCatalog() {
        if (catalogs.isEmpty()) return
        val labels = catalogs.map { catalog ->
            listOf(catalog.name, catalogTypeLabel(this, catalog.type), catalog.addon.name).joinToString(DOT)
        }
        choose(getString(R.string.discover_catalog), labels, checked = catalogs.indexOf(selected)) { select(catalogs[it]) }
    }

    private fun chooseFilter() {
        val catalog = selected ?: return
        val groups = catalog.filters.entries.toList()
        val labels = groups.map { (key, _) ->
            val name = if (key == "genre") getString(R.string.discover_genre) else key.replaceFirstChar { it.uppercase() }
            listOfNotNull(name, options[key] ?: catalog.required[key]).joinToString(DOT)
        }
        choose(getString(R.string.discover_filters), labels) { index ->
            val (key, choices) = groups[index]
            val values = if (key in catalog.required) choices
                else listOf(null) + choices.filterNot { key == "genre" && it.equals("None", ignoreCase = true) }
            choose(labels[index], values.map { it ?: getString(R.string.filter_all) },
                checked = values.indexOf(options[key] ?: catalog.required[key])) { choice ->
                val value = values[choice]
                val changed = if (value == null || value == catalog.required[key]) options - key else options + (key to value)
                if (changed != options) select(catalog, changed)
            }
        }
    }

    private fun select(catalog: Addon.Catalog, selection: Map<String, String> = emptyMap()) {
        selected = catalog
        options = selection
        controls()
        val token = ++request
        pages = null
        loading = false
        more.visibility = View.GONE
        titles.clear()
        adapter.notifyDataSetChanged()
        grid.setSelection(0)
        showStatus(text(R.string.player_loading, BODY, color = TEXT_2))
        background({
            val first = catalog.addon.filteredCatalog(catalog, selection)
            val pager = CatalogPager(listOf(first), listOf(catalog.pages), { meta -> meta.type to meta.id },
                { meta -> !isAddonMessage(meta.name) }) { _, skip -> catalog.addon.filteredCatalog(catalog, selection, skip) }
            val items = pager.slice(0, PAGE_SIZE)
            Triple(pager, items, pager.hasMore(items.size))
        }) { result ->
            if (token != request) return@background
            result.onSuccess { (pager, items, hasMore) ->
                pages = pager
                showStatus(if (items.isEmpty()) text(R.string.discover_no_titles, BODY, color = TEXT_2) else null)
                titles.addAll(items)
                adapter.notifyDataSetChanged()
                more.setText(R.string.discover_load_more)
                more.visibility = if (hasMore) View.VISIBLE else View.GONE
            }.onFailure { error -> showError(error) { select(catalog, selection) } }
        }
    }

    private fun loadMore() {
        val pager = pages ?: return
        if (loading) return
        loading = true
        val token = request
        val from = titles.size
        more.setText(R.string.player_loading)
        background({
            val items = pager.slice(from, PAGE_SIZE)
            items to pager.hasMore(from + items.size)
        }) { result ->
            if (token != request) return@background
            loading = false
            result.onSuccess { (items, hasMore) ->
                val moveFocus = more.hasFocus()
                titles.addAll(items)
                adapter.notifyDataSetChanged()
                if (moveFocus && titles.isNotEmpty()) {
                    grid.requestFocus()
                    grid.setSelection(minOf(from, titles.lastIndex))
                }
                more.setText(R.string.discover_load_more)
                more.visibility = if (hasMore) View.VISIBLE else View.GONE
            }.onFailure { error -> more.text = failureMessage(R.string.action_try_again, failureOf(error)) }
        }
    }

    private fun showStatus(view: View?) {
        status.removeAllViews()
        status.visibility = if (view == null) View.GONE else View.VISIBLE
        grid.visibility = if (view == null) View.VISIBLE else View.INVISIBLE
        view?.let { status.addView(it, FrameLayout.LayoutParams(-2, -2, Gravity.TOP or Gravity.START)) }
    }

    private fun showError(error: Throwable, retry: () -> Unit) {
        val card = failed(failureMessage(R.string.catalog_incomplete, failureOf(error)), retry)
        showStatus(card)
        card.getChildAt(2).requestFocus()
    }

    private inner class Posters : BaseAdapter() {
        override fun getCount() = titles.size
        override fun getItem(position: Int) = titles[position]
        override fun getItemId(position: Int) = position.toLong()

        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            val card = (convertView as? LinearLayout) ?: column(Gravity.START).apply {
                setPadding(dp(5), dp(5), dp(5), dp(4))
                addView(ImageView(this@DiscoverActivity).apply {
                    scaleType = ImageView.ScaleType.CENTER_CROP
                    background = tile(5)
                    clipToOutline = true
                    importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
                }, LinearLayout.LayoutParams(-1, dp(143)))
                addView(text("", SMALL).apply {
                    setLines(2)
                    ellipsize = TextUtils.TruncateAt.END
                    importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
                    setPadding(dp(3), dp(4), dp(3), 0)
                }, LinearLayout.LayoutParams(-1, -2))
            }
            val meta = getItem(position)
            val poster = card.getChildAt(0) as ImageView
            poster.setImageDrawable(null)
            Images.load(meta.poster ?: meta.background, poster, dp(120))
            (card.getChildAt(1) as TextView).text = meta.name
            card.contentDescription = meta.name
            return card
        }
    }

    private companion object { const val PAGE_SIZE = 24 }
}
