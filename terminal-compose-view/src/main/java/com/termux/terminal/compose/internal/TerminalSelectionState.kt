package com.termux.terminal.compose.internal

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.termux.terminal.compose.TerminalFrame
import com.termux.terminal.compose.TerminalRow
import com.termux.terminal.compose.TerminalSelection

/**
 * Selection state for one canvas: a live range and word expansion on long-press.
 * The rendered selection is snapshot state so the canvas
 * redraws when it changes; the consumer is notified through
 * [TerminalCanvasConfig.onSelectionChanged].
 */
internal enum class SelectionHandleEndpoint {
    START,
    END
}

internal class TerminalSelectionState {
    var selection: TerminalSelection by mutableStateOf(TerminalSelection.EMPTY)
        private set

    val isSelecting: Boolean
        get() = !selection.isEmpty

    fun startWordSelection(frame: TerminalFrame, column: Int, row: Int) {
        val normalizedRow = row.coerceIn(minimumSelectableRow(frame), maximumSelectableRow(frame))
        val rowData = frame.row(normalizedRow - frame.topRow)
        val x = rowData?.let { baseColumn(it, column, frame.columns) }
            ?: column.coerceIn(0, frame.columns - 1)
        var x1 = x
        var x2 = x
        if (rowData != null && !isWhitespaceCell(rowData, x1)) {
            while (x1 > 0 && !isWhitespaceCell(rowData, x1 - 1)) x1--
            while (x2 < frame.columns - 1 && !isWhitespaceCell(rowData, x2 + 1)) x2++
        }
        update(
            selectionStartCol = x1,
            selectionStartRow = normalizedRow,
            selectionEndCol = x2,
            selectionEndRow = normalizedRow
        )
    }

    /** Moves one endpoint without allowing the selection to invert. */
    fun updateHandle(
        frame: TerminalFrame,
        endpoint: SelectionHandleEndpoint,
        column: Int,
        row: Int
    ) {
        if (selection.isEmpty) return

        val normalizedRow = row.coerceIn(minimumSelectableRow(frame), maximumSelectableRow(frame))
        val normalizedColumn = validColumn(frame, normalizedRow, column)
        if (endpoint == SelectionHandleEndpoint.START) {
            val endRow = selection.endRow
            val endColumn = selection.endCol
            val boundedRow = normalizedRow.coerceAtMost(endRow)
            val boundedColumn = if (boundedRow == endRow) {
                normalizedColumn.coerceAtMost(endColumn)
            } else {
                normalizedColumn
            }
            update(
                selectionStartCol = boundedColumn,
                selectionStartRow = boundedRow,
                selectionEndCol = endColumn,
                selectionEndRow = endRow
            )
            return
        }

        val startRow = selection.startRow
        val startColumn = selection.startCol
        val boundedRow = normalizedRow.coerceAtLeast(startRow)
        val boundedColumn = if (boundedRow == startRow) {
            normalizedColumn.coerceAtLeast(startColumn)
        } else {
            normalizedColumn
        }
        update(
            selectionStartCol = startColumn,
            selectionStartRow = startRow,
            selectionEndCol = boundedColumn,
            selectionEndRow = boundedRow
        )
    }

    fun clear() {
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

    private fun minimumSelectableRow(frame: TerminalFrame): Int =
        if (frame.alternateBufferActive) 0 else -frame.viewport.transcriptRows

    private fun maximumSelectableRow(frame: TerminalFrame): Int = frame.rowsVisible - 1

    private fun validColumn(frame: TerminalFrame, row: Int, column: Int): Int {
        val normalizedColumn = column.coerceIn(0, frame.columns - 1)
        val rowData = frame.row(row - frame.topRow) ?: return normalizedColumn
        for (cellColumn in 0 until normalizedColumn) {
            val width = rowData.cellDisplayWidth(cellColumn)
            if (width > 1 && normalizedColumn < cellColumn + width) {
                return cellColumn + width
            }
        }
        return normalizedColumn
    }

    private fun baseColumn(row: TerminalRow, column: Int, columns: Int): Int {
        val normalizedColumn = column.coerceIn(0, columns - 1)
        for (cellColumn in 0 until normalizedColumn) {
            val width = row.cellDisplayWidth(cellColumn)
            if (width > 1 && normalizedColumn < cellColumn + width) return cellColumn
        }
        return normalizedColumn
    }
}
