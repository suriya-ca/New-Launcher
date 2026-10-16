package com.sdev.newlauncher.ui

import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.drawable.Drawable
import com.sdev.newlauncher.data.IconShape
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sign
import kotlin.math.sin

/**
 * Draws [source] clipped to [shape], which is what turns a square bitmap (or an adaptive icon's
 * full-size artwork) into a circle, squircle, teardrop and so on.
 *
 * The mask is drawn into a saved layer and punched out with [PorterDuff.Mode.DST_IN], so the
 * edge is anti-aliased — clipping the canvas directly would leave it jagged.
 *
 * @param cornerPercent the corner radius of [IconShape.ROUNDED], as a percentage of the icon's
 *   shorter side.
 * @param scale how much of the cell the icon fills; `1f` is edge to edge.
 */
internal class MaskedIconDrawable(
    private val source: Drawable,
    private val shape: IconShape,
    private val cornerPercent: Int,
    private val scale: Float
) : Drawable() {

    private val maskPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val xferMode = PorterDuffXfermode(PorterDuff.Mode.DST_IN)
    private val maskPath = Path()
    private val maskRect = RectF()
    private val sourceBounds = Rect()

    override fun draw(canvas: Canvas) {
        val bounds = bounds
        if (bounds.isEmpty) return
        val halfWidth = (bounds.width() * scale / 2f).toInt()
        val halfHeight = (bounds.height() * scale / 2f).toInt()
        if (halfWidth <= 0 || halfHeight <= 0) return

        val layer = canvas.saveLayer(bounds.left.toFloat(), bounds.top.toFloat(), bounds.right.toFloat(), bounds.bottom.toFloat(), null)
        val cx = bounds.exactCenterX()
        val cy = bounds.exactCenterY()
        sourceBounds.set(
            (cx - halfWidth).toInt(),
            (cy - halfHeight).toInt(),
            (cx + halfWidth).toInt(),
            (cy + halfHeight).toInt()
        )
        source.bounds = sourceBounds
        source.draw(canvas)

        buildMask(bounds)
        maskPaint.xfermode = xferMode
        canvas.drawPath(maskPath, maskPaint)
        maskPaint.xfermode = null
        canvas.restoreToCount(layer)
    }

    private fun buildMask(bounds: Rect) {
        maskPath.reset()
        maskRect.set(bounds)
        val width = bounds.width().toFloat()
        val height = bounds.height().toFloat()
        val minSide = min(width, height)
        when (shape) {
            IconShape.NONE -> maskPath.addRect(maskRect, Path.Direction.CW)

            IconShape.CIRCLE -> maskPath.addOval(maskRect, Path.Direction.CW)

            IconShape.ROUNDED -> {
                val radius = minSide * cornerPercent / 100f
                maskPath.addRoundRect(maskRect, radius, radius, Path.Direction.CW)
            }

            IconShape.TEARDROP -> {
                // Three corners rounded to the full half-side leave the fourth one sharp.
                val radius = minSide / 2f
                maskPath.addRoundRect(
                    maskRect,
                    floatArrayOf(radius, radius, 0f, 0f, radius, radius, radius, radius),
                    Path.Direction.CW
                )
            }

            IconShape.SQUIRCLE -> {
                // |x/rx|^n + |y/ry|^n = 1, sampled: n = 4 is the classic squircle.
                val exponent = 4.0
                val power = 2.0 / exponent
                val rx = width / 2f
                val ry = height / 2f
                val cx = bounds.exactCenterX().toDouble()
                val cy = bounds.exactCenterY().toDouble()
                val steps = 64
                for (i in 0 until steps) {
                    val angle = 2.0 * Math.PI * i / steps
                    val ct = cos(angle)
                    val st = sin(angle)
                    val x = cx + rx * sign(ct) * abs(ct).pow(power)
                    val y = cy + ry * sign(st) * abs(st).pow(power)
                    if (i == 0) maskPath.moveTo(x.toFloat(), y.toFloat())
                    else maskPath.lineTo(x.toFloat(), y.toFloat())
                }
                maskPath.close()
            }

            IconShape.HEXAGON -> {
                val rx = width / 2f
                val ry = height / 2f
                val cx = bounds.exactCenterX().toFloat()
                val cy = bounds.exactCenterY().toFloat()
                for (i in 0 until 6) {
                    val angle = Math.toRadians(-90.0 + i * 60.0)
                    val x = cx + rx * cos(angle).toFloat()
                    val y = cy + ry * sin(angle).toFloat()
                    if (i == 0) maskPath.moveTo(x, y) else maskPath.lineTo(x, y)
                }
                maskPath.close()
            }
        }
    }

    override fun setAlpha(alpha: Int) {
        source.alpha = alpha
        invalidateSelf()
    }

    override fun setColorFilter(colorFilter: ColorFilter?) {
        source.colorFilter = colorFilter
        invalidateSelf()
    }

    @Deprecated("Deprecated in Drawable")
    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT

    override fun getIntrinsicWidth(): Int = source.intrinsicWidth

    override fun getIntrinsicHeight(): Int = source.intrinsicHeight

    override fun isStateful(): Boolean = source.isStateful

    override fun onStateChange(state: IntArray): Boolean = source.setState(state)
}
