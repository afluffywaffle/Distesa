package com.afluffywaffle.avosetta.eink

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
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
 *  - Affordance: the whole thing is drawn as ONE continuous rounded CAPSULE outline
 *    spanning the paging zone AND the bottom sliver cap ([capReservePx]) — so the
 *    sliver button reads as part of the rail, not an addition bolted onto it. The
 *    internal boundaries (prev/next split, and paging/sliver) are short CENTRED
 *    hairlines that float open-ended inside the capsule, never touching its walls.
 *    Chevrons sit in the paging halves: up = prev, down = next. [showZones] hides the
 *    whole capsule while keeping the zones live. The inert upper margin sits ABOVE the
 *    capsule so it's clearly not part of the control (nor page-render space).
 *
 * The sliver icon itself is drawn by the host's overlay button; this view only draws
 * the capsule, dividers, and chevrons behind it.
 *
 * E-ink: static vector strokes, no animation.
 *
 * @param capReservePx height (px) reserved at the BOTTOM for the sliver cap, 0 if the
 *   strip has no sliver (then the capsule bottom is just the paging zone).
 * @param capReserveTopPx height (px) carved out of the TOP of the EXISTING capsule for
 *   a second sliver cap, 0 if the strip has no top sliver. The capsule's own top edge
 *   ([ACTIVE_TOP]) never moves — this only shrinks the paging zone from within, same as
 *   [capReservePx] already does at the bottom; the strip keeps its current overall size.
 */
class EdgeNavView(
    context: Context,
    var showZones: Boolean = true,
    private val capReservePx: Int = 0,
    private val capReserveTopPx: Int = 0,
    private val onNext: (() -> Unit)? = null,
    private val onPrev: (() -> Unit)? = null,
) : View(context) {

    private val chevW = dp(9f)
    private val chevH = dp(13f)
    private val path = Path()

    // Chevrons — mid grey, never black. Inside the capsule they read as directional
    // controls, so a touch stronger than the old hint-grey to look deliberate.
    private val chevronPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(120, 120, 120)
        style = Paint.Style.STROKE
        strokeWidth = dp(2.5f)
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }

    // White "moat" behind each chevron (same knock-out technique as the globe/magnifier
    // sliver icon) so the chevron stays legible over dark page backgrounds.
    private val chevronMoatPaint = Paint(chevronPaint).apply {
        color = Color.WHITE
        strokeWidth = dp(2.5f) + dp(3f) // fat enough to clear content behind the stroke
    }

    // The capsule + dividers: a faint light-grey hairline — never a filled box (a fill
    // reads as a grey halo on e-ink); the outline alone says "control lives here".
    private val railPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(200, 200, 200)
        style = Paint.Style.STROKE
        strokeWidth = dp(1.5f)
        strokeCap = Paint.Cap.ROUND
    }
    private val railRect = RectF()
    private val railInsetX = dp(5f)
    private val railRadius = dp(12f)

    private var downX = 0f
    private var downY = 0f
    private val tapSlop = dp(12f)

    // NOTE: no transient per-tap press highlight. On this panel a physical EPD refresh
    // is only reliably driven by an invalidate() on the GeckoView's own compositor
    // surface (the page flip); this overlay View's own invalidate() does not reliably
    // kick the panel, so a momentary press-and-revert flash drops out on fast taps.
    // The static affordance below (capsule + chevrons + moat) is what marks the zones —
    // it rides the flip's refresh for free and is always visible. See MainActivity/Epd.

    init {
        setLayerType(LAYER_TYPE_SOFTWARE, null)
    }

    override fun onDraw(canvas: Canvas) {
        if (!showZones) return
        val w = width.toFloat()
        val h = height.toFloat()
        val cx = w / 2f
        val half = railPaint.strokeWidth / 2f

        // The capsule's own top edge NEVER moves — same ACTIVE_TOP as before, so the
        // strip keeps its current size regardless of a top sliver. A top cap just
        // carves capReserveTopPx off the INSIDE of the existing capsule, shrinking the
        // paging zone from within (mirrors how capReservePx already shrinks it at the
        // bottom).
        val railTop = ACTIVE_TOP * h
        val pagingTop = railTop + capReserveTopPx
        val pagingBottom = h - capReservePx // where the bottom sliver cap begins (or h if none)

        // One continuous capsule — all four corners rounded — spanning the strip's
        // existing bounds (top cap and bottom cap both live INSIDE it).
        railRect.set(railInsetX, railTop + half, w - railInsetX, h - half)
        canvas.drawRoundRect(railRect, railRadius, railRadius, railPaint)

        // prev/next split: a short centred hairline floating inside the paging zone —
        // centred between whatever slivers are present (top and/or bottom), so the
        // chevrons re-centre themselves in the space left over.
        val splitY = pagingTop + (pagingBottom - pagingTop) * SPLIT_FRAC
        drawDivider(canvas, cx, splitY)

        drawChevron(canvas, cx, pagingTop + (splitY - pagingTop) / 2f, down = false)
        drawChevron(canvas, cx, splitY + (pagingBottom - splitY) / 2f, down = true)

        if (capReserveTopPx > 0) {
            // top-cap/paging boundary: a floating centred hairline, open-ended.
            drawDivider(canvas, cx, pagingTop)
        }
        if (capReservePx > 0) {
            // paging/sliver boundary: another floating centred hairline, open-ended.
            drawDivider(canvas, cx, pagingBottom)
        }
    }

    /** A short, centred, open-ended hairline (never reaches the capsule walls). */
    private fun drawDivider(canvas: Canvas, cx: Float, y: Float) {
        val halfLen = (width - 2f * railInsetX) * DIVIDER_FRAC / 2f
        canvas.drawLine(cx - halfLen, y, cx + halfLen, y, railPaint)
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
        // Moat first (knock-out stroke), then the chevron ink on top — keeps it legible
        // over dark backgrounds, same technique as the sliver icon.
        canvas.drawPath(path, chevronMoatPaint)
        canvas.drawPath(path, chevronPaint)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (onNext == null && onPrev == null) return false
        val h = height.toFloat()
        val pagingTop = ACTIVE_TOP * h + capReserveTopPx
        val pagingBottom = h - capReservePx
        val splitY = pagingTop + (pagingBottom - pagingTop) * SPLIT_FRAC
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                // Inert above the paging zone (falls through to the page, or — if a top
                // cap is reserved — is where the top sliver overlay button sits) and
                // below it (the bottom sliver overlay button handles that region).
                if (event.y < pagingTop || event.y >= pagingBottom) return false
                downX = event.x
                downY = event.y
                return true
            }
            MotionEvent.ACTION_UP -> {
                val dx = event.x - downX
                val dy = event.y - downY
                if (dx * dx + dy * dy > tapSlop * tapSlop) return true // a swipe — ignore
                if (event.y >= splitY) onNext?.invoke() else onPrev?.invoke()
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

        /** Where the paging zone splits: below = NEXT, above = PREV (fraction of zone). */
        const val SPLIT_FRAC = 0.5f

        /** Divider hairline length as a fraction of the capsule's inner width. */
        const val DIVIDER_FRAC = 0.5f
    }
}
