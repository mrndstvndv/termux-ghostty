package com.termux.terminal.compose.internal

import android.graphics.Canvas
import android.graphics.Paint
import com.termux.terminal.TextStyle
import com.termux.terminal.compose.TerminalFrame
import com.termux.terminal.compose.TerminalLinkSegment
import com.termux.terminal.compose.TerminalRow

/**
 * Draws the synthetic underline for link segments of a row. A segment cell
 * that already carries an underline attribute is left to the text renderer;
 * every other cell in the segment gets an underline in its effective
 * foreground color, batched per contiguous color.
 */
internal class TerminalLinkUnderlinePainter(
    private val paint: Paint,
    private val fontSizePx: Float,
    private val cellWidthPx: Float
) {
    fun drawUnderlines(
        canvas: Canvas,
        frame: TerminalFrame,
        row: TerminalRow,
        segments: Array<TerminalLinkSegment>,
        hints: RowRenderHints,
        baselineY: Float
    ) {
        if (segments.isEmpty()) return
        val rowHasBlockCursor = hints.cursorX >= 0 && hints.cursorStyle == 0
        for (segment in segments) {
            drawSegment(canvas, frame, row, segment, hints, rowHasBlockCursor, baselineY)
        }
    }

    private fun drawSegment(
        canvas: Canvas,
        frame: TerminalFrame,
        row: TerminalRow,
        segment: TerminalLinkSegment,
        hints: RowRenderHints,
        rowHasBlockCursor: Boolean,
        baselineY: Float
    ) {
        var batchStart = -1
        var batchEnd = -1
        var batchColor = 0

        var column = segment.startColumn
        while (column < segment.endColumnExclusive) {
            val displayWidth = row.cellDisplayWidth(column)
            if (displayWidth <= 0) {
                column++
                continue
            }
            val cellEnd = minOf(segment.endColumnExclusive, column + displayWidth)
            val textStyle = row.style(column)
            val underlined =
                (TextStyle.decodeEffect(textStyle) and TextStyle.CHARACTER_ATTRIBUTE_UNDERLINE) != 0
            val underlineColor = if (underlined) {
                0
            } else {
                resolveEffectiveForegroundColor(
                    frame.palette,
                    textStyle,
                    hints.reverseVideo || cellInSelection(column, displayWidth, hints) ||
                        (rowHasBlockCursor && isCursorColumn(displayWidth, hints.cursorX, column))
                )
            }
            if (underlined) {
                if (batchStart != -1) {
                    drawBatch(canvas, batchStart, batchEnd, batchColor, baselineY)
                    batchStart = -1
                }
            } else if (batchStart != -1 && batchEnd == column && batchColor == underlineColor) {
                batchEnd = cellEnd
            } else {
                if (batchStart != -1) {
                    drawBatch(canvas, batchStart, batchEnd, batchColor, baselineY)
                }
                batchStart = column
                batchEnd = cellEnd
                batchColor = underlineColor
            }
            column += displayWidth
        }

        if (batchStart != -1) {
            drawBatch(canvas, batchStart, batchEnd, batchColor, baselineY)
        }
    }

    private fun isCursorColumn(displayWidth: Int, cursorX: Int, column: Int): Boolean =
        cursorX == column || (displayWidth == 2 && cursorX == column + 1)

    private fun cellInSelection(column: Int, displayWidth: Int, hints: RowRenderHints): Boolean =
        column <= hints.selectionEnd && (column + displayWidth - 1) >= hints.selectionStart

    private fun drawBatch(
        canvas: Canvas,
        startColumn: Int,
        endColumnExclusive: Int,
        color: Int,
        baselineY: Float
    ) {
        if (endColumnExclusive <= startColumn) return
        val thickness = maxOf(1f, (fontSizePx / 14f).toInt().toFloat())
        val underlineBottom = baselineY - maxOf(1f, thickness * 0.5f)
        val underlineTop = underlineBottom - thickness
        paint.color = color
        canvas.drawRect(
            startColumn * cellWidthPx,
            underlineTop,
            endColumnExclusive * cellWidthPx,
            underlineBottom,
            paint
        )
    }
}
