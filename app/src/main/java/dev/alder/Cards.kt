package dev.alder

import android.animation.ValueAnimator
import android.graphics.Color
import android.graphics.Outline
import android.graphics.Rect
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.GradientDrawable
import android.text.TextUtils
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewOutlineProvider
import android.view.animation.DecelerateInterpolator
import android.view.animation.LinearInterpolator
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import kotlin.math.abs

internal fun pickCardImageUrl(
    live: Boolean,
    wide: Boolean,
    settled: Boolean,
    posterUrl: String?,
    artUrl: String?,
): String? = when {
    live -> posterUrl ?: artUrl
    wide -> artUrl ?: posterUrl?.takeIf { settled }
    else -> posterUrl ?: artUrl
}

internal fun needsCardMeta(
    live: Boolean,
    wide: Boolean,
    posterUrl: String?,
    artUrl: String?,
): Boolean = (posterUrl == null && artUrl == null) || (!live && wide && artUrl == null)

internal fun isLogoImage(
    hasAlpha: Boolean,
    bitmapWidth: Int,
    bitmapHeight: Int,
    cardWidthPx: Int,
    cardHeightPx: Int,
): Boolean {
    if (hasAlpha) return true
    if (bitmapHeight <= 0 || cardHeightPx <= 0) return false
    val shape = bitmapWidth / bitmapHeight.toFloat()
    val target = cardWidthPx / cardHeightPx.toFloat()
    return abs(shape - target) > 0.25f
}

internal fun railTargetScroll(index: Int, stepPx: Int, stripWidthPx: Int, scrollerWidthPx: Int): Int =
    minOf(index * stepPx, (stripWidthPx - scrollerWidthPx).coerceAtLeast(0))

internal fun isCardVisibleInRail(
    isFocused: Boolean,
    cardLeftPx: Int,
    cardRightPx: Int,
    scrollXPx: Int,
    scrollerWidthPx: Int,
    paddingStartPx: Int,
    cullLeft: Boolean,
): Boolean = !cullLeft || isFocused || (cardRightPx - scrollXPx > paddingStartPx && cardLeftPx - scrollXPx < scrollerWidthPx)

/**
 * Shared media card across Home, Movies, Shows, My Lists, Discover, Details, Search, and Live TV.
 */
internal class MediaCardView(
    private val screen: Screen,
    val titleName: String,
    var posterUrl: String?,
    var artUrl: String? = null,
    val widthPx: Int = screen.dp(Screen.POSTER_W),
    val heightPx: Int = screen.dp(Screen.POSTER_H),
    val wide: Boolean = false,
    val grid: Boolean = false,
    val showRim: Boolean = true,
    val dimAlpha: Float = if (grid) 0f else Screen.CARD_DIM,
    val eagerFallback: Boolean = grid,
    progress: Float = 0f,
    finished: Boolean = false,
    val live: Boolean = false,
    var settled: Boolean = true,
    val fadeIn: Boolean = true,
    val retryOnFailure: Boolean = false,
) : FrameLayout(screen) {

    var payload: Any? = null
    var onRequestMeta: (() -> Unit)? = null

    private val insetDp = if (wide) 14 else 12

    // Fading, a card that may overlap itself is drawn into a layer of its own first: an extra pass for every
    // card fading in or out. Fading each part on its own looks the same over 150 ms.
    override fun hasOverlappingRendering() = false

    private val image = ImageView(screen).apply { scaleType = ImageView.ScaleType.CENTER_CROP }
    private val face = object : FrameLayout(screen) { override fun hasOverlappingRendering() = false }.apply { alpha = 0f }
    // Darkens the cards beside the focused one. A shade, not transparency: a see-through card would let the
    // art behind it show through and look like it was floating on the poster.
    private val shade = View(screen).apply { setBackgroundColor(Color.BLACK); alpha = 0f }

    private val hasProgress = !grid && progress > 0f

    // The name in words: always on a wide non-live card, on a poster only when it has no picture.
    private val nameMade = lazy {
        screen.text(titleName, if (wide) Screen.BODY else Screen.SMALL, bold = true).apply {
            maxLines = if (wide) 1 else 2
            ellipsize = TextUtils.TruncateAt.END
            if (live) gravity = Gravity.CENTER
        }.also {
            face.addView(it, LayoutParams(
                if (live) -2 else -1,
                -2,
                if (live) Gravity.CENTER else Gravity.BOTTOM or Gravity.START,
            ).apply {
                if (live) setMargins(screen.dp(8), screen.dp(8), screen.dp(8), screen.dp(8))
                else setMargins(screen.dp(insetDp), 0, screen.dp(insetDp), screen.dp(if (hasProgress) 22 else 12))
            })
        }
    }
    private val nameView by nameMade

    private var shownUrl: String? = null
    private var wanted = false
    private var showing: Boolean? = null
    private var onFocusHandler: ((Boolean) -> Unit)? = null

    init {
        setBackgroundColor(if (grid) Screen.CARD_PLACEHOLDER else Screen.BASE)
        outlineProvider = object : ViewOutlineProvider() {
            override fun getOutline(view: View, outline: Outline) =
                outline.setRoundRect(0, 0, view.width, view.height, screen.dp(Screen.CARD_RADIUS).toFloat())
        }
        clipToOutline = true

        face.addView(image, LayoutParams(-1, -1))
        if ((wide && !live) || hasProgress) {
            face.addView(View(screen).apply {
                background = GradientDrawable(
                    GradientDrawable.Orientation.BOTTOM_TOP,
                    intArrayOf(0xE6000000.toInt(), 0x66000000, 0x00000000),
                )
            }, LayoutParams(-1, heightPx * (if (wide) 6 else 4) / 10, Gravity.BOTTOM))
        }
        if (wide && !live) nameView.visibility = View.VISIBLE

        addView(face, LayoutParams(-1, -1))
        if (hasProgress) {
            addView(screen.progressBar(progress), LayoutParams(-1, screen.dp(3), Gravity.BOTTOM).apply {
                setMargins(screen.dp(insetDp), 0, screen.dp(insetDp), screen.dp(11))
            })
        }
        if (grid && finished) {
            addView(screen.text("✓", Screen.SMALL, bold = true, color = Screen.TUNGSTEN).apply {
                gravity = Gravity.CENTER
                background = screen.rounded(Screen.BASE, 12)
            }, LayoutParams(screen.dp(24), screen.dp(24), Gravity.TOP or Gravity.END).apply {
                setMargins(0, screen.dp(6), screen.dp(6), 0)
            })
        }
        addView(shade, LayoutParams(-1, -1))

        isFocusable = true
        contentDescription = titleName
        setOnFocusChangeListener { _, has ->
            screen.run { lift(has, Screen.CARD_RADIUS, rim = showRim) }
            onFocusHandler?.invoke(has)
        }
    }

    fun setOnFocusChanged(handler: (Boolean) -> Unit) {
        onFocusHandler = handler
    }

    fun refresh(poster: String? = posterUrl, art: String? = artUrl, isSettled: Boolean = settled) {
        posterUrl = poster
        artUrl = art
        settled = isSettled
        if (wanted) load()
    }

    /** In view or slid into a margin; the row's slide supplies the motion. */
    fun show(on: Boolean) {
        if (on == showing) return
        showing = on
        alpha = if (on) 1f else 0f
    }

    fun dim(on: Boolean, animate: Boolean = true) {
        if (dimAlpha == 0f) return
        val target = if (on) dimAlpha else 0f
        shade.animate().cancel()
        if (animate) shade.animate().alpha(target).setDuration(Screen.FOCUS_MS).start()
        else shade.alpha = target
    }

    /** On screen (or nearly): load poster/art, or show fallback title when settled. */
    fun load() {
        wanted = true
        if (needsCardMeta(live, wide, posterUrl, artUrl)) {
            onRequestMeta?.invoke()
        }
        val url = pickCardImageUrl(live, wide, settled, posterUrl, artUrl) ?: run {
            if (settled || live || eagerFallback) {
                if (live) face.background = screen.glass(Screen.CARD_RADIUS)
                nameView.visibility = View.VISIBLE
                face.alpha = 1f
            }
            return
        }
        if (url == shownUrl) return
        shownUrl = url
        face.animate().cancel()
        face.alpha = 0f
        loadUrl(url, retry = retryOnFailure)
    }

    private fun loadUrl(url: String, retry: Boolean) {
        val imageWidth = (widthPx * Screen.CARD_GROW).toInt()
        if (eagerFallback && !Images.cached(url, imageWidth)) {
            nameView.visibility = View.VISIBLE
            face.alpha = 1f
        }
        var now = true // from memory: no fade, it's simply there
        val fallbackArt = artUrl?.takeIf { it != url && !wide }
        Images.load(url, image, imageWidth, orElse = fallbackArt, failed = {
            if (retryOnFailure) {
                android.util.Log.w("Alder", "search poster failed: ${android.net.Uri.parse(url).host ?: "none"}" +
                    if (retry) ", trying again" else "")
            }
            if (retry) {
                postDelayed({ if (isAttachedToWindow && shownUrl == url) loadUrl(url, retry = false) }, 2_000L)
            } else {
                if (live) face.background = screen.glass(Screen.CARD_RADIUS)
                nameView.visibility = View.VISIBLE
                face.alpha = 1f
            }
        }) {
            if (live) fitLogo()
            if (nameMade.isInitialized()) nameView.visibility = if (wide && !live) View.VISIBLE else View.INVISIBLE
            if (now || !fadeIn) face.alpha = 1f else screen.run { face.enter(Screen.QUICK_MS) }.alpha(1f).start()
            if (isFocused) screen.run { lift(true, Screen.CARD_RADIUS, rim = showRim) }
        }
        now = false
    }

    /** Far off screen: release decoded drawable. */
    fun unload() {
        if (!wanted && shownUrl == null && image.drawable == null) return
        wanted = false
        shownUrl = null
        face.animate().cancel()
        image.tag = null
        image.setImageDrawable(null)
        image.setPadding(0, 0, 0, 0)
        face.background = null
        face.alpha = 0f
    }

    private fun fitLogo() {
        val bitmap = (image.drawable as? BitmapDrawable)?.bitmap ?: return
        val logo = isLogoImage(bitmap.hasAlpha(), bitmap.width, bitmap.height, widthPx, heightPx)
        image.scaleType = if (logo) ImageView.ScaleType.FIT_CENTER else ImageView.ScaleType.CENTER_CROP
        val inset = if (logo) screen.dp(if (wide) 8 else 14) else 0
        image.setPadding(inset, inset, inset, inset)
        face.background = if (logo) screen.glass(Screen.CARD_RADIUS) else null
    }
}

/**
 * Shared horizontal card rail with D-pad left/right step navigation, focus memory,
 * smooth scrolling, and left-margin visibility culling so cards never clip as slivers.
 */
internal class HorizontalCardRail(
    screen: Screen,
    private val cardGapPx: Int,
    private val paddingStartPx: Int,
    paddingTopPx: Int,
    paddingEndPx: Int = paddingStartPx,
    paddingBottomPx: Int = paddingTopPx,
    private val wideMotion: Boolean = false,
    rightFadePx: Int = 0,
    private val stopAtStart: Boolean = true,
    private val cullLeft: Boolean = true,
) {
    val cards = mutableListOf<MediaCardView>()
    var selected = 0
        private set
    var held = false
        private set

    val stepPx: Int get() = (cards.firstOrNull()?.widthPx ?: 0) + cardGapPx

    var onScroll: (() -> Unit)? = null
    var onHoldEnd: ((selected: Int) -> Unit)? = null

    private var anim: ValueAnimator? = null

    val strip = LinearLayout(screen).apply {
        setPadding(paddingStartPx, paddingTopPx, paddingEndPx, paddingBottomPx)
        clipToPadding = false
        clipChildren = false
    }

    val scroller: HorizontalScrollView = object : HorizontalScrollView(screen) {
        override fun computeScrollDeltaToGetChildRectOnScreen(rect: Rect) = 0
        override fun getLeftFadingEdgeStrength() = if (rightFadePx > 0) 0f else super.getLeftFadingEdgeStrength()

        override fun dispatchKeyEvent(event: KeyEvent): Boolean {
            val left = event.keyCode == KeyEvent.KEYCODE_DPAD_LEFT
            val right = event.keyCode == KeyEvent.KEYCODE_DPAD_RIGHT
            if (!left && !right) return super.dispatchKeyEvent(event)
            if (event.action != KeyEvent.ACTION_DOWN) {
                val wasHeld = held
                held = false
                if (wasHeld) onHoldEnd?.invoke(selected)
                if (left && selected == 0 && !stopAtStart) return super.dispatchKeyEvent(event)
                return true
            }
            if (left && selected == 0 && !stopAtStart) return super.dispatchKeyEvent(event)
            held = event.repeatCount > 0
            if (shouldSkipRepeat(held, wideMotion, event.repeatCount)) return true
            cards.getOrNull(selected + if (left) -1 else 1)?.requestFocus()
            return true
        }

        // Coming in from above or below, the remote lands on the card this rail last had.
        override fun addFocusables(views: ArrayList<View>, direction: Int, focusableMode: Int) {
            if (!hasFocus() && (direction == View.FOCUS_UP || direction == View.FOCUS_DOWN) && cards.isNotEmpty()) {
                views.add(cards[selected.coerceIn(cards.indices)])
            } else {
                super.addFocusables(views, direction, focusableMode)
            }
        }
    }.apply {
        isHorizontalScrollBarEnabled = false
        if (rightFadePx > 0) {
            isHorizontalFadingEdgeEnabled = true
            setFadingEdgeLength(rightFadePx)
        }
        addView(strip)
        if (cullLeft) {
            strip.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> showVisibleCards() }
            addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> showVisibleCards() }
        }
        setOnScrollChangeListener { _, _, _, _, _ ->
            if (cullLeft) showVisibleCards()
            onScroll?.invoke()
        }
    }

    fun clearHeld() {
        held = false
    }

    fun appendCard(card: MediaCardView) {
        val index = cards.size
        cards += card
        strip.addView(card, LinearLayout.LayoutParams(card.widthPx, card.heightPx).apply {
            if (index > 0) marginStart = cardGapPx
        })
    }

    fun selectIndex(index: Int) {
        if (index in cards.indices) selected = index
    }

    fun focusSelected() = cards.getOrNull(selected)?.requestFocus() ?: false

    fun showVisibleCards() {
        if (!cullLeft) return
        val x = scroller.scrollX
        cards.forEach { card ->
            card.show(isCardVisibleInRail(card.isFocused, card.left, card.right, x, scroller.width, paddingStartPx, cullLeft))
        }
    }

    fun scrollToCard(index: Int): Int {
        selected = index
        anim?.cancel()
        val startScroll = scroller.scrollX
        val targetScroll = railTargetScroll(index, stepPx, strip.width, scroller.width)
        if (cullLeft) showVisibleCards()
        val glideTime = rowGlideDuration(held, wideMotion, ViewConfiguration.getKeyRepeatDelay().toLong())
        anim = ValueAnimator.ofInt(startScroll, targetScroll).setDuration(glideTime).apply {
            interpolator = if (held) LinearInterpolator() else DecelerateInterpolator()
            addUpdateListener { scroller.scrollTo(it.animatedValue as Int, 0) }
            start()
        }
        return targetScroll
    }

    fun hasCardsBeyond(targetScroll: Int, rightMarginPx: Int): Boolean =
        cards.any { it.right - targetScroll > scroller.width - rightMarginPx }
}
