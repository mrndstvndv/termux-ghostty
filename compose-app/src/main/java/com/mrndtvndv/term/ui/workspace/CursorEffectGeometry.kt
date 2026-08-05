package com.mrndtvndv.term.ui.workspace

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Path
import com.termux.terminal.compose.TerminalCursor
import com.termux.terminal.compose.TerminalMetrics
import kotlin.math.abs
import kotlin.math.sqrt

/** Connects two cell rectangles with a hull path that fills the space between them. */
internal fun trailPathBetweenRects(first: Rect, second: Rect): Path {
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

internal fun lerpRect(start: Rect, end: Rect, fraction: Float): Rect {
    fun lerpFloat(from: Float, to: Float): Float = from + (to - from) * fraction
    return Rect(
        left = lerpFloat(start.left, end.left),
        top = lerpFloat(start.top, end.top),
        right = lerpFloat(start.right, end.right),
        bottom = lerpFloat(start.bottom, end.bottom)
    )
}

internal fun cursorRectForCell(
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

internal fun easeOutCirc(value: Float): Float = sqrt(1f - (value - 1f) * (value - 1f))

internal fun smoothStep(edge0: Float, edge1: Float, value: Float): Float {
    if (edge1 <= edge0) return if (value < edge1) 0f else 1f
    val normalized = ((value - edge0) / (edge1 - edge0)).coerceIn(0f, 1f)
    return normalized * normalized * (3f - 2f * normalized)
}

internal fun easeOutCubic(value: Float): Float {
    val clamped = value.coerceIn(0f, 1f)
    val inverse = 1f - clamped
    return 1f - inverse * inverse * inverse
}
