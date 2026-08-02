package com.termux.terminal.compose.internal

import androidx.compose.ui.graphics.GraphicsContext
import androidx.compose.ui.graphics.layer.GraphicsLayer
import com.termux.terminal.compose.TerminalBackend
import com.termux.terminal.compose.TerminalBackendListener
import com.termux.terminal.compose.TerminalCommand
import com.termux.terminal.compose.TerminalCommandResult
import com.termux.terminal.compose.TerminalFrame
import org.junit.Assert.assertEquals
import org.junit.Test

class TerminalControllerTest {
    @Test
    fun resizesBackendBeforeFirstFrameIsAvailable() {
        val backend = RecordingBackend()
        val controller = TerminalController(backend, UnusedGraphicsContext)
        controller.resizeIfNeeded(widthPx = 640, heightPx = 480)

        assertEquals(listOf(640 to 480), backend.resizes)
    }
}

private object UnusedGraphicsContext : GraphicsContext {
    override fun createGraphicsLayer(): GraphicsLayer = error("Not used by this test")

    override fun releaseGraphicsLayer(layer: GraphicsLayer) = Unit
}

private class RecordingBackend : TerminalBackend {
    val resizes = mutableListOf<Pair<Int, Int>>()

    override fun attach(listener: TerminalBackendListener) = Unit

    override fun detach() = Unit

    override fun resize(widthPx: Int, heightPx: Int) {
        resizes += widthPx to heightPx
    }

    override fun submit(command: TerminalCommand): TerminalCommandResult =
        TerminalCommandResult.Unsupported("Not used by this test")

    override fun currentFrame(): TerminalFrame? = null

    override fun release() = Unit
}
