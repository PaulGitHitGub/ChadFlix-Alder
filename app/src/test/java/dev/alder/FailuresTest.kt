package dev.alder

import androidx.media3.common.PlaybackException
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.net.SocketTimeoutException

class FailuresTest {
    @Test fun failedOrPendingSearchCanNeverReportNoMatches() {
        assertNull(searchFailure(emptyList(), completed = 2, total = 2))
        assertEquals(Failure.TIMEOUT, searchFailure(emptyList(), completed = 1, total = 2))
        assertEquals(Failure.ACCESS, searchFailure(listOf(Failure.ACCESS), completed = 2, total = 2))
    }

    @Test fun classifyKnownFailuresWithoutUsingExceptionText() {
        assertEquals(Failure.SIGN_IN, failureOf(Stremio.ApiException(Stremio.NO_SESSION, "secret")))
        assertEquals(Failure.TIMEOUT, failureOf(SocketTimeoutException("secret")))
        assertEquals(Failure.BUSY, failureOf(HttpError(429)))
        assertEquals(Failure.WEB_PAGE, failureOf(WebPageAnswer())) // a closed add-on's host page, not "invalid response"
        assertEquals(Failure.FORMAT, playbackFailure(PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED, null))
        assertEquals("error", failureReason(IllegalStateException("https://example.org/key")))
    }

    @Test fun everyFailureSaysWhatToDo() {
        val advice = Failure.entries.map { it.advice }
        assertEquals(Failure.entries.size, advice.toSet().size) // each kind has its own line
        assertTrue(Failure.entries.all { it.advice != it.message })
    }

    @Test fun offlineProgressPreservesLibraryFieldsAndResumesFromTheLocalCheckpoint() {
        val remote = JSONObject("""{"_id":"tt1","type":"movie","_mtime":"2026-11-13T10:00:00.000Z","removed":false,
            "state":{"video_id":"tt1","timeOffset":4000,"duration":100000,"timeWatched":4000,"overallTimeWatched":5000,"watched":"existing"}}""")
        val checkpoint = JSONObject("""{"_id":"tt1","type":"movie","_mtime":"2026-11-13T10:01:00.000Z","removed":true,
            "alderProvisional":true,"state":{"video_id":"tt1","timeOffset":12000,"duration":100000,"timeWatched":8000,"overallTimeWatched":8000}}""")
        assertTrue(newerProgress(checkpoint, remote))
        assertFalse(newerProgress(remote, checkpoint))
        val merged = applyProgress(checkpoint, remote)
        assertTrue(Library.inList(merged))
        assertEquals(12_000L, Library.resumeAt(merged, "tt1"))
        assertEquals(13_000L, merged.state.getLong("overallTimeWatched"))
        assertEquals("existing", merged.state.getString("watched"))
        assertFalse(merged.has("alderProvisional"))
        assertEquals(4000L, Library.resumeAt(remote, "tt1"))
    }

    @Test fun offlineProgressStaysProvisionalUntilRemoteLibraryIsAvailable() {
        val checkpoint = Library.newItem("tt1", "movie", "Movie", null).apply {
            Library.record(this, "tt1", 12_000L, 100_000L, 12_000L)
            put("_mtime", "2026-11-13T10:01:00.000Z")
            put("alderProvisional", true)
        }

        val reloaded = applyProgress(checkpoint, null)
        assertTrue(reloaded.optBoolean("alderProvisional"))

        Library.record(reloaded, "tt1", 25_000L, 100_000L, 13_000L)
        reloaded.put("_mtime", "2026-11-13T10:02:00.000Z")

        val remote = JSONObject("""{"_id":"tt1","type":"movie","_mtime":"2026-11-13T10:00:00.000Z","removed":false,
            "state":{"video_id":"tt1","timeOffset":4000,"duration":100000,"timeWatched":4000,"overallTimeWatched":5000,"watched":"existing"}}""")
        val merged = applyProgress(reloaded, remote)
        assertTrue(Library.inList(merged))
        assertEquals(25_000L, Library.resumeAt(merged, "tt1"))
        assertEquals("existing", merged.state.getString("watched"))
        assertFalse(merged.has("alderProvisional"))
    }

    @Test fun offlineEpisodeCompletionMergesWatchedBitfieldAndTimesWatched() {
        val remote = JSONObject("""{"_id":"tt2","type":"series","_mtime":"2026-11-13T10:00:00.000Z","removed":false,
            "state":{"video_id":"tt2:1:1","timeOffset":50000,"duration":1800000,"timeWatched":50000,"overallTimeWatched":50000,"timesWatched":1,"flaggedWatched":0,"watched":"tt2:1:1:1:ep1"}}""")
        val checkpoint = Library.newItem("tt2", "series", "Show", null).apply {
            Library.record(this, "tt2:1:2", 1_650_000L, 1_800_000L, 1_650_000L)
            state.put("watched", "tt2:1:2:2:ep2")
            Library.stopped(this, "tt2:1:3")
            put("_mtime", "2026-11-13T10:05:00.000Z")
            put("alderProvisional", true)
        }

        val merged = applyProgress(checkpoint, remote)
        assertTrue(Library.inList(merged))
        assertEquals("tt2:1:3", Library.savedVideoId(merged))
        assertTrue(Library.nextEpisodePending(merged))
        assertEquals("tt2:1:2:2:ep2", merged.state.text("watched"))
        assertEquals(2, merged.state.optInt("timesWatched"))
        assertEquals(0, merged.state.optInt("flaggedWatched"))
        assertFalse(merged.has("alderProvisional"))
    }

    @Test fun partialOfflineWatchDoesNotClearRemoteWatchedOrInflateTimesWatched() {
        val remote = JSONObject("""{"_id":"tt2","type":"series","_mtime":"2026-11-13T10:00:00.000Z","removed":false,
            "state":{"video_id":"tt2:1:1","timeOffset":50000,"duration":1800000,"timeWatched":50000,"overallTimeWatched":50000,"timesWatched":1,"flaggedWatched":0,"watched":"tt2:1:1:1:ep1"}}""")
        val partial = Library.newItem("tt2", "series", "Show", null).apply {
            Library.record(this, "tt2:1:2", 120_000L, 1_800_000L, 120_000L)
            put("_mtime", "2026-11-13T10:05:00.000Z")
            put("alderProvisional", true)
        }

        val mergedPartial = applyProgress(partial, remote)
        assertEquals("tt2:1:2", Library.savedVideoId(mergedPartial))
        assertEquals(120_000L, Library.resumeAt(mergedPartial, "tt2:1:2"))
        assertEquals("tt2:1:1:1:ep1", mergedPartial.state.text("watched"))
        assertEquals(1, mergedPartial.state.optInt("timesWatched"))

        // If a provisional item already carries remote's unchanged watched bitfield without completing offline, don't double-count.
        val copiedWatched = JSONObject(partial.toString()).apply { state.put("watched", "tt2:1:1:1:ep1") }
        val mergedCopied = applyProgress(copiedWatched, remote)
        assertEquals(1, mergedCopied.state.optInt("timesWatched"))

        // Older provisional checkpoint must not overwrite newer remote progress.
        val stale = JSONObject(partial.toString()).apply { put("_mtime", "2026-11-13T09:59:00.000Z") }
        val keptRemote = applyProgress(stale, remote)
        assertEquals("tt2:1:1", Library.savedVideoId(keptRemote))
        assertEquals(50_000L, Library.resumeAt(keptRemote, "tt2:1:1"))
    }

    @Test fun offlineEpisodeCompletionWithoutCachedVideosStillCountsWatchAndDoesNotMutateRemote() {
        val remote = JSONObject("""{"_id":"tt3","type":"series","_mtime":"2026-11-13T10:00:00.000Z","removed":false}""")
        val checkpoint = Library.newItem("tt3", "series", "Show", null).apply {
            Library.record(this, "tt3:1:1", 1_650_000L, 1_800_000L, 1_650_000L)
            Library.stopped(this, "tt3:1:2")
            put("_mtime", "2026-11-13T10:05:00.000Z")
            put("alderProvisional", true)
        }

        val merged = applyProgress(checkpoint, remote)
        assertFalse(remote.has("state"))
        assertEquals("tt3:1:2", Library.savedVideoId(merged))
        assertTrue(Library.nextEpisodePending(merged))
        assertEquals(1, merged.state.optInt("timesWatched"))
        assertEquals(0, merged.state.optInt("flaggedWatched"))
        assertFalse(merged.has("alderProvisional"))
    }
}
