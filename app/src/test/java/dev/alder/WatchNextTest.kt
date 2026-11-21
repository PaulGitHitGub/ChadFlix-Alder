package dev.alder

import android.content.Context
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.lang.reflect.Modifier
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class WatchNextTest {
    @Test
    fun parsesIso8601Timestamps() {
        val timeWithoutMillis = WatchNext.parseIsoTimestamp("2026-10-28T05:25:04Z")
        assertEquals(1793165104000L, timeWithoutMillis)

        val timeWithMillis = WatchNext.parseIsoTimestamp("2026-10-28T05:25:04.123Z")
        // Second precision is consistent across both API >= 26 and API < 26 fallback
        assertEquals(1793165104000L, timeWithMillis / 1000 * 1000)
    }

    @Test
    fun fallsBackToCurrentTimeOnInvalidOrMissingTimestamp() {
        val before = System.currentTimeMillis()
        val timeEmpty = WatchNext.parseIsoTimestamp("")
        val timeInvalid = WatchNext.parseIsoTimestamp("not-a-date")
        val after = System.currentTimeMillis()

        assertTrue(timeEmpty in before..after)
        assertTrue(timeInvalid in before..after)
    }

    @Test
    fun syncMethodIsSynchronized() {
        val syncMethod = WatchNext::class.java.declaredMethods.single {
            (it.name == "sync" || it.name.startsWith("sync-")) &&
                it.parameterTypes.contentEquals(
                    arrayOf(Context::class.java, String::class.java, List::class.java, Boolean::class.javaPrimitiveType)
                )
        }
        assertTrue(Modifier.isSynchronized(syncMethod.modifiers))
    }

    @Test
    fun syncRowsResolvesBackdropsBeforeDeletingAndCapsAtTen() {
        val events = mutableListOf<String>()
        val items = (1..12).map { JSONObject().put("_id", "tt$it").put("name", "Title $it") }

        WatchNext.syncRows(
            items = items,
            locked = false,
            resolveBackdrop = { item ->
                val id = item.getString("_id")
                events += "resolve:$id"
                "https://img.example/$id.jpg"
            },
            deleteExisting = { events += "delete" },
            insertRows = { entries ->
                events += "insert:${entries.joinToString(",") { "${it.first.getString("_id")}=${it.second}" }}"
            },
        )

        val expectedResolves = (1..10).map { "resolve:tt$it" }
        val expectedInsert = "insert:" + (1..10).joinToString(",") { "tt$it=https://img.example/tt$it.jpg" }
        assertEquals(expectedResolves + "delete" + expectedInsert, events)
    }

    @Test
    fun lockedProfileDeletesExistingRowsWithoutResolvingOrInserting() {
        val events = mutableListOf<String>()
        val items = listOf(JSONObject().put("_id", "tt1").put("name", "Secret"))

        WatchNext.syncRows(
            items = items,
            locked = true,
            resolveBackdrop = { events += "resolve"; "https://img.example/tt1.jpg" },
            deleteExisting = { events += "delete" },
            insertRows = { events += "insert" },
        )

        assertEquals(listOf("delete"), events)
    }

    @Test
    fun concurrentSyncCallsSerializeSoLockedProfileNeverLeaksUnlockedRows() {
        val rows = mutableListOf("stale")
        val events = mutableListOf<String>()
        val resolvingUnlocked = CountDownLatch(1)
        val allowUnlockedToFinish = CountDownLatch(1)
        val unlockedFinished = CountDownLatch(1)
        val lockedFinished = CountDownLatch(1)

        val unlockedThread = Thread {
            WatchNext.syncRows(
                items = listOf(JSONObject().put("_id", "tt1")),
                locked = false,
                resolveBackdrop = {
                    events += "unlocked:resolve:start"
                    resolvingUnlocked.countDown()
                    assertTrue(allowUnlockedToFinish.await(5, TimeUnit.SECONDS))
                    events += "unlocked:resolve:end"
                    "https://img.example/tt1.jpg"
                },
                deleteExisting = {
                    events += "unlocked:delete"
                    rows.clear()
                },
                insertRows = { entries ->
                    events += "unlocked:insert"
                    rows += entries.map { it.first.getString("_id") }
                },
            )
            unlockedFinished.countDown()
        }

        val lockedThread = Thread {
            assertTrue(resolvingUnlocked.await(5, TimeUnit.SECONDS))
            WatchNext.syncRows(
                items = listOf(JSONObject().put("_id", "tt2")),
                locked = true,
                resolveBackdrop = { events += "locked:resolve"; null },
                deleteExisting = {
                    events += "locked:delete"
                    rows.clear()
                },
                insertRows = { events += "locked:insert" },
            )
            lockedFinished.countDown()
        }

        unlockedThread.start()
        lockedThread.start()

        assertTrue(resolvingUnlocked.await(5, TimeUnit.SECONDS))
        // Give lockedThread time to block on the WatchNext monitor while unlockedThread waits inside resolveBackdrop.
        Thread.sleep(50)
        assertFalse(events.contains("locked:delete"))
        allowUnlockedToFinish.countDown()

        assertTrue(unlockedFinished.await(5, TimeUnit.SECONDS))
        assertTrue(lockedFinished.await(5, TimeUnit.SECONDS))
        assertTrue(rows.isEmpty())
        assertEquals(
            listOf("unlocked:resolve:start", "unlocked:resolve:end", "unlocked:delete", "unlocked:insert", "locked:delete"),
            events,
        )
    }

    @Test
    fun syncRowsAbortsWithoutModifyingWhenStaleBeforeResolve() {
        val events = mutableListOf<String>()
        val items = listOf(JSONObject().put("_id", "tt1"), JSONObject().put("_id", "tt2"))

        WatchNext.syncRows(
            items = items,
            locked = false,
            isStale = { true },
            resolveBackdrop = { events += "resolve:${it.getString("_id")}"; "https://img.example/a.jpg" },
            deleteExisting = { events += "delete" },
            insertRows = { events += "insert" },
        )

        assertTrue(events.isEmpty())
    }

    @Test
    fun syncRowsAbortsWithoutDeletingOrInsertingWhenInvalidatedDuringResolveBackdrop() {
        val multiEvents = mutableListOf<String>()
        val token1 = WatchNext.currentGeneration()
        val multiItems = listOf(JSONObject().put("_id", "tt1"), JSONObject().put("_id", "tt2"))

        WatchNext.syncRows(
            items = multiItems,
            locked = false,
            isStale = { WatchNext.isGenerationStale(token1) },
            resolveBackdrop = { item ->
                val id = item.getString("_id")
                multiEvents += "resolve:$id"
                if (id == "tt1") WatchNext.invalidate()
                "https://img.example/$id.jpg"
            },
            deleteExisting = { multiEvents += "delete" },
            insertRows = { multiEvents += "insert" },
        )

        assertEquals(listOf("resolve:tt1"), multiEvents)

        val singleEvents = mutableListOf<String>()
        val token2 = WatchNext.currentGeneration()
        WatchNext.syncRows(
            items = listOf(JSONObject().put("_id", "tt3")),
            locked = false,
            isStale = { WatchNext.isGenerationStale(token2) },
            resolveBackdrop = { item ->
                singleEvents += "resolve:${item.getString("_id")}"
                WatchNext.invalidate()
                "https://img.example/tt3.jpg"
            },
            deleteExisting = { singleEvents += "delete" },
            insertRows = { singleEvents += "insert" },
        )

        assertEquals(listOf("resolve:tt3"), singleEvents)
    }

    @Test
    fun invalidateAdvancesGenerationAndMarksPriorTokensStale() {
        val firstToken = WatchNext.currentGeneration()
        assertFalse(WatchNext.isGenerationStale(firstToken))

        WatchNext.invalidate()
        val secondToken = WatchNext.currentGeneration()

        assertTrue(secondToken > firstToken)
        assertTrue(WatchNext.isGenerationStale(firstToken))
        assertFalse(WatchNext.isGenerationStale(secondToken))
    }

    @Test
    fun isProfileStaleChecksGenerationAuthKeyAndPinLock() {
        val unlocked = Profile(id = "1", name = "A", color = 0, authKey = "key-1", pin = null)
        val locked = Profile(id = "2", name = "B", color = 0, authKey = "key-2", pin = "1234")
        val sameKeyLocked = Profile(id = "1", name = "A", color = 0, authKey = "key-1", pin = "1234")

        val token = WatchNext.currentGeneration()
        assertFalse(WatchNext.isProfileStale(unlocked, "key-1", locked = false, expectedGeneration = token))
        assertTrue(WatchNext.isProfileStale(null, "key-1", locked = false, expectedGeneration = token))
        assertTrue(WatchNext.isProfileStale(locked, "key-1", locked = false, expectedGeneration = token))
        assertTrue(WatchNext.isProfileStale(sameKeyLocked, "key-1", locked = false, expectedGeneration = token))
        assertFalse(WatchNext.isProfileStale(locked, "key-2", locked = true, expectedGeneration = token))

        WatchNext.invalidate()
        assertTrue(WatchNext.isProfileStale(unlocked, "key-1", locked = false, expectedGeneration = token))
    }

    @Test
    fun invalidateDoesNotBlockOnInFlightSyncAndAbortsStaleInsert() {
        val rows = mutableListOf("previous-profile")
        val events = mutableListOf<String>()
        val resolving = CountDownLatch(1)
        val invalidated = CountDownLatch(1)
        val finished = CountDownLatch(1)
        val token = WatchNext.currentGeneration()

        val staleThread = Thread {
            WatchNext.syncRows(
                items = listOf(JSONObject().put("_id", "tt-old")),
                locked = false,
                isStale = { WatchNext.isGenerationStale(token) },
                resolveBackdrop = {
                    events += "old:resolve"
                    resolving.countDown()
                    assertTrue(invalidated.await(5, TimeUnit.SECONDS))
                    "https://img.example/old.jpg"
                },
                deleteExisting = {
                    events += "old:delete"
                    rows.clear()
                },
                insertRows = {
                    events += "old:insert"
                    rows += "tt-old"
                },
            )
            finished.countDown()
        }

        staleThread.start()
        assertTrue(resolving.await(5, TimeUnit.SECONDS))
        WatchNext.invalidate()
        invalidated.countDown()

        assertTrue(finished.await(5, TimeUnit.SECONDS))
        assertEquals(listOf("old:resolve"), events)
        assertEquals(listOf("previous-profile"), rows)
    }
}
