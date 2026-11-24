package dev.alder

import android.animation.Animator
import android.animation.AnimatorSet
import android.animation.Keyframe
import android.animation.ObjectAnimator
import android.animation.PropertyValuesHolder
import android.animation.TimeInterpolator
import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.drawable.Drawable
import android.os.Build
import android.text.TextPaint
import android.util.Property
import android.view.View
import android.view.ViewGroup
import android.view.animation.LinearInterpolator
import android.view.animation.PathInterpolator
import android.widget.ImageView
import android.widget.TextView

/** Search's intent extra: Home's pill as Search opens (screen px: left, top, right, bottom), for the pill to open from. */
internal const val HOME_PILL = "homePill"

/**
 * The Search page's top-bar pill, the designer's variant 1 "Slingshot" (pill-anim-20261124/README.md): one view, behind
 * the pages' names, draws the white pill, the black focus pill and Voice | Keyboard from about 25 floats, each moved by
 * an [ObjectAnimator] whose setter only invalidates (nothing is laid out). The real Voice and Keyboard views stay in the
 * invisible [capsule] as focus targets and give the places.
 *
 * - Opening (from a page's name, from Home's pill [arrive]s at, or in place from the magnifier): the left edge is thrown
 *   to the capsule's left end with an overshoot, the right edge follows 50 ms later, the pill thins as it stretches, the
 *   magnifier shrinks away under it, and the black pill pops out of the left end and grows over Voice, the icons and
 *   words popping in after it (400 ms).
 * - Closing onto a page's name: the mirror, right edge first (300 ms). Between names the pill glides, its leading edge
 *   first. Voice ↔ Keyboard: the black pill's leading edge first, 30 ms ahead, with a small squash (190 ms).
 * - Every animator starts from the live value, so a new move cancels the running one and carries on from where it is.
 *   With animations off (Remove animations) every change lands at once.
 */
internal class SearchPill(
    context: Context,
    private val bar: View, // the top bar: focus in it but not on a name or the capsule (the avatar, the gear) dims the capsule
    private val capsule: ViewGroup, // Voice and Keyboard, invisible, for focus and places
    private val voice: ViewGroup?,
    private val keyboard: ViewGroup,
    private val slot: View, // the magnifier tab's room
    private val names: () -> List<TextView>, // the pages' names, coloured by whether the pill covers them
) : View(context) {
    private enum class Mode { OPEN, NAME }
    private var mode = Mode.OPEN
    private var on: View? = null // the capsule item under the black pill, or the name under the white pill
    private var running: AnimatorSet? = null
    private val parts = mutableListOf<Animator>()
    private var animate = true
    var listening = false // Voice's mic goes red

    // The state: the white pill, its squash; the black pill, its alpha, scale and squash; the magnifier; each item's
    // icon (alpha, scale) and words (alpha, sideways offset); and whether the capsule is lit (white) or faint.
    private var pillL = 0f; private var pillR = 0f; private var pillT = 0f; private var pillB = 0f; private var squash = 1f
    private var blackL = 0f; private var blackR = 0f; private var blackT = 0f; private var blackB = 0f
    private var blackA = 0f; private var blackS = 1f; private var blackSy = 1f
    private var glyphA = 0f; private var glyphS = 1f
    private var vIconA = 1f; private var vIconS = 1f; private var vWordsA = 1f; private var vWordsX = 0f
    private var kIconA = 1f; private var kIconS = 1f; private var kWordsA = 1f; private var kWordsX = 0f
    private var lit = true
    private var started = false // nothing is drawn or moved before [arrive]

    private class F(name: String, val get: (SearchPill) -> Float, val put: (SearchPill, Float) -> Unit) :
        Property<SearchPill, Float>(Float::class.javaObjectType, name) {
        override fun get(o: SearchPill) = get.invoke(o)
        override fun set(o: SearchPill, v: Float) { put(o, v); o.moved() }
    }

    private fun moved() {
        recolorNames()
        invalidate()
    }

    // ---------- drawing ----------

    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val words = TextPaint(Paint.ANTI_ALIAS_FLAG)
    private val box = RectF()
    private val mic: Drawable = context.getDrawable(R.drawable.ic_mic)!!.mutate()
    private val keys: Drawable = context.getDrawable(R.drawable.ic_search)!!.mutate()
    private val glyph: Drawable = context.getDrawable(R.drawable.ic_search)!!.mutate()
    private val here = IntArray(2)
    private val there = IntArray(2)

    /** [v]'s bounds in this view. */
    private fun rectOf(v: View): RectF {
        getLocationInWindow(here)
        v.getLocationInWindow(there)
        val x = (there[0] - here[0]).toFloat()
        val y = (there[1] - here[1]).toFloat()
        return RectF(x, y, x + v.width, y + v.height)
    }

    override fun onDraw(canvas: Canvas) {
        if (!started) return
        // The white pill, its top and bottom drawn in while it stretches.
        if (pillR - pillL > 1f) {
            val inset = (1f - squash) * (pillB - pillT) / 2f
            box.set(pillL, pillT + inset, pillR, pillB - inset)
            fill.color = if (lit) HomeStyle.WHITE else HomeStyle.OPEN_TAB
            canvas.drawRoundRect(box, box.height() / 2f, box.height() / 2f, fill)
        }
        // The magnifier, black while the white pill covers it.
        if (glyphA > 0.01f) {
            val r = rectOf(slot)
            val half = resources.displayMetrics.density * GLYPH_DP / 2f * glyphS
            val covered = lit && r.centerX() in pillL..pillR
            glyph.setTint(if (covered) HomeStyle.BLACK else HomeStyle.WHITE)
            glyph.alpha = (glyphA * 255).toInt()
            glyph.setBounds((r.centerX() - half).toInt(), (r.centerY() - half).toInt(), (r.centerX() + half).toInt(), (r.centerY() + half).toInt())
            glyph.draw(canvas)
        }
        // The black pill, popping from its centre and squashing as it moves.
        if (blackA > 0.01f) {
            val w = (blackR - blackL) * blackS
            val h = (blackB - blackT) * blackS * blackSy
            val cx = (blackL + blackR) / 2f
            val cy = (blackT + blackB) / 2f
            box.set(cx - w / 2f, cy - h / 2f, cx + w / 2f, cy + h / 2f)
            fill.color = HomeStyle.BLACK
            fill.alpha = (blackA * 255).toInt()
            canvas.drawRoundRect(box, h / 2f, h / 2f, fill)
            fill.alpha = 255
        }
        // Voice and Keyboard twice: on the capsule's colour, then white again where the black pill is, so their colour
        // follows its edge as it slides.
        items(canvas, if (lit) HomeStyle.BLACK else HomeStyle.WHITE)
        if (blackA > 0.01f) {
            canvas.save()
            canvas.clipRect(box)
            items(canvas, HomeStyle.WHITE)
            canvas.restore()
        }
    }

    private fun items(canvas: Canvas, color: Int) {
        voice?.let { item(canvas, it, mic, if (listening) HomeStyle.LIST_RED else color, color, vIconA, vIconS, vWordsA, vWordsX) }
        item(canvas, keyboard, keys, color, color, kIconA, kIconS, kWordsA, kWordsX)
    }

    private fun item(canvas: Canvas, item: ViewGroup, icon: Drawable, iconColor: Int, color: Int,
                     iconA: Float, iconS: Float, wordsA: Float, wordsX: Float) {
        if (iconA <= 0.01f && wordsA <= 0.01f) return
        val r = rectOf(item)
        val image = item.getChildAt(0) as ImageView
        val label = item.getChildAt(1) as TextView
        if (iconA > 0.01f) {
            val cx = r.left + image.left + image.width / 2f
            val cy = r.top + image.top + image.height / 2f
            val hw = image.width / 2f * iconS
            val hh = image.height / 2f * iconS
            icon.setTint(iconColor)
            icon.alpha = (iconA * 255).toInt()
            icon.setBounds((cx - hw).toInt(), (cy - hh).toInt(), (cx + hw).toInt(), (cy + hh).toInt())
            icon.draw(canvas)
        }
        if (wordsA > 0.01f) {
            words.set(label.paint)
            words.color = color
            words.alpha = (wordsA * 255).toInt()
            canvas.drawText(label.text.toString(), r.left + label.left + label.paddingLeft + wordsX,
                r.top + label.top + label.baseline, words)
        }
    }

    /** A page's name reads black while the lit pill covers its middle, white on the page otherwise. */
    private fun recolorNames() {
        for (t in names()) {
            val mid = rectOf(t).centerX()
            val color = if (lit && pillR - pillL > 1f && mid in pillL..pillR) HomeStyle.BLACK else HomeStyle.WHITE
            if (t.currentTextColor != color) t.setTextColor(color)
        }
    }

    // ---------- moving ----------

    private fun begin() {
        running?.cancel()
        running = null
        parts.clear()
        animate = Build.VERSION.SDK_INT < 26 || ValueAnimator.areAnimatorsEnabled()
    }

    private fun play() {
        if (parts.isEmpty()) return moved()
        running = AnimatorSet().apply { playTogether(parts.toList()); start() }
        parts.clear()
    }

    /** [p] from [from] to [to] after [delay] ms over [ms]; with animations off it lands at once. */
    private fun tween(p: F, from: Float, to: Float, delay: Long, ms: Long, curve: TimeInterpolator) {
        if (!animate) return p.put(this, to)
        if (from == to) return p.put(this, to)
        parts += ObjectAnimator.ofFloat(this, p, from, to).apply { startDelay = delay; duration = ms; interpolator = curve }
    }

    /** [p] from where it is now. */
    private fun go(p: F, to: Float, delay: Long, ms: Long, curve: TimeInterpolator) = tween(p, p.get(this), to, delay, ms, curve)

    /** [p] to [low] and back to 1: [down] ms easing out, then [up] ms with a pop (the pill's squash). */
    private fun dip(p: F, low: Float, delay: Long, down: Long, up: Long) {
        if (!animate) return p.put(this, 1f)
        val total = (down + up).toFloat()
        val values = PropertyValuesHolder.ofKeyframe(p, Keyframe.ofFloat(0f, p.get(this)),
            Keyframe.ofFloat(down / total, low).apply { interpolator = OUT },
            Keyframe.ofFloat(1f, 1f).apply { interpolator = POP })
        parts += ObjectAnimator.ofPropertyValuesHolder(this, values).apply { startDelay = delay; duration = down + up }
    }

    /** Where [v] is: the white pill's edges and height. */
    private fun place(v: View) = rectOf(v).also { pillL = it.left; pillR = it.right; pillT = it.top; pillB = it.bottom }

    /**
     * The page opens: the pill starts on Home's ([fromOnScreen], the open or focused page's name there, in screen px)
     * or on the magnifier, and opens into the capsule with the black pill on [focus].
     */
    fun arrive(fromOnScreen: IntArray?, focus: View?) {
        started = true
        if (fromOnScreen != null && fromOnScreen.size == 4) {
            getLocationOnScreen(here)
            pillL = fromOnScreen[0] - here[0].toFloat(); pillT = fromOnScreen[1] - here[1].toFloat()
            pillR = fromOnScreen[2] - here[0].toFloat(); pillB = fromOnScreen[3] - here[1].toFloat()
        } else place(slot)
        glyphA = 1f; glyphS = 1f
        blackA = 0f
        vIconA = 0f; vWordsA = 0f; kIconA = 0f; kWordsA = 0f
        mode = Mode.NAME
        on = null
        open(focus ?: voice ?: keyboard)
    }

    /** The remote moved to [v]: the pill follows it. */
    fun focusOn(v: View?) {
        if (!started) return
        val names = names()
        when {
            v != null && (v === voice || v === keyboard) -> { lit = true; open(v) }
            v != null && names.any { it === v } -> { lit = true; toName(v) }
            v != null && isIn(v, bar) -> { lit = false; open(null) } // the avatar or the gear
            else -> { lit = true; open(null) }
        }
    }

    private fun isIn(v: View, parent: View): Boolean = generateSequence(v) { it.parent as? View }.any { it === parent }

    /** The capsule, the black pill on [item] (null: none, the remote is below). */
    private fun open(item: View?) {
        if (mode == Mode.OPEN) {
            if (item === on) return moved()
            val from = on
            on = item
            begin()
            when {
                item == null -> go(BLACK_A, 0f, 0, 90, LIN)
                from == null -> {
                    val r = rectOf(item)
                    blackL = r.left; blackR = r.right; blackT = r.top; blackB = r.bottom
                    go(BLACK_A, 1f, 0, 90, OUT)
                    tween(BLACK_S, if (blackA < 0.05f) 0.55f else blackS, 1f, 0, 200, POP)
                }
                else -> focusMove(rectOf(item))
            }
            return play()
        }
        // From a page's name (or Home's pill): the slingshot.
        mode = Mode.OPEN
        on = item
        begin()
        val cap = rectOf(capsule)
        go(PILL_L, cap.left, 0, 300, O4)
        go(PILL_R, cap.right, 50, 250, FOS)
        go(PILL_T, cap.top, 0, 250, FOS)
        go(PILL_B, cap.bottom, 0, 250, FOS)
        dip(SQUASH, 0.92f, 0, 120, 240)
        go(GLYPH_A, 0f, 90, 110, LIN)
        go(GLYPH_S, 0.6f, 90, 110, OUT)
        if (item != null) {
            val r = rectOf(item)
            blackT = r.top; blackB = r.bottom
            if (blackA < 0.05f) { blackL = r.left; blackR = r.left + r.height() }
            go(BLACK_L, r.left, 150, 240, O2)
            go(BLACK_R, r.right, 150, 240, O2)
            tween(BLACK_A, blackA, 1f, 150, 1, LIN)
            tween(BLACK_S, if (blackA < 0.05f) 0.55f else blackS, 1f, 150, 200, POP)
        } else go(BLACK_A, 0f, 0, 80, LIN)
        popIn(V_ICON_A, V_ICON_S, V_WORDS_A, V_WORDS_X, -dp(5f), 200, 230)
        popIn(K_ICON_A, K_ICON_S, K_WORDS_A, K_WORDS_X, dp(7f), 230, 260)
        play()
    }

    /** An item's icon pops in, its words slide in a beat later from [shift] px. */
    private fun popIn(iconA: F, iconS: F, wordsA: F, wordsX: F, shift: Float, iconAt: Long, wordsAt: Long) {
        val hidden = iconA.get(this) < 0.05f
        go(iconA, 1f, iconAt, 90, OUT)
        tween(iconS, if (hidden) 0.5f else iconS.get(this), 1f, iconAt, 160, POP)
        go(wordsA, 1f, wordsAt, 100, OUT)
        tween(wordsX, if (wordsA.get(this) < 0.05f) shift else wordsX.get(this), 0f, wordsAt, 140, OUT)
    }

    /** Voice ↔ Keyboard: the black pill's leading edge first, the trailing one 30 ms behind, a small squash. */
    private fun focusMove(to: RectF) {
        val right = to.centerX() > (blackL + blackR) / 2f
        go(if (right) BLACK_R else BLACK_L, if (right) to.right else to.left, 0, 150, O1)
        go(if (right) BLACK_L else BLACK_R, if (right) to.left else to.right, 30, 150, FOS)
        go(BLACK_A, 1f, 0, 60, OUT)
        dip(BLACK_SY, 0.94f, 0, 70, 120)
    }

    /** Onto a page's name: from the capsule the mirrored slingshot (right edge first); between names a glide. */
    private fun toName(name: View) {
        if (mode == Mode.NAME && on === name) return moved()
        val t = rectOf(name)
        begin()
        if (mode == Mode.OPEN) {
            go(V_ICON_A, 0f, 0, 70, LIN); go(V_WORDS_A, 0f, 0, 70, LIN)
            go(K_ICON_A, 0f, 0, 70, LIN); go(K_WORDS_A, 0f, 0, 70, LIN)
            go(BLACK_A, 0f, 0, 80, LIN)
            go(PILL_R, t.right, 0, 240, O4)
            go(PILL_L, t.left, 40, 220, FOS)
            go(PILL_T, t.top, 0, 220, FOS)
            go(PILL_B, t.bottom, 0, 220, FOS)
            go(GLYPH_A, 1f, 110, 60, LIN)
            go(GLYPH_S, 1f, 110, 200, POP)
        } else {
            val right = t.centerX() > (pillL + pillR) / 2f
            go(if (right) PILL_R else PILL_L, if (right) t.right else t.left, 0, 200, FOS)
            go(if (right) PILL_L else PILL_R, if (right) t.left else t.right, 30, 200, FOS)
            go(PILL_T, t.top, 0, 200, FOS)
            go(PILL_B, t.bottom, 0, 200, FOS)
        }
        mode = Mode.NAME
        on = name
        play()
    }

    /** The places moved (the capsule widened for "Listening…", the names changed): at rest, the pill follows at once. */
    fun relayout() {
        if (!started || running?.isRunning == true) return
        val target = on
        if (mode == Mode.OPEN) {
            val cap = rectOf(capsule)
            pillL = cap.left; pillR = cap.right; pillT = cap.top; pillB = cap.bottom
            if (target != null) rectOf(target).let { blackL = it.left; blackR = it.right; blackT = it.top; blackB = it.bottom }
        } else if (target != null && target.isAttachedToWindow) place(target)
        moved()
    }

    private fun dp(v: Float) = v * resources.displayMetrics.density

    private companion object {
        const val GLYPH_DP = 18f
        // The README's curves: Android's material curves as PathInterpolators, and the overshoots built into the curve.
        val LIN = LinearInterpolator()
        val FOS = PathInterpolator(0.4f, 0f, 0.2f, 1f)
        val OUT = PathInterpolator(0f, 0f, 0.2f, 1f)
        val O4 = PathInterpolator(0.22f, 0.9f, 0.3f, 1.16f)
        val O2 = PathInterpolator(0.2f, 0.9f, 0.3f, 1.1f)
        val O1 = PathInterpolator(0.2f, 0.9f, 0.3f, 1.05f)
        val POP = PathInterpolator(0.3f, 1f, 0.3f, 1.35f)
        val PILL_L = F("pillL", { it.pillL }, { o, v -> o.pillL = v })
        val PILL_R = F("pillR", { it.pillR }, { o, v -> o.pillR = v })
        val PILL_T = F("pillT", { it.pillT }, { o, v -> o.pillT = v })
        val PILL_B = F("pillB", { it.pillB }, { o, v -> o.pillB = v })
        val SQUASH = F("squash", { it.squash }, { o, v -> o.squash = v })
        val BLACK_L = F("blackL", { it.blackL }, { o, v -> o.blackL = v })
        val BLACK_R = F("blackR", { it.blackR }, { o, v -> o.blackR = v })
        val BLACK_A = F("blackA", { it.blackA }, { o, v -> o.blackA = v })
        val BLACK_S = F("blackS", { it.blackS }, { o, v -> o.blackS = v })
        val BLACK_SY = F("blackSy", { it.blackSy }, { o, v -> o.blackSy = v })
        val GLYPH_A = F("glyphA", { it.glyphA }, { o, v -> o.glyphA = v })
        val GLYPH_S = F("glyphS", { it.glyphS }, { o, v -> o.glyphS = v })
        val V_ICON_A = F("vIconA", { it.vIconA }, { o, v -> o.vIconA = v })
        val V_ICON_S = F("vIconS", { it.vIconS }, { o, v -> o.vIconS = v })
        val V_WORDS_A = F("vWordsA", { it.vWordsA }, { o, v -> o.vWordsA = v })
        val V_WORDS_X = F("vWordsX", { it.vWordsX }, { o, v -> o.vWordsX = v })
        val K_ICON_A = F("kIconA", { it.kIconA }, { o, v -> o.kIconA = v })
        val K_ICON_S = F("kIconS", { it.kIconS }, { o, v -> o.kIconS = v })
        val K_WORDS_A = F("kWordsA", { it.kWordsA }, { o, v -> o.kWordsA = v })
        val K_WORDS_X = F("kWordsX", { it.kWordsX }, { o, v -> o.kWordsX = v })
    }
}
