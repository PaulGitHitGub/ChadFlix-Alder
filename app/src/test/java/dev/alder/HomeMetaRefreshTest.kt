package dev.alder

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.IOException

class HomeMetaRefreshTest {
    private val stale = Meta(JSONObject().put("id", "tt1").put("type", "series").put("name", "Stale"))
    private val fresh = Meta(JSONObject().put("id", "tt1").put("type", "series").put("name", "Fresh"))
    private val ttlMs = 6 * 60 * 60 * 1000L

    @Test
    fun freshCacheSkipsRemoteFetch() {
        var remoteCalled = false
        val got = resolveMetaWithOfflineFallback(
            ttl = ttlMs,
            readSaved = { age -> if (age == ttlMs) fresh else null },
            fetchRemote = { remoteCalled = true; fresh },
        )
        assertEquals("Fresh", got?.name)
        assertEquals(false, remoteCalled)
    }

    @Test
    fun expiredCacheRefreshesOnlineWhenAvailable() {
        val seenAges = mutableListOf<Long?>()
        val got = resolveMetaWithOfflineFallback(
            ttl = ttlMs,
            readSaved = { age -> seenAges += age; if (age == null) stale else null },
            fetchRemote = { fresh },
        )
        assertEquals("Fresh", got?.name)
        assertEquals(listOf<Long?>(ttlMs), seenAges)
    }

    @Test
    fun expiredCacheFallsBackToDiskWhenOffline() {
        val seenAges = mutableListOf<Long?>()
        val got = resolveMetaWithOfflineFallback(
            ttl = ttlMs,
            readSaved = { age -> seenAges += age; if (age == null) stale else null },
            fetchRemote = { throw IOException("offline") },
        )
        assertEquals("Stale", got?.name)
        assertEquals(listOf<Long?>(ttlMs, null), seenAges)
    }

    @Test
    fun expiredCacheFallsBackToDiskWhenRemoteReturnsNull() {
        val got = resolveMetaWithOfflineFallback(
            ttl = ttlMs,
            readSaved = { age -> if (age == null) stale else null },
            fetchRemote = { null },
        )
        assertEquals("Stale", got?.name)
    }

    @Test
    fun noTtlDoesNotReReadDiskAfterMissAndOfflineFailure() {
        var reads = 0
        val got = resolveMetaWithOfflineFallback(
            ttl = null,
            readSaved = { reads++; null },
            fetchRemote = { throw IOException("offline") },
        )
        assertNull(got)
        assertEquals(1, reads)
    }
}
