package com.termux.terminal.compose

import android.graphics.Rect
import android.graphics.Typeface

/**
 * Font and viewport geometry for the terminal canvas.
 *
 * Mouse, selection, link, and cursor hit testing must use this type (and never
 * platform renderer internals). Cell conversion methods are cell-based:
 * [columnToX] returns the left edge of a cell column, [rowToY] returns the top
 * edge of an absolute row relative to the viewport top.
 */
@Suppress("LongParameterList")
class TerminalMetrics private constructor(
    val fontSizePx: Float,
    /** Raw, unscaled width of the base monospace cell. */
    val measuredCellWidthPx: Float,
    /** Rounded width used for visual cell placement and hit testing. */
    val cellWidthPx: Float,
    /** Paint scale that maps [measuredCellWidthPx] to [cellWidthPx]. */
    val textScaleX: Float,
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
         * rounded font spacing, and the visual cell width is the rounded
         * measurement of 'X'.
         */
        fun from(
            fontSizePx: Float,
            typeface: Typeface?,
            viewportWidthPx: Int,
            viewportHeightPx: Int
        ): TerminalMetrics {
            val fontMetrics = TerminalFontMetrics.from(typeface, fontSizePx)
            return TerminalMetrics(
                fontSizePx = fontSizePx,
                measuredCellWidthPx = fontMetrics.measuredCellWidthPx,
                cellWidthPx = fontMetrics.cellWidthPx,
                textScaleX = fontMetrics.textScaleX,
                cellHeightPx = fontMetrics.lineSpacingPx.toFloat(),
                fontAscentPx = fontMetrics.ascentPx.toFloat(),
                lineSpacingAndAscentPx = fontMetrics.lineSpacingAndAscentPx.toFloat(),
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
            measuredCellWidthPx = cellWidthPx,
            cellWidthPx = cellWidthPx,
            textScaleX = 1f,
            cellHeightPx = cellHeightPx,
            fontAscentPx = ascentPx,
            lineSpacingAndAscentPx = lineSpacingAndAscentPx,
            viewportWidthPx = viewportWidthPx,
            viewportHeightPx = viewportHeightPx
        )
    }
}
