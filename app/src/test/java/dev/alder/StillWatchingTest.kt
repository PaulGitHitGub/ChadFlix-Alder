package dev.alder

import org.junit.Assert.assertEquals
import org.junit.Test

class StillWatchingTest {
    @Test fun asksBeforeTheFourthEpisodeWithNoKeyPressed() {
        assertEquals(false, askStillWatching(1))
        assertEquals(false, askStillWatching(2))
        assertEquals(true, askStillWatching(3))
    }
}
