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
}
