package dev.alder

import android.content.Context
import android.graphics.Outline
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.InsetDrawable
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.ViewOutlineProvider
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView

/**
 * ChadFlix fork: the home page's look (MainActivity's top bar, rows, cards and info line), in one file so merging
 * upstream Alder stays easy. Only the home page uses these; Screen.kt's shared tokens are left alone. Sizes are dp,
 * ChadFlix's 1080p pixels halved (220 x 330 px posters are 110 x 165 dp).
 */
internal object HomeStyle {
    const val BLACK = 0xFF000000.toInt()
    const val WHITE = 0xFFFFFFFF.toInt()
    const val PLOT = 0xFFCFCFCF.toInt()
    const val NOTE = 0xFFDDDDDD.toInt()
    const val PLACEHOLDER = 0xFF1A1A1A.toInt()
    const val AVATAR = 0xFF2A2A2A.toInt()
    const val OPEN_TAB = 0x33FFFFFF // the open page's pill while the remote is on another item in the top bar
    const val RED = 0xFFE50914.toInt()
    const val TRACK = 0xFF4A4A4A.toInt()

    const val CARD_W = 110
    const val CARD_H = 165
    const val CARD_GAP = 8
    const val CARD_RADIUS = 3.5f
    const val RING = 2.5f // the focused card's white border
    const val CONTINUE_UNDER = 28 // under a Continue Watching poster: the red bar, then the time left
    const val ROWS_TOP = 70 // the first row's title, under the top bar
    const val INFO_GAP = 14 // between the info line and the next row's title
    const val ROW_TITLE = 17f // sp
    const val INFO = 13.5f // sp, the "Genre • Year • Runtime • Rating" line
    const val NOTE_SP = 12f
    const val ROW_DIM = 0.62f // rows the remote isn't in sit at about 38% brightness
    const val SEP = "  •  "

    private val small = setOf("a", "an", "and", "as", "at", "by", "for", "in", "of", "on", "or", "the", "to", "with")

    /** "Continue watching" → "Continue Watching", "Movies for you" → "Movies for You"; words already capitalised stay. */
    fun titleCase(s: String): String = s.split(" ").mapIndexed { i, w ->
        if (i > 0 && w.lowercase() in small) w.lowercase() else w.replaceFirstChar { it.uppercaseChar() }
    }.joinToString(" ")

    private fun px(c: Context, dp: Float) = dp * c.resources.displayMetrics.density

    /** A top bar pill: white for the focused item (black words on it), a faint one for the open page. */
    fun pill(color: Int) = GradientDrawable().apply { setColor(color); cornerRadius = 999f }

    /**
     * A home poster card: the poster sits on top, rounded on its own, so the space under it ([under] px, Continue
     * Watching's bar and time left) is outside the poster's rounding and placeholder.
     */
    fun posterCard(card: FrameLayout, face: View, under: Int) {
        val c = card.context
        val r = px(c, CARD_RADIUS)
        card.background = InsetDrawable(GradientDrawable().apply { setColor(PLACEHOLDER); cornerRadius = r }, 0, 0, 0, under)
        card.clipToOutline = false
        face.outlineProvider = object : ViewOutlineProvider() {
            override fun getOutline(view: View, outline: Outline) = outline.setRoundRect(0, 0, view.width, view.height, r)
        }
        face.clipToOutline = true
    }

    /** The focused card's white border, drawn over the poster. */
    fun ring(c: Context) = View(c).apply {
        background = GradientDrawable().apply {
            cornerRadius = px(c, CARD_RADIUS)
            setStroke(px(c, RING).toInt().coerceAtLeast(2), WHITE)
        }
        visibility = View.GONE
    }

    /** Under a Continue Watching poster: a red watched-so-far bar on a grey track, then "1h 10m left" or "S2 E4". */
    fun under(screen: Screen, card: FrameLayout, posterH: Int, progress: Float, note: String?) {
        val c = card.context
        if (progress > 0f) card.addView(LinearLayout(c).apply {
            val p = progress.coerceIn(0f, 1f)
            setBackgroundColor(TRACK)
            addView(View(c).apply { setBackgroundColor(RED) }, LinearLayout.LayoutParams(0, -1, p))
            addView(View(c), LinearLayout.LayoutParams(0, -1, 1f - p))
        }, FrameLayout.LayoutParams(-1, px(c, RING).toInt().coerceAtLeast(2), Gravity.TOP).apply { topMargin = posterH + px(c, 5f).toInt() })
        if (!note.isNullOrEmpty()) card.addView(screen.text(note, NOTE_SP, bold = true, color = NOTE).apply {
            setSingleLine()
            ellipsize = TextUtils.TruncateAt.END
            includeFontPadding = false
        }, FrameLayout.LayoutParams(-1, -2, Gravity.TOP).apply {
            topMargin = posterH + px(c, 11f).toInt()
            leftMargin = px(c, 2f).toInt()
        })
    }

    /** The IMDb and Letterboxd tags in white and grey instead of Alder's warm tungsten. */
    fun plainRatings(row: LinearLayout) {
        for (i in 0 until row.childCount) (row.getChildAt(i) as? TextView)?.let { t ->
            t.setTextColor(WHITE)
            if (t.background != null) t.background = GradientDrawable().apply {
                cornerRadius = px(t.context, 3f)
                setStroke(px(t.context, 1f).toInt().coerceAtLeast(1), 0x80FFFFFF.toInt())
            }
        }
    }
}
