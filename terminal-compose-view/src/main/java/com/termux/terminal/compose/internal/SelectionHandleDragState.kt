package com.termux.terminal.compose.internal

import androidx.compose.ui.geometry.Offset

/** Keeps handle dragging stable while the handle itself moves after snapping. */
internal class SelectionHandleDragState(
    private val initialPosition: Offset,
    initialHandleOffset: Offset,
    initialPointerPosition: Offset
) {
    private val initialPointerInCanvas = initialHandleOffset + initialPointerPosition

    fun position(handleOffset: Offset, pointerPosition: Offset): Offset {
        val pointerInCanvas = handleOffset + pointerPosition
        return initialPosition + (pointerInCanvas - initialPointerInCanvas)
    }
}
