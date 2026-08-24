package com.termux.terminal.compose.gpu

import android.graphics.Paint
import com.termux.terminal.TextStyle
import com.termux.terminal.compose.TerminalCursor
import com.termux.terminal.compose.TerminalFrame
import com.termux.terminal.compose.TerminalLinkSegment
import com.termux.terminal.compose.TerminalMetrics
import com.termux.terminal.compose.TerminalPalette
import com.termux.terminal.compose.TerminalSelection
import com.termux.terminal.compose.internal.resolveEffectiveForegroundColor
import kotlin.math.abs

internal const val GlesStyleFlagOverlayReverse = 1

internal data class TerminalQuad(
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float,
    val argb: Int,
    val style: Long? = null,
    val styleFlags: Int = 0
)

internal data class TerminalGlyphPlacement(
    val key: GlyphAtlasKey,
    val left: Float,
    val top: Float,
    val style: Long = 0L,
    val styleFlags: Int = 0
)

internal class TerminalRenderPlan(
    val rows: List<TerminalRenderRowPlan?>
) {
    val cellBackgrounds: List<TerminalQuad> by lazy(LazyThreadSafetyMode.NONE) {
        rows.filterNotNull().flatMap { it.cellBackgrounds }
    }
    val cursorQuads: List<TerminalQuad> by lazy(LazyThreadSafetyMode.NONE) {
        rows.filterNotNull().flatMap { it.cursorQuads }
    }
    val glyphs: List<TerminalGlyphPlacement> by lazy(LazyThreadSafetyMode.NONE) {
        rows.filterNotNull().flatMap { it.glyphs }
    }
    val decorations: List<TerminalQuad> by lazy(LazyThreadSafetyMode.NONE) {
        rows.filterNotNull().flatMap { it.decorations }
    }
}

internal class TerminalRenderRowPlan(
    val rowIndex: Int,
    val cellBackgrounds: List<TerminalQuad>,
    val cursorQuads: List<TerminalQuad>,
    val glyphs: List<TerminalGlyphPlacement>,
    val decorations: List<TerminalQuad>
)

/**
 * CPU-only conversion from an immutable frame to GLES draw packets.
 *
 * The planner owns no backend or Compose state. It retains only bounded,
 * immutable row packets for the visible rows; each plan belongs to one draw.
 */
internal class TerminalRenderPlanner {
    private val measurePaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.SUBPIXEL_TEXT_FLAG)
    private var cachedSnapshot: GlesTerminalSnapshot? = null
    private var cachedPlan: TerminalRenderPlan? = null
    private var rowPlans = arrayOfNulls<CachedRowPlan>(0)

    fun plan(snapshot: GlesTerminalSnapshot): TerminalRenderPlan {
        val previousSnapshot = cachedSnapshot
        if (snapshot === previousSnapshot ||
            (previousSnapshot != null && canReuseWholePlan(previousSnapshot, snapshot))
        ) {
            cachedSnapshot = snapshot
            return cachedPlan ?: error("cached GLES plan is missing")
        }

        val frame = snapshot.frame
        ensureRowPlanCapacity(frame.rowsVisible)
        return TerminalRenderPlan(planRows(snapshot)).also { nextPlan ->
            cachedSnapshot = snapshot
            cachedPlan = nextPlan
        }
    }

    private fun planRows(snapshot: GlesTerminalSnapshot): List<TerminalRenderRowPlan?> {
        val frame = snapshot.frame
        val plannedRows = arrayOfNulls<TerminalRenderRowPlan>(frame.rowsVisible)
        for (rowIndex in 0 until frame.rowsVisible) {
            val row = frame.row(rowIndex) ?: continue
            plannedRows[rowIndex] = planRowFor(snapshot, rowIndex, row)
        }
        return plannedRows.toList()
    }

    private fun planRowFor(
        snapshot: GlesTerminalSnapshot,
        rowIndex: Int,
        row: com.termux.terminal.compose.TerminalRow
    ): TerminalRenderRowPlan {
        val frame = snapshot.frame
        val absoluteRow = frame.topRow + rowIndex
        val selection = selectionRange(snapshot.selection, absoluteRow, frame.columns)
        val selectionStart = selection?.first ?: -1
        val selectionEnd = selection?.last ?: -1
        val linkSegments = frame.linkLayout
            ?.takeIf { it.frameSequence == frame.sequence }
            ?.rowSegments(rowIndex)
            ?.takeIf { it.isNotEmpty() }
        val cached = rowPlans[rowIndex]
        if (cached != null && cached.matches(
                snapshot = snapshot,
                row = row,
                rowIndex = rowIndex,
                absoluteRow = absoluteRow,
                selectionStart = selectionStart,
                selectionEnd = selectionEnd,
                linkSegments = linkSegments
            )
        ) {
            return cached.plan
        }

        val nextPlan = planRow(
            frame = frame,
            snapshot = snapshot,
            rowIndex = rowIndex,
            row = row,
            selectionStart = selectionStart,
            selectionEnd = selectionEnd,
            linkSegments = linkSegments
        )
        rowPlans[rowIndex] = CachedRowPlan(
            row = row,
            rowIndex = rowIndex,
            absoluteRow = absoluteRow,
            selectionStart = selectionStart,
            selectionEnd = selectionEnd,
            cursor = frame.cursor,
            reverseVideo = frame.reverseVideo,
            palette = frame.palette,
            columns = frame.columns,
            metrics = snapshot.metrics,
            typeface = snapshot.visual.typeface,
            fontSizePx = snapshot.visual.fontSizePx,
            linkSegments = linkSegments,
            plan = nextPlan
        )
        return nextPlan
    }

    private fun canReuseWholePlan(
        previous: GlesTerminalSnapshot,
        next: GlesTerminalSnapshot
    ): Boolean = previous.frame === next.frame &&
        previous.metrics === next.metrics &&
        previous.selection == next.selection &&
        previous.viewportWidthPx == next.viewportWidthPx &&
        previous.viewportHeightPx == next.viewportHeightPx &&
        previous.visual.typeface == next.visual.typeface &&
        previous.visual.fontSizePx == next.visual.fontSizePx

    private fun ensureRowPlanCapacity(requiredRows: Int) {
        if (requiredRows <= rowPlans.size) return
        rowPlans = rowPlans.copyOf(requiredRows)
    }

    @Suppress("LongParameterList")
    private class CachedRowPlan(
        private val row: com.termux.terminal.compose.TerminalRow,
        private val rowIndex: Int,
        private val absoluteRow: Int,
        private val selectionStart: Int,
        private val selectionEnd: Int,
        private val cursor: TerminalCursor,
        private val reverseVideo: Boolean,
        private val palette: TerminalPalette,
        private val columns: Int,
        private val metrics: TerminalMetrics,
        private val typeface: android.graphics.Typeface?,
        private val fontSizePx: Float,
        private val linkSegments: Array<TerminalLinkSegment>?,
        val plan: TerminalRenderRowPlan
    ) {
        @Suppress("LongParameterList")
        fun matches(
            snapshot: GlesTerminalSnapshot,
            row: com.termux.terminal.compose.TerminalRow,
            rowIndex: Int,
            absoluteRow: Int,
            selectionStart: Int,
            selectionEnd: Int,
            linkSegments: Array<TerminalLinkSegment>?
        ): Boolean {
            val frame = snapshot.frame
            val paletteMatches = this.palette === frame.palette ||
                (plan.cursorQuads.isEmpty() &&
                    plan.glyphs.none { it.key.rasterMode == GlyphAtlasKey.RASTER_MODE_RGBA })
            return this.row === row &&
                this.rowIndex == rowIndex &&
                this.absoluteRow == absoluteRow &&
                this.selectionStart == selectionStart &&
                this.selectionEnd == selectionEnd &&
                this.cursor == frame.cursor &&
                this.reverseVideo == frame.reverseVideo &&
                paletteMatches &&
                this.columns == frame.columns &&
                this.metrics === snapshot.metrics &&
                this.typeface == snapshot.visual.typeface &&
                this.fontSizePx == snapshot.visual.fontSizePx &&
                this.linkSegments === linkSegments
        }
    }

    @Suppress(
        "LongParameterList",
        "LongMethod",
        "CyclomaticComplexMethod",
        "NestedBlockDepth"
    )
    private fun planRow(
        frame: TerminalFrame,
        snapshot: GlesTerminalSnapshot,
        rowIndex: Int,
        row: com.termux.terminal.compose.TerminalRow,
        selectionStart: Int,
        selectionEnd: Int,
        linkSegments: Array<TerminalLinkSegment>?
    ): TerminalRenderRowPlan {
        val metrics = snapshot.metrics
        val absoluteRow = frame.topRow + rowIndex
        val rowTop = rowIndex * metrics.cellHeightPx
        val selection = if (selectionStart >= 0 && selectionEnd >= selectionStart) {
            selectionStart..selectionEnd
        } else {
            null
        }
        val cursor = frame.cursor
        val cellBackgrounds = ArrayList<TerminalQuad>()
        val cursorQuads = ArrayList<TerminalQuad>()
        val glyphs = ArrayList<TerminalGlyphPlacement>()
        val decorations = ArrayList<TerminalQuad>()
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
            val effect = TextStyle.decodeEffect(style)
            val styleFlags = if (insideSelection || blockCursor) {
                GlesStyleFlagOverlayReverse
            } else {
                0
            }
            val left = column * metrics.cellWidthPx
            val right = cellEnd * metrics.cellWidthPx
            val backgroundNeedsDraw = frame.reverseVideo ||
                styleFlags != 0 ||
                effect and TextStyle.CHARACTER_ATTRIBUTE_INVERSE != 0 ||
                TextStyle.decodeBackColor(style) != TextStyle.COLOR_INDEX_BACKGROUND
            if (backgroundNeedsDraw) {
                cellBackgrounds += TerminalQuad(
                    left = left,
                    top = rowTop,
                    right = right,
                    bottom = rowTop + metrics.cellHeightPx,
                    argb = 0,
                    style = style,
                    styleFlags = styleFlags
                )
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

            val range = row.cellTextRange(column)
            if (range != null && effect and TextStyle.CHARACTER_ATTRIBUTE_INVISIBLE == 0) {
                val text = textForRange(row, range)
                if (text.isNotEmpty()) {
                    val rasterMode = rasterModeForText(text)
                    val foregroundArgb = if (rasterMode == GlyphAtlasKey.RASTER_MODE_RGBA) {
                        resolveEffectiveForegroundColor(
                            frame.palette,
                            style,
                            frame.reverseVideo || insideSelection || blockCursor
                        )
                    } else {
                        0xFFFFFFFF.toInt()
                    }
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
                            strikeThrough = effect and TextStyle.CHARACTER_ATTRIBUTE_STRIKETHROUGH != 0,
                            rasterMode = rasterMode
                        ),
                        left = left,
                        top = rowTop,
                        style = style,
                        styleFlags = styleFlags
                    )
                }
            }
            column = cellEnd
        }
        planLinkDecorations(
            frame = frame,
            snapshot = snapshot,
            rowIndex = rowIndex,
            row = row,
            selection = selection,
            cursor = cursor,
            linkSegments = linkSegments,
            decorations = decorations
        )
        return TerminalRenderRowPlan(
            rowIndex = rowIndex,
            cellBackgrounds = cellBackgrounds.toList(),
            cursorQuads = cursorQuads.toList(),
            glyphs = glyphs.toList(),
            decorations = decorations.toList()
        )
    }

    @Suppress("LongParameterList")
    private fun planLinkDecorations(
        frame: TerminalFrame,
        snapshot: GlesTerminalSnapshot,
        rowIndex: Int,
        row: com.termux.terminal.compose.TerminalRow,
        selection: IntRange?,
        cursor: TerminalCursor,
        linkSegments: Array<TerminalLinkSegment>?,
        decorations: MutableList<TerminalQuad>
    ) {
        val segments = linkSegments ?: return
        val metrics = snapshot.metrics
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
                val overlayReverse = insideSelection ||
                    (cursorInCell && cursor.style == TerminalCursor.STYLE_BLOCK)
                decorations += TerminalQuad(
                    left = column * metrics.cellWidthPx,
                    top = underlineTop,
                    right = cellEnd * metrics.cellWidthPx,
                    bottom = underlineBottom,
                    argb = 0,
                    style = style,
                    styleFlags = if (overlayReverse) GlesStyleFlagOverlayReverse else 0
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
        measurePaint.textSkewX = if (effect and TextStyle.CHARACTER_ATTRIBUTE_ITALIC != 0) {
            GlesItalicTextSkewX
        } else {
            0f
        }
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

    private fun rasterModeForText(text: String): Int {
        var index = 0
        while (index < text.length) {
            val codePoint = Character.codePointAt(text, index)
            if (isPotentialColorCodePoint(codePoint)) return GlyphAtlasKey.RASTER_MODE_RGBA
            index += Character.charCount(codePoint)
        }
        return GlyphAtlasKey.RASTER_MODE_MASK
    }

    private fun isPotentialColorCodePoint(codePoint: Int): Boolean = when {
        codePoint in 0x1F000..0x1FAFF -> true
        codePoint in 0x2300..0x23FF -> true
        codePoint in 0x2600..0x27BF -> true
        codePoint == 0xFE0F -> true
        else -> false
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
