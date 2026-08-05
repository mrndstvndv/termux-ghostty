package com.termux.terminal.compose.internal

import androidx.compose.foundation.magnifier
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import com.termux.terminal.compose.TerminalMetrics

private const val SelectionMagnifierZoom = 2f

internal fun Modifier.terminalSelectionMagnifier(sourceCenter: () -> Offset): Modifier =
    magnifier(
        sourceCenter = { sourceCenter() },
        zoom = SelectionMagnifierZoom,
        size = DpSize(width = 128.dp, height = 56.dp),
        cornerRadius = 28.dp
    )

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
