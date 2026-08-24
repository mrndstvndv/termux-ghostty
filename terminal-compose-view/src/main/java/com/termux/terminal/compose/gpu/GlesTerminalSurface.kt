package com.termux.terminal.compose.gpu

import android.os.Handler
import android.os.Looper
import android.view.View
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import com.termux.terminal.compose.ShaderDefinition
import com.termux.terminal.compose.CursorEffectSnapshot
import com.termux.terminal.compose.TerminalFrame
import com.termux.terminal.compose.TerminalMetrics
import com.termux.terminal.compose.TerminalSelection
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/**
 * Immutable visual inputs that are not part of a terminal frame.
 *
 * The shader list is intentionally named AGSL: the existing Compose shader
 * contract is not a GLSL ES contract and is therefore reported as unsupported
 * by the GLES surface rather than being compiled accidentally.
 */
class GlesTerminalVisualConfig(
    val typeface: android.graphics.Typeface? = null,
    val fontSizePx: Float = 14f,
    agslShaders: List<ShaderDefinition> = emptyList()
) {
    val agslShaders: List<ShaderDefinition> = agslShaders.toList()

    init {
        require(fontSizePx > 0f) { "fontSizePx must be positive" }
    }

    override fun equals(other: Any?): Boolean =
        other is GlesTerminalVisualConfig &&
            typeface == other.typeface &&
            fontSizePx == other.fontSizePx &&
            agslShaders == other.agslShaders

    override fun hashCode(): Int {
        var result = typeface?.hashCode() ?: 0
        result = 31 * result + fontSizePx.hashCode()
        result = 31 * result + agslShaders.hashCode()
        return result
    }
}

/**
 * One complete immutable publication consumed by the GLES renderer.
 *
 * [frame] is the complete Compose terminal publication, never a transport
 * delta. [presentationRevision] must change when overlays or geometry change
 * without a new terminal sequence. The caller owns the frame's immutability
 * contract described by [TerminalFrame].
 */
data class GlesTerminalSnapshot(
    val frame: TerminalFrame,
    val metrics: TerminalMetrics,
    val selection: TerminalSelection = TerminalSelection.EMPTY,
    val cursorEffect: CursorEffectSnapshot? = null,
    val viewportWidthPx: Int = metrics.viewportWidthPx,
    val viewportHeightPx: Int = metrics.viewportHeightPx,
    val contentRevision: Long = frame.sequence,
    val presentationRevision: Long = contentRevision,
    val visual: GlesTerminalVisualConfig = GlesTerminalVisualConfig(
        fontSizePx = metrics.fontSizePx
    )
) {
    init {
        require(viewportWidthPx > 0) { "viewportWidthPx must be positive" }
        require(viewportHeightPx > 0) { "viewportHeightPx must be positive" }
        require(frame.rows.size == frame.rowsVisible) {
            "GlesTerminalSnapshot requires a complete visible row set"
        }
        require(frame.rows.all { it.columns == frame.columns }) {
            "GlesTerminalSnapshot requires rows with matching geometry"
        }
    }

    /** Returns a presentation-only copy for an intentional animation tick. */
    fun withPresentationRevision(revision: Long): GlesTerminalSnapshot =
        copy(presentationRevision = revision)
}

/** Bounded atlas statistics included in every renderer diagnostic. */
data class GlesAtlasDiagnostics(
    val generation: Int,
    val pageCount: Int,
    val maxPages: Int,
    val pageSizePx: Int,
    val usedAreaPx: Int,
    val entryCount: Int,
    val maxEntries: Int,
    val cacheHits: Long,
    val cacheMisses: Long,
    val resetCount: Long,
    val largestAllocationPx: Int
) {
    companion object {
        val EMPTY = GlesAtlasDiagnostics(
            generation = 0,
            pageCount = 0,
            maxPages = 0,
            pageSizePx = 0,
            usedAreaPx = 0,
            entryCount = 0,
            maxEntries = 0,
            cacheHits = 0,
            cacheMisses = 0,
            resetCount = 0,
            largestAllocationPx = 0
        )
    }
}

/** Frame counters and the last immutable publication presented by the GLES surface. */
data class GlesFrameDiagnostics(
    val drawCount: Long,
    /** Compatibility field counting redundant callbacks that still rendered a full frame. */
    val skippedDrawCount: Long,
    val terminalSequence: Long,
    val contentRevision: Long,
    val presentationRevision: Long,
    val surfaceGeneration: Long
) {
    companion object {
        val EMPTY = GlesFrameDiagnostics(
            drawCount = 0,
            skippedDrawCount = 0,
            terminalSequence = Long.MIN_VALUE,
            contentRevision = Long.MIN_VALUE,
            presentationRevision = Long.MIN_VALUE,
            surfaceGeneration = 0
        )
    }
}

/** Immutable GL state attached to every diagnostic event. */
data class GlesDiagnosticState(
    val vendor: String,
    val renderer: String,
    val version: String,
    val shadingLanguageVersion: String,
    val generation: Long,
    val atlas: GlesAtlasDiagnostics,
    val frame: GlesFrameDiagnostics,
    val error: String?
) {
    companion object {
        val EMPTY = GlesDiagnosticState(
            vendor = "unknown",
            renderer = "unknown",
            version = "unknown",
            shadingLanguageVersion = "unknown",
            generation = 0,
            atlas = GlesAtlasDiagnostics.EMPTY,
            frame = GlesFrameDiagnostics.EMPTY,
            error = null
        )
    }
}

/** Structured, main-thread-delivered diagnostics from the GLES surface. */
sealed interface GlesTerminalDiagnostic {
    val state: GlesDiagnosticState

    data class State(override val state: GlesDiagnosticState) : GlesTerminalDiagnostic

    data class UnsupportedAgsl(
        override val state: GlesDiagnosticState,
        val shaderIds: List<String>,
        val reason: String
    ) : GlesTerminalDiagnostic {
        init {
            require(shaderIds.isNotEmpty()) { "shaderIds must not be empty" }
        }
    }

    data class Error(
        override val state: GlesDiagnosticState,
        val stage: String,
        val message: String
    ) : GlesTerminalDiagnostic
}

/**
 * Public owner of one GLES terminal surface's bounded publication handoff.
 *
 * Publishing is latest-wins and non-blocking. The class contains no backend,
 * session, JNI, or Compose state and can be fed by the existing controller on
 * the main thread. [release] is idempotent and rejects later publications.
 * The renderer keeps a bounded row-packet cache so sparse frame updates do not
 * remeasure and rebuild unchanged visible rows.
 */
class GlesTerminalSurface(
    onDiagnostic: (GlesTerminalDiagnostic) -> Unit = {}
) {
    private val handoff = TerminalSnapshotHandoff()
    private val released = AtomicBoolean(false)
    private val diagnosticListener = AtomicReference<(GlesTerminalDiagnostic) -> Unit>(onDiagnostic)
    private val requestRender = AtomicReference<(() -> Unit)?>(null)
    private val releaseGlResources = AtomicReference<(() -> Unit)?>(null)
    private val lifecycleDecision = AtomicReference<((Boolean) -> Unit)?>(null)
    private val lifecycleActive = AtomicBoolean(false)
    private val animationTimeSeconds = AtomicReference(0f)
    private val cursorAnimationDeadlineSeconds = AtomicReference(Float.NEGATIVE_INFINITY)
    private val atlasResetRequested = AtomicBoolean(false)
    private val mainHandler = Handler(Looper.getMainLooper())
    private val pendingDiagnostic = AtomicReference<GlesTerminalDiagnostic?>(null)
    private val diagnosticPosted = AtomicBoolean(false)
    private val selectionMagnifierView = AtomicReference<GlesTerminalSurfaceView?>(null)
    private val selectionMagnifierRequest = AtomicReference<SelectionMagnifierRequest?>(null)
    private val magnifierUpdatePosted = AtomicBoolean(false)

    /** Publishes one complete frame without blocking the caller. */
    fun publish(snapshot: GlesTerminalSnapshot): Boolean {
        if (!handoff.publish(snapshot)) return false
        val cursorEffect = snapshot.cursorEffect
        val cursorDeadline = cursorEffect?.let {
            it.changeSeconds + it.effect.maxDurationSeconds
        } ?: Float.NEGATIVE_INFINITY
        val previousDeadline = cursorAnimationDeadlineSeconds.getAndSet(cursorDeadline)
        if (cursorDeadline > previousDeadline) {
            animationTimeSeconds.set(cursorEffect?.changeSeconds ?: 0f)
        }
        requestRender.get()?.invoke()
        return true
    }

    /** Convenience publication overload for controller integration. */
    fun publish(
        frame: TerminalFrame,
        metrics: TerminalMetrics,
        selection: TerminalSelection = TerminalSelection.EMPTY,
        cursorEffect: CursorEffectSnapshot? = null,
        contentRevision: Long = frame.sequence,
        presentationRevision: Long = contentRevision,
        visual: GlesTerminalVisualConfig = GlesTerminalVisualConfig(
            fontSizePx = metrics.fontSizePx
        )
    ): Boolean = publish(
        GlesTerminalSnapshot(
            frame = frame,
            metrics = metrics,
            cursorEffect = cursorEffect,
            selection = selection,
            contentRevision = contentRevision,
            presentationRevision = presentationRevision,
            visual = visual
        )
    )

    /** Requests a presentation-only draw; it never changes terminal state. */
    fun requestAnimationFrame(timeSeconds: Float) {
        if (released.get()) return
        animationTimeSeconds.set(timeSeconds)
        requestRender.get()?.invoke()
    }

    /** Requests a bounded atlas reset for deterministic diagnostics/laboratory scenes. */
    fun requestAtlasReset() {
        if (released.get()) return
        atlasResetRequested.set(true)
        requestRender.get()?.invoke()
    }

    /** Replaces the diagnostic listener. Passing null disables callbacks. */
    fun setDiagnosticListener(listener: ((GlesTerminalDiagnostic) -> Unit)?) {
        if (listener == null) {
            diagnosticListener.set { }
        } else {
            diagnosticListener.set(listener)
        }
    }

    /** Releases the handoff and schedules GL resource release exactly once. */
    @Suppress("TooGenericExceptionCaught")
    fun release() {
        if (!released.compareAndSet(false, true)) return
        handoff.release()
        val releaseResources = releaseGlResources.getAndSet(null)
        val request = requestRender.getAndSet(null)
        val lifecycle = lifecycleDecision.getAndSet(null)
        try {
            releaseResources?.invoke()
        } catch (_: RuntimeException) {
            // The view disposal path queues a second idempotent release if needed.
        }
        try {
            request?.invoke()
        } catch (_: RuntimeException) {
            // A released SurfaceView may reject a late render request.
        }
        try {
            lifecycle?.invoke(false)
        } catch (_: RuntimeException) {
            // The AndroidView release path performs an idempotent pause as well.
        }
        selectionMagnifierRequest.set(null)
        val magnifierView = selectionMagnifierView.getAndSet(null)
        if (magnifierView != null) {
            mainHandler.post(magnifierView::dismissSelectionMagnifier)
        }
        pendingDiagnostic.set(null)
    }

    internal fun isReleased(): Boolean = released.get()

    internal fun acquireSnapshot(): GlesTerminalSnapshot? = handoff.acquire()

    internal fun animationTimeSeconds(): Float = animationTimeSeconds.get()

    internal fun needsCursorAnimationFrame(timeSeconds: Float): Boolean =
        timeSeconds <= cursorAnimationDeadlineSeconds.get()

    internal fun consumeAtlasReset(): Boolean = atlasResetRequested.compareAndSet(true, false)

    internal fun showSelectionMagnifier(sourceCenterX: Float, sourceCenterY: Float) {
        if (released.get()) return
        val request = SelectionMagnifierRequest(sourceCenterX, sourceCenterY)
        selectionMagnifierRequest.set(request)
        selectionMagnifierView.get()?.showSelectionMagnifier(sourceCenterX, sourceCenterY)
    }

    internal fun dismissSelectionMagnifier() {
        selectionMagnifierRequest.set(null)
        selectionMagnifierView.get()?.dismissSelectionMagnifier()
    }

    /** Schedules a main-thread content refresh after a complete GL frame is presented. */
    internal fun notifyFramePresented() {
        if (released.get() || selectionMagnifierRequest.get() == null) return
        if (!magnifierUpdatePosted.compareAndSet(false, true)) return
        mainHandler.post {
            if (released.get() || selectionMagnifierRequest.get() == null) {
                magnifierUpdatePosted.set(false)
                return@post
            }
            val view = selectionMagnifierView.get()
            if (view == null) {
                magnifierUpdatePosted.set(false)
                return@post
            }
            // GLSurfaceView swaps after onDrawFrame returns. Wait for the next UI frame so
            // Magnifier.update() copies the newly swapped buffer rather than the previous one.
            view.postOnAnimation {
                magnifierUpdatePosted.set(false)
                if (released.get() || selectionMagnifierRequest.get() == null) return@postOnAnimation
                if (selectionMagnifierView.get() === view) {
                    view.updateSelectionMagnifierContent()
                }
            }
        }
    }

    internal fun attachView(
        view: GlesTerminalSurfaceView,
        request: () -> Unit,
        releaseResources: () -> Unit,
        lifecycleActive: (Boolean) -> Unit
    ) {
        if (released.get()) return
        selectionMagnifierView.set(view)
        requestRender.set(request)
        releaseGlResources.set(releaseResources)
        lifecycleDecision.set(lifecycleActive)
        lifecycleActive(this.lifecycleActive.get())
        selectionMagnifierRequest.get()?.let { pending ->
            view.showSelectionMagnifier(pending.sourceCenterX, pending.sourceCenterY)
        }
        if (handoff.hasPending()) request()
    }

    internal fun detachView(view: GlesTerminalSurfaceView) {
        if (!selectionMagnifierView.compareAndSet(view, null)) return
        view.dismissSelectionMagnifier()
        requestRender.set(null)
        releaseGlResources.set(null)
        lifecycleDecision.set(null)
    }

    internal fun setLifecycleActive(active: Boolean) {
        lifecycleActive.set(active)
        if (released.get()) return
        lifecycleDecision.get()?.invoke(active)
    }

    internal fun reportDiagnostic(diagnostic: GlesTerminalDiagnostic) {
        if (released.get()) return
        pendingDiagnostic.set(diagnostic)
        if (!diagnosticPosted.compareAndSet(false, true)) return
        mainHandler.post(::deliverLatestDiagnostic)
    }

    private fun deliverLatestDiagnostic() {
        diagnosticPosted.set(false)
        if (released.get()) {
            pendingDiagnostic.set(null)
            return
        }
        val diagnostic = pendingDiagnostic.getAndSet(null) ?: return
        diagnosticListener.get().invoke(diagnostic)
        if (pendingDiagnostic.get() != null && diagnosticPosted.compareAndSet(false, true)) {
            mainHandler.post(::deliverLatestDiagnostic)
        }
    }
}

private data class SelectionMagnifierRequest(
    val sourceCenterX: Float,
    val sourceCenterY: Float
)

/** Creates and owns a GLES surface controller for a composable host. */
@Composable
fun rememberGlesTerminalSurface(
    surfaceKey: Any? = Unit,
    onDiagnostic: (GlesTerminalDiagnostic) -> Unit = {}
): GlesTerminalSurface {
    val currentListener by rememberUpdatedState(onDiagnostic)
    val surface = remember(surfaceKey) { GlesTerminalSurface() }
    SideEffect { surface.setDiagnosticListener(currentListener) }
    DisposableEffect(surface) {
        onDispose { surface.release() }
    }
    return surface
}

/**
 * Opaque, non-input GLES content surface.
 *
 * Put the existing transparent input/semantics/selection layers above this
 * composable. The surface owns only pixels and lifecycle; it does not receive
 * terminal commands or inspect a backend.
 */
@Composable
fun GlesTerminalSurface(
    surface: GlesTerminalSurface,
    modifier: Modifier = Modifier,
    contentDescription: String? = null
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val lifecycle = lifecycleOwner.lifecycle
    key(surface) {
        AndroidView(
            modifier = modifier,
            factory = { GlesTerminalSurfaceView(context, surface) },
            update = { view ->
                view.contentDescription = contentDescription
                view.importantForAccessibility = if (contentDescription == null) {
                    View.IMPORTANT_FOR_ACCESSIBILITY_NO
                } else {
                    View.IMPORTANT_FOR_ACCESSIBILITY_YES
                }
                view.setLifecycleActive(
                    lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)
                )
            },
            onRelease = { view -> view.dispose() }
        )
        DisposableEffect(surface, lifecycleOwner) {
            val observer = LifecycleEventObserver { _, event ->
                surface.setLifecycleActive(
                    when (event) {
                        Lifecycle.Event.ON_RESUME -> true
                        Lifecycle.Event.ON_PAUSE,
                        Lifecycle.Event.ON_STOP,
                        Lifecycle.Event.ON_DESTROY -> false
                        else -> lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)
                    }
                )
            }
            lifecycle.addObserver(observer)
            surface.setLifecycleActive(
                lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)
            )
            onDispose {
                lifecycle.removeObserver(observer)
                surface.release()
            }
        }
    }
}
