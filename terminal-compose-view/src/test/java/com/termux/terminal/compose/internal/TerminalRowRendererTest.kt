package com.termux.terminal.compose.internal

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface
import com.termux.terminal.TextStyle
import com.termux.terminal.compose.TerminalCellLayout
import com.termux.terminal.compose.TerminalCursor
import com.termux.terminal.compose.TerminalFrame
import com.termux.terminal.compose.TerminalModes
import com.termux.terminal.compose.TerminalPalette
import com.termux.terminal.compose.TerminalRow
import com.termux.terminal.compose.TerminalViewport
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

    private fun frame(row: TerminalRow): TerminalFrame =
        TerminalFrame(
            sequence = 1L,
            viewport = TerminalViewport(0, 1, row.columns, 0),
            cursor = TerminalCursor(-1, -1, false, TerminalCursor.STYLE_BLOCK),
            modes = TerminalModes(false, false, false, false, false),
            palette = TerminalPalette.of(IntArray(TextStyle.NUM_INDEXED_COLORS)),
            rows = listOf(row),
            linkLayout = null
        )

    private class RecordingCanvas : Canvas() {
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
        ) = Unit
    }
}
