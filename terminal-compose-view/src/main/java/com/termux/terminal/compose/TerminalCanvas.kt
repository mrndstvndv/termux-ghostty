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
import androidx.compose.ui.graphics.GraphicsContext
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalGraphicsContext
import androidx.compose.ui.platform.LocalHapticFeedback
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
import com.termux.terminal.compose.internal.TerminalSelectionState
import com.termux.terminal.compose.internal.rememberImeHost
import com.termux.terminal.compose.internal.terminalGestures
import com.termux.terminal.compose.internal.terminalTaps

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
    val selectionState = remember { TerminalSelectionState() }
    val hapticFeedback = LocalHapticFeedback.current
    val focusRequester = remember { FocusRequester() }

    var contentVersion by remember { mutableIntStateOf(0) }
    val frameTimeState = remember { mutableFloatStateOf(0f) }
    val fontSizeState = rememberFontSizeState(config)
    var viewportSizePx by remember { mutableStateOf(IntSize.Zero) }

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

    Box(
        modifier = modifier
            .fillMaxSize()
            .preferredFrameRateOrNone(config)
            .onSizeChanged { viewportSizePx = it }
            .focusRequester(focusRequester)
            .focusTarget()
            .terminalKeyHandling(translator)
    ) {
        Canvas(
            modifier = Modifier
                .fillMaxSize()
                .terminalSemantics(visibleText, config.accessibilityEnabled)
                .terminalGestures(
                    controller, metrics, config, selectionState, fontSizeState,
                    onFontSizeChange = config.onFontSizeChange
                )
                .terminalTaps(
                    controller, metrics, config, selectionState, imeHost, focusRequester,
                    hapticFeedback
                )
        ) {
            controller.draw(
                drawScope = this,
                metrics = metrics,
                selection = selectionState.selection,
                contentVersion = contentVersion,
                timeSeconds = frameTimeState.floatValue
            )
        }
    }
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
            controller.tick(frameTimeState.floatValue)
        }
    } else {
        while (true) {
            controller.awaitInvalidation()
            do {
                withFrameNanos { nanos ->
                    frameTimeState.floatValue = nanos / 1_000_000_000f
                }
                controller.tick(frameTimeState.floatValue)
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

private fun Modifier.terminalKeyHandling(translator: TerminalInputTranslator): Modifier =
    onPreviewKeyEvent { keyEvent ->
        if (keyEvent.type == KeyEventType.KeyDown) {
            translator.handleKeyEvent(keyEvent.nativeKeyEvent)
        } else {
            false
        }
    }

private fun Modifier.terminalSemantics(visibleText: String, enabled: Boolean): Modifier =
    semantics {
        if (enabled) {
            text = AnnotatedString(visibleText)
        }
    }
