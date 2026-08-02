package com.termux.terminal.compose.internal

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.termux.terminal.compose.TerminalFrame
import com.termux.terminal.compose.TerminalRow
import com.termux.terminal.compose.TerminalSelection

/**
 * Selection state for one canvas: anchor cell, live range, and word expansion
 * on long-press. The rendered selection is snapshot state so the canvas
 * redraws when it changes; the consumer is notified through
 * [TerminalCanvasConfig.onSelectionChanged].
 */
internal class TerminalSelectionState {
    var selection: TerminalSelection by mutableStateOf(TerminalSelection.EMPTY)
        private set

    private var anchorCol = -1
    private var anchorRow = -1
    private var isDragging = false

    val isSelecting: Boolean
        get() = !selection.isEmpty

    fun startWordSelection(frame: TerminalFrame, column: Int, row: Int) {
        anchorCol = column
        anchorRow = row
        isDragging = false
        val x = column.coerceIn(0, frame.columns - 1)
        val rowData = frame.row(row - frame.topRow)
        var x1 = x
        var x2 = x
        if (rowData != null && !isWhitespaceCell(rowData, x1)) {
            while (x1 > 0 && !isWhitespaceCell(rowData, x1 - 1)) x1--
            while (x2 < frame.columns - 1 && !isWhitespaceCell(rowData, x2 + 1)) x2++
        }
        update(selectionStartCol = x1, selectionStartRow = row, selectionEndCol = x2, selectionEndRow = row)
    }

    fun startDragSelection(frame: TerminalFrame, column: Int, row: Int) {
        anchorCol = column.coerceIn(0, frame.columns - 1)
        anchorRow = row.coerceIn(frame.topRow, frame.topRow + frame.rowsVisible - 1)
        isDragging = true
        update(
            selectionStartCol = anchorCol,
            selectionStartRow = anchorRow,
            selectionEndCol = anchorCol,
            selectionEndRow = anchorRow
        )
    }

    /** Extends the selection to [column]/[row] (absolute). No-op when not dragging. */
    fun updateDrag(frame: TerminalFrame, column: Int, row: Int) {
        if (!isDragging) return
        val col = column.coerceIn(0, frame.columns - 1)
        val normalizedRow = row.coerceIn(frame.topRow, frame.topRow + frame.rowsVisible - 1)
        val startRow = minOf(anchorRow, normalizedRow)
        val endRow = maxOf(anchorRow, normalizedRow)
        val startCol = if (startRow == anchorRow) anchorCol else 0
        val endCol = if (endRow == normalizedRow) col else frame.columns - 1
        update(
            selectionStartCol = startCol,
            selectionStartRow = startRow,
            selectionEndCol = endCol,
            selectionEndRow = endRow
        )
    }

    fun clear() {
        isDragging = false
        anchorCol = -1
        anchorRow = -1
        update(selectionStartCol = -1, selectionStartRow = -1, selectionEndCol = -1, selectionEndRow = -1)
    }

    private fun update(
        selectionStartCol: Int,
        selectionStartRow: Int,
        selectionEndCol: Int,
        selectionEndRow: Int
    ) {
        val next = TerminalSelection(selectionStartCol, selectionStartRow, selectionEndCol, selectionEndRow)
        if (next == selection) return
        selection = next
    }

    private fun isWhitespaceCell(row: TerminalRow, column: Int): Boolean {
        val range = row.cellTextRange(column) ?: return true
        val text = row.text()
        var index = range.first
        while (index <= range.last && index < row.charsUsed) {
            val codePoint = Character.codePointAt(text, index)
            if (!Character.isWhitespace(codePoint)) return false
            index += Character.charCount(codePoint)
        }
        return true
    }
}
