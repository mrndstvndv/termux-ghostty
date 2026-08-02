package com.termux.terminal.compose.internal

import com.termux.terminal.compose.TerminalCellLayout
import com.termux.terminal.compose.TerminalCursor
import com.termux.terminal.compose.TerminalFrame
import com.termux.terminal.compose.TerminalModes
import com.termux.terminal.compose.TerminalPalette
import com.termux.terminal.compose.TerminalRow
import com.termux.terminal.compose.TerminalSelection
import androidx.compose.ui.geometry.Offset
import com.termux.terminal.compose.TerminalViewport
import org.junit.Assert.assertEquals
import org.junit.Test

class TerminalSelectionStateTest {
    @Test
    fun dragMovementIsNotCompoundedWhenSelectionAnchorMoves() {
        val handleOffset = Offset(90f, 90f)
        val drag = SelectionHandleDragState(
            initialPosition = Offset(100f, 100f),
            initialHandleOffset = handleOffset,
            initialPointerPosition = Offset(10f, 10f)
        )
        val first = drag.position(handleOffset, Offset(11f, 10f))
        val second = drag.position(handleOffset, Offset(12f, 10f))

        assertEquals(Offset(101f, 100f), first)
        assertEquals(Offset(102f, 100f), second)
    }

    @Test
    fun dragUsesCanvasCoordinatesWhenHandleMoves() {
        val initialHandleOffset = Offset(90f, 100f)
        val drag = SelectionHandleDragState(
            initialPosition = Offset(100f, 110f),
            initialHandleOffset = initialHandleOffset,
            initialPointerPosition = Offset(10f, 10f)
        )

        val first = drag.position(initialHandleOffset, Offset(10f, 10f))
        val second = drag.position(Offset(100f, 100f), Offset(0f, 10f))

        assertEquals(Offset(100f, 110f), first)
        assertEquals(Offset(100f, 110f), second)
    }

    @Test
    fun startHandleCannotCrossEndHandle() {
        val state = TerminalSelectionState()
        val frame = frame(row("word other", columns = 10))
        state.startWordSelection(frame, column = 1, row = 0)

        state.updateHandle(frame, SelectionHandleEndpoint.START, column = 8, row = 0)

        assertEquals(TerminalSelection(3, 0, 3, 0), state.selection)
    }

    @Test
    fun endHandleCannotCrossStartHandle() {
        val state = TerminalSelectionState()
        val frame = frame(row("word other", columns = 10))
        state.startWordSelection(frame, column = 1, row = 0)

        state.updateHandle(frame, SelectionHandleEndpoint.END, column = 0, row = 0)

        assertEquals(TerminalSelection(0, 0, 0, 0), state.selection)
    }

    @Test
    fun handleSnapsOutOfWideGlyphContinuationCell() {
        val row = TerminalRow(
            columns = 4,
            text = charArrayOf('a', '界'),
            charsUsed = 2,
            styles = LongArray(4),
            contentHash = 1L,
            cellLayout = TerminalCellLayout(
                start = intArrayOf(0, 1, -1, -1),
                length = intArrayOf(1, 1, 0, 0),
                displayWidth = intArrayOf(1, 2, 0, 1)
            ),
            isLineWrap = false
        )
        val state = TerminalSelectionState()
        val frame = frame(row)
        state.startWordSelection(frame, column = 0, row = 0)

        state.updateHandle(frame, SelectionHandleEndpoint.END, column = 2, row = 0)

        assertEquals(TerminalSelection(0, 0, 3, 0), state.selection)
    }

    private fun row(text: String, columns: Int): TerminalRow = TerminalRow(
        columns = columns,
        text = text.toCharArray(),
        charsUsed = text.length,
        styles = LongArray(columns),
        contentHash = 1L,
        cellLayout = null,
        isLineWrap = false
    )

    private fun frame(row: TerminalRow): TerminalFrame = TerminalFrame(
        sequence = 1L,
        viewport = TerminalViewport(0, 1, row.columns, 0),
        cursor = TerminalCursor(-1, -1, false, TerminalCursor.STYLE_BLOCK),
        modes = TerminalModes(false, false, false, false, false),
        palette = TerminalPalette.of(IntArray(259)),
        rows = listOf(row),
        linkLayout = null
    )
}
