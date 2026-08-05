package com.termux.terminal.compose.internal

import com.termux.terminal.compose.TerminalCommand
import com.termux.terminal.compose.TerminalMetrics

/** Applies endpoint movement and keeps a transcript selection near the viewport. */
internal fun updateSelectionHandle(
    endpoint: SelectionHandleEndpoint,
    x: Float,
    y: Float,
    controller: TerminalController,
    metrics: TerminalMetrics,
    selectionState: TerminalSelectionState
) {
    val frame = controller.currentFrame() ?: return
    val requestedRow = metrics.yToRow(y - metrics.cellHeightPx / 2f, frame.topRow)
    val requestedColumn = metrics.xToColumn(x)
    selectionState.updateHandle(frame, endpoint, requestedColumn, requestedRow)
    if (frame.alternateBufferActive) return

    val minimumTopRow = -frame.viewport.transcriptRows
    val nextTopRow = when {
        requestedRow <= frame.topRow -> (frame.topRow - 1).coerceAtLeast(minimumTopRow)
        requestedRow >= frame.topRow + frame.rowsVisible -> (frame.topRow + 1).coerceAtMost(0)
        else -> frame.topRow
    }
    if (nextTopRow != frame.topRow) {
        controller.submit(TerminalCommand.SetViewportTopRow(nextTopRow))
    }
}
