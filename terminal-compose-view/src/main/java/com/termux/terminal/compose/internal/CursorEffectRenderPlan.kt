package com.termux.terminal.compose.internal

import com.termux.terminal.compose.CursorEffect
import com.termux.terminal.compose.CursorEffectSnapshot
import com.termux.terminal.compose.TerminalCursor
import com.termux.terminal.compose.TerminalFrame
import com.termux.terminal.compose.TerminalMetrics
import com.termux.terminal.compose.TerminalPalette
import kotlin.math.abs
import kotlin.math.sqrt

private const val CoordinatesPerVertex = 2
private const val MaxPolygonVertices = 8
private const val TrailMinDistanceCells = 1.5f
private const val TrailMaxDistanceCells = 8f
private const val TrailEndEpsilonSeconds = 0.001f
private const val WarpTrailSize = 0.8f
private const val WarpThicknessX = 0.9f
private const val WarpThicknessY = 1f
private const val SweepTrailLength = 0.5f
private const val TailMaxTrailLengthCells = 8f

/** Reusable, allocation-free convex cursor-effect geometry for one renderer. */
internal class CursorEffectRenderPlan {
    val vertices = FloatArray(MaxPolygonVertices * CoordinatesPerVertex)
    var vertexCount: Int = 0
        private set
    var argb: Int = 0
        private set
    var cutoutLeft: Float = 0f
        private set
    var cutoutTop: Float = 0f
        private set
    var cutoutRight: Float = 0f
        private set
    var cutoutBottom: Float = 0f
        private set

    private val points = FloatArray(MaxPolygonVertices * CoordinatesPerVertex)
    private val sortedPoints = FloatArray(MaxPolygonVertices * CoordinatesPerVertex)
    val firstRect = EffectRect()
    val secondRect = EffectRect()
    val thirdRect = EffectRect()
    val fourthRect = EffectRect()

    fun clear() {
        vertexCount = 0
    }

    fun begin(argb: Int, cursor: EffectRect) {
        vertexCount = 0
        this.argb = argb
        cutoutLeft = cursor.left
        cutoutTop = cursor.top
        cutoutRight = cursor.right
        cutoutBottom = cursor.bottom
    }

    fun addVertex(x: Float, y: Float) {
        check(vertexCount < MaxPolygonVertices) { "cursor effect polygon exceeds $MaxPolygonVertices vertices" }
        val offset = vertexCount * CoordinatesPerVertex
        vertices[offset] = x
        vertices[offset + 1] = y
        vertexCount++
    }

    fun setHull(first: EffectRect, second: EffectRect) {
        val horizontalDistance = abs(first.centerX - second.centerX)
        val verticalDistance = abs(first.centerY - second.centerY)
        if (horizontalDistance < 0.001f || verticalDistance < 0.001f) {
            addVertex(minOf(first.left, second.left), minOf(first.top, second.top))
            addVertex(maxOf(first.right, second.right), minOf(first.top, second.top))
            addVertex(maxOf(first.right, second.right), maxOf(first.bottom, second.bottom))
            addVertex(minOf(first.left, second.left), maxOf(first.bottom, second.bottom))
            return
        }

        putRect(points, 0, first)
        putRect(points, 4, second)
        sortPoints()
        buildHull()
    }

    private fun sortPoints() {
        for (pointIndex in 0 until MaxPolygonVertices) {
            val source = pointIndex * CoordinatesPerVertex
            val x = points[source]
            val y = points[source + 1]
            var insertionIndex = pointIndex
            while (insertionIndex > 0) {
                val previous = (insertionIndex - 1) * CoordinatesPerVertex
                val comesAfter = sortedPoints[previous] > x ||
                    (sortedPoints[previous] == x && sortedPoints[previous + 1] > y)
                if (!comesAfter) break
                val destination = insertionIndex * CoordinatesPerVertex
                sortedPoints[destination] = sortedPoints[previous]
                sortedPoints[destination + 1] = sortedPoints[previous + 1]
                insertionIndex--
            }
            val destination = insertionIndex * CoordinatesPerVertex
            sortedPoints[destination] = x
            sortedPoints[destination + 1] = y
        }
    }

    private fun buildHull() {
        vertexCount = 0
        for (index in 0 until MaxPolygonVertices) appendHullPoint(index)
        val lowerSize = vertexCount
        for (index in MaxPolygonVertices - 2 downTo 0) {
            while (vertexCount > lowerSize && lastTurnIsNotCounterClockwise(index)) vertexCount--
            copySortedPoint(index)
        }
        if (vertexCount > 1) vertexCount--
    }

    private fun appendHullPoint(sortedIndex: Int) {
        while (vertexCount >= 2 && lastTurnIsNotCounterClockwise(sortedIndex)) vertexCount--
        copySortedPoint(sortedIndex)
    }

    private fun lastTurnIsNotCounterClockwise(sortedIndex: Int): Boolean {
        val origin = (vertexCount - 2) * CoordinatesPerVertex
        val first = (vertexCount - 1) * CoordinatesPerVertex
        val second = sortedIndex * CoordinatesPerVertex
        val cross = (vertices[first] - vertices[origin]) *
            (sortedPoints[second + 1] - vertices[origin + 1]) -
            (vertices[first + 1] - vertices[origin + 1]) *
            (sortedPoints[second] - vertices[origin])
        return cross <= 0f
    }

    private fun copySortedPoint(sortedIndex: Int) {
        val source = sortedIndex * CoordinatesPerVertex
        addVertex(sortedPoints[source], sortedPoints[source + 1])
    }

    private fun putRect(target: FloatArray, vertexOffset: Int, rect: EffectRect) {
        putPoint(target, vertexOffset, rect.left, rect.top)
        putPoint(target, vertexOffset + 1, rect.right, rect.top)
        putPoint(target, vertexOffset + 2, rect.right, rect.bottom)
        putPoint(target, vertexOffset + 3, rect.left, rect.bottom)
    }

    private fun putPoint(target: FloatArray, vertexIndex: Int, x: Float, y: Float) {
        val offset = vertexIndex * CoordinatesPerVertex
        target[offset] = x
        target[offset + 1] = y
    }
}

/** Plans one transient cursor polygon shared by Compose and GLES. */
internal fun planCursorEffect(
    effectSnapshot: CursorEffectSnapshot?,
    frame: TerminalFrame,
    metrics: TerminalMetrics,
    timeSeconds: Float,
    output: CursorEffectRenderPlan
): Boolean {
    output.clear()
    val movement = effectSnapshot ?: return false
    val cursor = frame.cursor
    val moveColumns = (movement.currentColumn - movement.previousColumn).toFloat()
    val moveRows = (movement.currentRow - movement.previousRow).toFloat()
    val moveDistance = sqrt(moveColumns * moveColumns + moveRows * moveRows)
    val progressSeconds = timeSeconds - movement.changeSeconds
    if (!isCurrentCursor(cursor, movement) ||
        moveDistance !in TrailMinDistanceCells..TrailMaxDistanceCells ||
        !isActiveProgress(movement, progressSeconds)
    ) {
        return false
    }

    val cursorRect = cursorRect(
        column = movement.currentColumn,
        row = movement.currentRow,
        topRow = frame.topRow,
        metrics = metrics,
        cursorStyle = cursor.style,
        output = output.firstRect
    )
    output.begin(cursorTrailColor(frame), cursorRect)
    when (movement.effect) {
        CursorEffect.WARP -> planWarp(movement, frame.topRow, metrics, progressSeconds, output)
        CursorEffect.SWEEP -> planSweep(movement, frame.topRow, metrics, cursor.style, progressSeconds, output)
        CursorEffect.TAIL -> planTail(
            movement,
            frame.topRow,
            metrics,
            cursor.style,
            moveDistance,
            progressSeconds,
            output
        )
    }
    return output.vertexCount >= 3
}

private fun isCurrentCursor(cursor: TerminalCursor, movement: CursorEffectSnapshot): Boolean =
    cursor.visible &&
        cursor.column == movement.currentColumn &&
        cursor.row == movement.currentRow

private fun isActiveProgress(movement: CursorEffectSnapshot, progressSeconds: Float): Boolean =
    progressSeconds >= 0f &&
        progressSeconds < movement.effect.maxDurationSeconds - TrailEndEpsilonSeconds

private fun planWarp(
    movement: CursorEffectSnapshot,
    topRow: Int,
    metrics: TerminalMetrics,
    progressSeconds: Float,
    output: CursorEffectRenderPlan
) {
    val halfWidth = metrics.cellWidthPx * 0.5f * WarpThicknessX
    val halfHeight = metrics.cellHeightPx * 0.5f * WarpThicknessY
    val currentCenterX = (movement.currentColumn + 0.5f) * metrics.cellWidthPx
    val currentCenterY = (movement.currentRow - topRow + 0.5f) * metrics.cellHeightPx
    val previousCenterX = (movement.previousColumn + 0.5f) * metrics.cellWidthPx
    val previousCenterY = (movement.previousRow - topRow + 0.5f) * metrics.cellHeightPx
    val signX = if (movement.currentColumn >= movement.previousColumn) 1f else -1f
    val signY = if (movement.currentRow >= movement.previousRow) 1f else -1f
    val leadDuration = CursorEffect.WARP.maxDurationSeconds * (1f - WarpTrailSize)
    val sideDuration = (leadDuration + CursorEffect.WARP.maxDurationSeconds) * 0.5f

    fun duration(dot: Float): Float = when {
        dot >= 0.5f -> leadDuration
        dot >= -0.5f -> sideDuration
        else -> CursorEffect.WARP.maxDurationSeconds
    }

    var topLeftDuration = duration(-signX + signY)
    var topRightDuration = duration(signX + signY)
    var bottomLeftDuration = duration(-signX - signY)
    var bottomRightDuration = duration(signX - signY)
    val leftDuration = duration(-signX)
    val rightDuration = duration(signX)
    if (signX < -0.5f) {
        topLeftDuration = leftDuration
        bottomLeftDuration = leftDuration
    } else {
        topRightDuration = rightDuration
        bottomRightDuration = rightDuration
    }

    addEasedVertex(
        output,
        previousCenterX - halfWidth,
        previousCenterY - halfHeight,
        currentCenterX - halfWidth,
        currentCenterY - halfHeight,
        progressSeconds / topLeftDuration
    )
    addEasedVertex(
        output,
        previousCenterX + halfWidth,
        previousCenterY - halfHeight,
        currentCenterX + halfWidth,
        currentCenterY - halfHeight,
        progressSeconds / topRightDuration
    )
    addEasedVertex(
        output,
        previousCenterX + halfWidth,
        previousCenterY + halfHeight,
        currentCenterX + halfWidth,
        currentCenterY + halfHeight,
        progressSeconds / bottomRightDuration
    )
    addEasedVertex(
        output,
        previousCenterX - halfWidth,
        previousCenterY + halfHeight,
        currentCenterX - halfWidth,
        currentCenterY + halfHeight,
        progressSeconds / bottomLeftDuration
    )
}

private fun addEasedVertex(
    output: CursorEffectRenderPlan,
    fromX: Float,
    fromY: Float,
    toX: Float,
    toY: Float,
    progress: Float
) {
    val fraction = easeOutCirc(progress.coerceIn(0f, 1f))
    output.addVertex(
        fromX + (toX - fromX) * fraction,
        fromY + (toY - fromY) * fraction
    )
}

private fun planSweep(
    movement: CursorEffectSnapshot,
    topRow: Int,
    metrics: TerminalMetrics,
    cursorStyle: Int,
    progressSeconds: Float,
    output: CursorEffectRenderPlan
) {
    val current = cursorRect(
        movement.currentColumn,
        movement.currentRow,
        topRow,
        metrics,
        cursorStyle,
        output.firstRect
    )
    val previous = cursorRect(
        movement.previousColumn,
        movement.previousRow,
        topRow,
        metrics,
        cursorStyle,
        output.secondRect
    )
    val shrink = easeOutCubic(progressSeconds / CursorEffect.SWEEP.maxDurationSeconds)
    val tail = lerpRect(
        current,
        previous,
        SweepTrailLength * (1f - shrink),
        output.thirdRect
    )
    output.setHull(current, tail)
}

private fun planTail(
    movement: CursorEffectSnapshot,
    topRow: Int,
    metrics: TerminalMetrics,
    cursorStyle: Int,
    moveDistance: Float,
    progressSeconds: Float,
    output: CursorEffectRenderPlan
) {
    val previous = cursorRect(
        movement.previousColumn,
        movement.previousRow,
        topRow,
        metrics,
        cursorStyle,
        output.firstRect
    )
    val current = cursorRect(
        movement.currentColumn,
        movement.currentRow,
        topRow,
        metrics,
        cursorStyle,
        output.secondRect
    )
    val progress = (progressSeconds / CursorEffect.TAIL.maxDurationSeconds).coerceIn(0f, 1f)
    val isLongMove = moveDistance >= TailMaxTrailLengthCells
    val tailDelay = (TailMaxTrailLengthCells / moveDistance).coerceIn(0f, 1f)
    val headProgress = if (isLongMove) 1f else easeOutCirc(progress)
    val tailProgress = if (isLongMove) {
        easeOutCirc(progress)
    } else {
        easeOutCirc(smoothStep(tailDelay, 1f, progress))
    }
    output.setHull(
        lerpRect(previous, current, headProgress, output.thirdRect),
        lerpRect(previous, current, tailProgress, output.fourthRect)
    )
}

internal class EffectRect {
    var left: Float = 0f
        private set
    var top: Float = 0f
        private set
    var right: Float = 0f
        private set
    var bottom: Float = 0f
        private set

    val centerX: Float
        get() = (left + right) * 0.5f
    val centerY: Float
        get() = (top + bottom) * 0.5f

    fun set(left: Float, top: Float, right: Float, bottom: Float): EffectRect {
        this.left = left
        this.top = top
        this.right = right
        this.bottom = bottom
        return this
    }
}

private fun cursorRect(
    column: Int,
    row: Int,
    topRow: Int,
    metrics: TerminalMetrics,
    cursorStyle: Int,
    output: EffectRect
): EffectRect {
    val left = column * metrics.cellWidthPx
    val top = (row - topRow) * metrics.cellHeightPx
    val right = left + metrics.cellWidthPx
    val bottom = top + metrics.cellHeightPx
    return when (cursorStyle) {
        TerminalCursor.STYLE_UNDERLINE ->
            output.set(left, bottom - metrics.cellHeightPx / 4f, right, bottom)
        TerminalCursor.STYLE_BAR ->
            output.set(left, top, left + metrics.cellWidthPx / 4f, bottom)
        else -> output.set(left, top, right, bottom)
    }
}

private fun lerpRect(
    start: EffectRect,
    end: EffectRect,
    fraction: Float,
    output: EffectRect
): EffectRect = output.set(
    lerp(start.left, end.left, fraction),
    lerp(start.top, end.top, fraction),
    lerp(start.right, end.right, fraction),
    lerp(start.bottom, end.bottom, fraction)
)

private fun lerp(start: Float, end: Float, fraction: Float): Float =
    start + (end - start) * fraction

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

private fun cursorTrailColor(frame: TerminalFrame): Int {
    val color = frame.palette.color(TerminalPalette.COLOR_INDEX_CURSOR)
    val alpha = (color ushr 24) and 0xFF
    val red = (color ushr 16) and 0xFF
    val green = (color ushr 8) and 0xFF
    val blue = color and 0xFF
    val luminance = (0.299f * red + 0.587f * green + 0.114f * blue) / 255f
    if (luminance >= 0.45f) return color
    return (((alpha + 255) / 2) shl 24) or
        (((red + 255) / 2) shl 16) or
        (((green + 255) / 2) shl 8) or
        ((blue + 255) / 2)
}
