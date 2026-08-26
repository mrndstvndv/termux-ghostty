package com.termux.terminal.compose.internal

import androidx.compose.ui.geometry.Offset
import com.termux.terminal.compose.TerminalMetrics
import com.termux.terminal.compose.TerminalSelection

internal fun selectionMagnifierSourceForSelection(
    endpoint: SelectionHandleEndpoint,
    selection: TerminalSelection,
    topRow: Int,
    metrics: TerminalMetrics
): Offset {
    if (selection.isEmpty) return Offset.Unspecified

    val handleColumn = if (endpoint == SelectionHandleEndpoint.START) {
        selection.startCol
    } else {
        selection.endCol + 1
    }
    val handleRow = if (endpoint == SelectionHandleEndpoint.START) {
        selection.startRow
    } else {
        selection.endRow
    }
    return selectionMagnifierSource(
        endpoint = endpoint,
        handlePosition = Offset(
            x = metrics.columnToX(handleColumn),
            y = metrics.rowToY(handleRow + 1, topRow)
        ),
        metrics = metrics
    )
}

internal fun selectionMagnifierSource(
    endpoint: SelectionHandleEndpoint,
    handlePosition: Offset,
    metrics: TerminalMetrics
): Offset {
    val characterCenterX = handlePosition.x + when (endpoint) {
        SelectionHandleEndpoint.START -> metrics.cellWidthPx / 2f
        SelectionHandleEndpoint.END -> -metrics.cellWidthPx / 2f
    }
    val characterCenterY = handlePosition.y - metrics.cellHeightPx / 2f
    return Offset(
        x = characterCenterX.coerceIn(0f, metrics.viewportWidthPx.toFloat()),
        y = characterCenterY.coerceIn(0f, metrics.viewportHeightPx.toFloat())
    )
}
