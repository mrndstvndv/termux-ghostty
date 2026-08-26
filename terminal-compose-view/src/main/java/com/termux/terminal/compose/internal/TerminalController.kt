package com.termux.terminal.compose.internal

import com.termux.terminal.compose.CursorEffectState
import com.termux.terminal.compose.CursorEffectSnapshot
import com.termux.terminal.compose.TerminalBackend
import com.termux.terminal.compose.TerminalBackendError
import com.termux.terminal.compose.TerminalBackendListener
import com.termux.terminal.compose.TerminalCanvasConfig
import com.termux.terminal.compose.TerminalCommand
import com.termux.terminal.compose.TerminalCommandResult
import com.termux.terminal.compose.TerminalDiagnostic
import com.termux.terminal.compose.TerminalFrame
import com.termux.terminal.compose.TerminalMetrics
import com.termux.terminal.compose.TerminalPointerGeometry
import com.termux.terminal.compose.TerminalSelection
import com.termux.terminal.compose.TerminalSize
import androidx.compose.runtime.withFrameNanos
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.Channel.Factory.CONFLATED
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.exp

private const val MinGridDimension = 4

/**
 * Converts a viewport width to columns using the raw measured cell width.
 * Visual geometry intentionally uses the rounded cell width instead.
 */
internal fun terminalColumnsForMeasuredCellWidth(widthPx: Int, measuredCellWidthPx: Float): Int =
    (widthPx / measuredCellWidthPx).toInt().coerceAtLeast(MinGridDimension)

/**
 * Owns the backend lifecycle, cursor effects, and frame scheduling decisions for
 * one [TerminalCanvas].
 *
 * The controller is main-thread confined. [release] is idempotent and releases
 * UI-owned resources. Backend ownership remains with the host that supplied it.
 */
@Suppress("TooManyFunctions") // lifecycle and rendering responsibilities share one backend owner
internal class TerminalController(
    private val backend: TerminalBackend,
    private val measureMetrics: (Int, android.graphics.Typeface?, Int, Int) -> TerminalMetrics =
        { fontSizePx, typeface, width, height ->
            TerminalMetrics.from(
                fontSizePx = fontSizePx.toFloat(),
                typeface = typeface,
                viewportWidthPx = width,
                viewportHeightPx = height
            )
        }
) : TerminalBackendListener {

    /** Invoked when Compose-owned overlays need a frame-driven state update. */
    var onInvalidated: (() -> Unit)? = null

    /** Publishes complete frames to the GLES renderer without recomposition. */
    var onFrameAvailable: (() -> Unit)? = null

    private val invalidations = Channel<Unit>(CONFLATED)
    private var contentVersion = 0
    private var config: TerminalCanvasConfig = TerminalCanvasConfig()
    private var effectiveFontSize = config.fontSize
    private var attached = false
    private var released = false

    private var lastResizeWidth = -1
    private var lastResizeHeight = -1
    private var lastResizeColumns = -1
    private var lastResizeRows = -1
    private var lastResizeCellWidth = -1
    private var lastResizeCellHeight = -1
    private var lastResizeContentTop = -1
    private var desiredTopRowF = 0.0
    private var lastSubmittedTopRow = 0
    private var discreteScrollAccumulatorPx = 0f
    private var currentVisualScrollOffsetPx = 0f
    private var lastKnownCellHeightPx = 0f
    private var fractionalScrollAllowed: Boolean? = null
    private var isDragging = false
    private var flingJob: Job? = null

    private val isScrollActive: Boolean
        get() = isDragging || flingJob != null

    private val cursorEffectState = CursorEffectState()
    private var cursorFramePending = false
    fun attach() {
        if (attached || released) return
        attached = true
        backend.attach(this)
    }

    fun detach() {
        if (!attached) return
        attached = false
        backend.detach()
    }

    /** Idempotent; detaches the backend and releases every render resource. */
    fun release() {
        if (released) return
        released = true
        cancelFling()
        detach()
        resetCursorTracking()
        onInvalidated = null
        onFrameAvailable = null
    }

    /**
     * Applies the latest host policy and rendering inputs.
     *
     * [fontSize] is canvas-owned because pinch zoom is local state. Keeping it
     * separate avoids allocating a copied [TerminalCanvasConfig] on every
     * recomposition solely to substitute that one value.
     */
    fun configure(newConfig: TerminalCanvasConfig, fontSize: Int = newConfig.fontSize) {
        val fontGeometryChanged = fontSize != effectiveFontSize ||
            newConfig.typeface != config.typeface
        val cursorEffectChanged = newConfig.cursorEffect != config.cursorEffect
        config = newConfig
        effectiveFontSize = fontSize
        if (cursorEffectChanged) resetCursorTracking()
        if (fontGeometryChanged) invalidateViewportMeasurement()
    }

    /** Resets cursor-effect tracking (backend or effect instance change). */
    fun resetCursorTracking() {
        cursorEffectState.reset()
        cursorFramePending = false
    }

    /** Suspends until the backend invalidates the frame; returns the new content version. */
    suspend fun awaitInvalidation(): Int {
        invalidations.receive()
        return contentVersion
    }

    /**
     * Whether the frame loop must keep ticking for a transient cursor effect.
     */
    fun needsFrame(timeSeconds: Float): Boolean {
        val effect = config.cursorEffect ?: return false
        val cursorAnimationActive = cursorEffectState.hasPreviousPosition &&
            timeSeconds - cursorEffectState.changeSeconds <
            effect.maxDurationSeconds + CURSOR_EFFECT_GRACE_SECONDS
        return cursorFramePending || cursorAnimationActive
    }


    /** Number of frames published since attach (monotonic). */
    fun version(): Int = contentVersion

    /**
     * Resizes the backend when the display grid (columns x rows) changes.
     *
     * The grid is derived from the measured cell width using the same
     * raw-width formula as terminal rendering, so
     * pixel drift during a drag-resize that does not cross a cell boundary is coalesced away. This
     * avoids churning every intermediate size through reflow -> SIGWINCH ->
     * full tmux redraw. Once the grid changes, the actual pixel size is still
     * forwarded so the emulator tracks the true viewport.
     */
    internal fun resizeIfNeeded(widthPx: Int, heightPx: Int) {
        val width = widthPx.coerceAtLeast(1)
        val height = heightPx.coerceAtLeast(1)

        val metrics = measureMetrics(effectiveFontSize, config.typeface, width, height)
        // Keep terminal column sizing on the raw measured width. Rounded
        // cellWidthPx remains the visual placement and session metadata size.
        val columns = terminalColumnsForMeasuredCellWidth(width, metrics.measuredCellWidthPx)
        val rows = terminalRowsForMetrics(metrics)
        val cellWidth = metrics.cellWidthPx.toInt().coerceAtLeast(1)
        val cellHeight = metrics.cellHeightPx.toInt().coerceAtLeast(1)
        val contentTop = metrics.lineSpacingAndAscentPx.toInt().coerceAtLeast(0)
        val geometryUnchanged = width == lastResizeWidth &&
            height == lastResizeHeight &&
            columns == lastResizeColumns &&
            rows == lastResizeRows &&
            cellWidth == lastResizeCellWidth &&
            cellHeight == lastResizeCellHeight &&
            contentTop == lastResizeContentTop
        if (geometryUnchanged) return
        lastResizeWidth = width
        lastResizeHeight = height
        lastResizeColumns = columns
        lastResizeRows = rows
        lastResizeCellWidth = cellWidth
        lastResizeCellHeight = cellHeight
        lastResizeContentTop = contentTop
        resetVisualScrollState()
        cursorEffectState.reset()
        cursorFramePending = false
        backend.resize(
            TerminalSize(
                widthPx = width,
                heightPx = height,
                columns = columns,
                rows = rows,
                cellWidthPx = cellWidth,
                cellHeightPx = cellHeight,
                contentTopPx = contentTop
            )
        )
    }

    private fun invalidateViewportMeasurement() {
        lastResizeWidth = -1
        lastResizeHeight = -1
        resetVisualScrollState()
    }

    /** Replays the latest backend publication after a first-frame attach race. */
    fun refresh() {
        if (released) return
        val versionBeforeRefresh = contentVersion
        backend.refresh()
        if (backend.currentFrame() != null && contentVersion == versionBeforeRefresh) {
            onFrameInvalidated()
        }
    }

    /** Latest backend frame, or null before the first invalidation. */
    fun currentFrame(): TerminalFrame? = backend.currentFrame()

    /** Presentation-only remainder used to move GLES content between terminal rows. */
    internal val visualScrollOffsetPx: Float
        get() = currentVisualScrollOffsetPx

    /** Starts a gesture from the settled integer viewport position. */
    internal fun beginScrollGesture() {
        if (released) return
        cancelFling()
        isDragging = true
        discreteScrollAccumulatorPx = 0f
        val frame = backend.currentFrame()
        if (frame != null) synchronizeFractionalScrollMode(frame)
        val initialRow = frame?.topRow ?: 0
        desiredTopRowF = initialRow.toDouble()
        lastSubmittedTopRow = initialRow
        updateVisualScrollOffset(0f, notify = true)
    }

    /** Applies one pan sample while keeping integer viewport mutation on the backend. */
    @Suppress("ReturnCount")
    internal fun applyScrollDelta(deltaPx: Float, cellHeightPx: Float): Int {
        if (released) return 0
        if (!deltaPx.isFinite()) return 0
        if (!cellHeightPx.isFinite() || cellHeightPx <= 0f) return 0
        lastKnownCellHeightPx = cellHeightPx
        val frame = backend.currentFrame() ?: return 0
        synchronizeFractionalScrollMode(frame)
        if (!canUseFractionalScroll(frame)) {
            updateVisualScrollOffset(0f, notify = false)
            val total = discreteScrollAccumulatorPx + deltaPx
            val deltaRows = (total / cellHeightPx).toInt()
            discreteScrollAccumulatorPx = total - deltaRows * cellHeightPx
            return deltaRows
        }

        val maxHistory = frame.transcriptRows.toDouble()
        val nextDesired = desiredTopRowF - (deltaPx / cellHeightPx).toDouble()
        desiredTopRowF = nextDesired.coerceIn(-maxHistory, 0.0)

        val targetIntRow = kotlin.math.round(desiredTopRowF).toInt().coerceIn(-frame.transcriptRows, 0)
        val deltaRows = -(targetIntRow - lastSubmittedTopRow)
        if (targetIntRow != lastSubmittedTopRow) {
            lastSubmittedTopRow = targetIntRow
        }

        val nextOffset = ((frame.topRow.toDouble() - desiredTopRowF) * cellHeightPx).toFloat()
        updateVisualScrollOffset(nextOffset, notify = true)
        return deltaRows
    }

    /** Cancels any active inertial fling coroutine. */
    internal fun cancelFling() {
        flingJob?.cancel()
        flingJob = null
    }

    /** Launches an inertial fling animation decaying smoothly on vsync. */
    internal fun startFling(
        coroutineScope: CoroutineScope,
        initialVelocityPxPerSec: Float,
        cellHeightPx: Float
    ) {
        cancelFling()
        isDragging = false
        val frame = backend.currentFrame()
        if (!canLaunchFling(initialVelocityPxPerSec, cellHeightPx, frame)) return

        flingJob = coroutineScope.launch {
            try {
                var velocity = initialVelocityPxPerSec
                var lastFrameTimeNanos = 0L
                val frictionMultiplier = 4.2f

                while (isActive && abs(velocity) > 30f) {
                    withFrameNanos { frameTimeNanos ->
                        if (lastFrameTimeNanos != 0L) {
                            val dt = ((frameTimeNanos - lastFrameTimeNanos) / 1_000_000_000f).coerceIn(0.001f, 0.05f)
                            val deltaPx = velocity * dt
                            velocity *= exp(-frictionMultiplier * dt)
                            val deltaRows = applyScrollDelta(deltaPx, cellHeightPx)
                            if (deltaRows != 0) {
                                submit(
                                    TerminalCommand.Scroll(
                                        rowsDown = -deltaRows,
                                        xPx = 0f,
                                        yPx = 0f,
                                        geometry = TerminalPointerGeometry(
                                            cellWidthPx = 1f,
                                            cellHeightPx = cellHeightPx,
                                            contentTopPx = 0f,
                                            viewportWidthPx = 1,
                                            viewportHeightPx = 1
                                        )
                                    )
                                )
                            }
                        }
                        lastFrameTimeNanos = frameTimeNanos
                    }
                }
            } finally {
                flingJob = null
                settleVisualScrollOffset()
            }
        }
    }

    /** Settles presentation-only motion before hit-testing or a new gesture. */
    internal fun settleVisualScrollOffset() {
        if (released) return
        isDragging = false
        cancelFling()
        discreteScrollAccumulatorPx = 0f
        val frame = backend.currentFrame()
        val settledRow = frame?.topRow ?: 0
        desiredTopRowF = settledRow.toDouble()
        lastSubmittedTopRow = settledRow
        updateVisualScrollOffset(0f, notify = true)
    }

    /**
     * Returns a complete frame whose grid matches the current measured canvas.
     * A resize command can be asynchronous, so publishing the old grid with
     * new pixel metrics would misalign glyphs, selection, and pointer geometry.
     */
    internal fun currentFrameForMetrics(metrics: TerminalMetrics): TerminalFrame? {
        val frame = backend.currentFrame() ?: return null
        synchronizeFractionalScrollMode(frame)
        val expectedColumns = terminalColumnsForMeasuredCellWidth(
            metrics.viewportWidthPx,
            metrics.measuredCellWidthPx
        )
        val matchesMetrics = frame.columns == expectedColumns &&
            frame.rowsVisible == terminalRowsForMetrics(metrics) &&
            frame.rows.size == frame.rowsVisible &&
            frame.rows.all { it.columns == frame.columns }
        return frame.takeIf { matchesMetrics }
    }

    /** Extracts selection text through the backend's full-content seam. */
    fun selectedText(selection: TerminalSelection): String = backend.selectedText(selection)

    /** Submits an input or navigation command to the backend. */
    fun submit(command: TerminalCommand): TerminalCommandResult {
        if (command !is TerminalCommand.Scroll) {
            settleVisualScrollOffset()
        }
        return backend.submit(command)
    }

    /** Captures cursor movement from one complete frame for a renderer thread. */
    internal fun captureCursorEffectSnapshot(
        frame: TerminalFrame,
        timeSeconds: Float
    ): CursorEffectSnapshot? {
        val effect = config.cursorEffect ?: return null
        cursorEffectState.observe(frame, timeSeconds)
        cursorFramePending = false
        return cursorEffectState.snapshot(effect)
    }

    override fun onFrameInvalidated() {
        if (released) return
        val frame = backend.currentFrame()
        if (frame != null) {
            synchronizeFractionalScrollMode(frame)
            if (canUseFractionalScroll(frame) && lastKnownCellHeightPx > 0f) {
                val rowDiscrepancy = frame.topRow.toDouble() - desiredTopRowF
                if (!isScrollActive || abs(rowDiscrepancy) > 1.5) {
                    desiredTopRowF = frame.topRow.toDouble()
                    lastSubmittedTopRow = frame.topRow
                    updateVisualScrollOffset(0f, notify = false)
                } else {
                    val nextOffset = (rowDiscrepancy * lastKnownCellHeightPx).toFloat()
                    updateVisualScrollOffset(nextOffset, notify = false)
                }
            }
        }
        contentVersion++
        cursorFramePending = true
        invalidations.trySend(Unit)
        onFrameAvailable?.invoke()
        onInvalidated?.invoke()
    }

    override fun onBackendError(error: TerminalBackendError) {
        if (released) return
        onDiagnosticsError(error)
    }

    private fun onDiagnosticsError(error: TerminalBackendError) {
        config.onDiagnostics(
            TerminalDiagnostic.BackendError(error.code, error.message)
        )
    }

    private fun canLaunchFling(
        initialVelocityPxPerSec: Float,
        cellHeightPx: Float,
        frame: TerminalFrame?
    ): Boolean {
        if (released || frame == null) return false
        val validVelocity = initialVelocityPxPerSec.isFinite() && abs(initialVelocityPxPerSec) >= 50f
        val validCellHeight = cellHeightPx.isFinite() && cellHeightPx > 0f
        return validVelocity && validCellHeight && canUseFractionalScroll(frame)
    }

    private fun canUseFractionalScroll(frame: TerminalFrame?): Boolean =
        frame != null && !frame.alternateBufferActive && !frame.mouseTrackingActive

    private fun synchronizeFractionalScrollMode(frame: TerminalFrame) {
        val allowed = canUseFractionalScroll(frame)
        if (fractionalScrollAllowed == allowed) return
        fractionalScrollAllowed = allowed
        resetVisualScrollState()
    }

    private fun resetVisualScrollState() {
        cancelFling()
        discreteScrollAccumulatorPx = 0f
        val frame = backend.currentFrame()
        val initialRow = frame?.topRow ?: 0
        desiredTopRowF = initialRow.toDouble()
        lastSubmittedTopRow = initialRow
        updateVisualScrollOffset(0f, notify = false)
    }

    private fun updateVisualScrollOffset(nextOffsetPx: Float, notify: Boolean) {
        if (currentVisualScrollOffsetPx == nextOffsetPx) return
        currentVisualScrollOffsetPx = nextOffsetPx
        if (notify) onFrameAvailable?.invoke()
    }

    private companion object {
        /** Small settle window after a cursor effect's declared duration. */
        const val CURSOR_EFFECT_GRACE_SECONDS = 0.05f
    }
}

internal fun terminalRowsForMetrics(metrics: TerminalMetrics): Int =
    ((metrics.viewportHeightPx - metrics.lineSpacingAndAscentPx) / metrics.cellHeightPx)
        .toInt()
        .coerceAtLeast(MinGridDimension)
