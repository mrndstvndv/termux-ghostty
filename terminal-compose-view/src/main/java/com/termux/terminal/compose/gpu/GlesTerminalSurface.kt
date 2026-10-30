package com.termux.terminal.compose.gpu

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
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
import androidx.compose.ui.layout.layout
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import com.termux.terminal.compose.CursorEffectSnapshot
import com.termux.terminal.compose.TerminalBlurRegion
import com.termux.terminal.compose.TerminalFrame
import com.termux.terminal.compose.TerminalMetrics
import com.termux.terminal.compose.TerminalSelection
import com.termux.terminal.compose.TerminalWallpaperConfig
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

/** Immutable visual inputs that are not part of a terminal frame. */
data class GlesTerminalVisualConfig(
    val typeface: android.graphics.Typeface? = null,
    val fontSizePx: Float = 14f,
    /** Presentation-only wallpaper. It never invalidates retained row plans. */
    val wallpaper: TerminalWallpaperConfig = TerminalWallpaperConfig(),
    /** Presentation-only backdrop blur regions. They never invalidate retained row plans. */
    val blurRegions: List<TerminalBlurRegion> = emptyList()
) {
    init {
        require(fontSizePx > 0f) { "fontSizePx must be positive" }
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
    val visualOffsetPx: Float = 0f,
    /** Pixels from the top of the surface to the first row. Clear and wallpaper are never shifted. */
    val contentOffsetYPx: Float = 0f,
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
        require(visualOffsetPx.isFinite()) { "visualOffsetPx must be finite" }
        require(contentOffsetYPx.isFinite()) { "contentOffsetYPx must be finite" }
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
@Suppress("TooManyFunctions") // publication, lifecycle and diagnostics seams of one surface
class GlesTerminalSurface(
    onDiagnostic: (GlesTerminalDiagnostic) -> Unit = {}
) {
    private val handoff = TerminalSnapshotHandoff()
    private val lastPublishedSnapshot = AtomicReference<GlesTerminalSnapshot?>(null)
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
    private val tracePublishAttempts = AtomicLong(0L)
    private val traceAcceptedPublications = AtomicLong(0L)
    private val traceDrawCount = AtomicLong(0L)
    private val traceDrawnSequence = AtomicLong(Long.MIN_VALUE)
    private val traceLastDrawAtMillis = AtomicLong(0L)
    private val traceHeartbeat = object : Runnable {
        override fun run() {
            if (released.get() || selectionMagnifierView.get() == null || !isTerminalTraceEnabled()) return
            val nowMillis = SystemClock.elapsedRealtime()
            val lastDrawMillis = traceLastDrawAtMillis.get()
            val snapshot = lastPublishedSnapshot.get()
            Log.d(
                TERMINAL_TRACE_TAG,
                "surface=${System.identityHashCode(this@GlesTerminalSurface)} " +
                    "active=${lifecycleActive.get()} publishAttempts=${tracePublishAttempts.get()} " +
                    "accepted=${traceAcceptedPublications.get()} " +
                    "publishedSequence=${snapshot?.frame?.sequence ?: Long.MIN_VALUE} " +
                    "topRow=${snapshot?.frame?.topRow ?: Int.MIN_VALUE} " +
                    "mouseTracking=${snapshot?.frame?.mouseTrackingActive ?: false} " +
                    "drawCount=${traceDrawCount.get()} drawnSequence=${traceDrawnSequence.get()} " +
                    "lastDrawAgeMs=${if (lastDrawMillis == 0L) -1L else nowMillis - lastDrawMillis}",
            )
            mainHandler.postDelayed(this, TERMINAL_TRACE_INTERVAL_MILLIS)
        }
    }

    /** Publishes one complete frame without blocking the caller. */
    fun publish(snapshot: GlesTerminalSnapshot): Boolean {
        if (released.get()) return false
        if (isTerminalTraceEnabled()) tracePublishAttempts.incrementAndGet()
        lastPublishedSnapshot.set(snapshot)
        if (!handoff.publish(snapshot)) return false
        if (isTerminalTraceEnabled()) {
            traceAcceptedPublications.incrementAndGet()
        }
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
    @Suppress("LongParameterList")
    fun publish(
        frame: TerminalFrame,
        metrics: TerminalMetrics,
        selection: TerminalSelection = TerminalSelection.EMPTY,
        visualOffsetPx: Float = 0f,
        contentOffsetYPx: Float = 0f,
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
            visualOffsetPx = visualOffsetPx,
            contentOffsetYPx = contentOffsetYPx,
            contentRevision = contentRevision,
            presentationRevision = presentationRevision,
            visual = visual
        )
    )

    /**
     * Discards everything a previous frame source published.
     *
     * <p>The next presentation comes from a different frame sequence space, so the revision
     * domain restarts with it and the previous source's pixels are not presented in the meantime.
     */
    fun beginNewSource() {
        if (released.get()) return
        handoff.beginNewSource()
        lastPublishedSnapshot.set(null)
        requestRender.get()?.invoke()
    }

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
        lastPublishedSnapshot.set(null)
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
        mainHandler.removeCallbacks(traceHeartbeat)
        val magnifierView = selectionMagnifierView.getAndSet(null)
        if (magnifierView != null) {
            mainHandler.post(magnifierView::dismissSelectionMagnifier)
        }
        pendingDiagnostic.set(null)
    }

    internal fun isReleased(): Boolean = released.get()

    internal fun acquireSnapshot(): GlesTerminalSnapshot? =
        handoff.acquireOrRetained() ?: lastPublishedSnapshot.get()

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
    internal fun notifyFramePresented(snapshot: GlesTerminalSnapshot) {
        if (isTerminalTraceEnabled()) {
            traceDrawCount.incrementAndGet()
            traceDrawnSequence.set(snapshot.frame.sequence)
            traceLastDrawAtMillis.set(SystemClock.elapsedRealtime())
        }
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
        mainHandler.removeCallbacks(traceHeartbeat)
        if (isTerminalTraceEnabled()) mainHandler.post(traceHeartbeat)
        lifecycleActive(this.lifecycleActive.get())
        selectionMagnifierRequest.get()?.let { pending ->
            view.showSelectionMagnifier(pending.sourceCenterX, pending.sourceCenterY)
        }
        if (handoff.hasPending() || handoff.hasRetained() || lastPublishedSnapshot.get() != null) {
            request()
        }
    }

    internal fun detachView(view: GlesTerminalSurfaceView) {
        if (!selectionMagnifierView.compareAndSet(view, null)) return
        mainHandler.removeCallbacks(traceHeartbeat)
        view.dismissSelectionMagnifier()
        requestRender.set(null)
        releaseGlResources.set(null)
        lifecycleDecision.set(null)
    }

    internal fun setLifecycleActive(active: Boolean) {
        val previous = lifecycleActive.getAndSet(active)
        if (released.get()) return
        if (previous != active && isTerminalTraceEnabled()) {
            Log.d(
                TERMINAL_TRACE_TAG,
                "surface=${System.identityHashCode(this)} lifecycle active=$active",
            )
        }
        lifecycleDecision.get()?.invoke(active)
    }

    internal fun reportDiagnostic(diagnostic: GlesTerminalDiagnostic) {
        if (released.get()) return
        if (diagnostic is GlesTerminalDiagnostic.Error && isTerminalTraceEnabled()) {
            Log.d(
                TERMINAL_TRACE_TAG,
                "surface=${System.identityHashCode(this)} glError stage=${diagnostic.stage} " +
                    "generation=${diagnostic.state.generation}",
            )
        }
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

private fun isTerminalTraceEnabled(): Boolean = Log.isLoggable(TERMINAL_TRACE_TAG, Log.DEBUG)

private const val TERMINAL_TRACE_TAG = "TylTerminalTrace"
private const val TERMINAL_TRACE_INTERVAL_MILLIS = 1_000L

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
        val stableHeight = remember { StableSurfaceHeight() }
        AndroidView(
            modifier = modifier.stableSurfaceHeight(stableHeight),
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
            }
        }
    }
}

private class StableSurfaceHeight {
    var width = -1
    var height = 0
}

/**
 * Keeps the GL surface at the tallest height seen for the current width, top-aligned.
 *
 * A SurfaceView buffer lags its view bounds by a frame or more, and any part of the hole it
 * does not cover shows black. With edge-to-edge insets the IME animation resizes the canvas
 * every frame; holding the surface size makes the IME cover and uncover already-drawn pixels.
 * Rows are positioned from the top of the viewport, so the extra height only adds background.
 */
private fun Modifier.stableSurfaceHeight(state: StableSurfaceHeight): Modifier =
    layout { measurable, constraints ->
        if (!constraints.hasBoundedWidth || !constraints.hasBoundedHeight) {
            val placeable = measurable.measure(constraints)
            return@layout layout(placeable.width, placeable.height) { placeable.place(0, 0) }
        }
        val width = constraints.maxWidth
        val height = constraints.maxHeight
        state.height = if (width == state.width) maxOf(state.height, height) else height
        state.width = width
        val placeable = measurable.measure(Constraints.fixed(width, state.height))
        layout(width, height) { placeable.place(0, 0) }
    }
