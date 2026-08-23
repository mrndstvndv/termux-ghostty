package com.termux.terminal.compose.gpu

import android.graphics.Paint
import com.termux.terminal.TextStyle
import com.termux.terminal.compose.TerminalCursor
import com.termux.terminal.compose.TerminalFrame
import com.termux.terminal.compose.TerminalLinkSegment
import com.termux.terminal.compose.TerminalMetrics
import com.termux.terminal.compose.TerminalPalette
import com.termux.terminal.compose.TerminalSelection
import com.termux.terminal.compose.internal.resolveEffectiveColors
import com.termux.terminal.compose.internal.resolveEffectiveForegroundColor
import kotlin.math.abs

internal data class TerminalQuad(
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float,
    val argb: Int
)

internal data class TerminalGlyphPlacement(
    val key: GlyphAtlasKey,
    val left: Float,
    val top: Float
)

internal class TerminalRenderPlan(
    val terminalBackgroundArgb: Int,
    val cellBackgrounds: List<TerminalQuad>,
    val cursorQuads: List<TerminalQuad>,
    val glyphs: List<TerminalGlyphPlacement>,
    val decorations: List<TerminalQuad>
)

/**
 * CPU-only conversion from an immutable frame to GLES draw packets.
 *
 * The planner owns no backend or Compose state. Its lists belong to one draw
 * and are never retained by the GL thread after the snapshot is replaced.
 */
internal class TerminalRenderPlanner(
    private val atlasPaddingPx: Int
) {
    private val measurePaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.SUBPIXEL_TEXT_FLAG)

    fun plan(snapshot: GlesTerminalSnapshot): TerminalRenderPlan {
        val frame = snapshot.frame
        val metrics = snapshot.metrics
        val terminalBackgroundArgb = frame.palette.color(
            if (frame.reverseVideo) {
                TerminalPalette.COLOR_INDEX_FOREGROUND
            } else {
                TerminalPalette.COLOR_INDEX_BACKGROUND
            }
        )
        val cellBackgrounds = ArrayList<TerminalQuad>()
        val cursorQuads = ArrayList<TerminalQuad>()
        val glyphs = ArrayList<TerminalGlyphPlacement>()
        val decorations = ArrayList<TerminalQuad>()

        for (rowIndex in 0 until frame.rowsVisible) {
            val row = frame.row(rowIndex) ?: continue
            planRow(
                frame = frame,
                snapshot = snapshot,
                rowIndex = rowIndex,
                row = row,
                terminalBackgroundArgb = terminalBackgroundArgb,
                cellBackgrounds = cellBackgrounds,
                cursorQuads = cursorQuads,
                glyphs = glyphs
            )
            planLinkDecorations(
                frame = frame,
                snapshot = snapshot,
                rowIndex = rowIndex,
                row = row,
                decorations = decorations
            )
        }

        return TerminalRenderPlan(
            terminalBackgroundArgb = terminalBackgroundArgb,
            cellBackgrounds = cellBackgrounds.toList(),
            cursorQuads = cursorQuads.toList(),
            glyphs = glyphs.toList(),
            decorations = decorations.toList()
        )
    }

    @Suppress("LongParameterList", "LongMethod")
    private fun planRow(
        frame: TerminalFrame,
        snapshot: GlesTerminalSnapshot,
        rowIndex: Int,
        row: com.termux.terminal.compose.TerminalRow,
        terminalBackgroundArgb: Int,
        cellBackgrounds: MutableList<TerminalQuad>,
        cursorQuads: MutableList<TerminalQuad>,
        glyphs: MutableList<TerminalGlyphPlacement>
    ) {
        val metrics = snapshot.metrics
        val absoluteRow = frame.topRow + rowIndex
        val rowTop = rowIndex * metrics.cellHeightPx
        val selection = selectionRange(snapshot.selection, absoluteRow, frame.columns)
        val cursor = frame.cursor
        val maxColumn = minOf(frame.columns, row.columns)
        var column = 0
        while (column < maxColumn) {
            val displayWidth = row.cellDisplayWidth(column)
            if (displayWidth <= 0) {
                column++
                continue
            }
            val cellEnd = minOf(maxColumn, column + displayWidth)
            val cellWidth = cellEnd - column
            val insideSelection = selection != null &&
                column <= selection.last && cellEnd - 1 >= selection.first
            val cursorInCell = cursor.visible && absoluteRow == cursor.row &&
                cursor.column in column until cellEnd
            val blockCursor = cursorInCell && cursor.style == TerminalCursor.STYLE_BLOCK
            val style = row.style(column)
            val packedColors = resolveEffectiveColors(
                frame.palette,
                style,
                frame.reverseVideo || insideSelection || blockCursor
            )
            val foregroundArgb = (packedColors ushr 32).toInt()
            val backgroundArgb = packedColors.toInt()
            val left = column * metrics.cellWidthPx
            val right = cellEnd * metrics.cellWidthPx
            if (backgroundArgb != terminalBackgroundArgb) {
                cellBackgrounds += TerminalQuad(left, rowTop, right, rowTop + metrics.cellHeightPx, backgroundArgb)
            }
            if (cursorInCell) {
                cursorQuads += cursorQuad(
                    cursor = cursor,
                    left = left,
                    right = right,
                    rowTop = rowTop,
                    cellHeight = metrics.cellHeightPx,
                    color = frame.palette.color(TerminalPalette.COLOR_INDEX_CURSOR)
                )
            }

            val effect = TextStyle.decodeEffect(style)
            val range = row.cellTextRange(column)
            if (range != null && effect and TextStyle.CHARACTER_ATTRIBUTE_INVISIBLE == 0) {
                val text = textForRange(row, range)
                if (text.isNotEmpty()) {
                    glyphs += TerminalGlyphPlacement(
                        key = GlyphAtlasKey(
                            text = text,
                            foregroundArgb = foregroundArgb,
                            typeface = snapshot.visual.typeface,
                            fontSizePx = snapshot.visual.fontSizePx,
                            cellWidthPx = metrics.cellWidthPx,
                            cellHeightPx = metrics.cellHeightPx,
                            fontAscentPx = -abs(metrics.fontAscentPx),
                            cellSpan = cellWidth,
                            textScaleX = textScaleX(
                                text = text,
                                style = style,
                                metrics = metrics,
                                visual = snapshot.visual,
                                targetWidth = cellWidth * metrics.cellWidthPx
                            ),
                            bold = effect and (
                                TextStyle.CHARACTER_ATTRIBUTE_BOLD or
                                    TextStyle.CHARACTER_ATTRIBUTE_BLINK
                                ) != 0,
                            italic = effect and TextStyle.CHARACTER_ATTRIBUTE_ITALIC != 0,
                            underline = effect and TextStyle.CHARACTER_ATTRIBUTE_UNDERLINE != 0,
                            strikeThrough = effect and TextStyle.CHARACTER_ATTRIBUTE_STRIKETHROUGH != 0
                        ),
                        left = left - atlasPaddingPx,
                        top = rowTop - atlasPaddingPx
                    )
                }
            }
            column = cellEnd
        }
    }

    private fun planLinkDecorations(
        frame: TerminalFrame,
        snapshot: GlesTerminalSnapshot,
        rowIndex: Int,
        row: com.termux.terminal.compose.TerminalRow,
        decorations: MutableList<TerminalQuad>
    ) {
        val links = frame.linkLayout ?: return
        if (links.frameSequence != frame.sequence) return
        val segments = links.rowSegments(rowIndex)
        if (segments.isEmpty()) return
        val metrics = snapshot.metrics
        val absoluteRow = frame.topRow + rowIndex
        val selection = selectionRange(snapshot.selection, absoluteRow, frame.columns)
        val cursor = frame.cursor
        val baseline = rowIndex * metrics.cellHeightPx + metrics.cellHeightPx
        val thickness = maxOf(1f, (snapshot.visual.fontSizePx / 14f).toInt().toFloat())
        val underlineBottom = baseline - maxOf(1f, thickness * 0.5f)
        val underlineTop = underlineBottom - thickness
        segments.forEach { segment ->
            planLinkSegment(
                frame = frame,
                row = row,
                segment = segment,
                rowIndex = rowIndex,
                selection = selection,
                cursor = cursor,
                metrics = metrics,
                underlineTop = underlineTop,
                underlineBottom = underlineBottom,
                decorations = decorations
            )
        }
    }

    @Suppress("LongParameterList")
    private fun planLinkSegment(
        frame: TerminalFrame,
        row: com.termux.terminal.compose.TerminalRow,
        segment: TerminalLinkSegment,
        rowIndex: Int,
        selection: IntRange?,
        cursor: com.termux.terminal.compose.TerminalCursor,
        metrics: TerminalMetrics,
        underlineTop: Float,
        underlineBottom: Float,
        decorations: MutableList<TerminalQuad>
    ) {
        var column = segment.startColumn.coerceAtLeast(0)
        val end = segment.endColumnExclusive.coerceAtMost(frame.columns)
        while (column < end) {
            val displayWidth = row.cellDisplayWidth(column)
            if (displayWidth <= 0) {
                column++
                continue
            }
            val cellEnd = minOf(end, column + displayWidth)
            val style = row.style(column)
            val effect = TextStyle.decodeEffect(style)
            if (effect and TextStyle.CHARACTER_ATTRIBUTE_UNDERLINE == 0) {
                val insideSelection = selection != null &&
                    column <= selection.last && cellEnd - 1 >= selection.first
                val cursorInCell = cursor.visible && frame.topRow + rowIndex == cursor.row &&
                    cursor.column in column until cellEnd
                val reverse = frame.reverseVideo || insideSelection ||
                    (cursorInCell && cursor.style == TerminalCursor.STYLE_BLOCK)
                val color = resolveEffectiveForegroundColor(frame.palette, style, reverse)
                decorations += TerminalQuad(
                    left = column * metrics.cellWidthPx,
                    top = underlineTop,
                    right = cellEnd * metrics.cellWidthPx,
                    bottom = underlineBottom,
                    argb = color
                )
            }
            column = cellEnd
        }
    }

    private fun textScaleX(
        text: String,
        style: Long,
        metrics: TerminalMetrics,
        visual: GlesTerminalVisualConfig,
        targetWidth: Float
    ): Float {
        val effect = TextStyle.decodeEffect(style)
        measurePaint.reset()
        measurePaint.flags = Paint.ANTI_ALIAS_FLAG or Paint.SUBPIXEL_TEXT_FLAG
        measurePaint.typeface = visual.typeface ?: android.graphics.Typeface.MONOSPACE
        measurePaint.textSize = visual.fontSizePx
        measurePaint.textScaleX = metrics.textScaleX
        measurePaint.isFakeBoldText = effect and (
            TextStyle.CHARACTER_ATTRIBUTE_BOLD or TextStyle.CHARACTER_ATTRIBUTE_BLINK
        ) != 0
        measurePaint.textSkewX = if (effect and TextStyle.CHARACTER_ATTRIBUTE_ITALIC != 0) -0.35f else 0f
        val measuredWidth = measurePaint.measureText(text)
        if (measuredWidth <= 0f) return metrics.textScaleX
        return (metrics.textScaleX * targetWidth / measuredWidth).coerceIn(0.05f, 20f)
    }

    private fun textForRange(
        row: com.termux.terminal.compose.TerminalRow,
        range: IntRange
    ): String {
        val start = range.first.coerceIn(0, row.charsUsed)
        val end = (range.last + 1).coerceIn(start, row.charsUsed)
        if (end <= start) return ""
        return String(row.text(), start, end - start)
    }

    @Suppress("ReturnCount")
    private fun selectionRange(
        selection: TerminalSelection,
        absoluteRow: Int,
        columns: Int
    ): IntRange? {
        if (selection.isEmpty) return null
        if (absoluteRow !in selection.startRow..selection.endRow) return null
        val first = if (absoluteRow == selection.startRow) selection.startCol else 0
        val last = if (absoluteRow == selection.endRow) selection.endCol else columns - 1
        if (last < first) return null
        return first.coerceAtLeast(0)..last.coerceAtMost(columns - 1)
    }

    private fun cursorQuad(
        cursor: com.termux.terminal.compose.TerminalCursor,
        left: Float,
        right: Float,
        rowTop: Float,
        cellHeight: Float,
        color: Int
    ): TerminalQuad = when (cursor.style) {
        TerminalCursor.STYLE_UNDERLINE -> TerminalQuad(
            left = left,
            top = rowTop + cellHeight * 0.75f,
            right = right,
            bottom = rowTop + cellHeight,
            argb = color
        )
        TerminalCursor.STYLE_BAR -> TerminalQuad(
            left = left,
            top = rowTop,
            right = left + (right - left) * 0.25f,
            bottom = rowTop + cellHeight,
            argb = color
        )
        else -> TerminalQuad(left, rowTop, right, rowTop + cellHeight, color)
    }
}
