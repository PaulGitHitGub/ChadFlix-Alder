package dev.alder

import org.junit.Assert.*
import org.junit.Test

class WatchlistsTest {
    private val prefs = ProfilesSyncTest.InMemoryPrefs()
    private val alice = Watchlists(prefs, "watchlists@alice")
    private val bob = Watchlists(prefs, "watchlists@bob")
    private val movie = WatchTitle("42", "movie", "Movie", null, "art")
    private val show = WatchTitle("42", "series", "Show", "poster", null)

    @Test fun namesAndMembershipKeepInsertionOrderAcrossReads() {
        assertTrue(alice.create(" First ", movie))
        assertTrue(alice.create("Second"))
        assertFalse(alice.create(" first"))
        assertFalse(alice.create("  "))
        assertFalse(alice.rename("First", "SECOND"))
        assertFalse(alice.rename("First", " "))
        alice.toggle("First", show)
        assertTrue(alice.rename("First", "Both"))
        assertEquals(listOf(Watchlist("Both", listOf(movie, show)), Watchlist("Second", emptyList())), alice.all())
        alice.toggle("Both", movie.copy(name = "Updated"))
        alice.toggle("Both", movie)
        assertEquals(listOf(show, movie), alice.all().first().items)
        alice.delete("Both")
        assertEquals(listOf("Second"), alice.all().map { it.name })
    }

    @Test fun forgettingOneProfilePreservesOtherProfilesLists() {
        alice.create("Weekend", movie)
        bob.create("Weekend", show)
        assertEquals(listOf(movie), alice.all().single().items)
        Settings.forgetPrefs(prefs, "alice")
        assertTrue(alice.all().isEmpty())
        assertEquals(listOf(show), bob.all().single().items)
    }
}
