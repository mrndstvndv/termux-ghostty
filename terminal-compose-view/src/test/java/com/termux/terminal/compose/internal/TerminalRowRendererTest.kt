package com.termux.terminal.compose.internal

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface
import com.termux.terminal.TextStyle
import com.termux.terminal.compose.TerminalCellLayout
import com.termux.terminal.compose.TerminalCursor
import com.termux.terminal.compose.TerminalFrame
import com.termux.terminal.compose.TerminalLinkLayout
import com.termux.terminal.compose.TerminalLinkSegment
import com.termux.terminal.compose.TerminalModes
import com.termux.terminal.compose.TerminalPalette
import com.termux.terminal.compose.TerminalRow
import com.termux.terminal.compose.TerminalViewport
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class TerminalRowRendererTest {
    @Test
    fun rendersNativeCellLayoutWhenCellStartsAfterFirstCharacter() {
        val row = TerminalRow(
            columns = 2,
            text = charArrayOf('x', 'A', 'B'),
            charsUsed = 3,
            styles = LongArray(2),
            contentHash = 1L,
            cellLayout = TerminalCellLayout(
                start = intArrayOf(0, 1),
                length = intArrayOf(1, 2),
                displayWidth = intArrayOf(1, 1)
            ),
            isLineWrap = false
        )

        TerminalRowRenderer(Typeface.MONOSPACE, 14f).renderRow(
            canvas = RecordingCanvas(),
            frame = frame(row),
            rowIndex = 0,
            hints = RowRenderHints(-1, -1, -1, TerminalCursor.STYLE_BLOCK, false)
        )
    }

    @Test
    fun usesOneGlobalTextScaleAndRoundedCellCoordinates() {
        val renderer = TerminalRowRenderer(Typeface.MONOSPACE, 14f)
        val row = TerminalRow(
            columns = 3,
            text = charArrayOf('⚙', '\uFE0F'),
            charsUsed = 2,
            styles = LongArray(3),
            contentHash = 2L,
            cellLayout = TerminalCellLayout(
                start = intArrayOf(0, 0, 0),
                length = intArrayOf(0, 2, 0),
                displayWidth = intArrayOf(1, 2, 0)
            ),
            isLineWrap = false
        )
        val canvas = RecordingCanvas()

        renderer.renderRow(
            canvas = canvas,
            frame = frame(row),
            rowIndex = 0,
            hints = RowRenderHints(-1, -1, 1, TerminalCursor.STYLE_BLOCK, false)
        )

        assertEquals(0, canvas.scaleCalls)
        assertEquals(renderer.cellWidthPx, canvas.textRuns.single().x, 0f)
        assertEquals(renderer.textScaleX, canvas.textRuns.single().textScaleX, 0f)
        assertEquals(
            renderer.cellWidthPx / renderer.measuredCellWidthPx,
            canvas.textRuns.single().textScaleX,
            0f
        )
        assertTrue("text should use subpixel positioning", canvas.textRuns.single().subpixelText)
        val cursor = canvas.rects.single()
        assertEquals(renderer.cellWidthPx, cursor.left, 0f)
        assertEquals(renderer.cellWidthPx * 3f, cursor.right, 0f)
    }

    @Test
    fun syntheticLinkUnderlineUsesRoundedCellCoordinates() {
        val renderer = TerminalRowRenderer(Typeface.MONOSPACE, 14f)
        val row = TerminalRow(
            columns = 3,
            text = charArrayOf('a'),
            charsUsed = 1,
            styles = LongArray(3),
            contentHash = 4L,
            cellLayout = TerminalCellLayout(
                start = intArrayOf(0, 0, 0),
                length = intArrayOf(0, 1, 0),
                displayWidth = intArrayOf(1, 1, 0)
            ),
            isLineWrap = false
        )
        val canvas = RecordingCanvas()
        val frame = frame(
            row,
            TerminalLinkLayout(
                frameSequence = 1L,
                topRow = 0,
                rows = 1,
                columns = row.columns,
                segmentsPerRow = listOf(listOf(TerminalLinkSegment(1, 2, "https://example.com")))
            )
        )

        renderer.renderRow(
            canvas = canvas,
            frame = frame,
            rowIndex = 0,
            hints = RowRenderHints(-1, -1, -1, TerminalCursor.STYLE_BLOCK, false)
        )

        assertEquals(renderer.cellWidthPx, canvas.textRuns.single().x, 0f)
        val underline = canvas.rects.single()
        assertEquals(renderer.cellWidthPx, underline.left, 0f)
        assertEquals(renderer.cellWidthPx * 2f, underline.right, 0f)
    }

    @Test
    fun preservesNativeGraphemeRangesWithoutWidthCorrection() {
        val graphemes = listOf("🧠", "界", "e\u0301", "👩‍💻", "⚙️")
        val widths = listOf(2, 2, 1, 2, 1)
        val text = graphemes.joinToString("").toCharArray()
        val starts = IntArray(widths.sum())
        val lengths = IntArray(widths.sum())
        val displayWidths = IntArray(widths.sum())
        val styles = LongArray(widths.sum())
        var textIndex = 0
        var column = 0
        graphemes.forEachIndexed { index, grapheme ->
            starts[column] = textIndex
            lengths[column] = grapheme.length
            displayWidths[column] = widths[index]
            styles[column] = index.toLong()
            repeat(widths[index] - 1) { continuation ->
                starts[column + continuation + 1] = textIndex
                lengths[column + continuation + 1] = 0
                displayWidths[column + continuation + 1] = 0
            }
            textIndex += grapheme.length
            column += widths[index]
        }

        val row = TerminalRow(
            columns = widths.sum(),
            text = text,
            charsUsed = text.size,
            styles = styles,
            contentHash = 3L,
            cellLayout = TerminalCellLayout(starts, lengths, displayWidths),
            isLineWrap = false
        )
        val canvas = RecordingCanvas()
        val renderer = TerminalRowRenderer(Typeface.MONOSPACE, 14f)

        renderer.renderRow(
            canvas = canvas,
            frame = frame(row),
            rowIndex = 0,
            hints = RowRenderHints(-1, -1, -1, TerminalCursor.STYLE_BLOCK, false)
        )

        assertEquals(graphemes.size, canvas.textRuns.size)
        var expectedColumn = 0
        graphemes.forEachIndexed { index, grapheme ->
            val draw = canvas.textRuns[index]
            assertEquals(starts[expectedColumn], draw.index)
            assertEquals(grapheme.length, draw.count)
            assertEquals(expectedColumn * renderer.cellWidthPx, draw.x, 0f)
            expectedColumn += widths[index]
        }
        assertEquals(0, canvas.scaleCalls)
    }

    private fun frame(row: TerminalRow, linkLayout: TerminalLinkLayout? = null): TerminalFrame =
        TerminalFrame(
            sequence = 1L,
            viewport = TerminalViewport(0, 1, row.columns, 0),
            cursor = TerminalCursor(-1, -1, false, TerminalCursor.STYLE_BLOCK),
            modes = TerminalModes(false, false, false, false, false),
            palette = TerminalPalette.of(IntArray(TextStyle.NUM_INDEXED_COLORS)),
            rows = listOf(row),
            linkLayout = linkLayout
        )

    private class RecordingCanvas : Canvas() {
        data class TextRun(
            val index: Int,
            val count: Int,
            val x: Float,
            val textScaleX: Float,
            val subpixelText: Boolean
        )

        data class DrawRect(
            val left: Float,
            val right: Float
        )

        var scaleCalls = 0
        val textRuns = mutableListOf<TextRun>()
        val rects = mutableListOf<DrawRect>()

        override fun scale(sx: Float, sy: Float) {
            scaleCalls++
        }

        override fun drawRect(
            left: Float,
            top: Float,
            right: Float,
            bottom: Float,
            paint: Paint
        ) {
            rects += DrawRect(left, right)
        }

        override fun drawTextRun(
            text: CharArray,
            index: Int,
            count: Int,
            contextIndex: Int,
            contextCount: Int,
            x: Float,
            y: Float,
            isRtl: Boolean,
            paint: Paint
        ) {
            if (count > 0) {
                val subpixelText = paint.flags and Paint.SUBPIXEL_TEXT_FLAG != 0
                textRuns += TextRun(index, count, x, paint.textScaleX, subpixelText)
            }
        }
    }
}
