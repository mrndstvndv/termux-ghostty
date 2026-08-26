package com.termux.terminal.compose.internal

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawOutline
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import com.termux.terminal.compose.ScrollbarVisibility
import com.termux.terminal.compose.TerminalCommand
import com.termux.terminal.compose.TerminalMetrics
import com.termux.terminal.compose.TerminalScrollbarConfig
import kotlinx.coroutines.delay

/**
 * Customizable Jetpack Compose scrollbar overlay for [com.termux.terminal.compose.TerminalCanvas].
 */
@Composable
internal fun TerminalScrollbar(
    controller: TerminalController,
    metrics: TerminalMetrics,
    config: TerminalScrollbarConfig,
    modifier: Modifier = Modifier
) {
    if (!config.enabled || config.visibility is ScrollbarVisibility.Hidden) return

    val density = LocalDensity.current
    val minThumbLengthPx = remember(density, config.minThumbLength) {
        with(density) { config.minThumbLength.toPx() }
    }

    val tickState = rememberScrollbarFrameTick(controller)
    val frame = controller.currentFrame()
    val topRow = frame?.topRow ?: 0
    val transcriptRows = frame?.transcriptRows ?: 0
    val visualOffsetPx = controller.visualScrollOffsetPx
    val sequence = frame?.sequence ?: 0L

    var isDragging by remember { mutableStateOf(false) }
    var currentGeometry by remember { mutableStateOf<TerminalScrollbarGeometry?>(null) }

    val alphaState = rememberScrollbarAlpha(
        config = config,
        topRow = topRow,
        sequence = sequence,
        visualOffsetPx = visualOffsetPx,
        transcriptRows = transcriptRows,
        scrollbarTick = tickState.value,
        isDragging = isDragging
    )

    val dragModifier = if (config.interactive) {
        Modifier.scrollbarPointerGestures(
            controller = controller,
            minThumbLengthPx = minThumbLengthPx,
            geometryProvider = { currentGeometry },
            onDraggingChanged = { isDragging = it }
        )
    } else {
        Modifier
    }

    Box(
        modifier = modifier
            .fillMaxHeight()
            .padding(config.padding)
            .width(config.thickness)
            .then(dragModifier)
    ) {
        Canvas(modifier = Modifier.matchParentSize()) {
            if (tickState.value >= 0) {
                val currentFrame = controller.currentFrame()
                val geom = TerminalScrollbarGeometry.calculate(
                    topRow = currentFrame?.topRow ?: 0,
                    rowsVisible = currentFrame?.rowsVisible ?: 0,
                    transcriptRows = currentFrame?.transcriptRows ?: 0,
                    visualScrollOffsetPx = controller.visualScrollOffsetPx,
                    cellHeightPx = metrics.cellHeightPx,
                    trackHeightPx = size.height,
                    minThumbLengthPx = minThumbLengthPx
                )
                currentGeometry = geom
                drawScrollbar(geom = geom, config = config, alpha = alphaState.value)
            }
        }
    }
}

@Composable
private fun rememberScrollbarFrameTick(controller: TerminalController): State<Int> {
    val tickState = remember { mutableIntStateOf(0) }
    DisposableEffect(controller) {
        val listener: () -> Unit = { tickState.intValue++ }
        controller.addFrameListener(listener)
        onDispose {
            controller.removeFrameListener(listener)
        }
    }
    return tickState
}

@Suppress("LongParameterList")
@Composable
private fun rememberScrollbarAlpha(
    config: TerminalScrollbarConfig,
    topRow: Int,
    sequence: Long,
    visualOffsetPx: Float,
    transcriptRows: Int,
    scrollbarTick: Int,
    isDragging: Boolean
): State<Float> {
    val alphaAnimatable = remember {
        Animatable(if (config.visibility is ScrollbarVisibility.Always) 1f else 0f)
    }
    val currentConfig by rememberUpdatedState(config)

    LaunchedEffect(topRow, sequence, visualOffsetPx, scrollbarTick, isDragging, config.visibility) {
        when (val visibility = currentConfig.visibility) {
            is ScrollbarVisibility.Always -> alphaAnimatable.snapTo(1f)
            is ScrollbarVisibility.AutoFade -> {
                if (isDragging) {
                    alphaAnimatable.snapTo(1f)
                } else if (transcriptRows > 0) {
                    alphaAnimatable.snapTo(1f)
                    delay(visibility.hideDelayMillis)
                    alphaAnimatable.animateTo(0f, animationSpec = tween(durationMillis = visibility.fadeDurationMillis))
                } else {
                    alphaAnimatable.snapTo(0f)
                }
            }
            is ScrollbarVisibility.Hidden -> alphaAnimatable.snapTo(0f)
        }
    }
    return alphaAnimatable.asState()
}

private fun DrawScope.drawScrollbar(
    geom: TerminalScrollbarGeometry,
    config: TerminalScrollbarConfig,
    alpha: Float
) {
    if (!geom.visible || alpha <= 0f) return

    if (config.trackColor.alpha > 0f) {
        drawRect(
            color = config.trackColor.copy(alpha = config.trackColor.alpha * alpha),
            size = size
        )
    }

    if (config.thumbColor.alpha > 0f && geom.thumbLengthPx > 0f) {
        drawThumb(
            thumbShape = config.thumbShape,
            thumbColor = config.thumbColor.copy(alpha = config.thumbColor.alpha * alpha),
            thumbLengthPx = geom.thumbLengthPx,
            thumbOffsetPx = geom.thumbOffsetPx,
            layoutDirection = layoutDirection,
            density = this
        )
    }
}

private fun DrawScope.drawThumb(
    thumbShape: androidx.compose.ui.graphics.Shape,
    thumbColor: androidx.compose.ui.graphics.Color,
    thumbLengthPx: Float,
    thumbOffsetPx: Float,
    layoutDirection: LayoutDirection,
    density: Density
) {
    val outline = thumbShape.createOutline(
        size = Size(width = size.width, height = thumbLengthPx),
        layoutDirection = layoutDirection,
        density = density
    )
    translate(top = thumbOffsetPx) {
        drawOutline(outline = outline, color = thumbColor)
    }
}

private fun Modifier.scrollbarPointerGestures(
    controller: TerminalController,
    minThumbLengthPx: Float,
    geometryProvider: () -> TerminalScrollbarGeometry?,
    onDraggingChanged: (Boolean) -> Unit
): Modifier {
    var dragAnchorY = 0f
    return this
        .pointerInput(controller, minThumbLengthPx) {
            detectTapGestures { offset ->
                val geom = geometryProvider() ?: return@detectTapGestures
                if (!geom.visible || geom.transcriptRows <= 0) return@detectTapGestures
                val currentFrame = controller.currentFrame() ?: return@detectTapGestures
                val delta = when {
                    offset.y < geom.thumbOffsetPx -> -currentFrame.rowsVisible
                    offset.y > geom.thumbOffsetPx + geom.thumbLengthPx -> currentFrame.rowsVisible
                    else -> 0
                }
                if (delta != 0) {
                    val newTop = (currentFrame.topRow + delta).coerceIn(-currentFrame.transcriptRows, 0)
                    controller.submit(TerminalCommand.SetViewportTopRow(newTop))
                }
            }
        }
        .pointerInput(controller, minThumbLengthPx) {
            detectDragGestures(
                onDragStart = { startOffset ->
                    val geom = geometryProvider() ?: return@detectDragGestures
                    if (geom.visible && geom.transcriptRows > 0 && geom.isOnThumb(startOffset.y)) {
                        onDraggingChanged(true)
                        dragAnchorY = startOffset.y - geom.thumbOffsetPx
                        controller.beginScrollGesture()
                    }
                },
                onDrag = { change, _ ->
                    val geom = geometryProvider() ?: return@detectDragGestures
                    val targetTop = geom.targetTopRowForPointerY(
                        pointerY = change.position.y,
                        dragAnchorOffsetY = dragAnchorY
                    )
                    controller.submit(TerminalCommand.SetViewportTopRow(targetTop))
                    change.consume()
                },
                onDragEnd = {
                    onDraggingChanged(false)
                    controller.settleVisualScrollOffset()
                },
                onDragCancel = {
                    onDraggingChanged(false)
                    controller.settleVisualScrollOffset()
                }
            )
        }
}
