package dev.alder

import org.json.JSONObject

internal data class Pick(val key: String, val label: String)
internal data class Person(val id: Int, val name: String, val role: String, val photo: String?)
/** The filter panel's rows, top to bottom. Sort isn't one: it lives on the results heading. */
internal enum class Facet(val title: Int) {
    GENRE(R.string.discover_genre), YEAR(R.string.discover_year), PEOPLE(R.string.ex_people),
    STUDIO(R.string.ex_studio), LANGUAGE(R.string.group_language)
}

internal data class ExploreQuery(
    val type: String = "movie", val genres: List<Pick> = emptyList(), val any: Boolean = false,
    val person: Person? = null, val choices: Map<Facet, Pick> = emptyMap(), val sort: String = "popular"
) {
    val movie get() = type == "movie"
    val media get() = if (movie) "movie" else "tv"

    fun sortOptions() = buildList {
        add("popular" to R.string.discover_sort_popular)
        add("top" to R.string.discover_sort_top)
        add("new" to R.string.discover_sort_new)
        add("old" to R.string.ex_old)
        add("votes" to R.string.showing_most_watched)
        if (movie) add("box" to R.string.showing_blockbusters)
    }

    fun without(f: Facet) = when (f) {
        Facet.PEOPLE -> copy(person = null)
        Facet.GENRE -> copy(genres = emptyList())
        else -> copy(choices = choices - f)
    }

    fun discover(): DiscoverQuery {
        val date = if (movie) "primary_release_date" else "first_air_date"
        val params = buildMap {
            if (genres.isNotEmpty()) put("with_genres", genres.joinToString(if (any) "|" else ",") { it.key })
            choices[Facet.YEAR]?.key?.split(":")?.let {
                if (it.first().isNotEmpty()) put("$date.gte", it.first() + "-01-01")
                put("$date.lte", it.last() + "-12-31")
            }
            choices[Facet.STUDIO]?.let { put(if (movie) "with_companies" else "with_networks", it.key) }
            choices[Facet.LANGUAGE]?.let { put("with_original_language", it.key) }
            val narrowed = isNotEmpty() || person != null
            val sortFloor = when (sort) {
                "top" -> if (movie) (if (narrowed) 500 else 3000) else (if (narrowed) 200 else 1000)
                "old" -> if (movie) 1000 else 100
                else -> 0
            }
            if (sortFloor > 0 && (movie || person == null)) put("vote_count.gte", sortFloor.toString())
            if (movie && person != null) put(when(person.role) {
                "actor" -> "with_cast"
                "any" -> "with_people"
                else -> "with_crew"
            }, person.id.toString())
        }
        return DiscoverQuery(type, sort = sort, filters = params)
    }
}

internal object ExploreCatalogue {
    data class Page(val titles: List<JSONObject>, val count: Int, val last: Boolean)

    fun people(query: String): List<Person> {
        val list = Tmdb.exploreRequest("search/person", mapOf("query" to query)).optJSONArray("results").objects()
        return list.sortedByDescending { it.optDouble("popularity", 0.0) }.map {
            Person(it.getInt("id"), it.getString("name"), when(it.text("known_for_department")) {
                "Acting" -> "actor"; "Writing" -> "writer"; "Directing" -> "director"; else -> "any"
            }, it.text("profile_path"))
        }
    }

    /**
     * Popular Now: the top-billed cast (and a film's director) of the catalogue's current popular titles, in the titles'
     * order. TMDb's own trending people led with names few viewers know. [loaded]: the catalogue's own first page of
     * those titles, when it has one, so only the credits are asked (on Tmdb's pool, cached for a day).
     */
    fun popularPeople(movie: Boolean, loaded: List<JSONObject>?): List<Person> {
        val media = if (movie) "movie" else "tv"
        val titles = (loaded ?: Tmdb.discoverPage(ExploreQuery(type = if (movie) "movie" else "series").discover(), 1)
            .optJSONArray("results").objects()).take(POPULAR_TITLES)
        val credits = Tmdb.pool.invokeAll(titles.map { t -> java.util.concurrent.Callable {
            runCatching { Tmdb.exploreRequest("$media/${t.getInt("id")}/credits") }.getOrNull()
        } }).map { runCatching { it.get() }.getOrNull() }
        return credits.filterNotNull().flatMap { c ->
            c.optJSONArray("cast").objects().take(2).map { Person(it.getInt("id"), it.getString("name"), "actor", it.text("profile_path")) } +
                c.optJSONArray("crew").objects().filter { it.text("job") == "Director" }.take(1)
                    .map { Person(it.getInt("id"), it.getString("name"), "director", it.text("profile_path")) }
        }.filter { it.photo != null }.distinctBy { it.id }.take(18)
    }

    private const val POPULAR_TITLES = 12

    private fun credits(q: ExploreQuery): List<JSONObject> {
        val person = requireNotNull(q.person)
        val data = Tmdb.exploreRequest("person/${person.id}/${q.media}_credits")
        val cast = if (person.role in listOf("actor", "any")) data.optJSONArray("cast").objects() else emptyList()
        val crew = data.optJSONArray("crew").objects().filter {
            when(person.role) {
                "writer" -> it.text("department") == "Writing"
                "director" -> it.text("job") in listOf("Director", "Series Director")
                else -> person.role == "any"
            }
        }
        // Guest spots (under 5 episodes) drop out for actors only: a show's directors and writers often have 1–4.
        return (cast.filter { q.movie || it.optInt("episode_count", 5) >= 5 } + crew).distinctBy { it.getInt("id") }
    }

    internal fun localOrder(rows: List<JSONObject>, q: ExploreQuery): List<JSONObject> {
        val date = if (q.movie) "release_date" else "first_air_date"
        return when (q.sort) {
            "new" -> rows.sortedByDescending { it.optString(date) }
            "old" -> rows.sortedBy { it.text(date) ?: "9999" }
            "votes" -> rows.sortedByDescending { it.optInt("vote_count") }
            "top" -> rows.sortedWith(compareByDescending<JSONObject> { it.optInt("vote_count") >= 100 }
                .thenByDescending { it.optDouble("vote_average",0.0) })
            else -> rows.sortedByDescending { it.optDouble("popularity",0.0) }
        }
    }

    fun page(q: ExploreQuery, page: Int): Page {
        if (!q.movie && q.person != null) {
            val params = q.discover().filters
            val rows = credits(q).filter {
                val genres = it.optJSONArray("genre_ids").let { ids -> (0 until (ids?.length() ?: 0)).map { i -> ids!!.getInt(i).toString() } }
                val date = it.optString("first_air_date")
                (q.genres.isEmpty() || if (q.any) q.genres.any { g -> g.key in genres } else q.genres.all { g -> g.key in genres }) &&
                    date >= params["first_air_date.gte"].orEmpty() && date <= (params["first_air_date.lte"] ?: "9999") &&
                    (params["with_original_language"]?.equals(it.text("original_language")) != false)
            }
            val sorted = localOrder(rows,q)
            return Page(sorted.drop((page-1)*20).take(20), sorted.size, page*20 >= sorted.size)
        }
        val answer = Tmdb.discoverPage(q.discover(), page)
        val titles = answer.optJSONArray("results").objects()
        if (q.movie && q.person?.role in listOf("director", "writer")) {
            val ids = credits(q).map { it.getInt("id") }.toSet()
            return Page(titles.filter { it.getInt("id") in ids }, -1, page >= minOf(500, answer.optInt("total_pages")))
        }
        return Page(titles, answer.optInt("total_results"), page >= minOf(500, answer.optInt("total_pages")))
    }

    /** A title's details for the info panel and OK: IMDb id, cast, certificate. Its logo comes by IMDb id (Images.logoFor). */
    fun details(meta: Meta): JSONObject {
        val media = if (meta.type == "movie") "movie" else "tv"
        val cert = if (media == "movie") "release_dates" else "content_ratings"
        return Tmdb.exploreRequest("$media/${meta.id.substringAfter(':')}", mapOf("append_to_response" to "external_ids,credits,$cert"))
    }
}
