package com.termux.terminal.compose

import com.termux.terminal.WcWidth

/**
 * Immutable snapshot of the terminal state for one draw.
 *
 * A frame must remain valid for the duration of one draw. The backend must not
 * mutate a published frame; the canvas never calls back into the backend while
 * consuming one. Frames are monotonic in [sequence].
 */
class TerminalFrame(
    val sequence: Long,
    val viewport: TerminalViewport,
    val cursor: TerminalCursor,
    val modes: TerminalModes,
    val palette: TerminalPalette,
    val rows: List<TerminalRow>,
    val linkLayout: TerminalLinkLayout?
) {

    /** Absolute row of the top of the viewport (0 or negative). */
    val topRow: Int
        get() = viewport.topRow

    /** Number of rows visible in the viewport. */
    val rowsVisible: Int
        get() = viewport.rows

    /** Number of cells per row. */
    val columns: Int
        get() = viewport.columns

    val reverseVideo: Boolean
        get() = modes.reverseVideo

    val cursorKeysApplicationMode: Boolean
        get() = modes.cursorKeysApplicationMode

    val keypadApplicationMode: Boolean
        get() = modes.keypadApplicationMode

    val mouseTrackingActive: Boolean
        get() = modes.mouseTrackingActive

    val alternateBufferActive: Boolean
        get() = modes.alternateBufferActive

    /** Row at the given viewport index, or null when out of range. */
    fun row(index: Int): TerminalRow? = rows.getOrNull(index)

    /** Absolute row to viewport index, or -1 when outside the viewport. */
    fun viewportRow(absoluteRow: Int): Int = absoluteRow - viewport.topRow

    /**
     * Extracts the visible text of the viewport, one row per line. Continuation
     * rows (soft-wrapped lines) join without a newline.
     */
    fun visibleText(): String {
        val builder = StringBuilder()
        for (row in rows) {
            builder.append(row.textString())
            if (!row.isLineWrap) {
                builder.append('\n')
            }
        }
        return builder.toString()
    }

    /**
     * Extracts the text covered by [selection]. The selection is in absolute
     * rows; rows outside the viewport produce empty output for that range.
     */
    fun selectionText(selection: TerminalSelection): String {
        if (selection.isEmpty) return ""
        val builder = StringBuilder()
        val firstRow = selection.startRow.coerceAtLeast(viewport.topRow)
        val lastRow = selection.endRow.coerceAtMost(viewport.topRow + viewport.rows - 1)
        for (absoluteRow in firstRow..lastRow) {
            val row = rows.getOrNull(absoluteRow - viewport.topRow) ?: continue
            val startColumn = if (absoluteRow == selection.startRow) selection.startCol else 0
            val endColumn = if (absoluteRow == selection.endRow) selection.endCol else row.columns - 1
            builder.append(row.textBetweenColumns(startColumn, endColumn))
            if (!row.isLineWrap && absoluteRow != lastRow) {
                builder.append('\n')
            }
        }
        return builder.toString()
    }
}

/** Terminal mode flags for the current frame. */
data class TerminalModes(
    val reverseVideo: Boolean,
    val cursorKeysApplicationMode: Boolean,
    val keypadApplicationMode: Boolean,
    val mouseTrackingActive: Boolean,
    val alternateBufferActive: Boolean
)

/** Viewport geometry: which part of the terminal content is visible. */
data class TerminalViewport(
    val topRow: Int,
    val rows: Int,
    val columns: Int,
    val transcriptRows: Int
)

/** Cursor state for the current frame. */
data class TerminalCursor(
    val column: Int,
    val row: Int,
    val visible: Boolean,
    val style: Int
) {
    companion object {
        const val STYLE_BLOCK = 0
        const val STYLE_UNDERLINE = 1
        const val STYLE_BAR = 2
    }
}

/**
 * Terminal color palette. Indexes follow the terminal emulator's palette
 * convention (16 ANSI colors + bright variants and the cursor/foreground/
 * background reserved indexes). [version] increments whenever the colors
 * change so renderers can invalidate cached rows.
 */
class TerminalPalette private constructor(
    colors: IntArray,
    val version: Int,
    takeOwnership: Boolean
) {
    private val colors = if (takeOwnership) colors else colors.copyOf()

    fun color(index: Int): Int = colors.getOrElse(index) { colors.getOrElse(0) { 0xFF000000.toInt() } }

    internal fun copyInto(): IntArray = colors.copyOf()

    companion object {
        const val COLOR_INDEX_FOREGROUND = 256
        const val COLOR_INDEX_BACKGROUND = 257
        const val COLOR_INDEX_CURSOR = 258

        /** Creates a palette from a color array (index = palette index). */
        fun of(colors: IntArray): TerminalPalette = of(colors, 0)

        /** Creates a palette with an explicit version for cache invalidation. */
        fun of(colors: IntArray, version: Int): TerminalPalette =
            TerminalPalette(colors, version, takeOwnership = false)

        /** Uses a newly allocated array without copying it again. The caller must relinquish ownership. */
        fun takeOwnership(colors: IntArray, version: Int): TerminalPalette =
            TerminalPalette(colors, version, takeOwnership = true)
    }
}

/**
 * One viewport row of terminal content.
 *
 * [text] holds the characters of the row (charsUsed entries, possibly with
 * surrogate pairs and continuation characters for wide glyphs) and [styles]
 * holds the packed text style per cell column. When the terminal has a native
 * backing store, [cellLayout] maps each cell column to its character range and
 * display width; otherwise the layout is derived from [text] using character
 * width tables.
 */
private class TerminalRowArrays(
    val text: CharArray,
    val styles: LongArray
)

class TerminalRow private constructor(
    val columns: Int,
    arrays: TerminalRowArrays,
    val charsUsed: Int,
    val contentHash: Long,
    val cellLayout: TerminalCellLayout?,
    val isLineWrap: Boolean
) {
    private val text = arrays.text
    private val styles = arrays.styles

    constructor(
        columns: Int,
        text: CharArray,
        charsUsed: Int,
        styles: LongArray,
        contentHash: Long,
        cellLayout: TerminalCellLayout?,
        isLineWrap: Boolean
    ) : this(
        columns,
        TerminalRowArrays(text.copyOf(), styles.copyOf()),
        charsUsed,
        contentHash,
        cellLayout,
        isLineWrap
    )

    fun text(): CharArray = text

    fun style(column: Int): Long = styles.getOrElse(column) { 0L }

    /** Character range covering the given cell column, or null when blank. */
    fun cellTextRange(column: Int): IntRange? {
        val layout = cellLayout
        if (layout != null) {
            val start = layout.cellTextStart(column)
            val length = layout.cellTextLength(column)
            return if (start >= 0 && length > 0) start until (start + length) else null
        }
        return javaRangeForColumn(column)
    }

    /** Display width in cells of the given column (0 for continuation cells). */
    fun cellDisplayWidth(column: Int): Int {
        val layout = cellLayout
        if (layout != null) return layout.cellDisplayWidth(column)
        val range = javaRangeForColumn(column)
        val width = if (range != null && range.first >= 0 && range.first < charsUsed) {
            var measured = 0
            var index = range.first
            while (index < range.last) {
                measured += WcWidth.width(text, index)
                index += if (Character.isHighSurrogate(text[index])) 2 else 1
            }
            measured.coerceAtLeast(1)
        } else {
            1
        }
        return width
    }

    /** Plain text of the row, trailing blank cells stripped. */
    fun textString(): String {
        var end = charsUsed
        while (end > 0 && text[end - 1] == '\u0000') {
            end--
        }
        return String(text, 0, end)
    }

    /** Text between two cell columns (inclusive), for selection extraction. */
    fun textBetweenColumns(startColumn: Int, endColumn: Int): String {
        if (startColumn > endColumn) return ""
        val first = textRangeForColumn(startColumn)
        val last = textRangeForColumn(endColumn)
        if (first == null) return ""
        val end = last?.last?.let { (it + 1).coerceAtMost(charsUsed) } ?: charsUsed
        return String(text, first.first.coerceAtMost(end), (end - first.first).coerceAtLeast(0))
    }

    private fun textRangeForColumn(column: Int): IntRange? {
        if (column < 0) return null
        val layout = cellLayout
        if (layout != null) {
            val start = layout.cellTextStart(column)
            val length = layout.cellTextLength(column)
            return if (start >= 0 && length > 0) start until (start + length) else null
        }
        return javaRangeForColumn(column)
    }

    private fun javaRangeForColumn(column: Int): IntRange? {
        var result: IntRange? = null
        var charIndex = 0
        var cellColumn = 0
        while (charIndex < charsUsed && result == null) {
            val chars = if (Character.isHighSurrogate(text[charIndex])) 2 else 1
            val width = WcWidth.width(text, charIndex)
            if (cellColumn == column) {
                val rangeStart = charIndex
                result = when {
                    chars == 2 -> rangeStart until (charIndex + 2)
                    width <= 0 -> null
                    else -> rangeStart until (charIndex + 1)
                }
            }
            if (result == null) {
                cellColumn += width.coerceAtLeast(1)
                charIndex += chars
                charIndex = skipContinuationCharacters(charIndex)
            }
        }
        return result
    }

    private fun skipContinuationCharacters(startIndex: Int): Int {
        var index = startIndex
        while (index < charsUsed && WcWidth.width(text, index) <= 0) {
            index += if (Character.isHighSurrogate(text[index])) 2 else 1
        }
        return index
    }

    companion object {
        /** Uses newly allocated arrays without copying them again. The caller must relinquish ownership. */
        fun takeOwnership(
            columns: Int,
            text: CharArray,
            charsUsed: Int,
            styles: LongArray,
            contentHash: Long,
            cellLayout: TerminalCellLayout?,
            isLineWrap: Boolean
        ): TerminalRow = TerminalRow(
            columns,
            TerminalRowArrays(text, styles),
            charsUsed,
            contentHash,
            cellLayout,
            isLineWrap
        )
    }
}

/**
 * Native cell layout for a row: per-column character range and display width.
 * This mirrors the emulator's native backing store so the renderer can batch
 * glyph runs without re-deriving widths.
 */
class TerminalCellLayout private constructor(
    start: IntArray,
    length: IntArray,
    displayWidth: IntArray,
    takeOwnership: Boolean
) {
    private val start = if (takeOwnership) start else start.copyOf()
    private val length = if (takeOwnership) length else length.copyOf()
    private val displayWidth = if (takeOwnership) displayWidth else displayWidth.copyOf()

    constructor(
        start: IntArray,
        length: IntArray,
        displayWidth: IntArray
    ) : this(start, length, displayWidth, takeOwnership = false)

    fun cellTextStart(column: Int): Int = start.getOrElse(column) { -1 }

    fun cellTextLength(column: Int): Int = length.getOrElse(column) { -1 }

    fun cellDisplayWidth(column: Int): Int = displayWidth.getOrElse(column) { 0 }

    companion object {
        /** Uses newly allocated arrays without copying them again. The caller must relinquish ownership. */
        fun takeOwnership(
            start: IntArray,
            length: IntArray,
            displayWidth: IntArray
        ): TerminalCellLayout = TerminalCellLayout(start, length, displayWidth, takeOwnership = true)
    }
}

/** A hyperlink span in the terminal, in absolute row coordinates. */
data class TerminalLink(
    val row: Int,
    val startColumn: Int,
    val endColumnExclusive: Int,
    val url: String
)

/**
 * Per-row link segments for the visible viewport, used to draw synthetic
 * underlines and to hit-test taps. [frameSequence] must match the frame it was
 * built from.
 */
class TerminalLinkLayout(
    val frameSequence: Long,
    val topRow: Int,
    val rows: Int,
    val columns: Int,
    segmentsPerRow: List<List<TerminalLinkSegment>>
) {
    private val segmentsPerRow = segmentsPerRow.map { it.toTypedArray() }

    fun rowSegments(rowIndex: Int): Array<TerminalLinkSegment> =
        segmentsPerRow.getOrElse(rowIndex) { EMPTY_SEGMENTS }

    fun findAt(absoluteRow: Int, column: Int): TerminalLinkSegment? {
        val rowIndex = absoluteRow - topRow
        if (rowIndex < 0 || rowIndex >= segmentsPerRow.size) return null
        return segmentsPerRow[rowIndex].firstOrNull {
            column >= it.startColumn && column < it.endColumnExclusive
        }
    }

    private companion object {
        val EMPTY_SEGMENTS = arrayOf<TerminalLinkSegment>()
    }
}

/** One link span in a [TerminalLinkLayout]. */
data class TerminalLinkSegment(
    val startColumn: Int,
    val endColumnExclusive: Int,
    val url: String
)

/** Selection in absolute terminal coordinates. */
data class TerminalSelection(
    val startCol: Int,
    val startRow: Int,
    val endCol: Int,
    val endRow: Int
) {
    val isEmpty: Boolean
        get() = startRow < 0 || endRow < 0

    companion object {
        val EMPTY = TerminalSelection(-1, -1, -1, -1)
    }
}

/**
 * Selection plus the geometry the consumer needs to draw selection UI
 * (handles, toolbar) in viewport pixel space. Pixel coordinates follow
 * [TerminalMetrics]: a cell at absolute row `r` and column `c` starts at
 * `x = c * cellWidthPx`, `y = (r - topRow) * cellHeightPx`.
 */
data class TerminalSelectionInfo(
    val selection: TerminalSelection,
    val topRow: Int,
    val columns: Int,
    val transcriptRows: Int,
    val alternateBufferActive: Boolean,
    val cellWidthPx: Float,
    val cellHeightPx: Float
)
