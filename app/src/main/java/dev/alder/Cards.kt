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

internal enum class CardVariant {
    /** 2:3 portrait poster in horizontal rails (Home, Shows, Movies, Details, Search). */
    POSTER_RAIL,
    /** 2:3 portrait poster in Home Continue Watching. */
    CONTINUE_POSTER,
    /** 2:3 dynamic-width poster in My Lists and Discover grids. */
    GRID_POSTER,
    /** 16:9 rectangular card for Live TV channels and events. */
    WIDE_CHANNEL,
}

internal data class CardStyle(
    val variant: CardVariant,
    val widthPx: Int,
    val heightPx: Int,
    val showRim: Boolean = variant == CardVariant.GRID_POSTER,
    val dimAlpha: Float = when (variant) {
        CardVariant.GRID_POSTER -> 0f
        CardVariant.CONTINUE_POSTER -> Screen.CARD_CONTINUE_DIM
        else -> Screen.CARD_DIM
    },
    val placeholderColor: Int = if (variant == CardVariant.GRID_POSTER) Screen.CARD_PLACEHOLDER else Screen.BASE,
    val showFallbackImmediately: Boolean = variant == CardVariant.GRID_POSTER,
    val showProgressOnCard: Boolean = variant == CardVariant.POSTER_RAIL || variant == CardVariant.CONTINUE_POSTER,
    val fadeImageIn: Boolean = true,
    val retryOnFailure: Boolean = false,
)

internal data class CardContent(
    val id: String,
    val name: String,
    val posterUrl: String?,
    val artUrl: String? = null,
    val progress: Float = 0f,
    val finished: Boolean = false,
    val live: Boolean = false,
    val settled: Boolean = true,
    val newEpisode: Boolean = false,
)

/**
 * Shared media card across Home, Movies, Shows, My Lists, Discover, Details, Search, and Live TV.
 * Portrait posters use [CardVariant.POSTER_RAIL], [CardVariant.CONTINUE_POSTER], or [CardVariant.GRID_POSTER];
 * Live TV uses [CardVariant.WIDE_CHANNEL].
 */
internal class MediaCardView(
    private val screen: Screen,
    val style: CardStyle,
    initial: CardContent,
) : FrameLayout(screen) {

    var content: CardContent = initial
        private set
    var payload: Any? = null
    var onRequestMeta: (() -> Unit)? = null

    private val isWide = style.variant == CardVariant.WIDE_CHANNEL
    private val isGrid = style.variant == CardVariant.GRID_POSTER
    private val insetDp = if (isWide) 14 else 12

    // Fading, a card that may overlap itself is drawn into a layer of its own first: an extra pass for every
    // card fading in or out. Fading each part on its own looks the same over 150 ms.
    override fun hasOverlappingRendering() = false

    private val image = ImageView(screen).apply { scaleType = ImageView.ScaleType.CENTER_CROP }
    private val face = object : FrameLayout(screen) { override fun hasOverlappingRendering() = false }.apply { alpha = 0f }
    // Darkens the cards beside the focused one. A shade, not transparency: a see-through card would let the
    // art behind it show through and look like it was floating on the poster.
    private val shade = View(screen).apply { setBackgroundColor(Color.BLACK); alpha = 0f }

    // The name in words: always on a wide non-live card, on a poster only when it has no picture.
    private val nameMade = lazy {
        screen.text(content.name, if (isWide) Screen.BODY else Screen.SMALL, bold = true).apply {
            maxLines = if (isWide) 1 else 2
            ellipsize = TextUtils.TruncateAt.END
            if (content.live) gravity = Gravity.CENTER
        }.also {
            face.addView(it, LayoutParams(
                if (content.live) -2 else -1,
                -2,
                if (content.live) Gravity.CENTER else Gravity.BOTTOM or Gravity.START,
            ).apply {
                if (content.live) setMargins(screen.dp(8), screen.dp(8), screen.dp(8), screen.dp(8))
                else setMargins(screen.dp(insetDp), 0, screen.dp(insetDp), screen.dp(if (content.progress > 0f && style.showProgressOnCard) 22 else 12))
            })
        }
    }
    private val nameView by nameMade

    private var shownUrl: String? = null
    private var wanted = false
    private var showing: Boolean? = null
    private var onFocusHandler: ((Boolean) -> Unit)? = null

    init {
        setBackgroundColor(style.placeholderColor)
        outlineProvider = object : ViewOutlineProvider() {
            override fun getOutline(view: View, outline: Outline) =
                outline.setRoundRect(0, 0, view.width, view.height, screen.dp(Screen.CARD_RADIUS).toFloat())
        }
        clipToOutline = true

        face.addView(image, LayoutParams(-1, -1))
        val hasProgress = style.showProgressOnCard && content.progress > 0f
        if ((isWide && !content.live) || hasProgress) {
            face.addView(View(screen).apply {
                background = GradientDrawable(
                    GradientDrawable.Orientation.BOTTOM_TOP,
                    intArrayOf(0xE6000000.toInt(), 0x66000000, 0x00000000),
                )
            }, LayoutParams(-1, style.heightPx * (if (isWide) 6 else 4) / 10, Gravity.BOTTOM))
        }
        if (isWide && !content.live) nameView.visibility = View.VISIBLE
        if (isWide && content.newEpisode) addNewBadge()

        addView(face, LayoutParams(-1, -1))
        if (hasProgress) {
            addView(screen.progressBar(content.progress), LayoutParams(-1, screen.dp(3), Gravity.BOTTOM).apply {
                setMargins(screen.dp(insetDp), 0, screen.dp(insetDp), screen.dp(11))
            })
        }
        if (isGrid && content.finished) {
            addView(screen.text("✓", Screen.SMALL, bold = true, color = Screen.TUNGSTEN).apply {
                gravity = Gravity.CENTER
                background = screen.rounded(Screen.BASE, 12)
            }, LayoutParams(screen.dp(24), screen.dp(24), Gravity.TOP or Gravity.END).apply {
                setMargins(0, screen.dp(6), screen.dp(6), 0)
            })
        }
        addView(shade, LayoutParams(-1, -1))

        isFocusable = true
        contentDescription = content.name
        setOnFocusChangeListener { _, has ->
            screen.run { lift(has, Screen.CARD_RADIUS, rim = style.showRim) }
            onFocusHandler?.invoke(has)
        }
    }

    fun setOnFocusChanged(handler: (Boolean) -> Unit) {
        onFocusHandler = handler
    }

    fun updateContent(updated: CardContent) {
        content = updated
        contentDescription = updated.name
        if (isWide && updated.newEpisode && face.findViewWithTag<View>("new_badge") == null) addNewBadge()
        if (wanted) load()
    }

    /** In view or slid into a margin; the row's slide supplies the motion. */
    fun show(on: Boolean) {
        if (on == showing) return
        showing = on
        alpha = if (on) 1f else 0f
    }

    fun dim(on: Boolean) {
        shade.animate().alpha(if (on) style.dimAlpha else 0f).setDuration(Screen.FOCUS_MS).start()
    }

    /** On screen (or nearly): load poster/art, or show fallback title when settled. */
    fun load(onLoaded: (() -> Unit)? = null) {
        wanted = true
        val pick = when {
            content.live -> content.posterUrl ?: content.artUrl
            isWide -> content.artUrl ?: content.posterUrl?.takeIf { content.settled }
            else -> content.posterUrl ?: content.artUrl
        }
        if (pick == null || (!content.live && isWide && content.artUrl == null)) {
            onRequestMeta?.invoke()
        }
        val url = pick ?: run {
            if (content.settled || content.live || style.showFallbackImmediately) {
                if (content.live) face.background = screen.glass(Screen.CARD_RADIUS)
                nameView.visibility = View.VISIBLE
                face.alpha = 1f
            }
            return
        }
        if (url == shownUrl) return
        shownUrl = url
        face.animate().cancel()
        face.alpha = 0f
        loadUrl(url, retry = style.retryOnFailure, onLoaded = onLoaded)
    }

    private fun loadUrl(url: String, retry: Boolean, onLoaded: (() -> Unit)?) {
        val imageWidth = (style.widthPx * Screen.CARD_GROW).toInt()
        if (style.showFallbackImmediately && !Images.cached(url, imageWidth)) {
            nameView.visibility = View.VISIBLE
            face.alpha = 1f
        }
        var now = true // from memory: no fade, it's simply there
        val fallbackArt = content.artUrl?.takeIf { it != url && !isWide }
        Images.load(url, image, imageWidth, orElse = fallbackArt, failed = {
            if (retry) {
                postDelayed({ if (isAttachedToWindow && shownUrl == url) loadUrl(url, retry = false, onLoaded = onLoaded) }, 1_500L)
            } else {
                if (content.live) face.background = screen.glass(Screen.CARD_RADIUS)
                nameView.visibility = View.VISIBLE
                face.alpha = 1f
            }
        }) {
            if (content.live) fitLogo()
            if (nameMade.isInitialized()) nameView.visibility = if (isWide && !content.live) View.VISIBLE else View.INVISIBLE
            if (now || !style.fadeImageIn) face.alpha = 1f else screen.run { face.enter(Screen.QUICK_MS) }.alpha(1f).start()
            if (isFocused) screen.run { lift(true, Screen.CARD_RADIUS, rim = style.showRim) }
            onLoaded?.invoke()
        }
        now = false
    }

    /** Far off screen: release decoded drawable. */
    fun unload() {
        if (!wanted && shownUrl == null && image.drawable == null) return
        wanted = false
        shownUrl = null
        image.tag = null
        image.setImageDrawable(null)
        image.setPadding(0, 0, 0, 0)
        face.background = null
        face.alpha = 0f
    }

    private fun addNewBadge() {
        face.addView(screen.text(R.string.new_episode, Screen.SMALL, bold = true, color = Screen.TUNGSTEN).apply {
            tag = "new_badge"
            background = screen.rounded(0xCC000000.toInt(), Screen.CARD_RADIUS)
            setPadding(screen.dp(8), screen.dp(4), screen.dp(8), screen.dp(4))
        }, LayoutParams(-2, -2, Gravity.TOP or Gravity.START).apply {
            setMargins(screen.dp(10), screen.dp(10), 0, 0)
        })
    }

    private fun fitLogo() {
        val bitmap = (image.drawable as? BitmapDrawable)?.bitmap ?: return
        val shape = bitmap.width / bitmap.height.toFloat()
        val target = style.widthPx / style.heightPx.toFloat()
        val logo = bitmap.hasAlpha() || abs(shape - target) > 0.25f
        image.scaleType = if (logo) ImageView.ScaleType.FIT_CENTER else ImageView.ScaleType.CENTER_CROP
        val inset = if (logo) screen.dp(if (isWide) 8 else 14) else 0
        image.setPadding(inset, inset, inset, inset)
        face.background = if (logo) screen.glass(Screen.CARD_RADIUS) else null
    }
}

/**
 * Shared horizontal card rail with D-pad left/right step navigation, focus memory,
 * smooth scrolling, and left-margin visibility culling so cards never clip as slivers.
 */
internal class HorizontalCardRail(
    private val screen: Screen,
    private val cardGapPx: Int,
    private val paddingStartPx: Int,
    private val paddingTopPx: Int,
    private val paddingEndPx: Int = paddingStartPx,
    private val paddingBottomPx: Int = paddingTopPx,
    private val wideMotion: Boolean = false,
    private val fadingEdgePx: Int = 0,
    private val hideLeftFadingEdge: Boolean = false,
    private val stopAtStart: Boolean = true,
    private val stopAtEnd: Boolean = true,
    private val cullLeftMargin: Boolean = true,
    private val cullThresholdPx: Int = paddingStartPx,
) {
    val cards = mutableListOf<MediaCardView>()
    var selected = 0
        private set
    var held = false
        private set

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
        override fun getLeftFadingEdgeStrength() = if (hideLeftFadingEdge) 0f else super.getLeftFadingEdgeStrength()

        override fun dispatchKeyEvent(event: KeyEvent): Boolean {
            val left = event.keyCode == KeyEvent.KEYCODE_DPAD_LEFT
            val right = event.keyCode == KeyEvent.KEYCODE_DPAD_RIGHT
            if (!left && !right) return super.dispatchKeyEvent(event)
            if (left && selected == 0 && !stopAtStart) return super.dispatchKeyEvent(event)
            if (right && selected == cards.lastIndex && !stopAtEnd) return super.dispatchKeyEvent(event)
            if (event.action == KeyEvent.ACTION_DOWN) {
                held = event.repeatCount > 0
                if (shouldSkipRepeat(held, wideMotion, event.repeatCount)) return true
                cards.getOrNull(selected + if (left) -1 else 1)?.requestFocus()
            } else {
                val wasHeld = held
                held = false
                if (wasHeld) onHoldEnd?.invoke(selected)
            }
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
        if (fadingEdgePx > 0) {
            isHorizontalFadingEdgeEnabled = true
            setFadingEdgeLength(fadingEdgePx)
        }
        addView(strip)
        strip.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> showVisibleCards() }
        addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> showVisibleCards() }
        setOnScrollChangeListener { _, _, _, _, _ ->
            showVisibleCards()
            onScroll?.invoke()
        }
    }

    fun appendCard(card: MediaCardView) {
        val index = cards.size
        cards += card
        strip.addView(card, LinearLayout.LayoutParams(card.style.widthPx, card.style.heightPx).apply {
            if (index > 0) marginStart = cardGapPx
        })
    }

    fun selectIndex(index: Int) {
        if (index in cards.indices) selected = index
    }

    fun focusSelected() = cards.getOrNull(selected)?.requestFocus() ?: false

    fun showVisibleCards() {
        if (!cullLeftMargin) return
        val x = scroller.scrollX
        cards.forEach { card ->
            card.show(card.isFocused || (card.right - x > cullThresholdPx && card.left - x < scroller.width))
        }
    }

    fun scrollToCard(index: Int, cardStepPx: Int, animate: Boolean = true): Int {
        selected = index
        anim?.cancel()
        val startScroll = scroller.scrollX
        val targetScroll = minOf(index * cardStepPx, (strip.width - scroller.width).coerceAtLeast(0))
        showVisibleCards()
        if (!animate) {
            scroller.scrollTo(targetScroll, 0)
            return targetScroll
        }
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
