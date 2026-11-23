package dev.alder

import org.junit.Assert.assertEquals
import org.junit.Test

/** ChadFlix fork: the home page's Title Case row names and row order. */
class HomeStyleTest {
    @Test fun rowNamesReadInTitleCase() {
        assertEquals("Continue Watching", HomeStyle.titleCase("Continue watching"))
        assertEquals("Movies for You", HomeStyle.titleCase("Movies for you"))
        assertEquals("More Like Crayon Shin-chan", HomeStyle.titleCase("More like Crayon Shin-chan"))
        assertEquals("The Best of IMDb", HomeStyle.titleCase("the best of IMDb"))
    }

    @Test fun savedRowOrderWinsAndNewRowsGoLast() =
        assertEquals(listOf("Top", "Popular", "New", "Anime"),
            orderedRows(listOf("Top", "Gone", "Popular"), listOf("Popular", "New", "Top", "Anime")) { it })

    @Test fun nothingSavedKeepsTheAddonsOrder() =
        assertEquals(listOf("Popular", "Top"), orderedRows(emptyList(), listOf("Popular", "Top")) { it })

    @Test fun rowsMoveOnePlaceAndStopAtTheEnds() {
        assertEquals(listOf("B", "A", "C"), movedRow(listOf("A", "B", "C"), "B", -1))
        assertEquals(listOf("A", "C", "B"), movedRow(listOf("A", "B", "C"), "B", 1))
        assertEquals(listOf("A", "B", "C"), movedRow(listOf("A", "B", "C"), "A", -1))
        assertEquals(listOf("A", "B", "C"), movedRow(listOf("A", "B", "C"), "X", 1))
    }

    @Test fun runtimesBecomeMilliseconds() {
        assertEquals(127 * 60_000L, HomeStyle.runtimeMs("2h 07m"))
        assertEquals(47 * 60_000L, HomeStyle.runtimeMs("47 min"))
        assertEquals(0L, HomeStyle.runtimeMs(null))
        assertEquals(0L, HomeStyle.runtimeMs("N/A"))
    }

    @Test fun endsAtCountsWhatIsLeft() {
        assertEquals(127 * 60_000L, HomeStyle.endsLeftMs("2h 07m", 0, 0)) // full runtime
        assertEquals(30 * 60_000L, HomeStyle.endsLeftMs("2h 07m", 100 * 60_000L, 70 * 60_000L)) // saved duration - progress
        assertEquals(97 * 60_000L, HomeStyle.endsLeftMs("2h 07m", 0, 30 * 60_000L)) // no duration: runtime - progress
        assertEquals(0L, HomeStyle.endsLeftMs(null, 0, 0)) // unknown: hidden
        assertEquals(0L, HomeStyle.endsLeftMs("47 min", 0, 50 * 60_000L)) // past the runtime, no duration: hidden
    }

    @Test fun genreListsBecomeTmdbFilters() {
        val trending = genreParams(true, 28, "trending", "2026-11-21", "2026-05-21")
        assertEquals("28", trending["with_genres"])
        assertEquals("popularity.desc", trending["sort_by"])
        assertEquals("2026-05-21", trending["primary_release_date.gte"])
        assertEquals("2026-11-21", genreParams(false, 18, "trending", "2026-11-21", "2025-11-21")["air_date.lte"])
        assertEquals("200", genreParams(false, 18, "top_rated", "", "")["vote_count.gte"])
        assertEquals("3", genreParams(false, 18, "complete", "", "")["with_status"])
        assertEquals("revenue.desc", genreParams(true, 28, "blockbusters", "", "")["sort_by"])
    }

    @Test fun movingAShownRowKeepsTheOffRowsPlaces() {
        val all = listOf("Action", "Western", "Comedy", "Drama")
        val shown = listOf("Action", "Comedy", "Drama")
        assertEquals(listOf("Comedy", "Western", "Action", "Drama"), movedAmong(all, shown, "Comedy", -1))
        assertEquals(all, movedAmong(all, shown, "Action", -1))
    }

    @Test fun washDimsTowardsTheBottomAndLiftsColour() {
        assertEquals(1f, washShade(0.2f), 0.001f)
        assertEquals(0.15f, washShade(1f), 0.001f)
        assertEquals(0xFF000000.toInt(), washPixel(0xFF808080.toInt(), 0f))
        val lifted = washPixel(0xFF804040.toInt(), 1f)
        assertEquals(true, (lifted shr 16 and 255) > 0x80 && (lifted and 255) < 0x40)
    }

    /** The ids were checked against TMDb (watch providers, networks, companies) on 2026-11-22; a change here should be checked again. */
    @Test fun platformIdsAreTheCheckedOnes() {
        val ids = HomeStyle.platforms.associate { it.name to listOf(it.providers, it.networks, it.companies) }
        assertEquals(listOf("8", "213", "178464|145174|171251"), ids["Netflix"])
        assertEquals(listOf("350", "2552", "194232"), ids["Apple TV+"])
        assertEquals(listOf("337", "2739", null), ids["Disney+"])
        assertEquals(listOf("9", "1024", "210099|20580"), ids["Prime Video"])
        assertEquals(listOf("1899", "3186|49", "7429|158691|138252"), ids["Max"])
        assertEquals(listOf("15", "453", null), ids["Hulu"])
        assertEquals(listOf("2303|2616", "4330", null), ids["Paramount+"])
        assertEquals(listOf("386|387", "3353", null), ids["Peacock"])
    }

    @Test fun platformRowsFilterByServiceAndExclusives() {
        val shows = platformParams(false, "8", "213", null, "trending", true, "US", "2026-11-22")
        assertEquals("8", shows["with_watch_providers"]); assertEquals("US", shows["watch_region"])
        assertEquals("flatrate", shows["with_watch_monetization_types"]); assertEquals("213", shows["with_networks"])
        assertEquals("popularity.desc", shows["sort_by"])
        val films = platformParams(true, "8", "213", "178464", "most_watched", true, "US", "2026-11-22")
        assertEquals("178464", films["with_companies"]); assertEquals(null, films["with_networks"])
        assertEquals("vote_count.desc", films["sort_by"])
        assertEquals(null, platformParams(true, "337", "2739", null, "new", true, "US", "2026-11-22")["with_companies"])
        assertEquals(null, platformParams(false, "8", "213", null, "top_rated", false, "US", "")["with_networks"])
    }

    @Test fun serviceRowsAddTheirGenreToTheSameQuery() {
        // Exclusives and a genre together: both filters, neither replacing the other (Netflix 213 + Comedy 35).
        val both = platformParams(false, "8", "213", null, "trending", true, "US", "", 35)
        assertEquals("213", both["with_networks"]); assertEquals("35", both["with_genres"]); assertEquals("8", both["with_watch_providers"])
        assertEquals("35", platformParams(false, "8", "213", null, "trending", true, "US", "", 35)["with_genres"])
        assertEquals(null, platformParams(false, "8", "213", null, "trending", true, "US", "", 0)["with_genres"])
    }

    @Test fun animeStartsOffUntilTurnedOn() {
        val never = org.json.JSONObject()
        assertEquals(false, HomeStyle.isOn(never, HomeStyle.ANIME, true)); assertEquals(false, HomeStyle.isOn(never, HomeStyle.ANIME, false))
        assertEquals(true, HomeStyle.isOn(org.json.JSONObject().put(HomeStyle.ANIME, true), HomeStyle.ANIME, true))
    }

    @Test fun exclusivesKeepTitlesOnThatServiceAlone() {
        val netflix = HomeStyle.platforms[0]
        // Checked against TMDb (US, 2026-11-22): BoJack streams on Netflix's plans only; The Loud House on Paramount+ too.
        assertEquals(true, onlyOn(listOf(8 to "Netflix", 1796 to "Netflix Standard with Ads"), netflix))
        assertEquals(false, onlyOn(listOf(8 to "Netflix", 2303 to "Paramount Plus Premium"), netflix))
        assertEquals(false, onlyOn(listOf(8 to "Netflix", 188 to "YouTube Premium"), netflix))
        assertEquals(true, onlyOn(emptyList(), netflix))
        val paramount = HomeStyle.platforms.first { it.name == "Paramount+" }
        assertEquals(true, onlyOn(listOf(2303 to "Paramount Plus Premium", 582 to "Paramount+ Amazon Channel"), paramount))
        val max = HomeStyle.platforms.first { it.name == "Max" }
        assertEquals(true, onlyOn(listOf(1899 to "HBO Max", 1825 to "HBO Max Amazon Channel"), max))
        assertEquals(false, onlyOn(listOf(1899 to "HBO Max", 289 to "Cinemax Amazon Channel"), max))
        // Pay-TV bundles carrying the channel don't count (House of the Dragon on Spectrum On Demand); Hulu and Disney+ share.
        assertEquals(true, onlyOn(listOf(1899 to "HBO Max", 486 to "Spectrum On Demand", 1 to "fuboTV"), max))
        val hulu = HomeStyle.platforms.first { it.name == "Hulu" }
        assertEquals(true, onlyOn(listOf(15 to "Hulu", 337 to "Disney Plus"), hulu))
        assertEquals(false, onlyOn(listOf(15 to "Hulu", 8 to "Netflix"), hulu))
        val apple = HomeStyle.platforms.first { it.name == "Apple TV+" }
        assertEquals(false, onlyOn(listOf(350 to "Apple TV", 1855 to "Starz Apple TV channel"), apple))
    }

    @Test fun animeRowIsJapaneseAnimationWithItsGenreAndSort() {
        val all = animeParams(false, "trending", 0, "2026-11-22", "2025-11-22", "2026-10-23")
        assertEquals("16", all["with_genres"]); assertEquals("ja", all["with_original_language"])
        val action = animeParams(true, "blockbusters", 28, "2026-11-22", "2026-05-22", "2026-10-23")
        assertEquals("16,28", action["with_genres"]); assertEquals("revenue.desc", action["sort_by"])
        val airing = animeParams(false, "airing", 0, "2026-11-22", "2025-11-22", "2026-10-23")
        assertEquals("2026-10-23", airing["air_date.gte"]); assertEquals("2026-11-22", airing["air_date.lte"])
        assertEquals("3", animeParams(false, "complete", 0, "", "", "")["with_status"])
    }

    @Test fun allGenresInterleavesFilmsAndShowsByRankOnce() {
        assertEquals(listOf("f1", "s1", "f2", "s2", "s3"), interleaved(listOf("f1", "f2"), listOf("s1", "s2", "s3")) { it })
        assertEquals(listOf("a", "b", "c"), interleaved(listOf("a", "b"), listOf("b", "c")) { it }) // a title in both: once, first place
        assertEquals(listOf("s1"), interleaved(emptyList(), listOf("s1")) { it })
    }

    @Test fun allGenresOffersEveryListOnceAndAsksTheTypesThatHaveIt() {
        assertEquals(listOf("trending", "new", "blockbusters", "most_watched", "top_rated", "complete"), HomeStyle.allLists().map { it.first })
        assertEquals(R.string.showing_new_releases, HomeStyle.allLists().first { it.first == "new" }.second)
        assertEquals(HomeStyle.allLists(), HomeStyle.listsFor(false, HomeStyle.ALL))
        assertEquals(listOf(true, false), HomeStyle.allMedia("trending"))
        assertEquals(listOf(true, false), HomeStyle.allMedia("most_watched"))
        assertEquals(listOf(true), HomeStyle.allMedia("blockbusters")) // films only
        assertEquals(listOf(false), HomeStyle.allMedia("complete")) // shows only
    }

    @Test fun allGenresHasItsOwnSourceAndNoGenreFilter() {
        assertEquals("tmdb:g:top_rated", HomeStyle.allSource("top_rated"))
        assertEquals("tmdb:g", HomeStyle.slotSource(HomeStyle.allSource("new")))
        assertEquals(null, genreParams(true, 0, "trending", "2026-11-23", "2026-05-23")["with_genres"])
        assertEquals("popularity.desc", genreParams(false, 0, "trending", "2026-11-23", "2025-11-23")["sort_by"])
        for (list in HomeStyle.allLists().map { it.first }) { // shows leave out News, Soap and Talk; films keep every genre
            assertEquals("10763|10766|10767", allParams(false, list, "2026-11-23", "2025-11-23")["without_genres"])
            assertEquals(null, allParams(true, list, "2026-11-23", "2026-05-23")["without_genres"])
            assertEquals(null, allParams(false, list, "2026-11-23", "2025-11-23")["with_genres"])
        }
        assertEquals("first_air_date.desc", allParams(false, "new", "2026-11-23", "")["sort_by"])
        assertEquals(null, genreParams(false, 18, "trending", "2026-11-23", "2025-11-23")["without_genres"]) // other rows unchanged
        assertEquals("100", allParams(true, "trending", "2026-11-23", "2026-05-23")["vote_count.gte"]) // a vote floor on Home
        assertEquals("100", allParams(false, "new", "2026-11-23", "")["vote_count.gte"])
        assertEquals("100", allParams(true, "most_watched", "", "")["vote_count.gte"])
        assertEquals("500", allParams(true, "top_rated", "", "")["vote_count.gte"]) // Top Rated keeps its own
        assertEquals(null, allParams(true, "blockbusters", "", "")["vote_count.gte"])
        assertEquals("20", genreParams(true, 28, "new", "2026-11-23", "")["vote_count.gte"]) // genre rows keep theirs
    }

    @Test fun allGenresKeepsOneKeyInEveryLanguage() {
        // On/off, order and Hidden rows know it as "All Genres"; only the screen shows the app's language.
        for (shown in listOf("All Genres", "Tous les genres", "كل الأنواع", "सभी शैलियाँ")) {
            assertEquals("g", HomeStyle.rowKey(false, HomeStyle.ALL_GENRES, HomeStyle.HOME))
            assertEquals(shown, rowName(HomeStyle.ALL_GENRES, shown))
            assertEquals("For You", rowName("For You", shown))
        }
        assertEquals("All Genres", HomeStyle.ALL_GENRES)
        assertEquals(null, HomeStyle.rowKey(false, "Tous les genres", HomeStyle.HOME)) // the shown name is never the key
        assertEquals(null, HomeStyle.rowKey(false, "For You", HomeStyle.HOME))
        assertEquals(null, HomeStyle.rowKey(true, HomeStyle.ALL_GENRES, "Movies"))
        assertEquals(listOf("All Genres", "For You"), orderedRows(listOf("All Genres", "For You"), listOf("For You", HomeStyle.ALL_GENRES)) { it })
    }

    private fun <T> withPool(block: (java.util.concurrent.ExecutorService) -> T): T {
        val pool = java.util.concurrent.Executors.newFixedThreadPool(2)
        try { return block(pool) } finally { pool.shutdownNow() }
    }

    @Test fun allGenresRowMixesBothHalvesFetchedSideBySide() = withPool { pool ->
        val bothStarted = java.util.concurrent.CountDownLatch(2)
        fun half(titles: List<String>, complete: Boolean = true): () -> Pair<List<String>, Boolean> = {
            bothStarted.countDown()
            // Each half waits for the other to start: this only finishes if they run at the same time.
            check(bothStarted.await(5, java.util.concurrent.TimeUnit.SECONDS))
            titles to complete
        }
        assertEquals(listOf("f1", "s1", "f2", "s2") to true, mixedRow(listOf(half(listOf("f1", "f2")), half(listOf("s1", "s2"))), pool) { it })
    }

    @Test fun allGenresRowKeepsTheHalfThatAnsweredAndFailsOnlyWhenBothDo() = withPool { pool ->
        val films = { listOf("f1", "f2") to true }
        val failed: () -> Pair<List<String>, Boolean> = { throw java.io.IOException("HTTP 429") }
        // One half failed: the other's titles, and the row is incomplete so it's asked again next time.
        assertEquals(listOf("f1", "f2") to false, mixedRow(listOf(films, failed), pool) { it })
        assertEquals(listOf("f1", "f2") to false, mixedRow(listOf(failed, films), pool) { it })
        // A half that mostly lacked IMDb ids leaves it incomplete too; one list (Blockbusters) is just that half.
        assertEquals(listOf("f1", "s1", "f2") to false, mixedRow(listOf(films, { listOf("s1") to false }), pool) { it })
        assertEquals(listOf("f1", "f2") to true, mixedRow(listOf(films), pool) { it })
        val error = runCatching { mixedRow(listOf(failed, failed), pool) { it } }.exceptionOrNull()
        assertEquals("HTTP 429", (error as java.io.IOException).message) // the half's own failure, not the pool's wrapper
    }

    @Test fun allGenresRowStopsWhenItsThreadIsStopped() = withPool { pool ->
        val never = java.util.concurrent.CountDownLatch(1)
        val waits: () -> Pair<List<String>, Boolean> = { never.await(); emptyList<String>() to true }
        Thread.currentThread().interrupt()
        val error = runCatching { mixedRow(listOf(waits, waits), pool) { it } }.exceptionOrNull()
        val stillStopped = Thread.interrupted() // and clears it for the next test
        assertEquals(true, error is InterruptedException) // not taken for a failed fetch
        assertEquals(true, stillStopped)
    }

    @Test fun allGenresGoesFirstInAHomeOrderSavedBeforeIt() {
        assertEquals(listOf("All Genres", "Watchly A", "Watchly B"), homeOrder(listOf("Watchly A", "Watchly B")))
        assertEquals(listOf("Watchly A", "All Genres", "Watchly B"), homeOrder(listOf("Watchly A", "All Genres", "Watchly B"))) // moved: stays
        assertEquals(emptyList<String>(), homeOrder(emptyList())) // nothing saved: the page's own order (All Genres first)
        assertEquals(listOf(HomeStyle.ALL_GENRES, "Watchly B", "Watchly A"),
            orderedRows(homeOrder(listOf("Watchly B", "Watchly A")), listOf(HomeStyle.ALL_GENRES, "Watchly A", "Watchly B")) { it })
    }

    @Test fun homeSaysNoPicksOnlyWhenNoRowIsOn() {
        assertEquals(false, showsNoCatalogs(home = true, noCatalogs = true, rows = 1)) // All Genres is on: no words above it
        assertEquals(true, showsNoCatalogs(home = true, noCatalogs = true, rows = 0)) // All Genres off too
        assertEquals(false, showsNoCatalogs(home = true, noCatalogs = false, rows = 0))
        assertEquals(true, showsNoCatalogs(home = false, noCatalogs = true, rows = 0)) // other pages as before
        assertEquals(true, showsNoCatalogs(home = false, noCatalogs = true, rows = 3))
    }
}
