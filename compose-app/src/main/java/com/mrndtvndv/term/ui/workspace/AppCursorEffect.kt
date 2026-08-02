package com.mrndtvndv.term.ui.workspace

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.lerp
import com.termux.terminal.compose.CursorEffect
import com.termux.terminal.compose.CursorEffectState
import com.termux.terminal.compose.TerminalCursor
import com.termux.terminal.compose.TerminalFrame
import com.termux.terminal.compose.TerminalMetrics
import com.termux.terminal.compose.TerminalPalette
import kotlin.math.abs
import kotlin.math.sqrt

internal class AppCursorEffect(
    private val effect: CursorTrailEffect
) : CursorEffect {
    override val maxDurationSeconds: Float
        get() = when (effect) {
            CursorTrailEffect.WARP -> WarpDurationSeconds
            CursorTrailEffect.SWEEP -> SweepDurationSeconds
            CursorTrailEffect.TAIL -> TailDurationSeconds
            CursorTrailEffect.NONE -> 0f
        }

    override fun draw(
        drawScope: DrawScope,
        frame: TerminalFrame,
        metrics: TerminalMetrics,
        state: CursorEffectState,
        timeSeconds: Float
    ) {
        if (!canDraw(frame, state)) return

        val moveColumns = (state.currentColumn - state.previousColumn).toFloat()
        val moveRows = (state.currentRow - state.previousRow).toFloat()
        val moveDistance = sqrt(moveColumns * moveColumns + moveRows * moveRows)
        val progressSeconds = timeSeconds - state.changeSeconds
        if (moveDistance < TrailMinDistanceCells ||
            progressSeconds >= maxDurationSeconds - 0.001f
        ) return

        val currentRect = cursorRectForCell(
            column = state.currentColumn,
            row = state.currentRow,
            topRow = frame.topRow,
            metrics = metrics,
            cursorStyle = frame.cursor.style
        )
        val previousRect = cursorRectForCell(
            column = state.previousColumn,
            row = state.previousRow,
            topRow = frame.topRow,
            metrics = metrics,
            cursorStyle = frame.cursor.style
        )
        val cursorColor = cursorTrailColor(frame)

        when (effect) {
            CursorTrailEffect.NONE -> Unit
            CursorTrailEffect.WARP -> drawWarpCursorTrail(
                drawScope,
                state,
                frame.topRow,
                metrics,
                frame.cursor.style,
                progressSeconds,
                cursorColor
            )
            CursorTrailEffect.SWEEP -> drawSweepCursorTrail(
                drawScope,
                previousRect,
                currentRect,
                progressSeconds,
                cursorColor
            )
            CursorTrailEffect.TAIL -> drawTailCursorTrail(
                drawScope,
                previousRect,
                currentRect,
                moveDistance,
                progressSeconds,
                cursorColor
            )
        }
    }
}

private fun canDraw(frame: TerminalFrame, state: CursorEffectState): Boolean =
    frame.cursor.visible && state.hasPreviousPosition &&
        state.previousColumn >= 0 && state.previousRow >= 0

private data class WarpQuad(
    val topLeft: Offset,
    val topRight: Offset,
    val bottomRight: Offset,
    val bottomLeft: Offset,
    val cursorRect: Rect
)

private fun drawWarpCursorTrail(
    drawScope: DrawScope,
    state: CursorEffectState,
    topRow: Int,
    metrics: TerminalMetrics,
    cursorStyle: Int,
    progressSeconds: Float,
    cursorColor: Color
) {
    val quad = warpQuad(state, topRow, metrics, cursorStyle, progressSeconds)
    val path = Path().apply {
        fillType = PathFillType.EvenOdd
        moveTo(quad.topLeft.x, quad.topLeft.y)
        lineTo(quad.topRight.x, quad.topRight.y)
        lineTo(quad.bottomRight.x, quad.bottomRight.y)
        lineTo(quad.bottomLeft.x, quad.bottomLeft.y)
        close()
        addRect(quad.cursorRect)
    }
    drawScope.drawPath(path, cursorColor)
}

private fun warpQuad(
    state: CursorEffectState,
    topRow: Int,
    metrics: TerminalMetrics,
    cursorStyle: Int,
    progressSeconds: Float
): WarpQuad {
    fun rectCorners(x: Float, y: Float): List<Offset> {
        val halfWidth = metrics.cellWidthPx * 0.5f * WarpThicknessX
        val halfHeight = metrics.cellHeightPx * 0.5f * WarpThicknessY
        val centerX = x + metrics.cellWidthPx * 0.5f
        val centerY = y + metrics.cellHeightPx * 0.5f
        return listOf(
            Offset(centerX - halfWidth, centerY - halfHeight),
            Offset(centerX + halfWidth, centerY - halfHeight),
            Offset(centerX + halfWidth, centerY + halfHeight),
            Offset(centerX - halfWidth, centerY + halfHeight)
        )
    }

    val current = rectCorners(
        state.currentColumn * metrics.cellWidthPx,
        (state.currentRow - topRow) * metrics.cellHeightPx
    )
    val previous = rectCorners(
        state.previousColumn * metrics.cellWidthPx,
        (state.previousRow - topRow) * metrics.cellHeightPx
    )
    val signX = if (state.currentColumn >= state.previousColumn) 1f else -1f
    val signY = if (state.currentRow >= state.previousRow) 1f else -1f
    val leadDuration = WarpDurationSeconds * (1f - WarpTrailSize)
    val sideDuration = (leadDuration + WarpDurationSeconds) / 2f

    fun durationFromDot(dot: Float): Float = when {
        dot >= 0.5f -> leadDuration
        dot >= -0.5f -> sideDuration
        else -> WarpDurationSeconds
    }

    var topLeftDuration = durationFromDot(-signX + signY)
    var topRightDuration = durationFromDot(signX + signY)
    var bottomLeftDuration = durationFromDot(-signX - signY)
    var bottomRightDuration = durationFromDot(signX - signY)
    val leftDuration = durationFromDot(-signX)
    val rightDuration = durationFromDot(signX)
    if (signX < -0.5f) {
        topLeftDuration = leftDuration
        bottomLeftDuration = leftDuration
    }
    if (signX >= 0.5f) {
        topRightDuration = rightDuration
        bottomRightDuration = rightDuration
    }

    fun eased(progress: Float, from: Offset, to: Offset): Offset {
        val fraction = easeOutCirc(progress.coerceIn(0f, 1f))
        return Offset(
            from.x + (to.x - from.x) * fraction,
            from.y + (to.y - from.y) * fraction
        )
    }

    return WarpQuad(
        topLeft = eased(progressSeconds / topLeftDuration, previous[0], current[0]),
        topRight = eased(progressSeconds / topRightDuration, previous[1], current[1]),
        bottomRight = eased(progressSeconds / bottomRightDuration, previous[2], current[2]),
        bottomLeft = eased(progressSeconds / bottomLeftDuration, previous[3], current[3]),
        cursorRect = cursorRectForCell(
            state.currentColumn,
            state.currentRow,
            topRow,
            metrics,
            cursorStyle
        )
    )
}

private fun drawSweepCursorTrail(
    drawScope: DrawScope,
    previousRect: Rect,
    currentRect: Rect,
    progressSeconds: Float,
    cursorColor: Color
) {
    val shrink = easeOutCubic(progressSeconds / SweepDurationSeconds)
    val tailRect = lerpRect(
        start = currentRect,
        end = previousRect,
        fraction = SweepTrailLength * (1f - shrink)
    )
    drawTrailBetweenRects(drawScope, currentRect, tailRect, currentRect, cursorColor)
}

private fun drawTailCursorTrail(
    drawScope: DrawScope,
    previousRect: Rect,
    currentRect: Rect,
    moveDistance: Float,
    progressSeconds: Float,
    cursorColor: Color
) {
    val progress = (progressSeconds / TailDurationSeconds).coerceIn(0f, 1f)
    val isLongMove = moveDistance >= TailMaxTrailLengthCells
    val tailDelay = (TailMaxTrailLengthCells / moveDistance).coerceIn(0f, 1f)
    val headProgress = if (isLongMove) 1f else easeOutCirc(progress)
    val tailProgress = if (isLongMove) {
        easeOutCirc(progress)
    } else {
        easeOutCirc(smoothStep(tailDelay, 1f, progress))
    }
    drawTrailBetweenRects(
        drawScope,
        lerpRect(previousRect, currentRect, headProgress),
        lerpRect(previousRect, currentRect, tailProgress),
        currentRect,
        cursorColor
    )
}

private fun drawTrailBetweenRects(
    drawScope: DrawScope,
    headRect: Rect,
    tailRect: Rect,
    cursorRect: Rect,
    color: Color
) {
    val path = trailPathBetweenRects(headRect, tailRect).apply {
        fillType = PathFillType.EvenOdd
        addRect(cursorRect)
    }
    drawScope.drawPath(path, color)
}

private fun trailPathBetweenRects(first: Rect, second: Rect): Path {
    val path = Path()
    val horizontalDistance = abs(first.center.x - second.center.x)
    val verticalDistance = abs(first.center.y - second.center.y)
    if (horizontalDistance < 0.001f || verticalDistance < 0.001f) {
        path.addRect(
            Rect(
                left = minOf(first.left, second.left),
                top = minOf(first.top, second.top),
                right = maxOf(first.right, second.right),
                bottom = maxOf(first.bottom, second.bottom)
            )
        )
        return path
    }

    val hull = convexHull(
        listOf(
            Offset(first.left, first.top),
            Offset(first.right, first.top),
            Offset(first.right, first.bottom),
            Offset(first.left, first.bottom),
            Offset(second.left, second.top),
            Offset(second.right, second.top),
            Offset(second.right, second.bottom),
            Offset(second.left, second.bottom)
        )
    )
    if (hull.isEmpty()) return path
    path.moveTo(hull.first().x, hull.first().y)
    hull.drop(1).forEach { point -> path.lineTo(point.x, point.y) }
    path.close()
    return path
}

private fun convexHull(points: List<Offset>): List<Offset> {
    val sortedPoints = points.sortedWith(compareBy<Offset> { it.x }.thenBy { it.y })
    if (sortedPoints.size <= 1) return sortedPoints

    fun crossProduct(origin: Offset, first: Offset, second: Offset): Float =
        (first.x - origin.x) * (second.y - origin.y) -
            (first.y - origin.y) * (second.x - origin.x)

    fun buildHalf(input: List<Offset>): MutableList<Offset> {
        val half = mutableListOf<Offset>()
        input.forEach { point ->
            while (half.size >= 2 && crossProduct(half[half.lastIndex - 1], half.last(), point) <= 0f) {
                half.removeAt(half.lastIndex)
            }
            half.add(point)
        }
        return half
    }

    val lower = buildHalf(sortedPoints)
    val upper = buildHalf(sortedPoints.asReversed())
    lower.removeAt(lower.lastIndex)
    upper.removeAt(upper.lastIndex)
    return lower + upper
}

private fun lerpRect(start: Rect, end: Rect, fraction: Float): Rect {
    fun lerpFloat(from: Float, to: Float): Float = from + (to - from) * fraction
    return Rect(
        left = lerpFloat(start.left, end.left),
        top = lerpFloat(start.top, end.top),
        right = lerpFloat(start.right, end.right),
        bottom = lerpFloat(start.bottom, end.bottom)
    )
}

private fun cursorRectForCell(
    column: Int,
    row: Int,
    topRow: Int,
    metrics: TerminalMetrics,
    cursorStyle: Int
): Rect {
    val left = column * metrics.cellWidthPx
    val top = (row - topRow) * metrics.cellHeightPx
    val right = left + metrics.cellWidthPx
    val bottom = top + metrics.cellHeightPx
    return when (cursorStyle) {
        TerminalCursor.STYLE_UNDERLINE -> Rect(left, bottom - metrics.cellHeightPx / 4f, right, bottom)
        TerminalCursor.STYLE_BAR -> Rect(left, top, left + metrics.cellWidthPx / 4f, bottom)
        else -> Rect(left, top, right, bottom)
    }
}

private fun cursorTrailColor(frame: TerminalFrame): Color {
    var color = Color(frame.palette.color(TerminalPalette.COLOR_INDEX_CURSOR))
    val luminance = 0.299f * color.red + 0.587f * color.green + 0.114f * color.blue
    if (luminance < 0.45f) color = lerp(color, Color.White, 0.5f)
    return color
}

private fun easeOutCirc(value: Float): Float = sqrt(1f - (value - 1f) * (value - 1f))

private fun smoothStep(edge0: Float, edge1: Float, value: Float): Float {
    if (edge1 <= edge0) return if (value < edge1) 0f else 1f
    val normalized = ((value - edge0) / (edge1 - edge0)).coerceIn(0f, 1f)
    return normalized * normalized * (3f - 2f * normalized)
}

private fun easeOutCubic(value: Float): Float {
    val clamped = value.coerceIn(0f, 1f)
    val inverse = 1f - clamped
    return 1f - inverse * inverse * inverse
}

private const val TrailMinDistanceCells = 1.5f
private const val WarpDurationSeconds = 0.2f
private const val WarpTrailSize = 0.8f
private const val WarpThicknessY = 1.0f
private const val WarpThicknessX = 0.9f
private const val SweepDurationSeconds = 0.2f
private const val SweepTrailLength = 0.5f
private const val TailDurationSeconds = 0.09f
private const val TailMaxTrailLengthCells = 8f
