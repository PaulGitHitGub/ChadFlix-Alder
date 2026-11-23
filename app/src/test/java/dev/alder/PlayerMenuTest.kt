package dev.alder

import android.view.KeyEvent
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The player's Audio & Subtitles menu rows, the Info pop-up's lines, and the "Ends at" time. */
class PlayerMenuTest {
    private fun t(id: String, language: String?, selected: Boolean = false, plain: Boolean = true, addon: Boolean = false,
                  preferred: Boolean = false) = MenuTrack(id, language, selected, plain, addon, preferred = preferred)
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

    @Test fun aLanguageNeverShowsTwice() {
        // The track in use is English SDH: it is the English row, the plain English track goes under More.
        val subs = listOf(off, t("sdh", "en", selected = true, plain = false), t("en", "en"), t("ko", "ko"))
        assertEquals(setOf("sdh", "off", "ko"), defaultTrackIds(subs, "ko"))
        // Two English audio tracks, neither in use: one row, the best.
        val audio = listOf(t("a", "ko", selected = true), t("en-com", "en", plain = false), t("en", "en"))
        assertEquals(setOf("a", "en"), defaultTrackIds(audio, "ko"))
        // the row is the one best track, whatever the order: never both English tracks, never the commentary beside a plain one
        val shown = defaultTrackIds(listOf(t("en-com", "en", plain = false), t("en2", "en"), t("en", "en", preferred = true), t("a", "ko", selected = true)), "ko")
        assertEquals(setOf("a", "en"), shown)
        // The track in use is the original language: the best original-language track is not added beside it.
        val ko = listOf(t("k1", "ko"), t("k2", "ko", selected = true), t("en", "en"))
        assertEquals(setOf("k2", "en"), defaultTrackIds(ko, "ko"))
    }

    @Test fun anAddonSubtitleInUseIsTheRowOfItsLanguageEvenWithAnEmbeddedTwin() {
        // The add-on's English file is in use and the file has English too: one English row, the one in use.
        val subs = listOf(off, t("sub:1", "en"), t("sub:2", "ko"), t("addon:x", "en", selected = true, addon = true))
        assertEquals(setOf("off", "addon:x", "sub:2"), defaultTrackIds(subs, "ko"))
        // An add-on's Korean file in use beside an embedded Korean one: the add-on's is the Korean row, English is the file's.
        val ko = listOf(off, t("sub:1", "ko"), t("sub:2", "en"), t("addon:k", "ko", selected = true, addon = true))
        assertEquals(setOf("off", "addon:k", "sub:2"), defaultTrackIds(ko, "ko"))
        // Nothing in use but Off: the file's English beats the add-on's, and the add-on's other language stays under More.
        val none = listOf(off.copy(selected = true), t("addon:en", "en", addon = true), t("sub:1", "en"), t("addon:fr", "fr", addon = true))
        assertEquals(setOf("off", "sub:1"), defaultTrackIds(none, null))
    }

    @Test fun aThirdLanguageInUseShowsBesideEnglishAndTheOriginal() {
        val audio = listOf(t("ko", "ko"), t("en", "en"), t("fr", "fr", selected = true), t("de", "de"))
        assertEquals(setOf("ko", "en", "fr"), defaultTrackIds(audio, "ko"))
        val subs = listOf(off, t("ko", "ko"), t("en", "en"), t("fr", "fr", selected = true), t("de", "de"))
        assertEquals(setOf("off", "ko", "en", "fr"), defaultTrackIds(subs, "ko"))
        assertEquals(setOf("off", "en", "fr"), defaultTrackIds(subs, null))
    }

    @Test fun theTrackAlderWouldPlayBacksTheLanguageRow() {
        // Korean: Dolby Digital 5.1 first, TrueHD second. Alder's pick for a Korean viewer is the TrueHD one, so the row backs it.
        val audio = listOf(t("ac3", "ko"), t("truehd", "ko", preferred = true), t("en", "en", selected = true))
        assertEquals(setOf("truehd", "en"), defaultTrackIds(audio, "ko"))
        // Without a preferred mark the first plain one wins, as before.
        assertEquals(setOf("ac3", "en"), defaultTrackIds(audio.map { it.copy(preferred = false) }, "ko"))
        // Plain still comes first: a preferred commentary does not take the row from a plain track.
        val odd = listOf(t("com", "ko", plain = false, preferred = true), t("ko", "ko"), t("en", "en", selected = true))
        assertEquals(setOf("ko", "en"), defaultTrackIds(odd, "ko"))
    }

    @Test fun preferredAudioFormatsFollowBestAudioFormatPerLanguage() {
        // Format.Builder.setLanguage needs Android's TextUtils, which local tests do not have: the field is set directly.
        fun f(mime: String, language: String, channels: Int, flags: Int = 0, label: String? = null) = Format.Builder()
            .setSampleMimeType(mime).setChannelCount(channels).setSelectionFlags(flags).setLabel(label).build().also {
                Format::class.java.getField("language").apply { isAccessible = true }.set(it, language)
            }
        val koAc3 = f(MimeTypes.AUDIO_AC3, "ko", 8)
        val koTrueHd = f(MimeTypes.AUDIO_TRUEHD, "ko", 6)
        val enAc3 = f(MimeTypes.AUDIO_AC3, "en", 6)
        val enDefault = f(MimeTypes.AUDIO_AAC, "en", 2, C.SELECTION_FLAG_DEFAULT)
        val enCommentary = f(MimeTypes.AUDIO_AC3, "en", 6, label = "Commentary")
        val formats = listOf(koAc3, koTrueHd, enAc3, enDefault, enCommentary, f(MimeTypes.AUDIO_AAC, "und", 2))
        val passes: (Format) -> Boolean = { losslessEncoding(it) != null } // a device that passes lossless audio through
        val picks = preferredAudioFormats(formats, emptySet(), passes)
        assertEquals(2, picks.size) // one per language; "und" has none
        assertTrue(picks.any { it === koTrueHd } && picks.none { it === koAc3 })
        assertTrue(picks.any { it === enDefault }) // the default flag outranks channels, as in Alder's own pick
        // A device that decodes everything to PCM: channels decide, so the 8-channel Dolby Digital beats the 6-channel TrueHD.
        val pcm = preferredAudioFormats(formats, emptySet()) { false }
        assertTrue(pcm.any { it === koAc3 } && pcm.none { it === koTrueHd })
        // A failed track is skipped, as at the start of playback.
        assertTrue(preferredAudioFormats(formats, setOf(koTrueHd), passes).any { it === koAc3 })
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
        // a negated word says the track is not that kind
        assertTrue(plainTrack(0, 0, "English Non-Forced"))
        assertTrue(plainTrack(0, 0, "English non-SDH"))
        assertTrue(plainTrack(0, 0, "English (No SDH)"))
        assertTrue(plainTrack(0, 0, "English not forced"))
        assertFalse(plainTrack(0, 0, "English Captions"))
        assertFalse(plainTrack(0, 0, "Piano SDH")) // "no" inside a word is not a negation
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

    @Test fun aRowShowsOnlyTheLanguageBeforeMoreAndTheRoleAndFormatUnderIt() {
        assertEquals("", rowDetail(false, "", "Dolby Digital 5.1"))
        assertEquals("", rowDetail(false, "Commentary", "Dolby Digital 5.1")) // no role word before More
        assertEquals("", rowDetail(false, "SDH", ""))
        assertEquals("Dolby Digital 5.1", rowDetail(true, "", "Dolby Digital 5.1"))
        assertEquals("Commentary · Dolby Digital 5.1", rowDetail(true, "Commentary", "Dolby Digital 5.1"))
        assertEquals("Forced", rowDetail(true, "Forced", "")) // a subtitle has no format
        assertEquals("SDH", rowDetail(true, "SDH", ""))
        assertEquals("", rowDetail(true, "", ""))
    }

    @Test fun theRoleComesFromTheFlagsOnly() {
        assertNull(trackRole(true, 0, 0))
        assertNull(trackRole(false, 0, 0))
        assertEquals(TrackRole.COMMENTARY, trackRole(true, 0, C.ROLE_FLAG_COMMENTARY))
        assertEquals(TrackRole.DESCRIPTIVE, trackRole(true, 0, C.ROLE_FLAG_DESCRIBES_VIDEO))
        assertEquals(TrackRole.FORCED, trackRole(false, C.SELECTION_FLAG_FORCED, 0))
        assertEquals(TrackRole.SDH, trackRole(false, 0, C.ROLE_FLAG_CAPTION))
        assertEquals(TrackRole.SDH, trackRole(false, 0, C.ROLE_FLAG_DESCRIBES_MUSIC_AND_SOUND))
        // a subtitle is never a commentary, and audio is never forced or SDH
        assertNull(trackRole(false, 0, C.ROLE_FLAG_COMMENTARY))
        assertNull(trackRole(true, C.SELECTION_FLAG_FORCED, C.ROLE_FLAG_CAPTION))
    }

    @Test fun theRoleAlsoComesFromWholeWordsInTheLabel() {
        // Matroska does not report these flags, so the label is the only source.
        assertEquals(TrackRole.COMMENTARY, trackRole(true, 0, 0, "BTM Commentary"))
        assertEquals(TrackRole.COMMENTARY, trackRole(true, 0, 0, "English_commentary_2.0"))
        assertEquals(TrackRole.DESCRIPTIVE, trackRole(true, 0, 0, "English Audio Description"))
        assertEquals(TrackRole.DESCRIPTIVE, trackRole(true, 0, 0, "Descriptive"))
        assertEquals(TrackRole.DESCRIPTIVE, trackRole(true, 0, 0, "English Descriptions"))
        assertEquals(TrackRole.SDH, trackRole(false, 0, 0, "English SDH"))
        assertEquals(TrackRole.SDH, trackRole(false, 0, 0, "English [CC]"))
        assertEquals(TrackRole.SDH, trackRole(false, 0, 0, "English.HoH"))
        assertEquals(TrackRole.SDH, trackRole(false, 0, 0, "Hearing Impaired"))
        assertEquals(TrackRole.FORCED, trackRole(false, 0, 0, "English (Forced)"))
        assertEquals(TrackRole.SDH, trackRole(false, 0, 0, "English Captions"))
        assertEquals(TrackRole.SDH, trackRole(false, 0, 0, "Piano SDH"))
        // flags still count, and come first
        assertEquals(TrackRole.COMMENTARY, trackRole(true, 0, C.ROLE_FLAG_COMMENTARY, "English"))
        assertEquals(TrackRole.FORCED, trackRole(false, C.SELECTION_FLAG_FORCED, 0, "English SDH"))
        // a letter next to the word, or a word for the other kind of track, gives nothing
        assertNull(trackRole(true, 0, 0, "Atmosphere"))
        assertNull(trackRole(true, 0, 0, "BTM"))
        assertNull(trackRole(true, 0, 0, "English"))
        assertNull(trackRole(true, 0, 0, "DDP5.1 Accessible"))
        assertNull(trackRole(true, 0, 0, "English SDH")) // audio is never SDH
        assertNull(trackRole(false, 0, 0, "BTM Commentary")) // a subtitle is never a commentary
        assertNull(trackRole(false, 0, 0, "Success Forceddd"))
        assertNull(trackRole(false, 0, 0, "Occ"))
        // "Non-Forced", "non-SDH" and "No SDH" are the opposite of the role
        assertNull(trackRole(false, 0, 0, "English Non-Forced"))
        assertNull(trackRole(false, 0, 0, "English non-SDH"))
        assertNull(trackRole(false, 0, 0, "English (No SDH)"))
        assertNull(trackRole(false, 0, 0, "English not forced"))
        assertNull(trackRole(true, 0, 0, "No Commentary"))
    }

    @Test fun aLanguageColumnIsAsWideAsItsWidestRowPlusOneFixedGap() {
        assertEquals(170, languageColumnDp(52)) // "English" and "More": the width the menu was approved at
        assertEquals(168, languageColumnDp(50)) // "Korean"
        assertEquals(223, languageColumnDp(105)) // "Portuguese (Brazil)"
        assertEquals(140, languageColumnDp(20)) // Off alone is never narrower than 140
        assertEquals(250, languageColumnDp(400)) // a very long row is cut with an ellipsis, not allowed to push the screen
    }

    @Test fun theGapBeforeTheNextColumnIsTheSameForEveryColumn() {
        // white space from a column's widest row to the next column's text = width - row; the same 118 dp for every row from 22 to 132 dp
        // (a short name, a long name, or a name with its role under More), so Subtitles -> Appearance equals Audio -> Subtitles
        assertEquals(setOf(LANGUAGE_GAP_DP), (22..132).map { languageColumnDp(it) - it }.toSet())
    }

    @Test fun appearanceStepsWrapAtBothEnds() {
        assertEquals(1, steppedIndex(0, 1, 4))
        assertEquals(0, steppedIndex(3, 1, 4)) // after Extra Large comes Small
        assertEquals(3, steppedIndex(0, -1, 4)) // before Small comes Extra Large
        assertEquals(5, steppedIndex(0, -1, 6))
        assertEquals(1, steppedIndex(0, 1, 2)) // Background: either arrow toggles
        assertEquals(0, steppedIndex(1, 1, 2))
        assertEquals(1, steppedIndex(0, -1, 2))
        assertEquals(0, steppedIndex(0, 1, 1))
        assertEquals(0, steppedIndex(0, 1, 0))
    }

    @Test fun appearanceChoicesAreAldersOwnLists() {
        assertEquals(1, choiceIndex(SUBTITLE_SIZES, 100)) // the default is Medium
        assertEquals(3, choiceIndex(SUBTITLE_SIZES, 160)) // Extra Large
        assertEquals(0, choiceIndex(SUBTITLE_COLORS, "white"))
        assertEquals(5, choiceIndex(SUBTITLE_COLORS, "warm_white"))
        assertEquals(0, choiceIndex(SUBTITLE_BACKGROUNDS, true)) // the default is On
        assertEquals(1, choiceIndex(SUBTITLE_BACKGROUNDS, false))
        assertEquals(0, choiceIndex(SUBTITLE_SIZES, 115)) // a value that is no choice counts as the first
        assertEquals(0, choiceIndex(SUBTITLE_COLORS, "pink"))
        // the six names are the six colours Alder draws
        assertEquals(listOf(0xFFFFFFFF, 0xFFBFBFBF, 0xFFFFE600, 0xFF00FFFF, 0xFFFFF5C0, 0xFFF3EEE6).map { it.toInt() },
            SUBTITLE_COLORS.map { subtitleColorValue(it) })
        assertEquals(4, SUBTITLE_SIZES.size)
        assertEquals(2, SUBTITLE_BACKGROUNDS.size)
    }

    @Test fun okOrRightStepsTheValueAndLeftGoesBack() {
        val ok = listOf(KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER)
        ok.forEach { assertEquals(AppearanceKey.NEXT_VALUE, appearanceKeyAction(it, false, 1, 2, 0)) }
        ok.forEach { assertEquals(AppearanceKey.NEXT_VALUE, appearanceKeyAction(it, true, 1, 2, 0)) } // OK is the same in right-to-left
        assertEquals(AppearanceKey.NEXT_VALUE, appearanceKeyAction(KeyEvent.KEYCODE_DPAD_RIGHT, false, 0, 2, 0))
        assertEquals(AppearanceKey.TO_SUBTITLES, appearanceKeyAction(KeyEvent.KEYCODE_DPAD_LEFT, false, 2, 2, 0))
        // right-to-left mirrors the arrows: Left steps, Right goes back
        assertEquals(AppearanceKey.NEXT_VALUE, appearanceKeyAction(KeyEvent.KEYCODE_DPAD_LEFT, true, 0, 2, 0))
        assertEquals(AppearanceKey.TO_SUBTITLES, appearanceKeyAction(KeyEvent.KEYCODE_DPAD_RIGHT, true, 0, 2, 0))
    }

    @Test fun aHeldKeyNeverStepsOrGoesBack() {
        // the Right that brought focus here, still held, must not change a value
        listOf(KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_DPAD_LEFT).forEach {
            assertEquals(AppearanceKey.NOTHING, appearanceKeyAction(it, false, 0, 2, 1))
            assertEquals(AppearanceKey.NOTHING, appearanceKeyAction(it, true, 0, 2, 3))
        }
        assertEquals(AppearanceKey.DOWN, appearanceKeyAction(KeyEvent.KEYCODE_DPAD_DOWN, false, 0, 2, 5)) // Up and Down may repeat
    }

    @Test fun upAndDownMoveBetweenTheRows() {
        assertEquals(AppearanceKey.NOTHING, appearanceKeyAction(KeyEvent.KEYCODE_DPAD_UP, false, 0, 2, 0)) // Up on Size stays
        assertEquals(AppearanceKey.UP, appearanceKeyAction(KeyEvent.KEYCODE_DPAD_UP, false, 1, 2, 0))
        assertEquals(AppearanceKey.DOWN, appearanceKeyAction(KeyEvent.KEYCODE_DPAD_DOWN, false, 0, 2, 0))
        assertEquals(AppearanceKey.TO_BUTTON, appearanceKeyAction(KeyEvent.KEYCODE_DPAD_DOWN, false, 2, 2, 0))
        assertNull(appearanceKeyAction(KeyEvent.KEYCODE_BACK, false, 0, 2, 0)) // Back is the menu's own
    }

    @Test fun theAppearanceColumnIsLabelGapValueAndArrow() {
        assertEquals(42 + 78 + 24 + 73 + 2 + 16 + 22, appearanceColumnDp(78, 73)) // 257: nothing floats at the far end
        assertEquals(78 + 73 + 106, appearanceColumnDp(78, 73))
        assertEquals(340, appearanceColumnDp(250, 200)) // a long translation stays on the screen
        // ...and then the label gives way, not the values: label + the rest = the column
        assertEquals(340 - 106 - 73, appearanceLabelDp(340, 73))
        assertEquals(78, appearanceLabelDp(appearanceColumnDp(78, 73), 73))
    }

    @Test fun theMenuDimIsStrongerOverHdrVideo() {
        assertEquals(0x99000000.toInt(), menuDimColor(hdr = false)) // 60%
        assertEquals(0xE6000000.toInt(), menuDimColor(hdr = true)) // 90%
        assertTrue((menuDimColor(true) ushr 24) > (menuDimColor(false) ushr 24))
    }

    @Test fun theThreeColumnsFitA960DpScreenInTheWidestCase() {
        // Audio and Subtitles at their widest, Appearance at its widest, plus the panel's padding (2 x 8) and the screen margin (48).
        val widest = 2 * languageColumnDp(400) + appearanceColumnDp(300, 300) + 2 * 8 + 48
        assertEquals(250 * 2 + 340 + 64, widest)
        assertTrue(widest <= 960)
        // the usual case: "English" columns and the real English Appearance strings
        assertEquals(170 * 2 + 257, 2 * languageColumnDp(52) + appearanceColumnDp(78, 73))
    }

    @Test fun theSampleSitsAt280OfAHeightOf1080ClearOfTheMenu() {
        assertEquals(280, sampleBottomPx(1080, 452, 24)) // a short menu: the fixed place
        assertEquals(140, sampleBottomPx(540, 226, 12)) // other heights keep the share
        assertEquals(276, sampleBottomPx(1080, 300, 24)) // a taller menu pushes it up
        assertEquals(76, sampleBottomPx(1080, 100, 24))
        assertEquals(0, sampleBottomPx(1080, 10, 24)) // never below the top
    }

    @Test fun trackNumberIsThePlaceNotTheFileId() {
        // The id of a track ("2" in an MP4, "aac:Main" in HLS, "1/257" in TS) never reaches the name.
        assertEquals("1", trackNumber(0))
        assertEquals("2", trackNumber(1))
        assertEquals("12", trackNumber(11))
    }

    @Test fun theAudioFormatTakesAtmosFromTheLabelButNeverTheReleaseGroup() {
        val truehd = MimeTypes.AUDIO_TRUEHD
        assertEquals("Dolby TrueHD Atmos 7.1", audioFormatText("Dolby TrueHD", truehd, "BTM TrueHD Atmos 7.1", "7.1"))
        assertEquals("Dolby TrueHD 7.1", audioFormatText("Dolby TrueHD", truehd, "BTM TrueHD 7.1", "7.1"))
        assertEquals("Dolby Digital Plus Atmos 5.1", audioFormatText("Dolby Digital Plus", MimeTypes.AUDIO_E_AC3, "DDP ATMOS", "5.1"))
        assertEquals("Dolby Atmos 5.1", audioFormatText("Dolby Atmos", MimeTypes.AUDIO_E_AC3_JOC, "Atmos", "5.1")) // not twice
        assertEquals("AAC Stereo", audioFormatText("AAC", MimeTypes.AUDIO_AAC, "Atmos", "Stereo")) // only a Dolby track
        assertEquals("Dolby TrueHD 7.1", audioFormatText("Dolby TrueHD", truehd, "Atmosphere", "7.1")) // whole word
        assertEquals("DTS:X 7.1", audioFormatText("DTS", MimeTypes.AUDIO_DTS, "BTM DTS-X 7.1", "7.1"))
        assertEquals("DTS:X 7.1", audioFormatText("DTS", MimeTypes.AUDIO_DTS, "DTS:X", "7.1"))
        assertEquals("DTS:X 7.1", audioFormatText("DTS", MimeTypes.AUDIO_DTS, "Movie DTSX 7.1", "7.1"))
        assertEquals("DTS 5.1", audioFormatText("DTS", MimeTypes.AUDIO_DTS, "Movie.2019.1080p.BluRay.DTS.x264-GRP", "5.1")) // x264 is a video codec
        assertEquals("DTS 5.1", audioFormatText("DTS", MimeTypes.AUDIO_DTS, "Movie.DTS-x265", "5.1"))
        assertEquals("DTS-HD MA 5.1", audioFormatText("DTS", MimeTypes.AUDIO_DTS, "DTS-HD.MA 5.1", "5.1"))
        assertEquals("DTS-HD MA 5.1", audioFormatText("DTS-HD", MimeTypes.AUDIO_DTS_HD, "BTM DTS-HD MA", "5.1"))
        assertEquals("DTS-HD MA 7.1", audioFormatText("DTS-HD", MimeTypes.AUDIO_DTS_HD, "DTS-HD Master Audio", "7.1"))
        assertEquals("DTS-HD MA 7.1", audioFormatText("DTS-HD", MimeTypes.AUDIO_DTS_HD, "DTS_HD_Master_Audio_7.1", "7.1"))
        assertEquals("DTS-HD MA 5.1", audioFormatText("DTS", MimeTypes.AUDIO_DTS, "DTS-HD.Master.Audio", "5.1"))
        assertEquals("DTS-HD 5.1", audioFormatText("DTS-HD", MimeTypes.AUDIO_DTS_HD, "DTS-HD HRA 5.1", "5.1")) // High Resolution is not MA
        assertEquals("DTS-HD 5.1", audioFormatText("DTS-HD", MimeTypes.AUDIO_DTS_HD, "DTS-HD High Resolution Audio", "5.1"))
        assertEquals("Dolby TrueHD Atmos 7.1", audioFormatText("Dolby TrueHD", truehd, "TrueHD_Atmos_7.1", "7.1")) // "_" is no word letter
        assertEquals("Dolby TrueHD 7.1", audioFormatText("Dolby TrueHD", truehd, "TrueHD_Atmosphere_7.1", "7.1"))
        assertEquals("DTS:X 7.1", audioFormatText("DTS:X", MimeTypes.AUDIO_DTS_X, "DTS-HD MA", "7.1")) // a DTS:X track stays DTS:X
        assertEquals("Dolby Digital", audioFormatText("Dolby Digital", MimeTypes.AUDIO_AC3, null, null))
        assertEquals("", audioFormatText(null, null, "BTM", null))
        assertEquals("Stereo", audioFormatText(null, null, "BTM", "Stereo"))
    }
}
