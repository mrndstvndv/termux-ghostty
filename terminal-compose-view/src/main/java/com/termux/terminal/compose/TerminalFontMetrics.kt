package com.termux.terminal.compose

import android.graphics.Paint
import android.graphics.Typeface
import kotlin.math.ceil
import kotlin.math.roundToInt

/**
 * Shared font measurements for rendering and terminal geometry.
 *
 * The base cell is measured before the paint is scaled. Visual cell geometry
 * then uses the rounded width, while text uses the scale that maps the raw
 * measurement to that rounded width.
 */
internal class TerminalFontMetrics private constructor(
    val measuredCellWidthPx: Float,
    val cellWidthPx: Float,
    val textScaleX: Float,
    val lineSpacingPx: Int,
    val lineSpacingAndAscentPx: Int,
    val ascentPx: Int,
    private val basePaint: Paint
) {
    /** Configures a paint with the same base settings used for measurement. */
    fun configurePaint(paint: Paint) {
        paint.set(basePaint)
        paint.flags = paint.flags or Paint.SUBPIXEL_TEXT_FLAG
        paint.textScaleX = textScaleX
    }

    companion object {
        fun from(typeface: Typeface?, fontSizePx: Float): TerminalFontMetrics {
            val resolvedTypeface = typeface ?: Typeface.MONOSPACE
            val paint = Paint()
            configureBasePaint(paint, resolvedTypeface, fontSizePx)

            val measuredCellWidthPx = paint.measureText("X").coerceAtLeast(1f)
            val cellWidthPx = measuredCellWidthPx.roundToInt().coerceAtLeast(1).toFloat()
            val lineSpacingPx = ceil(paint.fontSpacing.toDouble()).toInt()
            val ascentPx = ceil(paint.ascent().toDouble()).toInt()

            return TerminalFontMetrics(
                measuredCellWidthPx = measuredCellWidthPx,
                cellWidthPx = cellWidthPx,
                textScaleX = cellWidthPx / measuredCellWidthPx,
                lineSpacingPx = lineSpacingPx,
                lineSpacingAndAscentPx = lineSpacingPx + ascentPx,
                ascentPx = ascentPx,
                basePaint = paint
            )
        }

        private fun configureBasePaint(paint: Paint, typeface: Typeface, fontSizePx: Float) {
            paint.typeface = typeface
            paint.isAntiAlias = true
            paint.flags = paint.flags or Paint.SUBPIXEL_TEXT_FLAG
            paint.textSize = fontSizePx
            paint.textScaleX = 1f
        }
    }
}
