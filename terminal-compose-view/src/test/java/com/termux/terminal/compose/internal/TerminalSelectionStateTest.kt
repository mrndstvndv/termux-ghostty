package com.termux.terminal.compose.internal

import com.termux.terminal.compose.TerminalCellLayout
import com.termux.terminal.compose.TerminalCursor
import com.termux.terminal.compose.TerminalFrame
import com.termux.terminal.compose.TerminalMetrics
import com.termux.terminal.compose.TerminalModes
import com.termux.terminal.compose.TerminalPalette
import com.termux.terminal.compose.TerminalRow
import com.termux.terminal.compose.TerminalSelection
import androidx.compose.ui.geometry.Offset
import com.termux.terminal.compose.TerminalViewport
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TerminalSelectionStateTest {
    @Test
    fun selectionIsEmptyOnlyForInvalidColumnsOrInvertedRanges() {
        assertTrue(TerminalSelection.EMPTY.isEmpty)
        assertFalse(TerminalSelection(0, -10, 5, -10).isEmpty)
        assertFalse(TerminalSelection(0, -5, 10, 2).isEmpty)
        assertTrue(TerminalSelection(0, 5, 0, 4).isEmpty)
        assertTrue(TerminalSelection(5, -2, 4, -2).isEmpty)
        assertTrue(TerminalSelection(-1, -2, 5, -2).isEmpty)
        assertTrue(TerminalSelection(0, -2, -1, -2).isEmpty)
    }

    @Test
    fun startWordSelectionSupportsScrollbackRows() {
        val state = TerminalSelectionState()
        val frame = scrollbackFrame()

        state.startWordSelection(frame, column = 1, row = -3)

        assertEquals(TerminalSelection(0, -3, 3, -3), state.selection)
        assertTrue(state.isSelecting)
    }

    @Test
    fun updateHandleMovesBothEndpointsAcrossScrollbackRows() {
        val state = TerminalSelectionState()
        val frame = scrollbackFrame()
        state.startWordSelection(frame, column = 1, row = -3)

        state.updateHandle(frame, SelectionHandleEndpoint.START, column = 2, row = -5)
        assertEquals(TerminalSelection(2, -5, 3, -3), state.selection)

        state.updateHandle(frame, SelectionHandleEndpoint.END, column = 4, row = -1)

        assertEquals(TerminalSelection(2, -5, 4, -1), state.selection)
        assertFalse(state.selection.isEmpty)
        assertTrue(state.isSelecting)
    }

    @Test
    fun selectionTextIncludesScrollbackRows() {
        val frame = scrollbackFrame()

        val text = frame.selectionText(TerminalSelection(0, -5, 3, -3))

        assertEquals("older\nhistory\nword", text)
    }

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
    fun offscreenHandleRemainsPinnedToViewportEdge() {
        val metrics = TerminalMetrics.of(
            cellWidthPx = 10f,
            cellHeightPx = 20f,
            ascentPx = -15f,
            lineSpacingAndAscentPx = 0f,
            viewportWidthPx = 100,
            viewportHeightPx = 100
        )
        val position = selectionHandlePosition(
            selection = TerminalSelection(1, -1, 2, -1),
            frame = frame(row("word", columns = 10)),
            metrics = metrics,
            endpoint = SelectionHandleEndpoint.START,
            visualSizePx = 20f,
            touchTargetSizePx = 48f
        )

        assertEquals(0f, position.anchorY, 0.001f)
        assertEquals(true, position.isVisible)
    }

    @Test
    fun offscreenBelowHandleKeepsItsVisualAtTheViewportBottom() {
        val metrics = TerminalMetrics.of(
            cellWidthPx = 10f,
            cellHeightPx = 20f,
            ascentPx = -15f,
            lineSpacingAndAscentPx = 0f,
            viewportWidthPx = 100,
            viewportHeightPx = 100
        )
        val position = selectionHandlePosition(
            selection = TerminalSelection(1, 5, 2, 5),
            frame = frame(row("word", columns = 10)),
            metrics = metrics,
            endpoint = SelectionHandleEndpoint.START,
            visualSizePx = 20f,
            touchTargetSizePx = 48f
        )

        assertEquals(80f, position.anchorY, 0.001f)
        assertEquals(52f, position.touchTop, 0.001f)
        assertEquals(28f, position.visualTopOffset, 0.001f)
        assertEquals(100f, position.anchorY + position.visualSizePx, 0.001f)
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

    private fun scrollbackFrame(): TerminalFrame = frame(
        rows = listOf(
            row("older", columns = 12),
            row("history", columns = 12),
            row("word other", columns = 12),
            row("next", columns = 12),
            row("newest", columns = 12)
        ),
        topRow = -5,
        transcriptRows = 5
    )

    private fun row(text: String, columns: Int): TerminalRow = TerminalRow(
        columns = columns,
        text = text.toCharArray(),
        charsUsed = text.length,
        styles = LongArray(columns),
        contentHash = 1L,
        cellLayout = null,
        isLineWrap = false
    )

    private fun frame(
        rows: List<TerminalRow>,
        topRow: Int,
        transcriptRows: Int
    ): TerminalFrame {
        val columns = rows.first().columns
        return TerminalFrame(
            sequence = 1L,
            viewport = TerminalViewport(topRow, rows.size, columns, transcriptRows),
            cursor = TerminalCursor(-1, -1, false, TerminalCursor.STYLE_BLOCK),
            modes = TerminalModes(false, false, false, false, false),
            palette = TerminalPalette.of(IntArray(259)),
            rows = rows,
            linkLayout = null
        )
    }

    private fun frame(row: TerminalRow): TerminalFrame = frame(
        rows = listOf(row),
        topRow = 0,
        transcriptRows = 0
    )
}
