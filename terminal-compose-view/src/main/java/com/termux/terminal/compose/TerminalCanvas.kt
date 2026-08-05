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
import com.termux.terminal.compose.internal.CommandTerminalInput
import com.termux.terminal.compose.internal.ImeEditCommandProcessor
import com.termux.terminal.compose.internal.ImeHost
import com.termux.terminal.compose.internal.TerminalController
import com.termux.terminal.compose.internal.TerminalInputTranslator
import com.termux.terminal.compose.internal.TerminalSelectionActionMode
import com.termux.terminal.compose.internal.TerminalSelectionOverlay
import com.termux.terminal.compose.internal.TerminalSelectionState
import com.termux.terminal.compose.internal.rememberImeHost
import com.termux.terminal.compose.internal.rememberSelectionHandleColor
import com.termux.terminal.compose.internal.terminalGestures
import com.termux.terminal.compose.internal.terminalImeHost
import com.termux.terminal.compose.internal.terminalTaps
import com.termux.terminal.compose.internal.updateSelectionHandle

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
    modifier: Modifier = Modifier
) {
    val graphicsContext = LocalGraphicsContext.current
    val selectionState = remember(backend) { TerminalSelectionState() }
    val hapticFeedback = LocalHapticFeedback.current
    val focusRequester = remember { FocusRequester() }

    var contentVersion by remember { mutableIntStateOf(0) }
    val frameTimeState = remember { mutableFloatStateOf(0f) }
    val fontSizeState = rememberFontSizeState(config)
    var viewportSizePx by remember { mutableStateOf(IntSize.Zero) }
    var canvasPositionInWindow by remember { mutableStateOf(Offset.Zero) }

    val controller = rememberConfiguredController(
        backend, graphicsContext, frameTimeState, fontSizeState, config,
        onInvalidated = { contentVersion++ }
    )
    val (translator, imeHost) = rememberInputPipeline(controller, modifierKeys)
    val metrics = rememberCanvasMetrics(fontSizeState, config, viewportSizePx)
    LaunchedEffect(requestFocus) {
        if (requestFocus) focusRequester.requestFocus()
    }
    LaunchedEffect(config.selectionResetKey) {
        selectionState.clear()
    }

    reportSelectionChanges(config, selectionState, controller, metrics)
    val visibleText = rememberVisibleText(controller, contentVersion, config.accessibilityEnabled)

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
            contentVersion = contentVersion,
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
    val contentVersion: Int,
    val frameTimeState: MutableFloatState,
    val canvasPositionInWindow: Offset
)

@Composable
private fun TerminalCanvasLayout(
    modifier: Modifier,
    state: TerminalCanvasState,
    onViewportSizeChanged: (IntSize) -> Unit,
    onCanvasPositionChanged: (Offset) -> Unit
) {
    Box(
        modifier = modifier
            .fillMaxSize()
            .preferredFrameRateOrNone(state.config)
            .onSizeChanged(onViewportSizeChanged)
            .onGloballyPositioned { onCanvasPositionChanged(it.positionInWindow()) }
            .terminalImeHost(state.imeHost)
            .focusRequester(state.focusRequester)
            .focusTarget()
            .terminalKeyHandling(state.translator, state.selectionState)
    ) {
        Canvas(
            modifier = Modifier
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
        ) {
            state.controller.draw(
                drawScope = this,
                selection = state.selectionState.selection,
                contentVersion = state.contentVersion,
                timeSeconds = if (state.controller.isContinuouslyAnimated) {
                    state.frameTimeState.floatValue
                } else {
                    0f
                }
            )
        }
        if (state.config.cursorEffect != null) {
            Canvas(modifier = Modifier.fillMaxSize()) {
                state.controller.drawCursorEffect(
                    drawScope = this,
                    metrics = state.metrics,
                    timeSeconds = state.frameTimeState.floatValue
                )
            }
        }
        TerminalSelectionUi(state)
    }
}

@Composable
private fun TerminalSelectionUi(state: TerminalCanvasState) {
    val hostView = LocalView.current
    val selection = state.selectionState.selection
    val currentFrame = remember(state.controller, state.contentVersion, selection) {
        state.controller.currentFrame()
    }
    val selectionActionMode = remember(hostView) { TerminalSelectionActionMode(hostView) }
    val selectionHandleColor = rememberSelectionHandleColor(state.config.selectionHandleColor)
    DisposableEffect(selectionActionMode) {
        onDispose { selectionActionMode.dispose() }
    }
    LaunchedEffect(
        selection,
        state.contentVersion,
        currentFrame,
        state.metrics,
        state.canvasPositionInWindow,
        state.config.onCopyRequest,
        state.config.onPasteRequest,
        state.config.onMoreSelectionRequest
    ) {
        selectionActionMode.update(
            selection = selection,
            frame = currentFrame,
            selectedTextProvider = { state.controller.selectedText(selection) },
            metrics = state.metrics,
            canvasPositionInWindow = state.canvasPositionInWindow,
            config = state.config,
            clearSelection = state.selectionState::clear
        )
    }
    TerminalSelectionOverlay(
        selection = selection,
        frame = currentFrame,
        metrics = state.metrics,
        configuredColor = selectionHandleColor,
        onHandleDragStart = { selectionActionMode.hideForHandleDrag() },
        onHandleDragEnd = { selectionActionMode.showAfterHandleDrag() },
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
        controller.configure(config.copy(fontSize = fontSizeState.intValue))
    }
    LaunchedEffect(controller, config.shaders, config.cursorEffect, fontSizeState.intValue) {
        controller.configure(config.copy(fontSize = fontSizeState.intValue))
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
    DisposableEffect(controller) {
        controller.attach()
        onDispose { controller.release() }
    }
    DisposableEffect(controller) {
        controller.onInvalidated = onInvalidated
        onDispose { controller.onInvalidated = null }
    }
    return controller
}

/** Creates the input pipeline (translator, IME host) and wires its lifecycle. */
@Composable
private fun rememberInputPipeline(
    controller: TerminalController,
    modifierKeys: ModifierKeyReader
): Pair<TerminalInputTranslator, ImeHost> {
    val translator = remember(controller, modifierKeys) {
        TerminalInputTranslator(modifierKeys) { command -> controller.submit(command) }
    }
    val imeProcessor = remember(translator) {
        ImeEditCommandProcessor(CommandTerminalInput(translator))
    }
    val imeHost = rememberImeHost(imeProcessor::process)
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
    contentVersion: Int,
    enabled: Boolean
): String =
    if (enabled) {
        remember(controller, contentVersion) {
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
    selectionState: TerminalSelectionState
): Modifier = onPreviewKeyEvent { keyEvent ->
    if (keyEvent.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
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
