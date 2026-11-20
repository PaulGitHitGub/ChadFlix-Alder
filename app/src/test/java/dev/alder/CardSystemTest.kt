package dev.alder

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CardSystemTest {
    @Test
    fun cardStyleDefaultsMatchEachVariant() {
        val rail = CardStyle(CardVariant.POSTER_RAIL, widthPx = 88, heightPx = 132)
        assertFalse(rail.showRim)
        assertEquals(Screen.CARD_DIM, rail.dimAlpha, 0.001f)
        assertEquals(Screen.BASE, rail.placeholderColor)
        assertTrue(rail.showProgressOnCard)
        assertFalse(rail.showFallbackImmediately)

        val cont = CardStyle(CardVariant.CONTINUE_POSTER, widthPx = 88, heightPx = 132)
        assertFalse(cont.showRim)
        assertEquals(Screen.CARD_CONTINUE_DIM, cont.dimAlpha, 0.001f)
        assertTrue(cont.showProgressOnCard)

        val grid = CardStyle(CardVariant.GRID_POSTER, widthPx = 140, heightPx = 210)
        assertTrue(grid.showRim)
        assertEquals(0f, grid.dimAlpha, 0.001f)
        assertEquals(Screen.CARD_PLACEHOLDER, grid.placeholderColor)
        assertFalse(grid.showProgressOnCard)
        assertTrue(grid.showFallbackImmediately)

        val wide = CardStyle(CardVariant.WIDE_CHANNEL, widthPx = 200, heightPx = 112)
        assertFalse(wide.showRim)
        assertEquals(Screen.CARD_DIM, wide.dimAlpha, 0.001f)
        assertFalse(wide.showProgressOnCard)
    }
}
