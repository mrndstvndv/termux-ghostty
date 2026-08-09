package com.termux.terminal.compose.internal

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.runtime.MutableIntState
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.hapticfeedback.HapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.AwaitPointerEventScope
import androidx.compose.ui.input.pointer.PointerEvent
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import com.termux.terminal.compose.TerminalCanvasConfig
import com.termux.terminal.compose.TerminalCommand
import com.termux.terminal.compose.TerminalFrame
import com.termux.terminal.compose.TerminalMetrics
import com.termux.terminal.compose.TerminalPointerEvent
import com.termux.terminal.compose.TerminalPointerGeometry
import kotlin.math.abs
import kotlin.math.sqrt

/** Everything a gesture handler needs besides the per-gesture scroll state. */
private data class GestureContext(
    val controller: TerminalController,
    val metrics: TerminalMetrics,
    val config: TerminalCanvasConfig,
    val fontSizeState: MutableIntState,
    val onFontSizeChange: (Int) -> Unit
)

/** Per-gesture scroll, pan, and zoom state. */
private class ScrollGestureState {
    var dragAccumulator = 0f
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

/** Attaches the scroll/zoom/drag gesture handling to the terminal canvas. */
internal fun Modifier.terminalGestures(
    controller: TerminalController,
    metrics: TerminalMetrics,
    config: TerminalCanvasConfig,
    selectionState: TerminalSelectionState,
    fontSizeState: MutableIntState,
    onFontSizeChange: (Int) -> Unit
): Modifier = pointerInput(controller, metrics, config, selectionState, fontSizeState) {
    val context = GestureContext(controller, metrics, config, fontSizeState, onFontSizeChange)
    awaitEachGesture {
        handleTerminalGesture(selectionState, context)
    }
}

private suspend fun AwaitPointerEventScope.handleTerminalGesture(
    selectionState: TerminalSelectionState,
    context: GestureContext
) {
    var scaleAccumulator = 1f
    val touchSlop = viewConfiguration.touchSlop
    val scrollState = ScrollGestureState()

    awaitFirstDown(requireUnconsumed = false)
    if (selectionState.isSelecting) return

    do {
        val event = awaitPointerEvent()
        val canceled = event.changes.any { it.isConsumed }
        if (!canceled) {
            scaleAccumulator = handleGestureEvent(event, scaleAccumulator, scrollState, touchSlop, context)
        }
    } while (!canceled && event.changes.any { it.pressed })
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
        handleScrollGesture(event, context, scrollState)
    }
    return scaleAccumulator
}

/** Applies pinch-zoom font stepping; returns the next scale accumulator. */
private fun handlePinchZoom(
    event: PointerEvent,
    scaleAccumulator: Float,
    context: GestureContext
): Float {
    event.changes.forEach { if (it.positionChanged()) it.consume() }
    val zoomChange = event.calculateZoom()
    if (zoomChange == 1f) return scaleAccumulator
    val accumulated = scaleAccumulator * zoomChange
    if (accumulated >= 0.9f && accumulated <= 1.1f) return accumulated

    val increase = accumulated > 1f
    val newSize = context.fontSizeState.intValue + (if (increase) 1 else -1) * 2
    val clampedSize = newSize.coerceIn(context.config.minimumFontSize, context.config.maximumFontSize)
    if (clampedSize != context.fontSizeState.intValue) {
        context.fontSizeState.intValue = clampedSize
        context.onFontSizeChange(clampedSize)
    }
    return 1f
}

/** Applies vertical scroll through backend-routed incremental deltas. */
private fun handleScrollGesture(
    event: PointerEvent,
    context: GestureContext,
    scrollState: ScrollGestureState
) {
    event.changes.forEach { if (it.positionChanged()) it.consume() }
    // Keep routing in the backend; a rendered frame can lag live multiplexer modes.
    val centroid = event.calculateCentroid()
    scrollState.dragAccumulator += event.calculatePan().y
    val deltaRows = (scrollState.dragAccumulator / context.metrics.cellHeightPx).toInt()
    if (deltaRows != 0) {
        scrollState.dragAccumulator -= deltaRows * context.metrics.cellHeightPx
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
internal fun Modifier.terminalTaps(
    controller: TerminalController,
    metrics: TerminalMetrics,
    config: TerminalCanvasConfig,
    selectionState: TerminalSelectionState,
    imeHost: ImeHost,
    focusRequester: FocusRequester,
    hapticFeedback: HapticFeedback
): Modifier = pointerInput(controller, metrics, config, selectionState, imeHost, focusRequester) {
    detectTapGestures(
        onTap = { offset ->
            handleTap(offset, controller, metrics, config, selectionState, imeHost, focusRequester)
        },
        onLongPress = { offset ->
            handleLongPress(offset, metrics, controller, selectionState, hapticFeedback)
        }
    )
}

private fun handleTap(
    offset: Offset,
    controller: TerminalController,
    metrics: TerminalMetrics,
    config: TerminalCanvasConfig,
    selectionState: TerminalSelectionState,
    imeHost: ImeHost,
    focusRequester: FocusRequester
) {
    if (selectionState.isSelecting) {
        selectionState.clear()
        return
    }
    val frame = controller.currentFrame()
    // The frame is a rendered snapshot and may lag the live terminal mode.
    // Ghostty's encoder ignores mouse events when tracking is disabled, so
    // forwarding the pair unconditionally avoids dropping taps during a mode
    // transition.
    sendTapAsMouseClick(offset.x, offset.y, metrics, controller)
    if (config.unconditionalKeyboardOnTap || frame == null || !frame.mouseTrackingActive) {
        focusRequester.requestFocus()
        imeHost.open()
    }
    val link = linkAt(frame, offset.x, offset.y, metrics)
    if (link != null) {
        config.onOpenUrl(link)
    }
}

private fun handleLongPress(
    offset: Offset,
    metrics: TerminalMetrics,
    controller: TerminalController,
    selectionState: TerminalSelectionState,
    hapticFeedback: HapticFeedback
) {
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
    val press = tapMousePressForPosition(x, y, metrics)
    controller.submit(TerminalCommand.Mouse(press))
    controller.submit(TerminalCommand.Mouse(press.copy(action = TerminalPointerEvent.Action.RELEASE)))
}

/** Builds the left-button press event used by terminal taps. */
private fun tapMousePressForPosition(
    x: Float,
    y: Float,
    metrics: TerminalMetrics
): TerminalPointerEvent = TerminalPointerEvent(
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

/** Keeps tap transport independent from the lagging rendered mode snapshot. */
internal fun tapMouseEventsForPosition(
    x: Float,
    y: Float,
    metrics: TerminalMetrics
): List<TerminalPointerEvent> {
    val press = tapMousePressForPosition(x, y, metrics)
    return listOf(press, press.copy(action = TerminalPointerEvent.Action.RELEASE))
}

/** Hit-tests a tap against the frame's visible link layout. */
private fun linkAt(frame: TerminalFrame?, x: Float, y: Float, metrics: TerminalMetrics): String? {
    if (frame == null) return null
    val column = metrics.xToColumn(x)
    val row = metrics.yToRow(y, frame.topRow)
    return frame.linkLayout?.findAt(row, column)?.url
}
