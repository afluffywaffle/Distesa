package com.afluffywaffle.avosetta.eink

import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.RectF
import android.graphics.drawable.Drawable

/**
 * The address-bar sliver icon: a globe (web address) with a search magnifier laid
 * diagonally OVER it (search). A font can't overlap two glyphs, so it's drawn.
 *
 * The globe sits upper-left; the magnifier's lens overlaps the globe's lower-right and
 * its handle runs out to the bottom-right corner. A white "moat" (a fatter stroke in
 * the page colour, drawn under the magnifier) knocks out the globe lines beneath the
 * lens so the magnifier reads as being IN FRONT.
 *
 * E-ink: flat vector strokes, mid-grey (never pure black), no anti-alias-dependent
 * fills — reads crisp in grayscale at the sliver size.
 *
 * @param inkColor stroke colour of the icon (globe + magnifier).
 * @param moatColor the surface colour behind the icon, used for the knock-out moat.
 */
class GlobeSearchDrawable(
    inkColor: Int,
    moatColor: Int,
    private val intrinsicPx: Int = -1,
) : Drawable() {

    private val ink = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = inkColor
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }
    private val moat = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = moatColor
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }
    private val oval = RectF()

    override fun draw(canvas: Canvas) {
        val b = bounds
        val s = minOf(b.width(), b.height()).toFloat()
        if (s <= 0f) return
        val stroke = s * 0.06f
        ink.strokeWidth = stroke
        moat.strokeWidth = stroke * 3f // fat enough to clear the globe lines under the lens

        // Globe: centred slightly upper-left so the magnifier has room lower-right.
        val gcx = b.left + s * 0.42f
        val gcy = b.top + s * 0.42f
        val gr = s * 0.30f
        oval.set(gcx - gr * 0.45f, gcy - gr, gcx + gr * 0.45f, gcy + gr)

        // Moat first: a fatter stroke in the moat colour so the globe reads against
        // dark backgrounds too (same technique as the magnifier's knock-out moat).
        canvas.drawCircle(gcx, gcy, gr, moat)
        canvas.drawLine(gcx - gr, gcy, gcx + gr, gcy, moat)
        canvas.drawOval(oval, moat)

        // Then the globe on top.
        canvas.drawCircle(gcx, gcy, gr, ink)
        // Equator.
        canvas.drawLine(gcx - gr, gcy, gcx + gr, gcy, ink)
        // Meridian (a thin vertical ellipse through the poles).
        canvas.drawOval(oval, ink)

        // Magnifier, lower-right, overlapping the globe. Lens centre + diagonal handle.
        val lr = s * 0.20f
        val lcx = b.left + s * 0.62f
        val lcy = b.top + s * 0.62f
        val hx = b.left + s * 0.92f
        val hy = b.top + s * 0.92f
        // Handle start just outside the lens on the diagonal toward the corner.
        val d = lr / kotlin.math.sqrt(2f)
        val h0x = lcx + d
        val h0y = lcy + d

        // Moat first (knock out whatever is beneath the lens + handle).
        canvas.drawCircle(lcx, lcy, lr, moat)
        canvas.drawLine(h0x, h0y, hx, hy, moat)
        // Then the magnifier on top.
        canvas.drawCircle(lcx, lcy, lr, ink)
        canvas.drawLine(h0x, h0y, hx, hy, ink)
    }

    override fun getIntrinsicWidth(): Int = intrinsicPx
    override fun getIntrinsicHeight(): Int = intrinsicPx
    override fun setAlpha(alpha: Int) { ink.alpha = alpha }
    override fun setColorFilter(colorFilter: ColorFilter?) { ink.colorFilter = colorFilter }
    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
}
