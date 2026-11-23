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

    /** The gaps down a pop-up that shows [rows] under its logo, the way showInfo places them. */
    private fun infoGaps(vararg rows: InfoRow): List<Int> {
        var above = InfoRow.LOGO
        return rows.map { row -> infoGap(above, row).also { above = row } }
    }

    @Test fun infoRowsKeepToOneSpacingScale() {
        val scale = setOf(4, 8, 12, 16, 24, 32)
        for (above in InfoRow.values()) for (row in InfoRow.values()) assertTrue("$above to $row", infoGap(above, row) in scale)
        assertTrue(INFO_BADGE_GAP in scale && INFO_META_H in 4..32 step 4)
        assertEquals(48, INFO_BOTTOM - (SEEK_BAR_BOTTOM + SEEK_BAR_H)) // 48 dp above the seek bar's top
    }

    @Test fun infoRowsGroupTitleTextAndTags() {
        // A movie with everything: logo 12 facts 8 plot 16 ratings 8 badges.
        assertEquals(listOf(12, 8, 16, 8), infoGaps(InfoRow.FACTS, InfoRow.PLOT, InfoRow.RATINGS, InfoRow.BADGES))
    }

    @Test fun infoRowsThatAreMissingLeaveNoGap() {
        // No ratings: the badges take the ratings' 16 under the plot, not the 8 that sits between the two tag rows.
        assertEquals(listOf(12, 8, 16), infoGaps(InfoRow.FACTS, InfoRow.PLOT, InfoRow.BADGES))
        // An episode with one rating and no badges ends on the ratings row.
        assertEquals(listOf(12, 8, 16), infoGaps(InfoRow.FACTS, InfoRow.PLOT, InfoRow.RATINGS))
        // No facts: the plot follows the logo at the logo's gap. No plot: the ratings still open the tag group with 16.
        assertEquals(listOf(12, 16, 8), infoGaps(InfoRow.PLOT, InfoRow.RATINGS, InfoRow.BADGES))
        assertEquals(listOf(12, 16, 8), infoGaps(InfoRow.FACTS, InfoRow.RATINGS, InfoRow.BADGES))
        assertEquals(listOf(12), infoGaps(InfoRow.FACTS))
        assertEquals(emptyList<Int>(), infoGaps())
    }

    @Test fun runtimeWordsReadHoursAndMinutes() {
        assertEquals("2 hr 52 mins", runtimeWords(172 * 60_000L))
        assertEquals("2 hr", runtimeWords(120 * 60_000L))
        assertEquals("47 mins", runtimeWords(47 * 60_000L))
        assertNull(runtimeWords(0L))
        assertNull(runtimeWords(30_000L))
        assertEquals("2 h 5 min", runtimeWords(125 * 60_000L, "%1\$d h %2\$d min", "%1\$d h", "%1\$d min"))
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

    @Test fun audioRowsHideCodecAndChannelsButKeepALabel() {
        val rows = listOf(AudioText("English", "Dolby Digital Plus 5.1", ""), AudioText("French", "AAC Stereo", ""),
            AudioText("Spanish", "Descriptive", "Descriptive"))
        assertEquals(listOf("", "", "Descriptive"), audioDetails(rows, expanded = false))
        assertEquals(listOf("Dolby Digital Plus 5.1", "AAC Stereo", "Descriptive"), audioDetails(rows, expanded = true))
    }

    @Test fun audioRowsWithTheSameNameShowTheirDetail() {
        val rows = listOf(AudioText("English", "Dolby TrueHD 7.1", ""), AudioText("English", "Commentary", "Commentary"),
            AudioText("French", "AAC Stereo", ""))
        assertEquals(listOf("Dolby TrueHD 7.1", "Commentary", ""), audioDetails(rows, expanded = false))
        // Alone in the list, a row has no twin to be told from.
        assertEquals(listOf(""), audioDetails(rows.take(1), expanded = false))
    }

    @Test fun onlyANonPlainTrackKeepsANote() {
        assertEquals("", trackNote("English", "DD+ 5.1", 0, 0))
        assertEquals("", trackNote("English", "Surround 5.1", C.SELECTION_FLAG_DEFAULT, C.ROLE_FLAG_MAIN))
        assertEquals("Commentary", trackNote("English", "Commentary", 0, 0))
        assertEquals("Descriptive", trackNote("English", "Descriptive", 0, 0))
        assertEquals("English", trackNote("English", "English", 0, C.ROLE_FLAG_COMMENTARY))
        assertEquals("Director", trackNote("English", "Director", 0, C.ROLE_FLAG_COMMENTARY))
        // A German TV: the language reads "Englisch", the file's label "English" is not a note.
        assertEquals("", trackNote("Englisch", "English", 0, 0))
        assertEquals("", trackNote(null, "Commentary", 0, 0))
        assertEquals("", trackNote("English", null, 0, C.ROLE_FLAG_COMMENTARY))
    }

    @Test fun anAudioTrackWithNoLanguageIsNamedByItsLabelElseTrackN() {
        val dd = "Dolby Digital Plus 5.1"
        // No label: "Track 2", and the codec is the detail.
        assertEquals("Track 2", trackText(true, null, null, dd, 0, 0, "Track 2").name)
        assertEquals(dd, trackText(true, null, null, dd, 0, 0, "Track 2").detail)
        // Any label names it, plain or not: an HLS rendition "Main" shows as "Main".
        val main = trackText(true, null, "Main", dd, 0, 0, "Track 2")
        assertEquals("Main", main.name)
        assertEquals(dd, main.detail)
        assertEquals("Surround", trackText(true, null, "Surround", dd, C.SELECTION_FLAG_DEFAULT, C.ROLE_FLAG_MAIN, "Track 2").name)
        val commentary = trackText(true, null, "Commentary", dd, 0, 0, "Track 2")
        assertEquals("Commentary", commentary.name)
        assertEquals(dd, commentary.detail)
        // The label is the name, so it is not repeated in the detail, with or without a codec.
        assertEquals("", trackText(true, null, "Surround", null, 0, 0, "Track 2").detail)
        // A subtitle with no language keeps its label as the name.
        val sub = trackText(false, null, "Full", null, 0, 0, "Track 3")
        assertEquals("Full", sub.name)
        assertEquals("", sub.detail)
        assertEquals("Track 3", trackText(false, null, null, null, 0, 0, "Track 3").name)
    }

    @Test fun trackNumberIsThePlaceNotTheFileId() {
        // The id of a track ("2" in an MP4, "aac:Main" in HLS, "1/257" in TS) never reaches the name.
        assertEquals("1", trackNumber(0))
        assertEquals("2", trackNumber(1))
        assertEquals("12", trackNumber(11))
    }

    @Test fun twinsWithTheSameDetailGetTheirLabelInFront() {
        val dd = "Dolby Digital 5.1"
        val us = trackText(true, "English", "US", dd, 0, 0, "Track 1")
        val uk = trackText(true, "English", "UK", dd, 0, 0, "Track 2")
        val rows = listOf(AudioText(us.name, us.detail, us.note, us.label), AudioText(uk.name, uk.detail, uk.note, uk.label),
            AudioText("French", dd, "", ""))
        for (expanded in listOf(false, true)) {
            val d = audioDetails(rows, expanded)
            assertEquals("US · $dd", d[0])
            assertEquals("UK · $dd", d[1])
            // A different name has no twin.
            assertEquals(if (expanded) dd else "", d[2])
        }
        // Details that differ already tell the rows apart: no label in front.
        val other = listOf(AudioText("English", dd, "", "US"), AudioText("English", "AAC Stereo", "", "UK"))
        assertEquals(listOf(dd, "AAC Stereo"), audioDetails(other, false))
        // A descriptive label is in the detail already: not twice.
        val both = listOf(AudioText("English", "Commentary · $dd", "Commentary", "Commentary"), AudioText("English", "Commentary · $dd", "Commentary", "Commentary"))
        assertEquals(listOf("Commentary · $dd", "Commentary · $dd"), audioDetails(both, false))
        // Twins with no label have nothing to put in front.
        assertEquals(listOf(dd, dd), audioDetails(listOf(AudioText("English", dd, ""), AudioText("English", dd, "")), false))
    }

    @Test fun theDetailShowsTheFormatForAPlainLabelAndBothForADescriptiveOne() {
        val dd = "Dolby Digital 5.1"
        assertEquals(dd, trackText(true, "English", "DD+ 5.1", dd, 0, 0, "Track 1").detail)
        assertEquals("Commentary · $dd", trackText(true, "English", "Commentary", dd, 0, 0, "Track 1").detail)
        assertEquals("Commentary", trackText(true, "English", "Commentary", dd, 0, 0, "Track 1").note)
        assertEquals("English", trackText(true, "English", null, dd, 0, 0, "Track 1").name)
        // Subtitles have no format: the label is the detail.
        assertEquals("SDH", trackText(false, "English", "SDH", null, 0, 0, "Track 1").detail)
    }

    @Test fun theAudioColumnIsWideOnlyWhenItShowsDetails() {
        assertFalse(audioColumnWide(false, listOf("", "")))
        assertFalse(audioColumnWide(false, emptyList()))
        assertTrue(audioColumnWide(false, listOf("", "Commentary")))
        assertTrue(audioColumnWide(true, listOf("", "")))
    }
}
