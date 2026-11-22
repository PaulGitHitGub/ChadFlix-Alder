package dev.alder

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StreamsResolverTest {

    private fun stream(
        title: String = "Test Stream",
        url: String = "https://example.com/stream.mkv",
        filename: String? = "test.mkv",
        size: Long = 1_000_000_000L,
        bingeGroup: String? = null
    ): Stream = Stream(
        title = title,
        description = "",
        url = url,
        headers = emptyMap(),
        size = size,
        filename = filename,
        bingeGroup = bingeGroup
    )

    @Test
    fun readyStreamsStopsAtNullWhenGapsDisallowed() {
        val s1 = stream("s1")
        val s2 = stream("s2")
        val s3 = stream("s3")
        val answers: Array<List<Stream>?> = arrayOf(listOf(s1), null, listOf(s3))

        val withoutGaps = readyStreams(answers, allowGaps = false)
        assertEquals(listOf(s1), withoutGaps)

        val withGaps = readyStreams(answers, allowGaps = true)
        assertEquals(listOf(s1, s3), withGaps)
    }

    @Test
    fun automaticPlaybackExhaustsPreferredMatchesBeforeFallbacks() {
        val hindi = stream("Movie 1080p Hindi", filename = "last-played.mkv")
        val unknown = stream("Movie 1080p MULTI")
        val polish = stream("Movie 1080p Polish")
        val polishBackup = requireNotNull(Stream.from(JSONObject()
            .put("name", "Movie 1080p")
            .put("infoHash", "1234567890abcdef1234567890abcdef12345678")
            .put("behaviorHints", JSONObject().put("filename", "Movie.POL.mkv")), allowTorrents = true))
        val morePolish = listOf(stream("Another Polish release"), stream("Fourth Polish release"))
        val french = stream("Movie 1080p French")
        val english = stream("Movie 1080p English")
        val answers: Array<List<Stream>?> = arrayOf(listOf(hindi, unknown, english, french), null, listOf(polish, polishBackup) + morePolish)
        val tv = Device(hevc = true, av1 = false, dolbyVision = false, hdr = true, height = 1080)

        val ranked = StreamsResolver.rank(
            readyStreams(answers, allowGaps = true),
            tv, 0, hindi.filename, null, false, { 0 }
        )
        val polishAttempts = (listOf(polish) + morePolish + polishBackup).map { PlaybackSource(it, "pl") }
        val englishAttempts = listOf(PlaybackSource(unknown, "en"), PlaybackSource(english, "en"))

        assertEquals(polishAttempts, StreamsResolver.playbackQueue(ranked, StreamsResolver.languageOrder("pl", "")))
        assertEquals(polishAttempts + englishAttempts, StreamsResolver.playbackQueue(ranked, StreamsResolver.languageOrder("pl", "en")))
        assertEquals(polishAttempts + PlaybackSource(french, "fr") + englishAttempts,
            StreamsResolver.playbackQueue(ranked, StreamsResolver.languageOrder("pl", "fr")))
        assertEquals(listOf("en", "fr"), StreamsResolver.languageOrder("en", "fr"))
        assertEquals(listOf("pt-BR", "en"), StreamsResolver.languageOrder("pt-BR", "pt"))
        assertEquals(listOf(hindi, unknown, english), StreamsResolver.playbackQueue(ranked, emptyList()).map { it.stream })
    }

    @Test
    fun ranksSharpestSourceThatFitsFirst() {
        val sd = stream("Movie 720p WEB-DL")
        val hd = stream("Movie 1080p WEB-DL")
        val uhd = stream("Movie 2160p WEB-DL")
        val tv = Device(hevc = true, av1 = false, dolbyVision = false, hdr = true, height = 1080)
        val uhdTv = Device(hevc = true, av1 = false, dolbyVision = false, hdr = false, height = 2160)

        fun rank(device: Device, override: Int = 0, prefer: (Stream) -> Int = { 0 }) =
            StreamsResolver.rank(listOf(sd, hd, uhd), device, override, null, null, false, prefer)

        assertEquals(listOf(hd, sd, uhd), rank(tv))
        assertEquals(listOf(uhd, hd, sd), rank(uhdTv))
        assertEquals(listOf(uhd, hd, sd), rank(tv, override = 2160))
        assertEquals(listOf(hd, sd, uhd), rank(uhdTv, override = 1080))
        // A viewer's preference (language, release, HDR) still beats resolution.
        assertEquals(listOf(sd, uhd, hd), rank(uhdTv, prefer = { if (it === sd) 1 else 0 }))
    }

    @Test
    fun wantedFiltersCamReleases() {
        val cam = stream("Movie (CAM)")
        assertTrue(cam.cam)

        val nonCam = stream("Movie 1080p BluRay")
        assertFalse(nonCam.cam)
    }

    @Test
    fun cancellableCanBeCanceled() {
        var canceled = false
        val c = object : Cancellable {
            override fun cancel() { canceled = true }
        }
        assertFalse(canceled)
        c.cancel()
        assertTrue(canceled)
    }

    private fun streamAddon(id: String, name: String, transportUrl: String): Addon = Addon(
        JSONObject()
            .put("transportUrl", transportUrl)
            .put(
                "manifest",
                JSONObject()
                    .put("id", id)
                    .put("name", name)
                    .put("resources", JSONArray().put("stream"))
                    .put("types", JSONArray().put("movie"))
            )
    )

    @Test
    fun mergeStreamAddonsPrefersOwnAddonsOverLenderAddons() {
        val ownTorrentio = streamAddon("com.torrentio", "Own Torrentio", "https://torrentio.example/own/manifest.json")
        val ownAio = streamAddon("com.aiostreams.own", "Own AIO", "https://aio.example/shared/manifest.json")
        val lenderTorrentio = streamAddon("com.torrentio", "Lender Torrentio", "https://torrentio.example/lender/manifest.json")
        val lenderAioSameUrl = streamAddon("com.aiostreams.lender", "Lender AIO", "https://aio.example/shared/manifest.json")
        val lenderComet = streamAddon("com.comet", "Lender Comet", "https://comet.example/lender/manifest.json")

        val merged = StreamsResolver.mergeStreamAddons(
            own = listOf(ownTorrentio, ownAio),
            lent = listOf(lenderTorrentio, lenderAioSameUrl, lenderComet),
            type = "movie",
            videoId = "tt1234567"
        )

        assertEquals(listOf(ownTorrentio, ownAio, lenderComet), merged)
    }
}
