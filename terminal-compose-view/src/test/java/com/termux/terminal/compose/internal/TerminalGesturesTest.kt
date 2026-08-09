package com.termux.terminal.compose.internal

import androidx.compose.ui.geometry.Offset
import com.termux.terminal.compose.TerminalMetrics
import com.termux.terminal.compose.TerminalPointerEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TerminalGesturesTest {
    @Test
    fun scrollCommandPreservesTouchPositionForPaneRouting() {
        val command = scrollCommandForGesture(
            deltaRows = 2,
            touchPosition = Offset(120f, 640f),
            metrics = metrics()
        )

        assertEquals(-2, command.rowsDown)
        assertEquals(120f, command.xPx)
        assertEquals(640f, command.yPx)
        assertEquals(400, command.geometry.viewportWidthPx)
        assertEquals(800, command.geometry.viewportHeightPx)
    }

    @Test
    fun tapBuildsMousePairWithoutDependingOnRenderedModeSnapshot() {
        val events = tapMouseEventsForPosition(120f, 640f, metrics())

        assertEquals(2, events.size)
        assertEquals(TerminalPointerEvent.Action.PRESS, events[0].action)
        assertEquals(TerminalPointerEvent.Action.RELEASE, events[1].action)
        assertTrue(events.all { it.button == TerminalPointerEvent.BUTTON_LEFT })
        assertTrue(events.all { it.xPx == 120f && it.yPx == 640f })
    }

    private fun metrics() = TerminalMetrics.of(
        cellWidthPx = 10f,
        cellHeightPx = 20f,
        ascentPx = 15f,
        lineSpacingAndAscentPx = 16f,
        viewportWidthPx = 400,
        viewportHeightPx = 800
    )
}
