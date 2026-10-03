package com.mrndtvndv.term.ui.sftp

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import com.mikepenz.markdown.compose.LocalMarkdownColors
import com.mikepenz.markdown.compose.LocalMarkdownDimens
import com.mikepenz.markdown.compose.elements.MarkdownTableBasicText
import org.intellij.markdown.ast.ASTNode
import org.intellij.markdown.flavours.gfm.GFMElementTypes
import org.intellij.markdown.flavours.gfm.GFMTokenTypes

private val MaxColumnWidth = 240.dp
private val CellPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp)

@Composable
internal fun SftpMarkdownTable(content: String, node: ASTNode, style: TextStyle) {
    val rows = remember(node) {
        node.children
            .filter { it.type == GFMElementTypes.HEADER || it.type == GFMElementTypes.ROW }
            .map { row ->
                val cells = row.children.filter { it.type == GFMTokenTypes.CELL }
                (row.type == GFMElementTypes.HEADER) to cells
            }
    }
    val columns = rows.maxOfOrNull { it.second.size } ?: return
    val colors = LocalMarkdownColors.current
    val borderColor = colors.dividerColor

    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(LocalMarkdownDimens.current.tableCornerSize))
            .background(colors.tableBackground)
            .horizontalScroll(rememberScrollState()),
    ) {
        TableGrid(columns = columns) {
            rows.forEach { (isHeader, cells) ->
                repeat(columns) { column ->
                    TableCell(borderColor = borderColor) {
                        cells.getOrNull(column)?.let {
                            MarkdownTableBasicText(
                                content = content,
                                cell = it,
                                style = if (isHeader) style.copy(fontWeight = FontWeight.Bold) else style,
                                maxLines = Int.MAX_VALUE,
                                overflow = TextOverflow.Clip,
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun TableCell(borderColor: Color, content: @Composable () -> Unit) {
    Box(
        modifier = Modifier
            .drawBehind {
                val bottomRight = Offset(size.width, size.height)
                drawLine(borderColor, Offset(0f, size.height), bottomRight, strokeWidth = 1.dp.toPx())
            }
            .padding(CellPadding),
    ) { content() }
}

@Composable
private fun TableGrid(columns: Int, content: @Composable () -> Unit) {
    Layout(content = content) { measurables, _ ->
        val rows = measurables.chunked(columns)
        val maxColumnWidth = MaxColumnWidth.roundToPx()
        val columnWidths = IntArray(columns) { column ->
            rows.maxOf { it[column].maxIntrinsicWidth(Constraints.Infinity) }.coerceAtMost(maxColumnWidth)
        }
        val rowHeights = rows.map { row ->
            row.indices.maxOf { column -> row[column].minIntrinsicHeight(columnWidths[column]) }
        }
        val placeables = rows.mapIndexed { index, row ->
            row.mapIndexed { column, cell ->
                cell.measure(Constraints.fixed(columnWidths[column], rowHeights[index]))
            }
        }
        layout(columnWidths.sum(), rowHeights.sum()) {
            var y = 0
            placeables.forEachIndexed { index, row ->
                var x = 0
                row.forEachIndexed { column, placeable ->
                    placeable.placeRelative(x, y)
                    x += columnWidths[column]
                }
                y += rowHeights[index]
            }
        }
    }
}
