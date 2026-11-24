package dev.alder

import android.content.Intent
import android.graphics.Color
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.widget.*
import java.util.Locale
import org.json.JSONArray
import org.json.JSONObject

/** The catalogue and its filter panel live in Search's results area. */
abstract class ExploreScreen : Screen() {
    protected abstract val results: LinearLayout
    protected abstract val resultsScroll: ScrollView
    protected abstract fun lookup(facet: String?, restoreTitle: Boolean = true)
    protected abstract fun refreshCatalogue()
    private var query = ExploreQuery()
    protected lateinit var panel: LinearLayout
    protected lateinit var peopleButton: View
    protected lateinit var panelScroll: ScrollView
    protected lateinit var summary: TextView
    private val labels = mutableMapOf<Facet, TextView>()
    private lateinit var clearFilters: View
    private val typeButtons = mutableListOf<TextView>()
    private var genreNames = emptyMap<Int,String>()
    private var loadedBand = -1
    @Volatile private var generation = 0
    private val catalogueCards = mutableListOf<MediaCardView>()
    private var preview: View? = null
    private var pending: Runnable? = null
    private var delayed = Runnable { refreshCatalogue() }
    protected val heading: String get() = query.collection?.let { it.label + DOT + value(Facet.SORT) }
        ?: value(Facet.SORT) + " " + getString(if (query.movie) R.string.tab_movies else R.string.tab_shows)

    private fun title(f: Facet) = getString(if (f == Facet.STUDIO && !query.movie) R.string.ex_network else f.title)
    private fun value(f: Facet): String = when (f) {
        Facet.PEOPLE -> query.person?.name
        Facet.GENRE -> query.genres.joinToString(if (query.any) " ${getString(R.string.ex_or)} " else " + ") { it.label }.ifEmpty { null }
        Facet.FRANCHISE -> query.collection?.label
        Facet.SORT -> query.choices[f]?.label ?: getString(if (query.collection==null) R.string.discover_sort_popular else R.string.ex_order)
        else -> query.choices[f]?.label
    } ?: getString(R.string.filter_all)

    protected fun buildPanel(): ScrollView {
        panel = column(Gravity.START).apply {
            background = rounded(0xff191715.toInt(), 12)
            setPadding(dp(6),dp(5),dp(6),dp(6))
        }
        val types = LinearLayout(this)
        listOf("movie" to R.string.tab_movies, "series" to R.string.tab_shows).forEach { (type, name) ->
            val b = text(name, SMALL, bold = true).apply {
                gravity = Gravity.CENTER
                rowFocus(rest = { if (query.type == type) rounded(0xff49423c.toInt(), 14) else null })
                setOnClickListener {
                    if (query.type != type) {
                        genreNames = emptyMap()
                        val validGenres = HomeStyle.genres(type=="movie").map { it.first.toString() }.toSet()
                        val choices = query.choices - Facet.STUDIO - Facet.RUNTIME
                        change(query.copy(type=type, genres=query.genres.filter { it.key in validGenres },
                            choices=if (query.sort=="box") choices-Facet.SORT else choices))
                    }
                }
            }
            typeButtons += b
            types.addView(b, LinearLayout.LayoutParams(0,dp(29),1f))
        }
        panel.addView(types)
        Facet.values().forEach { f ->
            if (f == Facet.FRANCHISE) panel.addView(View(this).apply { setBackgroundColor(0xff4b4540.toInt()) }, -1,dp(1))
            val key = text(title(f), SMALL, bold = true)
            val label = text("", HomeStyle.INFO, color = HomeStyle.PLOT).apply {
                gravity = Gravity.END or Gravity.CENTER_VERTICAL
                isSingleLine = true
                ellipsize = TextUtils.TruncateAt.END
            }
            val row = LinearLayout(this).apply {
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(8),0,dp(8),0)
                addView(key)
                addView(label, LinearLayout.LayoutParams(0,-1,1f).apply { marginStart = dp(5) })
                rowFocus(8, also = { on ->
                    if (on) background = rounded(Color.WHITE,8)
                    key.setTextColor(if (on) Color.BLACK else Color.WHITE)
                    label.setTextColor(if (on) Color.BLACK else HomeStyle.PLOT)
                })
                setOnClickListener { open(f) }
            }
            panel.addView(row, -1, dp(31))
            labels[f] = label
            if (f == Facet.PEOPLE) peopleButton = row
        }
        clearFilters = pill(R.string.ex_clear_filters).apply {
            setOnClickListener {
                peopleButton.requestFocus()
                change(ExploreQuery(type=query.type, choices=query.choices.filterKeys { it==Facet.SORT }))
            }
        }
        panel.addView(clearFilters,-1,dp(30))
        updatePanel()
        return ScrollView(this).apply { isVerticalScrollBarEnabled = false; addView(panel) }.also { panelScroll = it }
    }

    private fun updatePanel() {
        clearFilters.visibility = if (query.collection == null && Facet.values().any(::active)) View.VISIBLE else View.GONE
        typeButtons.forEachIndexed { i,b ->
            b.isEnabled = query.collection == null
            b.isFocusable = b.isEnabled
            b.alpha = if (b.isEnabled) 1f else .38f
            if (!b.hasFocus()) b.background = if ((i==0)==query.movie) rounded(0xff49423c.toInt(),14) else null
        }
        labels.forEach { (f,t) ->
            val row = t.parent as LinearLayout
            (row.getChildAt(0) as TextView).text = title(f)
            t.text = value(f)
            val enabled = if (f == Facet.SORT) true else if (f == Facet.FRANCHISE) query.movie else query.collection == null &&
                (query.movie || query.person == null || f !in listOf(Facet.RUNTIME, Facet.STUDIO))
            row.isEnabled = enabled
            row.isFocusable = enabled
            row.alpha = if (enabled) 1f else .38f
        }
        if (::summary.isInitialized) summary.text = Facet.values().filter { active(it) }.joinToString(DOT) { value(it) }
            .ifBlank { getString(R.string.ex_filters) + " · " + getString(R.string.filter_all) }
    }

    private fun active(f: Facet) = query.without(f) != query && f != Facet.SORT &&
        (query.collection == null || f == Facet.FRANCHISE) && !(query.person != null && !query.movie && f in listOf(Facet.RUNTIME,Facet.STUDIO))

    private fun change(q: ExploreQuery) {
        query = q
        invalidateCatalogue()
        updatePanel()
        main.postDelayed(delayed, 300)
    }

    private fun chooseNear(name: String, options: List<String>, checked: Int = -1, pick: (Int)->Unit) =
        choose(name, options, checked = checked, widthDp = 205, anchorStartDp = 241, pick = pick)

    private fun open(f: Facet) {
        when(f) {
            Facet.PEOPLE -> lookup("person")
            Facet.GENRE -> {
                if (genreNames.isNotEmpty()) return genres(genreNames.toList(),0)
                val media = query.media
                background({ Tmdb.genres(media) }) { answer ->
                    if (query.media == media && hasWindowFocus()) answer.fold({ list ->
                        genreNames = list.toMap()
                        genres(list, 0)
                    }, { toastFailure() })
                }
            }
            Facet.YEAR -> years()
            Facet.FRANCHISE -> lookup("collection")
            else -> {
                val options = options(f)
                val all = f != Facet.SORT
                chooseNear(title(f), (if (all) listOf(getString(R.string.filter_all)) else emptyList()) + options.map { it.label },
                    if (all) options.indexOfFirst { it.key == query.choices[f]?.key }+1 else options.indexOfFirst { it.key == query.sort }) { index ->
                    val option = options.getOrNull(index - if (all) 1 else 0)
                    change(if (option == null) query.without(f) else query.copy(choices = query.choices + (f to option)))
                }
            }
        }
    }

    private fun genres(list: List<Pair<Int,String>>, focus: Int) {
        val mode = getString(if (query.any) R.string.ex_any else R.string.filter_all)
        val choices = listOf(getString(R.string.ex_match) + " · " + mode, getString(R.string.filter_all)) + list.map { it.second }
        val ticks = list.indices.filter { i -> query.genres.any { it.key == list[i].first.toString() } }.map { it+2 }.toSet()
        choose(title(Facet.GENRE), choices, focus = focus, widthDp = 205, anchorStartDp = 241,
            checkedRows = ticks.ifEmpty { setOf(1) }) { i ->
            val pick = list.getOrNull(i-2)?.let { Pick(it.first.toString(), it.second) }
            change(when {
                i == 0 -> query.copy(any = !query.any)
                pick == null -> query.without(Facet.GENRE)
                pick in query.genres -> query.copy(genres = query.genres - pick)
                else -> query.copy(genres = query.genres + pick)
            })
            genres(list,i)
        }
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
            listOf(getString(R.string.ex_before),getString(R.string.ex_range)),widthDp = 205,anchorStartDp = 241,
            right = { i -> decades.getOrNull(i-1)?.let { { singles(it) } } }) { i ->
            when(i) {
                0 -> change(query.without(Facet.YEAR))
                decades.size+1 -> set(null,1969,getString(R.string.ex_before))
                decades.size+2 -> {
                    val from = (now downTo 1870).toList()
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
        Facet.SORT -> (listOf("popular" to R.string.discover_sort_popular,"top" to R.string.discover_sort_top,
            "new" to R.string.discover_sort_new,"old" to R.string.ex_old,"votes" to R.string.ex_votes,"az" to R.string.list_sort_name) +
            if (query.movie && query.collection == null) listOf("box" to R.string.ex_box) else emptyList()).let { sorts ->
                if (query.collection == null) sorts else listOf("release" to R.string.ex_order) + sorts.filterNot { it.first == "old" }
            }.map { Pick(it.first,HomeStyle.titleCase(getString(it.second))) }
        Facet.RATING -> listOf("6","7","7.5","8").map { Pick(it,"$it+") }
        Facet.STUDIO -> if (query.movie) listOf(Pick("41077","A24"),Pick("420","Marvel Studios"),Pick("3","Pixar"),
            Pick("2","Walt Disney Pictures"),Pick("174","Warner Bros."),Pick("33","Universal Pictures"),Pick("4","Paramount Pictures"),
            Pick("521","DreamWorks Animation"),Pick("10342","Studio Ghibli"),Pick("35","Lionsgate"),Pick("3172","Blumhouse"))
            else HomeStyle.platforms.map { Pick(it.networks,it.name) }
        Facet.LANGUAGE -> listOf("en","ko","ja","es","fr","hi","de","it","pt","ar","zh").map { Pick(it,Locale.forLanguageTag(it).displayLanguage) }
        Facet.COUNTRY -> listOf("US","GB","KR","JP","IN","FR","ES","DE","IT","BR","CA","AU","CN").map { Pick(it,Locale.Builder().setRegion(it).build().displayCountry) }
        Facet.RUNTIME -> (if (query.movie) listOf("" to "89","90" to "119","120" to "150","151" to "")
            else listOf("" to "29","30" to "60","61" to "")).map { (a,b) ->
                val label = getString(R.string.info_runtime_minutes,(b.ifEmpty { a }).toInt())
                Pick("$a:$b", when { a.isEmpty() -> "≤ $label"; b.isEmpty() -> "≥ $label"; else -> "$a–$label" })
            }
        else -> emptyList()
    }

    protected fun invalidateCatalogue() {
        generation++
        main.removeCallbacks(delayed)
        closePreview()
    }

    private fun closePreview() {
        pending?.let(main::removeCallbacks)
        pending = null
        preview?.let { results.removeView(it) }
        preview = null
    }

    private fun toastFailure() = Toast.makeText(this,R.string.ex_unavailable,Toast.LENGTH_SHORT).show()

    protected fun clearGrid() {
        invalidateCatalogue()
        catalogueCards.forEach { it.unload() }
        catalogueCards.clear()
        loadedBand = -1
    }

    protected fun catalogue() {
        val g = generation
        val q = query
        updatePanel()
        val seen = mutableSetOf<String>()
        var next = 1
        var done = false
        var loading = false
        var row: LinearLayout? = null
        val width = ((resources.displayMetrics.widthPixels - dp(288)) / 5 - dp(6)).coerceAtLeast(dp(48))
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
                    if (next == 2 && page.count >= 0) summary.append(" · " + java.text.NumberFormat.getIntegerInstance().format(page.count))
                    val before = seen.size
                    page.titles.forEach { json ->
                        val meta = discoverMeta(json,"tmdb:${json.getInt("id")}",q.movie,names)
                        if (!seen.add(meta.id)) return@forEach
                        if (row == null || row!!.childCount == 5) {
                            row = LinearLayout(this).also { results.addView(it) }
                        }
                        val band = row!!
                        val card = MediaCardView(this,meta.name,meta.poster,null,widthPx = width,heightPx = width*3/2,
                            dimAlpha = 0f,fadeIn = false,home = true)
                        val bandIndex = catalogueCards.size / 5
                        catalogueCards += card
                        band.addView(card,LinearLayout.LayoutParams(width,width*3/2).apply { setMargins(dp(3),dp(4),dp(3),dp(4)) })
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
                                band.post { if (card.hasFocus()) resultsScroll.scrollTo(0,band.top) }
                                pending = Runnable { if (card.hasFocus() && hasWindowFocus() && g == generation) drawer(card,band,meta) }
                                    .also { main.postDelayed(it,HomeStyle.WASH_WAIT) }
                                if (results.indexOfChild(band) >= results.childCount-2) more()
                            } else closePreview()
                        }
                        fun resolve(save: Boolean) {
                            background({ ExploreCatalogue.details(meta).optJSONObject("external_ids")?.text("imdb_id") }) { answer ->
                                if (generation != g || !card.hasFocus() || !hasWindowFocus()) return@background
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
                    if (before == seen.size && !done) more()
                    else if (seen.isEmpty()) emptySuggestions(g,q)
                }, {
                    results.addView(failed(getString(R.string.search_popular_unavailable)) { refreshCatalogue() })
                })
            }
        }
        if (genreNames.isEmpty()) background({ Tmdb.genres(q.media).toMap() }) { answer ->
            if (query.media == q.media) answer.getOrNull()?.let { genreNames = it }
        }
        more()
    }

    private fun emptySuggestions(g: Int,q: ExploreQuery) {
        results.addView(text(R.string.discover_empty,BODY))
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
                    results.addView(pill("$name   $count").apply { setOnClickListener { peopleButton.requestFocus(); change(alternative) } })
                }
            }
        }
    }

    private fun drawer(card: View, band: LinearLayout, meta: Meta) {
        val frame = FrameLayout(this)
        val art = FadedArt(this).apply { scaleType = ImageView.ScaleType.CENTER_CROP }
        frame.addView(art, FrameLayout.LayoutParams(dp(290),-1,Gravity.END))
        Images.load(meta.background?.replace("/w1280/","/w780/"),art,dp(290))
        val info = column(Gravity.START).apply { setPadding(dp(9),dp(4),dp(12),0) }
        frame.addView(info, FrameLayout.LayoutParams((results.width*.68f).toInt(),-1))
        val titleSlot = FrameLayout(this)
        val name = text(meta.name,BODY+2,bold=true).apply { isSingleLine=true; ellipsize=TextUtils.TruncateAt.END }
        titleSlot.addView(name)
        info.addView(titleSlot,-1,dp(32))
        val facts = text(listOfNotNull(meta.year,*meta.genres.toTypedArray()).joinToString(DOT),SMALL).apply { isSingleLine=true }
        info.addView(facts,-1,dp(20))
        val scores = LinearLayout(this)
        info.addView(scores,-1,dp(22))
        info.addView(text(meta.description.orEmpty(),SMALL,color=TEXT_2).apply {
            maxLines=3; ellipsize=TextUtils.TruncateAt.END
        },-1,dp(50))
        val chips = LinearLayout(this)
        info.addView(chips,-1,dp(24))
        preview = frame
        results.addView(frame,results.indexOfChild(band)+1,LinearLayout.LayoutParams(-1,dp(155)))
        background({ ExploreCatalogue.details(meta) }) { answer ->
            if (preview !== frame || !card.hasFocus() || !hasWindowFocus()) return@background
            val d = answer.getOrNull() ?: return@background
            val logo = d.optJSONObject("images")?.optJSONArray("logos").objects().firstNotNullOfOrNull { it.text("file_path") }
            if (logo != null) {
                val image = ImageView(this).apply { scaleType = ImageView.ScaleType.FIT_START }
                titleSlot.addView(image,-1,-1)
                Images.load("https://image.tmdb.org/t/p/w300$logo",image,dp(150),done={name.visibility=View.INVISIBLE})
            }
            val certificate = if (meta.type=="movie") d.optJSONObject("release_dates")?.optJSONArray("results").objects()
                .firstOrNull { it.text("iso_3166_1")=="US" }?.optJSONArray("release_dates").objects().firstNotNullOfOrNull { it.text("certification") }
                else d.optJSONObject("content_ratings")?.optJSONArray("results").objects().firstOrNull { it.text("iso_3166_1")=="US" }?.text("rating")
            val duration = if (meta.type=="movie") d.optInt("runtime").takeIf { it>0 }?.let { getString(R.string.info_runtime_minutes,it) }
                else d.optInt("number_of_seasons").takeIf { it>0 }?.let { resources.getQuantityString(R.plurals.seasons,it,it) }
            facts.text = (listOfNotNull(certificate,meta.year)+d.optJSONArray("genres").objects().take(2).map { it.optString("name") }+listOfNotNull(duration)).joinToString(DOT)
            val people = listOfNotNull(query.person?.takeIf { query.collection==null }?.let { it.name to it.photo }) +
                d.optJSONObject("credits")?.optJSONArray("cast").objects().mapNotNull { member ->
                    member.text("name")?.let { it to member.text("profile_path") }
                }
            people.distinctBy { it.first }.take(3).forEach { (person,photo) ->
                val chip = LinearLayout(this).apply {
                    gravity = Gravity.CENTER_VERTICAL
                    background = rounded(0xff322f2d.toInt(),12)
                    setPadding(dp(2),0,dp(6),0)
                }
                if (photo != null) {
                    val face = ImageView(this).apply {
                        scaleType = ImageView.ScaleType.CENTER_CROP
                        background = rounded(0xff322f2d.toInt(),12)
                        clipToOutline = true
                    }
                    chip.addView(face,dp(18),dp(18))
                    Images.load("https://image.tmdb.org/t/p/w185$photo",face,dp(18))
                }
                chip.addView(text(person,HomeStyle.INFO).apply { isSingleLine=true; setPadding(dp(3),0,0,0) })
                chips.addView(chip,LinearLayout.LayoutParams(-2,dp(22)).apply { marginEnd=dp(4) })
            }
            val id = d.optJSONObject("external_ids")?.text("imdb_id") ?: return@background
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
            }) { if (preview === frame) addRatingLogos(scores,it.getOrNull().orEmpty()) }
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

    protected fun findChoices(kind: String, words: String) {
        val g = generation
        results.removeAllViews()
        val all = pill(R.string.filter_all).apply { setOnClickListener {
            query = query.without(if(kind=="person") Facet.PEOPLE else Facet.FRANCHISE)
            lookup(null,restoreTitle=false)
        } }
        results.addView(all)
        if (kind=="collection") {
            fun show(items: List<Pick>) {
                items.forEach { pick -> results.addView(pill(pick.label).apply {
                    setOnClickListener { query = query.copy(collection=pick,choices=query.choices-Facet.SORT); lookup(null,restoreTitle=false) }
                }) }
            }
            if (words.isEmpty()) show(collections) else background({
                Tmdb.exploreRequest("search/collection",mapOf("query" to words),cache=true).optJSONArray("results").objects()
                    .map { Pick(it.getInt("id").toString(),it.getString("name")) }
            }) { if (g==generation) it.fold(::show,{toastFailure()}) }
            return
        }
        fun faces(label: String, people: List<Person>) {
            if (people.isEmpty()) return
            results.addView(text(label,BODY,bold=true))
            people.take(20).chunked(5).forEach { group ->
                val row = LinearLayout(this)
                results.addView(row)
                group.forEach { p ->
                    val tile = column(Gravity.CENTER_HORIZONTAL).apply {
                        rowFocus()
                        setPadding(dp(4),dp(4),dp(4),dp(3))
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
                        background=rounded(0xff302d2b.toInt(),40); clipToOutline=true
                    }
                    tile.addView(face,dp(62),dp(62))
                    Images.load(p.photo?.let { "https://image.tmdb.org/t/p/w185$it" },face,dp(62))
                    tile.addView(text(p.name,SMALL).apply { gravity=Gravity.CENTER; maxLines=2 },-1,dp(34))
                    row.addView(tile,LinearLayout.LayoutParams((resources.displayMetrics.widthPixels-dp(285))/5,-2))
                }
            }
        }
        if (words.isEmpty()) {
            val recent = recentPeople()
            faces(getString(R.string.search_recent),recent)
            if (recent.none { it.id == 1253360 }) {
                faces(getString(R.string.ex_people),listOf(Person(1253360,"Pedro Pascal","actor","/oKcMbVn0NJTNzQt0ClKKvVXkm60.jpg")))
            }
        }
        background({ ExploreCatalogue.people(words) }) {
            if (g==generation) it.fold({ found -> faces(if (words.isBlank()) getString(R.string.ex_popular) else words,found) },{toastFailure()})
        }
    }

    private val collections = listOf(
        Pick("10","Star Wars"),Pick("1241","Harry Potter"),Pick("645","James Bond"),Pick("87359","Mission: Impossible"),
        Pick("9485","Fast & Furious"),Pick("119","The Lord of the Rings"),Pick("404609","John Wick"),Pick("328","Jurassic Park"),
        Pick("10194","Toy Story"),Pick("84","Indiana Jones"),Pick("295","Pirates of the Caribbean"),Pick("263","The Dark Knight"),
        Pick("1575","Rocky"),Pick("230","The Godfather"),Pick("264","Back to the Future"),Pick("2344","The Matrix"),
        Pick("2150","Shrek"),Pick("86066","Despicable Me"),Pick("173710","Planet of the Apes"),Pick("726871","Dune"),
        Pick("556","Spider-Man"),Pick("8091","Alien"),Pick("86311","The Avengers")
    )
}
