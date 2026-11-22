package dev.alder

import org.junit.Assert.assertEquals
import org.junit.Test

/** ChadFlix fork: the home page's Title Case row names and row order. */
class HomeStyleTest {
    @Test fun rowNamesReadInTitleCase() {
        assertEquals("Continue Watching", HomeStyle.titleCase("Continue watching"))
        assertEquals("Movies for You", HomeStyle.titleCase("Movies for you"))
        assertEquals("More Like Crayon Shin-chan", HomeStyle.titleCase("More like Crayon Shin-chan"))
        assertEquals("The Best of IMDb", HomeStyle.titleCase("the best of IMDb"))
    }

    @Test fun savedRowOrderWinsAndNewRowsGoLast() =
        assertEquals(listOf("Top", "Popular", "New", "Anime"),
            orderedRows(listOf("Top", "Gone", "Popular"), listOf("Popular", "New", "Top", "Anime")) { it })

    @Test fun nothingSavedKeepsTheAddonsOrder() =
        assertEquals(listOf("Popular", "Top"), orderedRows(emptyList(), listOf("Popular", "Top")) { it })

    @Test fun rowsMoveOnePlaceAndStopAtTheEnds() {
        assertEquals(listOf("B", "A", "C"), movedRow(listOf("A", "B", "C"), "B", -1))
        assertEquals(listOf("A", "C", "B"), movedRow(listOf("A", "B", "C"), "B", 1))
        assertEquals(listOf("A", "B", "C"), movedRow(listOf("A", "B", "C"), "A", -1))
        assertEquals(listOf("A", "B", "C"), movedRow(listOf("A", "B", "C"), "X", 1))
    }

    @Test fun runtimesBecomeMilliseconds() {
        assertEquals(127 * 60_000L, HomeStyle.runtimeMs("2h 07m"))
        assertEquals(47 * 60_000L, HomeStyle.runtimeMs("47 min"))
        assertEquals(0L, HomeStyle.runtimeMs(null))
        assertEquals(0L, HomeStyle.runtimeMs("N/A"))
    }

    @Test fun genreListsBecomeTmdbFilters() {
        val trending = genreParams(true, 28, "trending", "2026-11-21", "2026-05-21")
        assertEquals("28", trending["with_genres"])
        assertEquals("popularity.desc", trending["sort_by"])
        assertEquals("2026-05-21", trending["primary_release_date.gte"])
        assertEquals("2026-11-21", genreParams(false, 18, "trending", "2026-11-21", "2025-11-21")["air_date.lte"])
        assertEquals("200", genreParams(false, 18, "top_rated", "", "")["vote_count.gte"])
        assertEquals("3", genreParams(false, 18, "complete", "", "")["with_status"])
        assertEquals("revenue.desc", genreParams(true, 28, "blockbusters", "", "")["sort_by"])
    }
}
