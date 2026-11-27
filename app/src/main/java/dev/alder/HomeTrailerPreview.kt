package dev.alder

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Matrix
import android.graphics.Rect
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.TextureView
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.MimeTypes
import androidx.media3.common.Timeline
import androidx.media3.common.Tracks
import androidx.media3.common.audio.AudioFocusRequestCompat
import androidx.media3.common.audio.AudioManagerCompat
import androidx.media3.common.VideoSize
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.Renderer
import androidx.media3.exoplayer.RendererCapabilities
import androidx.media3.exoplayer.audio.AudioRendererEventListener
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.ForwardingAudioSink
import androidx.media3.exoplayer.audio.MediaCodecAudioRenderer
import androidx.media3.exoplayer.mediacodec.MediaCodecSelector
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector
import java.net.URI
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume

private fun directTrailer(url: String): URI? {
    val uri = runCatching { URI(url) }.getOrNull() ?: return null
    val host = uri.host?.lowercase()?.trimEnd('.') ?: return null
    if (!uri.scheme.equals("https", true) && !uri.scheme.equals("http", true)) return null
    return uri.takeUnless { youtubeHosts.any { host == it || host.endsWith(".$it") } }
}

private val youtubeHosts = listOf("youtube.com", "youtu.be", "youtube-nocookie.com", "googlevideo.com", "youtube.googleapis.com")

internal fun imdbPreviewUrl(streams: List<Stream>): String? {
    for (quality in listOf("720p", "480p", "SD")) {
        streams.firstOrNull {
            it.description.equals(quality, true) && directTrailer(it.url)?.path?.endsWith(".mp4", true) == true
        }?.let { return it.url }
    }
    return null
}

internal fun addonPreviewUrl(streams: List<Stream>): String? = streams.firstOrNull { directTrailer(it.url) != null }?.url

private const val MOVIE_PREVIEW_ZOOM = 1.35f
private const val PREVIEW_LONGER_THAN_MS = 60_000L
private const val PREVIEW_SKIP_MS = 6_000L

internal fun previewStartMs(durationMs: Long): Long =
    if (durationMs == C.TIME_UNSET || durationMs > PREVIEW_LONGER_THAN_MS) PREVIEW_SKIP_MS else 0L

/** Texture coordinates for a center crop, including anamorphic SD trailers with non-square pixels. */
internal fun previewCrop(videoW: Int, videoH: Int, pixelAspect: Float, cardW: Int, cardH: Int, movie: Boolean): Pair<Float, Float>? {
    if (minOf(videoW, videoH, cardW, cardH) <= 0 || pixelAspect <= 0f) return null
    val ratio = (videoW * pixelAspect / videoH) / (cardW.toFloat() / cardH)
    val zoom = if (movie) MOVIE_PREVIEW_ZOOM else 1f
    return zoom * maxOf(1f, ratio) to zoom * maxOf(1f, 1f / ratio)
}

internal fun previewAudioAllowed(mime: String?, channels: Int): Boolean =
    channels in 1..2 && (mime == MimeTypes.AUDIO_AAC || mime == MimeTypes.AUDIO_RAW)

// Adapted from NuvioTV's TrailerPlayerPool, TrailerPlayer and ModernHomeContent (GPL-3.0).
// Home owns one player across focus changes and yields it before another activity takes over.
internal class HomeTrailerPreview(private val context: Context, private val held: () -> Boolean, private val moving: () -> Boolean) : Player.Listener {
    private var player: ExoPlayer? = null
    private var watchJob: Job? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val main = Handler(Looper.getMainLooper())
    private var output: TextureView? = null
    private val visible = Rect()
    private var activeCard: MediaCardView? = null
    private var mayPlay: (() -> Boolean)? = null
    private var fade: ValueAnimator? = null
    private var volumeFade: ValueAnimator? = null
    private var focusRequest: AudioFocusRequestCompat? = null
    private var movie = false
    private var awaitingStart = false
    private var firstFrameShown = false
    private var triedFocus = false

    private fun fullyVisible(card: View) = card.width > 0 && card.height > 0 && card.getGlobalVisibleRect(visible) &&
        visible.width() == card.width && visible.height() == card.height

    fun watch(card: MediaCardView, id: String, isMovie: Boolean, remainingDwell: Long, motionSettled: () -> Boolean, still: () -> Boolean,
              metadataSettled: () -> Boolean, addonUrl: () -> String?) {
        if (watchJob != null) stop()
        val titleDwellAt = SystemClock.uptimeMillis()
        watchJob = scope.launch {
            if (!still() || !card.awaitMotion(motionSettled) || !still() || !fullyVisible(card)) return@launch
            val cached = cachedImdbTrailerStreams(id)
            val streams = cached ?: run {
                // Cold requests wait until roughly 900 ms of focus; cache hits never queue behind HTTP.
                delay(titleDwellAt + 500L - SystemClock.uptimeMillis())
                if (!card.awaitMotion(motionSettled) || !still() || !fullyVisible(card)) return@launch
                runInterruptible(lookupDispatcher) { imdbTrailerStreams(id) }
            }
            val imdb = imdbPreviewUrl(streams)
            if (!still()) return@launch
            delay(titleDwellAt + remainingDwell - SystemClock.uptimeMillis())
            // Image and metadata callbacks also fire when the answer doesn't change the layout.
            fun ready() = (imdb == null && metadataSettled() && addonUrl() == null) ||
                (card.hasBackdrop && (imdb != null || addonUrl() != null))
            if (!ready()) {
                val arrived = CompletableDeferred<Unit>()
                card.onPreviewData = { if (ready()) arrived.complete(Unit) }
                try {
                    if (withTimeoutOrNull(15_000L) { arrived.await() } == null) return@launch
                } finally {
                    card.onPreviewData = null
                }
            }
            val url = imdb ?: addonUrl() ?: return@launch
            if (!card.awaitMotion(motionSettled) || !still() || !card.hasBackdrop || !fullyVisible(card)) return@launch
            val p = player ?: createPlayer().also { player = it }
            main.removeCallbacks(detach)
            if (output?.parent != null) detachOutput()
            activeCard = card
            mayPlay = { still() && motionSettled() && fullyVisible(card) }
            val texture = output ?: TextureView(card.context).also { output = it }
            texture.setTransform(null)
            texture.visibility = View.VISIBLE
            card.attachPreview(texture)
            movie = isMovie
            awaitingStart = true
            p.pause()
            p.addListener(this@HomeTrailerPreview)
            p.setMediaItem(MediaItem.fromUri(url), previewStartMs(C.TIME_UNSET))
            p.prepare()
        }
    }

    private fun createPlayer(): ExoPlayer {
        val selector = DefaultTrackSelector(context).apply {
            setParameters(buildUponParameters().setMaxVideoSize(1280, 720).setExceedVideoConstraintsIfNecessary(false)
                .setExceedRendererCapabilitiesIfNecessary(false))
        }
        val load = DefaultLoadControl.Builder().setBufferDurationsMs(2_000, 15_000, 250, 1_000)
            .setTargetBufferBytes(6 * 1024 * 1024).setPrioritizeTimeOverSizeThresholds(false).build()
        val renderers = object : DefaultRenderersFactory(context) {
            override fun buildAudioRenderers(context: Context, extensionRendererMode: Int, selector: MediaCodecSelector,
                    fallback: Boolean, sink: AudioSink, handler: Handler, listener: AudioRendererEventListener,
                    out: ArrayList<Renderer>) {
                // A Shield receiver can ignore player volume on a compressed HDMI bitstream.
                val pcmSink = object : ForwardingAudioSink(sink) {
                    override fun supportsFormat(format: Format) =
                        format.sampleMimeType == MimeTypes.AUDIO_RAW && super.supportsFormat(format)
                }
                out += object : MediaCodecAudioRenderer(context, codecAdapterFactory, selector, fallback, handler, listener, pcmSink) {
                    override fun supportsFormat(selector: MediaCodecSelector, format: Format): Int =
                        if (previewAudioAllowed(format.sampleMimeType, format.channelCount)) super.supportsFormat(selector, format)
                        else RendererCapabilities.create(C.FORMAT_UNSUPPORTED_TYPE)
                }
            }
        }.setMediaCodecSelector { mime, secure, tunneling ->
            val decoders = MediaCodecSelector.DEFAULT.getDecoderInfos(mime, secure, tunneling)
            if (MimeTypes.isVideo(mime)) decoders.filter { it.hardwareAccelerated } else decoders
        }
        return ExoPlayer.Builder(context, renderers).setTrackSelector(selector).setLoadControl(load)
            .setAudioAttributes(movieAudio, false) // Media3 automatic focus for USAGE_MEDIA always requests permanent gain.
            .setVideoChangeFrameRateStrategy(C.VIDEO_CHANGE_FRAME_RATE_STRATEGY_OFF).build().apply { volume = 0f }
    }

    override fun onTimelineChanged(timeline: Timeline, reason: Int) {
        if (reason == Player.TIMELINE_CHANGE_REASON_SOURCE_UPDATE && player?.duration != C.TIME_UNSET) attachAtStart()
    }

    private fun attachAtStart() {
        val p = player ?: return
        if (!awaitingStart) return
        if (mayPlay?.invoke() != true) return stop()
        // Some sources still have no duration at READY. Keep their six-second start provisional until first frame.
        val duration = p.duration
        awaitingStart = duration == C.TIME_UNSET
        val start = previewStartMs(duration)
        if (p.playWhenReady) {
            if (start != 0L) return
            p.clearVideoTextureView(output) // discard any first-frame callback from the provisional surface
        }
        if (start == 0L) p.seekTo(0L)
        p.setVideoTextureView(output)
        p.play()
    }

    override fun onRenderedFirstFrame() {
        val card = activeCard ?: return
        if (firstFrameShown) return
        if (mayPlay?.invoke() != true) return stop()
        if (awaitingStart && previewStartMs(player?.duration ?: C.TIME_UNSET) == 0L) {
            attachAtStart()
            return
        }
        awaitingStart = false
        firstFrameShown = true
        if (animationsOn()) {
            fade = ValueAnimator.ofFloat(1f, 0f).apply {
                duration = 400
                addUpdateListener { card.previewArtAlpha = 1f - it.animatedFraction }
                start()
            }
        } else card.previewArtAlpha = 0f
        startSound()
    }

    private fun startSound() {
        val p = player ?: return
        if (!firstFrameShown || triedFocus || !p.currentTracks.isTypeSelected(C.TRACK_TYPE_AUDIO)) return
        triedFocus = true
        lateinit var request: AudioFocusRequestCompat
        request = AudioFocusRequestCompat.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
            .setAudioAttributes(movieAudio).setWillPauseWhenDucked(true)
            .setOnAudioFocusChangeListener { change ->
                if (change < 0 && focusRequest === request) silence()
            }.build()
        if (AudioManagerCompat.requestAudioFocus(AudioManagerCompat.getAudioManager(context), request) !=
            AudioManager.AUDIOFOCUS_REQUEST_GRANTED) return
        focusRequest = request
        if (animationsOn()) {
            volumeFade = ValueAnimator.ofFloat(0f, 1f).apply {
                duration = 300
                addUpdateListener { p.volume = it.animatedFraction }
                start()
            }
        } else p.volume = 1f
    }

    private fun silence() {
        volumeFade?.cancel()
        volumeFade = null
        player?.volume = 0f
        focusRequest?.let {
            focusRequest = null
            AudioManagerCompat.abandonAudioFocusRequest(AudioManagerCompat.getAudioManager(context), it)
        }
    }

    override fun onPlaybackStateChanged(playbackState: Int) {
        if (playbackState == Player.STATE_READY) {
            if (player?.currentTracks?.isTypeSelected(C.TRACK_TYPE_VIDEO) == true) attachAtStart() else stop()
        } else if (playbackState == Player.STATE_ENDED) {
            // A clip shorter than the provisional offset can hit its end while preparing.
            if (awaitingStart) attachAtStart()
            if (player?.playbackState != Player.STATE_ENDED) return
            stop()
        }
    }

    override fun onTracksChanged(tracks: Tracks) {
        if (!tracks.isEmpty && !tracks.isTypeSelected(C.TRACK_TYPE_VIDEO)) stop()
        else if (tracks.isTypeSelected(C.TRACK_TYPE_AUDIO)) startSound()
        else if (focusRequest != null) {
            silence()
            triedFocus = false
        }
    }

    override fun onVideoSizeChanged(videoSize: VideoSize) {
        val texture = output ?: return
        val face = texture.parent as? View ?: return
        val (sx, sy) = previewCrop(videoSize.width, videoSize.height, videoSize.pixelWidthHeightRatio,
            face.width, face.height, movie) ?: return
        texture.setTransform(Matrix().apply { setScale(sx, sy, face.width / 2f, face.height / 2f) })
    }

    override fun onPlayerError(error: PlaybackException) = stop()

    // The decoder is already stopping on its playback thread. Detach the hidden texture after navigation settles.
    private val detach = Runnable {
        if (!held()) {
            if (moving()) deferDetach() else detachOutput()
        }
    }

    fun deferDetach() {
        if (activeCard != null || output?.parent == null) return
        main.removeCallbacks(detach)
        main.postDelayed(detach, 220L)
    }

    private fun detachOutput() {
        val texture = output ?: return
        val face = texture.parent as? ViewGroup ?: return
        player?.apply {
            clearVideoTextureView(texture)
            clearMediaItems()
        }
        face.removeView(texture)
    }

    fun stop() {
        watchJob?.cancel()
        watchJob = null
        fade?.cancel()
        fade = null
        activeCard?.let { card ->
            card.previewArtAlpha = 1f
            awaitingStart = false
            firstFrameShown = false
            triedFocus = false
            activeCard = null
            mayPlay = null
            output?.visibility = View.INVISIBLE
            player?.removeListener(this)
            player?.stop()
            silence()
            deferDetach()
        }
    }

    fun release() {
        stop()
        main.removeCallbacks(detach)
        detachOutput()
        player?.release()
        player = null
        output = null
        scope.cancel()
    }

    private companion object {
        val movieAudio = AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MOVIE).build()
        // A cancelled HttpURLConnection can run until its timeout. Never let stale requests fill the IO pool.
        @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
        val lookupDispatcher = Dispatchers.IO.limitedParallelism(1)
    }
}

// Only an unfinished widen or rail animation needs a draw listener. Cold imagery and metadata use onPreviewData.
private suspend fun View.awaitMotion(ready: () -> Boolean): Boolean {
    fun settled() = !isLayoutRequested && ready()
    if (settled()) return true
    return withTimeoutOrNull(15_000L) {
        suspendCancellableCoroutine { wait ->
            val tree = viewTreeObserver
            fun remove(listener: ViewTreeObserver.OnPreDrawListener) {
                val currentTree = if (tree.isAlive) tree else viewTreeObserver
                if (currentTree.isAlive) currentTree.removeOnPreDrawListener(listener)
            }
            val listener = object : ViewTreeObserver.OnPreDrawListener {
                override fun onPreDraw(): Boolean {
                    if (settled()) {
                        remove(this)
                        if (wait.isActive) wait.resume(true)
                    }
                    return true
                }
            }
            tree.addOnPreDrawListener(listener)
            wait.invokeOnCancellation { remove(listener) }
        }
    } ?: false
}
