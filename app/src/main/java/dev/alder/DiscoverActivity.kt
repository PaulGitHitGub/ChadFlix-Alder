package dev.alder

import android.os.Bundle
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

class DiscoverActivity : Screen() {
    private lateinit var picker: TextView
    private lateinit var filters: TextView
    private lateinit var pin: TextView
    private lateinit var results: LinearLayout
    private lateinit var scroll: ScrollView
    private var catalogs = emptyList<Addon.Catalog>()
    private var selected: Addon.Catalog? = null
    private var options = emptyMap<String, String>()
    private var request = 0
    private var next = 0
    private var row: LinearLayout? = null
    private var footer: View? = null
    private val columns get() = ((resources.displayMetrics.widthPixels - dp(72)) / dp(122)).coerceIn(3, 9)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val key = authKey ?: return finish()
        picker = pill(R.string.discover_catalog).apply { setOnClickListener { chooseCatalog() } }
        filters = pill(R.string.discover_filters).apply { setOnClickListener { chooseFilter() } }
        pin = pill(R.string.discover_pin).apply {
            setOnClickListener {
                selected?.let { catalog ->
                    Settings.pinCatalog(this@DiscoverActivity, catalog.key.takeUnless { it == Settings.pinnedCatalog(this@DiscoverActivity) })
                    controls()
                }
            }
        }
        results = column(Gravity.START)
        scroll = ScrollView(this).apply {
            isVerticalScrollBarEnabled = false
            addView(results)
        }
        val bar = LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            listOf(picker, filters, pin).forEach { addView(it, wrap().apply { marginEnd = dp(12) }) }
        }
        val title = text(R.string.nav_discover, SERIF_TITLE, serif = true)
        setWindowed(column(Gravity.START).apply {
            setPadding(dp(36), dp(20), dp(36), dp(16))
            addView(title)
            addView(bar, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8); bottomMargin = dp(14) })
            addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
        })
        controls()
        picker.requestFocus()
        fun load() {
            results.removeAllViews()
            results.addView(text(R.string.player_loading, BODY, color = TEXT_2))
            background({ Stremio.addons(key, refresh = true) }) { answer ->
                results.removeAllViews()
                answer.onSuccess {
                    catalogs = it.flatMap { addon -> addon.catalogs }.distinctBy { catalog -> catalog.key }
                    val pinned = Settings.pinnedCatalog(this)
                    val catalog = catalogs.firstOrNull { it.key == pinned }
                        ?: catalogs.firstOrNull { it.type == "movie" || it.type == "series" }
                        ?: catalogs.firstOrNull()
                    if (catalog == null) results.addView(text(R.string.discover_no_catalogs, BODY))
                    else select(catalog)
                }.onFailure { error ->
                    val card = failed(failureMessage(R.string.catalog_incomplete, failureOf(error))) { load() }
                    results.addView(card)
                    card.getChildAt(2).requestFocus()
                }
            }
        }
        load()
    }

    private fun controls() {
        val catalog = selected
        picker.text = catalog?.name ?: getString(R.string.discover_catalog)
        picker.maxWidth = dp(300)
        picker.maxLines = 1
        picker.ellipsize = TextUtils.TruncateAt.END
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
        next = 0
        row = null
        footer = null
        results.removeAllViews()
        scroll.scrollTo(0, 0)
        results.addView(text(R.string.player_loading, BODY, color = TEXT_2))
        background({
            val first = catalog.addon.filteredCatalog(catalog, selection)
            val pager = CatalogPager(listOf(first), listOf(catalog.pages), { meta -> meta.type to meta.id },
                { meta -> !isAddonMessage(meta.name) }) { _, skip -> catalog.addon.filteredCatalog(catalog, selection, skip) }
            val items = pager.slice(0, PAGE_SIZE)
            Triple(pager, items, pager.hasMore(items.size))
        }) { result ->
            if (token != request) return@background
            results.removeAllViews()
            result.onSuccess { (pager, items, hasMore) ->
                if (items.isEmpty()) results.addView(text(R.string.discover_no_titles, BODY, color = TEXT_2))
                else append(items)
                if (hasMore) more(pager, token)
            }.onFailure { error ->
                results.addView(failed(failureMessage(R.string.catalog_incomplete, failureOf(error))) { select(catalog, selection) })
            }
        }
    }

    private fun more(pager: CatalogPager<Meta, Pair<String, String>>, token: Int) {
        val load = pill(R.string.discover_load_more)
        footer = load
        results.addView(load, wrap().apply { topMargin = dp(12) })
        load.setOnClickListener {
            if (load.text == getString(R.string.player_loading)) return@setOnClickListener
            load.setText(R.string.player_loading)
            val from = next
            background({
                val items = pager.slice(from, PAGE_SIZE)
                items to pager.hasMore(from + items.size)
            }) { result ->
                if (token != request || footer !== load) return@background
                result.onSuccess { (items, hasMore) ->
                    val focused = load.isFocused
                    results.removeView(load)
                    footer = null
                    val first = append(items)
                    if (hasMore) more(pager, token)
                    if (focused) (first ?: row?.getChildAt((row?.childCount ?: 1) - 1) ?: picker).requestFocus()
                }.onFailure { error ->
                    load.text = failureMessage(R.string.action_try_again, failureOf(error))
                }
            }
        }
    }

    private fun append(items: List<Meta>): View? {
        var first: View? = null
        val columns = columns
        items.forEach { meta ->
            if (next % columns == 0) {
                row = LinearLayout(this).also { results.addView(it, LinearLayout.LayoutParams(-1, -2)) }
            }
            val poster = ImageView(this).apply {
                scaleType = ImageView.ScaleType.CENTER_CROP
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
                Images.load(meta.poster ?: meta.background, this, dp(120))
            }
            val name = text(meta.name, SMALL).apply {
                maxLines = 2
                ellipsize = TextUtils.TruncateAt.END
                minLines = 2
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
                setPadding(dp(4), dp(5), dp(4), dp(3))
            }
            val card = column(Gravity.START, poster, name).apply {
                poster.layoutParams = LinearLayout.LayoutParams(-1, dp(147))
                setPadding(dp(4), dp(4), dp(4), dp(2))
                contentDescription = meta.name
                rowFocus(8)
                setOnClickListener { openCatalogTitle(meta) }
            }
            row?.addView(card, LinearLayout.LayoutParams(dp(114), -2).apply { marginEnd = dp(8); bottomMargin = dp(6) })
            if (first == null) first = card
            next++
        }
        return first
    }

    private companion object { const val PAGE_SIZE = 24 }
}
