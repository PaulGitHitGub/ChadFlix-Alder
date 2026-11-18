package dev.alder

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

/** Search redraws its results only when what they show changes, so posters already in place stay. */
class SearchRenderKeyTest {
    private val movies = listOf("movie" to "tt1", "movie" to "tt2")

    @Test fun aLateAddonThatAddsNothingRedrawsNothing() =
        assertEquals(searchRenderKey(linkedMapOf("Movies" to movies), "dune"), searchRenderKey(linkedMapOf("Movies" to movies.toList()), "dune"))

    @Test fun aNewTitleOrOrderRedraws() {
        val before = searchRenderKey(linkedMapOf("Movies" to movies), "dune")
        assertNotEquals(before, searchRenderKey(linkedMapOf("Movies" to movies + ("movie" to "tt3")), "dune"))
        assertNotEquals(before, searchRenderKey(linkedMapOf("Movies" to movies.reversed()), "dune"))
        assertNotEquals(before, searchRenderKey(linkedMapOf("Movies" to movies, "Shows" to listOf("series" to "tt9")), "dune"))
    }

    @Test fun anotherQueryRedrawsTheSameTitles() =
        assertNotEquals(searchRenderKey(linkedMapOf("Movies" to movies), "dune"), searchRenderKey(linkedMapOf("Movies" to movies), "dun"))
}
