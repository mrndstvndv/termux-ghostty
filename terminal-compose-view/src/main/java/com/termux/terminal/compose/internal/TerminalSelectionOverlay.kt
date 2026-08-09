package com.termux.terminal.compose.internal

import android.content.Context
import android.content.res.Resources
import android.util.TypedValue
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableIntState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.termux.terminal.compose.TerminalFrame
import com.termux.terminal.compose.TerminalMetrics
import com.termux.terminal.compose.TerminalSelection
import kotlin.math.roundToInt

private val HANDLE_VISUAL_SIZE = 20.dp
private val HANDLE_TOUCH_TARGET_SIZE = 48.dp

/** Draws and drags the two selection endpoints above the terminal canvas. */
@Suppress("LongParameterList")
@Composable
internal fun TerminalSelectionOverlay(
    selection: TerminalSelection,
    controller: TerminalController,
    contentVersionState: MutableIntState,
    metrics: TerminalMetrics,
    configuredColor: Color,
    onHandleDragStart: (SelectionHandleEndpoint) -> Unit,
    onHandleDragEnd: (SelectionHandleEndpoint) -> Unit,
    onHandleDrag: (SelectionHandleEndpoint, Float, Float) -> Unit
) {
    if (selection.isEmpty) return
    val frame = remember(contentVersionState.intValue) { controller.currentFrame() }
    if (frame == null) return

    val density = LocalDensity.current
    val visualSizePx = with(density) { HANDLE_VISUAL_SIZE.toPx() }
    val touchTargetSize = HANDLE_TOUCH_TARGET_SIZE
    val touchTargetSizePx = with(density) { touchTargetSize.toPx() }
    val handleColor = rememberSelectionHandleColor(configuredColor)
    var draggingEndpoint by remember { mutableStateOf<SelectionHandleEndpoint?>(null) }
    val currentOnHandleDragStart by rememberUpdatedState(onHandleDragStart)
    val currentOnHandleDragEnd by rememberUpdatedState(onHandleDragEnd)
    val currentOnHandleDrag by rememberUpdatedState(onHandleDrag)

    val startPosition = selectionHandlePosition(
        selection = selection,
        frame = frame,
        metrics = metrics,
        endpoint = SelectionHandleEndpoint.START,
        visualSizePx = visualSizePx,
        touchTargetSizePx = touchTargetSizePx
    )
    val endPosition = selectionHandlePosition(
        selection = selection,
        frame = frame,
        metrics = metrics,
        endpoint = SelectionHandleEndpoint.END,
        visualSizePx = visualSizePx,
        touchTargetSizePx = touchTargetSizePx
    )

    val callbacks = SelectionHandleCallbacks(
        onStart = { endpoint, position ->
            draggingEndpoint = endpoint
            currentOnHandleDragStart(endpoint)
            currentOnHandleDrag(endpoint, position.x, position.y)
        },
        onDrag = { endpoint, position ->
            currentOnHandleDrag(endpoint, position.x, position.y)
        },
        onEnd = { endpoint ->
            draggingEndpoint = null
            currentOnHandleDragEnd(endpoint)
        }
    )
    val positions = mapOf(
        SelectionHandleEndpoint.START to startPosition,
        SelectionHandleEndpoint.END to endPosition
    )
    SelectionHandleEndpoint.values().forEach { endpoint ->
        SelectionEndpointHandle(
            position = positions.getValue(endpoint),
            endpoint = endpoint,
            isDragging = draggingEndpoint == endpoint,
            color = handleColor,
            touchTargetSize = touchTargetSize,
            callbacks = callbacks
        )
    }
}

private data class SelectionHandleCallbacks(
    val onStart: (SelectionHandleEndpoint, Offset) -> Unit,
    val onDrag: (SelectionHandleEndpoint, Offset) -> Unit,
    val onEnd: (SelectionHandleEndpoint) -> Unit
)

@Composable
private fun SelectionEndpointHandle(
    position: SelectionHandlePosition,
    endpoint: SelectionHandleEndpoint,
    isDragging: Boolean,
    color: Color,
    touchTargetSize: Dp,
    callbacks: SelectionHandleCallbacks
) {
    if (!position.isVisible && !isDragging) return
    SelectionHandle(
        position = position,
        endpoint = endpoint,
        color = color,
        touchTargetSize = touchTargetSize,
        onDragStart = { callbacks.onStart(endpoint, it) },
        onDrag = callbacks.onDrag,
        onDragEnd = { callbacks.onEnd(endpoint) }
    )
}

@Composable
private fun SelectionHandle(
    position: SelectionHandlePosition,
    endpoint: SelectionHandleEndpoint,
    color: Color,
    touchTargetSize: Dp,
    onDragStart: (Offset) -> Unit,
    onDrag: (SelectionHandleEndpoint, Offset) -> Unit,
    onDragEnd: () -> Unit
) {
    val currentPosition = rememberUpdatedState(position)
    Canvas(
        modifier = Modifier
            .offset { IntOffset(position.touchLeft.roundToInt(), position.anchorY.roundToInt()) }
            .size(touchTargetSize)
            .semantics {
                contentDescription = if (endpoint == SelectionHandleEndpoint.START) {
                    "Start selection handle"
                } else {
                    "End selection handle"
                }
            }
            .selectionHandleGestures(
                endpoint = endpoint,
                currentPosition = currentPosition,
                onDragStart = onDragStart,
                onDrag = onDrag,
                onDragEnd = onDragEnd
            )
    ) {
        drawSelectionHandle(position, color)
    }
}

private fun Modifier.selectionHandleGestures(
    endpoint: SelectionHandleEndpoint,
    currentPosition: androidx.compose.runtime.State<SelectionHandlePosition>,
    onDragStart: (Offset) -> Unit,
    onDrag: (SelectionHandleEndpoint, Offset) -> Unit,
    onDragEnd: () -> Unit
): Modifier = pointerInput(endpoint) {
    awaitEachGesture {
        val down = awaitFirstDown(requireUnconsumed = false)
        down.consume()
        var isDragging = false
        var dragState: SelectionHandleDragState? = null
        val touchSlopSquared = viewConfiguration.touchSlop * viewConfiguration.touchSlop
        try {
            var pointerActive = true
            while (pointerActive) {
                val event = awaitPointerEvent()
                val change = event.changes.firstOrNull { it.id == down.id }
                pointerActive = change?.pressed == true
                if (change != null && change.pressed) {
                    val fromDown = change.position - down.position
                    val distanceSquared = fromDown.x * fromDown.x + fromDown.y * fromDown.y
                    if (!isDragging && distanceSquared > touchSlopSquared) {
                        isDragging = true
                        val handlePosition = currentPosition.value
                        val anchor = Offset(handlePosition.anchorX, handlePosition.anchorY)
                        dragState = SelectionHandleDragState(
                            initialPosition = anchor,
                            initialHandleOffset = handlePosition.handleOffset(),
                            initialPointerPosition = down.position
                        )
                        onDragStart(anchor)
                    }
                    if (isDragging) {
                        change.consume()
                        val position = checkNotNull(dragState).position(
                            handleOffset = currentPosition.value.handleOffset(),
                            pointerPosition = change.position
                        )
                        onDrag(endpoint, position)
                    }
                }
            }
        } finally {
            if (isDragging) onDragEnd()
        }
    }
}

private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawSelectionHandle(
    position: SelectionHandlePosition,
    color: Color
) {
    val radius = position.visualSizePx / 2f
    val visualLeft = if (position.pointsLeft) size.width - position.visualSizePx else 0f
    val rectangleLeft = if (position.pointsLeft) visualLeft + radius else visualLeft
    drawRect(
        color = color,
        topLeft = Offset(rectangleLeft, 0f),
        size = Size(radius, radius)
    )
    drawCircle(
        color = color,
        radius = radius,
        center = Offset(visualLeft + radius, radius)
    )
}

internal data class SelectionHandlePosition(
    val anchorX: Float,
    val anchorY: Float,
    val touchLeft: Float,
    val visualSizePx: Float,
    val pointsLeft: Boolean,
    val isVisible: Boolean
)

private fun SelectionHandlePosition.handleOffset(): Offset =
    Offset(touchLeft.roundToInt().toFloat(), anchorY.roundToInt().toFloat())

internal fun selectionHandlePosition(
    selection: TerminalSelection,
    frame: TerminalFrame,
    metrics: TerminalMetrics,
    endpoint: SelectionHandleEndpoint,
    visualSizePx: Float,
    touchTargetSizePx: Float
): SelectionHandlePosition {
    val column = if (endpoint == SelectionHandleEndpoint.START) {
        selection.startCol
    } else {
        selection.endCol + 1
    }
    val row = if (endpoint == SelectionHandleEndpoint.START) selection.startRow else selection.endRow
    val anchorX = metrics.columnToX(column)
    val rawAnchorY = metrics.rowToY(row + 1, frame.topRow)
    val maxAnchorY = (metrics.viewportHeightPx - touchTargetSizePx).coerceAtLeast(0f)
    val anchorY = rawAnchorY.coerceIn(0f, maxAnchorY)
    val pointsLeft = if (endpoint == SelectionHandleEndpoint.START) {
        anchorX - visualSizePx >= 0f
    } else {
        anchorX + visualSizePx > metrics.viewportWidthPx
    }
    val visualLeft = if (pointsLeft) anchorX - visualSizePx else anchorX
    val touchLeft = if (pointsLeft) {
        visualLeft - (touchTargetSizePx - visualSizePx)
    } else {
        visualLeft
    }
    val isVisible = metrics.viewportWidthPx > 0 && metrics.viewportHeightPx > 0
    return SelectionHandlePosition(
        anchorX = anchorX,
        anchorY = anchorY,
        touchLeft = touchLeft,
        visualSizePx = visualSizePx,
        pointsLeft = pointsLeft,
        isVisible = isVisible
    )
}

@Composable
internal fun rememberSelectionHandleColor(configuredColor: Color): Color {
    if (configuredColor != Color.Unspecified) return configuredColor
    val context = LocalContext.current
    return remember(context) { resolveSelectionHandleColor(context) }
}

private fun resolveSelectionHandleColor(context: Context): Color {
    val value = TypedValue()
    if (!context.theme.resolveAttribute(android.R.attr.colorAccent, value, true)) {
        return Color.White
    }
    if (value.resourceId == 0) return Color(value.data)
    return try {
        Color(context.getColor(value.resourceId))
    } catch (_: Resources.NotFoundException) {
        Color.White
    }
}
