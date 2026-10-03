package com.mrndtvndv.term.ui.review

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp

private const val MaxLanes = 4
private val laneWidth = 14.dp
private val dotRadius = 4.dp
private val lineWidth = 1.5.dp

private val laneColors = listOf(
    Color(0xFF4FC3F7),
    Color(0xFFE3B341),
    Color(0xFF56D364),
    Color(0xFFD2A8FF),
    Color(0xFFF85149)
)

/** Lines drawn on one commit row; every value is a lane index. */
internal data class GraphRow(
    val column: Int,
    val passing: List<Int>,
    val merging: List<Int>,
    val branching: List<Int>
)

internal data class GraphLayout(val rows: List<GraphRow>, val laneCount: Int)

internal fun layoutGraph(commits: List<GitCommit>): GraphLayout {
    var lanes = emptyList<String?>()
    val rows = commits.map { commit ->
        val before = lanes
        val merging = before.indices.filter { before[it] == commit.hash }
        val column = merging.firstOrNull() ?: before.indexOf(null).takeIf { it >= 0 } ?: before.size

        val after = before.toMutableList()
        while (after.size <= column) after.add(null)
        merging.forEach { after[it] = null }

        val branching = commit.parents.mapIndexed { index, parent ->
            val lane = if (index == 0) column else after.indexOf(parent).takeIf { it >= 0 } ?: after.freeLane()
            after[lane] = parent
            lane
        }
        while (after.lastOrNull() == null && after.isNotEmpty()) after.removeLast()
        lanes = after

        val passing = before.indices.filter { before[it] != null && before[it] != commit.hash }
        GraphRow(column, passing, merging, branching.distinct())
    }
    val laneCount = rows.maxOfOrNull { row ->
        (row.passing + row.merging + row.branching + row.column).max() + 1
    } ?: 0
    return GraphLayout(rows, laneCount)
}

private fun MutableList<String?>.freeLane(): Int {
    val free = indexOf(null)
    if (free >= 0) return free
    add(null)
    return lastIndex
}

@Composable
internal fun CommitGraph(row: GraphRow, laneCount: Int, modifier: Modifier = Modifier) {
    val visibleLanes = minOf(laneCount, MaxLanes)
    Canvas(modifier.width(laneWidth * visibleLanes)) {
        val midY = size.height / 2
        fun x(lane: Int) = (minOf(lane, MaxLanes - 1) + 0.5f) * laneWidth.toPx()
        fun color(lane: Int) = laneColors[lane % laneColors.size]

        row.passing.forEach { drawEdge(x(it), 0f, x(it), size.height, color(it)) }
        row.merging.forEach { drawEdge(x(it), 0f, x(row.column), midY, color(it)) }
        row.branching.forEach { drawEdge(x(row.column), midY, x(it), size.height, color(it)) }
        drawCircle(color(row.column), dotRadius.toPx(), Offset(x(row.column), midY))
    }
}

private fun DrawScope.drawEdge(fromX: Float, fromY: Float, toX: Float, toY: Float, color: Color) {
    val midY = (fromY + toY) / 2
    val path = Path().apply {
        moveTo(fromX, fromY)
        cubicTo(fromX, midY, toX, midY, toX, toY)
    }
    drawPath(path, color, style = Stroke(lineWidth.toPx()))
}
