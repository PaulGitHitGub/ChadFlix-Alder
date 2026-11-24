package dev.alder

import org.json.JSONObject

internal data class Pick(val key: String, val label: String)
internal data class Person(val id: Int, val name: String, val role: String, val photo: String?)
internal enum class Facet(val title: Int) {
    PEOPLE(R.string.ex_people), GENRE(R.string.discover_genre), YEAR(R.string.discover_year),
    RATING(R.string.ex_rating), SORT(R.string.list_sort), STUDIO(R.string.ex_studio),
    RUNTIME(R.string.ex_runtime), LANGUAGE(R.string.group_language), COUNTRY(R.string.ex_country),
    FRANCHISE(R.string.ex_franchise)
}

internal data class ExploreQuery(
    val type: String = "movie", val genres: List<Pick> = emptyList(), val any: Boolean = false,
    val person: Person? = null, val collection: Pick? = null, val choices: Map<Facet, Pick> = emptyMap()
) {
    val movie get() = type == "movie"
    val media get() = if (movie) "movie" else "tv"
    val sort get() = choices[Facet.SORT]?.key ?: if (collection == null) "popular" else "release"

    fun without(f: Facet) = when (f) {
        Facet.PEOPLE -> copy(person = null)
        Facet.GENRE -> copy(genres = emptyList())
        Facet.FRANCHISE -> copy(collection = null, choices = if (sort == "release") choices - Facet.SORT else choices)
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
            choices[Facet.RATING]?.let {
                put("vote_average.gte", it.key)
                put("vote_count.gte", if (movie) "200" else "100")
            }
            choices[Facet.STUDIO]?.let { put(if (movie) "with_companies" else "with_networks", it.key) }
            choices[Facet.RUNTIME]?.key?.split(":")?.let {
                if (it.first().isNotEmpty()) put("with_runtime.gte", it.first())
                if (it.last().isNotEmpty()) put("with_runtime.lte", it.last())
            }
            choices[Facet.LANGUAGE]?.let { put("with_original_language", it.key) }
            choices[Facet.COUNTRY]?.let { put("with_origin_country", it.key) }
            val narrowed = isNotEmpty() || person != null
            val sortFloor = when (sort) {
                "top" -> if (movie) (if (narrowed) 500 else 3000) else (if (narrowed) 200 else 1000)
                "old", "az" -> if (movie) 1000 else 100
                else -> 0
            }
            if (sortFloor > 0 && (movie || person == null)) {
                put("vote_count.gte", maxOf(sortFloor,get("vote_count.gte")?.toInt() ?: 0).toString())
            }
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
        val result = if (query.isBlank()) Tmdb.exploreRequest("trending/person/week", cache = true)
            else Tmdb.exploreRequest("search/person", mapOf("query" to query), cache = true)
        val list = result.optJSONArray("results").objects()
        return (if (query.isBlank()) list else list.sortedByDescending { it.optDouble("popularity", 0.0) }).map {
            Person(it.getInt("id"), it.getString("name"), when(it.text("known_for_department")) {
                "Acting" -> "actor"; "Writing" -> "writer"; "Directing" -> "director"; else -> "any"
            }, it.text("profile_path"))
        }
    }

    private fun credits(q: ExploreQuery): List<JSONObject> {
        val person = requireNotNull(q.person)
        val data = Tmdb.exploreRequest("person/${person.id}/${q.media}_credits", cache = true)
        val cast = if (person.role in listOf("actor", "any")) data.optJSONArray("cast").objects() else emptyList()
        val crew = data.optJSONArray("crew").objects().filter {
            when(person.role) {
                "writer" -> it.text("department") == "Writing"
                "director" -> it.text("job") in listOf("Director", "Series Director")
                else -> person.role == "any"
            }
        }
        return (cast + crew).filter { q.movie || it.optInt("episode_count", 5) >= 5 }.distinctBy { it.getInt("id") }
    }

    internal fun localOrder(rows: List<JSONObject>, q: ExploreQuery): List<JSONObject> {
        val date = if (q.movie) "release_date" else "first_air_date"
        return when (q.sort) {
            "new" -> rows.sortedByDescending { it.optString(date) }
            "release", "old" -> rows.sortedBy { it.text(date) ?: "9999" }
            "az" -> rows.sortedWith(compareBy(java.text.Collator.getInstance()) { it.optString(if (q.movie) "title" else "name") })
            "votes" -> rows.sortedByDescending { it.optInt("vote_count") }
            "top" -> rows.sortedWith(compareByDescending<JSONObject> { it.optInt("vote_count") >= 100 }
                .thenByDescending { it.optDouble("vote_average",0.0) })
            else -> rows.sortedByDescending { it.optDouble("popularity",0.0) }
        }
    }

    fun page(q: ExploreQuery, page: Int): Page {
        q.collection?.let {
            val parts = Tmdb.exploreRequest("collection/${it.key}", cache = true).optJSONArray("parts").objects()

            return Page(localOrder(parts,q), parts.size, true)
        }
        if (!q.movie && q.person != null) {
            val params = q.discover().filters
            val rows = credits(q).filter {
                val genres = it.optJSONArray("genre_ids").let { ids -> (0 until (ids?.length() ?: 0)).map { i -> ids!!.getInt(i).toString() } }
                val date = it.optString("first_air_date")
                (q.genres.isEmpty() || if (q.any) q.genres.any { g -> g.key in genres } else q.genres.all { g -> g.key in genres }) &&
                    date >= params["first_air_date.gte"].orEmpty() && date <= (params["first_air_date.lte"] ?: "9999") &&
                    it.optDouble("vote_average", 0.0) >= (params["vote_average.gte"]?.toDouble() ?: 0.0) &&
                    it.optInt("vote_count") >= (params["vote_count.gte"]?.toInt() ?: 0) &&
                    (params["with_original_language"]?.equals(it.text("original_language")) != false) &&
                    (params["with_origin_country"]?.let { c -> c in it.optJSONArray("origin_country").strings() } != false)
            }
            val sorted = localOrder(rows,q)
            return Page(sorted.drop((page-1)*20).take(20), sorted.size, page*20 >= sorted.size)
        }
        val answer = Tmdb.exploreDiscover(q.discover(), page)
        val titles = answer.optJSONArray("results").objects()
        if (q.movie && q.person?.role in listOf("director", "writer")) {
            val ids = credits(q).map { it.getInt("id") }.toSet()
            return Page(titles.filter { it.getInt("id") in ids }, -1, page >= minOf(500, answer.optInt("total_pages")))
        }
        return Page(titles, answer.optInt("total_results"), page >= minOf(500, answer.optInt("total_pages")))
    }

    fun details(meta: Meta): JSONObject {
        val media = if (meta.type == "movie") "movie" else "tv"
        val cert = if (media == "movie") "release_dates" else "content_ratings"
        return Tmdb.exploreRequest("$media/${meta.id.substringAfter(':')}", mapOf(
            "append_to_response" to "external_ids,images,credits,$cert",
            "include_image_language" to "${java.util.Locale.getDefault().language},en,null"), cache = true)
    }
}
