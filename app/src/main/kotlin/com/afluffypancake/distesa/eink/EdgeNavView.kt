package com.afluffypancake.distesa.eink

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.util.TypedValue
import android.view.MotionEvent
import android.view.View

/**
 * A single edge paging strip. The host ([MainActivity]) places one or two of these
 * (LEFT/RIGHT/BOTH) either inside real inset margins (default) or overlaid on the
 * page. Mirrors layuv's reader EdgeNavView split model (prev/next per strip) but
 * ADAPTED for a browser:
 *
 *  - BOTTOM-WEIGHTED ergonomics: the flip targets live in the LOWER part of the
 *    strip where a thumb rests. The active zone is the lower ~60% ([ACTIVE_TOP]..1);
 *    the very bottom is NEXT/advance and PREV sits just above it ([SPLIT]). The
 *    upper ~40% is INACTIVE — taps there fall through to the page.
 *  - ALWAYS flips and consumes the tap, so a tap over a link (overlay mode) turns
 *    the page instead of opening the link (native interception = the same effect
 *    as a capture-phase preventDefault, but more reliable).
 *  - Affordance: faint LIGHT chevrons (never dark) placed low — up = prev,
 *    down = next. [showZones] hides them while keeping the zones live.
 *
 * E-ink: static vector strokes, no animation.
 */
class EdgeNavView(
    context: Context,
    var showZones: Boolean = true,
    private val onNext: (() -> Unit)? = null,
    private val onPrev: (() -> Unit)? = null,
) : View(context) {

    private val chevW = dp(9f)
    private val chevH = dp(13f)
    private val path = Path()

    // Faint LIGHT chevrons — subtle grey, never dark, so they read as a quiet hint
    // on the white e-ink page / light margin.
    private val chevronPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(150, 150, 150)
        style = Paint.Style.STROKE
        strokeWidth = dp(2.5f)
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }

    private var downX = 0f
    private var downY = 0f
    private val tapSlop = dp(12f)

    init {
        setLayerType(LAYER_TYPE_SOFTWARE, null)
    }

    override fun onDraw(canvas: Canvas) {
        if (!showZones) return
        val w = width.toFloat()
        val h = height.toFloat()
        val cx = w / 2f
        drawChevron(canvas, cx, h * 0.55f, down = false) // prev (upper of active zone)
        drawChevron(canvas, cx, h * 0.85f, down = true)  // next (very bottom)
    }

    private fun drawChevron(canvas: Canvas, cx: Float, cy: Float, down: Boolean) {
        val w = chevW
        val h = chevH / 2f
        path.rewind()
        if (down) {
            path.moveTo(cx - w, cy - h); path.lineTo(cx, cy + h); path.lineTo(cx + w, cy - h)
        } else {
            path.moveTo(cx - w, cy + h); path.lineTo(cx, cy - h); path.lineTo(cx + w, cy + h)
        }
        canvas.drawPath(path, chevronPaint)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (onNext == null && onPrev == null) return false
        val h = height.toFloat()
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                if (event.y < ACTIVE_TOP * h) return false // upper 40% falls through to page
                downX = event.x
                downY = event.y
                return true
            }
            MotionEvent.ACTION_UP -> {
                val dx = event.x - downX
                val dy = event.y - downY
                if (dx * dx + dy * dy > tapSlop * tapSlop) return true // a swipe — ignore
                if (event.y >= SPLIT * h) onNext?.invoke() else onPrev?.invoke()
                return true
            }
        }
        return false
    }

    private fun dp(value: Float): Float =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value, resources.displayMetrics)

    companion object {
        /** Above this fraction of height the strip is inactive (taps fall through). */
        const val ACTIVE_TOP = 0.40f

        /** Active zone split: below = NEXT (bottom), above = PREV. */
        const val SPLIT = 0.70f
    }
}
