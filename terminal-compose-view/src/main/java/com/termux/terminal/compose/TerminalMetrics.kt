package com.termux.terminal.compose

import android.graphics.Rect
import android.graphics.Paint
import android.graphics.Typeface

/**
 * Font and viewport geometry for the terminal canvas.
 *
 * Mouse, selection, link, and cursor hit testing must use this type (and never
 * platform renderer internals). Cell conversion methods are cell-based:
 * [columnToX] returns the left edge of a cell column, [rowToY] returns the top
 * edge of an absolute row relative to the viewport top.
 */
class TerminalMetrics private constructor(
    val fontSizePx: Float,
    val cellWidthPx: Float,
    val cellHeightPx: Float,
    val fontAscentPx: Float,
    val lineSpacingAndAscentPx: Float,
    val viewportWidthPx: Int,
    val viewportHeightPx: Int
) {

    fun xToColumn(x: Float): Int =
        (x / cellWidthPx).toInt().coerceAtLeast(0)

    /** Row in viewport coordinates (0-based) for a y pixel position. */
    fun yToViewportRow(y: Float): Int =
        ((y - lineSpacingAndAscentPx) / cellHeightPx).toInt()

    /** Absolute row for a y pixel position and the current viewport top. */
    fun yToRow(y: Float, topRow: Int): Int = yToViewportRow(y) + topRow

    fun columnToX(column: Int): Float = column * cellWidthPx

    fun viewportRowToY(row: Int): Float =
        row * cellHeightPx + lineSpacingAndAscentPx

    fun rowToY(row: Int, topRow: Int): Float =
        (row - topRow) * cellHeightPx + lineSpacingAndAscentPx

    /**
     * Cursor rectangle for a cell, in viewport pixel coordinates. The shape
     * follows [TerminalCursor.style] the same way the renderer draws it.
     */
    fun cursorRect(cursor: TerminalCursor, topRow: Int): Rect {
        val left = cursor.column * cellWidthPx
        val top = (cursor.row - topRow) * cellHeightPx
        val right = left + cellWidthPx
        val bottom = top + cellHeightPx
        return when (cursor.style) {
            TerminalCursor.STYLE_UNDERLINE ->
                Rect(left.toInt(), (bottom - cellHeightPx / 4f).toInt(), right.toInt(), bottom.toInt())
            TerminalCursor.STYLE_BAR ->
                Rect(left.toInt(), top.toInt(), (left + cellWidthPx / 4f).toInt(), bottom.toInt())
            else -> Rect(left.toInt(), top.toInt(), right.toInt(), bottom.toInt())
        }
    }

    companion object {
        /**
         * Builds metrics from a text size and viewport size. [typeface] is the
         * consumer's font choice; null uses the platform monospace font. Cell
         * geometry follows the emulator's convention: line spacing is the
         * rounded font spacing, and the cell width is measured from 'X'.
         */
        fun from(
            fontSizePx: Float,
            typeface: Typeface?,
            viewportWidthPx: Int,
            viewportHeightPx: Int
        ): TerminalMetrics {
            val paint = Paint()
            paint.isAntiAlias = true
            paint.typeface = typeface ?: Typeface.MONOSPACE
            paint.textSize = fontSizePx
            val lineSpacing = kotlin.math.ceil(paint.fontSpacing.toDouble()).toInt()
            val ascent = kotlin.math.ceil(paint.ascent().toDouble()).toInt()
            val lineSpacingAndAscent = lineSpacing + ascent
            val cellWidth = maxOf(1f, paint.measureText("X"))
            return TerminalMetrics(
                fontSizePx = fontSizePx,
                cellWidthPx = cellWidth,
                cellHeightPx = lineSpacing.toFloat(),
                fontAscentPx = ascent.toFloat(),
                lineSpacingAndAscentPx = lineSpacingAndAscent.toFloat(),
                viewportWidthPx = viewportWidthPx,
                viewportHeightPx = viewportHeightPx
            )
        }

        /** Convenience constructor for tests and fakes. */
        fun of(
            cellWidthPx: Float,
            cellHeightPx: Float,
            ascentPx: Float,
            lineSpacingAndAscentPx: Float,
            viewportWidthPx: Int,
            viewportHeightPx: Int,
            fontSizePx: Float = 14f
        ): TerminalMetrics = TerminalMetrics(
            fontSizePx = fontSizePx,
            cellWidthPx = cellWidthPx,
            cellHeightPx = cellHeightPx,
            fontAscentPx = ascentPx,
            lineSpacingAndAscentPx = lineSpacingAndAscentPx,
            viewportWidthPx = viewportWidthPx,
            viewportHeightPx = viewportHeightPx
        )
    }
}
