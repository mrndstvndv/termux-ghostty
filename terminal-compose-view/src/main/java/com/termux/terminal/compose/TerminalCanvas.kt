package com.termux.terminal.compose

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableFloatState
import androidx.compose.runtime.MutableIntState
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusTarget
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.GraphicsContext
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalGraphicsContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.preferredFrameRate
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.text
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.zIndex
import com.termux.terminal.compose.internal.CommandTerminalInput
import com.termux.terminal.compose.internal.ImeEditCommandProcessor
import com.termux.terminal.compose.internal.ImeHost
import com.termux.terminal.compose.internal.TerminalController
import com.termux.terminal.compose.internal.TerminalInputTranslator
import com.termux.terminal.compose.internal.SelectionHandleEndpoint
import com.termux.terminal.compose.internal.TerminalSelectionActionMode
import com.termux.terminal.compose.internal.TerminalSelectionOverlay
import com.termux.terminal.compose.internal.TerminalSelectionState
import com.termux.terminal.compose.internal.rememberImeHost
import com.termux.terminal.compose.internal.rememberSelectionHandleColor
import com.termux.terminal.compose.internal.terminalGestures
import com.termux.terminal.compose.internal.selectionMagnifierSourceForSelection
import com.termux.terminal.compose.internal.terminalImeHost
import com.termux.terminal.compose.internal.terminalSelectionMagnifier
import com.termux.terminal.compose.internal.terminalTaps
import com.termux.terminal.compose.internal.updateSelectionHandle

/**
 * How long after controller attach to issue the initial-frame settle repaint.
 * Long enough to let a just-published frame land in the backend store, short
 * enough that the repaint is imperceptible.
 */
private const val AttachSettleMillis = 64L
private const val PixelLayerZIndex = 0f
private const val InputLayerZIndex = 1f
private const val CursorEffectZIndex = 2f
private const val SelectionLayerZIndex = 3f

/**
 * Compose terminal canvas.
 *
 * Owns rendering, input/IME translation, selection gestures, links, scrolling,
 * focus, and accessibility seams. The backend stays neutral: all session
 * interaction goes through [TerminalBackend] commands and immutable
 * [TerminalFrame] snapshots. All resources are released when the canvas leaves
 * composition (release is idempotent).
 */
@Composable
fun TerminalCanvas(
    backend: TerminalBackend,
    modifierKeys: ModifierKeyReader,
    config: TerminalCanvasConfig,
    requestFocus: Boolean = false,
    requestFocusKey: Long = 0L,
    requestImeKey: Long = 0L,
    modifier: Modifier = Modifier
) {
    val graphicsContext = LocalGraphicsContext.current
    val selectionState = remember(backend) { TerminalSelectionState() }
    val hapticFeedback = LocalHapticFeedback.current
    val focusRequester = remember { FocusRequester() }

    val contentVersionState = remember { mutableIntStateOf(0) }
    val frameTimeState = remember { mutableFloatStateOf(0f) }
    val fontSizeState = rememberFontSizeState(config)
    var viewportSizePx by remember { mutableStateOf(IntSize.Zero) }
    var canvasPositionInWindow by remember { mutableStateOf(Offset.Zero) }

    val controller = rememberConfiguredController(
        backend, graphicsContext, frameTimeState, fontSizeState, config,
        onInvalidated = { contentVersionState.intValue++ }
    )
    // Initial-frame safety net: a frame published before the canvas attached
    // (or while the backend was still wiring itself to the session) never
    // delivers an invalidation. One settle repaint, shortly after attach,
    // guarantees the first frame paints even if that event was lost.
    LaunchedEffect(controller) {
        kotlinx.coroutines.delay(AttachSettleMillis)
        controller.refresh()
        contentVersionState.intValue++
    }
    val (translator, imeHost) = rememberInputPipeline(
        controller = controller,
        modifierKeys = modifierKeys,
        onCodePoint = config.onCodePoint,
        onImeSessionClosed = config.onImeSessionClosed
    )
    val metrics = rememberCanvasMetrics(fontSizeState, config, viewportSizePx)
    LaunchedEffect(requestFocus, requestFocusKey, requestImeKey) {
        if (requestFocus || requestImeKey != 0L) focusRequester.requestFocus()
        if (requestImeKey != 0L) imeHost.open()
    }
    LaunchedEffect(config.selectionResetKey) {
        selectionState.clear()
    }

    reportSelectionChanges(config, selectionState, controller, metrics)
    val visibleText = rememberVisibleText(controller, contentVersionState, config.accessibilityEnabled)

    TerminalCanvasLayout(
        modifier = modifier,
        state = TerminalCanvasState(
            config = config,
            visibleText = visibleText,
            controller = controller,
            metrics = metrics,
            translator = translator,
            selectionState = selectionState,
            fontSizeState = fontSizeState,
            imeHost = imeHost,
            focusRequester = focusRequester,
            hapticFeedback = hapticFeedback,
            contentVersionState = contentVersionState,
            frameTimeState = frameTimeState,
            canvasPositionInWindow = canvasPositionInWindow
        ),
        onViewportSizeChanged = { viewportSizePx = it },
        onCanvasPositionChanged = { canvasPositionInWindow = it }
    )
}

private data class TerminalCanvasState(
    val config: TerminalCanvasConfig,
    val visibleText: String,
    val controller: TerminalController,
    val metrics: TerminalMetrics,
    val translator: TerminalInputTranslator,
    val selectionState: TerminalSelectionState,
    val fontSizeState: MutableIntState,
    val imeHost: ImeHost,
    val focusRequester: FocusRequester,
    val hapticFeedback: androidx.compose.ui.hapticfeedback.HapticFeedback,
    val contentVersionState: MutableIntState,
    val frameTimeState: MutableFloatState,
    val canvasPositionInWindow: Offset
)

@Suppress("LongMethod")
@Composable
private fun TerminalCanvasLayout(
    modifier: Modifier,
    state: TerminalCanvasState,
    onViewportSizeChanged: (IntSize) -> Unit,
    onCanvasPositionChanged: (Offset) -> Unit
) {
    var magnifierEndpoint by remember { mutableStateOf<SelectionHandleEndpoint?>(null) }
    val selection = state.selectionState.selection

    Box(
        modifier = modifier
            .fillMaxSize()
            .preferredFrameRateOrNone(state.config)
            .onSizeChanged(onViewportSizeChanged)
            .onGloballyPositioned { onCanvasPositionChanged(it.positionInWindow()) }
            .terminalImeHost(state.imeHost)
            .focusRequester(state.focusRequester)
            .focusTarget()
            .terminalKeyHandling(state.translator, state.selectionState, state.config)
            .terminalSelectionMagnifier(
                visible = magnifierEndpoint != null && !selection.isEmpty &&
                    state.controller.currentFrame() != null
            ) {
                val endpoint = magnifierEndpoint
                val frame = state.controller.currentFrame()
                if (endpoint == null || frame == null) {
                    Offset.Unspecified
                } else {
                    selectionMagnifierSourceForSelection(
                        endpoint = endpoint,
                        selection = selection,
                        topRow = frame.topRow,
                        metrics = state.metrics
                    )
                }
            }
    ) {
        val inputModifier = Modifier
            .fillMaxSize()
            .terminalSemantics(state.visibleText, state.config.accessibilityEnabled)
            .terminalGestures(
                state.controller,
                state.metrics,
                state.config,
                state.selectionState,
                state.fontSizeState,
                onFontSizeChange = state.config.onFontSizeChange
            )
            .terminalTaps(
                state.controller,
                state.metrics,
                state.config,
                state.selectionState,
                state.imeHost,
                state.focusRequester,
                state.hapticFeedback
            )

        if (state.config.renderer == TerminalRenderer.OPENGL_ES) {
            GlesTerminalCanvasContent(
                controller = state.controller,
                metrics = state.metrics,
                selection = state.selectionState.selection,
                contentVersion = state.contentVersionState.intValue,
                fontSizePx = state.fontSizeState.intValue.toFloat(),
                config = state.config,
                modifier = Modifier
                    .fillMaxSize()
                    .zIndex(PixelLayerZIndex)
            )
            Canvas(modifier = inputModifier.zIndex(InputLayerZIndex)) {}
        } else {
            Canvas(modifier = inputModifier.zIndex(InputLayerZIndex)) {
                state.controller.draw(
                    drawScope = this,
                    selection = state.selectionState.selection,
                    contentVersion = state.contentVersionState.intValue,
                    timeSeconds = if (state.controller.isContinuouslyAnimated) {
                        state.frameTimeState.floatValue
                    } else {
                        0f
                    }
                )
            }
        }
        if (state.config.cursorEffect != null) {
            Canvas(
                modifier = Modifier
                    .fillMaxSize()
                    .zIndex(CursorEffectZIndex)
            ) {
                state.controller.drawCursorEffect(
                    drawScope = this,
                    metrics = state.metrics,
                    timeSeconds = state.frameTimeState.floatValue
                )
            }
        }
        Box(
            modifier = Modifier
                .fillMaxSize()
                .zIndex(SelectionLayerZIndex)
        ) {
            TerminalSelectionUi(
                state = state,
                onMagnifierEndpointChanged = { magnifierEndpoint = it }
            )
        }
    }
}

@Composable
private fun TerminalSelectionUi(
    state: TerminalCanvasState,
    onMagnifierEndpointChanged: (SelectionHandleEndpoint?) -> Unit
) {
    val hostView = LocalView.current
    val selection = state.selectionState.selection
    val selectionActionMode = remember(hostView) { TerminalSelectionActionMode(hostView) }
    val selectionHandleColor = rememberSelectionHandleColor(state.config.selectionHandleColor)
    DisposableEffect(selectionActionMode) {
        onDispose { selectionActionMode.dispose() }
    }
    TerminalSelectionActionUpdater(
        contentVersionState = state.contentVersionState,
        selection = selection,
        controller = state.controller,
        metrics = state.metrics,
        canvasPositionInWindow = state.canvasPositionInWindow,
        config = state.config,
        selectionActionMode = selectionActionMode,
        clearSelection = state.selectionState::clear
    )
    TerminalSelectionOverlay(
        selection = selection,
        controller = state.controller,
        contentVersionState = state.contentVersionState,
        metrics = state.metrics,
        configuredColor = selectionHandleColor,
        onHandleDragStart = { endpoint ->
            onMagnifierEndpointChanged(endpoint)
            selectionActionMode.hideForHandleDrag()
        },
        onHandleDragEnd = {
            onMagnifierEndpointChanged(null)
            selectionActionMode.showAfterHandleDrag()
        },
        onHandleDrag = { endpoint, x, y ->
            updateSelectionHandle(
                endpoint = endpoint,
                x = x,
                y = y,
                controller = state.controller,
                metrics = state.metrics,
                selectionState = state.selectionState
            )
        }
    )
}

@Composable
private fun rememberConfiguredController(
    backend: TerminalBackend,
    graphicsContext: GraphicsContext,
    frameTimeState: MutableFloatState,
    fontSizeState: MutableIntState,
    config: TerminalCanvasConfig,
    onInvalidated: () -> Unit
): TerminalController {
    val controller = rememberTerminalController(
        backend = backend,
        graphicsContext = graphicsContext,
        onInvalidated = onInvalidated
    )
    SideEffect {
        controller.configure(config, fontSizeState.intValue)
    }
    LaunchedEffect(controller, config.shaders, config.cursorEffect, fontSizeState.intValue) {
        controller.configure(config, fontSizeState.intValue)
        runFrameLoop(controller, frameTimeState)
    }
    return controller
}

@Composable
private fun rememberTerminalController(
    backend: TerminalBackend,
    graphicsContext: GraphicsContext,
    onInvalidated: () -> Unit
): TerminalController {
    val controller = remember(backend, graphicsContext) { TerminalController(backend, graphicsContext) }
    // Wire the invalidation callback BEFORE attach: attach() replays the
    // latest published frame synchronously, and onFrameInvalidated() during
    // that replay must reach the canvas or the initial paint stays stale.
    DisposableEffect(controller) {
        controller.onInvalidated = onInvalidated
        onDispose { controller.onInvalidated = null }
    }
    DisposableEffect(controller) {
        controller.attach()
        onDispose { controller.release() }
    }
    return controller
}

/** Creates the input pipeline (translator, IME host) and wires its lifecycle. */
@Composable
private fun rememberInputPipeline(
    controller: TerminalController,
    modifierKeys: ModifierKeyReader,
    onCodePoint: ((Int, Boolean, Boolean) -> Boolean)?,
    onImeSessionClosed: () -> Unit
): Pair<TerminalInputTranslator, ImeHost> {
    val translator = remember(controller, modifierKeys, onCodePoint) {
        TerminalInputTranslator(
            modifierKeys = modifierKeys,
            onCodePoint = onCodePoint
        ) { command -> controller.submit(command) }
    }
    val imeProcessor = remember(translator) {
        ImeEditCommandProcessor(CommandTerminalInput(translator))
    }
    val imeHost = rememberImeHost(
        onEditCommands = imeProcessor::process,
        onSessionStarted = imeProcessor::reset,
        onSessionClosed = {
            imeProcessor.reset()
            onImeSessionClosed()
        }
    )
    DisposableEffect(imeHost) {
        onDispose { imeHost.close() }
    }
    return translator to imeHost
}

/** Font size state clamped to the config range. */
@Composable
private fun rememberFontSizeState(config: TerminalCanvasConfig): MutableIntState {
    val fontSizeState = remember { mutableIntStateOf(config.fontSize) }
    LaunchedEffect(config.minimumFontSize, config.maximumFontSize) {
        val fontSize = fontSizeState.intValue
        if (fontSize !in config.minimumFontSize..config.maximumFontSize) {
            fontSizeState.intValue = fontSize.coerceIn(config.minimumFontSize, config.maximumFontSize)
        }
    }
    return fontSizeState
}

/** Cell metrics for the current font size and viewport. */
@Composable
private fun rememberCanvasMetrics(
    fontSizeState: MutableIntState,
    config: TerminalCanvasConfig,
    viewportSizePx: IntSize
): TerminalMetrics = remember(fontSizeState.intValue, config.typeface, viewportSizePx) {
    TerminalMetrics.from(
        fontSizePx = fontSizeState.intValue.toFloat(),
        typeface = config.typeface,
        viewportWidthPx = viewportSizePx.width,
        viewportHeightPx = viewportSizePx.height
    )
}

/** Frame loop: continuous shaders own one display-rate loop; transient effects pulse. */
private suspend fun runFrameLoop(
    controller: TerminalController,
    frameTimeState: MutableFloatState
) {
    controller.resetCursorTracking()
    if (controller.isContinuouslyAnimated) {
        while (true) {
            withFrameNanos { nanos ->
                frameTimeState.floatValue = nanos / 1_000_000_000f
            }
        }
    } else {
        while (true) {
            controller.awaitInvalidation()
            do {
                withFrameNanos { nanos ->
                    frameTimeState.floatValue = nanos / 1_000_000_000f
                }
            } while (controller.needsFrame(frameTimeState.floatValue))
        }
    }
}

/** Reports selection geometry changes to the consumer. */
@Composable
private fun reportSelectionChanges(
    config: TerminalCanvasConfig,
    selectionState: TerminalSelectionState,
    controller: TerminalController,
    metrics: TerminalMetrics
) {
    LaunchedEffect(selectionState.selection, metrics) {
        val selection = selectionState.selection
        if (selection.isEmpty) {
            config.onSelectionChanged(null)
        } else {
            val frame = controller.currentFrame()
            if (frame != null) {
                config.onSelectionChanged(
                    TerminalSelectionInfo(
                        selection = selection,
                        topRow = frame.topRow,
                        columns = frame.columns,
                        transcriptRows = frame.viewport.transcriptRows,
                        alternateBufferActive = frame.alternateBufferActive,
                        cellWidthPx = metrics.cellWidthPx,
                        cellHeightPx = metrics.cellHeightPx
                    )
                )
            }
        }
    }
}

/** Visible text for accessibility, off the render hot path. */
@Composable
private fun rememberVisibleText(
    controller: TerminalController,
    contentVersionState: MutableIntState,
    enabled: Boolean
): String =
    if (enabled) {
        remember(controller, contentVersionState.intValue) {
            controller.currentFrame()?.visibleText() ?: ""
        }
    } else {
        ""
    }

private fun Modifier.preferredFrameRateOrNone(config: TerminalCanvasConfig): Modifier =
    if (config.preferredFrameRate != null) {
        this.then(Modifier.preferredFrameRate(config.preferredFrameRate))
    } else {
        this
    }

private fun Modifier.terminalKeyHandling(
    translator: TerminalInputTranslator,
    selectionState: TerminalSelectionState,
    config: TerminalCanvasConfig
): Modifier = onPreviewKeyEvent { keyEvent ->
    if (keyEvent.type == KeyEventType.KeyUp) {
        return@onPreviewKeyEvent config.onKeyUp(keyEvent.nativeKeyEvent)
    }
    if (keyEvent.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
    if (config.onKeyDown(keyEvent.nativeKeyEvent)) return@onPreviewKeyEvent true
    if (keyEvent.nativeKeyEvent.keyCode == android.view.KeyEvent.KEYCODE_BACK) {
        if (!selectionState.isSelecting) return@onPreviewKeyEvent false
        selectionState.clear()
        return@onPreviewKeyEvent true
    }
    if (selectionState.isSelecting) selectionState.clear()
    translator.handleKeyEvent(keyEvent.nativeKeyEvent)
}

private fun Modifier.terminalSemantics(visibleText: String, enabled: Boolean): Modifier =
    semantics {
        if (enabled) {
            text = AnnotatedString(visibleText)
        }
    }
