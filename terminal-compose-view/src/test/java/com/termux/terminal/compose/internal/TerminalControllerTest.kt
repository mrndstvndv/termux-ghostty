package com.termux.terminal.compose.internal

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
import com.termux.terminal.compose.TerminalModes
import com.termux.terminal.compose.TerminalPalette
import com.termux.terminal.compose.TerminalRow
import com.termux.terminal.compose.TerminalSize
import com.termux.terminal.compose.TerminalViewport
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TerminalControllerTest {
    @Test
    fun frameInvalidationSchedulesCursorAnimationBeforeFirstRenderedFrame() {
        val controller = TerminalController(RecordingBackend())
        controller.configure(TerminalCanvasConfig(cursorEffect = CursorEffect.SWEEP))

        controller.onFrameInvalidated()

        assertTrue(controller.needsFrame(0f))
    }


    @Test
    fun frameInvalidationPublishesBeforeRequestingOverlayState() {
        val callbacks = ArrayList<String>()
        val controller = TerminalController(RecordingBackend())
        controller.onFrameAvailable = { callbacks += "frame" }
        controller.onInvalidated = { callbacks += "compose" }

        controller.onFrameInvalidated()

        assertEquals(listOf("frame", "compose"), callbacks)
    }

    @Test
    fun initialFrameRecoveryRefreshesBackendBeforeRepaint() {
        val backend = RecordingBackend(completeFrame(columns = 80, rows = 24))
        val controller = testController(backend)

        controller.refresh()

        assertEquals(1, backend.refreshCount)
        assertEquals(1, controller.version())
    }
    @Test
    fun fractionalScrollPublishesRemainderAndCommitsIntegerRows() {
        val controller = testController(
            RecordingBackend(completeFrame(columns = 80, rows = 24))
        )
        controller.onFrameInvalidated()
        controller.beginScrollGesture()

        assertEquals(0, controller.applyScrollDelta(deltaPx = 6f, cellHeightPx = 16f))
        assertEquals(6f, controller.visualScrollOffsetPx, 0f)
        assertEquals(1, controller.applyScrollDelta(deltaPx = 10f, cellHeightPx = 16f))
        assertEquals(16f, controller.visualScrollOffsetPx, 0f)
    }

    @Test
    fun boundaryDragClampsAndPreventsUnexecutableSubmissions() {
        val frame = completeFrame(columns = 80, rows = 24, transcriptRows = 0)
        val controller = testController(RecordingBackend(frame))
        controller.onFrameInvalidated()
        controller.beginScrollGesture()

        assertEquals(0, controller.applyScrollDelta(deltaPx = 25f, cellHeightPx = 16f))
        assertEquals(0f, controller.visualScrollOffsetPx, 0f)
    }

    @Test
    fun fractionalScrollIsDisabledForMouseTrackingFrames() {
        val frame = completeFrame(
            columns = 80,
            rows = 24,
            modes = TerminalModes(false, false, false, true, false)
        )
        val controller = testController(RecordingBackend(frame))
        controller.onFrameInvalidated()
        controller.beginScrollGesture()

        assertEquals(0, controller.applyScrollDelta(deltaPx = 8f, cellHeightPx = 16f))
        assertEquals(0f, controller.visualScrollOffsetPx, 0f)
        assertEquals(1, controller.applyScrollDelta(deltaPx = 8f, cellHeightPx = 16f))
        assertEquals(0f, controller.visualScrollOffsetPx, 0f)
    }

    @Test
    fun refreshDoesNotDoubleInvalidateWhenBackendAlreadyNotified() {
        val backend = RecordingBackend(
            publishedFrame = completeFrame(columns = 80, rows = 24),
            notifyOnRefresh = true
        )
        val controller = testController(backend)
        controller.attach()
        val versionAfterAttach = controller.version()

        controller.refresh()

        assertEquals(versionAfterAttach + 1, controller.version())
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
    fun frameSequenceGapDoesNotBecomeCursorTrailMovement() {
        val state = CursorEffectState()

        state.observe(cursorFrame(1L, 1, 1), 0.1f)
        state.observe(cursorFrame(3L, 40, 20), 0.2f)

        assertEquals(-1, state.previousColumn)
        assertEquals(-1, state.previousRow)
        assertEquals(40, state.currentColumn)
        assertEquals(20, state.currentRow)
    }

    @Test
    fun repeatedFrameDoesNotResetCursorTrailOrigin() {
        val state = CursorEffectState()

        state.observe(cursorFrame(1L, 1, 1), 0.1f)
        state.observe(cursorFrame(1L, 1, 1), 0.15f)
        state.observe(cursorFrame(2L, 2, 1), 0.2f)

        assertEquals(1, state.previousColumn)
        assertEquals(1, state.previousRow)
        assertEquals(2, state.currentColumn)
        assertEquals(1, state.currentRow)
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
        val first = TerminalController(backend)
        val second = TerminalController(backend)

        first.attach()
        second.attach()
        second.detach()

        assertEquals(listOf("attach", "attach", "detach"), backend.lifecycle)
        assertTrue(backend.attachedListener == null)
    }

    @Test
    fun releaseIsIdempotentAndLeavesBackendOwnedByTheHost() {
        val backend = RecordingBackend()
        val controller = TerminalController(backend)
        controller.attach()

        controller.release()
        controller.release()

        assertEquals(listOf("attach", "detach"), backend.lifecycle)
    }

    @Test
    fun invalidationIsIgnoredAfterRelease() {
        val backend = RecordingBackend()
        val controller = TerminalController(backend)
        controller.attach()
        controller.release()

        controller.onFrameInvalidated()

        assertEquals(0, controller.version())
        assertEquals(listOf("attach", "detach"), backend.lifecycle)
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
        val controller = TerminalController(backend) { fontSize, _, width, height ->
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
        val controller = TerminalController(backend) { fontSize, _, width, height ->
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

    @Test
    fun frameForMetricsRejectsTheOldGridDuringAnAsynchronousResize() {
        val backend = RecordingBackend(completeFrame(columns = 80, rows = 24))
        val controller = testController(backend)
        val oldMetrics = TerminalMetrics.of(
            cellWidthPx = 8f,
            cellHeightPx = 16f,
            ascentPx = -12f,
            lineSpacingAndAscentPx = 4f,
            viewportWidthPx = 640,
            viewportHeightPx = 388
        )
        val resizedMetrics = TerminalMetrics.of(
            cellWidthPx = 8f,
            cellHeightPx = 16f,
            ascentPx = -12f,
            lineSpacingAndAscentPx = 4f,
            viewportWidthPx = 320,
            viewportHeightPx = 388
        )

        assertTrue(controller.currentFrameForMetrics(oldMetrics) != null)
        assertNull(controller.currentFrameForMetrics(resizedMetrics))
    }
}


private fun cursorFrame(sequence: Long, column: Int, row: Int): TerminalFrame =
    TerminalFrame(
        sequence = sequence,
        viewport = TerminalViewport(topRow = 0, rows = 24, columns = 80, transcriptRows = 0),
        cursor = TerminalCursor(column, row, true, TerminalCursor.STYLE_BLOCK),
        modes = TerminalModes(false, false, false, false, false),
        palette = TerminalPalette.of(IntArray(259)),
        rows = emptyList(),
        linkLayout = null
    )

private fun testController(backend: TerminalBackend): TerminalController =
    TerminalController(backend) { _, _, width, height ->
        TerminalMetrics.of(
            cellWidthPx = 8f,
            cellHeightPx = 16f,
            ascentPx = -12f,
            lineSpacingAndAscentPx = 4f,
            viewportWidthPx = width,
            viewportHeightPx = height
        )
    }

private fun completeFrame(
    columns: Int,
    rows: Int,
    modes: TerminalModes = TerminalModes(false, false, false, false, false),
    transcriptRows: Int = rows
): TerminalFrame = TerminalFrame(
    sequence = 1L,
    viewport = TerminalViewport(0, rows, columns, transcriptRows),
    cursor = TerminalCursor(0, 0, false, TerminalCursor.STYLE_BLOCK),
    modes = modes,
    palette = TerminalPalette.of(IntArray(259)),
    rows = List(rows) {
        TerminalRow(
            columns = columns,
            text = CharArray(0),
            charsUsed = 0,
            styles = LongArray(columns),
            contentHash = 0L,
            cellLayout = null,
            isLineWrap = false
        )
    },
    linkLayout = null
)

private class RecordingBackend(
    private val publishedFrame: TerminalFrame? = null,
    private val notifyOnRefresh: Boolean = false
) : TerminalBackend {
    val resizes = mutableListOf<TerminalSize>()
    val lifecycle = mutableListOf<String>()
    var attachedListener: TerminalBackendListener? = null
    var refreshCount = 0
    override fun attach(listener: TerminalBackendListener) {
        lifecycle += "attach"
        attachedListener = listener
    }

    override fun detach() {
        lifecycle += "detach"
        attachedListener = null
    }

    override fun refresh() {
        refreshCount++
        if (notifyOnRefresh) attachedListener?.onFrameInvalidated()
    }

    override fun resize(size: TerminalSize) {
        resizes += size
    }

    override fun submit(command: TerminalCommand): TerminalCommandResult =
        TerminalCommandResult.Unsupported("Not used by this test")

    override fun currentFrame(): TerminalFrame? = publishedFrame

    override fun release() {
        lifecycle += "release"
    }
}
