@file:Suppress("TooManyFunctions")

package com.termux.terminal.compose

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableIntState
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusTarget
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.preferredFrameRate
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.text
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.zIndex
import com.termux.terminal.compose.gpu.GlesTerminalSurface
import com.termux.terminal.compose.internal.CommandTerminalInput
import com.termux.terminal.compose.internal.ImeEditCommandProcessor
import com.termux.terminal.compose.internal.ImeHost
import com.termux.terminal.compose.internal.TerminalController
import com.termux.terminal.compose.internal.TerminalInputTranslator
import com.termux.terminal.compose.internal.SelectionHandleEndpoint
import com.termux.terminal.compose.internal.TerminalScrollbar
import com.termux.terminal.compose.internal.TerminalSelectionActionMode
import com.termux.terminal.compose.internal.TerminalSelectionOverlay
import com.termux.terminal.compose.internal.TerminalSelectionState
import com.termux.terminal.compose.internal.rememberImeHost
import com.termux.terminal.compose.internal.rememberSelectionHandleColor
import com.termux.terminal.compose.internal.terminalGestures
import com.termux.terminal.compose.internal.selectionMagnifierSourceForSelection
import com.termux.terminal.compose.internal.terminalImeHost
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
private const val ScrollbarLayerZIndex = 2f
private const val SelectionLayerZIndex = 3f

/**
 * Compose terminal canvas.
 *
 * Owns rendering, input/IME translation, selection gestures, links, scrolling,
 * focus, and accessibility seams. The backend stays neutral: all session
 * interaction goes through [TerminalBackend] commands and immutable
 * [TerminalFrame] snapshots. UI resources are released when the canvas leaves
 * composition; the host retains backend ownership.
 */
@Composable
@Suppress("LongParameterList", "LongMethod")
fun TerminalCanvas(
    backend: TerminalBackend,
    modifierKeys: ModifierKeyReader,
    config: TerminalCanvasConfig,
    requestFocus: Boolean = false,
    requestFocusKey: Long = 0L,
    requestImeKey: Long = 0L,
    requestDismissImeKey: Long = 0L,
    modifier: Modifier = Modifier
) {
    val selectionState = remember(backend) { TerminalSelectionState() }
    val hapticFeedback = LocalHapticFeedback.current
    val focusRequester = remember { FocusRequester() }

    val contentVersionState = remember { mutableIntStateOf(0) }
    val fontSizeState = rememberFontSizeState(config)
    var viewportSizePx by remember { mutableStateOf(IntSize.Zero) }
    var canvasPositionInWindow by remember { mutableStateOf(Offset.Zero) }

    val controller = rememberConfiguredController(
        backend = backend,
        fontSizeState = fontSizeState,
        config = config,
        onInvalidated = {
            if (config.accessibilityEnabled || !selectionState.selection.isEmpty) {
                contentVersionState.intValue++
            }
        }
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
    var lastHandledImeKey by remember { mutableLongStateOf(0L) }
    var lastHandledDismissImeKey by remember { mutableLongStateOf(0L) }
    LaunchedEffect(requestFocus, requestFocusKey, requestImeKey) {
        if (requestFocus || requestImeKey != 0L) focusRequester.requestFocus()
        if (requestImeKey != 0L && requestImeKey != lastHandledImeKey) {
            lastHandledImeKey = requestImeKey
            imeHost.open()
        }
    }
    LaunchedEffect(requestDismissImeKey) {
        if (requestDismissImeKey != 0L && requestDismissImeKey != lastHandledDismissImeKey) {
            lastHandledDismissImeKey = requestDismissImeKey
            imeHost.close()
        }
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
                onFontSizeChange = state.config.onFontSizeChange,
                onTwoFingerSwipeUp = {
                    state.focusRequester.requestFocus()
                    state.imeHost.open()
                }
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

        val glesSurface = glesTerminalCanvasContent(
            controller = state.controller,
            metrics = state.metrics,
            selection = state.selectionState.selection,
            fontSizePx = state.fontSizeState.intValue.toFloat(),
            config = state.config,
            modifier = Modifier
                .fillMaxSize()
                .zIndex(PixelLayerZIndex)
        )
        Canvas(modifier = inputModifier.zIndex(InputLayerZIndex)) {}
        TerminalScrollbar(
            controller = state.controller,
            metrics = state.metrics,
            config = state.config.scrollbar,
            modifier = Modifier
                .align(Alignment.CenterEnd)
                .zIndex(ScrollbarLayerZIndex)
        )
        Box(
            modifier = Modifier
                .fillMaxSize()
                .zIndex(SelectionLayerZIndex)
        ) {
            TerminalSelectionUi(
                state = state,
                magnifierEndpoint = magnifierEndpoint,
                gpuSurface = glesSurface,
                onMagnifierEndpointChanged = { magnifierEndpoint = it }
            )
        }
    }
}

@Composable
private fun TerminalSelectionUi(
    state: TerminalCanvasState,
    magnifierEndpoint: SelectionHandleEndpoint?,
    gpuSurface: GlesTerminalSurface?,
    onMagnifierEndpointChanged: (SelectionHandleEndpoint?) -> Unit
) {
    val hostView = LocalView.current
    val selection = state.selectionState.selection
    GlesSelectionMagnifierEffect(
        endpoint = magnifierEndpoint,
        gpuSurface = gpuSurface,
        selection = selection,
        controller = state.controller,
        contentVersion = state.contentVersionState.intValue,
        metrics = state.metrics
    )
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
            gpuSurface?.dismissSelectionMagnifier()
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
private fun GlesSelectionMagnifierEffect(
    endpoint: SelectionHandleEndpoint?,
    gpuSurface: GlesTerminalSurface?,
    selection: TerminalSelection,
    controller: TerminalController,
    contentVersion: Int,
    metrics: TerminalMetrics
) {
    LaunchedEffect(gpuSurface, endpoint, selection, contentVersion, metrics) {
        if (gpuSurface == null || endpoint == null || selection.isEmpty) {
            gpuSurface?.dismissSelectionMagnifier()
            return@LaunchedEffect
        }
        val frame = controller.currentFrame()
        if (frame == null) {
            gpuSurface.dismissSelectionMagnifier()
            return@LaunchedEffect
        }
        val source = selectionMagnifierSourceForSelection(
            endpoint = endpoint,
            selection = selection,
            topRow = frame.topRow,
            metrics = metrics
        )
        gpuSurface.showSelectionMagnifier(source.x, source.y)
    }
}

@Composable
private fun rememberConfiguredController(
    backend: TerminalBackend,
    fontSizeState: MutableIntState,
    config: TerminalCanvasConfig,
    onInvalidated: () -> Unit
): TerminalController {
    val controller = rememberTerminalController(
        backend = backend,
        onInvalidated = onInvalidated
    )
    SideEffect {
        controller.configure(config, fontSizeState.intValue)
    }
    return controller
}

@Composable
private fun rememberTerminalController(
    backend: TerminalBackend,
    onInvalidated: () -> Unit
): TerminalController {
    val controller = remember(backend) { TerminalController(backend) }
    // Wire the callback before attach and keep it current as overlay policy changes.
    val currentOnInvalidated by rememberUpdatedState(onInvalidated)
    DisposableEffect(controller) {
        val callback = { currentOnInvalidated() }
        controller.onInvalidated = callback
        onDispose {
            if (controller.onInvalidated === callback) controller.onInvalidated = null
        }
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
    val currentOnCodePoint = rememberUpdatedState(onCodePoint)
    val translator = remember(controller, modifierKeys) {
        TerminalInputTranslator(
            modifierKeys = modifierKeys,
            onCodePoint = null
        ) { command -> controller.submit(command) }
    }
    SideEffect { translator.updateOnCodePoint(currentOnCodePoint.value) }
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
    LaunchedEffect(config.fontSize, config.minimumFontSize, config.maximumFontSize) {
        val fontSize = config.fontSize.coerceIn(config.minimumFontSize, config.maximumFontSize)
        if (fontSizeState.intValue != fontSize) {
            fontSizeState.intValue = fontSize
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
