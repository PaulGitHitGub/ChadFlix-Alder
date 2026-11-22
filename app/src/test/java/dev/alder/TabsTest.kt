package dev.alder

import org.junit.Assert.assertEquals
import org.junit.Test

class TabsTest {
    @Test fun tabsFollowTheAddonsCatalogs() {
        // ChadFlix: Movies, Shows, My Lists first, then the tabs that start hidden.
        assertEquals(listOf("movies", "shows", "library", "discover", "live", "type:trakt", "type:anime"),
            tabsFor(listOf("movie", "series", "Trakt", "tv", "anime")))
        assertEquals(listOf("movies", "shows", "library", "discover"), tabsFor(listOf("movie", "series")))
    }

    @Test fun nothingSavedKeepsAldersOrder() {
        val available = listOf("shows", "movies", "type:trakt", "library")
        assertEquals(available, orderTabs(emptyList(), available))
    }

    @Test fun builtInTabsKeepTheirSavedOrderWithoutAddonData() =
        // Discover is new to a saved order: it goes after My Lists, the tab it follows in ChadFlix's order.
        assertEquals(listOf("library", "discover", "movies", "shows"),
            orderTabs(listOf("library", "type:anime", "movies", "live", "shows"), tabsFor(emptyList())))

    @Test fun reorderingBuiltInTabsKeepsUnavailableAddonTabs() =
        assertEquals(listOf("library", "type:anime", "shows", "movies"),
            saveTabOrder(listOf("shows", "type:anime", "movies", "library"), listOf("library", "shows", "movies")))

    @Test fun savedOrderWins() =
        assertEquals(listOf("library", "type:trakt", "shows", "movies"),
            orderTabs(listOf("library", "type:trakt", "shows", "movies"), listOf("shows", "movies", "type:trakt", "library")))

    @Test fun aNewAddonsTabGoesAfterTheTabItFollows() =
        assertEquals(listOf("library", "shows", "movies", "type:marvel"),
            orderTabs(listOf("library", "shows", "movies"), listOf("shows", "movies", "type:marvel", "library")))

    @Test fun aNewFirstTabGoesFirst() =
        assertEquals(listOf("shows", "movies", "library"), orderTabs(listOf("movies", "library"), listOf("shows", "movies", "library")))

    @Test fun aRemovedAddonsTabIsLeftOut() =
        assertEquals(listOf("shows", "movies", "library"),
            orderTabs(listOf("type:old", "shows", "movies", "library", "shows"), listOf("shows", "movies", "library")))
}
