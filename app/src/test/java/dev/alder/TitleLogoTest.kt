package dev.alder

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TitleLogoTest {
    private fun canvas(w: Int, h: Int, ink: IntArray, alpha: Int = 255): IntArray {
        val px = IntArray(w * h)
        for (y in ink[1] until ink[3]) for (x in ink[0] until ink[2]) px[y * w + x] = (alpha shl 24) or 0xFFFFFF
        return px
    }

    @Test fun trimBoundsFindTheInk() {
        val px = canvas(800, 310, intArrayOf(10, 100, 790, 210))
        assertArrayEquals(intArrayOf(10, 100, 790, 210), inkBounds(800, 310, px))
    }

    @Test fun faintMarginsAreIgnoredAndEmptyIsNull() {
        val px = canvas(40, 20, intArrayOf(5, 5, 30, 15))
        px[0] = (16 shl 24) // at the threshold: not ink
        px[39 + 19 * 40] = (17 shl 24) // just above: ink
        assertArrayEquals(intArrayOf(5, 5, 40, 20), inkBounds(40, 20, px))
        assertNull(inkBounds(10, 10, IntArray(100)))
    }

    @Test fun constantAreaSizing() {
        val d = 1f
        // Odyssey-like wide wordmark (aspect 14): capped at 240 wide
        val (w1, h1) = logoSizePx(780, 55, 1f / 1f * d)
        assertTrue(w1 <= 240); assertTrue(h1 in 16..17)
        // Stacked logo (aspect ~1.35): 92 x 68 dp, source big enough at density 1
        val (w2, h2) = logoSizePx(500, 370, 1f)
        assertEquals(68, h2); assertTrue(w2 in 91..92)
        // Matrix-like (aspect ~5.4): about 221 x 41
        val (w3, h3) = logoSizePx(780, 145, 1f)
        assertTrue(w3 in 218..222); assertTrue(h3 in 40..42)
    }

    @Test fun neverLargerThanSourcePixels() {
        // 4K Shield, density 4: a 780 x 145 wordmark would want 221 x 41 dp = 884 x 164 px; capped at its own 780 px
        val (w, h) = logoSizePx(780, 145, 4f)
        assertTrue(w <= 780); assertTrue(h <= 145)
        assertEquals(780 / 4f, w / 4f, 0.3f)
        // aspect kept
        assertEquals(780.0 / 145, w.toDouble() / h, 0.05)
        // a 780 px wide wordmark caps at about 195 dp
        assertTrue(w / 4f <= 195f)
        for ((tw, th) in listOf(780 to 55, 300 to 280, 120 to 90, 780 to 480)) for (d in listOf(1f, 1.333f, 2f, 4f)) {
            val (pw, ph) = logoSizePx(tw, th, d)
            assertTrue("$tw x $th at $d", pw <= tw && ph <= th && pw >= 1 && ph >= 1)
            assertTrue(pw / d <= 240.01f && ph / d <= 68.01f)
        }
    }
}
