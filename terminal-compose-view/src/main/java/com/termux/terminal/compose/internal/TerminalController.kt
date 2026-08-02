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

    private val cursorEffectState = CursorEffectState()

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
    }

    /** Suspends until the backend invalidates the frame; returns the new content version. */
    suspend fun awaitInvalidation(): Int {
        invalidations.receive()
        return contentVersion
    }

    /** Advances cursor-effect tracking for the current frame. */
    fun tick(timeSeconds: Float) {
        val frame = backend.currentFrame() ?: return
        cursorEffectState.observe(frame.cursor.column, frame.cursor.row, timeSeconds)
    }

    /**
     * Whether the frame loop must keep ticking: a continuous shader runs
     * forever; a transient cursor effect runs for its declared duration plus
     * a small grace so its final frame settles.
     */
    fun needsFrame(timeSeconds: Float): Boolean {
        val effect = config.cursorEffect
        return if (hasContinuousShader) {
            true
        } else if (effect != null && cursorEffectState.hasPreviousPosition) {
            timeSeconds - cursorEffectState.changeSeconds <
                effect.maxDurationSeconds + CURSOR_EFFECT_GRACE_SECONDS
        } else {
            false
        }
    }

    /** True when the compiled shader chain animates continuously. */
    val isContinuouslyAnimated: Boolean
        get() = hasContinuousShader

    /** Number of frames published since attach (monotonic). */
    fun version(): Int = contentVersion

    /** Resizes before reading the first frame because sizing may create that frame. */
    internal fun resizeIfNeeded(widthPx: Int, heightPx: Int) {
        val width = widthPx.coerceAtLeast(1)
        val height = heightPx.coerceAtLeast(1)
        if (width == lastResizeWidth && height == lastResizeHeight) return

        lastResizeWidth = width
        lastResizeHeight = height
        cursorEffectState.reset()
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
     * changes, and renders the cursor effect overlay last. [contentVersion]
     * is the composable's snapshot-state invalidation counter: reading it here
     * redraws the canvas when content changes.
     */
    fun draw(
        drawScope: DrawScope,
        metrics: TerminalMetrics,
        selection: TerminalSelection,
        contentVersion: Int,
        timeSeconds: Float
    ) {
        if (released) return
        resizeIfNeeded(
            widthPx = drawScope.size.width.toInt(),
            heightPx = drawScope.size.height.toInt()
        )

        val frame = backend.currentFrame() ?: return
        val cfg = config
        val renderer = ensureRenderer(cfg) ?: return
        renderer.draw(
            drawScope = drawScope,
            frame = frame,
            contentVersion = contentVersion,
            selection = selection,
            timeSeconds = timeSeconds
        )
        cfg.cursorEffect?.draw(drawScope, frame, metrics, cursorEffectState, timeSeconds)
    }

    override fun onFrameInvalidated() {
        if (released) return
        contentVersion++
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
