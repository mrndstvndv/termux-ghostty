@file:Suppress("TooManyFunctions")

package com.termux.terminal.compose.internal

import android.view.InputDevice
import android.view.MotionEvent
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableIntState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.hapticfeedback.HapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.AwaitPointerEventScope
import androidx.compose.ui.input.pointer.PointerEvent
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.pointerInteropFilter
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.input.pointer.util.VelocityTracker
import kotlinx.coroutines.CoroutineScope
import com.termux.terminal.compose.TerminalCanvasConfig
import com.termux.terminal.compose.TerminalCommand
import com.termux.terminal.compose.TerminalFrame
import com.termux.terminal.compose.TerminalMetrics
import com.termux.terminal.compose.TerminalPointerEvent
import com.termux.terminal.compose.TerminalPointerGeometry
import kotlin.math.abs
import kotlin.math.sqrt

/** Everything a gesture handler needs besides the per-gesture scroll state. */
private class GestureContext(
    val controller: TerminalController,
    private val metricsProvider: () -> TerminalMetrics,
    private val configProvider: () -> TerminalCanvasConfig,
    val fontSizeState: MutableIntState,
    private val onFontSizeChangeProvider: () -> (Int) -> Unit
) {
    val metrics: TerminalMetrics get() = metricsProvider()
    val config: TerminalCanvasConfig get() = configProvider()
    val onFontSizeChange: (Int) -> Unit get() = onFontSizeChangeProvider()
}

/** Per-gesture scroll, pan, and zoom state. */
private class ScrollGestureState {
    var totalPanX = 0f
    var totalPanY = 0f
    var isVerticalScroll = false
    var isHorizontalSwipe = false
    var isPinchZoom = false

    fun trackPan(event: PointerEvent, touchSlop: Float) {
        totalPanX += event.calculatePan().x
        totalPanY += event.calculatePan().y
        val dist = sqrt(totalPanX * totalPanX + totalPanY * totalPanY)
        if (!isVerticalScroll && dist > touchSlop) {
            if (abs(totalPanX) > 1.5f * abs(totalPanY)) {
                isHorizontalSwipe = true
            } else {
                isVerticalScroll = true
            }
        }
    }
}

internal data class ScrollPixelDelta(
    val deltaRows: Int,
    val remainderPx: Float
)

@Suppress("ReturnCount")
internal fun scrollPixelDelta(
    accumulatedPx: Float,
    deltaPx: Float,
    cellHeightPx: Float
): ScrollPixelDelta {
    if (!accumulatedPx.isFinite()) return ScrollPixelDelta(0, accumulatedPx)
    if (!deltaPx.isFinite()) return ScrollPixelDelta(0, accumulatedPx)
    if (!cellHeightPx.isFinite() || cellHeightPx <= 0f) {
        return ScrollPixelDelta(0, accumulatedPx)
    }
    val totalPx = accumulatedPx + deltaPx
    val deltaRows = (totalPx / cellHeightPx).toInt()
    return ScrollPixelDelta(
        deltaRows = deltaRows,
        remainderPx = totalPx - deltaRows * cellHeightPx
    )
}

/** Captures tap source metadata without retaining framework MotionEvent instances. */
private class TerminalTapMotionEventState {
    private var hasUpEvent = false
    private var source = InputDevice.SOURCE_TOUCHSCREEN
    private var x = 0f
    private var y = 0f

    fun record(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_UP -> {
                hasUpEvent = true
                source = event.source
                x = event.x
                y = event.y
            }
            MotionEvent.ACTION_CANCEL -> hasUpEvent = false
        }
        return false
    }

    fun take(offset: Offset): MotionEvent {
        val event = MotionEvent.obtain(
            0L,
            0L,
            MotionEvent.ACTION_UP,
            if (hasUpEvent) x else offset.x,
            if (hasUpEvent) y else offset.y,
            0
        )
        event.source = if (hasUpEvent) source else InputDevice.SOURCE_TOUCHSCREEN
        hasUpEvent = false
        return event
    }
}

private data class TapContext(
    val controller: TerminalController,
    val metrics: TerminalMetrics,
    val config: TerminalCanvasConfig,
    val selectionState: TerminalSelectionState,
    val imeHost: ImeHost,
    val focusRequester: FocusRequester
)

/** Attaches the scroll/zoom/drag gesture handling to the terminal canvas. */
@Composable
internal fun Modifier.terminalGestures(
    controller: TerminalController,
    metrics: TerminalMetrics,
    config: TerminalCanvasConfig,
    selectionState: TerminalSelectionState,
    fontSizeState: MutableIntState,
    onFontSizeChange: (Int) -> Unit
): Modifier {
    val currentMetrics by rememberUpdatedState(metrics)
    val currentConfig by rememberUpdatedState(config)
    val currentOnFontSizeChange by rememberUpdatedState(onFontSizeChange)
    val coroutineScope = rememberCoroutineScope()
    return pointerInput(controller, selectionState) {
        val context = GestureContext(
            controller = controller,
            metricsProvider = { currentMetrics },
            configProvider = { currentConfig },
            fontSizeState = fontSizeState,
            onFontSizeChangeProvider = { currentOnFontSizeChange }
        )
        awaitEachGesture {
            handleTerminalGesture(selectionState, context, coroutineScope)
        }
    }
}

private suspend fun AwaitPointerEventScope.handleTerminalGesture(
    selectionState: TerminalSelectionState,
    context: GestureContext,
    coroutineScope: CoroutineScope
) {
    var scaleAccumulator = 1f
    val touchSlop = viewConfiguration.touchSlop
    val scrollState = ScrollGestureState()
    val velocityTracker = VelocityTracker()

    // Claim vertical terminal input before a parent pager gets its main-pass
    // opportunity to consume the same pointer stream. Tap handling remains in
    // the main pass and is unaffected because this recognizer only consumes
    // after it has established a vertical or pinch gesture.
    val down = awaitFirstDown(
        requireUnconsumed = false,
        pass = PointerEventPass.Initial
    )
    velocityTracker.resetTracking()
    velocityTracker.addPosition(down.uptimeMillis, down.position)
    context.controller.beginScrollGesture()
    if (selectionState.isSelecting) return

    do {
        val event = awaitPointerEvent(PointerEventPass.Initial)
        val canceled = event.changes.any { it.isConsumed }
        if (!canceled) {
            event.changes.firstOrNull()?.let { change ->
                velocityTracker.addPosition(change.uptimeMillis, change.position)
            }
            scaleAccumulator = handleGestureEvent(event, scaleAccumulator, scrollState, touchSlop, context)
        }
    } while (!canceled && event.changes.any { it.pressed })

    if (scrollState.isVerticalScroll && !scrollState.isPinchZoom && !selectionState.isSelecting) {
        val velocityY = velocityTracker.calculateVelocity().y
        if (abs(velocityY) >= 100f) {
            context.controller.startFling(
                coroutineScope = coroutineScope,
                initialVelocityPxPerSec = velocityY,
                cellHeightPx = context.metrics.cellHeightPx
            )
        } else {
            context.controller.settleVisualScrollOffset()
        }
    } else {
        context.controller.settleVisualScrollOffset()
    }
}

private fun handleGestureEvent(
    event: PointerEvent,
    scaleAccumulator: Float,
    scrollState: ScrollGestureState,
    touchSlop: Float,
    context: GestureContext
): Float {
    val zoomChange = event.calculateZoom()
    if (event.changes.size > 1 || zoomChange != 1f) {
        scrollState.isPinchZoom = true
    }
    if (scrollState.isPinchZoom) {
        return handlePinchZoom(event, scaleAccumulator, context)
    }
    if (scrollState.isHorizontalSwipe) return scaleAccumulator
    scrollState.trackPan(event, touchSlop)
    if (scrollState.isVerticalScroll && event.calculatePan().y != 0f) {
        handleScrollGesture(event, context)
    }
    return scaleAccumulator
}

/** Applies pinch-zoom font stepping; returns the next scale accumulator. */
internal fun applyPinchZoomStep(
    zoomChange: Float,
    scaleAccumulator: Float,
    currentFontSize: Int,
    minFontSize: Int,
    maxFontSize: Int,
    onFontSizeChange: (Int) -> Unit
): Float {
    if (zoomChange == 1f) return scaleAccumulator
    val accumulated = scaleAccumulator * zoomChange
    if (accumulated >= 0.9f && accumulated <= 1.1f) return accumulated

    val increase = accumulated > 1f
    val newSize = currentFontSize + (if (increase) 1 else -1) * 2
    val clampedSize = newSize.coerceIn(minFontSize, maxFontSize)
    if (clampedSize != currentFontSize) {
        onFontSizeChange(clampedSize)
    }
    return 1f
}

private fun handlePinchZoom(
    event: PointerEvent,
    scaleAccumulator: Float,
    context: GestureContext
): Float {
    event.changes.forEach { if (it.positionChanged()) it.consume() }
    val zoomChange = event.calculateZoom()
    return applyPinchZoomStep(
        zoomChange = zoomChange,
        scaleAccumulator = scaleAccumulator,
        currentFontSize = context.fontSizeState.intValue,
        minFontSize = context.config.minimumFontSize,
        maxFontSize = context.config.maximumFontSize,
        onFontSizeChange = { newSize ->
            context.fontSizeState.intValue = newSize
            context.onFontSizeChange(newSize)
        }
    )
}

/** Applies vertical scroll through backend-routed incremental deltas. */
private fun handleScrollGesture(
    event: PointerEvent,
    context: GestureContext
) {
    event.changes.forEach { if (it.positionChanged()) it.consume() }
    // Keep routing in the backend; a rendered frame can lag live multiplexer modes.
    val centroid = event.calculateCentroid()
    val deltaRows = context.controller.applyScrollDelta(
        deltaPx = event.calculatePan().y,
        cellHeightPx = context.metrics.cellHeightPx
    )
    if (deltaRows != 0) {
        context.controller.submit(scrollCommandForGesture(deltaRows, centroid, context.metrics))
    }
}

/** Builds the backend command for one drag threshold crossing. */
internal fun scrollCommandForGesture(
    deltaRows: Int,
    touchPosition: Offset,
    metrics: TerminalMetrics
): TerminalCommand.Scroll =
    TerminalCommand.Scroll(
        rowsDown = -deltaRows,
        xPx = touchPosition.x,
        yPx = touchPosition.y,
        geometry = TerminalPointerGeometry(
            cellWidthPx = metrics.cellWidthPx,
            cellHeightPx = metrics.cellHeightPx,
            contentTopPx = metrics.lineSpacingAndAscentPx,
            viewportWidthPx = metrics.viewportWidthPx,
            viewportHeightPx = metrics.viewportHeightPx
        )
    )

/** Attaches tap and long-press handling to the terminal canvas. */
@Composable
internal fun Modifier.terminalTaps(
    controller: TerminalController,
    metrics: TerminalMetrics,
    config: TerminalCanvasConfig,
    selectionState: TerminalSelectionState,
    imeHost: ImeHost,
    focusRequester: FocusRequester,
    hapticFeedback: HapticFeedback
): Modifier {
    val currentMetrics by rememberUpdatedState(metrics)
    val currentConfig by rememberUpdatedState(config)
    val motionEventState = remember { TerminalTapMotionEventState() }
    return pointerInteropFilter(onTouchEvent = motionEventState::record)
        .pointerInput(controller, selectionState, imeHost, focusRequester) {
            detectTapGestures(
                onTap = { offset ->
                    handleTap(
                        offset,
                        motionEventState.take(offset),
                        TapContext(
                            controller = controller,
                            metrics = currentMetrics,
                            config = currentConfig,
                            selectionState = selectionState,
                            imeHost = imeHost,
                            focusRequester = focusRequester
                        )
                    )
                },
                onLongPress = { offset ->
                    handleLongPress(offset, currentMetrics, controller, selectionState, hapticFeedback)
                }
            )
        }
}

private fun handleTap(
    offset: Offset,
    event: MotionEvent,
    context: TapContext
) {
    context.controller.settleVisualScrollOffset()
    try {
        if (context.selectionState.isSelecting) {
            context.selectionState.clear()
            return
        }
        if (context.config.onSingleTap(event)) return

        val frame = context.controller.currentFrame()
        // The frame is a rendered snapshot and may lag the live terminal mode.
        // Ghostty's encoder ignores mouse events when tracking is disabled, so
        // forwarding the pair unconditionally avoids dropping taps during a mode
        // transition.
        sendTapAsMouseClick(offset.x, offset.y, context.metrics, context.controller)
        if (shouldOpenKeyboard(context.config, frame, event)) {
            context.focusRequester.requestFocus()
            context.imeHost.open()
        }
        val link = linkAt(frame, offset.x, offset.y, context.metrics)
        if (link != null) {
            context.config.onOpenUrl(link)
        }
    } finally {
        event.recycle()
    }
}

private fun shouldOpenKeyboard(
    config: TerminalCanvasConfig,
    frame: TerminalFrame?,
    event: MotionEvent
): Boolean {
    if (event.isFromSource(InputDevice.SOURCE_MOUSE)) return false
    val keyboardAllowedByMode = config.unconditionalKeyboardOnTap ||
        frame == null ||
        !frame.mouseTrackingActive
    return keyboardAllowedByMode
}

private fun handleLongPress(
    offset: Offset,
    metrics: TerminalMetrics,
    controller: TerminalController,
    selectionState: TerminalSelectionState,
    hapticFeedback: HapticFeedback
) {
    controller.settleVisualScrollOffset()
    if (selectionState.isSelecting) return
    hapticFeedback.performHapticFeedback(HapticFeedbackType.LongPress)
    val frame = controller.currentFrame() ?: return
    val column = metrics.xToColumn(offset.x)
    val row = metrics.yToRow(offset.y, frame.topRow)
    selectionState.startWordSelection(frame, column, row)
}

/** Builds the left-button press/release pair used for every terminal tap. */
private fun sendTapAsMouseClick(
    x: Float,
    y: Float,
    metrics: TerminalMetrics,
    controller: TerminalController
) {
    for (event in tapMouseEventsForPosition(x, y, metrics)) {
        controller.submit(TerminalCommand.Mouse(event))
    }
}

/** Keeps tap transport independent from the lagging rendered mode snapshot. */
internal fun tapMouseEventsForPosition(
    x: Float,
    y: Float,
    metrics: TerminalMetrics
): List<TerminalPointerEvent> {
    val press = TerminalPointerEvent(
        action = TerminalPointerEvent.Action.PRESS,
        button = TerminalPointerEvent.BUTTON_LEFT,
        xPx = x,
        yPx = y,
        cellWidthPx = metrics.cellWidthPx,
        cellHeightPx = metrics.cellHeightPx,
        lineSpacingAndAscentPx = metrics.lineSpacingAndAscentPx,
        viewportWidthPx = metrics.viewportWidthPx,
        viewportHeightPx = metrics.viewportHeightPx
    )
    return listOf(press, press.copy(action = TerminalPointerEvent.Action.RELEASE))
}

/** Hit-tests a tap against the frame's visible link layout. */
private fun linkAt(frame: TerminalFrame?, x: Float, y: Float, metrics: TerminalMetrics): String? {
    if (frame == null) return null
    val column = metrics.xToColumn(x)
    val row = metrics.yToRow(y, frame.topRow)
    return frame.linkLayout?.findAt(row, column)?.url
}
