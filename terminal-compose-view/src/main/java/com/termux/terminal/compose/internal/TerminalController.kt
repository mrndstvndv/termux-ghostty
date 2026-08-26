package com.termux.terminal.compose.internal

import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.GraphicsContext
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.drawscope.DrawScope
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
import com.termux.terminal.compose.TerminalSelection
import com.termux.terminal.compose.TerminalSize
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.Channel.Factory.CONFLATED

private const val MinGridDimension = 4

/**
 * Converts a viewport width to columns using the raw measured cell width.
 * Visual geometry intentionally uses the rounded cell width instead.
 */
internal fun terminalColumnsForMeasuredCellWidth(widthPx: Int, measuredCellWidthPx: Float): Int =
    (widthPx / measuredCellWidthPx).toInt().coerceAtLeast(MinGridDimension)

/**
 * Owns the backend lifecycle, retained renderer, cursor effects, and frame scheduling
 * decisions for one [TerminalCanvas].
 *
 * The controller is main-thread confined. [release] is idempotent and releases
 * every row layer, parent layer, and cursor resource. Backend ownership remains
 * with the host that supplied it.
 */
@Suppress("TooManyFunctions") // lifecycle and rendering responsibilities share one backend owner
internal class TerminalController(
    private val backend: TerminalBackend,
    private val graphicsContext: GraphicsContext,
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

    /** Publishes complete frames to non-Compose renderers without recomposition. */
    var onFrameAvailable: (() -> Unit)? = null

    private val invalidations = Channel<Unit>(CONFLATED)
    private var contentVersion = 0
    private var config: TerminalCanvasConfig = TerminalCanvasConfig()
    private var effectiveFontSize = config.fontSize
    private var attached = false
    private var released = false

    private var rowRenderer: TerminalRowRenderer? = null
    private var renderer: TerminalRenderNodeRenderer? = null

    private var lastResizeWidth = -1
    private var lastResizeHeight = -1
    private var lastResizeColumns = -1
    private var lastResizeRows = -1
    private var lastResizeCellWidth = -1
    private var lastResizeCellHeight = -1
    private var lastResizeContentTop = -1

    private val cursorEffectState = CursorEffectState()
    private var cursorFramePending = false
    private val cursorEffectPlan = CursorEffectRenderPlan()
    private val cursorEffectPath = Path()

    private var renderKey = RenderKey(0, null)

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
        detach()
        renderer?.release()
        renderer = null
        rowRenderer = null
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
     * The grid is derived from the row renderer's measured cell width using
     * the same raw-width formula as terminal rendering, so
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

    /**
     * Returns a complete frame whose grid matches the current measured canvas.
     * A resize command can be asynchronous, so publishing the old grid with
     * new pixel metrics would misalign glyphs, selection, and pointer geometry.
     */
    internal fun currentFrameForMetrics(metrics: TerminalMetrics): TerminalFrame? {
        val frame = backend.currentFrame() ?: return null
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
    fun submit(command: TerminalCommand): TerminalCommandResult = backend.submit(command)

    /**
     * Draws the current frame. Resizes the backend when the draw size
     * changed and re-creates the retained renderer on font/typeface changes.
     * [contentVersion] is the composable's snapshot-state invalidation counter:
     * reading it here redraws the canvas when content changes.
     */
    fun draw(
        drawScope: DrawScope,
        selection: TerminalSelection,
        contentVersion: Int
    ) {
        if (released) return
        val cfg = config
        val renderer = ensureRenderer(cfg) ?: return
        resizeIfNeeded(
            widthPx = drawScope.size.width.toInt(),
            heightPx = drawScope.size.height.toInt()
        )

        val frame = backend.currentFrame() ?: return
        renderer.draw(
            drawScope = drawScope,
            frame = frame,
            contentVersion = contentVersion,
            selection = selection
        )
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

    /** Draws the shared cursor-effect geometry through the Compose adapter. */
    fun drawCursorEffect(
        drawScope: DrawScope,
        metrics: TerminalMetrics,
        timeSeconds: Float
    ) {
        if (released) return
        val frame = backend.currentFrame() ?: return
        val effectSnapshot = captureCursorEffectSnapshot(frame, timeSeconds) ?: return
        if (planCursorEffect(effectSnapshot, frame, metrics, timeSeconds, cursorEffectPlan)) {
            drawCursorEffectPlan(drawScope)
        }
    }

    private fun drawCursorEffectPlan(drawScope: DrawScope) {
        cursorEffectPath.reset()
        cursorEffectPath.fillType = PathFillType.EvenOdd
        cursorEffectPath.moveTo(cursorEffectPlan.vertices[0], cursorEffectPlan.vertices[1])
        for (index in 1 until cursorEffectPlan.vertexCount) {
            val offset = index * 2
            cursorEffectPath.lineTo(
                cursorEffectPlan.vertices[offset],
                cursorEffectPlan.vertices[offset + 1]
            )
        }
        cursorEffectPath.close()
        cursorEffectPath.addRect(
            Rect(
                cursorEffectPlan.cutoutLeft,
                cursorEffectPlan.cutoutTop,
                cursorEffectPlan.cutoutRight,
                cursorEffectPlan.cutoutBottom
            )
        )
        drawScope.drawPath(cursorEffectPath, Color(cursorEffectPlan.argb))
    }

    override fun onFrameInvalidated() {
        if (released) return
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


    private fun ensureRenderer(cfg: TerminalCanvasConfig): TerminalRenderNodeRenderer? {
        val newKey = RenderKey(effectiveFontSize, cfg.typeface)
        if (renderKey == newKey) {
            return renderer
        }
        renderKey = newKey

        renderer?.release()
        rowRenderer = TerminalRowRenderer(
            typeface = cfg.typeface,
            fontSizePx = effectiveFontSize.toFloat()
        )
        val next = TerminalRenderNodeRenderer(
            graphicsContext = graphicsContext,
            rowRenderer = rowRenderer!!
        )
        renderer = next
        return next
    }

    private class RenderKey(
        val fontSize: Int,
        val typeface: android.graphics.Typeface?
    ) {
        override fun equals(other: Any?): Boolean =
            other is RenderKey &&
                fontSize == other.fontSize &&
                typeface == other.typeface

        override fun hashCode(): Int =
            31 * fontSize + (typeface?.hashCode() ?: 0)
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
