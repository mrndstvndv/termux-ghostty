package com.mrndtvndv.term.ui.workspace

import android.view.KeyEvent
import com.termux.terminal.GhosttyScrollEvent
import com.termux.terminal.TerminalSession
import com.termux.terminal.TerminalSessionIO
import com.termux.terminal.compose.TerminalCommand
import com.termux.terminal.compose.TerminalCommandResult
import com.termux.terminal.compose.TerminalPointerGeometry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class TerminalSessionCommandAdapterTest {
    @Test
    fun queuedModeTransitionCannotSelectScrollbackFromStaleMode() {
        var viewportUpdates = 0
        var submittedEvent: GhosttyScrollEvent? = null
        val adapter = adapterFor(
            onViewportUpdate = { viewportUpdates++ },
            onScrollEvent = { submittedEvent = it }
        )

        val result = adapter.submit(scrollCommand(rowsDown = -2))

        assertEquals(TerminalCommandResult.Success, result)
        assertEquals(0, viewportUpdates)
        assertNotNull(submittedEvent)
        assertEquals(-2, submittedEvent?.rowsDown)
        assertEquals(100f, submittedEvent?.surfaceX)
        assertEquals(200f, submittedEvent?.surfaceY)
        assertEquals(401, submittedEvent?.screenWidthPx)
        assertEquals(801, submittedEvent?.screenHeightPx)
        assertEquals(10, submittedEvent?.cellWidthPx)
        assertEquals(20, submittedEvent?.cellHeightPx)
        assertEquals(16, submittedEvent?.paddingTopPx)
    }

    @Test
    fun zeroRowScrollDoesNotSubmitWorkerEvent() {
        var submittedEvent: GhosttyScrollEvent? = null
        val adapter = adapterFor(onScrollEvent = { submittedEvent = it })

        val result = adapter.submit(scrollCommand(rowsDown = 0))

        assertEquals(TerminalCommandResult.Success, result)
        assertNull(submittedEvent)
    }

    @Test
    fun textCommandScrollsToBottomBeforeWriting() {
        var viewportResets = 0
        val adapter = adapterFor(onViewportUpdate = { viewportResets++ })

        val result = adapter.submit(TerminalCommand.Text("hello"))

        assertEquals(TerminalCommandResult.Success, result)
        assertEquals(1, viewportResets)
    }

    @Test
    fun emptyTextCommandDoesNotScrollToBottom() {
        var viewportResets = 0
        val adapter = adapterFor(onViewportUpdate = { viewportResets++ })

        val result = adapter.submit(TerminalCommand.Text(""))

        assertEquals(TerminalCommandResult.Success, result)
        assertEquals(0, viewportResets)
    }

    @Test
    fun keyDownCommandScrollsToBottomBeforeWriting() {
        var viewportResets = 0
        val adapter = adapterFor(onViewportUpdate = { viewportResets++ })

        val result = adapter.submit(
            TerminalCommand.Key(keyCode = KeyEvent.KEYCODE_DPAD_DOWN, metaState = 0, down = true)
        )

        assertEquals(TerminalCommandResult.Success, result)
        assertEquals(1, viewportResets)
    }

    @Test
    fun keyUpCommandDoesNotScrollToBottom() {
        var viewportResets = 0
        val adapter = adapterFor(onViewportUpdate = { viewportResets++ })

        val result = adapter.submit(
            TerminalCommand.Key(keyCode = KeyEvent.KEYCODE_DPAD_DOWN, metaState = 0, down = false)
        )

        assertEquals(TerminalCommandResult.Success, result)
        assertEquals(0, viewportResets)
    }

    @Test
    fun cursorMoveCommandScrollsToBottomBeforeWriting() {
        var viewportResets = 0
        val adapter = adapterFor(onViewportUpdate = { viewportResets++ })

        val result = adapter.submit(TerminalCommand.CursorMove(delta = 1))

        assertEquals(TerminalCommandResult.Success, result)
        assertEquals(1, viewportResets)
    }

    @Test
    fun zeroCursorMoveDoesNotScrollToBottom() {
        var viewportResets = 0
        val adapter = adapterFor(onViewportUpdate = { viewportResets++ })

        val result = adapter.submit(TerminalCommand.CursorMove(delta = 0))

        assertEquals(TerminalCommandResult.Success, result)
        assertEquals(0, viewportResets)
    }

    @Test
    fun scrollCommandDoesNotScrollToBottom() {
        var viewportResets = 0
        var submittedEvent: GhosttyScrollEvent? = null
        val adapter = adapterFor(
            onViewportUpdate = { viewportResets++ },
            onScrollEvent = { submittedEvent = it }
        )

        val result = adapter.submit(scrollCommand(rowsDown = -2))

        assertEquals(TerminalCommandResult.Success, result)
        assertEquals(0, viewportResets)
        assertNotNull(submittedEvent)
    }

    private fun adapterFor(
        onViewportUpdate: () -> Unit = {},
        onScrollEvent: (GhosttyScrollEvent) -> Unit = {}
    ) = TerminalSessionCommandAdapter(
        session = TerminalSession(4096, null, NoOpIo()),
        updateTopRow = { onViewportUpdate() },
        submitScrollEvent = onScrollEvent
    )

    private fun scrollCommand(rowsDown: Int) = TerminalCommand.Scroll(
        rowsDown = rowsDown,
        xPx = 100f,
        yPx = 200f,
        geometry = TerminalPointerGeometry(
            cellWidthPx = 10.4f,
            cellHeightPx = 20.4f,
            contentTopPx = 16.4f,
            viewportWidthPx = 401,
            viewportHeightPx = 801
        )
    )

    private class NoOpIo : TerminalSessionIO {
        override fun write(data: ByteArray?, offset: Int, count: Int) = Unit

        override fun onResize(columns: Int, rows: Int, cellWidth: Int, cellHeight: Int) = Unit

        override fun onClose() = Unit
    }
}
