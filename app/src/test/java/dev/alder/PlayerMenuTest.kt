package dev.alder

import androidx.media3.common.C
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The player's Audio & Subtitles menu rows, the Info pop-up's lines, and the "Ends at" time. */
class PlayerMenuTest {
    private fun t(id: String, language: String?, selected: Boolean = false, plain: Boolean = true, addon: Boolean = false) =
        MenuTrack(id, language, selected, plain, addon)
    private val off = MenuTrack("off", null, off = true)

    /** Parasite: original Korean, with English, French, Spanish and German dubs and many subtitles. */
    @Test fun parasiteShowsTheTrackInUseEnglishAndKorean() {
        val audio = listOf(t("ko", "ko", selected = true), t("en", "en"), t("fr", "fr"), t("es", "es"), t("de", "de"))
        assertEquals(setOf("ko", "en"), defaultTrackIds(audio, "ko"))
        val subs = listOf(off, t("en-sdh", "en", plain = false), t("en", "en"), t("en-forced", "en", plain = false), t("ko", "ko"),
            t("fr", "fr"), t("en-os", "en", addon = true))
        // Off, the best plain English (not the SDH one before it) and Korean; nothing is in use but Off
        assertEquals(setOf("off", "en", "ko"), defaultTrackIds(subs.map { if (it.id == "off") it.copy(selected = true) else it }, "ko"))
    }

    @Test fun anEnglishOriginalGivesNoDuplicate() {
        val audio = listOf(t("en", "en", selected = true), t("fr", "fr"), t("es", "es"))
        assertEquals(setOf("en"), defaultTrackIds(audio, "en"))
        val subs = listOf(off, t("en", "en"), t("fr", "fr"))
        assertEquals(setOf("off", "en"), defaultTrackIds(subs, "en"))
    }

    @Test fun withNoEnglishTheOriginalAndTheTrackInUseShow() {
        val audio = listOf(t("fr", "fr", selected = true), t("de", "de"), t("it", "it"))
        assertEquals(setOf("fr"), defaultTrackIds(audio, "fr"))
        val subs = listOf(off, t("de", "de"), t("it", "it"))
        assertEquals(setOf("off"), defaultTrackIds(subs, "fr")) // no French subtitles: only Off; the rest is under More
        assertEquals(setOf("off", "it"), defaultTrackIds(subs, "it"))
    }

    @Test fun anUnknownOriginalShowsEnglishAndTheTrackInUse() {
        val audio = listOf(t("a1", "ko", selected = true), t("a2", "en"), t("a3", "ja"))
        assertEquals(setOf("a1", "a2"), defaultTrackIds(audio, null))
        // "English" is literal: the TV's own language does not matter
        assertEquals(setOf("a1", "a2"), defaultTrackIds(audio, "xx"))
    }

    @Test fun severalEnglishTracksPickThePlainOneFromTheFileFirst() {
        val subs = listOf(off, t("forced", "en", plain = false), t("addon", "en", addon = true), t("sdh", "en", plain = false), t("file", "en"))
        assertEquals(setOf("off", "file"), defaultTrackIds(subs, null))
        // the file's plain track comes before an add-on's, whatever the order
        assertEquals(setOf("off", "file"), defaultTrackIds(listOf(off, t("addon", "en", addon = true), t("file", "en")), null))
        // with no plain English track the first one of the language shows: the file's before an add-on's
        val odd = listOf(off, t("addon", "en", plain = false, addon = true), t("sdh", "en", plain = false), t("forced", "en", plain = false))
        assertEquals(setOf("off", "sdh"), defaultTrackIds(odd, null))
        // an add-on's plain track beats the file's SDH one
        assertEquals(setOf("off", "addon"), defaultTrackIds(listOf(off, t("sdh", "en", plain = false), t("addon", "en", addon = true)), null))
    }

    @Test fun theTrackInUseShowsEvenWhenItIsHidden() {
        val audio = listOf(t("en", "en"), t("fr", "fr", selected = true), t("es", "es"))
        assertEquals(setOf("en", "fr"), defaultTrackIds(audio, "en"))
        val untagged = listOf(off, t("en", "en"), t("x", null, selected = true), t("y", "und"))
        assertEquals(setOf("off", "en", "x"), defaultTrackIds(untagged, "ko")) // untagged and unknown show only when in use
    }

    @Test fun noTracksShowNothingBeyondOff() {
        assertEquals(emptySet<String>(), defaultTrackIds(emptyList(), "ko"))
        assertEquals(setOf("off"), defaultTrackIds(listOf(off), "ko"))
    }

    @Test fun anOriginalFromAnotherCountryStillMatches() {
        val audio = listOf(t("en", "en", selected = true), t("br", "pt-BR"), t("pt", "pt"))
        assertEquals(setOf("en", "br"), defaultTrackIds(audio, "pt"))
    }

    @Test fun everyTrackStaysReachable() {
        // More shows exactly when a track is left out; the left-out ones are the rest
        val audio = listOf(t("ko", "ko", selected = true), t("en", "en"), t("fr", "fr"), t("es", "es"))
        val shown = defaultTrackIds(audio, "ko")
        assertEquals(listOf("fr", "es"), audio.map { it.id }.filter { it !in shown })
    }

    @Test fun plainTracksAreNotForcedSdhOrCommentary() {
        assertTrue(plainTrack(0, 0, null))
        assertTrue(plainTrack(C.SELECTION_FLAG_DEFAULT, C.ROLE_FLAG_MAIN, "English"))
        assertFalse(plainTrack(C.SELECTION_FLAG_FORCED, 0, null))
        assertFalse(plainTrack(0, C.ROLE_FLAG_COMMENTARY, null))
        assertFalse(plainTrack(0, C.ROLE_FLAG_CAPTION, null))
        assertFalse(plainTrack(0, C.ROLE_FLAG_DESCRIBES_VIDEO, null))
        assertFalse(plainTrack(0, 0, "English SDH"))
        assertFalse(plainTrack(0, 0, "English (Forced)"))
        assertFalse(plainTrack(0, 0, "Director's Commentary"))
        assertFalse(plainTrack(0, 0, "English [CC]"))
        assertTrue(plainTrack(0, 0, "Dolby Digital 5.1"))
    }

    @Test fun endsAtIsNoneForTrailersLiveAndUnknownLengths() {
        assertEquals(1_000L + 60_000L, endsAtTime(1_000L, 120_000L, 60_000L, 1f, trailer = false, live = false))
        assertEquals(1_000L + 30_000L, endsAtTime(1_000L, 120_000L, 60_000L, 2f, trailer = false, live = false))
        assertNull(endsAtTime(1_000L, 120_000L, 60_000L, 1f, trailer = true, live = false))
        assertNull(endsAtTime(1_000L, 120_000L, 60_000L, 1f, trailer = false, live = true))
        assertNull(endsAtTime(1_000L, 0L, 0L, 1f, trailer = false, live = false))
        assertEquals(1_000L, endsAtTime(1_000L, 120_000L, 130_000L, 1f, trailer = false, live = false)) // past the end: now
    }

    @Test fun infoFactsLeaveOutWhatIsMissing() {
        assertEquals("2026 • R • 2 hr 52 mins", infoFacts("2026", "R", runtimeWords(172 * 60_000L)))
        assertEquals("2026 • 2 hr 52 mins", infoFacts("2026", null, "2 hr 52 mins"))
        assertEquals("2026 • R", infoFacts("2026", " R ", null))
        assertEquals("R", infoFacts(null, "R", null))
        assertEquals("", infoFacts(null, "", null))
    }

    @Test fun runtimeWordsReadHoursAndMinutes() {
        assertEquals("2 hr 52 mins", runtimeWords(172 * 60_000L))
        assertEquals("2 hr", runtimeWords(120 * 60_000L))
        assertEquals("47 mins", runtimeWords(47 * 60_000L))
        assertNull(runtimeWords(0L))
        assertNull(runtimeWords(30_000L))
        assertEquals("2 h 5 min", runtimeWords(125 * 60_000L, "%1\$d h %2\$d min", "%1\$d h", "%1\$d min"))
    }

    @Test fun chipsReadInTitleCase() {
        assertEquals("Dolby Vision", badgeChip(PlayerBadge(BadgeBrand.DOLBY, "VISION")))
        assertEquals("Dolby Atmos", badgeChip(PlayerBadge(BadgeBrand.DOLBY, "ATMOS")))
        assertEquals("Dolby TrueHD", badgeChip(PlayerBadge(BadgeBrand.DOLBY, "TRUEHD")))
        assertEquals("Dolby Audio", badgeChip(PlayerBadge(BadgeBrand.DOLBY, "AUDIO")))
        assertEquals("DTS", badgeChip(PlayerBadge(BadgeBrand.DTS, "")))
        assertEquals("DTS-HD", badgeChip(PlayerBadge(BadgeBrand.DTS, "HD")))
        assertEquals("DTS:X", badgeChip(PlayerBadge(BadgeBrand.DTS, "X")))
        assertEquals("Blu-Ray", badgeChip(PlayerBadge(BadgeBrand.NONE, "BLU-RAY")))
        assertEquals("1080p", badgeChip(PlayerBadge(BadgeBrand.NONE, "1080p")))
        assertEquals("7.1", badgeChip(PlayerBadge(BadgeBrand.NONE, "7.1")))
    }

    @Test fun originalLanguageComesFromTheFindAnswer() {
        val find = JSONObject("""{"movie_results":[{"id":496243,"title":"Parasite","original_language":"ko"}],"tv_results":[]}""")
        assertEquals("ko", parseOriginalLanguage(find, "movie"))
        assertEquals("ko", parseOriginalLanguage(find, "series")) // the other kind stands in
        val both = JSONObject("""{"movie_results":[{"original_language":"en"}],"tv_results":[{"original_language":"ja"}]}""")
        assertEquals("ja", parseOriginalLanguage(both, "series"))
        assertEquals("en", parseOriginalLanguage(both, "movie"))
        assertNull(parseOriginalLanguage(JSONObject("""{"movie_results":[],"tv_results":[]}"""), "movie"))
        assertNull(parseOriginalLanguage(JSONObject("""{"movie_results":[{"original_language":"xx"}]}"""), "movie"))
        assertNull(parseOriginalLanguage(JSONObject("""{"movie_results":[{"title":"No language"}]}"""), "movie"))
        assertEquals("zh", parseOriginalLanguage(JSONObject("""{"movie_results":[{"original_language":"cn"}]}"""), "movie"))
    }
}
