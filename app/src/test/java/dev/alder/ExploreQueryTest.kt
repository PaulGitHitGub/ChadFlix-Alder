package dev.alder

import org.junit.Assert.*
import org.junit.Test

class ExploreQueryTest {
    private val genres = listOf(Pick("35", "Comedy"), Pick("27", "Horror"))

    @Test fun matchModeAndStudioReachTheDiscoverRequest() {
        val q = ExploreQuery(genres = genres, choices = mapOf(Facet.STUDIO to Pick("1632|35", "Lionsgate")))
        assertEquals("35,27", q.discover().filters["with_genres"])
        assertEquals("35|27", q.copy(any = true).discover().filters["with_genres"])
        assertEquals("1632|35", q.discover().filters["with_companies"])
        assertEquals("1632|35", q.copy(type = "series").discover().filters["with_networks"])
        val newest = q.copy(sort = "new").discover()
        assertNull(newest.filters["vote_count.gte"])
    }

    @Test fun tvUsesFirstAirDateAndNeverAddsMoviePeopleParameters() {
        val q = ExploreQuery(type = "series", person = Person(42,"Name","actor",null),
            choices = mapOf(Facet.YEAR to Pick(":1969","Before 1970")))
        assertEquals(mapOf("first_air_date.lte" to "1969-12-31"),q.discover().filters)
        assertEquals("42",q.copy(type="movie").discover().filters["with_cast"])
        assertEquals("42",q.copy(type="movie", person=q.person!!.copy(role="writer")).discover().filters["with_crew"])
    }

    @Test fun sortFloorsFollowTheEffectiveCatalogue() {
        val top = ExploreQuery(sort = "top")
        assertEquals("3000",top.discover().filters["vote_count.gte"])
        assertEquals("500",top.copy(genres=genres).discover().filters["vote_count.gte"])
        assertEquals("1000",top.copy(type="series").discover().filters["vote_count.gte"])
        assertEquals("200",top.copy(type="series",genres=genres).discover().filters["vote_count.gte"])
        assertEquals("popular",ExploreQuery().sort)
    }

    @Test fun reliableSortsFollowCatalogueType() {
        val movies = ExploreQuery()
        val shows = movies.copy(type = "series")
        val moviesList = listOf("popular","top","new","old","votes","box")
        assertEquals(moviesList,movies.sortOptions().map { it.first })
        assertEquals(moviesList.dropLast(1),shows.sortOptions().map { it.first })
        assertEquals("1000",movies.copy(sort="old").discover().filters["vote_count.gte"])
        assertEquals("100",shows.copy(sort="old").discover().filters["vote_count.gte"])
    }

    @Test fun removingAFilterKeepsTheOthersAndTheSort() {
        val q = ExploreQuery(genres=genres, any=true, sort="votes",
            choices=mapOf(Facet.LANGUAGE to Pick("ko","Korean")))
        assertEquals("votes",q.without(Facet.GENRE).sort)
        assertEquals(q.copy(genres=emptyList()),q.without(Facet.GENRE))
        assertNull(q.without(Facet.LANGUAGE).discover().filters["with_original_language"])
        assertEquals("ko",q.without(Facet.GENRE).discover().filters["with_original_language"])
    }
}
