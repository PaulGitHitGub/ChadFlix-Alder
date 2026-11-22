package dev.alder

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Handler
import android.os.Looper
import android.os.Process
import android.util.LruCache
import android.widget.ImageView
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.FutureTask
import java.util.concurrent.LinkedBlockingDeque
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit

/** Posters and backgrounds: downloaded off the main thread, scaled down to the size shown, kept in memory. */
object Images {
    // Hardware bitmaps live in graphics memory, not the Java heap; the heap limit (192 MB on the test TV) only scales
    // the budget to the device, so switching pages shows them from memory. Full-screen art (8 MB a picture at 1080p) has a store of its own: sharing one, a few arts pushed out every poster,
    // so a page seen a moment ago drew blank tiles again. Posters: a quarter (48 MB here, about three pages); art: a
    // sixth (32 MB, the art on screen, the one fading out and the neighbours fetched ahead).
    private fun store(budget: Long) = object : LruCache<String, Bitmap>(budget.coerceIn(1L, Int.MAX_VALUE.toLong()).toInt()) {
        override fun sizeOf(key: String, value: Bitmap) = value.byteCount
    }
    // On API 23-25 bitmaps live in the Java heap instead of GPU memory, so halve the cache share there.
    private val softwareBitmaps = android.os.Build.VERSION.SDK_INT < 26
    private val cards = store(Runtime.getRuntime().maxMemory() / if (softwareBitmaps) 8 else 4)
    private val screens = store(Runtime.getRuntime().maxMemory() / if (softwareBitmaps) 12 else 6)
    private fun cache(widthPx: Int, wide: Boolean = false) = if (wide || widthPx > 1000) screens else cards // wide: ChadFlix's widened cards
    // Home asks only for posters near the screen and lets far ones go, so this pool and cache stay small.
    /** Threads in the card pool: the first this many requests start at once, ahead of the newest-first queue. */
    const val THREADS = 8
    private val pool = newestFirst(THREADS)
    // The picture on screen right now (the full-screen art, a logo) skips the queue of cards loading behind it.
    private val urgentPool = newestFirst(2)
    // Art fetched ahead for where the remote may go next never holds up the cards on screen.
    private val aheadPool = newestFirst(2)
    private val tmdb = Regex("""(image\.tmdb\.org/t/p/)(original|w1280)/""")
    private val metahub = Regex("""(images\.metahub\.space/(?:poster|background)/)(small|medium)/""")

    /**
     * The latest request first: after racing along a row, the cards the remote stopped on load before the ones it
     * passed (which mostly turn out stale and are skipped).
     */
    fun newestFirst(threads: Int) = ThreadPoolExecutor(threads, threads, 0, TimeUnit.SECONDS,
        object : LinkedBlockingDeque<Runnable>() { override fun offer(e: Runnable) = offerFirst(e) },
        // Decoding is background work: the four slow cores go to the main thread and RenderThread first.
        { r -> Thread { Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND); r.run() } })
    private val main = Handler(Looper.getMainLooper())

    // Logos straight from MetaHub (Stremio's image server) by IMDb id, so a card's logo doesn't wait for its details.
    fun logoFor(id: String) = id.takeIf { it.startsWith("tt") }?.let { "https://images.metahub.space/logo/medium/${it.substringBefore(':')}/img" }

    /**
     * Shows [url] in [into]; [done] runs once it's showing (right away when cached), for fading it in. When [url] is
     * missing or won't load, [orElse] is shown instead (an episode without a working still gets the series' art), or
     * else [failed] runs.
     */
    fun load(
        url: String?, into: ImageView, widthPx: Int, orElse: String? = null, urgent: Boolean = false, look: Look? = null,
        failed: (() -> Unit)? = null, wide: Boolean = false, done: (() -> Unit)? = null,
    ) {
        if (url == null) {
            if (orElse != null) load(orElse, into, widthPx, look = look, failed = failed, done = done)
            else { into.tag = null; into.setImageDrawable(null); failed?.invoke() }
            return
        }
        into.tag = url
        val key = key(url, widthPx, look)
        cache(widthPx, wide).get(key)?.let { into.setImageBitmap(it); done?.invoke(); return }
        into.setImageDrawable(null)
        (if (urgent) urgentPool else pool).execute {
            if (into.tag != url) return@execute // the view moved on (scrolled away, another title) while this waited
            val bitmap = bitmap(url, widthPx, look, wide)
            if (bitmap == null) {
                main.post { if (into.tag == url) { if (orElse != null) load(orElse, into, widthPx, look = look, failed = failed, done = done) else failed?.invoke() } }
                return@execute
            }
            main.post { if (into.tag == url) { into.setImageBitmap(bitmap); done?.invoke() } }
        }
    }

    /**
     * Fetches [url] into the cache ahead of time, so the next card's art is ready the moment it's focused. [urgent]
     * for the picture about to fill the screen (Home's first title at launch).
     */
    fun prefetch(url: String?, widthPx: Int, urgent: Boolean = false, look: Look? = null, wide: Boolean = false,
                 still: (() -> Boolean)? = null) { // still: ChadFlix, asked again just before the work; false drops it
        if (cache(widthPx, wide).get(key(url ?: return, widthPx, look)) != null) return
        (if (urgent) urgentPool else aheadPool).execute { if (still?.invoke() != false) bitmap(url, widthPx, look, wide) }
    }

    /**
     * Something painted into a picture once, as it's decoded, instead of drawn over it every frame: [paint] gets the
     * picture as decoded (software, [widthPx] wide) and returns what to show. [name] keeps its copies apart from plain ones.
     */
    class Look(val name: String, val paint: (Bitmap) -> Bitmap)
    val blurred = Look("blurred") { src -> frost(src).also { if (it !== src) src.recycle() } }
    /** ChadFlix: a title logo cropped to its ink (MetaHub logos are 800 x 310 canvases with wide transparent margins). Title page only. */
    val trimmedLogo = Look("trimmed") { src ->
        val w = src.width; val h = src.height
        val pixels = IntArray(w * h).also { src.getPixels(it, 0, w, 0, 0, w, h) }
        val box = inkBounds(w, h, pixels)
        // A logo with no visible ink fails (not cached), so the page falls back to the text title.
        if (box == null) { src.recycle(); error("logo has no ink") }
        if (box[0] == 0 && box[1] == 0 && box[2] == w && box[3] == h) src
        else Bitmap.createBitmap(src, box[0], box[1], box[2] - box[0], box[3] - box[1]).also { if (it !== src) src.recycle() }
    }

    private val inFlight = ConcurrentHashMap<String, FutureTask<Bitmap?>>()

    /**
     * The picture at this size: from memory, or downloaded and decoded once however many ask at the same time (a
     * prefetch and then the view it was for wait on the same work instead of doing it twice).
     */
    private fun bitmap(url: String, widthPx: Int, look: Look? = null, wide: Boolean = false): Bitmap? {
        val key = key(url, widthPx, look)
        cache(widthPx, wide).get(key)?.let { return it }
        val mine = FutureTask {
            runCatching {
                val bytes = Http.bytes(sized(url, widthPx))
                if (look == null) decode(bytes, widthPx) else decode(bytes, widthPx, Bitmap.Config.ARGB_8888)?.let(look.paint)
            }.getOrNull()?.also { cache(widthPx, wide).put(key, it) }
        }
        val task = inFlight.putIfAbsent(key, mine) ?: mine
        if (task === mine) try { mine.run() } finally { inFlight.remove(key) }
        return runCatching { task.get() }.getOrNull()
    }

    fun cached(url: String, widthPx: Int, look: Look? = null, wide: Boolean = false) = cache(widthPx, wide).get(key(url, widthPx, look)) != null

    /**
     * [src] frosted for glass, as Plume does it: shrunk to [w] px wide and box-blurred twice. Works on every Android
     * version (RenderEffect needs 12), costs one pass per picture instead of a GPU blur every frame, and the tiny
     * result scales up smooth. Call off the main thread.
     */
    fun frost(src: Bitmap, w: Int = 120): Bitmap {
        val h = maxOf(1, w * src.height / src.width)
        // Hardware bitmaps can't be read back; shrink in two steps, or the result gets grainy.
        val soft = if (src.onGpu()) src.copy(Bitmap.Config.ARGB_8888, false) else src
        val mid = Bitmap.createScaledBitmap(soft, maxOf(w, soft.width / 4), maxOf(h, soft.height / 4), true)
        if (soft !== src && soft !== mid) soft.recycle()
        val small = Bitmap.createScaledBitmap(mid, w, h, true)
        if (mid !== small) mid.recycle()
        var a = IntArray(w * h).also { small.getPixels(it, 0, w, 0, 0, w, h) }
        var b = IntArray(a.size)
        val r = 2
        repeat(2) {
            for (horizontal in listOf(true, false)) {
                val n = if (horizontal) w else h
                val lines = if (horizontal) h else w
                val step = if (horizontal) 1 else w
                val lineStep = if (horizontal) w else 1
                for (l in 0 until lines) for (i in 0 until n) {
                    var red = 0; var green = 0; var blue = 0
                    for (k in -r..r) {
                        val p = a[l * lineStep + (i + k).coerceIn(0, n - 1) * step]
                        red += p shr 16 and 255; green += p shr 8 and 255; blue += p and 255
                    }
                    val c = 2 * r + 1
                    b[l * lineStep + i * step] = (255 shl 24) or (red / c shl 16) or (green / c shl 8) or (blue / c)
                }
                val t = a; a = b; b = t
            }
        }
        val out = if (small.isMutable) small else small.copy(Bitmap.Config.ARGB_8888, true).also { small.recycle() }
        out.setPixels(a, 0, w, 0, 0, w, h)
        return out
    }

    /**
     * MetaHub (Stremio's image server) serves small, medium and large copies, and Stremio's catalogs and library point
     * at the small poster (about 150 px) and medium background (about 1000 px): soft on a TV once stretched. The large
     * copy is asked for instead, and the decode below scales it to the size shown.
     */
    fun sharp(url: String) = url.replace(metahub, "$1large/")

    /** The 16:9 art MetaHub keeps for an IMDb title, for places that have no art of their own (Watch Next). */
    fun backgroundFor(id: String) = id.takeIf { it.startsWith("tt") }?.let { "https://images.metahub.space/background/large/${it.substringBefore(':')}/img" }

    // TMDB serves every picture in a few widths: a card needs the 780 px copy, not the 4K original (a tenth of the bytes).
    fun sized(url: String, widthPx: Int): String {
        val size = if (widthPx <= 780) "w780" else if (widthPx <= 1280) "w1280" else return sharp(url)
        return sharp(url).replace(tmdb, "$1$size/")
    }

    // One copy per size: a card's small copy must never stand in for the full-screen art (it would be blurry).
    private fun key(url: String, widthPx: Int, look: Look? = null) = "$url#$widthPx" + (look?.name?.let { "#$it" } ?: "")

    private fun decode(bytes: ByteArray, widthPx: Int, config: Bitmap.Config = GPU_BITMAP): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        var sample = 1
        while (bounds.outWidth / (sample * 2) >= widthPx) sample *= 2
        // Then scaled to exactly the width shown: a card's poster halved from 780 px was still 390 px for a 248 px card,
        // two and a half times the memory.
        val sampled = bounds.outWidth / sample
        // Decoded straight into GPU memory, here in the background. Otherwise the upload happens in the frame that first
        // shows the picture, and that frame drops: the only jank left when moving along a row.
        val options = BitmapFactory.Options().apply {
            inSampleSize = sample
            inPreferredConfig = config
            if (sampled > widthPx) { inScaled = true; inDensity = sampled; inTargetDensity = widthPx }
        }
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
    }
}

/** ChadFlix: the pixels of a [width] x [height] ARGB picture whose alpha is above [threshold], as [left, top, right, bottom] (right and bottom exclusive); null when none. */
internal fun inkBounds(width: Int, height: Int, pixels: IntArray, threshold: Int = 16): IntArray? {
    var left = width; var top = height; var right = -1; var bottom = -1
    for (y in 0 until height) for (x in 0 until width) {
        if ((pixels[y * width + x] ushr 24) > threshold) {
            if (x < left) left = x
            if (x > right) right = x
            if (y < top) top = y
            bottom = y
        }
    }
    return if (right < 0) null else intArrayOf(left, top, right + 1, bottom + 1)
}

/**
 * ChadFlix: the title page logo's size in px for a trimmed picture of [trimW] x [trimH] px. Constant area (132 x 68 dp):
 * height = min(68, sqrt(8976 / aspect)) dp, width capped at 240 dp. Never larger than the picture's own pixels
 * (one source px per device px at most), so it is never upscaled and stays sharp; the aspect ratio is kept.
 */
internal fun logoSizePx(trimW: Int, trimH: Int, density: Float): Pair<Int, Int> {
    val aspect = trimW.toDouble() / trimH
    var hDp = minOf(68.0, Math.sqrt(8976.0 / aspect))
    var wDp = aspect * hDp
    if (wDp > 240.0) { wDp = 240.0; hDp = wDp / aspect }
    val scale = minOf(1.0, trimW / (wDp * density), trimH / (hDp * density))
    val w = minOf(trimW, Math.floor(wDp * density * scale).toInt()).coerceAtLeast(1)
    val h = minOf(trimH, Math.floor(hDp * density * scale).toInt()).coerceAtLeast(1)
    return w to h
}
