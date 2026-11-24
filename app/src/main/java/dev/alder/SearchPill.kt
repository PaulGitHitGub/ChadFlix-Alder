package dev.alder

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.AnimatorSet
import android.animation.Keyframe
import android.animation.ObjectAnimator
import android.animation.PropertyValuesHolder
import android.animation.TimeInterpolator
import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
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

/** Search's intent extra: how far (ms) Home's copy of the pill's move got before Search took over, to carry on from. */
internal const val PILL_PLAYED = "pillPlayed"

/** Animators run (Remove animations off); before API 26 they always do, ending at once when the scale is 0. */
internal fun animationsOn() = Build.VERSION.SDK_INT < 26 || ValueAnimator.areAnimatorsEnabled()

/** Where Search's capsule last was (screen px: left, top, right, bottom), for Home to throw its pill there; null: never yet. */
internal fun savedCapsule(c: Context): IntArray? = c.getSharedPreferences(PILL_PREFS, Context.MODE_PRIVATE).getString("capsule", null)
    ?.split(',')?.mapNotNull { it.toIntOrNull() }?.takeIf { it.size == 4 }?.toIntArray()

private const val PILL_PREFS = "searchPill"

/**
 * The add-ons' catalog types Search's top bar last had (they add Live and per-type tabs), so its first layout already has
 * them and the capsule sits where Home threw its pill; [save] keeps a newly fetched list.
 */
internal fun searchTabTypes(c: Context, save: List<String>? = null): List<String> {
    val prefs = c.getSharedPreferences(PILL_PREFS, Context.MODE_PRIVATE)
    if (save != null) { if (prefs.getString("types", null) != save.joinToString("\n")) prefs.edit().putString("types", save.joinToString("\n")).apply(); return save }
    return prefs.getString("types", null)?.split("\n")?.filter { it.isNotEmpty() }.orEmpty()
}

/**
 * The Search page's top-bar pill, the designer's variant 1 "Slingshot" (pill-anim-20261124/README.md): one view, behind
 * the pages' names, draws the white pill, the black focus pill and Keyboard | Voice from about 25 floats, each moved by
 * an [ObjectAnimator] whose setter only invalidates (nothing is laid out). The real Voice and Keyboard views stay in the
 * invisible [capsule] as focus targets and give the places, measured once per move ([measure]), not per frame.
 *
 * - Opening (from a page's name, from Home's pill [arrive]s at, or in place from the magnifier): the left edge is thrown
 *   to the capsule's left end with an overshoot, the right edge follows 50 ms later, the pill thins as it stretches, the
 *   magnifier shrinks away under it, and the black pill pops out of the focused item's outer end (Voice's right, next to
 *   the names) and grows over it, the icons and words popping in after it (400 ms).
 * - Closing onto a page's name: the mirror, right edge first (300 ms). Between names the pill glides, its leading edge
 *   first. Voice ↔ Keyboard: the black pill's leading edge first, 30 ms ahead, with a small squash (190 ms).
 * - Every move drives every float to its rest from the live value (a float already there costs nothing), so a new move
 *   cancels the running one and carries on from where it is. With animations off every change lands at once.
 */
internal class SearchPill(
    context: Context,
    private val bar: View, // the top bar: focus in it but not on a name or the capsule (the avatar, the gear) dims the capsule
    private val capsule: View?, // Voice and Keyboard, invisible, for focus and places; null on Home ([capsuleOnScreen])
    private val voice: ViewGroup?,
    private val keyboard: ViewGroup?,
    private val slot: View, // the magnifier tab's room
    private val avatar: View, // the pill never draws over it, even overshooting
    private val names: () -> List<TextView>, // the pages' names, coloured by whether the pill covers them
) : View(context) {
    private enum class Mode { OPEN, NAME }
    private var mode = Mode.OPEN
    private var on: View? = null // the capsule item under the black pill, or the name under the white pill
    private var running: AnimatorSet? = null
    private val parts = mutableListOf<Animator>()
    private var animate = true
    private var started = false // nothing is drawn or moved before [arrive]
    var listening = false // Voice's mic goes red
    /** Home: no capsule of its own; the pill opens towards where Search's was ([savedCapsule]), Search carrying on. */
    var capsuleOnScreen: IntArray? = null
    // The state: the white pill, its squash; the black pill, its alpha, scale and squash; the magnifier; each item's
    // icon (alpha, scale) and words (alpha, sideways offset); and whether the capsule is lit (white) or faint.
    private var pillL = 0f; private var pillR = 0f; private var pillT = 0f; private var pillB = 0f; private var squash = 1f
    private var blackL = 0f; private var blackR = 0f; private var blackT = 0f; private var blackB = 0f
    private var blackA = 0f; private var blackS = 1f; private var blackSy = 1f
    private var glyphA = 0f; private var glyphS = 1f
    private var vIconA = 1f; private var vIconS = 1f; private var vWordsA = 1f; private var vWordsX = 0f
    private var kIconA = 1f; private var kIconS = 1f; private var kWordsA = 1f; private var kWordsX = 0f
    private var lit = true

    private class F(name: String, val get: (SearchPill) -> Float, val put: (SearchPill, Float) -> Unit) :
        Property<SearchPill, Float>(Float::class.javaObjectType, name) {
        override fun get(o: SearchPill) = get.invoke(o)
        override fun set(o: SearchPill, v: Float) { put(o, v); o.invalidate() }
    }

    // ---------- places, measured once per move ----------

    private val here = IntArray(2)
    private val there = IntArray(2)
    private val slotR = RectF(); private val capR = RectF(); private val voiceR = RectF(); private val keyR = RectF()
    private var floor = 0f // the avatar's right edge and a gap: the pill's left edge is drawn no further left
    private var nameViews = emptyList<TextView>()
    private var nameRects = emptyList<RectF>()

    /** [v]'s bounds in this view, into [out]. */
    private fun rectOf(v: View, out: RectF = RectF()): RectF {
        getLocationInWindow(here)
        v.getLocationInWindow(there)
        val x = (there[0] - here[0]).toFloat()
        val y = (there[1] - here[1]).toFloat()
        out.set(x, y, x + v.width, y + v.height)
        return out
    }

    private fun measure() {
        rectOf(slot, slotR)
        keyboard?.let { rectOf(it, keyR) }
        voice?.let { rectOf(it, voiceR) }
        val cap = capsule
        if (cap != null) { rectOf(cap, capR); remember(cap) }
        else capsuleOnScreen?.let { s ->
            getLocationOnScreen(here)
            capR.set(s[0] - here[0].toFloat(), s[1] - here[1].toFloat(), s[2] - here[0].toFloat(), s[3] - here[1].toFloat())
        }
        floor = rectOf(avatar, box).right + dp(4f)
        nameViews = names()
        nameRects = nameViews.map { rectOf(it) }
        ((keyboard ?: voice)?.getChildAt(1) as? TextView)?.let { words.textSize = it.textSize; words.typeface = it.typeface }
    }

    /** Search's capsule on screen, kept for Home's next start ([savedCapsule]) when it moved. */
    private fun remember(cap: View) {
        if (cap.width == 0) return
        cap.getLocationOnScreen(there)
        val now = "${there[0]},${there[1]},${there[0] + cap.width},${there[1] + cap.height}"
        val prefs = context.getSharedPreferences(PILL_PREFS, Context.MODE_PRIVATE)
        if (prefs.getString("capsule", null) != now) prefs.edit().putString("capsule", now).apply()
    }

    private fun rectFor(v: View) = when (v) { voice -> voiceR; keyboard -> keyR; else -> rectOf(v) }

    // ---------- drawing ----------

    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val words = TextPaint(Paint.ANTI_ALIAS_FLAG)
    private val box = RectF()
    /** An icon with its own tint, set only when it changes (each pass has its own, so they don't alternate). */
    private inner class Ink(res: Int) {
        val d: Drawable = context.getDrawable(res)!!.mutate()
        private var color = 0
        fun tint(c: Int) = d.also { if (c != color) { color = c; it.setTint(c) } }
    }
    private val micOn = Ink(R.drawable.ic_mic); private val micUnder = Ink(R.drawable.ic_mic)
    private val keysOn = Ink(R.drawable.ic_search); private val keysUnder = Ink(R.drawable.ic_search)
    private val glyph = Ink(R.drawable.ic_search)

    override fun onDraw(canvas: Canvas) {
        if (!started) return
        recolorNames()
        // The white pill, its top and bottom drawn in while it stretches, never further left than the avatar.
        val left = maxOf(pillL, minOf(floor, pillR))
        if (pillR - left > 1f) {
            val inset = (1f - squash) * (pillB - pillT) / 2f
            box.set(left, pillT + inset, pillR, pillB - inset)
            fill.color = if (lit) HomeStyle.WHITE else HomeStyle.OPEN_TAB
            canvas.drawRoundRect(box, box.height() / 2f, box.height() / 2f, fill)
            drawNames(canvas)
        }
        // The magnifier, black while the white pill covers it.
        if (glyphA > 0.01f) {
            val size = resources.displayMetrics.density * GLYPH_DP
            val covered = lit && slotR.centerX() in pillL..pillR
            icon(canvas, glyph.tint(if (covered) HomeStyle.BLACK else HomeStyle.WHITE), slotR.centerX(), slotR.centerY(), size, size, glyphA, glyphS)
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
        val base = if (lit) HomeStyle.BLACK else HomeStyle.WHITE
        items(canvas, base, micOn, keysOn)
        if (blackA > 0.01f) {
            canvas.save()
            canvas.clipRect(box)
            items(canvas, HomeStyle.WHITE, micUnder, keysUnder)
            canvas.restore()
        }
    }

    private fun items(canvas: Canvas, color: Int, mic: Ink, keys: Ink) {
        voice?.let { item(canvas, it, voiceR, mic.tint(if (listening) HomeStyle.LIST_RED else color), color, vIconA, vIconS, vWordsA, vWordsX) }
        keyboard?.let { item(canvas, it, keyR, keys.tint(color), color, kIconA, kIconS, kWordsA, kWordsX) }
    }

    private fun item(canvas: Canvas, item: ViewGroup, r: RectF, icon: Drawable, color: Int,
                     iconA: Float, iconS: Float, wordsA: Float, wordsX: Float) {
        val image = item.getChildAt(0) as ImageView
        val label = item.getChildAt(1) as TextView
        if (iconA > 0.01f) icon(canvas, icon, r.left + image.left + image.width / 2f, r.top + image.top + image.height / 2f,
            image.width.toFloat(), image.height.toFloat(), iconA, iconS)
        if (wordsA > 0.01f) {
            words.color = color
            words.alpha = (wordsA * 255).toInt()
            val text = label.text
            canvas.drawText(text, 0, text.length, r.left + label.left + label.paddingLeft + wordsX, r.top + label.top + label.baseline, words)
        }
    }

    /** [d] at its own size, scaled about its centre by the canvas (so a popping icon isn't drawn again at every size). */
    private fun icon(canvas: Canvas, d: Drawable, cx: Float, cy: Float, w: Float, h: Float, a: Float, s: Float) {
        d.setBounds(0, 0, w.toInt(), h.toInt())
        d.alpha = (a * 255).toInt()
        canvas.save()
        canvas.translate(cx, cy)
        canvas.scale(s, s)
        canvas.translate(-w / 2f, -h / 2f)
        d.draw(canvas)
        canvas.restore()
    }

    /**
     * A page's name the lit pill reaches, even in part, is drawn here instead ([drawNames]): its own words go clear (set only
     * on a change); the others are white on the page.
     */
    private fun recolorNames() {
        for (i in nameViews.indices) {
            val r = nameRects.getOrNull(i) ?: continue
            val color = if (covers(r)) Color.TRANSPARENT else HomeStyle.WHITE
            val t = nameViews[i]
            if (t.currentTextColor != color) t.setTextColor(color)
        }
    }

    private fun covers(r: RectF) = lit && pillR - pillL > 1f && pillR > r.left && pillL < r.right

    /**
     * The names the pill reaches, twice like Voice and Keyboard: white, then black where the white pill is ([box]), so
     * a name half under the pill reads in both colours and never vanishes white on white.
     */
    private fun drawNames(canvas: Canvas) {
        for (i in nameViews.indices) {
            val r = nameRects.getOrNull(i) ?: continue
            if (!covers(r)) continue
            val t = nameViews[i]
            val layout = t.layout ?: continue
            val paint = layout.paint
            val own = paint.color
            canvas.save()
            canvas.translate(r.left + t.totalPaddingLeft, r.top + t.totalPaddingTop)
            paint.color = HomeStyle.WHITE
            layout.draw(canvas)
            canvas.restore()
            canvas.save()
            canvas.clipRect(box)
            canvas.translate(r.left + t.totalPaddingLeft, r.top + t.totalPaddingTop)
            paint.color = HomeStyle.BLACK
            layout.draw(canvas)
            canvas.restore()
            paint.color = own
        }
    }

    // ---------- moving ----------

    private fun begin() {
        running?.cancel()
        running = null
        parts.clear()
        animate = animationsOn()
        measure()
    }

    private fun play() {
        if (parts.isEmpty()) return invalidate()
        val set = AnimatorSet().apply { playTogether(parts.toList()) }
        parts.clear()
        // The bar moved meanwhile (the add-ons' page names arrived): settle on the new places once it lands.
        set.addListener(object : AnimatorListenerAdapter() {
            private var cancelled = false
            override fun onAnimationCancel(animation: Animator) { cancelled = true }
            override fun onAnimationEnd(animation: Animator) { if (!cancelled && running === set) { running = null; relayout() } }
        })
        running = set
        // Carrying on from Home's pill: seeked before it starts, so the very first frame already shows that moment
        // (a seek on a running set only shows from the next frame).
        val seek = pendingSeek
        pendingSeek = 0L
        if (seek > 0 && Build.VERSION.SDK_INT >= 26) set.currentPlayTime = seek.coerceIn(0L, set.totalDuration - 1)
        set.start()
    }
    private var pendingSeek = 0L

    /** The page is going: nothing runs on after it. */
    fun stop() { running?.cancel(); running = null }

    /**
     * Home is starting Search: its pill holds still where it was last drawn (the shared main thread is about to be busy
     * opening Search) and says how far into the move that is, for Search to carry on from exactly there.
     */
    fun freeze(): Long {
        val set = running ?: return 0L
        val played = if (Build.VERSION.SDK_INT >= 24) set.currentPlayTime else 0L
        set.pause()
        return played
    }

    /** Home again: the pill it started is gone (Search took over, or never opened). */
    fun reset() { stop(); started = false; invalidate() }

    /** [p] from [from] to [to] after [delay] ms over [ms]; with animations off, or already there, it lands at once. */
    private fun tween(p: F, from: Float, to: Float, delay: Long, ms: Long, curve: TimeInterpolator) {
        if (!animate || from == to) return p.put(this, to)
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
        parts += ObjectAnimator.ofPropertyValuesHolder(this, values).apply { startDelay = delay; duration = down + up; interpolator = LIN }
    }

    /**
     * The page opens, called as the bar is first laid out (before its first frame is drawn): the pill starts on Home's
     * ([fromOnScreen], the open or focused page's name there, in screen px) or on the magnifier, and opens into the
     * capsule with the black pill on [focus]. [played] (ms): how far Home's own copy of the move had got when it froze
     * ([freeze]), so this one carries on from what Home last drew instead of starting again.
     */
    fun arrive(fromOnScreen: IntArray?, focus: View?, played: Long = 0L) {
        started = true
        measure()
        if (fromOnScreen != null && fromOnScreen.size == 4) {
            getLocationOnScreen(here)
            pillL = fromOnScreen[0] - here[0].toFloat(); pillT = fromOnScreen[1] - here[1].toFloat()
            pillR = fromOnScreen[2] - here[0].toFloat(); pillB = fromOnScreen[3] - here[1].toFloat()
        } else { pillL = slotR.left; pillR = slotR.right; pillT = slotR.top; pillB = slotR.bottom }
        glyphA = 1f; glyphS = 1f
        blackA = 0f
        vIconA = 0f; vWordsA = 0f; kIconA = 0f; kWordsA = 0f
        mode = Mode.NAME
        on = null
        pendingSeek = played
        open(focus ?: voice ?: keyboard)
        pendingSeek = 0L
    }

    /** The remote moved to [v]: the pill follows it. */
    fun focusOn(v: View?) {
        if (!started) return
        when {
            v != null && (v === voice || v === keyboard) -> { lit = true; open(v) }
            v is TextView && names().any { it === v } -> { lit = true; toName(v) }
            v != null && isIn(v, bar) -> { lit = false; open(null) } // the avatar or the gear
            else -> { lit = true; open(null) }
        }
    }

    private fun isIn(v: View, parent: View): Boolean = generateSequence(v) { it.parent as? View }.any { it === parent }

    /** The capsule, the black pill on [item] (null: none, the remote is below). */
    private fun open(item: View?) {
        if (mode == Mode.OPEN) {
            if (item === on) return invalidate()
            on = item
            begin()
            var popped = false
            var moving = false
            when {
                item == null -> go(BLACK_A, 0f, 0, 90, LIN)
                blackA < 0.05f -> {
                    rectFor(item).let { blackL = it.left; blackR = it.right; blackT = it.top; blackB = it.bottom }
                    go(BLACK_A, 1f, 0, 90, OUT)
                    tween(BLACK_S, 0.55f, 1f, 0, 200, POP)
                    popped = true
                }
                else -> { focusMove(rectFor(item)); moving = true } // from the other item, or a black pill still fading
            }
            // Whatever an interrupted move left half done comes to rest too (already there: nothing runs).
            rest(blackS = !popped, blackSy = !moving)
            return play()
        }
        // From a page's name (or Home's pill): the slingshot.
        mode = Mode.OPEN
        on = item
        begin()
        go(PILL_L, capR.left, 0, 300, O4)
        go(PILL_R, capR.right, 50, 250, FOS)
        go(PILL_T, capR.top, 0, 250, FOS)
        go(PILL_B, capR.bottom, 0, 250, FOS)
        dip(SQUASH, 0.92f, 0, 120, 240)
        go(GLYPH_A, 0f, 90, 110, LIN)
        go(GLYPH_S, 0.6f, 90, 110, OUT)
        if (item != null) {
            val r = rectFor(item)
            blackT = r.top; blackB = r.bottom
            // A circle at the item's outer end (Voice's right, next to the names the pill came from), growing over it.
            if (blackA < 0.05f) {
                if (r.centerX() > capR.centerX()) { blackR = r.right; blackL = r.right - r.height() }
                else { blackL = r.left; blackR = r.left + r.height() }
            }
            go(BLACK_L, r.left, 150, 240, O2)
            go(BLACK_R, r.right, 150, 240, O2)
            tween(BLACK_A, blackA, 1f, 150, 1, LIN)
            tween(BLACK_S, if (blackA < 0.05f) 0.55f else blackS, 1f, 150, 200, POP)
        } else go(BLACK_A, 0f, 0, 80, LIN)
        go(BLACK_SY, 1f, 0, 120, OUT)
        popIn(V_ICON_A, V_ICON_S, V_WORDS_A, V_WORDS_X, shift(voiceR), 200, 230)
        popIn(K_ICON_A, K_ICON_S, K_WORDS_A, K_WORDS_X, shift(keyR), 230, 260)
        play()
    }

    /** Words slide in from their own side: the left item's from the left (5 dp), the right one's from the right (7 dp). */
    private fun shift(r: RectF) = if (r.centerX() < capR.centerX()) -dp(5f) else dp(7f)

    /**
     * The open capsule's rest, from wherever an interrupted move left things (each float already there costs nothing);
     * [blackS] and [blackSy] unless this move animates them itself.
     */
    private fun rest(blackS: Boolean, blackSy: Boolean) {
        go(PILL_L, capR.left, 0, 200, FOS)
        go(PILL_R, capR.right, 0, 200, FOS)
        go(PILL_T, capR.top, 0, 200, FOS)
        go(PILL_B, capR.bottom, 0, 200, FOS)
        go(SQUASH, 1f, 0, 200, FOS)
        go(GLYPH_A, 0f, 0, 110, LIN)
        go(GLYPH_S, 0.6f, 0, 110, OUT)
        if (blackS) go(BLACK_S, 1f, 0, 200, POP)
        if (blackSy) go(BLACK_SY, 1f, 0, 120, OUT)
        popIn(V_ICON_A, V_ICON_S, V_WORDS_A, V_WORDS_X, shift(voiceR), 0, 30)
        popIn(K_ICON_A, K_ICON_S, K_WORDS_A, K_WORDS_X, shift(keyR), 30, 60)
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
        blackT = to.top; blackB = to.bottom
        go(if (right) BLACK_R else BLACK_L, if (right) to.right else to.left, 0, 150, O1)
        go(if (right) BLACK_L else BLACK_R, if (right) to.left else to.right, 30, 150, FOS)
        go(BLACK_A, 1f, 0, 60, OUT)
        dip(BLACK_SY, 0.94f, 0, 70, 120)
    }

    /** Onto a page's name: from the capsule the mirrored slingshot (right edge first); between names a glide. */
    private fun toName(name: View) {
        if (mode == Mode.NAME && on === name) return invalidate()
        begin()
        val t = rectOf(name)
        if (mode == Mode.OPEN) {
            go(PILL_R, t.right, 0, 240, O4)
            go(PILL_L, t.left, 40, 220, FOS)
            go(GLYPH_A, 1f, 110, 60, LIN)
            go(GLYPH_S, 1f, 110, 200, POP)
        } else {
            val right = t.centerX() > (pillL + pillR) / 2f
            go(if (right) PILL_R else PILL_L, if (right) t.right else t.left, 0, 200, FOS)
            go(if (right) PILL_L else PILL_R, if (right) t.left else t.right, 30, 200, FOS)
            go(GLYPH_A, 1f, 0, 60, LIN)
            go(GLYPH_S, 1f, 0, 200, POP)
        }
        // Everything else to a name's rest: the capsule's content and the black pill gone, no squash.
        go(PILL_T, t.top, 0, 220, FOS)
        go(PILL_B, t.bottom, 0, 220, FOS)
        go(SQUASH, 1f, 0, 200, FOS)
        go(V_ICON_A, 0f, 0, 70, LIN); go(V_WORDS_A, 0f, 0, 70, LIN)
        go(K_ICON_A, 0f, 0, 70, LIN); go(K_WORDS_A, 0f, 0, 70, LIN)
        go(BLACK_A, 0f, 0, 80, LIN)
        mode = Mode.NAME
        on = name
        play()
    }

    /** The places moved (the capsule widened for "Listening…", the names changed): at rest, the pill follows at once. */
    fun relayout() {
        if (!started) return
        if (running?.isRunning == true) { // mid-move: only the names (rebuilt meanwhile) get their new places
            nameViews = names()
            nameRects = nameViews.map { rectOf(it) }
            return invalidate()
        }
        measure()
        val target = on
        if (mode == Mode.OPEN) {
            pillL = capR.left; pillR = capR.right; pillT = capR.top; pillB = capR.bottom
            if (target != null) rectFor(target).let { blackL = it.left; blackR = it.right; blackT = it.top; blackB = it.bottom }
        } else if (target != null && target.isAttachedToWindow) rectOf(target).let { pillL = it.left; pillR = it.right; pillT = it.top; pillB = it.bottom }
        invalidate()
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
