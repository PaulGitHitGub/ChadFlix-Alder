package dev.alder

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RatingsTest {
    private fun answer(vararg ratings: JSONObject) = JSONObject().put("ratings", JSONArray(ratings.toList()))
    private fun rating(source: String, value: Any?, score: Any?) =
        JSONObject().put("source", source).put("value", value ?: JSONObject.NULL).put("score", score ?: JSONObject.NULL)

    @Test fun letterboxdOutOfFive() =
        assertEquals("4.5", parseLetterboxd(answer(rating("imdb", 9.2, 92), rating("letterboxd", 4.5, 90))))

    @Test fun batchScaleValueFallsBackToScore() =
        assertEquals("4.5", parseLetterboxd(answer(rating("letterboxd", 9, 90))))

    @Test fun missingOrEmptyLetterboxdIsNull() {
        assertNull(parseLetterboxd(answer(rating("imdb", 7.9, 79))))
        assertNull(parseLetterboxd(answer(rating("letterboxd", null, null))))
        assertNull(parseLetterboxd(JSONObject()))
    }

    @Test fun choiceDecidesWhatShows() {
        assertEquals(listOf("IMDb" to "7.9"), shownRatings("imdb", "7.9", "3.9"))
        assertEquals(listOf(LETTERBOXD to "3.9"), shownRatings("letterboxd", "7.9", "3.9"))
        assertEquals(listOf("IMDb" to "7.9", LETTERBOXD to "3.9"), shownRatings("both", "7.9", "3.9"))
    }

    @Test fun imdbStandsInWhenLetterboxdHasNothing() {
        assertEquals(listOf("IMDb" to "7.9"), shownRatings("letterboxd", "7.9", null))
        assertEquals(listOf("IMDb" to "7.9"), shownRatings("both", "7.9", null))
        assertEquals(emptyList<Pair<String, String>>(), shownRatings("both", null, null))
    }

    @Test fun parsesTomatoesAndLetterboxdFromOneAnswer() {
        val json = answer(rating("imdb", 8.1, 81), rating("tomatoes", 92, 92), rating("letterboxd", 3.9, 78))
        assertEquals("92%", parseTomatoes(json))
        assertEquals(mapOf("tomatoes" to "92%", "letterboxd" to "3.9"), parseRatings(json))
    }

    @Test fun showHasTomatoesOnly() {
        assertEquals(mapOf("tomatoes" to "88%"), parseRatings(answer(rating("tomatoes", 88, 88))))
        assertEquals(emptyMap<String, String>(), parseRatings(answer(rating("tomatoes", null, null))))
        assertEquals(emptyMap<String, String>(), parseRatings(JSONObject()))
    }

    @Test fun bothShowsImdbTomatoesLetterboxdInOrder() {
        assertEquals(listOf("IMDb" to "7.9", ROTTEN to "92%", LETTERBOXD to "3.9"), shownRatings("both", "7.9", "3.9", "92%"))
        assertEquals(listOf("IMDb" to "7.9", ROTTEN to "92%"), shownRatings("both", "7.9", null, "92%"))
        assertEquals(listOf("IMDb" to "7.9"), shownRatings("imdb", "7.9", "3.9", "92%"))
    }
}
