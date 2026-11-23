package dev.alder

import androidx.media3.common.C

// ChadFlix: what the player's Audio & Subtitles menu and Info pop-up decide, kept apart from the views so it can be tested.

/** One audio or subtitle track as the menu sees it. [plain]: not forced, SDH or commentary. [off]: the Subtitles' Off row. */
internal data class MenuTrack(
    val id: String,
    val language: String?,
    val selected: Boolean = false,
    val plain: Boolean = true,
    val addon: Boolean = false,
    val off: Boolean = false,
)

/**
 * The rows a column shows before "More": Off, the track in use, the best plain English track, and the best plain track
 * in the title's [originalLanguage] (null: unknown, so only English and the track in use). "English" is literal, not the
 * TV's language. The best track of a language is a plain one, from the file before an add-on, then in file order; a
 * language with no plain track gives its first. A track with no language shows only when it is in use. Every track not
 * listed here is behind More.
 */
internal fun defaultTrackIds(tracks: List<MenuTrack>, originalLanguage: String?): Set<String> {
    val shown = HashSet<String>()
    tracks.forEach { if (it.off || it.selected) shown += it.id }
    fun best(language: String?) = language?.let { code ->
        tracks.filter { !it.off && Languages.match(it.language, code) > 0 }.sortedWith(compareBy({ !it.plain }, { it.addon })).firstOrNull()
    }
    best("en")?.let { shown += it.id }
    best(originalLanguage)?.let { shown += it.id }
    return shown
}

private val ODD_LABEL = Regex("""\b(sdh|cc|hoh|hearing|forced|commentary|description|descriptive|captions?)\b""", RegexOption.IGNORE_CASE)

/** A track that is not forced-only, for the deaf and hard of hearing, a commentary or a description for the blind. */
internal fun plainTrack(selectionFlags: Int, roleFlags: Int, label: String?): Boolean {
    if (selectionFlags and C.SELECTION_FLAG_FORCED != 0) return false
    val odd = C.ROLE_FLAG_COMMENTARY or C.ROLE_FLAG_DESCRIBES_MUSIC_AND_SOUND or C.ROLE_FLAG_DESCRIBES_VIDEO or C.ROLE_FLAG_CAPTION
    return roleFlags and odd == 0 && !ODD_LABEL.containsMatchIn(label.orEmpty())
}

/** When the title ends in wall-clock ms, or null for a trailer, live TV or a length not known. */
internal fun endsAtTime(now: Long, duration: Long, position: Long, speed: Float, trailer: Boolean, live: Boolean): Long? =
    if (trailer || live || duration <= 0) null else now + ((duration - position).coerceAtLeast(0) / speed).toLong()

/** "2 hr 52 mins"; null for no length. The screen passes its words. */
internal fun runtimeWords(ms: Long, hoursMinutes: String = "%1\$d hr %2\$d mins", hours: String = "%1\$d hr",
                          minutes: String = "%1\$d mins"): String? {
    val total = (ms / 60_000).toInt()
    return when {
        total <= 0 -> null
        total < 60 -> minutes.format(total)
        total % 60 == 0 -> hours.format(total / 60)
        else -> hoursMinutes.format(total / 60, total % 60)
    }
}

/** The Info pop-up's facts line, "2026 • R • 2 hr 52 mins": the year, the content rating and the length, whichever are known. */
internal fun infoFacts(year: String?, certificate: String?, runtime: String?): String =
    listOfNotNull(year, certificate, runtime).map { it.trim() }.filter { it.isNotEmpty() }.joinToString(" • ")

/** The Info pop-up's rows, top to bottom: the logo (or name) is always there, the others only when there is something to show. */
internal enum class InfoRow { LOGO, FACTS, PLOT, RATINGS, BADGES }

internal const val INFO_META_H = 20 // dp, a ratings or badges row
internal const val INFO_BADGE_GAP = 8 // dp between badges
internal const val INFO_BOTTOM = 183 // dp from the screen's bottom: 48 above the seek bar's top (SEEK_BAR_BOTTOM + SEEK_BAR_H)

/**
 * dp between a row and the row above it that is showing, on a 4 / 8 / 12 / 16 dp scale: the logo then the facts 12, the facts
 * and plot one block 8, a 16 break before the ratings and badges, which sit 8 apart as one group. Asked of the row above that
 * is actually there, so a missing row (no ratings, no plot) leaves no gap behind it.
 */
internal fun infoGap(above: InfoRow, row: InfoRow): Int = when {
    above == InfoRow.LOGO -> 12
    row == InfoRow.BADGES && above == InfoRow.RATINGS -> 8
    row == InfoRow.RATINGS || row == InfoRow.BADGES -> 16
    else -> 8
}

/** What a track's menu row says: [name] (the language), [detail] (label and/or codec and channels, under More) and [note] (the label alone). */
internal class TrackText(val name: String, val detail: String, val note: String)

/**
 * The text of one track's row. [label] is the file's own label (already without the language); [audio] is false for subtitles; [format] is the audio codec and
 * channels, or null (subtitles, or unknown). [unnamed] is the "Track N" name. The name is the [language]. A track with no language
 * is named by its label only when that label is descriptive (not plain), so a codec never becomes the name; otherwise it is
 * [unnamed]. A subtitle track with no language keeps its label as the name. The detail is the format; a plain label is left out
 * when there is a format ("English" is no use there), and a descriptive one comes first: "Commentary · Dolby Digital 5.1".
 */
internal fun trackText(audio: Boolean, language: String?, label: String?, format: String?, selectionFlags: Int, roleFlags: Int, unnamed: String): TrackText {
    val plain = plainTrack(selectionFlags, roleFlags, label)
    val name = language ?: if (!audio) label ?: unnamed else label?.takeIf { !plain } ?: unnamed
    val shownLabel = label?.takeIf { it != name && (!plain || !audio || format == null) }
    return TrackText(name, listOfNotNull(shownLabel, format).joinToString(" · "), trackNote(language, label, selectionFlags, roleFlags))
}

/**
 * The note on a track's row, the file's own [label] (already without the language), shown before More only for a track
 * that is not plain: a commentary, a description, SDH or forced. A label like "DD+ 5.1" or "English" on a plain track is
 * no note, and a track with no [language] has none.
 */
internal fun trackNote(language: String?, label: String?, selectionFlags: Int, roleFlags: Int): String =
    if (language != null && label != null && !plainTrack(selectionFlags, roleFlags, label)) label else ""

/** One audio row as [audioDetails] sees it. */
internal class AudioText(val name: String, val detail: String, val note: String)

/**
 * The detail each audio row shows. Under More it is all of it. Before More only the [AudioText.note] (a label such as
 * "Commentary"), not codec and channels, except that rows with the same name show all of it, so two English tracks look different.
 */
internal fun audioDetails(rows: List<AudioText>, expanded: Boolean): List<String> =
    rows.map { r -> if (expanded || rows.count { it.name.equals(r.name, ignoreCase = true) } > 1) r.detail else r.note }

/** Whether the Audio column is wide: under More, or when any row shows a detail. Narrow keeps Subtitles on screen. */
internal fun audioColumnWide(expanded: Boolean, details: List<String>): Boolean = expanded || details.any { it.isNotEmpty() }

internal const val SEEK_BAR_BOTTOM = 111 // dp from the screen's bottom to the seek bar
internal const val SEEK_BAR_H = 24 // dp, the seek bar's height
