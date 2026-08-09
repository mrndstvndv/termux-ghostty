package com.termux.terminal.compose.internal

import androidx.compose.ui.graphics.GraphicsContext
import androidx.compose.ui.graphics.layer.GraphicsLayer
import androidx.compose.ui.graphics.drawscope.DrawScope
import com.termux.terminal.compose.CursorEffect
import com.termux.terminal.compose.CursorEffectState
import com.termux.terminal.compose.TerminalBackend
import com.termux.terminal.compose.TerminalBackendListener
import com.termux.terminal.compose.TerminalCanvasConfig
import com.termux.terminal.compose.TerminalCursor
import com.termux.terminal.compose.TerminalMetrics
import com.termux.terminal.compose.TerminalCommand
import com.termux.terminal.compose.TerminalCommandResult
import com.termux.terminal.compose.TerminalFrame
import com.termux.terminal.compose.TerminalSize
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TerminalControllerTest {
    @Test
    fun frameInvalidationSchedulesCursorAnimationBeforeFirstRenderedFrame() {
        val controller = TerminalController(RecordingBackend(), UnusedGraphicsContext)
        controller.configure(TerminalCanvasConfig(cursorEffect = TestCursorEffect))

        controller.onFrameInvalidated()

        assertTrue(controller.needsFrame(0f))
    }

    @Test
    fun invisibleCursorDoesNotBecomePreviousTrailPosition() {
        val state = CursorEffectState()

        state.observe(TerminalCursor(1, 1, true, TerminalCursor.STYLE_BLOCK), 0.1f)
        state.observe(TerminalCursor(20, 20, false, TerminalCursor.STYLE_BLOCK), 0.2f)
        state.observe(TerminalCursor(2, 1, true, TerminalCursor.STYLE_BLOCK), 0.3f)

        assertEquals(1, state.previousColumn)
        assertEquals(1, state.previousRow)
        assertEquals(2, state.currentColumn)
        assertEquals(1, state.currentRow)
        assertEquals(0.3f, state.changeSeconds, 0f)
    }

    @Test
    fun resizesBackendBeforeFirstFrameIsAvailable() {
        val backend = RecordingBackend()
        val controller = testController(backend)
        controller.resizeIfNeeded(widthPx = 640, heightPx = 480)

        assertEquals(listOf(640 to 480), backend.resizes.map { it.widthPx to it.heightPx })
        assertTrue(backend.resizes.single().columns >= 4)
        assertTrue(backend.resizes.single().rows >= 4)
    }

    @Test
    fun attachReplacesThePreviousListenerAndDetachStopsInvalidations() {
        val backend = RecordingBackend()
        val first = TerminalController(backend, UnusedGraphicsContext)
        val second = TerminalController(backend, UnusedGraphicsContext)

        first.attach()
        second.attach()
        second.detach()

        assertEquals(listOf("attach", "attach", "detach"), backend.lifecycle)
        assertTrue(backend.attachedListener == null)
    }

    @Test
    fun releaseIsIdempotentAndReleasesBackendOnce() {
        val backend = RecordingBackend()
        val controller = TerminalController(backend, UnusedGraphicsContext)
        controller.attach()

        controller.release()
        controller.release()

        assertEquals(listOf("attach", "detach", "release"), backend.lifecycle)
    }

    @Test
    fun invalidationIsIgnoredAfterRelease() {
        val backend = RecordingBackend()
        val controller = TerminalController(backend, UnusedGraphicsContext)
        controller.attach()
        controller.release()

        controller.onFrameInvalidated()

        assertEquals(0, controller.version())
        assertEquals(listOf("attach", "detach", "release"), backend.lifecycle)
    }

    @Test
    fun repeatedResizeCallsCoalesceToOneBackendResize() {
        val backend = RecordingBackend()
        val controller = testController(backend)

        controller.resizeIfNeeded(widthPx = 640, heightPx = 480)
        controller.resizeIfNeeded(widthPx = 640, heightPx = 480)
        controller.resizeIfNeeded(widthPx = 320, heightPx = 240)

        assertEquals(listOf(640 to 480, 320 to 240), backend.resizes.map { it.widthPx to it.heightPx })
    }

    @Test
    fun fontSizeChangeRecomputesGridAtUnchangedViewportSize() {
        val backend = RecordingBackend()
        val controller = TerminalController(backend, UnusedGraphicsContext) { fontSize, _, width, height ->
            TerminalMetrics.of(
                cellWidthPx = fontSize.toFloat(),
                cellHeightPx = fontSize.toFloat() * 2,
                ascentPx = -fontSize.toFloat(),
                lineSpacingAndAscentPx = 4f,
                viewportWidthPx = width,
                viewportHeightPx = height
            )
        }
        controller.configure(TerminalCanvasConfig(fontSize = 10))
        controller.resizeIfNeeded(widthPx = 640, heightPx = 480)

        controller.configure(TerminalCanvasConfig(fontSize = 20))
        controller.resizeIfNeeded(widthPx = 640, heightPx = 480)

        assertEquals(2, backend.resizes.size)
        assertTrue(backend.resizes[1].columns < backend.resizes[0].columns)
        assertTrue(backend.resizes[1].rows < backend.resizes[0].rows)
    }

    @Test
    fun fontGeometryChangeResizesBackendEvenWhenMinimumGridIsUnchanged() {
        val backend = RecordingBackend()
        val controller = TerminalController(backend, UnusedGraphicsContext) { fontSize, _, width, height ->
            TerminalMetrics.of(
                cellWidthPx = fontSize.toFloat(),
                cellHeightPx = fontSize.toFloat() * 2,
                ascentPx = -fontSize.toFloat(),
                lineSpacingAndAscentPx = 4f,
                viewportWidthPx = width,
                viewportHeightPx = height
            )
        }
        controller.configure(TerminalCanvasConfig(fontSize = 10))
        controller.resizeIfNeeded(widthPx = 20, heightPx = 20)

        controller.configure(TerminalCanvasConfig(fontSize = 11))
        controller.resizeIfNeeded(widthPx = 20, heightPx = 20)

        assertEquals(listOf(4 to 4, 4 to 4), backend.resizes.map { it.columns to it.rows })
        assertEquals(listOf(10, 11), backend.resizes.map { it.cellWidthPx })
    }

    @Test
    fun columnSizingUsesRawWidthInsteadOfRoundedVisualWidth() {
        val measuredCellWidthPx = 7.5f

        assertEquals(4, terminalColumnsForMeasuredCellWidth(37, measuredCellWidthPx))
        assertEquals(5, terminalColumnsForMeasuredCellWidth(38, measuredCellWidthPx))
        assertEquals(5, terminalColumnsForMeasuredCellWidth(39, measuredCellWidthPx))
    }
}

private object TestCursorEffect : CursorEffect {
    override val maxDurationSeconds: Float = 0.2f

    override fun draw(
        drawScope: DrawScope,
        frame: TerminalFrame,
        metrics: TerminalMetrics,
        state: CursorEffectState,
        timeSeconds: Float
    ) = Unit
}

private fun testController(backend: TerminalBackend): TerminalController =
    TerminalController(backend, UnusedGraphicsContext) { _, _, width, height ->
        TerminalMetrics.of(
            cellWidthPx = 8f,
            cellHeightPx = 16f,
            ascentPx = -12f,
            lineSpacingAndAscentPx = 4f,
            viewportWidthPx = width,
            viewportHeightPx = height
        )
    }

private object UnusedGraphicsContext : GraphicsContext {
    override fun createGraphicsLayer(): GraphicsLayer = error("Not used by this test")

    override fun releaseGraphicsLayer(layer: GraphicsLayer) = Unit
}

private class RecordingBackend : TerminalBackend {
    val resizes = mutableListOf<TerminalSize>()
    val lifecycle = mutableListOf<String>()
    var attachedListener: TerminalBackendListener? = null

    override fun attach(listener: TerminalBackendListener) {
        lifecycle += "attach"
        attachedListener = listener
    }

    override fun detach() {
        lifecycle += "detach"
        attachedListener = null
    }

    override fun refresh() = Unit

    override fun resize(size: TerminalSize) {
        resizes += size
    }

    override fun submit(command: TerminalCommand): TerminalCommandResult =
        TerminalCommandResult.Unsupported("Not used by this test")

    override fun currentFrame(): TerminalFrame? = null

    override fun release() {
        lifecycle += "release"
    }
}
