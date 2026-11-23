package dev.alder

import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Color
import android.os.Bundle
import android.view.Display
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.drawable.GradientDrawable
import android.text.TextUtils
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.Tracks
import androidx.media3.common.util.Util
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.datasource.HttpDataSource
import android.os.Looper
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.Renderer
import androidx.media3.exoplayer.RendererCapabilities
import androidx.media3.exoplayer.text.TextOutput
import androidx.media3.exoplayer.text.TextRenderer
import androidx.media3.exoplayer.ExoPlaybackException
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.analytics.AnalyticsListener
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector
import androidx.media3.exoplayer.upstream.DefaultBandwidthMeter
import androidx.media3.extractor.DefaultExtractorsFactory
import androidx.media3.extractor.mp3.Mp3Extractor
import androidx.media3.ui.CaptionStyleCompat
import androidx.media3.ui.PlayerView
import androidx.media3.ui.SubtitleView
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

internal fun lowerSourceHeight(height: Int): Int? = listOf(1080, 720, 480).firstOrNull { it < height }
internal fun upNextWindow(durationMs: Long) = minOf(25_000L, maxOf(15_000L, durationMs / 60))
internal fun plausibleEpisode(durationMs: Long) = durationMs >= 2 * 60_000L

// Fall-asleep protection: episodes that end in a row with no remote key pressed are counted, and the next one waits
// behind "Still watching?" instead of streaming all night (debrid traffic, and Stremio's watched ticks).
internal const val QUIET_EPISODES = 3
/** [endedQuietly] episodes in a row, this one included, ended with no key pressed: ask before the next one. */
internal fun askStillWatching(endedQuietly: Int) = endedQuietly >= QUIET_EPISODES
internal fun formatTiming(delay: Long, onTime: String = "On time",
                          earlier: String = "%1\$+.2f s  earlier", later: String = "%1\$+.2f s  later") = when {
    delay == 0L -> onTime
    delay < 0L -> earlier.format(delay / 1000f)
    else -> later.format(delay / 1000f)
}

/** Holds track data for display and smart pinning in the Audio & Subtitles overlay. */
internal data class TrackChoice(
    val id: String,
    val name: String,
    val tag: String = "",
    val langCode: String? = null,
    val isSelected: Boolean = false,
    val isOff: Boolean = false
)

/**
 * Pinned vs more tracks for fast TV D-pad access:
 * 1. Off (if present)
 * 2. Active / selected track
 * 3. Device / preferred language track (if found and not already pinned)
 * 4. Recent language tracks (in order of recency, if found and not already pinned)
 * 5. All remaining tracks stay in `more` in their original order.
 */
internal fun partitionPinnedTracks(
    tracks: List<TrackChoice>,
    preferredLang: String?,
    recentLangs: List<String>
): Pair<List<TrackChoice>, List<TrackChoice>> {
    val pinned = mutableListOf<TrackChoice>()
    val seen = mutableSetOf<String>()

    fun pin(track: TrackChoice) {
        if (seen.add(track.id)) {
            pinned.add(track)
        }
    }

    // 1. Off track first
    tracks.firstOrNull { it.isOff }?.let { pin(it) }

    // 2. Currently selected track (if not Off)
    tracks.firstOrNull { it.isSelected && !it.isOff }?.let { pin(it) }

    // 3. Preferred language track
    if (!preferredLang.isNullOrBlank()) {
        tracks.firstOrNull { !it.isOff && Languages.match(it.langCode, preferredLang) > 0 && it.id !in seen }?.let { pin(it) }
    }

    // 4. Recently used language tracks
    for (recent in recentLangs) {
        if (recent.isBlank()) continue
        tracks.firstOrNull { !it.isOff && Languages.match(it.langCode, recent) > 0 && it.id !in seen }?.let { pin(it) }
    }

    // 5. Remaining tracks
    val more = tracks.filter { it.id !in seen }
    return pinned to more
}

/** Updates recent languages with [newLang] at the front, deduplicated and capped at [maxCount]. */
internal fun updateRecentLanguages(recents: List<String>, newLang: String, maxCount: Int = 3): List<String> {
    val code = Languages.code(newLang) ?: newLang.trim().lowercase()
    if (code.isBlank() || code == "und") return recents
    val list = recents.filterNot { Languages.same(it, code) || it.equals(code, ignoreCase = true) }.toMutableList()
    list.add(0, code)
    return list.take(maxCount)
}

/** Chunks a list of tracks into rows for a multi-column grid. */
internal fun <T> chunkGrid(tracks: List<T>, columns: Int = 3): List<List<T>> {
    require(columns > 0)
    return tracks.chunked(columns)
}

// ChadFlix: no audio or subtitle language and no debrid badge; "Ends at" is its own line at the top left.
internal enum class BadgeBrand { NONE, DOLBY, DTS }

internal data class PlayerBadge(val brand: BadgeBrand, val label: String, val accent: Boolean = false)

internal fun playerBadges(
    stream: Stream?,
    videoHeight: Int = 0,
    videoWidth: Int = 0,
    audioMime: String? = null,
    audioLabel: String? = null,
    audioChannels: Int = 0,
): List<PlayerBadge> {
    if (stream == null && videoHeight <= 0 && audioMime == null && audioChannels <= 0) return emptyList()
    val out = mutableListOf<PlayerBadge>()
    // Widescreen films are stored without black bars (4K scope is 3840 x 1608), so the width's 16:9 height counts too.
    val height = maxOf(videoHeight.takeIf { it > 0 } ?: stream?.height ?: 0, videoWidth * 9 / 16)
    // Midway between sizes: a file cropped a few pixels (3836 or 1916 wide) keeps its class.
    when {
        height >= 1800 -> out += PlayerBadge(BadgeBrand.NONE, "4K", accent = true)
        height >= 1260 -> out += PlayerBadge(BadgeBrand.NONE, "1440p")
        height >= 900 -> out += PlayerBadge(BadgeBrand.NONE, "1080p")
        height >= 600 -> out += PlayerBadge(BadgeBrand.NONE, "720p")
        height >= 480 -> out += PlayerBadge(BadgeBrand.NONE, "${height}p")
    }
    if (stream?.dolbyVision == true) out += PlayerBadge(BadgeBrand.DOLBY, "VISION", accent = true)
    if (stream?.hdr == true && !stream.dolbyVision) out += PlayerBadge(BadgeBrand.NONE, "HDR", accent = true)

    val words = "${stream?.filename.orEmpty()} ${stream?.title.orEmpty()} ${stream?.description.orEmpty()} ${audioLabel.orEmpty()}".lowercase()
    // The file name describes the main audio track. Once the playing track is known, the name only refines its own
    // codec family (Matroska reports Atmos as plain E-AC-3, DTS-HD MA as DTS), so an AAC commentary is not "ATMOS".
    val unknown = audioMime == null
    val dolbyTrack = unknown || audioMime == MimeTypes.AUDIO_E_AC3 || audioMime == MimeTypes.AUDIO_E_AC3_JOC ||
        audioMime == MimeTypes.AUDIO_TRUEHD
    val dtsTrack = audioMime?.startsWith("audio/vnd.dts") == true
    val atmos = audioMime == MimeTypes.AUDIO_E_AC3_JOC || (dolbyTrack && "atmos" in words)
    val trueHd = audioMime == MimeTypes.AUDIO_TRUEHD || (unknown && "truehd" in words)
    val dolbyDigital = audioMime == MimeTypes.AUDIO_E_AC3 || audioMime == MimeTypes.AUDIO_AC3 ||
        (unknown && Regex("\\b(ddp?|e-?ac-?3|ac-?3|dolby.?digital)(\\b|\\d)").containsMatchIn(words))
    val dtsX = (unknown || dtsTrack) && Regex("dts[-_.: ]?x\\b").containsMatchIn(words)
    val dtsHd = audioMime == MimeTypes.AUDIO_DTS_HD || ((unknown || dtsTrack) && Regex("dts[-_.: ]?hd").containsMatchIn(words))
    val dts = dtsTrack || (unknown && Regex("\\bdts\\b").containsMatchIn(words))

    when {
        atmos -> out += PlayerBadge(BadgeBrand.DOLBY, "ATMOS", accent = true)
        trueHd -> out += PlayerBadge(BadgeBrand.DOLBY, "TRUEHD")
        dtsX -> out += PlayerBadge(BadgeBrand.DTS, "X", accent = true)
        dtsHd -> out += PlayerBadge(BadgeBrand.DTS, "HD")
        dts -> out += PlayerBadge(BadgeBrand.DTS, "")
        dolbyDigital -> out += PlayerBadge(BadgeBrand.DOLBY, "AUDIO")
    }

    val channels = when {
        audioChannels == 8 -> "7.1"
        audioChannels == 6 -> "5.1"
        audioChannels == 2 -> "2.0"
        audioChannels == 1 -> "1.0"
        audioChannels > 0 -> null
        Regex("(?<!\\d)7[ ._-]?1\\b").containsMatchIn(words) -> "7.1"
        Regex("(?<!\\d)5[ ._-]?1\\b").containsMatchIn(words) -> "5.1"
        else -> null
    }
    if (channels != null) out += PlayerBadge(BadgeBrand.NONE, channels)

    if (stream?.remux == true) out += PlayerBadge(BadgeBrand.NONE, "BLU-RAY")
    return out
}

internal class PlaybackCheckpoint {
    var handedOff: Boolean = false
        private set
    var resumeOnReturn: Boolean = false
        private set
    private var pauseCommitted = false
    private var externalCommitted = false

    fun onHandOffStarted() {
        handedOff = true
        resumeOnReturn = false
    }

    fun onHandOffCancelled() {
        handedOff = false
    }

    fun captureResumeOnPause(playWhenReady: Boolean, finishing: Boolean) {
        resumeOnReturn = playWhenReady && !finishing && !handedOff
    }

    fun consumeResumeOnReturn(finishing: Boolean, destroyed: Boolean, prepared: Boolean): Boolean {
        val shouldResume = resumeOnReturn && !finishing && !destroyed && !handedOff && prepared
        resumeOnReturn = false
        return shouldResume
    }

    fun onResume(finishing: Boolean) {
        if (!handedOff && !externalCommitted && !finishing) {
            pauseCommitted = false
        }
    }

    fun commitOnPause(eligible: Boolean, action: () -> Unit): Boolean {
        if (!eligible || handedOff || pauseCommitted || externalCommitted) return false
        pauseCommitted = true
        action()
        return true
    }

    fun commitExternal(eligible: Boolean, action: () -> Unit): Boolean {
        if (!eligible || externalCommitted) return false
        externalCommitted = true
        pauseCommitted = true
        action()
        return true
    }
}

internal fun commitStopCheckpoint(
    record: () -> JSONObject?,
    nextVideoId: String?,
    push: (JSONObject) -> Unit,
    stop: (JSONObject, String?) -> Unit = Library::stopped,
): Boolean {
    val recorded = record() ?: return false
    val snapshot = JSONObject(recorded.toString())
    stop(snapshot, nextVideoId)
    push(snapshot)
    return true
}

internal fun selectedSubtitleTrackIndex(
    disabled: Boolean,
    addonSelected: Boolean,
    embeddedCount: Int,
    embeddedSelectedIndex: Int,
    addonSubUrls: List<String>,
    chosenSubUrl: String?,
): Int = when {
    disabled -> 0
    addonSelected -> {
        val idx = if (chosenSubUrl == null) -1 else addonSubUrls.indexOfFirst { it == chosenSubUrl }
        if (idx >= 0) 1 + embeddedCount + idx else -1
    }
    embeddedSelectedIndex >= 0 -> 1 + embeddedSelectedIndex
    else -> 0
}

@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
internal class OffsettableTextRenderer(
    private val delegate: TextRenderer
) : Renderer by delegate, RendererCapabilities by delegate {
    @Volatile
    var offsetUs: Long = 0L

    override fun getName(): String = delegate.name
    override fun getTrackType(): Int = delegate.trackType
    override fun getCapabilities(): RendererCapabilities = this

    override fun render(positionUs: Long, elapsedRealtimeUs: Long) {
        delegate.render(maxOf(0L, positionUs - offsetUs), elapsedRealtimeUs)
    }

    override fun resetPosition(positionUs: Long, join: Boolean) {
        delegate.resetPosition(maxOf(0L, positionUs - offsetUs), join)
    }
}

// ExoPlayer with our own controls (buildControls) and audio/subtitle panel (openOptions). Resumes where Stremio says you stopped and
// writes progress back to the account, so Continue Watching matches on every device.
// It gets a short list of sources: one that fails, or hasn't shown a picture after START_WAIT, gives way to the next
// at the same second, and when the list runs out the sources page opens. Network blips are retried first, twice
// after 1.5 s (NuvioTV's rule), so a dropped connection doesn't cost the source.
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class PlayerActivity : Screen() {
    private lateinit var player: ExoPlayer
    private lateinit var view: PlayerView
    private val type by lazy { intent.getStringExtra("type") ?: "movie" }
    private val metaId by lazy { intent.getStringExtra("id")!! }
    private val videoId by lazy { intent.getStringExtra("videoId")!! }
    private val isTrailer by lazy { intent.getBooleanExtra("isTrailer", false) }

    private var item: JSONObject? = null
    private var provisionalProgress = false
    private var lastSourceFailure: Failure? = null
    private var lastPosition = 0L
    private var ticks = 0

    @Volatile private var sources: List<Stream> = emptyList()
    private var resolvingStreams: Cancellable? = null
    private lateinit var backdropArt: ImageView
    private lateinit var backdropShade: FrameLayout
    @Volatile private var at = 0 // the source playing
    private var retries = 0 // network retries on this source
    private var asHls = false // tried as HLS: live and IPTV links often hide the playlist behind a plain URL
    private var started = false // a picture has shown
    private var startMessage: String? = null
    private var watched = false // any source showed a picture this time: only then is progress saved
    private val checkpoint = PlaybackCheckpoint()
    private val handedOff get() = checkpoint.handedOff // this source went to another video app (Settings › Video player)
    private var externalUnavailable = false
    private var startAt = 0L
    private var prepared = false
    private var paused = false
    private var stopped = false // Alder is behind another app: a late load must not start the sound
    private var pendingForegroundAction: (() -> Unit)? = null
    // The current source's link (Stream.open), the source it belongs to, and the one still opening (a torrent's engine).
    private var opened: Opened? = null
    private var openedFor: Stream? = null
    private var opening: Stream? = null
    private val openLock = Any()
    @Volatile private var destroyed = false
    private val inFlightOpened = mutableListOf<Opened>()
    private var startWait = START_WAIT
    // Stremio's resume point when it differs from the TV's copy; used once the picture shows and the file can seek.
    private var lateResume: Long? = null
    private lateinit var status: android.widget.TextView
    private lateinit var loading: FrameLayout
    private lateinit var stallPanel: FrameLayout
    private lateinit var screen: FrameLayout
    private lateinit var pausedShade: View
    // Subtitles from addons (OpenSubtitles): offered in Playback options after the file's own. Picking one reloads the
    // source from the same second with that file attached, then selects it once its track shows up.
    private var addonSubs: List<Subtitle> = emptyList()
    private var chosenSub: Subtitle? = null
    private val readySubs = mutableMapOf<String, SubtitleFiles.Ready?>() // add-on subtitle link -> local UTF-8 file
    private var selectAddonSub = false
    // The languages to start with: what was picked in Audio & subtitles for this title before (the next episode, a
    // resume), else the profile's Settings. Picks in the menu change them, are kept for the title, and hold when the
    // player moves to another source.
    private var wantAudio = ""
    private var wantSub = ""
    private var subsOn = true
    private var viewerAddonSub = false // the viewer chose the addon file, over the file's own in the same language
    private val dialogueProcessor = DialogueAudioProcessor()
    @Volatile private var decodeAudio = false
    @Volatile private var retryWithPcm = false
    @Volatile private var speed = 1f // playback speed: the audio sink decodes to PCM for any but 1x
    @Volatile private var passthroughOut = false // the audio track takes the stream as it is, not decoded PCM
    private var pickedSub: String? = null // the exact subtitle picked for this video before: "addon:<url>" or "file:<track id>"
    // Timing for subtitles (in the file or from add-ons): offset is applied directly to the TextRenderer live.
    private var subDelay = 0L
    private var textRenderer: OffsettableTextRenderer? = null
    private var subPref = "on"
    private var rememberedSubtitles: String? = null

    // A series' episodes (for the next one and Stremio's watched ticks), from the meta addon's saved answer when there is one.
    private var meta: Meta? = null
    private val episodic get() = hasEpisodes(type, meta?.videos)
    private var tickedWatched = false
    // "Up next": offered during credits; the current episode keeps playing until the viewer chooses the next one.
    private lateinit var upNextPanel: FrameLayout
    private var upNextDismissed = false
    // "Still watching?": episodes before this one that ended with no key pressed (carried in the intent from one
    // episode to the next, and to another source).
    private lateinit var stillPanel: FrameLayout
    private var quiet = 0
    // The pill's other use: a source that keeps stalling (three times in a minute) is offered a lower quality once.
    private var offeringLower = false
    private val stalls = ArrayDeque<Long>()
    private var seekedAt = 0L
    private val loadingTask = object : Runnable {
        override fun run() {
            if (loading.visibility != View.VISIBLE || player.playbackState != Player.STATE_BUFFERING) return
            showLoading(if (started) getString(R.string.player_loading) else startMessage
                ?: getString(R.string.player_starting, sources.getOrNull(at)?.label.orEmpty()))
        }
    }
    private val startTimeout = Runnable {
        if (started) return@Runnable
        android.util.Log.w("Alder", "source ${at + 1}/${sources.size} timed out, state ${player.playbackState}")
        next(Failure.TIMEOUT)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val key = authKey ?: return finish()
        val sourcesJson = intent.getStringExtra("sources")
        if (!sourcesJson.isNullOrBlank()) {
            sources = JSONArray(sourcesJson).objects().map(Stream::read)
        }
        if (sources.isEmpty() && isTrailer) return finish()
        decodeAudio = Settings.decodeAudio(this)
        // ChadFlix: no speed control; always 1x (a speed saved for the title earlier is ignored).
        dialogueProcessor.enabled = Settings.clearDialogue(this)
        val renderers = object : DefaultRenderersFactory(this) {
            override fun buildAudioSink(context: Context, enableFloatOutput: Boolean, enableAudioTrackPlaybackParams: Boolean): AudioSink =
                playbackAudioSink(context, dialogueProcessor) { decodeAudio || retryWithPcm || speed != 1f }

            override fun buildTextRenderers(
                context: Context,
                output: TextOutput,
                outputLooper: Looper,
                extensionRendererMode: Int,
                out: java.util.ArrayList<Renderer>
            ) {
                val list = java.util.ArrayList<Renderer>()
                super.buildTextRenderers(context, output, outputLooper, extensionRendererMode, list)
                val wrapped = list.map { r ->
                    if (r is TextRenderer) {
                        OffsettableTextRenderer(r).also { textRenderer = it }
                    } else r
                }
                out.addAll(wrapped)
            }
        }.setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_ON) // ffmpeg after the TV's own decoders
        // Start after 2 s of video and 4 s after a stall, not 1 s and 2 s: big files stall again at once on 2 s.
        val load = DefaultLoadControl.Builder().setBufferDurationsMs(DefaultLoadControl.DEFAULT_MIN_BUFFER_MS,
            DefaultLoadControl.DEFAULT_MAX_BUFFER_MS, 2_000, 4_000)
            .apply { bufferBytesFor(Runtime.getRuntime().maxMemory())?.let(::setTargetBufferBytes) }.build()
        val selector = DefaultTrackSelector(this).apply {
            setParameters(buildUponParameters().setAllowInvalidateSelectionsOnRendererCapabilitiesChange(true))
        }
        player = ExoPlayer.Builder(this, renderers).setLoadControl(load).setTrackSelector(selector).build()
        player.setPlaybackSpeed(speed)
        player.addAnalyticsListener(object : AnalyticsListener {
            override fun onAudioTrackInitialized(eventTime: AnalyticsListener.EventTime, audioTrackConfig: AudioSink.AudioTrackConfig) {
                passthroughOut = !Util.isEncodingLinearPcm(audioTrackConfig.encoding)
                android.util.Log.i("Alder", "Audio output: encoding=${audioTrackConfig.encoding}, sample rate=${audioTrackConfig.sampleRate}, channel mask=${audioTrackConfig.channelConfig}")
            }
        })
        val remembered = Settings.titleChoice(this, metaId)
        wantAudio = remembered?.audio ?: Settings.audioLanguage(this)
        subPref = Settings.subPref(this)
        rememberedSubtitles = remembered?.subtitles
        subsOn = remembered?.subtitles?.isNotEmpty() ?: (subPref != "off")
        wantSub = remembered?.subtitles?.ifEmpty { null } ?: Settings.subtitleLanguage(this)
        pickedSub = remembered?.takeIf { it.subtitleVideo == videoId }?.subtitle
        if (pickedSub != null) { subsOn = true; viewerAddonSub = pickedSub!!.startsWith("addon:") }
        // Release files carry many dubs; start in the chosen language (the TV's unless Settings says) instead of the first.
        player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
            .setPreferredAudioLanguage(wantAudio).setPreferredTextLanguage(wantSub).build()
        view = PlayerView(this).apply {
            player = this@PlayerActivity.player
            keepScreenOn = true
            useController = false
            setShowBuffering(PlayerView.SHOW_BUFFERING_NEVER)
            // Settings' size, color and background, with a shadow so text holds up on bright scenes either way.
            subtitleView?.setFractionalTextSize(SubtitleView.DEFAULT_TEXT_SIZE_FRACTION * Settings.subtitleSize(context) / 100f)
            subtitleView?.setStyle(CaptionStyleCompat(Settings.subtitleColor(context),
                if (Settings.subtitleBackground(context)) 0xB3000000.toInt() else Color.TRANSPARENT, Color.TRANSPARENT,
                CaptionStyleCompat.EDGE_TYPE_DROP_SHADOW, Color.BLACK, null))
            setBackgroundColor(Color.BLACK)
        }
        applyPictureFit()
        status = text("", BODY).onArt().apply {
            gravity = Gravity.CENTER
            maxWidth = dp(440)
            maxLines = 2
            ellipsize = TextUtils.TruncateAt.END
        }
        pausedShade = View(this).apply { setBackgroundColor(0x4D000000); visibility = View.GONE }
        upNextPanel = FrameLayout(this).apply { visibility = View.GONE }
        stillPanel = FrameLayout(this).apply { visibility = View.GONE }
        quiet = intent.getIntExtra("quiet", 0)
        controls = buildControls()
        loading = buildLoading()
        stallPanel = FrameLayout(this).apply { visibility = View.GONE }
        skip = text("", BODY, bold = true).onArt().apply {
            setPadding(dp(22), dp(12), dp(22), dp(12))
            background = glass(10)
            visibility = View.GONE
        }
        if (!isTrailer) fetchSegments()
        val (bArt, bShade) = buildBackdrop()
        backdropArt = bArt
        backdropShade = bShade
        screen = FrameLayout(this).apply {
            addView(view, FrameLayout.LayoutParams(-1, -1))
            addView(backdropArt, FrameLayout.LayoutParams(-1, -1))
            addView(backdropShade, FrameLayout.LayoutParams(-1, -1))
            addView(pausedShade, FrameLayout.LayoutParams(-1, -1))
            addView(controls, FrameLayout.LayoutParams(-1, -1))
            addView(loading, FrameLayout.LayoutParams(-1, -1))
            addView(stallPanel, FrameLayout.LayoutParams(-1, -1))
            addView(upNextPanel, FrameLayout.LayoutParams(-1, -1))
            addView(stillPanel, FrameLayout.LayoutParams(-1, -1))
            addView(skip, FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM or Gravity.END).apply { setMargins(0, 0, dp(PAD), dp(PAD + 40)) })
        }
        setContentView(screen)
        background({
            Stremio.addons(key).let { it.savedMeta(type, metaId) ?: it.meta(type, metaId) }
        }) { r ->
            meta = r.getOrNull()
            val m = meta ?: return@background
            if (!isTrailer) fetchSegments() // anime episode ids get their IntroDB season and number from the meta
            val episode = if (isTrailer) null else m.videos.firstOrNull { it.id == videoId }
            pauseTitle.text = episode?.title?.takeIf { it.isNotBlank() } ?: m.name
            subtitle.text = if (isTrailer) getString(R.string.action_trailer) else episode?.let { "S${it.season} E${it.episode}" } ?: m.year.orEmpty()
            if (infoOpen) showInfo()
            nextCell.visibility = if (isTrailer || nextVideo() == null) View.GONE else View.VISIBLE
        }

        player.addListener(object : Player.Listener {
            override fun onPlayerError(e: PlaybackException) {
                val format = (e as? ExoPlaybackException)?.rendererFormat
                val sinkFormat = when (val cause = e.cause) {
                    is AudioSink.ConfigurationException -> cause.format
                    is AudioSink.InitializationException -> cause.format
                    is AudioSink.WriteException -> cause.format
                    else -> null
                }
                val outputFailed = audioOutputFailure(e.errorCode)
                val selected = audioGroups.firstOrNull { it.isSelected }
                val failed = audioGroups.firstOrNull { it.getTrackFormat(0) == format || it.getTrackFormat(0) == sinkFormat } ?: selected
                if (shouldRetryWithPcm(decodeAudio || retryWithPcm, outputFailed, sinkFormat?.sampleMimeType)) {
                    retryWithPcm = true
                    retryAudio(failed, "Retrying the audio track as PCM after ${e.errorCodeName}")
                    return
                }
                if ((outputFailed || format?.sampleMimeType?.let(MimeTypes::isAudio) == true) &&
                    failed != null && failedAudio.add(failed.getTrackFormat(0))) {
                    bestAudio(audioGroups)?.let {
                        retryAudio(it, "Audio track changed after ${e.errorCodeName}")
                        return
                    }
                }
                // A link that isn't a file ExoPlayer knows may be an HLS playlist without .m3u8 in its name.
                if (!asHls && e.errorCode == PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED) {
                    asHls = true
                    return load(position())
                }
                val failure = playbackFailure(e.errorCode, e.cause)
                if (retryable(e) && retries < RETRIES) {
                    lastSourceFailure = failure
                    FailureLog.record(FailureLog.Operation.PLAYBACK, failure)
                    retries++
                    // ExoPlayer's own retries already took about 6 s: each of ours gets a fresh START_WAIT, or the
                    // start timeout would move on to the next source before the second retry ran.
                    main.removeCallbacks(startTimeout)
                    if (!paused && !stopped) main.postDelayed(startTimeout, RETRY_WAIT + startWait)
                    main.postDelayed({ if (!isFinishing && !isDestroyed && !paused && !stopped) player.prepare() }, RETRY_WAIT)
                    return
                }
                android.util.Log.w("Alder", "source ${at + 1}/${sources.size} failed: ${e.errorCodeName}", e)
                next(failure)
            }

            override fun onRenderedFirstFrame() {
                lastSourceFailure?.let { FailureLog.record(FailureLog.Operation.PLAYBACK, it, recovered = true) }
                lastSourceFailure = null
                if (started) return
                started = true
                applyLateResume()
                watched = true
                retries = 0
                hideLoading()
                dismissBackdrop()
                main.removeCallbacks(startTimeout)
                player.videoFormat?.frameRate?.let(::matchFrameRate)
                refreshBadges()
                // Next time: the same release for this video, and the same group (release and quality) for the next episode.
                if (!isTrailer && sources.isNotEmpty()) {
                    val s = sources[at]
                    getSharedPreferences("sources", MODE_PRIVATE).edit()
                        .putString("file:$videoId", s.filename).putString("binge:$metaId", s.bingeGroup).apply()
                }
                main.postDelayed({ if (player.playWhenReady) hideControls() }, 2000)
            }

            override fun onPlaybackStateChanged(state: Int) {
                // At the real end the next episode starts, unless the viewer chose to watch the credits,
                // or this is the third episode in a row with no key pressed: then "Still watching?" waits.
                if (state == Player.STATE_ENDED) {
                    if (isTrailer) {
                        finish()
                        return
                    }
                    val upcoming = nextVideo()
                    // shortcut: sub-two-minute series streams are treated as addon placeholders; revisit for short-form shows.
                    if (episodic && !plausibleEpisode(player.duration)) {
                        started = false // retry from the original resume point, not the placeholder's short ending
                        next(Failure.MISSING)
                    } else if (upcoming == null || upNextDismissed) finish()
                    else if (askStillWatching(quiet + 1)) showStillWatching(upcoming)
                    else playNext(quiet + 1)
                }
                if (state == Player.STATE_BUFFERING) showLoading(if (started) getString(R.string.player_loading) else startMessage
                    ?: getString(R.string.player_starting, sources.getOrNull(at)?.label.orEmpty()))
                if (state == Player.STATE_READY) {
                    if (started) {
                        retries = 0 // the source recovered: the next network drop gets its own two retries
                        hideLoading()
                        updateMarkers()
                    }
                }
                // A seek buffers too; only stalls in plain playback count.
                if (state == Player.STATE_BUFFERING && started && player.playWhenReady && System.currentTimeMillis() - seekedAt > 2_000) stalled()
            }

            override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
                playPause.foreground = centred(if (playWhenReady) R.drawable.ic_pause else R.drawable.ic_play, 25, BASE)
                buttonLabels[playPause]?.text = getString(if (playWhenReady) R.string.player_pause else R.string.action_play)
                pausedShade.visibility = if (playWhenReady) View.GONE else View.VISIBLE
                if (playWhenReady) { if (shown) showControls(null) }
                else if (!panelUp() && upNextPanel.visibility != View.VISIBLE &&
                    stallPanel.visibility != View.VISIBLE && stillPanel.visibility != View.VISIBLE) showControls(playPause)
            }

            override fun onPositionDiscontinuity(old: Player.PositionInfo, new: Player.PositionInfo, reason: Int) {
                if (reason == Player.DISCONTINUITY_REASON_SEEK) {
                    seekedAt = System.currentTimeMillis()
                    lastPosition = new.positionMs // a jump is not watched time
                    skipped.clear() // back into an intro: offer it again
                    skip.tag = null // with a new 10 s window, not the old one
                }
            }

            override fun onTracksChanged(tracks: Tracks) {
                trackStatus.text = trackStatus(tracks)
                refreshBadges(tracks)
                if (selectAddonSub) tracks.groups.firstOrNull(::isAddonSub)?.let { g ->
                    selectAddonSub = false
                    player.trackSelectionParameters = player.trackSelectionParameters.buildUpon().setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false)
                        .setOverrideForType(TrackSelectionOverride(g.mediaTrackGroup, 0)).build()
                }
                val audio = tracks.groups.filter { it.type == C.TRACK_TYPE_AUDIO }
                if (audio.isNotEmpty()) audioGroups = audio
                if (menuOpen && !tracks.isEmpty) refreshMenu() // ChadFlix: a late track or a pick shows in the open menu
                val lostAudioSupport = audio.any { it.isSelected && !it.isTrackSupported(0) }
                val best = bestAudio(audio)
                // Releases flag foreign subtitles as forced or default (Lanterns came up with Russian "Forced" under
                // English audio): start with subtitles only in the profile's subtitle language, whatever the sound, else
                // none. The file's own come first, then the addon file attached at the start, then the file's own from
                // another country (Portugal's for Brazil's). Once per video, like audio.
                if (!subsPicked && best != null) {
                    subsPicked = true
                    val texts = tracks.groups.filter { it.type == C.TRACK_TYPE_TEXT && it.isTrackSupported(0) }
                    fun inFile(score: Int) = texts.filterNot(::isAddonSub).filter { Languages.match(it.getTrackFormat(0).language, wantSub) == score }
                        .maxByOrNull { it.getTrackFormat(0).selectionFlags and C.SELECTION_FLAG_FORCED == 0 } // full, not forced-only
                    val addon = texts.firstOrNull { isAddonSub(it) && Languages.same(chosenSub?.lang, wantSub) }
                    // The same track of the file as last time, while its language still matches (another source's file
                    // numbers its tracks its own way).
                    val exact = pickedSub?.takeIf { it.startsWith("file:") }?.removePrefix("file:")?.let { id ->
                        texts.firstOrNull { g -> !isAddonSub(g) && g.getTrackFormat(0).let { it.id == id &&
                            (it.language == null || Languages.same(it.language, wantSub)) } }
                    }
                    val audioLang = best.getTrackFormat(0).language
                    val foreignAudio = !Languages.same(audioLang, wantSub)
                    val forcedTrack = texts.filterNot(::isAddonSub).firstOrNull {
                        Languages.match(it.getTrackFormat(0).language, wantSub) >= 1 &&
                            (it.getTrackFormat(0).selectionFlags and C.SELECTION_FLAG_FORCED != 0)
                    }
                    val pick = when {
                        !subsOn -> null
                        exact != null -> exact
                        subPref == "foreign" && !foreignAudio && rememberedSubtitles == null -> forcedTrack
                        else -> (if (viewerAddonSub) addon else null) ?: inFile(2) ?: addon ?: inFile(1)
                    }
                    player.trackSelectionParameters = player.trackSelectionParameters.buildUpon().apply {
                        if (pick == null) setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true)
                        else setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false).setOverrideForType(TrackSelectionOverride(pick.mediaTrackGroup, 0))
                    }.build()
                }
                if (lostAudioSupport && best == null) {
                    player.trackSelectionParameters = player.trackSelectionParameters.buildUpon().clearOverridesOfType(C.TRACK_TYPE_AUDIO).build()
                    return
                }
                if ((audioPicked && !lostAudioSupport) || best == null) return
                audioPicked = true // once per video; picks made later in the settings menu stand
                player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
                    .setOverrideForType(TrackSelectionOverride(best.mediaTrackGroup, (0 until best.length).toList())).build()
            }
        })

        // "Start Over" plays from the beginning; a switch to a lower quality carries its own second.
        val known = if (isTrailer || intent.getBooleanExtra("startOver", false)) 0L else intent.getLongExtra("resumeAt", -1L).takeIf { it >= 0 }
        // The addon's subtitles are waited for (SUB_WAIT at most) so a file in the profile's language is attached from
        // the start: attaching one later means loading the source again. The latest resume point asked for wins.
        var subsAnswered = isTrailer || !subsOn
        var waiting: Long? = null
        var pendingResume: Long? = null
        var beginStarted = false
        fun begin(resume: Long) {
            if (prepared || beginStarted) return
            if (sources.isEmpty()) {
                pendingResume = resume
                return
            }
            if (!subsAnswered) { waiting = resume; return }
            if (isFinishing || isDestroyed) return
            if (paused || stopped) {
                pendingForegroundAction = { begin(resume) }
                return
            }
            beginStarted = true
            lastPosition = resume
            load(resume)
        }
        if (isTrailer) {
            begin(0L)
            return
        }
        val waitMs = if (pickedSub?.startsWith("addon:") == true) 3_500L else SUB_WAIT
        val stopWaiting = Runnable { if (!isDestroyed) { subsAnswered = true; waiting?.let(::begin) } } // left during the wait: no start
        main.postDelayed(stopWaiting, waitMs)
        // Asked with the stream's file name and size when they're known, so the addon puts files for that release
        // first. A stream found here (Continue Watching) asks again once it's known; an older answer never replaces a newer one.
        var subsAsked = 0
        var subsShown = 0
        fun askSubs(stream: Stream?) = (++subsAsked).let { round -> background({
            // Every language: the profile's subtitle language and English first, three files from each add-on in its order,
            // then one file for each other language by name, so the list stays short enough for a remote.
            val all = Stremio.addons(key).filter { it.serves("subtitles", type, videoId) }
                .flatMap { a -> runCatching { a.subtitles(type, videoId, stream) }
                    .onFailure { android.util.Log.w("Alder", "subtitles from ${a.name} failed", it) }.getOrDefault(emptyList()) }
            all.groupBy { Languages.code(it.lang) ?: it.lang }.entries
                .sortedWith(compareBy({ it.key != wantSub }, { it.key != "en" }, { Languages.name(it.key) }))
                // The limit is per add-on: with one limit for all, OpenSubtitles' files filled it and SubDL's never showed.
                .flatMap { (code, files) -> files.groupBy { it.addon }.values.flatMap { it.take(if (code == wantSub || code == "en") 3 else 1) } }
        }) { r ->
            if (round < subsShown || isFinishing || isDestroyed) return@background
            subsShown = round
            addonSubs = r.getOrDefault(emptyList())
            android.util.Log.i("Alder", "addon subtitles: ${addonSubs.size}", r.exceptionOrNull())
            if (menuOpen) refreshMenu() // ChadFlix: late add-on subtitles join the open menu without moving focus
            val exact = addonSubs.firstOrNull { "addon:" + it.url == pickedSub }
            if (subsAnswered) { // too late for the start: offered in the list, and the file picked last time attached now
                if (exact != null && chosenSub == null) {
                    chosenSub = exact
                    if (prepared) {
                        selectAddonSub = true
                        val pos = position()
                        val play = if (paused || stopped) checkpoint.resumeOnReturn else player.playWhenReady
                        if (paused || stopped) pendingForegroundAction = { load(pos, play = play) }
                        else load(pos, play = play)
                    } else if (beginStarted) {
                        selectAddonSub = true
                    }
                }
                return@background
            }
            main.removeCallbacks(stopWaiting)
            if (chosenSub == null) chosenSub = exact ?: addonSubs.firstOrNull { Languages.same(it.lang, wantSub) } // attached, picked above
            stopWaiting.run()
        } }
        askSubs(sources.firstOrNull())
        if (sources.isEmpty()) {
            pauseFacts.text = getString(R.string.finding_sources)
            pauseFacts.visibility = View.VISIBLE
            bar.isIndeterminate = true
            showControls(null)
            resolvingStreams = StreamsResolver.resolve(
                context = this,
                type = type,
                videoId = videoId,
                metaId = metaId,
                authKey = key,
                onReady = { best ->
                    resolvingStreams = null
                    if (isFinishing || isDestroyed) return@resolve
                    sources = best
                    best.firstOrNull()?.takeIf { subtitleExtras(it.filename, it.size).isNotEmpty() }?.let(::askSubs)
                    refreshBadges()
                    pauseFacts.text = getString(R.string.player_starting_stream)
                    val resumeToStart = pendingResume ?: known ?: 0L
                    begin(resumeToStart)
                },
                onFallback = {
                    resolvingStreams = null
                    if (isFinishing || isDestroyed) return@resolve
                    if (paused || stopped) {
                        pendingForegroundAction = {
                            startActivity(Intent(this, StreamsActivity::class.java).putExtras(intent))
                            finish()
                        }
                        return@resolve
                    }
                    startActivity(Intent(this, StreamsActivity::class.java).putExtras(intent))
                    finish()
                }
            )
        }
        // The stream starts at once from what's known here (the intent, or the library saved on the TV) instead of
        // waiting a round trip to Stremio for the resume point; Stremio's answer corrects it if it moved elsewhere.
        if (known != null) begin(known)
        background({ Stremio.savedLibrary(key)?.firstOrNull { it.optString("_id") == metaId } }) { r ->
            val cached = r.getOrNull()
            if (cached != null && (item == null || provisionalProgress)) {
                item = cached
                provisionalProgress = false
                if (known == null) begin(Library.resumeAt(cached, videoId))
            }
        }
        background({ Stremio.library(key, listOf(metaId)).firstOrNull() }) { result ->
            val fresh = result.getOrNull()
            if (fresh != null && newerProgress(fresh, item)) item = fresh
            if (item == null) {
                provisionalProgress = result.isFailure
                item = Library.newItem(metaId, type, intent.getStringExtra("name") ?: "", intent.getStringExtra("poster"))
            }
            val resume = known ?: item?.let { Library.resumeAt(it, videoId) } ?: 0L
            if (!prepared && !beginStarted) begin(resume)
            else if (known == null && fresh != null) { lateResume = resume; applyLateResume() }
        }
    }

    /** Moves to [lateResume] unless the viewer has already moved on. Waits for the first picture: before it, a subtitle
     *  may still be getting ready (no file loaded) and the file doesn't yet say if it can seek (if not, a seek lands on 0:00). */
    private fun applyLateResume() {
        val resume = lateResume ?: return
        if (!started) return
        lateResume = null
        if (abs(resume - startAt) > RESUME_SLACK && player.currentPosition < startAt + RESUME_SLACK && player.isCurrentMediaItemSeekable) {
            startAt = resume
            player.seekTo(resume)
            lastPosition = resume
        }
    }

    private fun buildBackdrop(): Pair<ImageView, FrameLayout> {
        val art = ImageView(this).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
        }
        val bg = intent.getStringExtra("background") ?: intent.getStringExtra("poster")
        if (!bg.isNullOrBlank()) {
            Images.load(bg, art, resources.displayMetrics.widthPixels)
        }
        val shade = FrameLayout(this).apply {
            addView(View(context).apply {
                background = GradientDrawable(
                    GradientDrawable.Orientation.LEFT_RIGHT,
                    intArrayOf(0xEB0E0C0A.toInt(), 0x990E0C0A.toInt(), 0x330E0C0A)
                )
            }, FrameLayout.LayoutParams(-1, -1))
            addView(View(context).apply {
                background = GradientDrawable(
                    GradientDrawable.Orientation.BOTTOM_TOP,
                    intArrayOf(0xF70E0C0A.toInt(), 0xB30E0C0A.toInt(), 0x000E0C0A)
                )
            }, FrameLayout.LayoutParams(-1, -1))
            addView(View(context).apply {
                background = GradientDrawable(
                    GradientDrawable.Orientation.TOP_BOTTOM,
                    intArrayOf(0x8C0E0C0A.toInt(), 0x000E0C0A)
                )
            }, FrameLayout.LayoutParams(-1, dp(120), Gravity.TOP))
        }
        return art to shade
    }

    private fun dismissBackdrop() {
        if (::backdropArt.isInitialized && backdropArt.visibility == View.VISIBLE) {
            backdropArt.exit(PAGE_MS).alpha(0f).withEndAction { backdropArt.visibility = View.GONE }
            backdropShade.exit(PAGE_MS).alpha(0f).withEndAction { backdropShade.visibility = View.GONE }
        }
    }

    private fun buildLoading() = FrameLayout(this).apply {
        visibility = View.GONE
        addView(View(context).apply { setBackgroundColor(0x800E0C0A.toInt()) }, FrameLayout.LayoutParams(-1, -1))
        val center = column(Gravity.CENTER_HORIZONTAL).apply {
            addView(ring(), LinearLayout.LayoutParams(dp(40), dp(40)))
            addView(status, LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(12) })
        }
        addView(center, FrameLayout.LayoutParams(-2, -2, Gravity.CENTER))
    }

    private fun showLoading(message: String) {
        if (started) {
            status.text = if (message == getString(R.string.player_loading) && player.bufferedPercentage in 1..99)
                getString(R.string.player_loading_percent, player.bufferedPercentage) else message
            loading.visibility = View.VISIBLE
            main.removeCallbacks(loadingTask)
            if (player.playbackState == Player.STATE_BUFFERING) main.postDelayed(loadingTask, 500)
        } else {
            pauseFacts.text = message
            pauseFacts.visibility = View.VISIBLE
            bar.isIndeterminate = true
            showControls(null)
        }
    }

    private fun hideLoading() {
        main.removeCallbacks(loadingTask)
        loading.visibility = View.GONE
        status.text = ""
        bar.isIndeterminate = false
    }

    /** A subtitle pick in the menu: the language ("" for Off) for this title's next episode and resume, and for the
     *  next source if this one fails, and [pick] itself for this video. A track with no language turns subtitles on
     *  without naming one. */
    private fun remember(language: String?, addonFile: Boolean, pick: String) {
        subsOn = language != ""
        viewerAddonSub = addonFile
        pickedSub = pick.ifEmpty { null }
        val code = if (language == "") "" else Languages.code(language)
        rememberedSubtitles = code
        if (!code.isNullOrEmpty()) wantSub = code
        Settings.pickedInPlayer(this, metaId, subtitles = code, video = videoId, subtitle = pick)
    }

    /** Plays the current source from [position], with its own request headers (debrid proxies need them). */
    private fun load(position: Long, message: String? = null, play: Boolean = true) {
        if (sources.isEmpty() || isFinishing || isDestroyed) return
        if (paused || stopped) {
            pendingForegroundAction = { load(position, message, play) }
            return
        }
        startAt = position
        started = false
        val s = sources[at]
        // The source's link first (Stream.open). A link is ready at once; a torrent's engine starts on another thread,
        // then load runs again. A new source lets the last one go.
        if (openedFor !== s) {
            prepared = false
            if (player.playbackState != Player.STATE_IDLE) player.stop()
            retryWithPcm = false
            audioPicked = false
            audioGroups = emptyList()
            failedAudio.clear()
            player.trackSelectionParameters = player.trackSelectionParameters.buildUpon().clearOverridesOfType(C.TRACK_TYPE_AUDIO).build()
            closeInFlightOpened()
            if (opening !== s) opening = null
            opened?.close()
            opened = null
            openedFor = null
            if (!s.opensAtOnce) return openSource(s, position, message, play)
            opened = s.open(this)
            openedFor = s
        }
        val src = opened!!
        startWait = src.startWait ?: START_WAIT
        val app = Settings.externalPlayer(this)
        if (!isTrailer && !handedOff && !externalUnavailable && app != null) {
            if (handOff(src, position, app)) return
            // No other video app on this TV: Alder plays it, and says so.
            return load(position, getString(R.string.player_no_other_app), play)
        }
        // An add-on subtitle is fetched and made ready first (unpacked, UTF-8): see SubtitleFiles. Once per file.
        chosenSub?.let { sub ->
            if (sub.url !in readySubs) {
                prepared = false
                background({ SubtitleFiles.prepare(java.io.File(cacheDir, "subtitles"), sub) }) { r ->
                    r.exceptionOrNull()?.let { android.util.Log.w("Alder", "subtitle from ${sub.addon} not prepared", it) }
                    readySubs[sub.url] = r.getOrNull()
                    if (!isFinishing && !isDestroyed && chosenSub?.url == sub.url) {
                        if (paused || stopped) pendingForegroundAction = { load(position, message, play) }
                        else load(position, message, play)
                    }
                }
                return
            }
        }
        toldUnseekable = false
        startMessage = message ?: getString(R.string.player_starting, s.label)
        bar.isIndeterminate = true
        showLoading(startMessage!!)
        subDelay = 0L
        textRenderer?.offsetUs = 0L
        mkvChapters = emptyList()
        setChapterSnapMode(false)
        updateMarkers()
        refreshBadges()
        showControls(null)
        background({
            MkvChapters.fetch(src.url, src.headers)
        }) { r ->
            val chaps = r.getOrNull()
            if (!chaps.isNullOrEmpty() && sources.getOrNull(at) === s) {
                mkvChapters = chaps
                reconcileSegments()
            }
        }
        val http = DefaultHttpDataSource.Factory().setAllowCrossProtocolRedirects(true).setDefaultRequestProperties(src.headers)
            .setReadTimeoutMs(src.readTimeoutMs)
        val item = MediaItem.Builder().setUri(src.url).apply { if (asHls) setMimeType(MimeTypes.APPLICATION_M3U8) }
            .setSubtitleConfigurations(listOfNotNull(chosenSub?.let {
                val ready = readySubs[it.url] // null: not prepared, so the add-on's own link as before
                MediaItem.SubtitleConfiguration.Builder(ready?.file?.let(android.net.Uri::fromFile) ?: android.net.Uri.parse(it.url))
                    .setMimeType(ready?.mimeType ?: it.mimeType)
                    .setLanguage(it.lang).setLabel(it.addon).setId(ADDON_SUB).build()
            })).build()
        // Files with no index (.ts, raw audio, some .avi) seek by their bitrate instead of not at all: ExoPlayer turns
        // every seek in a file it can't seek into a jump to 0:00, so scrubbing restarted the movie.
        val extractors = DefaultExtractorsFactory().setConstantBitrateSeekingEnabled(true).setConstantBitrateSeekingAlwaysEnabled(true)
            .setMp3ExtractorFlags(Mp3Extractor.FLAG_ENABLE_INDEX_SEEKING)
        player.setMediaSource(DefaultMediaSourceFactory(DefaultDataSource.Factory(this, http), extractors).createMediaSource(item), position) // files too: shifted subtitles
        lastPosition = position
        prepared = true
        player.prepare()
        player.playWhenReady = play && !stopped && !paused
        main.removeCallbacks(startTimeout)
        if (!stopped && !paused) main.postDelayed(startTimeout, startWait) // else onResume starts it
        if (!isTrailer && !stopped && !paused) {
            main.removeCallbacks(tickTask)
            main.postDelayed(tickTask, TICK)
            main.removeCallbacks(skipTask)
            main.post(skipTask)
        }
    }

    /** Opens [s] off the main thread (a torrent finds peers and its file), then plays it; a failure moves on. */
    private fun openSource(s: Stream, position: Long, message: String?, play: Boolean) {
        main.removeCallbacks(startTimeout)
        showLoading(message ?: getString(R.string.player_starting, s.label))
        if (opening === s) return
        opening = s
        val episode = if (isTrailer || type == "movie" || isLiveType(type)) null else {
            meta?.videos?.firstOrNull { it.id == videoId && it.season >= 0 && it.episode > 0 }
                ?.let { it.season to it.episode }
                ?: episodeNumbers(videoId)
                ?: intent.getStringExtra("episode")?.substringBefore("  ·  ")?.let(::parseEpisodeLabel)
        }
        val stale = { destroyed || isFinishing || isDestroyed || sources.getOrNull(at) !== s }
        background({
            val o = s.open(applicationContext, episode, stale)
            synchronized(openLock) {
                if (closeStaleOpen(o, stale())) null
                else o.also { inFlightOpened += it }
            }
        }) { r ->
            if (opening === s) opening = null
            val o = r.getOrNull()
            if (o != null) synchronized(openLock) { inFlightOpened.remove(o) }
            if (closeStaleOpen(o, stale())) return@background
            if (o == null) {
                android.util.Log.w("Alder", "source ${at + 1}/${sources.size} didn't open", r.exceptionOrNull())
                startAt = position
                started = false
                val failure = r.exceptionOrNull()?.let(::failureOf) ?: Failure.OTHER
                if (paused || stopped) {
                    pendingForegroundAction = { next(failure) }
                    return@background
                }
                return@background next(failure)
            }
            opened = o
            openedFor = s
            if (paused || stopped) {
                pendingForegroundAction = { load(position, message, play) }
                return@background
            }
            load(position, message, play)
        }
    }

    /**
     * Settings › Video player › a video app: Alder still picks the source, the resume point and the subtitle, then the
     * other app plays it. False when no app on the TV opens video. Its answer comes back in [onActivityResult].
     */
    private fun handOff(s: Opened, position: Long, app: String): Boolean {
        checkpoint.onHandOffStarted()
        startAt = position
        // The chosen app, or Android's Open with list if it was uninstalled since.
        val pick = app.takeIf { a -> ExternalPlayer.installed(this).any { it.first == a } }
        val out = ExternalPlayer.intent(s.url, intent.getStringExtra("name").orEmpty(), s.headers, position, chosenSub?.url, pick)
        return try {
            startActivityForResult(out, ExternalPlayer.REQUEST)
            showLoading(getString(R.string.player_other_app))
            hideControls(force = true) // Alder's play and seek hints mean nothing while the other app plays
            true
        } catch (e: android.content.ActivityNotFoundException) {
            android.util.Log.i("Alder", "no other video player", e)
            checkpoint.onHandOffCancelled()
            externalUnavailable = true
            false
        }
    }

    // ponytail: VLC gives no duration for network streams; the title's last known duration stands in, else nothing is saved.
    @Deprecated("Activity result API")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == SOURCES_REQUEST) return sourcesClosed(resultCode, data)
        if (requestCode != ExternalPlayer.REQUEST) return
        val (position, told) = ExternalPlayer.result(data)
        val item = item
        val duration = told.takeIf { it > 0 } ?: item?.optJSONObject("state")?.optLong("duration") ?: 0L
        android.util.Log.i("Alder", "other player stopped at $position of $duration")
        val canCommit = item != null && position > 0 && duration > 0 && !isLiveType(type) && !(episodic && !plausibleEpisode(duration))
        checkpoint.commitExternal(canCommit) {
            val current = item!!
            val nextId = nextVideo(onlyAired = false)?.id ?: intent.getStringExtra("nextVideoId")
            commitStopCheckpoint(
                record = {
                    Library.record(current, videoId, position, duration, (position - startAt).coerceAtLeast(0))
                    val videos = meta?.videos
                    if (videos != null && current.optJSONObject("state")?.optInt("flaggedWatched") == 1) Library.markWatched(current, videos, videoId)
                    current
                },
                nextVideoId = nextId,
                push = { snapshot ->
                    watched = true
                    push(snapshot)
                },
            )
        }
        finish()
    }

    private fun position() = if (started) player.currentPosition else startAt

    /** What one source learned about itself; a new source starts clean. */
    private fun resetSourceState() {
        stalls.clear()
        retries = 0
        asHls = false
        audioPicked = false
        subsPicked = false
        failedAudio.clear()
    }

    /** This source failed: the next one from the same second or, with none left, the list to choose from. */
    private fun next(reason: Failure) {
        if (isFinishing || isDestroyed) return
        if (paused || stopped) {
            pendingForegroundAction = { next(reason) }
            return
        }
        main.removeCallbacks(startTimeout)
        lastSourceFailure = reason
        FailureLog.record(FailureLog.Operation.PLAYBACK, reason)
        val position = position()
        if (at + 1 < sources.size) {
            at++
            resetSourceState()
            return load(position, failureMessage(R.string.player_source_failed_next, reason, advice = false))
        }
        if (isTrailer) {
            intent.getStringExtra("trailerYtId")?.takeIf { it.isNotBlank() }?.let { ytId ->
                val opened = runCatching {
                    startActivity(Intent(Intent.ACTION_VIEW, android.net.Uri.parse("vnd.youtube:$ytId")))
                }.isSuccess
                if (!opened && runCatching {
                    startActivity(Intent(Intent.ACTION_VIEW, android.net.Uri.parse("https://www.youtube.com/watch?v=$ytId")))
                }.isFailure) android.widget.Toast.makeText(this, R.string.trailer_no_app, android.widget.Toast.LENGTH_LONG).show()
            }
            finish()
            return
        }
        if (intent.getBooleanExtra("picked", false)) {
            if (started) record()
            startAt = position
            started = false
            showSourceError(reason)
            player.stop()
            showLoading(failureMessage(R.string.player_source_failed_choose, reason, advice = false))
            return
        }
        startActivity(Intent(this, StreamsActivity::class.java).putExtras(intent).putExtra("pick", true)
            .putExtra("message", failureMessage(R.string.player_source_failed_choose, reason, advice = false))
            .putExtra("startOver", false)
            .putExtra("resumeAt", position))
        finish()
    }

    /** Worth trying the same link again: a network or server hiccup, not a link that's gone (4xx) or a bad file. */
    private fun retryable(e: PlaybackException): Boolean {
        if (e.errorCode !in 2000..2999) return false
        val code = (e.cause as? HttpDataSource.InvalidResponseCodeException)?.responseCode ?: return true
        return code !in 400..499 || code == 408 || code == 429
    }

    private var audioPicked = false
    private var subsPicked = false
    private var audioGroups: List<Tracks.Group> = emptyList()
    private val failedAudio = mutableSetOf<Format>()

    /** Whether a format would play lossless as it is, as Alder decides it at the start of playback. */
    private fun audioLossless(formats: List<Format>): (Format) -> Boolean =
        // No lossless format among them (most web files): nothing to ask the audio route, the answer is no for each.
        if (decodeAudio || retryWithPcm || formats.none { losslessEncoding(it) != null }) ({ _: Format -> false })
        else losslessOutput(this, player.audioAttributes)

    private fun bestAudio(groups: List<Tracks.Group>): Tracks.Group? {
        val supported = groups.filter { it.isTrackSupported(0) }
        if (supported.isEmpty()) return null
        val formats = supported.map { it.getTrackFormat(0) }
        val best = bestAudioFormat(formats, wantAudio, failedAudio, audioLossless(formats))
        return supported.firstOrNull { it.getTrackFormat(0) === best }
    }

    private fun retryAudio(track: Tracks.Group?, reason: String) {
        val resume = position()
        if (track != null) {
            audioPicked = true
            player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
                .setOverrideForType(TrackSelectionOverride(track.mediaTrackGroup, (0 until track.length).toList())).build()
        }
        if (shouldSeekOnAudioRetry(player.isCurrentMediaItemLive, player.isCurrentMediaItemSeekable)) {
            player.seekTo(resume)
        }
        player.prepare()
        if (!started && !stopped && !paused) {
            main.removeCallbacks(startTimeout)
            main.postDelayed(startTimeout, startWait)
        }
        android.util.Log.i("Alder", reason)
    }

    private fun selectedTrackPassesAudio(): Boolean =
        !decodeAudio && !retryWithPcm && player.currentTracks.groups
            .firstOrNull { it.type == C.TRACK_TYPE_AUDIO && it.isSelected }
            ?.let { passthroughOutput(this, player.audioAttributes)(it.getTrackFormat(0)) } == true

    private fun isLive() = isLiveType(type) || player.isCurrentMediaItemLive

    private val tickTask = Runnable { tick() }

    // Progress every 10 s, pushed to Stremio every minute (Stremio's own apps push every 3 minutes).
    private fun tick() {
        record()
        if (++ticks % 6 == 0) push()
        main.postDelayed(tickTask, TICK)
    }

    override fun onStart() {
        super.onStart()
        stopped = false
    }

    override fun onResume() {
        super.onResume()
        paused = false
        stopped = false
        val shouldResume = checkpoint.consumeResumeOnReturn(isFinishing, isDestroyed, prepared)
        if (!::status.isInitialized || isFinishing || isDestroyed || handedOff) return
        checkpoint.onResume(isFinishing)
        pendingForegroundAction?.let { action ->
            pendingForegroundAction = null
            action()
            return
        }
        if (prepared) {
            lastPosition = position()
            if (shouldResume) {
                if (player.playbackState == Player.STATE_IDLE) player.prepare()
                player.play()
                // Left before the picture showed: nothing was seen, so it gets a fresh START_WAIT.
                if (!started && !isTrailer) {
                    main.removeCallbacks(startTimeout)
                    main.postDelayed(startTimeout, startWait)
                }
            }
            if (!isTrailer) {
                main.removeCallbacks(tickTask)
                main.postDelayed(tickTask, TICK)
                main.removeCallbacks(skipTask)
                main.post(skipTask)
            }
            if (loading.visibility == View.VISIBLE && player.playbackState == Player.STATE_BUFFERING) {
                main.removeCallbacks(loadingTask)
                main.post(loadingTask)
            }
        }
    }

    private fun record(): JSONObject? {
        // Nothing is saved until a picture shows: a source that never starts would otherwise reset the resume point.
        // Live TV has no progress to keep, nor does a channel or event whose stream doesn't say it's live.
        if (isTrailer || !started || player.isCurrentMediaItemLive || isLiveType(type)) return null
        if (episodic && !plausibleEpisode(player.duration)) return null
        val item = item ?: return null
        val position = player.currentPosition
        val duration = player.duration.takeIf { it > 0 } ?: return null
        // Only real viewing counts as watched time: a seek moves the position by more than a tick allows.
        val played = (position - lastPosition).takeIf { it in 1..TICK * 2 } ?: 0
        lastPosition = position
        Library.record(item, videoId, position, duration, played)
        val videos = meta?.videos
        if (!tickedWatched && videos != null && item.optJSONObject("state")?.optInt("flaggedWatched") == 1) {
            tickedWatched = true
            Library.markWatched(item, videos, videoId)
        }
        // Keep the current episode full screen; offer the next one only near its actual end.
        // If credits timing (outroAt) is known, wait until outroAt before showing Up next.
        val shouldShowUpNext = if (outroAt > 0) position >= outroAt else duration - position < upNextWindow(duration)
        if (!upNextDismissed && upNextPanel.visibility != View.VISIBLE && shouldShowUpNext) showUpNext()
        return item
    }

    /** The episode after this one, or null for a film, the last episode, or before the episode list is in. */
    private fun nextVideo(onlyAired: Boolean = true): Video? {
        val videos = meta?.videos ?: return null
        val at = videos.indexOfFirst { it.id == videoId }.takeIf { it >= 0 } ?: return null
        val next = videos.getOrNull(at + 1)?.takeIf { it.season != 0 || videos[at].season == 0 } // not from the finale into specials
        return if (onlyAired && next != null && !isEpisodeAired(next.released)) null else next
    }

    private fun stalled() {
        val now = System.currentTimeMillis()
        stalls.addLast(now)
        while (stalls.first() < now - 60_000) stalls.removeFirst()
        // Download speed against the file's bitrate tells a slow link (speed below bitrate) from a short buffer.
        val size = sources.getOrNull(at)?.size ?: 0L
        val fileKbps = if (size > 0 && player.duration > 0) size * 8 / player.duration else -1
        android.util.Log.i("Alder", "stall: download ${DefaultBandwidthMeter.getSingletonInstance(this).bitrateEstimate / 1000} kbps, " +
            "file $fileKbps kbps, buffered ${player.totalBufferedDuration} ms, heap ${Runtime.getRuntime().maxMemory() shr 20} MB")
        val height = sources[at].height
        if (stalls.size != 3 || offeringLower || upNextPanel.visibility == View.VISIBLE || lowerSourceHeight(height) == null) return
        showStall(height)
    }

    private fun showStall(height: Int) {
        val lower = lowerSourceHeight(height) ?: return
        offeringLower = true
        closePanels()
        stallPanel.removeAllViews()
        val card = column(Gravity.START).apply {
            setPadding(dp(16), dp(15), dp(16), dp(14))
            background = videoGlass(14)
            addView(text(getString(R.string.player_switch_prompt, lower), SERIF_TITLE, serif = true))
            addView(text(getString(R.string.player_switch_desc, height, clock(position())),
                SMALL, color = TEXT_2), LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(5) })
            val actions = LinearLayout(this@PlayerActivity).apply { gravity = Gravity.CENTER_VERTICAL }
            val lowerButton = pill(getString(R.string.player_switch_to, lower)).apply { setOnClickListener { lowerQuality() } }
            actions.addView(lowerButton, wrap())
            actions.addView(chip(getString(R.string.player_keep, height)).apply { setOnClickListener { dismissStall() } },
                wrap().apply { marginStart = dp(6) })
            actions.addView(chip(R.string.player_other_sources).apply { setOnClickListener { changeStream() } },
                wrap().apply { marginStart = dp(6) })
            addView(actions, wrap().apply { topMargin = dp(12) })
            lowerButton.post { lowerButton.requestFocus() }
        }
        stallPanel.addView(card, FrameLayout.LayoutParams(dp(460), -2, Gravity.BOTTOM or Gravity.END).apply {
            rightMargin = dp(PAD); bottomMargin = dp(48)
        })
        stallPanel.visibility = View.VISIBLE
    }

    private fun dismissStall() {
        offeringLower = false
        stallPanel.visibility = View.GONE
        stallPanel.removeAllViews()
    }

    private fun showSourceError(reason: Failure) {
        prepared = false
        closePanels()
        stallPanel.removeAllViews()
        val card = column(Gravity.START,
            caps(R.string.player_source_failed, TUNGSTEN),
            text(failureMessage(R.string.player_try_another, reason), SERIF_TITLE, serif = true).marginTop(6),
            pill(R.string.player_other_sources).apply { setOnClickListener { changeStream() } }.marginTop(12)
        ).apply {
            setPadding(dp(18), dp(18), dp(18), dp(18))
            background = videoGlass(14)
        }
        stallPanel.addView(card, FrameLayout.LayoutParams(dp(310), -2, Gravity.BOTTOM or Gravity.END).apply {
            rightMargin = dp(PAD); bottomMargin = dp(48)
        })
        stallPanel.visibility = View.VISIBLE
        card.getChildAt(2).requestFocus()
    }

    /** Continue at the same second with one quality step lower. */
    private fun lowerQuality() {
        val height = sources[at].height
        val lower = lowerSourceHeight(height) ?: return
        startActivity(Intent(this, StreamsActivity::class.java).putExtras(intent).apply {
            removeExtra("picked"); removeExtra("pick")
        }.putExtra("maxHeight", lower).putExtra("resumeAt", player.currentPosition))
        finish()
    }

    private fun showUpNext() {
        if (episodic && !plausibleEpisode(player.duration)) return
        if (stillPanel.visibility == View.VISIBLE) return
        val next = nextVideo() ?: return
        if (offeringLower) dismissStall()
        if (shown) hideControls(force = true)
        hideLoading()
        upNextPanel.removeAllViews()
        val playNext = pill(R.string.player_next_episode, R.drawable.ic_play).apply { setOnClickListener { playNext() } }
        val content = column(Gravity.START).apply {
            setPadding(dp(18), dp(14), dp(18), dp(14))
            // Video can't be frosted: a dark base under the glass keeps it readable over white end cards.
            background = videoGlass(14)
            addView(text(next.title.ifBlank { getString(R.string.episode_number, next.episode) }, SERIF_TITLE, serif = true).onArt())
            addView(text("S${next.season} E${next.episode}", SMALL, color = TEXT_2).onArt(), wrap().apply { topMargin = dp(5) })
            addView(playNext, wrap().apply { topMargin = dp(16) })
        }
        upNextPanel.addView(content, FrameLayout.LayoutParams(dp(360), -2, Gravity.BOTTOM or Gravity.END).apply {
            rightMargin = dp(PAD); bottomMargin = dp(68)
        })
        upNextPanel.visibility = View.VISIBLE
        playNext.requestFocus()
    }

    private fun dismissUpNext() {
        upNextDismissed = true
        upNextPanel.visibility = View.GONE
    }

    /** Hands the next episode to the sources page, which starts it (preferring this release's group) and finishes here.
     *  [quietly]: episodes in a row that ended with no key pressed (0 when the viewer chose it). */
    private fun playNext(quietly: Int = 0) {
        val next = nextVideo() ?: return finish()
        val videos = meta!!.videos
        val after = videos.getOrNull(videos.indexOf(next) + 1)?.takeIf { it.season != 0 }
        val name = intent.getStringExtra("name") ?: ""
        startActivity(Intent(this, StreamsActivity::class.java)
            .putExtra("type", type).putExtra("id", metaId).putExtra("name", name)
            .putExtra("poster", intent.getStringExtra("poster")).putExtra("background", intent.getStringExtra("background"))
            .putExtra("videoId", next.id).putExtra("nextVideoId", after?.id)
            .putExtra("label", "$name  ·  S${next.season} E${next.episode}")
            .putExtra("logo", meta?.logo).putExtra("still", next.thumbnail).putExtra("runtime", meta?.runtime)
            .putExtra("episode", "S${next.season} E${next.episode}  ·  ${next.title}")
            .putExtra("quiet", quietly))
        finish()
    }

    /**
     * "Still watching?", in the Up next place: before the next episode ([next]) once [QUIET_EPISODES] ended with no key
     * pressed. Playback stops, and the screen may sleep.
     */
    private fun showStillWatching(next: Video) {
        if (offeringLower) dismissStall()
        if (shown) hideControls(force = true)
        upNextPanel.visibility = View.GONE
        hideLoading()
        stillPanel.removeAllViews()
        stillPanel.visibility = View.VISIBLE
        player.pause()
        pausedShade.visibility = View.VISIBLE
        view.keepScreenOn = false // nobody may be watching: let the TV's own screen saver and sleep take over
        val play = pill(getString(R.string.player_play_episode, next.season, next.episode), R.drawable.ic_play).apply {
            setOnClickListener { playNext() }
        }
        val actions = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        actions.addView(play, wrap())
        actions.addView(chip(R.string.player_exit_home).apply {
            setOnClickListener {
                startActivity(Intent(this@PlayerActivity, MainActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP))
                finish()
            }
        }, wrap().apply { marginStart = dp(8) })
        val title = next.title.ifBlank { getString(R.string.episode_number, next.episode) }
        val why = getString(R.string.player_quiet_episodes, QUIET_EPISODES)
        val content = column(Gravity.START).apply {
            setPadding(dp(18), dp(14), dp(18), dp(14))
            background = videoGlass(14)
            addView(caps(R.string.player_still_watching, TUNGSTEN).onArt())
            addView(text(title, SERIF_HERO, serif = true).onArt().apply { maxLines = 2; ellipsize = TextUtils.TruncateAt.END },
                LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(6) })
            addView(text(why, SMALL, color = TEXT_2).onArt(), LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(5) })
            addView(actions, wrap().apply { topMargin = dp(16) })
        }
        stillPanel.addView(content, FrameLayout.LayoutParams(dp(500), -2, Gravity.BOTTOM or Gravity.END).apply {
            rightMargin = dp(PAD); bottomMargin = dp(68)
        })
        play.requestFocus()
    }

    /** Back on "Still watching?": at the end the player closes; mid-episode it stays paused with the controls. */
    private fun leaveStillWatching(resume: Boolean = true) {
        if (player.playbackState == Player.STATE_ENDED) return finish()
        stillPanel.visibility = View.GONE
        stillPanel.removeAllViews()
        view.keepScreenOn = true
        if (resume) player.play()
        showControls(playPause)
    }

    /**
     * Switches the TV to a refresh rate the film divides into (24 Hz for 24 fps, 50 Hz for 25), as Just Player does, so
     * pans don't judder. Same resolution only; the window gives the mode back when the player closes.
     */
    private fun matchFrameRate(fps: Float) {
        if (fps <= 0f || !Settings.matchFrameRate(this)) return
        val display = window.decorView.display ?: return
        val now = display.mode
        fun fits(m: Display.Mode) = (m.refreshRate / fps).let { r -> r.roundToInt() >= 1 && abs(r - r.roundToInt()) < 0.0005f }
        if (fits(now)) return
        val best = display.supportedModes
            .filter { it.physicalWidth == now.physicalWidth && it.physicalHeight == now.physicalHeight && fits(it) }
            .minByOrNull { it.refreshRate } ?: return
        window.attributes = window.attributes.apply { preferredDisplayModeId = best.modeId }
    }

    private fun commitPauseCheckpoint() {
        val nextId = nextVideo(onlyAired = false)?.id ?: intent.getStringExtra("nextVideoId")
        commitStopCheckpoint(
            record = { (if (started) record() else null) ?: item },
            nextVideoId = nextId,
            push = ::push,
        )
    }

    private fun push(snapshot: JSONObject? = item?.let { JSONObject(it.toString()) }) {
        if (isTrailer || !watched || player.isCurrentMediaItemLive || isLiveType(type)) return
        val key = authKey ?: return
        val copy = snapshot ?: return
        ProgressSaves.keep(key, copy, provisionalProgress)
        background({ syncWatchProgress(key) }) { result ->
            result.getOrNull()?.let { failure ->
                android.widget.Toast.makeText(this, failureMessage(R.string.progress_saved_on_tv, failure, source = "Stremio", advice = false),
                    android.widget.Toast.LENGTH_LONG).show()
            }
        }
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        // Someone is awake: the count of episodes with no key pressed starts again (also for another source of this one).
        if (event.action == KeyEvent.ACTION_DOWN && quiet != 0) { quiet = 0; intent.removeExtra("quiet") }
        if (stillPanel.visibility == View.VISIBLE) {
            if (event.keyCode == KeyEvent.KEYCODE_BACK) {
                if (event.action == KeyEvent.ACTION_UP) leaveStillWatching(resume = false)
                return true
            }
            return super.dispatchKeyEvent(event)
        }
        if (upNextPanel.visibility == View.VISIBLE) {
            if (event.keyCode == KeyEvent.KEYCODE_BACK) {
                if (event.action == KeyEvent.ACTION_DOWN) {
                    wakeKey = KeyEvent.KEYCODE_BACK
                    dismissUpNext()
                }
                return true
            }
            return super.dispatchKeyEvent(event)
        }
        if (stallPanel.visibility == View.VISIBLE) {
            if (event.keyCode == KeyEvent.KEYCODE_BACK) {
                if (event.action == KeyEvent.ACTION_UP) dismissStall()
                return true
            }
            return super.dispatchKeyEvent(event)
        }
        val down = event.action == KeyEvent.ACTION_DOWN
        if (!started) {
            if (event.keyCode == KeyEvent.KEYCODE_BACK) {
                if (down) {
                    resolvingStreams?.cancel()
                    resolvingStreams = null
                    finish()
                }
                return true
            }
            return true
        }
        when (event.keyCode) {
            KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE -> { if (down) { if (player.playWhenReady) player.pause() else player.play() }; return true }
            KeyEvent.KEYCODE_MEDIA_PLAY -> { if (down) player.play(); return true }
            KeyEvent.KEYCODE_MEDIA_PAUSE -> { if (down) player.pause(); return true }
            KeyEvent.KEYCODE_MEDIA_FAST_FORWARD -> { if (down) { showControls(bar); scrub(SKIP) }; return true }
            KeyEvent.KEYCODE_MEDIA_REWIND -> { if (down) { showControls(bar); scrub(-SKIP) }; return true }
        }
        // The OK that woke the controls: its repeats and release would otherwise press the button it focused.
        // Skip intro: OK jumps past it while the pill shows over the picture (the controls down).
        if (skip.visibility == View.VISIBLE && !shown && down && event.keyCode in listOf(KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER)) {
            (skip.tag as? IntroSegment)?.takeIf { canSeek() }?.let { player.seekTo(it.endMs) }
            skip.visibility = View.GONE
            wakeKey = event.keyCode
            return true
        }
        if (wakeKey != 0 && event.keyCode == wakeKey) {
            if (!down) wakeKey = 0
            return true
        }
        // Back with the controls up closes them; the next Back leaves the player.
        if (shown && event.keyCode == KeyEvent.KEYCODE_BACK) {
            if (menuOpen) { // ChadFlix: one press closes the menu (an expanded More folds first); the next hides the controls
                if (!down) backInMenu()
                return true
            }
            if (chapterSnapMode) {
                if (!down) setChapterSnapMode(false)
                return true
            }
            if (!down) hideControls(force = true)
            return true
        }
        if (!shown && down) when (event.keyCode) {
            KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT -> { showControls(bar); return true }
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> {
                wakeKey = event.keyCode
                if (player.playWhenReady) player.pause() else player.play()
                showControls(playPause)
                return true
            }
            KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN -> { showControls(); return true }
        }
        if (shown) showControls(null) // any key keeps them up a while longer
        return super.dispatchKeyEvent(event)
    }

    // ---------- skip intro, as NuvioTV does it ----------
    // IntroDB (api.introdb.app, community timings by IMDb id, season and episode; no key, nothing personal sent) says where
    // an episode's intro, recap and credits are. Inside an intro or recap a "Skip intro" pill shows for 10 s, and OK
    // jumps to its end. The credits start, when known, is when Up next appears, rather than the last 4% guess.
    // ponytail: shows with IMDb ids only (anime episode ids under an IMDb show count); anime with no IMDb id
    // (AniSkip by MAL id) and films' credits aren't asked for.
    private lateinit var skip: TextView
    private var segments = emptyList<IntroSegment>()
    private var introDbSegments = emptyList<IntroSegment>()
    private var outroAt = 0L
    private var introDbOutroAt = 0L
    private var skipShownAt = 0L
    private val skipped = mutableSetOf<IntroSegment>() // shown once and left alone, or skipped: not offered again this time
    private val skipTask = Runnable { checkSegments() }

    private var introDbAsked = false

    private fun fetchSegments() {
        if (!episodic || introDbAsked) return
        // A Kitsu or MAL episode id needs the show's videos for its season and number, so it waits for the meta.
        val (imdbId, season, episode) = introDbEpisode(metaId, videoId, meta?.videos) ?: return
        introDbAsked = true
        background({
            val json = JSONObject(Http.get("https://api.introdb.app/segments?imdb_id=$imdbId&season=$season&episode=$episode"))
            fun part(name: String) = json.optJSONObject(name)?.let { seg ->
                val startSec = seg.optDouble("start_sec", -1.0)
                val endSec = seg.optDouble("end_sec", -1.0)
                val confidence = seg.optDouble("confidence", 1.0)
                // Filter out low confidence segments (must be >= 0.8)
                if (confidence < 0.8 || startSec < 0 || endSec <= startSec) null
                else (startSec * 1000).toLong() to (endSec * 1000).toLong()
            }
            Triple(part("intro"), part("recap"), part("outro"))
        }) { r ->
            val (intro, recap, outro) = r.getOrNull() ?: return@background
            introDbSegments = listOfNotNull(
                recap?.let { IntroSegment("Skip recap", it.first, it.second) },
                intro?.let { IntroSegment("Skip intro", it.first, it.second) }
            )
            introDbOutroAt = outro?.first ?: 0L
            reconcileSegments()
        }
    }

    private fun reconcileSegments() {
        val duration = player.duration.takeIf { it != C.TIME_UNSET && it > 0 } ?: 0L
        val mkvIntros = MkvChapters.findIntroSegments(mkvChapters, duration)
        if (mkvIntros.isNotEmpty()) {
            segments = mkvIntros.map { IntroSegment(it.label, it.startMs, it.endMs) }
            outroAt = introDbOutroAt
        } else {
            // Reject zero-second starts from IntroDB if file has chapters indicating a later start or cold open
            val hasColdOpenChapter = mkvChapters.any { it.startMs in 1L..120_000L }
            segments = introDbSegments.filterNot { seg ->
                seg.startMs == 0L && hasColdOpenChapter
            }
            outroAt = introDbOutroAt
        }
        updateMarkers()
    }

    private fun updateMarkers() {
        val duration = player.duration.takeIf { it != C.TIME_UNSET && it > 0 } ?: 0L
        markers = TimelineMarkers.merge(mkvChapters, segments, outroAt, duration)
        bar.setMarkers(markers)
        if (shown) updateProgress()
    }

    private fun checkSegments() {
        main.removeCallbacks(skipTask)
        if (!started || player.isCurrentMediaItemLive) return run { main.postDelayed(skipTask, 500) }
        val position = player.currentPosition
        val now = segments.firstOrNull { position >= it.startMs && position < it.endMs - 1_000 && it !in skipped }
        if (now == null || shown) skip.visibility = View.GONE
        else if (skip.tag !== now || skip.visibility != View.VISIBLE) {
            if (skip.tag !== now) skipShownAt = System.currentTimeMillis()
            skip.tag = now
            // The label names the kind ("Skip recap"); the words come from the viewer's language.
            skip.text = getString(if (now.label.contains("recap", ignoreCase = true)) R.string.skip_recap else R.string.skip_intro) + "   OK"
            skip.visibility = View.VISIBLE
        }
        if (now != null && skip.tag === now && System.currentTimeMillis() - skipShownAt > SKIP_SHOWN) { skipped += now; skip.visibility = View.GONE }
        if (outroAt > 0 && position >= outroAt && player.duration - position < 60_000 &&
            !upNextDismissed && upNextPanel.visibility != View.VISIBLE && player.duration > 0) showUpNext()
        main.postDelayed(skipTask, 500)
    }

    // ---------- player controls ----------
    private lateinit var controls: FrameLayout
    private lateinit var bar: SeekBar
    private lateinit var chapterPill: TextView
    private lateinit var elapsed: TextView
    private lateinit var length: TextView
    private lateinit var playPause: TextView
    private lateinit var audioButton: TextView
    private lateinit var trackStatus: TextView // "English  ·  Spanish subtitles", under the bar whenever the controls are up
    private lateinit var nextCell: FrameLayout
    private lateinit var infoButton: TextView
    private val dimmed = mutableListOf<View>() // the other buttons, dimmed while the menu is open
    private lateinit var endsAt: TextView
    private lateinit var playerLogo: ImageView // the Info pop-up's logo, or its name when there is none
    private lateinit var topName: TextView
    private lateinit var pauseTitle: TextView
    private lateinit var pauseInfo: LinearLayout
    private lateinit var pauseFacts: TextView
    private lateinit var subtitle: TextView // the year or S5 E2
    private val buttonLabels = mutableMapOf<View, TextView>()
    private var shown = false
    private var wakeKey = 0
    private var scrubTo = -1L
    private var chapterSnapMode = false
    private var markers = emptyList<Marker>()
    private var mkvChapters = emptyList<MkvChapter>()
    private var shownBadges = emptyList<PlayerBadge>()
    private val hideTask = Runnable { hideControls() }
    private val progressTask = Runnable { updateProgress() }
    private val seekTask = Runnable { if (scrubTo >= 0) { player.seekTo(scrubTo); scrubTo = -1 } }

    private fun pillBackground(snapMode: Boolean) = GradientDrawable().apply {
        cornerRadius = dp(12).toFloat()
        if (snapMode) {
            setColor(0xE60E0C0A.toInt())
            setStroke(dp(1), Screen.TUNGSTEN_RIM)
        } else {
            setColor(0xCC0E0C0A.toInt())
        }
    }

    private fun setChapterSnapMode(enabled: Boolean) {
        if (chapterSnapMode == enabled) return
        chapterSnapMode = enabled
        chapterPill.background = pillBackground(enabled)
        chapterPill.setTextColor(if (enabled) Screen.TUNGSTEN else Screen.TEXT_2)
        bar.invalidate()
        if (shown) updateProgress()
    }

    private fun buildControls(): FrameLayout {
        val name = intent.getStringExtra("name").orEmpty()
        menuLayer = FrameLayout(this).apply { visibility = View.GONE; clipChildren = false }
        infoLayer = FrameLayout(this).apply { visibility = View.GONE; clipChildren = false }
        // ChadFlix: the title's logo (or its name) is the Info pop-up's, no longer at the top left; it is loaded when the pop-up first shows.
        playerLogo = ImageView(this).apply { adjustViewBounds = true; maxWidth = dp(225); maxHeight = dp(75); visibility = View.GONE }
        topName = text(name, SERIF_HERO, serif = true).onArt().apply {
            maxLines = 2; ellipsize = TextUtils.TruncateAt.END
        }
        endsAt = text("", BODY, bold = true, color = 0xB3FFFFFF.toInt()).onArt().apply { maxLines = 1; visibility = View.GONE }
        pauseTitle = text(intent.getStringExtra("episode")?.substringAfter("  ·  ", "")?.takeIf { it.isNotBlank() }
            ?: name, SERIF_HERO, serif = true).onArt().apply {
            maxLines = 1; ellipsize = TextUtils.TruncateAt.END
        }
        subtitle = text(if (isTrailer) getString(R.string.action_trailer) else intent.getStringExtra("episode")?.substringBefore("  ·  ").orEmpty(), SMALL, color = TEXT_2).onArt()
        pauseFacts = text(if (sources.isEmpty() && !isTrailer) getString(R.string.finding_sources) else "", SMALL, color = TEXT_2).onArt()
        val pauseMetaRow = LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            addView(pauseFacts, wrap())
        }
        pauseInfo = column(Gravity.START).apply {
            if (isTrailer) addView(caps(R.string.action_trailer, TUNGSTEN).onArt())
            addView(pauseTitle, LinearLayout.LayoutParams(dp(640), -2).apply { if (isTrailer) topMargin = dp(6) })
            addView(pauseMetaRow, LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(6) })
        }
        refreshBadges()

        trackStatus = text("", SMALL, color = TEXT_2).onArt().apply { maxLines = 1; ellipsize = TextUtils.TruncateAt.END }
        chapterPill = text("", SMALL, color = TEXT_2).onArt().apply {
            setPadding(dp(10), dp(3), dp(10), dp(3))
            background = pillBackground(false)
            visibility = View.INVISIBLE
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        }
        elapsed = text("", SMALL).onArt()
        length = text("", SMALL).onArt()
        bar = SeekBar(this).apply {
            id = View.generateViewId()
            isFocusable = true
            contentDescription = getString(R.string.player_seek)
            setOnFocusChangeListener { _, focused ->
                if (!focused) setChapterSnapMode(false)
                invalidate()
            }
            setOnClickListener {
                if (chapterSnapMode) {
                    if (scrubTo >= 0) { player.seekTo(scrubTo); scrubTo = -1 }
                    setChapterSnapMode(false)
                } else {
                    if (player.playWhenReady) player.pause() else player.play()
                }
            }
            setOnKeyListener { _, code, event ->
                val current = if (scrubTo >= 0) scrubTo else player.currentPosition
                if (chapterSnapMode) {
                    if (event.action != KeyEvent.ACTION_DOWN) return@setOnKeyListener false
                    when (code) {
                        KeyEvent.KEYCODE_DPAD_RIGHT -> {
                            val next = TimelineMarkers.findNext(markers, current)
                            if (next != null) {
                                scrubTo = next.timeMs
                                updateProgress()
                                main.removeCallbacks(seekTask)
                                main.postDelayed(seekTask, SEEK_SETTLE)
                            }
                            true
                        }
                        KeyEvent.KEYCODE_DPAD_LEFT -> {
                            val prev = TimelineMarkers.findPrev(markers, current)
                            if (prev != null) {
                                scrubTo = prev.timeMs
                                updateProgress()
                                main.removeCallbacks(seekTask)
                                main.postDelayed(seekTask, SEEK_SETTLE)
                            }
                            true
                        }
                        KeyEvent.KEYCODE_DPAD_DOWN -> {
                            setChapterSnapMode(false)
                            true
                        }
                        KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> {
                            if (scrubTo >= 0) { player.seekTo(scrubTo); scrubTo = -1 }
                            setChapterSnapMode(false)
                            true
                        }
                        else -> false
                    }
                } else {
                    val by = when (code) {
                        KeyEvent.KEYCODE_DPAD_LEFT -> -1
                        KeyEvent.KEYCODE_DPAD_RIGHT -> 1
                        KeyEvent.KEYCODE_DPAD_UP -> {
                            if (event.action == KeyEvent.ACTION_DOWN && markers.size > 1) {
                                setChapterSnapMode(true)
                                return@setOnKeyListener true
                            }
                            return@setOnKeyListener false
                        }
                        KeyEvent.KEYCODE_DPAD_DOWN -> {
                            if (scrubTo >= 0) {
                                scrubTo = -1
                                updateProgress()
                            }
                            return@setOnKeyListener false
                        }
                        else -> return@setOnKeyListener false
                    }
                    if (event.action == KeyEvent.ACTION_DOWN)
                        scrub(by * when { event.repeatCount < 10 -> SKIP; event.repeatCount < 30 -> 30_000L; else -> 60_000L })
                    true
                }
            }
        }

        audioButton = button(getString(R.string.player_audio_subtitles), R.drawable.ic_subtitles) { enterMenu() }
        val streamButton = button(getString(R.string.player_change_stream), R.drawable.ic_layers) { changeStream() }
        playPause = button(getString(R.string.player_pause), R.drawable.ic_pause, primary = true) {
            if (player.playWhenReady) player.pause() else player.play()
        }
        val nextButton = button(getString(R.string.player_next_episode), R.drawable.ic_next_episode) { playNext() }
        // ChadFlix: OK does nothing here (it used to open the title page): the Info pop-up shows while this button has focus.
        infoButton = button(getString(R.string.player_info), R.drawable.ic_info) {}
        // Landing on Audio & Subtitles opens its menu and landing on Info shows the pop-up, after a short dwell.
        audioButton.apply {
            val face = onFocusChangeListener
            setOnFocusChangeListener { v, has -> face?.onFocusChange(v, has); audioFocusChanged(has) }
            setOnKeyListener { _, code, event ->
                (code == KeyEvent.KEYCODE_DPAD_UP).also { if (it && event.action == KeyEvent.ACTION_DOWN) enterMenu() }
            }
        }
        infoButton.apply {
            val face = onFocusChangeListener
            setOnFocusChangeListener { v, has -> face?.onFocusChange(v, has); infoFocusChanged(has) }
        }
        nextCell = buttonCell(nextButton).apply { visibility = View.GONE }
        val buttons = LinearLayout(this).apply {
            clipChildren = false // a focused button's label is wider than its cell
            clipToPadding = false
            gravity = Gravity.CENTER_HORIZONTAL or Gravity.TOP
            if (!isTrailer) {
                addView(LinearLayout(context).apply {
                    clipChildren = false; clipToPadding = false
                    gravity = Gravity.END or Gravity.TOP
                    addView(buttonCell(streamButton).also { dimmed += it })
                    addView(buttonCell(audioButton))
                    addView(View(context).apply { setBackgroundColor(GLASS_EDGE) },
                        LinearLayout.LayoutParams(dp(1), dp(22)).apply { topMargin = dp(14); marginEnd = dp(10) })
                }, LinearLayout.LayoutParams(0, -2, 1f))
            }
            addView(buttonCell(playPause, true).also { dimmed += it })
            if (!isTrailer) {
                addView(LinearLayout(context).apply {
                    clipChildren = false; clipToPadding = false
                    gravity = Gravity.START or Gravity.TOP
                    addView(View(context).apply { setBackgroundColor(GLASS_EDGE) },
                        LinearLayout.LayoutParams(dp(1), dp(22)).apply { topMargin = dp(14); marginStart = dp(10) })
                    addView(nextCell.also { dimmed += it })
                    addView(buttonCell(infoButton).also { dimmed += it })
                }, LinearLayout.LayoutParams(0, -2, 1f))
            }
        }
        for (button in listOf(streamButton, audioButton, playPause, nextButton, infoButton))
            button.nextFocusUpId = bar.id
        playPause.id = View.generateViewId()
        bar.nextFocusDownId = playPause.id
        return FrameLayout(this).apply {
            visibility = View.GONE
            alpha = 0f
            addView(View(context).apply {
                background = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                    intArrayOf(0x990E0C0A.toInt(), 0x000E0C0A))
            }, FrameLayout.LayoutParams(-1, dp(120), Gravity.TOP))
            addView(View(context).apply {
                background = GradientDrawable(GradientDrawable.Orientation.BOTTOM_TOP,
                    intArrayOf(0xBF0E0C0A.toInt(), 0x590E0C0A, 0x000E0C0A))
            }, FrameLayout.LayoutParams(-1, dp(220), Gravity.BOTTOM))
            // ChadFlix: "Ends at 2:40 PM" at the top left, once; it replaces the logo, the name and the clock up here.
            addView(endsAt, FrameLayout.LayoutParams(-2, -2, Gravity.TOP or Gravity.START).apply {
                marginStart = dp(PAD); topMargin = dp(31)
            })
            addView(pauseInfo, FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM or Gravity.START).apply {
                leftMargin = dp(PAD); bottomMargin = dp(168)
            })
            addView(chapterPill, FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM or Gravity.START).apply {
                leftMargin = dp(PAD); bottomMargin = dp(137)
            })
            addView(bar, FrameLayout.LayoutParams(-1, dp(SEEK_BAR_H), Gravity.BOTTOM).apply {
                leftMargin = dp(PAD); rightMargin = dp(PAD); bottomMargin = dp(SEEK_BAR_BOTTOM)
            })
            addView(elapsed, FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM or Gravity.START).apply {
                leftMargin = dp(PAD); bottomMargin = dp(91)
            })
            addView(length, FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM or Gravity.END).apply {
                rightMargin = dp(PAD); bottomMargin = dp(91)
            })
            // ChadFlix: the menu and the pop-up, empty until they open, under the buttons so the focused one stays lit.
            addView(menuLayer, FrameLayout.LayoutParams(-1, -1))
            addView(infoLayer, FrameLayout.LayoutParams(-1, -1))
            addView(buttons, FrameLayout.LayoutParams(-1, dp(76), Gravity.BOTTOM).apply {
                bottomMargin = dp(11)
            })
        }
    }

    private fun refreshBadges(tracks: Tracks = player.currentTracks) {
        if (!::pauseInfo.isInitialized || isTrailer) return
        val audio = tracks.groups.firstOrNull { it.type == C.TRACK_TYPE_AUDIO && it.isSelected }?.getTrackFormat(0)
        val videoHeight = player.videoFormat?.height?.takeIf { it > 0 }
            ?: tracks.groups.firstOrNull { it.type == C.TRACK_TYPE_VIDEO && it.isSelected }?.getTrackFormat(0)?.height ?: 0
        val videoWidth = player.videoFormat?.width?.takeIf { it > 0 }
            ?: tracks.groups.firstOrNull { it.type == C.TRACK_TYPE_VIDEO && it.isSelected }?.getTrackFormat(0)?.width ?: 0
        val badges = playerBadges(
            stream = sources.getOrNull(at),
            videoHeight = videoHeight,
            videoWidth = videoWidth,
            audioMime = audio?.sampleMimeType,
            audioLabel = audio?.label,
            audioChannels = audio?.channelCount?.takeIf { it > 0 } ?: 0,
        )
        // ChadFlix (owner): the badges show only in the Info pop-up, not on the play page.
        if (badges == shownBadges) return
        shownBadges = badges
        if (infoOpen) showInfo()
    }

    /** Monochrome House Lights brand badge: custom vector marks for Dolby / DTS, or crisp typographic lockups. */
    private fun badgeView(b: PlayerBadge): View {
        val rim = if (b.accent) 0x80F3D9B1.toInt() else 0x66FFF0DC
        val frame = GradientDrawable().apply {
            setColor(0xEB0E0C0A.toInt())
            cornerRadius = dp(4).toFloat()
            setStroke(dp(1), rim)
        }
        return when (b.brand) {
            BadgeBrand.DOLBY -> LinearLayout(this).apply {
                gravity = Gravity.CENTER_VERTICAL
                background = frame
                setPadding(dp(6), 0, dp(6), 0)
                addView(dolbyIcon(if (b.accent) TUNGSTEN else TEXT), LinearLayout.LayoutParams(dp(13), dp(9)).apply { marginEnd = dp(5) })
                addView(text("DOLBY ${b.label}", 9.5f, bold = true, color = if (b.accent) TUNGSTEN else TEXT).apply {
                    includeFontPadding = false
                    letterSpacing = 0.06f
                }, wrap())
            }
            BadgeBrand.DTS -> LinearLayout(this).apply {
                gravity = Gravity.CENTER_VERTICAL
                background = frame
                setPadding(dp(6), 0, dp(6), 0)
                addView(dtsIcon(if (b.accent) TUNGSTEN else TEXT), LinearLayout.LayoutParams(dp(9), dp(9)).apply { marginEnd = dp(4) })
                val suffix = when (b.label) { "HD" -> "-HD"; "X" -> ":X"; else -> "" }
                addView(text("DTS$suffix", 9.5f, bold = true, color = if (b.accent) TUNGSTEN else TEXT).apply {
                    includeFontPadding = false
                    letterSpacing = 0.06f
                }, wrap())
            }
            BadgeBrand.NONE -> text(b.label, 9.5f, bold = true, color = if (b.accent) TUNGSTEN else TEXT_2).apply {
                gravity = Gravity.CENTER
                includeFontPadding = false
                letterSpacing = 0.05f
                background = frame
                setPadding(dp(6), 0, dp(6), 0)
            }
        }
    }

    /** The Dolby double-D mark (two opposing D shapes), drawn in [color]. */
    private fun dolbyIcon(color: Int) = object : View(this) {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = color }
        private val path = android.graphics.Path()
        private val rect = android.graphics.RectF()
        override fun onDraw(canvas: Canvas) {
            val w = width.toFloat()
            val h = height.toFloat()
            val r = h / 2f
            path.reset()
            rect.set(0f, 0f, w * 0.44f, h)
            path.addRoundRect(rect, floatArrayOf(0f, 0f, r, r, r, r, 0f, 0f), android.graphics.Path.Direction.CW)
            rect.set(w * 0.56f, 0f, w, h)
            path.addRoundRect(rect, floatArrayOf(r, r, 0f, 0f, 0f, 0f, r, r), android.graphics.Path.Direction.CW)
            canvas.drawPath(path, paint)
        }
    }

    /** A stylized three-bar DTS acoustic slash mark, drawn in [color]. */
    private fun dtsIcon(color: Int) = object : View(this) {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = color }
        override fun onDraw(canvas: Canvas) {
            val w = width.toFloat()
            val h = height.toFloat()
            val barW = w * 0.22f
            val r = barW / 2f
            canvas.drawRoundRect(0f, h * 0.30f, barW, h * 0.70f, r, r, paint)
            canvas.drawRoundRect(w * 0.39f, h * 0.14f, w * 0.39f + barW, h * 0.86f, r, r, paint)
            canvas.drawRoundRect(w - barW, 0f, w, h, r, r, paint)
        }
    }

    /** An icon of [size] dp in the middle of whatever it's drawn over (a button's circle). */
    private fun centred(res: Int, size: Int, tint: Int) = getDrawable(res)?.mutate()?.let { d ->
        d.setTint(tint)
        android.graphics.drawable.LayerDrawable(arrayOf(d)).apply {
            setLayerSize(0, dp(size), dp(size)); setLayerGravity(0, Gravity.CENTER)
        }
    }

    private fun button(label: String, res: Int, primary: Boolean = false, onClick: () -> Unit): TextView {
        val words = text(label, SMALL, color = TEXT_2).onArt().apply {
            gravity = Gravity.CENTER; visibility = View.INVISIBLE
            maxLines = 1; ellipsize = TextUtils.TruncateAt.END
        }
        val radius = if (primary) 25 else 20
        val control = text("", BODY).apply {
            gravity = Gravity.CENTER
            contentDescription = label
            isFocusable = true
            if (res != 0) foreground = centred(res, if (primary) 25 else 20, if (primary) BASE else TEXT)
            if (primary) {
                fun face(focused: Boolean) = GradientDrawable().apply {
                    setColor(TEXT); cornerRadius = dp(radius).toFloat()
                }
                background = face(false)
                setOnFocusChangeListener { view, focused ->
                    view.background = face(focused)
                    view.grow(focused).start()
                    words.visibility = if (focused) View.VISIBLE else View.INVISIBLE
                }
            } else {
                fun face(focused: Boolean) = android.graphics.drawable.LayerDrawable(arrayOf(
                    rounded(VIDEO_CONTROL_BASE, radius), glass(radius, focused)))
                background = face(false)
                setOnFocusChangeListener { view, focused ->
                    view.background = face(focused)
                    words.visibility = if (focused) View.VISIBLE else View.INVISIBLE
                }
            }
            setOnClickListener { onClick() }
        }
        buttonLabels[control] = words
        return control
    }

    private fun buttonCell(control: TextView, primary: Boolean = false) = FrameLayout(this).apply {
        clipChildren = false
        clipToPadding = false
        addView(control, FrameLayout.LayoutParams(dp(if (primary) 50 else 40), dp(if (primary) 50 else 40),
            Gravity.TOP or Gravity.CENTER_HORIZONTAL))
        addView(buttonLabels.getValue(control), FrameLayout.LayoutParams(dp(160), dp(18),
            Gravity.TOP or Gravity.CENTER_HORIZONTAL).apply { topMargin = dp(if (primary) 53 else 48) })
        layoutParams = LinearLayout.LayoutParams(dp(if (primary) 70 else 62), dp(76))
    }

    /** Brings the controls up with [focus] focused (null: leaves focus where it is) and restarts the time to hide. */
    private fun showControls(focus: View? = playPause) {
        main.removeCallbacks(hideTask)
        if (!shown) {
            shown = true
            view.subtitleView?.setBottomPaddingFraction(0.28f) // keep captions above the seek bar and buttons
            controls.visibility = View.VISIBLE
            controls.enter(FOCUS_MS).alpha(1f).start()
            updateProgress()
            (focus ?: playPause).requestFocus()
        } else focus?.requestFocus()
        if (player.playWhenReady && !panelUp()) main.postDelayed(hideTask, HIDE)
    }

    private fun hideControls(force: Boolean = false) {
        if (!force && (!player.playWhenReady || panelUp())) return // ChadFlix: an open menu or pop-up keeps the controls up
        closeMenu(focusButton = false, restartHide = false)
        hideInfo(restartHide = false)
        setChapterSnapMode(false)
        chapterPill.visibility = View.INVISIBLE
        shown = false
        main.removeCallbacks(hideTask)
        main.removeCallbacks(progressTask)
        controls.exit(ENTER_MS).alpha(0f).withEndAction {
            if (!shown) {
                controls.visibility = View.GONE
                view.subtitleView?.setBottomPaddingFraction(0.08f)
            }
        }.start()
    }

    private fun updateProgress() {
        main.removeCallbacks(progressTask)
        if (!shown) return
        val live = player.isCurrentMediaItemLive
        val duration = player.duration.takeIf { it != C.TIME_UNSET } ?: 0L
        val position = if (scrubTo >= 0) scrubTo else player.currentPosition
        if (shown) {
            if (!bar.isIndeterminate) {
                bar.set(position, player.bufferedPosition, if (live) 0L else duration)
                elapsed.text = if (live) getString(R.string.player_live) else clock(position)
                length.text = if (live || duration <= 0) "" else "−${clock((duration - position).coerceAtLeast(0))}"
            }
            val active = if (markers.size > 1) TimelineMarkers.findCurrent(markers, position) else null
            if ((bar.isFocused || scrubTo >= 0 || chapterSnapMode) && active != null && !live) {
                chapterPill.text = when {
                    chapterSnapMode -> "‹  ${active.title}  ›"
                    bar.isFocused && scrubTo < 0 -> getString(R.string.player_chapters, active.title)
                    else -> active.title
                }
                chapterPill.visibility = View.VISIBLE
                val knob = dp(if (bar.isFocused) 9 else 6).toFloat()
                val barWidth = bar.width.takeIf { it > 0 } ?: (controls.width - 2 * dp(PAD))
                val knobX = knob + (barWidth - 2 * knob) * (if (duration > 0) position.coerceIn(0, duration) / duration.toFloat() else 0f)
                chapterPill.post {
                    val pillWidth = chapterPill.width
                    val targetX = (knobX - pillWidth / 2f).coerceIn(0f, (barWidth - pillWidth).toFloat().coerceAtLeast(0f))
                    chapterPill.translationX = targetX
                }
            } else {
                chapterPill.visibility = View.INVISIBLE
            }
        }
        // ChadFlix: "Ends at 2:40 PM" is its own line at the top left (none for a trailer, live TV or a length not known).
        val end = endsAtTime(System.currentTimeMillis(), duration, position, player.playbackParameters.speed, isTrailer, live)
        if (started) {
            endsAt.visibility = if (end == null) View.GONE else View.VISIBLE
            if (end != null) endsAt.text = getString(R.string.details_ends_at, android.text.format.DateFormat.format(
                if (android.text.format.DateFormat.is24HourFormat(this)) "HH:mm" else "h:mm a", end).toString())
            pauseFacts.text = subtitle.text.toString()
            pauseFacts.visibility = if (pauseFacts.text.isEmpty()) View.GONE else View.VISIBLE
        }
        main.postDelayed(progressTask, 500)
    }

    private fun clock(ms: Long): String {
        val s = ms / 1000
        return if (s >= 3600) "%d:%02d:%02d".format(s / 3600, s / 60 % 60, s % 60) else "%d:%02d".format(s / 60, s % 60)
    }

    /** Moves the playing point by [by] ms; the seek itself waits until the presses stop (SEEK_SETTLE). */
    private fun scrub(by: Long) {
        val duration = player.duration.takeIf { it != C.TIME_UNSET && it > 0 } ?: return
        if (player.isCurrentMediaItemLive || !canSeek()) return
        scrubTo = ((if (scrubTo >= 0) scrubTo else player.currentPosition) + by).coerceIn(0, maxOf(0, duration - 1_000))
        updateProgress()
        main.removeCallbacks(seekTask)
        main.postDelayed(seekTask, SEEK_SETTLE)
    }

    /** Whether this file can jump to another second. A seek in one that can't (no index, even by bitrate) lands on
     *  0:00, so it's refused with a word instead; once per source. */
    private fun canSeek(): Boolean {
        if (player.isCurrentMediaItemSeekable) return true
        if (!toldUnseekable) {
            toldUnseekable = true
            android.widget.Toast.makeText(this, getString(R.string.player_cannot_seek), android.widget.Toast.LENGTH_LONG).show()
        }
        return false
    }
    private var toldUnseekable = false

    /** The source list for this video, from this second; the player closes, and a pick starts a new one. */
    /**
     * The source list over a paused player, not in its place: Back comes back to the same picture at once, with no
     * reload (a debrid link can expire meanwhile, and a file that can't seek would restart at 0:00). A source picked
     * there comes back in [onActivityResult] and plays from the same second.
     */
    private fun changeStream() {
        playAfterSources = player.playWhenReady
        player.pause()
        startActivityForResult(Intent(this, StreamsActivity::class.java).putExtras(intent).apply {
            removeExtra("picked"); removeExtra("maxHeight")
            putExtra("pick", true); putExtra("fromPlayer", true)
            if (!player.isCurrentMediaItemLive) putExtra("resumeAt", position())
        }, SOURCES_REQUEST)
    }
    private var playAfterSources = false

    /** Back from the source list: the picked source at the same second, or the paused one as it was. */
    private fun sourcesClosed(resultCode: Int, data: Intent?) {
        val picked = data?.getStringExtra("sources")?.takeIf { resultCode == RESULT_OK }
        if (picked == null) {
            // The list is see-through (Alder.Over), so this screen was only paused and its picture is still there.
            if (playAfterSources) player.play()
            return
        }
        sources = JSONArray(picked).objects().map(Stream::read)
        at = 0
        resetSourceState()
        intent.putExtra("picked", true) // as before: if it fails, say so and offer the list again
        dismissStall()
        load(if (player.isCurrentMediaItemLive) 0L else position())
    }

    // ---------- ChadFlix: the Audio & Subtitles menu (with Appearance) and the Info pop-up (after the player-menu and info-popup drafts) ----------
    // Landing on Audio & Subtitles opens a menu above the buttons, over a 60% dim, after a short dwell so passing over
    // does not flash it. OK or Up goes into it, Left or Right leaves it, Back closes it. Landing on Info shows its pop-up
    // over the playing picture. Each is built when it opens and taken away when it closes: nothing exists or runs while
    // they are shut. An open one keeps the controls up.
    private lateinit var menuLayer: FrameLayout
    private lateinit var infoLayer: FrameLayout
    private var menuOpen = false
    private var infoOpen = false
    private var menuMoving = false // focus is moving into the menu on purpose, not leaving the button
    private var menuBusy = false // the rows are being replaced: focus callbacks mean nothing
    private var suppressOpen = false // focus returns to the button without opening the menu again
    private var infoShade: View? = null
    private var infoLogoUrl: String? = null
    private var originalLanguage: String? = null
    private var askedOriginal = false
    private var menuRows = 7
    private var menuPanel: View? = null
    private var sample: SubtitleView? = null // the sample line above the menu, while an Appearance row has focus
    private val dwellMenu = Runnable { if (audioButton.isFocused && !menuOpen && shown && !paused) openMenu() }
    private val dwellInfo = Runnable { if (infoButton.isFocused && !infoOpen && shown && !paused) showInfo() }

    private fun panelUp() = menuOpen || infoOpen
    private fun isRtl() = controls.layoutDirection == View.LAYOUT_DIRECTION_RTL


    private fun audioFocusChanged(has: Boolean) {
        main.removeCallbacks(dwellMenu)
        if (has) { if (!menuOpen && !suppressOpen) main.postDelayed(dwellMenu, DWELL) }
        else if (menuOpen && !menuMoving && !menuBusy) closeMenu() // Left or Right: on to a neighbour
    }

    private fun infoFocusChanged(has: Boolean) {
        main.removeCallbacks(dwellInfo)
        if (has) main.postDelayed(dwellInfo, DWELL) else hideInfo()
    }

    // ----- the menu -----

    /** [role]: a word like "Commentary" or "Forced" (or ""); [format]: an audio track's codec and channels, shown only under More. */
    private class MenuItem(val track: MenuTrack, val name: String, val role: String, val format: String, val pick: () -> Unit)
    private class MenuRow(val key: String, val item: MenuItem?, val view: LinearLayout, val check: ImageView,
                          val name: TextView, val detail: TextView)
    private class MenuColumn(val audio: Boolean) {
        var items = emptyList<MenuItem>()
        var rows = emptyList<MenuRow>()
        var checkedKey: String? = null
        var expanded = false
        var scroll = 0
        lateinit var view: LinearLayout
        lateinit var list: FrameLayout
        lateinit var inner: LinearLayout
        lateinit var rail: View
    }
    private val audioColumn = MenuColumn(true)
    private val subsColumn = MenuColumn(false)

    private fun audioItems(): List<MenuItem> {
        val groups = audioGroups.filter { it.isTrackSupported(0) }
        val formats = groups.map { it.getTrackFormat(0) }
        val preferred = preferredAudioFormats(formats, failedAudio, audioLossless(formats))
        return groups.mapIndexed { i, g ->
            val f = g.getTrackFormat(0)
            MenuItem(MenuTrack("audio:$i", Languages.code(f.language), g.isSelected, plainTrack(f.selectionFlags, f.roleFlags, f.label),
                preferred = preferred.any { it === f }),
                trackName(f, i), roleWord(true, f), audioFormat(f)) { pickAudio(g, Languages.code(f.language)) }
        }
    }

    private fun subtitleItems(): List<MenuItem> {
        val texts = player.currentTracks.groups.filter { it.type == C.TRACK_TYPE_TEXT && it.isTrackSupported(0) }
        val subs = texts.filterNot(::isAddonSub)
        val loaded = texts.firstOrNull(::isAddonSub)
        val chosen = selectedSubtitleTrackIndex(
            disabled = C.TRACK_TYPE_TEXT in player.trackSelectionParameters.disabledTrackTypes,
            addonSelected = loaded?.isSelected == true,
            embeddedCount = subs.size,
            embeddedSelectedIndex = subs.indexOfFirst { it.isSelected },
            addonSubUrls = addonSubs.map { it.url },
            chosenSubUrl = chosenSub?.url,
        )
        val out = mutableListOf(MenuItem(MenuTrack("sub:off", null, chosen == 0, off = true), getString(R.string.state_off), "", "") {
            pickSubtitle(0, subs, loaded)
        })
        subs.forEachIndexed { i, g ->
            val f = g.getTrackFormat(0)
            out += MenuItem(MenuTrack("sub:${1 + i}", Languages.code(f.language), chosen == 1 + i, plainTrack(f.selectionFlags, f.roleFlags, f.label)),
                trackName(f, i), roleWord(false, f), "") { pickSubtitle(1 + i, subs, loaded) }
        }
        addonSubs.forEachIndexed { i, sub ->
            out += MenuItem(MenuTrack("addon:${sub.url}", Languages.code(sub.lang), chosen == 1 + subs.size + i, addon = true),
                // an add-on's own language text never shows when it is no known language: "Track N" instead
                if (Languages.code(sub.lang) != null) sub.language else getString(R.string.track_number, trackNumber(subs.size + i)),
                "", "") { pickSubtitle(1 + subs.size + i, subs, loaded) }
        }
        return out
    }

    private fun pickAudio(group: Tracks.Group, code: String?) {
        audioPicked = true
        player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
            .setOverrideForType(TrackSelectionOverride(group.mediaTrackGroup, (0 until group.length).toList())).build()
        code?.let {
            wantAudio = it
            Settings.pickedInPlayer(this, metaId, audio = it)
            Settings.addRecentAudio(this, it)
        }
    }

    /** Alder's own pick, as the old panel made it: [i] 0 is Off, then the file's subtitles, then the add-ons' files (which reload the source). */
    private fun pickSubtitle(i: Int, subs: List<Tracks.Group>, loaded: Tracks.Group?) {
        val p = player.trackSelectionParameters.buildUpon()
        val sub = addonSubs.getOrNull(i - 1 - subs.size)
        val file = if (i == 0 || sub != null) null else subs.getOrNull(i - 1) ?: return
        val lang = if (i == 0) "" else sub?.lang ?: file?.getTrackFormat(0)?.language
        remember(lang, addonFile = sub != null,
            pick = when { i == 0 -> ""; sub != null -> "addon:" + sub.url; else -> "file:" + file?.getTrackFormat(0)?.id.orEmpty() })
        if (i != 0) Languages.code(lang)?.let { Settings.addRecentSubtitle(this, it) }
        player.trackSelectionParameters = when {
            i == 0 -> p.setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true).build()
            file != null -> p.setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false)
                .setOverrideForType(TrackSelectionOverride(file.mediaTrackGroup, 0)).build()
            sub?.url == chosenSub?.url && loaded != null -> p.setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false)
                .setOverrideForType(TrackSelectionOverride(loaded.mediaTrackGroup, 0)).build()
            else -> { chosenSub = sub; subDelay = 0L; textRenderer?.offsetUs = 0L; selectAddonSub = true; load(position(), play = player.playWhenReady); return }
        }
    }

    private fun rowStyle(row: MenuRow, focused: Boolean) {
        row.view.background = if (focused) rounded(0xFFFFFFFF.toInt(), 4) else null
        val ink = if (focused) 0xFF141414.toInt() else 0xFFC4C4C8.toInt()
        row.name.setTextColor(ink)
        row.detail.setTextColor(if (focused) ink else 0xB8C4C4C8.toInt())
        row.check.setColorFilter(if (focused) ink else 0xFFFFFFFF.toInt())
    }

    private fun makeRow(col: MenuColumn, key: String, item: MenuItem?, shownDetail: String = ""): MenuRow {
        val check = ImageView(this).apply { setImageResource(R.drawable.ic_check); visibility = View.INVISIBLE }
        val name = text(item?.name ?: getString(R.string.menu_more), BODY).apply { maxLines = 1; ellipsize = TextUtils.TruncateAt.END }
        val detail = text(shownDetail, SMALL).apply { maxLines = 1; ellipsize = TextUtils.TruncateAt.END; textAlignment = View.TEXT_ALIGNMENT_VIEW_START }
        val view = LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            isFocusable = true
            setPaddingRelative(0, 0, dp(12), 0)
            addView(check, LinearLayout.LayoutParams(dp(18), dp(18)).apply { marginStart = dp(8); marginEnd = dp(6) })
            addView(name, wrap())
            addView(detail, LinearLayout.LayoutParams(0, -2, 1f).apply { marginStart = dp(7) })
        }
        val row = MenuRow(key, item, view, check, name, detail)
        if (shownDetail.isEmpty()) detail.visibility = View.GONE
        if (detail.visibility == View.GONE) name.layoutParams = LinearLayout.LayoutParams(0, -2, 1f)
        rowStyle(row, false)
        view.setOnFocusChangeListener { _, has ->
            rowStyle(row, has)
            if (has && !menuBusy) col.rows.indexOf(row).takeIf { it >= 0 }?.let { scrollTo(col, it) }
        }
        view.setOnClickListener { rowPicked(col, row) }
        view.setOnKeyListener { _, code, ev -> rowKey(col, row, code, ev) }
        return row
    }

    private fun paintChecks(col: MenuColumn) = col.rows.forEach { it.check.visibility = if (it.key == col.checkedKey) View.VISIBLE else View.INVISIBLE }

    private fun checkedIndex(col: MenuColumn) = col.rows.indexOfFirst { it.key == col.checkedKey }.coerceAtLeast(0)

    private fun focusRow(col: MenuColumn, index: Int) {
        val row = col.rows.getOrNull(index) ?: return
        menuMoving = true
        row.view.requestFocus()
        menuMoving = false
    }

    /** Down off the last row: focus goes back to the button, the menu stays. */
    private fun focusAudioButton() {
        suppressOpen = true
        audioButton.requestFocus()
        suppressOpen = false
    }

    /** Lets the row at [index] show: the list scrolls by whole rows, once the rows pass [menuRows]. */
    private fun scrollTo(col: MenuColumn, index: Int) {
        val visible = minOf(col.rows.size, menuRows)
        var at = col.scroll
        if (index < at) at = index else if (index > at + visible - 1) at = index - visible + 1
        at = at.coerceIn(0, (col.rows.size - visible).coerceAtLeast(0))
        col.scroll = at
        col.inner.translationY = -(at * dp(36)).toFloat()
        val total = col.rows.size
        col.rail.visibility = if (total > menuRows) View.VISIBLE else View.GONE
        col.rail.layoutParams = (col.rail.layoutParams as FrameLayout.LayoutParams).apply { height = dp(36) * visible * visible / maxOf(total, 1) }
        col.rail.translationY = (dp(36) * at * visible / maxOf(total, 1)).toFloat()
    }

    /** The column's rows: the defaults and More, or every track once More is open. */
    private fun fillColumn(col: MenuColumn) {
        val shownIds = defaultTrackIds(col.items.map { it.track }, originalLanguage)
        val shown = if (col.expanded) col.items else col.items.filter { it.track.id in shownIds }
        col.inner.removeAllViews()
        // ChadFlix: a row shows its language (and a role word like "Commentary"); an audio track's codec and channels only under More
        val rows = shown.map { item -> makeRow(col, item.track.id, item, rowDetail(col.expanded, item.role, item.format)) }.toMutableList()
        if (!col.expanded && shown.size < col.items.size) rows += makeRow(col, if (col.audio) "more:audio" else "more:subs", null)
        rows.forEach { col.inner.addView(it.view, LinearLayout.LayoutParams(-1, dp(32)).apply { setMargins(dp(10), dp(2), dp(10), dp(2)) }) }
        col.rows = rows
        col.inner.layoutParams = (col.inner.layoutParams as FrameLayout.LayoutParams).apply { height = rows.size * dp(36) }
        col.list.layoutParams = (col.list.layoutParams as LinearLayout.LayoutParams).apply { height = minOf(rows.size, menuRows) * dp(36) }
        col.view.visibility = if (col.items.isEmpty()) View.GONE else View.VISIBLE
        val anyLong = rows.any { it.item != null && it.name.paint.measureText(it.name.text.toString()) > dp(NARROW_NAME_DP) }
        col.view.layoutParams = col.view.layoutParams?.apply { width = dp(languageColumnDp(col.expanded, shown.any { it.role.isNotEmpty() }, anyLong)) }
        paintChecks(col)
        scrollTo(col, col.scroll)
    }

    /** A column's title, at the text's own indent (past the check). */
    private fun columnHeader(title: Int) = FrameLayout(this).apply {
        addView(text(title, BODY, bold = true, color = 0xFFFFFFFF.toInt()),
            FrameLayout.LayoutParams(-2, -2, Gravity.CENTER_VERTICAL or Gravity.START).apply { marginStart = dp(42); topMargin = dp(4) })
    }

    private fun columnView(col: MenuColumn): LinearLayout {
        col.view = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val header = columnHeader(if (col.audio) R.string.tab_audio else R.string.tab_subtitles)
        col.view.addView(header, LinearLayout.LayoutParams(-1, dp(50)))
        col.list = FrameLayout(this)
        col.inner = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        col.rail = View(this).apply { background = rounded(0x59FFFFFF, 1); visibility = View.GONE }
        col.list.addView(col.inner, FrameLayout.LayoutParams(-1, 0))
        col.list.addView(col.rail, FrameLayout.LayoutParams(dp(2), 0, Gravity.TOP or Gravity.END).apply { marginEnd = dp(3) })
        col.view.addView(col.list, LinearLayout.LayoutParams(-1, 0))
        return col.view
    }

    private fun openMenu() {
        if (menuOpen || isTrailer || !shown) return
        menuOpen = true
        main.removeCallbacks(hideTask)
        hideInfo(restartHide = false)
        menuLayer.animate().cancel()
        menuLayer.removeAllViews()
        menuLayer.alpha = 0f
        menuLayer.visibility = View.VISIBLE
        // ChadFlix (owner): no glass and no blur; what is playing is dimmed, black at 0.6, as Alder's menus dim the page.
        menuLayer.addView(View(this).apply { setBackgroundColor(0x99000000.toInt()) }, FrameLayout.LayoutParams(-1, -1))
        menuRows = ((resources.displayMetrics.heightPixels - dp(150) - dp(120)) / dp(36)).coerceIn(3, 7)
        val panel = LinearLayout(this).apply { setPaddingRelative(dp(8), dp(2), dp(8), dp(8)) }
        panel.addView(columnView(audioColumn), LinearLayout.LayoutParams(dp(languageColumnDp(false, false)), -2))
        panel.addView(columnView(subsColumn), LinearLayout.LayoutParams(dp(languageColumnDp(false, false)), -2))
        panel.addView(appearanceColumn(), LinearLayout.LayoutParams(dp(appearanceWidth()), -2))
        menuPanel = panel
        // a taller or shorter menu (More, a refresh) moves the sample so it never overlaps it
        panel.addOnLayoutChangeListener { _, _, top, _, _, _, oldTop, _, _ -> if (top != oldTop) placeSample() }
        sample = null
        menuLayer.addView(panel, FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM or Gravity.START).apply {
            marginStart = dp(PAD); bottomMargin = dp(150)
        })
        audioColumn.expanded = false; subsColumn.expanded = false
        audioColumn.scroll = 0; subsColumn.scroll = 0
        menuBusy = true
        audioColumn.items = audioItems(); subsColumn.items = subtitleItems()
        audioColumn.checkedKey = audioColumn.items.firstOrNull { it.track.selected }?.track?.id
        subsColumn.checkedKey = subsColumn.items.firstOrNull { it.track.selected }?.track?.id
        fillColumn(audioColumn); fillColumn(subsColumn)
        menuBusy = false
        menuLayer.enter().alpha(1f)
        dimmed.forEach { it.animate().alpha(0.45f).setDuration(FOCUS_MS) }
        pauseInfo.visibility = View.INVISIBLE
        askOriginalLanguage()
    }

    /** The language the title was made in, asked once for this title and never waited for: until it is known the menu shows English and the track in use. */
    private fun askOriginalLanguage() {
        if (askedOriginal || !metaId.startsWith("tt")) return
        askedOriginal = true
        background({ Tmdb.originalLanguage(metaId, type) }) { r ->
            originalLanguage = r.getOrNull()
            if (originalLanguage != null) refreshMenu()
        }
    }

    /** New tracks, a pick or the title's original language: the open menu follows, and focus stays on its row. */
    private fun refreshMenu() {
        if (!menuOpen) return
        menuBusy = true
        try {
            val focusedKey = (audioColumn.rows + subsColumn.rows).firstOrNull { it.view.isFocused }?.let { it.key to (it in audioColumn.rows) }
            for (col in listOf(audioColumn, subsColumn)) {
                col.items = if (col.audio) audioItems() else subtitleItems()
                col.checkedKey = col.items.firstOrNull { it.track.selected }?.track?.id
                val shownIds = defaultTrackIds(col.items.map { it.track }, originalLanguage)
                val keys = (if (col.expanded) col.items else col.items.filter { it.track.id in shownIds }).map { it.track.id } +
                    (if (!col.expanded && col.items.any { it.track.id !in shownIds }) listOf(if (col.audio) "more:audio" else "more:subs") else emptyList())
                if (keys == col.rows.map { it.key }) paintChecks(col) else fillColumn(col)
            }
            if (focusedKey != null) {
                val col = if (focusedKey.second) audioColumn else subsColumn
                val target = col.rows.firstOrNull { it.key == focusedKey.first } ?: col.rows.getOrNull(checkedIndex(col))
                menuMoving = true
                target?.view?.requestFocus()
                menuMoving = false
                col.rows.indexOf(target).takeIf { it >= 0 }?.let { scrollTo(col, it) }
            }
        } finally { menuBusy = false }
    }

    private fun rowKey(col: MenuColumn, row: MenuRow, code: Int, ev: KeyEvent): Boolean {
        val towardsSubs = if (isRtl()) KeyEvent.KEYCODE_DPAD_LEFT else KeyEvent.KEYCODE_DPAD_RIGHT
        val towardsAudio = if (isRtl()) KeyEvent.KEYCODE_DPAD_RIGHT else KeyEvent.KEYCODE_DPAD_LEFT
        if (code != KeyEvent.KEYCODE_DPAD_UP && code != KeyEvent.KEYCODE_DPAD_DOWN && code != towardsSubs && code != towardsAudio) return false
        if (ev.action != KeyEvent.ACTION_DOWN) return true
        val index = col.rows.indexOf(row)
        when (code) {
            KeyEvent.KEYCODE_DPAD_UP -> if (index > 0) focusRow(col, index - 1)
            KeyEvent.KEYCODE_DPAD_DOWN -> if (index < col.rows.lastIndex) focusRow(col, index + 1) else focusAudioButton()
            towardsSubs -> if (col.audio) { if (subsColumn.rows.isNotEmpty()) focusRow(subsColumn, checkedIndex(subsColumn)) } else { focusAppearance(0); foldMore() }
            else -> if (!col.audio && audioColumn.rows.isNotEmpty()) focusRow(audioColumn, checkedIndex(audioColumn))
        }
        return true
    }

    private fun rowPicked(col: MenuColumn, row: MenuRow) {
        // The row's own item can be stale after a reload (new track groups, a shorter add-on list): use the current one.
        val item = if (row.item == null) null else col.items.firstOrNull { it.track.id == row.key } ?: return
        if (item == null) { // More: every track of this column, from the first that was hidden
            val shownIds = defaultTrackIds(col.items.map { it.track }, originalLanguage)
            val first = col.items.firstOrNull { it.track.id !in shownIds }
            col.expanded = true
            menuBusy = true
            fillColumn(col)
            menuBusy = false
            focusRow(col, col.rows.indexOfFirst { it.key == first?.track?.id }.coerceAtLeast(0))
            return
        }
        col.checkedKey = row.key
        paintChecks(col)
        item.pick()
    }

    /** OK or Up on the button: the menu opens if it is shut, and focus goes in, to the subtitle in use. */
    private fun enterMenu() {
        if (!shown || isTrailer) return
        if (!menuOpen) openMenu()
        val col = if (subsColumn.rows.isNotEmpty()) subsColumn else audioColumn
        focusRow(col, checkedIndex(col))
    }

    /** Back in the menu: Appearance gives focus back to Subtitles; a column that is showing More folds; then the menu closes. */
    private fun backInMenu() {
        if (appearanceRows.any { it.view.isFocused }) { // Back from Appearance: on to the subtitle in use
            focusRow(subsColumn, checkedIndex(subsColumn))
            return
        }
        val col = listOf(audioColumn, subsColumn).firstOrNull { c -> c.rows.any { it.view.isFocused } }
        if (col != null && col.expanded) {
            col.expanded = false
            col.scroll = 0
            menuBusy = true
            fillColumn(col)
            menuBusy = false
            focusRow(col, checkedIndex(col))
            return
        }
        closeMenu(focusButton = true)
    }

    /** [focusButton]: Back puts focus on the button, and the menu stays shut until focus lands there again. */
    private fun closeMenu(focusButton: Boolean = false, restartHide: Boolean = true) {
        if (!menuOpen) return
        menuOpen = false
        main.removeCallbacks(dwellMenu)
        hideSample()
        if (focusButton) focusAudioButton()
        dimmed.forEach { it.animate().alpha(1f).setDuration(FOCUS_MS) }
        if (!infoOpen) pauseInfo.visibility = View.VISIBLE
        menuLayer.exit().alpha(0f).withEndAction {
            if (!menuOpen) { menuLayer.removeAllViews(); menuLayer.visibility = View.GONE }
        }
        if (restartHide && shown) showControls(null)
    }

    // ----- Appearance -----
    // The third column: Size, Color and Background, each "‹ value ›". Left and Right (or OK) step the value, wrapping, and it
    // applies at once, to the real subtitles too. While a row has focus the sample line shows above the menu.

    private enum class Look { SIZE, COLOR, BACKGROUND }
    private class AppearanceRow(val look: Look, val view: LinearLayout, val label: TextView, val value: TextView,
                                val before: ImageView, val after: ImageView, val dot: View?, val names: List<String>)
    private val appearanceRows = mutableListOf<AppearanceRow>()

    private fun subtitleStyle() = CaptionStyleCompat(Settings.subtitleColor(this),
        if (Settings.subtitleBackground(this)) 0xB3000000.toInt() else Color.TRANSPARENT, Color.TRANSPARENT,
        CaptionStyleCompat.EDGE_TYPE_DROP_SHADOW, Color.BLACK, null)

    private fun applySubtitleStyle() {
        view.subtitleView?.setStyle(subtitleStyle())
        sample?.setStyle(subtitleStyle())
    }

    private fun applySubtitleSize() {
        val fraction = SubtitleView.DEFAULT_TEXT_SIZE_FRACTION * Settings.subtitleSize(this) / 100f
        view.subtitleView?.setFractionalTextSize(fraction)
        sample?.setFractionalTextSize(fraction)
    }

    private fun lookNames(look: Look) = when (look) {
        Look.SIZE -> listOf(R.string.size_small, R.string.size_medium, R.string.size_large, R.string.size_extra_large)
        Look.COLOR -> listOf(R.string.color_white, R.string.color_gray, R.string.color_yellow, R.string.color_cyan, R.string.color_cream, R.string.color_warm_white)
        Look.BACKGROUND -> listOf(R.string.state_on, R.string.state_off)
    }.map { getString(it) }

    private fun lookIndex(look: Look) = when (look) {
        Look.SIZE -> choiceIndex(SUBTITLE_SIZES, Settings.subtitleSize(this))
        Look.COLOR -> choiceIndex(SUBTITLE_COLORS, Settings.subtitleColorName(this))
        Look.BACKGROUND -> choiceIndex(SUBTITLE_BACKGROUNDS, Settings.subtitleBackground(this))
    }

    /** Alder's own setters, as the old Appearance screen called them. */
    private fun applyLook(look: Look, index: Int) {
        when (look) {
            Look.SIZE -> { Settings.setSubtitleSize(this, SUBTITLE_SIZES[index]); applySubtitleSize() }
            Look.COLOR -> { Settings.setSubtitleColor(this, SUBTITLE_COLORS[index]); applySubtitleStyle() }
            Look.BACKGROUND -> { Settings.setSubtitleBackground(this, SUBTITLE_BACKGROUNDS[index]); applySubtitleStyle() }
        }
    }

    private fun stepLook(row: AppearanceRow, delta: Int) {
        applyLook(row.look, steppedIndex(lookIndex(row.look), delta, row.names.size))
        paintAppearance(row)
        placeSample()
    }

    private fun paintAppearance(row: AppearanceRow) {
        row.value.text = row.names[lookIndex(row.look)]
        row.dot?.background = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(subtitleColorValue(Settings.subtitleColorName(this@PlayerActivity)))
            setStroke(dp(1), 0x59FFFFFF)
        }
    }

    private fun appearanceStyle(row: AppearanceRow, focused: Boolean) {
        row.view.background = if (focused) rounded(0xFFFFFFFF.toInt(), 4) else null
        val ink = if (focused) 0xFF141414.toInt() else 0xFFC4C4C8.toInt()
        row.label.setTextColor(ink)
        row.value.setTextColor(ink)
        row.value.alpha = if (focused) 0.85f else 0.78f
        listOf(row.before, row.after).forEach { it.setColorFilter(ink); it.alpha = if (focused) 1f else 0.4f }
    }

    private fun appearanceRow(look: Look, label: Int): AppearanceRow {
        val names = lookNames(look)
        fun chevron() = ImageView(this).apply { setImageResource(R.drawable.ic_chevron_end) }
        val before = chevron().apply { rotation = 180f } // points to the previous choice: left, or right in a right-to-left layout
        val after = chevron()
        val value = text("", SMALL).apply {
            gravity = Gravity.CENTER; maxLines = 1
            minWidth = names.maxOf { paint.measureText(it) }.toInt() + dp(2)
        }
        val dot = if (look == Look.COLOR) View(this) else null
        val name = text(label, BODY).apply { maxLines = 1; ellipsize = TextUtils.TruncateAt.END }
        val view = LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            isFocusable = true
            setPaddingRelative(dp(32), 0, dp(12), 0) // the label starts where the other columns' names do
            addView(name, LinearLayout.LayoutParams(0, -2, 1f))
            addView(before, LinearLayout.LayoutParams(dp(APPEARANCE_CHEVRON), dp(APPEARANCE_CHEVRON)).apply { marginEnd = dp(2) })
            dot?.let { addView(it, LinearLayout.LayoutParams(dp(APPEARANCE_DOT), dp(APPEARANCE_DOT)).apply { marginEnd = dp(6) }) }
            addView(value, wrap())
            addView(after, LinearLayout.LayoutParams(dp(APPEARANCE_CHEVRON), dp(APPEARANCE_CHEVRON)).apply { marginStart = dp(2) })
        }
        val row = AppearanceRow(look, view, name, value, before, after, dot, names)
        appearanceStyle(row, false)
        paintAppearance(row)
        view.setOnFocusChangeListener { _, has ->
            appearanceStyle(row, has)
            if (has) showSample() else view.post { if (appearanceRows.none { it.view.isFocused }) hideSample() }
        }
        view.setOnClickListener { stepLook(row, 1) }
        view.setOnKeyListener { _, code, ev -> appearanceKey(row, code, ev) }
        return row
    }

    /** The widest label and the widest "‹ value ›" of the three rows, in dp, from the real strings of this language. */
    private fun appearanceWidth(): Int {
        val d = resources.displayMetrics.density
        val probe = text("", BODY)
        val small = text("", SMALL)
        val labels = listOf(R.string.subtitle_size, R.string.color, R.string.appearance_background)
        val widestLabel = labels.maxOf { probe.paint.measureText(getString(it)) } / d
        val widestValue = Look.values().maxOf { look ->
            lookNames(look).maxOf { small.paint.measureText(it) } / d + 2 * (APPEARANCE_CHEVRON + 2) + (if (look == Look.COLOR) APPEARANCE_DOT + 6 else 0) + 2
        }
        return appearanceColumnDp(Math.ceil(widestLabel.toDouble()).toInt(), Math.ceil(widestValue.toDouble()).toInt())
    }

    private fun appearanceColumn(): LinearLayout {
        appearanceRows.clear()
        val column = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        column.addView(columnHeader(R.string.menu_appearance), LinearLayout.LayoutParams(-1, dp(50)))
        val list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        listOf(Look.SIZE to R.string.subtitle_size, Look.COLOR to R.string.color, Look.BACKGROUND to R.string.appearance_background).forEach { (look, label) ->
            val row = appearanceRow(look, label)
            appearanceRows += row
            list.addView(row.view, LinearLayout.LayoutParams(-1, dp(32)).apply { setMargins(dp(10), dp(2), dp(10), dp(2)) })
        }
        column.addView(list, LinearLayout.LayoutParams(-1, appearanceRows.size * dp(36)))
        return column
    }

    /**
     * More folds when focus goes to Appearance: a long list makes the menu tall, and the sample must fit above it. Folded, the menu has at
     * most five rows (Off, the track in use, English, the original language, More), so the sample always fits and never leaves the screen.
     */
    private fun foldMore() {
        for (col in listOf(audioColumn, subsColumn)) {
            if (!col.expanded) continue
            col.expanded = false
            col.scroll = 0
            menuBusy = true
            fillColumn(col)
            menuBusy = false
        }
    }

    private fun focusAppearance(index: Int) {
        val row = appearanceRows.getOrNull(index) ?: return
        menuMoving = true
        row.view.requestFocus()
        menuMoving = false
    }

    /** Up and Down move between the rows (Up on Size goes back to the subtitle in use), Left and Right (and OK) step the value; Left never leaves the column. */
    private fun appearanceKey(row: AppearanceRow, code: Int, ev: KeyEvent): Boolean {
        if (code != KeyEvent.KEYCODE_DPAD_UP && code != KeyEvent.KEYCODE_DPAD_DOWN &&
            code != KeyEvent.KEYCODE_DPAD_LEFT && code != KeyEvent.KEYCODE_DPAD_RIGHT) return false
        if (ev.action != KeyEvent.ACTION_DOWN) return true
        // a key held down to arrive here (Right from Subtitles) must not go on to step the value
        if (ev.repeatCount > 0 && (code == KeyEvent.KEYCODE_DPAD_LEFT || code == KeyEvent.KEYCODE_DPAD_RIGHT)) return true
        val index = appearanceRows.indexOf(row)
        when (code) {
            KeyEvent.KEYCODE_DPAD_UP -> if (index > 0) focusAppearance(index - 1) else focusRow(subsColumn, checkedIndex(subsColumn))
            KeyEvent.KEYCODE_DPAD_DOWN -> if (index < appearanceRows.lastIndex) focusAppearance(index + 1) else focusAudioButton()
            else -> stepLook(row, stepForKey(code == KeyEvent.KEYCODE_DPAD_RIGHT, isRtl()))
        }
        return true
    }

    /** The sample line, drawn as Alder draws subtitles, centred above the menu. The real subtitles hide while it shows, as they did on the old screen. */
    private fun showSample() {
        val line = sample ?: SubtitleView(this).also {
            sample = it
            it.setCues(listOf(androidx.media3.common.text.Cue.Builder().setText(getString(R.string.appearance_sample)).build()))
            menuLayer.addView(it, 1, FrameLayout.LayoutParams(-1, -1)) // above the dim, under the menu
        }
        applySubtitleStyle(); applySubtitleSize()
        line.visibility = View.VISIBLE
        view.subtitleView?.visibility = View.INVISIBLE
        placeSample()
    }

    private fun placeSample() {
        val line = sample ?: return
        val screen = menuLayer.height
        if (screen <= 0) return
        val bottom = sampleBottomPx(screen, menuPanel?.top ?: screen, dp(24))
        line.setBottomPaddingFraction((screen - bottom).toFloat() / screen)
    }

    /** Focus left Appearance or the menu closed: the sample goes and the real subtitles come back. */
    private fun hideSample() {
        sample?.let { menuLayer.removeView(it) }
        sample = null
        view.subtitleView?.visibility = View.VISIBLE
    }

    /** Whatever panel is open goes away (an error, up next or still watching takes the screen). */
    private fun closePanels() {
        closeMenu(restartHide = false)
        hideInfo(restartHide = false)
    }

    // ----- Info -----

    private fun loadInfoLogo(url: String?) = Images.load(url, playerLogo, Images.TITLE_LOGO_PX, urgent = true,
        look = Images.trimmedLogo, failed = {
            playerLogo.visibility = View.GONE; topName.visibility = View.VISIBLE
        }) {
        playerLogo.visibility = View.VISIBLE; topName.visibility = View.GONE
    }

    /** The pop-up: logo, facts, plot, ratings and the technical chips, from what the player already has. Nothing is asked for. */
    private fun showInfo() {
        if (isTrailer || !shown) return
        val refresh = infoOpen
        infoOpen = true
        main.removeCallbacks(hideTask)
        pauseInfo.visibility = View.INVISIBLE
        val m = meta
        val logo = m?.logo ?: intent.getStringExtra("logo") ?: Images.logoFor(metaId)
        if (logo != infoLogoUrl) { infoLogoUrl = logo; loadInfoLogo(logo) }
        topName.text = m?.name?.takeIf { it.isNotBlank() } ?: intent.getStringExtra("name").orEmpty()

        if (!refresh) infoLayer.animate().cancel()
        infoLayer.removeAllViews()
        val stack = column(Gravity.START)
        val box = FrameLayout(this).apply { clipChildren = false }
        (playerLogo.parent as? ViewGroup)?.removeView(playerLogo)
        (topName.parent as? ViewGroup)?.removeView(topName)
        box.addView(playerLogo, FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM or Gravity.START))
        box.addView(topName, FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM or Gravity.START))
        stack.addView(box, LinearLayout.LayoutParams(dp(560), -2))
        val soft = 0xB3FFFFFF.toInt()
        val certificate = m?.certification ?: knownCertificate(metaId)
        val length = HomeStyle.runtimeMs(m?.runtime ?: intent.getStringExtra("runtime")).takeIf { it > 0 }
            ?: player.duration.takeIf { it > 0 && !isLive() } ?: 0L
        val facts = infoFacts(m?.year?.take(4), certificate, runtimeWords(length, getString(R.string.info_runtime_hours_minutes),
            getString(R.string.info_runtime_hours), getString(R.string.info_runtime_minutes)))
        // Each row sits infoGap() under the row above it that is showing, so a missing row leaves no gap.
        var above = InfoRow.LOGO
        fun place(row: InfoRow, view: View, width: Int = -2) {
            stack.addView(view, LinearLayout.LayoutParams(width, -2).apply { topMargin = dp(infoGap(above, row)) })
            above = row
        }
        if (facts.isNotEmpty()) place(InfoRow.FACTS, text(facts, BODY, color = soft).apply { maxLines = 1 })
        m?.description?.takeIf { it.isNotBlank() }?.let { plot ->
            place(InfoRow.PLOT, text(plot, BODY, color = soft).apply { maxLines = 2; ellipsize = TextUtils.TruncateAt.END }, dp(560))
        }
        val scores = knownRatings(metaId).orEmpty()
        val ratings = shownRatings("both", m?.rating, scores["letterboxd"], scores["tomatoes"])
        if (ratings.isNotEmpty()) {
            val row = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL; minimumHeight = dp(INFO_META_H) }
            addRatingLogos(row, ratings)
            place(InfoRow.RATINGS, row)
        }
        if (shownBadges.isNotEmpty()) {
            val chips = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
            shownBadges.forEachIndexed { i, b -> // Alder's own badges, Dolby and DTS marks included (owner)
                chips.addView(badgeView(b), LinearLayout.LayoutParams(-2, dp(INFO_META_H)).apply { if (i > 0) marginStart = dp(INFO_BADGE_GAP) })
            }
            place(InfoRow.BADGES, chips)
        }
        infoLayer.addView(stack, FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM or Gravity.START).apply {
            marginStart = dp(PAD); bottomMargin = dp(INFO_BOTTOM)
        })
        if (refresh) return
        infoLayer.alpha = 0f
        infoLayer.visibility = View.VISIBLE
        infoLayer.enter().alpha(1f)
        // Only the picture dims, 0.72 black, under the controls.
        val shade = infoShade ?: View(this).apply { setBackgroundColor(0xB8000000.toInt()); alpha = 0f }.also {
            infoShade = it
            screen.addView(it, screen.indexOfChild(controls), FrameLayout.LayoutParams(-1, -1))
        }
        shade.animate().cancel()
        shade.visibility = View.VISIBLE
        shade.enter().alpha(1f)
    }

    private fun hideInfo(restartHide: Boolean = true) {
        if (!infoOpen) return
        infoOpen = false
        main.removeCallbacks(dwellInfo)
        if (!menuOpen) pauseInfo.visibility = View.VISIBLE
        infoLayer.exit().alpha(0f).withEndAction {
            if (!infoOpen) { infoLayer.removeAllViews(); infoLayer.visibility = View.GONE }
        }
        infoShade?.let { shade -> shade.exit().alpha(0f).withEndAction { if (!infoOpen) shade.visibility = View.GONE } }
        if (restartHide && shown) showControls(null)
    }

    /** What's playing, in a few words: "English  ·  Spanish subtitles", or "English  ·  Subtitles off". */
    private fun trackStatus(tracks: Tracks): String {
        fun name(language: String?) = Languages.code(language)?.let(Languages::name)
        val audio = tracks.groups.firstOrNull { it.type == C.TRACK_TYPE_AUDIO && it.isSelected }?.getTrackFormat(0)?.language
        val text = tracks.groups.firstOrNull { it.type == C.TRACK_TYPE_TEXT && it.isSelected }
        val subtitles = when {
            text == null || C.TRACK_TYPE_TEXT in player.trackSelectionParameters.disabledTrackTypes -> getString(R.string.subtitles_off)
            else -> name(if (isAddonSub(text)) chosenSub?.lang else text.getTrackFormat(0).language)
                ?.let { getString(R.string.subtitles_in, it) } ?: getString(R.string.subtitles_on)
        }
        return listOfNotNull(name(audio), subtitles).joinToString(DOT)
    }

    /** Sets subtitle delay in milliseconds live on the renderer without reloading playback. */
    private fun setSubtitleDelay(newDelay: Long) {
        val oldDelay = subDelay
        subDelay = newDelay
        textRenderer?.offsetUs = subDelay * 1000L
        // A seek to the same second drops cues already queued for the old timing. Only where the file can seek: ExoPlayer
        // takes any seek in a file it can't seek as a seek to 0:00, so Right (subtitle later) restarted the film, which
        // the 1.2 fix (canSeek) had stopped for scrubbing.
        if (subDelay > oldDelay && player.isCurrentMediaItemSeekable) {
            player.seekTo(player.currentPosition)
        }
    }

    private fun isAddonSub(g: Tracks.Group) = g.type == C.TRACK_TYPE_TEXT && g.getTrackFormat(0).id?.contains(ADDON_SUB) == true

    /** A track's name: its language, or "Track N" ([position] is its place in its column). The file's own label is never used: uploaders put release group names there. */
    private fun trackName(f: Format, position: Int): String =
        Languages.code(f.language)?.let(Languages::name)
            ?: getString(R.string.track_number, trackNumber(position))

    /** The short word for a commentary, description, forced or SDH track, from the track's flags; "" for the rest. */
    private fun roleWord(audio: Boolean, f: Format): String = when (trackRole(audio, f.selectionFlags, f.roleFlags, f.label)) {
        TrackRole.COMMENTARY -> getString(R.string.role_commentary)
        TrackRole.DESCRIPTIVE -> getString(R.string.role_descriptive)
        TrackRole.FORCED -> getString(R.string.role_forced)
        TrackRole.SDH -> getString(R.string.role_sdh)
        null -> ""
    }

    /** An audio track's codec and channels, "Dolby Digital 5.1", or "". See [audioFormatText]. */
    private fun audioFormat(f: Format): String = audioFormatText(when (f.sampleMimeType) {
        MimeTypes.AUDIO_AC3 -> "Dolby Digital"
        MimeTypes.AUDIO_E_AC3 -> "Dolby Digital Plus"
        MimeTypes.AUDIO_E_AC3_JOC -> "Dolby Atmos"
        MimeTypes.AUDIO_TRUEHD -> "Dolby TrueHD"
        MimeTypes.AUDIO_DTS -> "DTS"
        MimeTypes.AUDIO_DTS_HD -> "DTS-HD"
        MimeTypes.AUDIO_DTS_X -> "DTS:X"
        MimeTypes.AUDIO_DTS_EXPRESS -> "DTS Express"
        MimeTypes.AUDIO_AAC -> "AAC"
        MimeTypes.AUDIO_OPUS -> "Opus"
        MimeTypes.AUDIO_FLAC -> "FLAC"
        else -> null
    }, f.sampleMimeType, f.label, when (f.channelCount) {
        1 -> getString(R.string.audio_mono); 2 -> getString(R.string.audio_stereo); 6 -> "5.1"; 8 -> "7.1"
        Format.NO_VALUE -> null; else -> getString(R.string.audio_channels, f.channelCount)
    })

    private fun timing(delay: Long) = formatTiming(delay, getString(R.string.timing_on_time),
        getString(R.string.timing_earlier), getString(R.string.timing_later))

    /**
     * ChadFlix: always Fit now (the owner removed Picture). Kept as Alder wrote it, for easy merges: under Zoom the
     * subtitles would move onto the screen itself; Fit keeps them inside the video's frame.
     */
    private fun applyPictureFit() {
        val fit = "fit" // ChadFlix: Picture is gone from the player (owner), so a Zoom or Stretch saved before can't stick
        view.resizeMode = resizeModeFor(fit)
        val subs = view.subtitleView ?: return
        val frame = view.findViewById<ViewGroup>(androidx.media3.ui.R.id.exo_content_frame) ?: return
        val home: ViewGroup = if (fit == "zoom") view else frame
        if (subs.parent === home) return
        (subs.parent as? ViewGroup)?.removeView(subs)
        val at = if (home === view) view.indexOfChild(frame) + 1
            else frame.indexOfChild(frame.findViewById(androidx.media3.ui.R.id.exo_buffering)).takeIf { it >= 0 } ?: frame.childCount
        home.addView(subs, at, FrameLayout.LayoutParams(-1, -1))
    }

    override fun onPause() {
        paused = true
        if (::status.isInitialized) {
            main.removeCallbacks(tickTask)
            main.removeCallbacks(progressTask)
            main.removeCallbacks(skipTask)
            main.removeCallbacks(loadingTask)
            main.removeCallbacks(startTimeout)
            main.removeCallbacks(seekTask)
            main.removeCallbacks(dwellMenu)
            main.removeCallbacks(dwellInfo)
            checkpoint.captureResumeOnPause(player.playWhenReady, isFinishing)
            player.pause()
            val durationMs = player.duration.takeIf { it > 0 } ?: item?.optJSONObject("state")?.optLong("duration") ?: 0L
            val canCommit = !isTrailer && watched && !player.isCurrentMediaItemLive && !isLiveType(type) &&
                !(episodic && !plausibleEpisode(durationMs))
            checkpoint.commitOnPause(canCommit, ::commitPauseCheckpoint)
        } else {
            checkpoint.captureResumeOnPause(playWhenReady = false, finishing = isFinishing)
        }
        super.onPause()
    }

    override fun onStop() {
        super.onStop()
        stopped = true
        if (!::status.isInitialized) return
        main.removeCallbacks(tickTask)
        main.removeCallbacks(progressTask)
        main.removeCallbacks(skipTask)
        main.removeCallbacks(loadingTask)
        // A paused source in the background shows no picture: timing it out would skip a good source.
        main.removeCallbacks(startTimeout)
        main.removeCallbacks(seekTask)
        player.pause()
    }

    private fun closeInFlightOpened(markDestroyed: Boolean = false) {
        val leftover = synchronized(openLock) {
            if (markDestroyed) destroyed = true
            inFlightOpened.toList().also { inFlightOpened.clear() }
        }
        leftover.forEach { it.close() }
    }

    override fun onDestroy() {
        resolvingStreams?.cancel()
        resolvingStreams = null
        closeInFlightOpened(markDestroyed = true)
        main.removeCallbacks(startTimeout)
        opened?.close()
        opened = null
        main.removeCallbacks(loadingTask)
        if (::status.isInitialized) player.release()
        super.onDestroy()
    }

    private companion object {
        const val SOURCES_REQUEST = 42
        const val TICK = 10_000L
        const val RESUME_SLACK = 10_000L // how far apart the saved and fresh resume points may be before it jumps
        const val SKIP = 10_000L // ms a Left/Right press on the bar moves
        const val SEEK_SETTLE = 600L // ms after the last skip before seeking
        const val SKIP_SHOWN = 10_000L // ms the Skip intro pill stays, as in NuvioTV
        const val HIDE = 5_000L // ms the controls stay up while playing
        const val START_WAIT = 12_000L // ms for a source to show a picture before the next is tried (4K DV from debrid took 6 s)
        const val ADDON_SUB = "addon-subtitle" // the id an addon subtitle file is attached under
        const val SUB_WAIT = 2_000L // ms the start waits for the addon's subtitles
        const val APPEARANCE_CHEVRON = 16 // dp, the arrows beside an Appearance value
        const val APPEARANCE_DOT = 11 // dp, the colour dot before the Color value
        const val DWELL = 180L // ms on Audio & Subtitles or Info before its panel opens, so passing over does not flash it
        const val RETRIES = 2
        const val RETRY_WAIT = 1_500L
    }
}

/** Tungsten playback progress over a dark track, with a warm-white knob on focus. */
private class SeekBar(context: Context) : View(context) {
    private var position = 0L
    private var buffered = 0L
    private var duration = 0L
    private var markers = emptyList<Marker>()
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val d = context.resources.displayMetrics.density

    var isIndeterminate = false
        set(value) {
            if (field == value) return
            field = value
            if (value) startPulse() else stopPulse()
            invalidate()
        }
    private var pulseFraction = 0.5f
    private var pulseAnimator: android.animation.ValueAnimator? = null

    init { setLayerType(LAYER_TYPE_SOFTWARE, null) }

    private fun startPulse() {
        if (pulseAnimator?.isRunning == true) return
        pulseAnimator = android.animation.ValueAnimator.ofFloat(0.15f, 0.85f).apply {
            duration = 1100
            repeatMode = android.animation.ValueAnimator.REVERSE
            repeatCount = android.animation.ValueAnimator.INFINITE
            addUpdateListener {
                pulseFraction = it.animatedValue as Float
                invalidate()
            }
            start()
        }
    }

    private fun stopPulse() {
        pulseAnimator?.cancel()
        pulseAnimator = null
        pulseFraction = 0.5f
    }

    override fun onDetachedFromWindow() {
        stopPulse()
        super.onDetachedFromWindow()
    }

    fun set(position: Long, buffered: Long, duration: Long) {
        this.position = position
        this.buffered = buffered
        this.duration = duration
        invalidate()
    }

    fun setMarkers(markers: List<Marker>) {
        this.markers = markers
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        val knob = (if (isFocused) 9 else 6) * d
        val line = (if (isFocused) 4 else 3) * d
        val y = height / 2f
        fun x(t: Long) = knob + (width - 2 * knob) * (if (duration > 0) t.coerceIn(0, duration) / duration.toFloat() else 0f)
        fun stretch(to: Float, color: Int) {
            paint.color = color
            canvas.drawRoundRect(knob, y - line / 2, to, y + line / 2, line / 2, line / 2, paint)
        }
        if (isIndeterminate) {
            stretch(width - knob, 0x33F3EEE6)
            val trackW = width - 2 * knob
            val centerX = knob + trackW * pulseFraction
            val halfSpan = trackW * 0.22f
            paint.color = Screen.TUNGSTEN
            paint.setShadowLayer(6 * d, 0f, 0f, Screen.TUNGSTEN_GLOW)
            canvas.drawRoundRect((centerX - halfSpan).coerceAtLeast(knob), y - line / 2,
                (centerX + halfSpan).coerceAtMost(width - knob), y + line / 2, line / 2, line / 2, paint)
            paint.clearShadowLayer()
            return
        }
        // A light track: the dark one vanished over a dark picture.
        stretch(width - knob, 0x40F3EEE6)

        // Tint spans for Intro / Recap if present
        if (duration > 0) {
            paint.color = 0x33F3D9B1.toInt()
            for (m in markers) {
                if (m.endMs != null && m.endMs > m.timeMs) {
                    val x1 = x(m.timeMs)
                    val x2 = x(m.endMs)
                    if (x2 > x1) canvas.drawRect(x1, y - line / 2, x2, y + line / 2, paint)
                }
            }
        }

        stretch(x(buffered), 0x73F3EEE6)
        paint.setShadowLayer(4 * d, 0f, 0f, Screen.TUNGSTEN_GLOW)
        stretch(x(position), Screen.TUNGSTEN)
        paint.clearShadowLayer()

        // Marker ticks along the bar
        if (duration > 0 && markers.size > 1) {
            val gapHalf = 1.5f * d
            val tickHalf = 0.8f * d
            val tickExtra = if (isFocused) 2.5f * d else 1f * d
            for (m in markers) {
                if (m.timeMs > 0 && m.timeMs < duration) {
                    val mx = x(m.timeMs)
                    paint.color = 0xFF0E0C0A.toInt()
                    canvas.drawRect(mx - gapHalf, y - line / 2 - 1f, mx + gapHalf, y + line / 2 + 1f, paint)
                    paint.color = if (m.type != MarkerType.CHAPTER) Screen.TUNGSTEN else 0xCCF3EEE6.toInt()
                    canvas.drawRect(mx - tickHalf, y - line / 2 - tickExtra, mx + tickHalf, y + line / 2 + tickExtra, paint)
                }
            }
        }

        paint.color = Screen.TEXT
        if (duration > 0) canvas.drawCircle(x(position), y, knob, paint)
    }
}

/** Media3's resize mode for Settings' picture fit: Zoom fills the screen and crops, Stretch fills it and changes the shape. */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
internal fun resizeModeFor(fit: String) = when (fit) {
    "zoom" -> androidx.media3.ui.AspectRatioFrameLayout.RESIZE_MODE_ZOOM
    "stretch" -> androidx.media3.ui.AspectRatioFrameLayout.RESIZE_MODE_FILL
    else -> androidx.media3.ui.AspectRatioFrameLayout.RESIZE_MODE_FIT
}

/**
 * The buffer's size limit for the memory Android gives Alder ([maxHeap], bytes), or null for Media3's own (about 131 MB:
 * 10 to 17 s of a 4K remux). With the large heap (512 MB on most TV boxes) the buffer can use up to 200 MB, and
 * 250 MB stays free for decoders, pictures and screens. As NuvioTV does it.
 */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
internal fun bufferBytesFor(maxHeap: Long): Int? {
    val mb = 1024L * 1024
    return (maxHeap - 250 * mb).coerceAtMost(200 * mb).takeIf { it > DefaultLoadControl.DEFAULT_VIDEO_BUFFER_SIZE }?.toInt()
}
