package com.termux.terminal.compose.internal

import androidx.compose.ui.geometry.Offset
import org.junit.Assert.assertEquals
import org.junit.Test

class TerminalGesturesTest {
    @Test
    fun scrollCommandPreservesTouchPositionForPaneRouting() {
        val command = scrollCommandForGesture(
            deltaRows = 2,
            touchPosition = Offset(120f, 640f)
        )

        assertEquals(-2, command.rowsDown)
        assertEquals(120f, command.xPx)
        assertEquals(640f, command.yPx)
    }
}
