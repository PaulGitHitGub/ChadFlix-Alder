package dev.alder

import android.content.Context
import android.os.Handler
import android.os.Looper
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.exoplayer.audio.AudioCapabilities

interface Cancellable {
    fun cancel()
}

object StreamsResolver {
    const val TRY = 3

    fun rank(
        context: Context,
        streams: List<Stream>,
        videoId: String,
        metaId: String?,
        maxHeight: Int = 0
    ): List<Stream> {
        val prefs = context.getSharedPreferences("sources", Context.MODE_PRIVATE)
        val file = prefs.getString("file:$videoId", null)
        val binge = metaId?.let { prefs.getString("binge:$it", null) }
        val tv = Device.of(context)
        val preferResolution = Settings.release(context) == "remux" && Settings.hdrPref(context) == "dv"
        return rank(streams, tv, Settings.displayHeight(context), file, binge, maxHeight > 0, preference(context), preferResolution)
    }

    internal fun rank(
        streams: List<Stream>,
        tv: Device,
        displayOverride: Int,
        file: String?,
        binge: String?,
        lower: Boolean,
        prefer: (Stream) -> Int,
        preferResolution: Boolean
    ): List<Stream> {
        val displayHeight = if (displayOverride > 0) displayOverride else tv.height
        // P2P after every direct or debrid source: peers start slower and can't be checked first.
        // A P2P source its add-on says nobody shares (👤 0) can't start: after every other.
        return streams.sortedWith(compareBy<Stream> { it.torrent != null }
            .thenBy { it.torrent != null && it.seeders == 0 }
            .thenByDescending { if (lower) it.height else 0 }
            .thenByDescending { file != null && it.filename == file }
            .thenByDescending { binge != null && it.bingeGroup == binge }
            .thenBy { it.awkward }
            .thenBy { it.dolbyVisionOnly && !tv.dolbyVision }
            .thenByDescending { it.fit(tv, displayHeight) }
            .thenByDescending(prefer)
            .thenByDescending { if (preferResolution && it.height <= displayHeight) it.height else 0 }
            .thenByDescending { if (it.torrent != null) it.seeders ?: 0 else 0 })
    }

    @androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
    fun preference(context: Context): (Stream) -> Int {
        val release = Settings.release(context)
        val hdr = Settings.hdrPref(context)
        val dub = Settings.dub(context)
        val language = Settings.audioLanguage(context)
        val audio = if (Settings.decodeAudio(context)) null else AudioCapabilities.getCapabilities(context, AudioAttributes.DEFAULT, null)
        val trueHd = audio?.supportsEncoding(C.ENCODING_DOLBY_TRUEHD) == true
        val dtsHd = audio?.supportsEncoding(C.ENCODING_DTS_HD) == true
        return { s ->
            when (release) {
                "remux" -> if (s.remux) 3 else if (s.bluray) 1 else 0
                "encode" -> if (s.remux) -2 else if (s.bluray || s.web) 2 else 0
                "web" -> if (s.web && !s.remux) 2 else if (s.remux) -2 else 0
                else -> 0
            } + when (hdr) {
                "dv" -> if (s.dolbyVision) 2 else if (s.hdr) 1 else 0
                "hdr10" -> if (s.hdr) 2 else 0
                else -> 0
            } + when (dub) {
                "original" -> if (s.dubbed) -2 else 0
                "mine" -> if (s.mentions(language)) 2 else if (s.multiAudio) 1 else 0
                else -> 0
            } + if ((trueHd && s.trueHdAudio) || (dtsHd && s.dtsHdAudio)) 1 else 0
        }
    }

    fun wanted(context: Context, s: Stream, maxHeight: Int = 0): Boolean {
        val limitHeight = maxHeight.takeIf { it > 0 } ?: Settings.maxHeight(context)
        val maxBytes = Settings.maxSizeGb(context) * 1_073_741_824L
        return (limitHeight == 0 || s.height == 0 || s.height <= limitHeight) &&
            (maxBytes == 0L || s.size == 0L || s.size <= maxBytes) &&
            (Settings.hdr(context) || !s.isHdr) && !s.cam
    }

    internal fun resolve(
        context: Context,
        lookup: SourceLookup,
        videoId: String,
        metaId: String,
        mainHandler: Handler = Handler(Looper.getMainLooper()),
        onReady: (List<Stream>) -> Unit,
        onFallback: () -> Unit
    ): Cancellable {
        val tv = Device.of(context)
        var finished = false
        var waitingForEarlier = false
        lateinit var subscription: Cancellable

        fun done(action: () -> Unit) {
            finished = true
            subscription.cancel()
            action()
        }

        fun best(allowGaps: Boolean): List<Stream> {
            val answers = lookup.answers.map { answer ->
                answer?.getOrNull()?.playable?.filter { it.torrent == null }
                    ?: emptyList<Stream>().takeIf { answer != null }
            }.toTypedArray()
            val ready = readyStreams(answers, allowGaps)
            return rank(context, ready.filter { it.isVideo && it.playableOn(tv) && wanted(context, it) }, videoId, metaId)
        }

        subscription = lookup.listen {
            if (finished || lookup.providers == null) return@listen
            val settled = lookup.answers.none { it == null }
            val candidates = best(allowGaps = settled)
            if (candidates.isNotEmpty()) done { onReady(candidates.take(TRY)) }
            else if (settled) done(onFallback)
            else if (!waitingForEarlier && best(allowGaps = true).isNotEmpty()) {
                waitingForEarlier = true
                mainHandler.postDelayed({
                    if (!finished) best(allowGaps = true).takeIf { it.isNotEmpty() }?.let { ready ->
                        done { onReady(ready.take(TRY)) }
                    }
                }, 800)
            }
        }
        return object : Cancellable {
            override fun cancel() { finished = true; subscription.cancel() }
        }
    }
}
