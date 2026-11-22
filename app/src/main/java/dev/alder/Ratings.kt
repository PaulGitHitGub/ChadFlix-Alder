package dev.alder

import org.json.JSONObject
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

const val LETTERBOXD = "Letterboxd"
const val ROTTEN = "RT"

/**
 * Letterboxd's average for a film, "3.9" out of 5, from MDBList's ratings. `score` is 0–100 on every MDBList endpoint;
 * `value` is Letterboxd's own 0–5 on the single-title one but 0–10 on the batch one, so it's used only when it agrees.
 */
internal fun parseLetterboxd(json: JSONObject): String? {
    val r = json.optJSONArray("ratings").objects().firstOrNull { it.optString("source") == "letterboxd" } ?: return null
    val score = r.optDouble("score").takeIf { !it.isNaN() && it > 0 } ?: return null
    val stars = r.optDouble("value").takeIf { it in 0.0..5.0 && Math.abs(it * 20 - score) <= 2 } ?: (score / 20)
    return String.format(Locale.US, "%.1f", stars)
}

/** Rotten Tomatoes' critics score, "92%", from MDBList's ratings ("tomatoes"; `score` is the percentage). */
internal fun parseTomatoes(json: JSONObject): String? {
    val r = json.optJSONArray("ratings").objects().firstOrNull { it.optString("source") == "tomatoes" } ?: return null
    val score = r.optDouble("score").takeIf { !it.isNaN() && it > 0 } ?: return null
    return "${Math.round(score)}%"
}

/** MDBList's sources worth showing, by source name ("tomatoes", "letterboxd"); a source it has nothing for is left out. */
internal fun parseRatings(json: JSONObject): Map<String, String> =
    listOfNotNull(parseTomatoes(json)?.let { "tomatoes" to it }, parseLetterboxd(json)?.let { "letterboxd" to it }).toMap()

/**
 * The ratings to show, in order, for Settings → Ratings ("imdb", "letterboxd", "both"); IMDb stands in for a missing
 * Letterboxd. "both" adds Rotten Tomatoes between them when [tomatoes] is known (title pages only).
 */
internal fun shownRatings(pick: String, imdb: String?, letterboxd: String?, tomatoes: String? = null): List<Pair<String, String>> {
    val i = imdb?.takeIf { it.isNotBlank() }?.let { "IMDb" to it }
    val l = letterboxd?.let { LETTERBOXD to it }
    val t = tomatoes?.let { ROTTEN to it }
    return when (pick) {
        "letterboxd" -> listOfNotNull(l ?: i)
        "both" -> listOfNotNull(i, t, l)
        else -> listOfNotNull(i)
    }
}

// An empty map is a title MDBList has nothing for, or a request that failed (asked again next start).
private val ratingsCache = ConcurrentHashMap<String, Map<String, String>>()

/** What's known without the network: this session's answer, or null when it has to be asked. */
internal fun knownRatings(imdbId: String): Map<String, String>? = ratingsCache[imdbId]?.ifEmpty { null }

internal fun knownLetterboxd(imdbId: String): String? = knownRatings(imdbId)?.get("letterboxd")

internal fun forgetLetterboxd() = ratingsCache.clear()

/**
 * A title's Rotten Tomatoes and Letterboxd scores through MDBList, with the viewer's own API key (free, 1,000 requests
 * a day), one request for both. Answers are kept on disk, so Home asks once per film; [fresh] (the title page) asks
 * again to keep them current. [type] picks MDBList's movie or show endpoint.
 */
internal fun mdbRatings(imdbId: String, apiKey: String, type: String = "movie", fresh: Boolean = false): Map<String, String>? {
    if (!imdbId.startsWith("tt") || apiKey.isBlank()) return null
    if (!fresh) ratingsCache[imdbId]?.let { return it.ifEmpty { null } }
    val kind = if (type == "movie") "movie" else "show"
    val url = "https://api.mdblist.com/imdb/$kind/$imdbId?apikey=${android.net.Uri.encode(apiKey)}"
    val saved = Http.savedAnswer(url)
    val raw = if (saved != null && !fresh) saved else runCatching { Http.get(url, keep = true, timeoutMs = 5_000, strict = true) }
        .onFailure { android.util.Log.w("Alder", "MDBList ratings for $imdbId failed: ${it.javaClass.simpleName}") }
        .getOrNull() ?: saved
    val answered = raw?.let { runCatching { JSONObject(it) }.getOrNull() }?.takeIf { it.has("ratings") }
    val found = answered?.let { runCatching { parseRatings(it) }.getOrNull() }.orEmpty()
    // ChadFlix: only a real answer is remembered; an error body (429, 404) must not hide a good saved one.
    if (answered != null) ratingsCache[imdbId] = found
    return found.ifEmpty { null }
}

internal fun letterboxd(imdbId: String, apiKey: String, fresh: Boolean = false): String? =
    mdbRatings(imdbId, apiKey, "movie", fresh)?.get("letterboxd")
