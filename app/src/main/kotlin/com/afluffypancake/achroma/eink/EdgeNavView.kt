package com.afluffypancake.achroma.eink

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.util.TypedValue
import android.view.MotionEvent
import android.view.View

/**
 * Edge-navigation affordance, factored out as a standalone [View]. Tall strips run
 * down the left and right edges; a faint dotted rail marks each strip's inner edge
 * (hinting the whole column is tappable) and a midline splits the strip top
 * (= next) / bottom (= prev), with a faint chevron in each half — top points right
 * (forward), bottom points left (back). Strip width is [NAV_STRIP_DP].
 *
 * A tap inside a strip turns the page via [onNext]/[onPrev]; taps elsewhere fall
 * through (returns false on DOWN), and a drag is ignored so a swipe handled by the
 * host isn't double-counted.
 *
 * E-ink: static vector strokes, no animation. The host invalidates on page change.
 *
 * TODO(Phase 1): This was ported from layuv's reader, where it overlaid the
 * ReaderView and its theme (ReaderTheme colours/typography, plus a "diagram"
 * teaching mode used by Help). That reader-specific coupling — ReaderTheme, the
 * diagram/label rendering, and the runtime handedness "side" toggle's theme
 * paints — has been removed so this compiles standalone with plain Paint/Color.
 * Phase 1 wires this over the GeckoView surface to drive scroll/navigation and
 * feeds page changes into [Epd].
 */
class EdgeNavView(
    context: Context,
    side: String = "both",
    private val onNext: (() -> Unit)? = null,
    private val onPrev: (() -> Unit)? = null,
) : View(context) {

    /** Which edge(s) draw + tap: "both" (default), "left", or "right". */
    private var navSide: String = side
    private fun leftActive() = navSide != "right"
    private fun rightActive() = navSide != "left"

    /** Switch the active edge at runtime (e.g. a handedness toggle). */
    fun setSide(side: String) {
        if (side == navSide) return
        navSide = side
        invalidate()
    }

    private val stripWidth = dp(NAV_STRIP_DP)
    private val chevronHalfW = dp(8f)
    private val chevronHalfH = dp(12f)
    private val path = Path()

    // Faint divider between the top (next) and bottom (prev) zones, and the
    // dotted inner-edge rail. On e-ink, greyscale strokes only.
    private val hairlinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.BLACK
        alpha = 90
        style = Paint.Style.STROKE
        strokeWidth = dp(1.5f)
    }
    private val chevronPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.BLACK
        alpha = 55
        style = Paint.Style.STROKE
        strokeWidth = dp(2.5f)
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    // A faint, finely dotted line at each strip's inner edge (and the midline).
    // Hints the WHOLE column is a tap zone, while staying quiet against content.
    private val lanePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.BLACK
        alpha = 40
        style = Paint.Style.STROKE
        strokeWidth = dp(1f)
        pathEffect = android.graphics.DashPathEffect(floatArrayOf(dp(1f), dp(4f)), 0f)
    }

    private var downX = 0f
    private var downY = 0f
    private val tapSlop = dp(12f)

    init {
        // Software layer so the dashed lane line renders reliably.
        setLayerType(LAYER_TYPE_SOFTWARE, null)
    }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        // Faint dotted rails marking the inner edge of each active tap strip.
        if (leftActive()) canvas.drawLine(stripWidth, 0f, stripWidth, h, lanePaint)
        if (rightActive()) canvas.drawLine(w - stripWidth, 0f, w - stripWidth, h, lanePaint)
        if (leftActive()) drawStrip(canvas, 0f, h)
        if (rightActive()) drawStrip(canvas, w - stripWidth, h)
    }

    private fun drawStrip(canvas: Canvas, left: Float, h: Float) {
        val cx = left + stripWidth / 2f
        val midY = h / 2f
        // Midline splitting top (next) / bottom (prev); shares the rail's faint
        // dotted style and meets it, so it reads as anchored rather than floating.
        canvas.drawLine(left, midY, left + stripWidth, midY, lanePaint)
        drawChevron(canvas, cx, h / 4f, pointRight = true)       // top = next
        drawChevron(canvas, cx, h * 3f / 4f, pointRight = false) // bottom = prev
    }

    private fun drawChevron(canvas: Canvas, cx: Float, cy: Float, pointRight: Boolean) {
        val w = chevronHalfW
        val h = chevronHalfH
        path.rewind()
        if (pointRight) {
            path.moveTo(cx - w, cy - h); path.lineTo(cx + w, cy); path.lineTo(cx - w, cy + h)
        } else {
            path.moveTo(cx + w, cy - h); path.lineTo(cx - w, cy); path.lineTo(cx + w, cy + h)
        }
        canvas.drawPath(path, chevronPaint)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (onNext == null && onPrev == null) return false // non-interactive
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                val inStrip = (leftActive() && event.x < stripWidth) ||
                    (rightActive() && event.x > width - stripWidth)
                if (!inStrip) return false // let center taps/scrolls fall through
                downX = event.x
                downY = event.y
                return true
            }
            MotionEvent.ACTION_UP -> {
                val dx = event.x - downX
                val dy = event.y - downY
                if (dx * dx + dy * dy > tapSlop * tapSlop) return true // a swipe — host handles it
                if (event.y < height / 2f) onNext?.invoke() else onPrev?.invoke()
                return true
            }
        }
        return false
    }

    private fun dp(value: Float): Float =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value, resources.displayMetrics)

    companion object {
        /** Strip width in dp. */
        const val NAV_STRIP_DP = 80f
    }
}
