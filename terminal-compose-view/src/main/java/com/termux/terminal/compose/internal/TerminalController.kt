package com.termux.terminal.compose.internal

import androidx.compose.ui.graphics.GraphicsContext
import androidx.compose.ui.graphics.drawscope.DrawScope
import com.termux.terminal.compose.CursorEffectState
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
 * Owns the backend lifecycle, the retained renderer, shader compilation, and
 * frame scheduling decisions for one [TerminalCanvas].
 *
 * The controller is main-thread confined. [release] is idempotent and releases
 * every row layer, parent layer, bitmap, shader, and backend resource.
 */
internal class TerminalController(
    private val backend: TerminalBackend,
    private val graphicsContext: GraphicsContext
) : TerminalBackendListener {

    /** Invoked by the composable when backend invalidation arrives. */
    var onInvalidated: (() -> Unit)? = null

    private val invalidations = Channel<Unit>(CONFLATED)
    private var contentVersion = 0
    private var config: TerminalCanvasConfig = TerminalCanvasConfig()
    private var attached = false
    private var released = false

    private var rowRenderer: TerminalRowRenderer? = null
    private var renderer: TerminalRenderNodeRenderer? = null
    private var compiledShaders: List<CompiledShader> = emptyList()
    private var shaderCompiler: TerminalShaderCompiler? = null

    private var lastResizeWidth = -1
    private var lastResizeHeight = -1
    private var lastResizeColumns = -1
    private var lastResizeRows = -1

    private val cursorEffectState = CursorEffectState()
    private var cursorFramePending = false

    private var renderKey = RenderKey(0, null, emptyList())

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

    /** Idempotent; releases backend and every render resource. */
    fun release() {
        if (released) return
        released = true
        detach()
        renderer?.release()
        renderer = null
        rowRenderer = null
        compiledShaders = emptyList()
        backend.release()
    }

    /** Applies a new configuration; cheap when nothing relevant changed. */
    fun configure(newConfig: TerminalCanvasConfig) {
        if (newConfig == config) return
        config = newConfig
        if (newConfig.shaders != renderKey.shaders) {
            shaderCompiler = TerminalShaderCompiler(newConfig.onDiagnostics)
            compiledShaders = shaderCompiler!!.compile(newConfig.shaders)
        }
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
     * Whether the frame loop must keep ticking: a continuous shader runs
     * forever; a transient cursor effect runs for its declared duration plus
     * a small grace so its final frame settles.
     */
    fun needsFrame(timeSeconds: Float): Boolean {
        if (hasContinuousShader) return true
        val effect = config.cursorEffect ?: return false
        val cursorAnimationActive = cursorEffectState.hasPreviousPosition &&
            timeSeconds - cursorEffectState.changeSeconds <
            effect.maxDurationSeconds + CURSOR_EFFECT_GRACE_SECONDS
        return cursorFramePending || cursorAnimationActive
    }

    /** True when the compiled shader chain animates continuously. */
    val isContinuouslyAnimated: Boolean
        get() = hasContinuousShader

    /** Number of frames published since attach (monotonic). */
    fun version(): Int = contentVersion

    /**
     * Resizes the backend when the display grid (columns x rows) changes.
     *
     * The grid is derived from the row renderer's measured cell width using
     * the same raw-width formula as the backing [TerminalView.updateSize], so
     * pixel drift during a drag-resize that does not cross a cell boundary is coalesced away. This
     * avoids churning every intermediate size through reflow -> SIGWINCH ->
     * full tmux redraw. Once the grid changes, the actual pixel size is still
     * forwarded so the emulator tracks the true viewport.
     */
    internal fun resizeIfNeeded(widthPx: Int, heightPx: Int) {
        val width = widthPx.coerceAtLeast(1)
        val height = heightPx.coerceAtLeast(1)
        if (width == lastResizeWidth && height == lastResizeHeight) return
        lastResizeWidth = width
        lastResizeHeight = height

        val renderer = this.rowRenderer ?: run {
            // No renderer yet (before the first draw): fall back to pixel-based
            // coalescing so the initial size is applied before the first frame.
            cursorEffectState.reset()
            cursorFramePending = false
            backend.resize(width, height)
            return
        }
        // Keep terminal column sizing on the raw measured width. The hidden
        // migration adapter uses this same policy; rounded cellWidthPx is for
        // visual placement and session cell-width metadata only.
        val columns = terminalColumnsForMeasuredCellWidth(width, renderer.measuredCellWidthPx)
        val rows = ((height - renderer.lineSpacingAndAscentPx) / renderer.lineSpacingPx)
            .coerceAtLeast(MinGridDimension)
        if (columns == lastResizeColumns && rows == lastResizeRows) return
        lastResizeColumns = columns
        lastResizeRows = rows
        cursorEffectState.reset()
        cursorFramePending = false
        backend.resize(width, height)
    }

    /** Latest backend frame, or null before the first invalidation. */
    fun currentFrame(): TerminalFrame? = backend.currentFrame()

    /** Extracts selection text through the backend's full-content seam. */
    fun selectedText(selection: TerminalSelection): String = backend.selectedText(selection)

    /** Submits an input or navigation command to the backend. */
    fun submit(command: TerminalCommand): TerminalCommandResult = backend.submit(command)

    /**
     * Draws the current frame. Resizes the backend when the draw size
     * changed, re-creates the retained renderer on font/typeface/shader
     * changes. [contentVersion] is the composable's snapshot-state invalidation
     * counter: reading it here redraws the canvas when content changes.
     */
    fun draw(
        drawScope: DrawScope,
        selection: TerminalSelection,
        contentVersion: Int,
        timeSeconds: Float
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
            selection = selection,
            timeSeconds = timeSeconds
        )
    }

    /** Draws cursor effects in a separate overlay using the frame it observes. */
    fun drawCursorEffect(
        drawScope: DrawScope,
        metrics: TerminalMetrics,
        timeSeconds: Float
    ) {
        if (released) return
        val effect = config.cursorEffect ?: return
        val frame = backend.currentFrame() ?: return
        cursorEffectState.observe(frame.cursor, timeSeconds)
        cursorFramePending = false
        effect.draw(drawScope, frame, metrics, cursorEffectState, timeSeconds)
    }

    override fun onFrameInvalidated() {
        if (released) return
        contentVersion++
        cursorFramePending = true
        invalidations.trySend(Unit)
        onInvalidated?.invoke()
    }

    override fun onBackendError(error: TerminalBackendError) {
        if (released) return
        onDiagnosticsError(error)
    }

    private fun onDiagnosticsError(error: TerminalBackendError) {
        // Backend errors are surfaced through the same diagnostics channel as
        // shader issues; the consumer owns policy for both.
        config.onDiagnostics(
            TerminalDiagnostic.BackendError(error.code, error.message)
        )
    }

    private val hasContinuousShader: Boolean
        get() = compiledShaders.any { it.definition.usesTimeUniform }

    private fun ensureRenderer(cfg: TerminalCanvasConfig): TerminalRenderNodeRenderer? {
        val newKey = RenderKey(cfg.fontSize, cfg.typeface, cfg.shaders)
        if (renderKey == newKey) {
            return renderer
        }
        renderKey = newKey

        renderer?.release()
        rowRenderer = TerminalRowRenderer(
            typeface = cfg.typeface,
            fontSizePx = cfg.fontSize.toFloat()
        )
        val shaders = compiledShaders.ifEmpty {
            val compiler = TerminalShaderCompiler(cfg.onDiagnostics)
            shaderCompiler = compiler
            compiler.compile(cfg.shaders).also { compiledShaders = it }
        }
        val next = TerminalRenderNodeRenderer(
            graphicsContext = graphicsContext,
            rowRenderer = rowRenderer!!,
            shaders = shaders
        )
        renderer = next
        return next
    }

    private class RenderKey(
        val fontSize: Int,
        val typeface: android.graphics.Typeface?,
        val shaders: List<com.termux.terminal.compose.ShaderDefinition>
    ) {
        override fun equals(other: Any?): Boolean =
            other is RenderKey &&
                fontSize == other.fontSize &&
                typeface == other.typeface &&
                shaders == other.shaders

        override fun hashCode(): Int {
            var result = fontSize
            result = 31 * result + (typeface?.hashCode() ?: 0)
            result = 31 * result + shaders.hashCode()
            return result
        }
    }

    private companion object {
        /** Small settle window after a cursor effect's declared duration. */
        const val CURSOR_EFFECT_GRACE_SECONDS = 0.05f
    }
}
