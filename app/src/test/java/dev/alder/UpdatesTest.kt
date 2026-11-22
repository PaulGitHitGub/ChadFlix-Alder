package dev.alder

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UpdatesTest {
    @Test fun comparesVersionsByNumber() {
        assertTrue(newerVersion("1.1", "1.0"))
        assertTrue(newerVersion("1.10", "1.9"))
        assertTrue(newerVersion("2", "1.9.9"))
        assertTrue(newerVersion("1.2.1", "1.2"))
        assertTrue(newerVersion("1.3.0", "1.2.9"))
        assertFalse(newerVersion("1.0", "1.0"))
        assertFalse(newerVersion("1.0", "1.0.1"))
        assertFalse(newerVersion("1.0.0", "1.0"))
        assertFalse(newerVersion("1.2", "1.2.0"))
    }

    @Test fun comparesAlderPartBeforeChadFlixBuild() {
        assertTrue(newerVersion("1.3.1-chadflix.2", "1.3.1-chadflix.1"))
        assertTrue(newerVersion("1.3.1-chadflix.10", "1.3.1-chadflix.9"))
        assertTrue(newerVersion("1.3.2-chadflix.1", "1.3.1-chadflix.2"))
        assertTrue(newerVersion("1.10-chadflix.1", "1.9.9-chadflix.12"))
        assertTrue(newerVersion("1.3.1-chadflix.1", "1.3.1"))
        assertTrue(newerVersion("1.4", "1.3.9-chadflix.7"))
        assertFalse(newerVersion("1.3.1-chadflix.2", "1.3.2-chadflix.1"))
        assertFalse(newerVersion("1.3.1-chadflix.2", "1.3.1-chadflix.2"))
        assertFalse(newerVersion("1.3-chadflix.1", "1.3.0-chadflix.1"))
        assertFalse(newerVersion("1.3.1", "1.3.1-chadflix.1"))
        assertFalse(newerVersion("1.3.1-chadflix.x", "1.3.1-chadflix.1"))
    }
}
