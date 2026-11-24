package dev.alder

import org.junit.Assert.*
import org.junit.Test

class ExploreQueryTest {
    private val genres = listOf(Pick("35", "Comedy"), Pick("27", "Horror"))

    @Test fun matchModeAndRatingFloorReachTheDiscoverRequest() {
        val q = ExploreQuery(genres = genres, choices = mapOf(Facet.RATING to Pick("7.5", "7.5+")))
        assertEquals("35,27", q.discover().filters["with_genres"])
        assertEquals("35|27", q.copy(any = true).discover().filters["with_genres"])
        assertEquals("200", q.discover().filters["vote_count.gte"])
        assertEquals("100", q.copy(type = "series").discover().filters["vote_count.gte"])
        val newest = q.copy(choices = q.choices + (Facet.SORT to Pick("new", "Newest"))).discover()
        assertTrue(discoverUrl(newest,1,"US","en","2026-11-24","k").contains("vote_count.gte=200"))
    }

    @Test fun tvUsesFirstAirDateAndNeverAddsMoviePeopleParameters() {
        val q = ExploreQuery(type = "series", person = Person(42,"Name","actor",null),
            choices = mapOf(Facet.YEAR to Pick(":1969","Before 1970")))
        assertEquals(mapOf("first_air_date.lte" to "1969-12-31"),q.discover().filters)
        assertEquals("42",q.copy(type="movie").discover().filters["with_cast"])
        assertEquals("42",q.copy(type="movie", person=q.person!!.copy(role="writer")).discover().filters["with_crew"])
    }

    @Test fun clearingFranchiseRestoresEveryPausedFilterAndSort() {
        val q = ExploreQuery(genres=genres, any=true, choices=mapOf(Facet.SORT to Pick("votes","Most Votes")))
        assertEquals(q,q.copy(collection=Pick("10","Star Wars")).without(Facet.FRANCHISE))
        assertEquals("votes",q.without(Facet.GENRE).sort)
    }
}
