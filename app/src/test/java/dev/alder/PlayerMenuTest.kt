package dev.alder

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** The player's top line: when the title ends. */
class PlayerMenuTest {
    @Test fun endsAtIsNoneForTrailersLiveAndUnknownLengths() {
        assertEquals(1_000L + 60_000L, endsAtTime(1_000L, 120_000L, 60_000L, 1f, trailer = false, live = false))
        assertEquals(1_000L + 30_000L, endsAtTime(1_000L, 120_000L, 60_000L, 2f, trailer = false, live = false))
        assertNull(endsAtTime(1_000L, 120_000L, 60_000L, 1f, trailer = true, live = false))
        assertNull(endsAtTime(1_000L, 120_000L, 60_000L, 1f, trailer = false, live = true))
        assertNull(endsAtTime(1_000L, 0L, 0L, 1f, trailer = false, live = false))
        assertEquals(1_000L, endsAtTime(1_000L, 120_000L, 130_000L, 1f, trailer = false, live = false)) // past the end: now
    }
}
