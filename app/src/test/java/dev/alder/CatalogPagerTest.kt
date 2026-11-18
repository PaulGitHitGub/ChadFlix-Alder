package dev.alder

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Test
import java.io.IOException

class CatalogPagerTest {
    @Test fun countsRawTitlesAndSkipsDuplicatePages() {
        val skips = mutableListOf<Int>()
        val pager = CatalogPager(listOf(listOf("a", "message", "a")), listOf(true), { it }, { it != "message" }) { _, skip ->
            skips += skip
            when (skip) {
                3 -> listOf("a", "message")
                5 -> listOf("b", "b", "c")
                else -> emptyList()
            }
        }
        assertEquals(listOf("a"), pager.slice(0, 1))
        assertEquals(listOf("b"), pager.slice(1, 1))
        assertEquals(listOf("c"), pager.slice(2, 1))
        assertEquals(emptyList<String>(), pager.slice(3, 1))
        assertEquals(listOf(3, 5, 8), skips)
        assertFalse(pager.hasMore(3))
    }

    @Test fun retriesFailedPageAndContinuesToAnotherProvider() {
        var attempts = 0
        val pager = CatalogPager(listOf(listOf("a"), listOf("b")), listOf(true, true), { it }, { true }) { source, skip ->
            assertEquals(1, skip)
            if (source == 1) listOf("c")
            else if (attempts++ == 0) throw IOException("Unavailable")
            else emptyList()
        }
        assertThrows(IOException::class.java) { pager.slice(2, 1) }
        assertEquals(listOf("c"), pager.slice(2, 1))
    }
}
