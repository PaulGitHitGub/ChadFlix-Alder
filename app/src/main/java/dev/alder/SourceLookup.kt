package dev.alder

import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper

/** One lookup follows a Play request from the player to Sources, including providers still answering. */
internal class SourceLookup(context: Context, key: String, type: String, videoId: String, refresh: Boolean = false) {
    var providers: Result<Pair<List<Addon>, Boolean>>? = null
        private set
    var answers: List<Result<Addon.Streams>?> = emptyList()
        private set
    private var listener: (() -> Unit)? = null
    private val attempted = mutableSetOf<Triple<String, String?, Int?>>()

    init {
        val lenders = Profiles.streamLenders(context)
        Screen.pool.execute {
            val result = runCatching {
                val own = Stremio.addons(key, refresh).filter { it.providesStreams }
                val lent = lenders.flatMap { lender ->
                    runCatching {
                        val saved = if (refresh) null else Stremio.savedAddons(lender.authKey)
                        (saved ?: Stremio.addons(lender.authKey, refresh)).filter { it.shareable }
                    }.getOrDefault(emptyList())
                }
                val household = lent.filter { it.serves("stream", type, videoId) }.distinctBy { it.id }
                val supported = household + own.filter { it.serves("stream", type, videoId) && household.none { other -> other.id == it.id } }
                supported to (own.any { !it.official } || lent.isNotEmpty())
            }
            main.post {
                val addons = result.getOrNull()?.first.orEmpty()
                val slots = MutableList<Result<Addon.Streams>?>(addons.size) { null }
                answers = slots
                providers = result
                listener?.invoke()
                addons.forEachIndexed { index, addon ->
                    Screen.pool.execute {
                        val answer = runCatching { addon.streams(type, videoId, allowTorrents = supportsTorrentPlayback) }
                        main.post {
                            slots[index] = answer
                            listener?.invoke()
                        }
                    }
                }
            }
        }
    }

    fun listen(update: () -> Unit): Cancellable {
        listener = update
        // Defer the initial update so a caller can install its cancellation handle first.
        main.post { if (listener === update) update() }
        return object : Cancellable {
            override fun cancel() { if (listener === update) listener = null }
        }
    }

    private fun key(stream: Stream) = Triple(stream.url, stream.torrent?.infoHash, stream.torrent?.fileIndex)
    fun markFailed(streams: List<Stream>) { streams.forEach { attempted.add(key(it)) } }
    fun wasTried(stream: Stream) = key(stream) in attempted

    fun handoff(intent: Intent): Intent {
        val token = ++lastToken
        handoffs[token] = this
        main.postDelayed({ handoffs.remove(token) }, 10_000L)
        return intent.putExtra(EXTRA, token)
    }

    companion object {
        private const val EXTRA = "sourceLookup"
        private val main = Handler(Looper.getMainLooper())
        private val handoffs = mutableMapOf<Int, SourceLookup>()
        private var lastToken = 0

        fun take(intent: Intent): SourceLookup? {
            val token = intent.getIntExtra(EXTRA, 0)
            intent.removeExtra(EXTRA)
            return handoffs.remove(token)
        }
    }
}
