package dev.alder

import org.junit.Assert.assertEquals
import org.junit.Test

/** ChadFlix fork: the home page's Title Case row names. */
class HomeStyleTest {
    @Test fun rowNamesReadInTitleCase() {
        assertEquals("Continue Watching", HomeStyle.titleCase("Continue watching"))
        assertEquals("Movies for You", HomeStyle.titleCase("Movies for you"))
        assertEquals("More Like Crayon Shin-chan", HomeStyle.titleCase("More like Crayon Shin-chan"))
        assertEquals("The Best of IMDb", HomeStyle.titleCase("the best of IMDb"))
    }
}
