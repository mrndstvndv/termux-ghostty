package com.termux.terminal.compose.internal

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface
import com.termux.terminal.TextStyle
import com.termux.terminal.WcWidth
import com.termux.terminal.compose.TerminalCellLayout
import com.termux.terminal.compose.TerminalFontMetrics
import com.termux.terminal.compose.TerminalFrame
import com.termux.terminal.compose.TerminalPalette
import com.termux.terminal.compose.TerminalRow
import kotlin.math.abs

/** Row-local overlay and selection hints used while building and drawing runs. */
internal data class RowRenderHints(
    val selectionStart: Int,
    val selectionEnd: Int,
    val cursorX: Int,
    val cursorStyle: Int,
    val reverseVideo: Boolean
)

/**
 * Draws [TerminalRow] content with platform text primitives.
 *
 * This is a library-owned port of the emulator's row renderer: it builds
 * style runs per row, caches them per row, and draws text with Paint. It
 * consumes only library-owned frame types and never touches session or view
 * classes.
 */
internal class TerminalRowRenderer(
    typeface: Typeface?,
    fontSizePx: Float
) {
    val measuredCellWidthPx: Float
    val cellWidthPx: Float
    val textScaleX: Float
    val lineSpacingPx: Int
    val lineSpacingAndAscentPx: Int
    val ascentPx: Int

    private val fontMetrics = TerminalFontMetrics.from(typeface, fontSizePx)
    private val textPaint = Paint()
    private val linkUnderlinePainter: TerminalLinkUnderlinePainter
    private var rowRunCaches = arrayOfNulls<RowRunCache>(0)
    private var preparedSequence = Long.MIN_VALUE
    private var preparedTopRow = 0
    private var preparedRows = -1
    private var preparedColumns = -1

    init {
        fontMetrics.configurePaint(textPaint)
        measuredCellWidthPx = fontMetrics.measuredCellWidthPx
        cellWidthPx = fontMetrics.cellWidthPx
        textScaleX = fontMetrics.textScaleX
        lineSpacingPx = fontMetrics.lineSpacingPx
        ascentPx = fontMetrics.ascentPx
        lineSpacingAndAscentPx = fontMetrics.lineSpacingAndAscentPx
        linkUnderlinePainter = TerminalLinkUnderlinePainter(textPaint, fontSizePx, cellWidthPx)
    }

    /** Prepares row caches for a frame; shifts caches on viewport scroll. */
    fun prepare(frame: TerminalFrame) {
        val rows = frame.rowsVisible
        if (rowRunCaches.size != rows) {
            rowRunCaches = arrayOfNulls(rows)
            preparedSequence = Long.MIN_VALUE
            preparedTopRow = frame.topRow
            preparedRows = rows
            preparedColumns = frame.columns
            return
        }
        val sequence = frame.sequence
        if (sequence == preparedSequence) return
        if (preparedSequence != Long.MIN_VALUE) {
            val topRowDelta = frame.topRow - preparedTopRow
            if (abs(topRowDelta) < rows && topRowDelta != 0) {
                shiftRowCaches(topRowDelta, rows)
            }
        }
        preparedSequence = sequence
        preparedTopRow = frame.topRow
        preparedRows = rows
        preparedColumns = frame.columns
    }

    fun invalidate() {
        preparedSequence = Long.MIN_VALUE
        rowRunCaches.forEach { it?.runs = null }
    }

    /**
     * Draws one viewport row into [canvas] at the row-local baseline
     * [baselineY]. The render-node path passes [lineSpacingPx] (matching the
     * emulator's single-row `renderRow` convention); the bitmap path passes
     * the full-loop convention `lineSpacingAndAscent + lineSpacing * (k + 1)`.
     */
    fun renderRow(
        canvas: Canvas,
        frame: TerminalFrame,
        rowIndex: Int,
        hints: RowRenderHints,
        baselineY: Float = lineSpacingPx.toFloat()
    ) {
        prepare(frame)
        val row = frame.row(rowIndex) ?: return
        val cache = obtainRowRunCache(row, rowIndex, frame.columns, hints)
        renderCachedRow(canvas, frame, row, cache, hints, baselineY)

        val linkLayout = frame.linkLayout ?: return
        if (linkLayout.frameSequence != frame.sequence) return
        linkUnderlinePainter.drawUnderlines(
            canvas, frame, row, linkLayout.rowSegments(rowIndex), hints, baselineY
        )
    }

    /**
     * Draws every viewport row into [canvas] (used by the animated shader
     * bitmap path). Matches the emulator's snapshot render: background fill
     * first, then each row with its cursor and selection overlays.
     */
    fun renderAll(
        canvas: Canvas,
        frame: TerminalFrame,
        selectionStartRow: Int,
        selectionEndRow: Int,
        selectionStartCol: Int,
        selectionEndCol: Int,
        reverseVideo: Boolean
    ) {
        prepare(frame)
        canvas.drawColor(
            frame.palette.color(
                if (reverseVideo) TerminalPalette.COLOR_INDEX_FOREGROUND
                else TerminalPalette.COLOR_INDEX_BACKGROUND
            )
        )
        val cursorVisible = frame.cursor.visible
        val cursorRow = frame.cursor.row
        val cursorCol = frame.cursor.column
        val cursorStyle = frame.cursor.style
        for (rowIndex in 0 until frame.rowsVisible) {
            val absoluteRow = frame.topRow + rowIndex
            val selectionStart = if (absoluteRow == selectionStartRow) selectionStartCol else -1
            val selectionEnd = when {
                absoluteRow < selectionStartRow || absoluteRow > selectionEndRow -> -1
                absoluteRow == selectionEndRow -> selectionEndCol
                else -> frame.columns
            }
            val rowCursorX = if (cursorVisible && absoluteRow == cursorRow) cursorCol else -1
            val baselineY = (lineSpacingAndAscentPx + lineSpacingPx * (rowIndex + 1)).toFloat()
            renderRow(
                canvas = canvas,
                frame = frame,
                rowIndex = rowIndex,
                hints = RowRenderHints(selectionStart, selectionEnd, rowCursorX, cursorStyle, reverseVideo),
                baselineY = baselineY
            )
        }
    }

    private fun obtainRowRunCache(
        row: TerminalRow,
        rowIndex: Int,
        columns: Int,
        hints: RowRenderHints
    ): RowRunCache {
        val contentHash = row.contentHash
        val hasCellLayout = row.cellLayout != null
        var cache = rowRunCaches[rowIndex]
        if (cache != null && cache.matches(hints, hasCellLayout, contentHash)) {
            return cache
        }
        val matchIndex = findMatchingCache(hints, hasCellLayout, contentHash, rowIndex)
        if (matchIndex != -1) {
            val matching = rowRunCaches[matchIndex]!!
            rowRunCaches[matchIndex] = cache
            rowRunCaches[rowIndex] = matching
            return matching
        }
        if (cache == null) {
            cache = RowRunCache()
            rowRunCaches[rowIndex] = cache
        }
        cache.beginBuild(hints, hasCellLayout, contentHash)
        if (hasCellLayout) {
            buildNativeRuns(cache, row, columns, hints)
        } else {
            buildJavaRuns(cache, row, columns, hints)
        }
        return cache
    }

    private fun findMatchingCache(
        hints: RowRenderHints,
        hasCellLayout: Boolean,
        contentHash: Long,
        excludedIndex: Int
    ): Int {
        for (index in rowRunCaches.indices) {
            val candidate = if (index == excludedIndex) null else rowRunCaches[index]
            if (candidate != null && candidate.matches(hints, hasCellLayout, contentHash)) {
                return index
            }
        }
        return -1
    }

    private fun buildJavaRuns(
        cache: RowRunCache,
        row: TerminalRow,
        columns: Int,
        hints: RowRenderHints
    ) {
        val line = row.text()
        val charsUsed = row.charsUsed
        val rowColumns = row.columns
        if (columns <= 0) return
        if (charsUsed <= 0 || rowColumns <= 0) {
            appendBlankRuns(cache, row, rowColumns, 0, columns, hints)
            return
        }

        val accumulator = JavaRunAccumulator(cache, hints)
        var column = 0
        var charIndex = 0
        while (column < columns) {
            if (charIndex >= charsUsed) {
                accumulator.flush(column, charIndex)
                appendBlankRuns(cache, row, rowColumns, column, columns, hints)
                column = columns
                continue
            }
            val cell = javaCellAt(line, row, rowColumns, column, charIndex)
            if (accumulator.lastStartColumn == -1 || accumulator.runPropertiesChanged(cell)) {
                accumulator.flush(column, charIndex)
                accumulator.beginRun(cell, charIndex)
            }
            column += cell.width
            charIndex += codePointChars(line, charIndex)
            charIndex = skipContinuationCharacters(line, charIndex, charsUsed)
        }
        accumulator.flush(columns, charIndex)
    }

    private fun buildNativeRuns(
        cache: RowRunCache,
        row: TerminalRow,
        columns: Int,
        hints: RowRenderHints
    ) {
        val layout = row.cellLayout!!
        val charsUsed = row.charsUsed
        val accumulator = NativeRunAccumulator(cache, hints)
        var column = 0
        while (column < columns) {
            val cell = nativeCellAt(layout, row, column, charsUsed)
            if (cell != null) {
                if (accumulator.lastStartColumn == -1 || accumulator.runPropertiesChanged(cell)) {
                    accumulator.flush(column)
                    accumulator.beginRun(cell)
                }
                accumulator.add(cell)
                column += cell.cellDisplayWidth
            } else {
                column++
            }
        }
        accumulator.flush(columns)
    }

    private fun appendBlankRuns(
        cache: RowRunCache,
        row: TerminalRow,
        rowColumns: Int,
        startColumn: Int,
        endColumnExclusive: Int,
        hints: RowRenderHints
    ) {
        if (startColumn >= endColumnExclusive) return

        var lastStyle = 0L
        var lastInsideCursor = false
        var lastInsideSelection = false
        var lastStartColumn = -1

        var column = startColumn
        while (column < endColumnExclusive) {
            val style = if (column < rowColumns) row.style(column) else 0L
            val insideCursor = hints.cursorX == column
            val insideSelection = column >= hints.selectionStart && column <= hints.selectionEnd
            val splitRun = lastStartColumn == -1 ||
                style != lastStyle ||
                insideCursor != lastInsideCursor ||
                insideSelection != lastInsideSelection
            if (splitRun) {
                if (lastStartColumn != -1) {
                    cache.addRun(
                        lastStartColumn, column - lastStartColumn, 0, 0, lastStyle,
                        flags(lastInsideCursor, lastInsideSelection)
                    )
                }
                lastStartColumn = column
                lastStyle = style
                lastInsideCursor = insideCursor
                lastInsideSelection = insideSelection
            }
            column++
        }

        if (lastStartColumn != -1) {
            cache.addRun(
                lastStartColumn, endColumnExclusive - lastStartColumn, 0, 0, lastStyle,
                flags(lastInsideCursor, lastInsideSelection)
            )
        }
    }

    private fun renderCachedRow(
        canvas: Canvas,
        frame: TerminalFrame,
        row: TerminalRow,
        cache: RowRunCache,
        hints: RowRenderHints,
        baselineY: Float
    ) {
        val runs = cache.runs ?: return
        if (runs.isEmpty()) return
        val line = row.text()
        for (run in runs) {
            drawTextRun(canvas, line, frame, run, hints, baselineY)
        }
    }

    private fun drawTextRun(
        canvas: Canvas,
        line: CharArray,
        frame: TerminalFrame,
        run: RowRun,
        hints: RowRenderHints,
        baselineY: Float
    ) {
        val effect = TextStyle.decodeEffect(run.style)
        val bold = (effect and (TextStyle.CHARACTER_ATTRIBUTE_BOLD or TextStyle.CHARACTER_ATTRIBUTE_BLINK)) != 0
        val underline = (effect and TextStyle.CHARACTER_ATTRIBUTE_UNDERLINE) != 0
        val italic = (effect and TextStyle.CHARACTER_ATTRIBUTE_ITALIC) != 0
        val strikeThrough = (effect and TextStyle.CHARACTER_ATTRIBUTE_STRIKETHROUGH) != 0

        val hasText = run.widthChars > 0
        val left = run.startColumn * cellWidthPx
        val right = left + run.widthColumns * cellWidthPx

        val reverseVideo = hints.reverseVideo || invertOnCursorOrSelection(run, hints)
        val packedColors = resolveEffectiveColors(frame.palette, run.style, reverseVideo)
        val backColor = packedColors.toInt()
        if (backColor != frame.palette.color(TerminalPalette.COLOR_INDEX_BACKGROUND)) {
            textPaint.color = backColor
            canvas.drawRect(
                left, baselineY - lineSpacingAndAscentPx + ascentPx.toFloat(), right, baselineY,
                textPaint
            )
        }

        drawCursorBlock(canvas, frame, hints, run, left, right, baselineY)

        if (hasText && (effect and TextStyle.CHARACTER_ATTRIBUTE_INVISIBLE) == 0) {
            textPaint.isFakeBoldText = bold
            textPaint.isUnderlineText = underline
            textPaint.textSkewX = if (italic) -0.35f else 0f
            textPaint.isStrikeThruText = strikeThrough
            textPaint.color = (packedColors ushr 32).toInt()
            canvas.drawTextRun(
                line, run.startCharIndex, run.widthChars,
                run.startCharIndex, run.widthChars,
                left, baselineY - lineSpacingAndAscentPx,
                false, textPaint
            )
        }

    }

    private fun drawCursorBlock(
        canvas: Canvas,
        frame: TerminalFrame,
        hints: RowRenderHints,
        run: RowRun,
        left: Float,
        right: Float,
        baselineY: Float
    ) {
        if (!run.insideCursor) return
        textPaint.color = frame.palette.color(TerminalPalette.COLOR_INDEX_CURSOR)
        var cursorHeight = (lineSpacingAndAscentPx - ascentPx).toFloat()
        var cursorRight = right
        if (hints.cursorStyle == 1) {
            cursorHeight /= 4f
        } else if (hints.cursorStyle == 2) {
            cursorRight -= ((cursorRight - left) * 3) / 4f
        }
        canvas.drawRect(left, baselineY - cursorHeight, cursorRight, baselineY, textPaint)
    }

    private fun javaCellAt(
        line: CharArray,
        row: TerminalRow,
        rowColumns: Int,
        column: Int,
        charIndex: Int
    ): JavaRunCell {
        val charAtIndex = line[charIndex]
        val charsForCodePoint = codePointChars(line, charIndex)
        val codePoint =
            if (charsForCodePoint == 2) Character.toCodePoint(charAtIndex, line[charIndex + 1])
            else charAtIndex.code
        val width = WcWidth.width(codePoint)
        val style = if (column < rowColumns) row.style(column) else 0L
        return JavaRunCell(column, width, style)
    }

    private fun nativeCellAt(
        layout: TerminalCellLayout,
        row: TerminalRow,
        column: Int,
        charsUsed: Int
    ): NativeRunCell? {
        val cellTextStart = layout.cellTextStart(column)
        val cellTextLength = layout.cellTextLength(column)
        val cellDisplayWidth = layout.cellDisplayWidth(column)
        if (!isValidCell(cellTextStart, cellTextLength, cellDisplayWidth, charsUsed)) return null
        return NativeRunCell(
            column, cellTextStart, cellTextLength, cellDisplayWidth, row.style(column)
        )
    }

    private fun shiftRowCaches(topRowDelta: Int, rows: Int) {
        if (topRowDelta > 0) {
            val retainedRows = rows - topRowDelta
            if (retainedRows > 0) {
                System.arraycopy(rowRunCaches, topRowDelta, rowRunCaches, 0, retainedRows)
            }
            for (row in retainedRows until rows) {
                rowRunCaches[row] = null
            }
            return
        }
        val shift = -topRowDelta
        val retainedRows = rows - shift
        if (retainedRows > 0) {
            System.arraycopy(rowRunCaches, 0, rowRunCaches, shift, retainedRows)
        }
        for (row in 0 until shift) {
            rowRunCaches[row] = null
        }
    }
}

/** True when a cell layout entry covers a valid text range. */
private fun isValidCell(
    textStart: Int,
    textLength: Int,
    displayWidth: Int,
    charsUsed: Int
): Boolean =
    displayWidth > 0 && textStart >= 0 && textLength >= 0 && textStart + textLength <= charsUsed

/** True when the run must be inverted for a block cursor or the selection. */
private fun invertOnCursorOrSelection(run: RowRun, hints: RowRenderHints): Boolean =
    (run.insideCursor && hints.cursorStyle == 0) || run.insideSelection

private fun skipContinuationCharacters(line: CharArray, startIndex: Int, charsUsed: Int): Int {
    var index = startIndex
    while (index < charsUsed && WcWidth.width(line, index) <= 0) {
        index += if (Character.isHighSurrogate(line[index])) 2 else 1
    }
    return index
}

/** One character-level cell of a Java-rendered row while building runs. */
private data class JavaRunCell(
    val column: Int,
    val width: Int,
    val style: Long
)

/** Builds [RowRun]s for a Java-rendered row, tracking the trailing run state. */
private class JavaRunAccumulator(
    private val cache: RowRunCache,
    private val hints: RowRenderHints
) {
    var lastStyle = 0L
    var lastInsideCursor = false
    var lastInsideSelection = false
    var lastStartColumn = -1
    var lastStartIndex = 0

    fun runPropertiesChanged(cell: JavaRunCell): Boolean =
        cell.style != lastStyle ||
            insideCursor(cell) != lastInsideCursor ||
            insideSelection(cell) != lastInsideSelection

    fun beginRun(cell: JavaRunCell, charIndex: Int) {
        lastStyle = cell.style
        lastInsideCursor = insideCursor(cell)
        lastInsideSelection = insideSelection(cell)
        lastStartColumn = cell.column
        lastStartIndex = charIndex
    }

    fun flush(endColumn: Int, endCharIndex: Int) {
        if (lastStartColumn != -1) {
            cache.addRun(
                lastStartColumn,
                endColumn - lastStartColumn,
                lastStartIndex,
                endCharIndex - lastStartIndex,
                lastStyle,
                flags(lastInsideCursor, lastInsideSelection)
            )
        }
    }

    private fun insideCursor(cell: JavaRunCell): Boolean =
        hints.cursorX == cell.column || (cell.width == 2 && hints.cursorX == cell.column + 1)

    private fun insideSelection(cell: JavaRunCell): Boolean =
        cell.column >= hints.selectionStart && cell.column <= hints.selectionEnd
}

/** One cell of a native-rendered row while building runs. */
private data class NativeRunCell(
    val column: Int,
    val cellTextStart: Int,
    val cellTextLength: Int,
    val cellDisplayWidth: Int,
    val style: Long
) {
    val hasText: Boolean
        get() = cellTextLength > 0
}

/** Builds [RowRun]s for a native-rendered row, tracking the trailing run state. */
private class NativeRunAccumulator(
    private val cache: RowRunCache,
    private val hints: RowRenderHints
) {
    var lastStyle = 0L
    var lastInsideCursor = false
    var lastInsideSelection = false
    var lastHasText = false
    var lastStartColumn = -1
    var lastStartIndex = 0
    var lastEndIndex = 0

    fun runPropertiesChanged(cell: NativeRunCell): Boolean =
        cell.style != lastStyle ||
            insideCursor(cell) != lastInsideCursor ||
            insideSelection(cell) != lastInsideSelection ||
            cell.hasText != lastHasText ||
            (cell.hasText && lastHasText && cell.cellTextStart != lastEndIndex)

    fun beginRun(cell: NativeRunCell) {
        lastStyle = cell.style
        lastInsideCursor = insideCursor(cell)
        lastInsideSelection = insideSelection(cell)
        lastHasText = cell.hasText
        lastStartColumn = cell.column
        lastStartIndex = cell.cellTextStart
        lastEndIndex = cell.cellTextStart
    }

    fun add(cell: NativeRunCell) {
        if (cell.hasText) {
            lastEndIndex = cell.cellTextStart + cell.cellTextLength
        }
    }

    fun flush(endColumn: Int) {
        if (lastStartColumn != -1) {
            cache.addRun(
                lastStartColumn,
                endColumn - lastStartColumn,
                lastStartIndex,
                lastEndIndex - lastStartIndex,
                lastStyle,
                flags(lastInsideCursor, lastInsideSelection)
            )
        }
    }

    private fun insideCursor(cell: NativeRunCell): Boolean =
        hints.cursorX == cell.column ||
            (cell.cellDisplayWidth == 2 && hints.cursorX == cell.column + 1)

    private fun insideSelection(cell: NativeRunCell): Boolean =
        cell.column <= hints.selectionEnd &&
            (cell.column + cell.cellDisplayWidth - 1) >= hints.selectionStart
}

private fun codePointChars(line: CharArray, index: Int): Int =
    if (Character.isHighSurrogate(line[index])) 2 else 1
