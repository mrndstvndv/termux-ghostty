package com.termux.terminal.compose.session

import com.termux.terminal.RenderFrameCache
import com.termux.terminal.ScreenSnapshot
import com.termux.terminal.TextStyle
import com.termux.terminal.ViewportLinkSnapshot
import com.termux.terminal.compose.TerminalCellLayout
import com.termux.terminal.compose.TerminalCursor
import com.termux.terminal.compose.TerminalFrame
import com.termux.terminal.compose.TerminalImagePlacement
import com.termux.terminal.compose.TerminalLinkLayout
import com.termux.terminal.compose.TerminalLinkSegment
import com.termux.terminal.compose.TerminalModes
import com.termux.terminal.compose.TerminalPalette
import com.termux.terminal.compose.TerminalRow
import com.termux.terminal.compose.TerminalViewport
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Session values that are not encoded in the visible frame transport. */
data class TerminalFrameSessionState(
    val transcriptRows: Int,
    val cursorBlinkingEnabled: Boolean,
    val cursorBlinkState: Boolean
)

/** Applies session deltas and publishes immutable frames without a View intermediary. */
class TerminalSessionFrameStore {
    enum class ApplyResult {
        UPDATED,
        IGNORED,
        NEEDS_FULL_REFRESH
    }

    private val renderCache = RenderFrameCache()
    private val frameAdapter = TerminalSessionFrameAdapter()
    private var frame: TerminalFrame? = null

    fun apply(
        frameDelta: com.termux.terminal.FrameDelta,
        state: TerminalFrameSessionState
    ): ApplyResult = when (val result = renderCache.apply(frameDelta)) {
        RenderFrameCache.ApplyResult.APPLIED -> {
            val snapshot = renderCache.getSnapshotForRender(
                state.cursorBlinkingEnabled,
                state.cursorBlinkState
            ) ?: return ApplyResult.NEEDS_FULL_REFRESH
            frame = frameAdapter.build(snapshot, frameDelta.viewportLinkSnapshot, state)
            ApplyResult.UPDATED
        }
        RenderFrameCache.ApplyResult.IGNORED_OLDER_OR_DUPLICATE -> ApplyResult.IGNORED
        else -> if (result.requiresFullRefresh()) {
            ApplyResult.NEEDS_FULL_REFRESH
        } else {
            ApplyResult.IGNORED
        }
    }

    fun currentFrame(): TerminalFrame? = frame

    fun clear() {
        renderCache.reset()
        frame = null
    }
}

/** Converts a complete render-cache snapshot into a compose-owned immutable frame. */
class TerminalSessionFrameAdapter {
    private val contentCache = TerminalFrameContentCache()
    private val linkLayoutCache = TerminalFrameLinkLayoutCache()

    fun build(
        snapshot: ScreenSnapshot,
        viewportLinks: ViewportLinkSnapshot,
        state: TerminalFrameSessionState
    ): TerminalFrame {
        val content = contentCache.update(snapshot)
        return TerminalFrame(
            sequence = snapshot.frameSequence,
            viewport = TerminalViewport(
                topRow = snapshot.topRow,
                rows = snapshot.rows,
                columns = snapshot.columns,
                transcriptRows = state.transcriptRows
            ),
            cursor = TerminalCursor(
                column = snapshot.cursorCol,
                row = snapshot.cursorRow,
                visible = snapshot.isCursorVisible,
                style = snapshot.cursorStyle
            ),
            modes = TerminalModes(
                reverseVideo = snapshot.isReverseVideo,
                cursorKeysApplicationMode = snapshot.isCursorKeysApplicationMode,
                keypadApplicationMode = snapshot.isKeypadApplicationMode,
                mouseTrackingActive = snapshot.isMouseTrackingActive,
                alternateBufferActive = snapshot.isAlternateBufferActive
            ),
            palette = content.palette,
            rows = content.rows,
            linkLayout = linkLayoutCache.update(snapshot, viewportLinks, content),
            imagePlacements = snapshot.toImagePlacements()
        )
    }

    private fun ScreenSnapshot.toImagePlacements(): List<TerminalImagePlacement> {
        if (!hasImageUpdate() || imageCount == 0) return emptyList()
        val pixelData = imagePixelData
        return List(imageCount) { index ->
            val src = getImagePlacement(index)
            val buffer: ByteBuffer? = if (src.bufferLen > 0 && pixelData != null) {
                ByteBuffer.wrap(pixelData, src.bufferOffset, src.bufferLen).order(ByteOrder.nativeOrder())
            } else {
                null
            }
            TerminalImagePlacement(
                imageId = src.imageId.toLong() and 0xFFFFFFFFL,
                placementId = src.placementId.toLong() and 0xFFFFFFFFL,
                imageGeneration = src.imageGeneration,
                viewportCol = src.viewportCol,
                viewportRow = src.viewportRow,
                colSpan = src.colSpan,
                rowSpan = src.rowSpan,
                zIndex = src.zIndex,
                srcX = src.srcX,
                srcY = src.srcY,
                srcWidth = src.srcWidth,
                srcHeight = src.srcHeight,
                destWidthPx = src.destWidthPx,
                destHeightPx = src.destHeightPx,
                pixelBuffer = buffer,
                textureWidth = src.destWidthPx,
                textureHeight = src.destHeightPx
            )
        }
    }
}

/**
 * Retains parsed hyperlink geometry while visible terminal rows and OSC 8
 * metadata stay unchanged. Cursor blink and palette-only frames otherwise
 * rerun the regular expression over every visible cell and allocate a complete
 * link layout despite having no link work to publish.
 */
private class TerminalFrameLinkLayoutCache {
    private var previousRows: List<TerminalRow> = emptyList()
    private var layout: TerminalLinkLayout? = null
    private var osc8Links: List<Osc8Link> = emptyList()
    private val linkLayoutBuilder = TerminalFrameLinkLayoutBuilder()

    fun update(
        snapshot: ScreenSnapshot,
        viewportLinks: ViewportLinkSnapshot,
        content: TerminalFrameContent
    ): TerminalLinkLayout {
        val current = layout
        if (current != null && canReuse(current, snapshot, viewportLinks, content.rows)) {
            previousRows = content.rows
            return current.withFrameSequence(snapshot.frameSequence).also { layout = it }
        }

        return linkLayoutBuilder.build(snapshot, viewportLinks).also {
            previousRows = content.rows
            osc8Links = viewportLinks.toOsc8Links()
            layout = it
        }
    }

    private fun canReuse(
        layout: TerminalLinkLayout,
        snapshot: ScreenSnapshot,
        viewportLinks: ViewportLinkSnapshot,
        rows: List<TerminalRow>
    ): Boolean {
        val geometryMatches = layout.topRow == snapshot.topRow &&
            layout.rows == snapshot.rows &&
            layout.columns == snapshot.columns
        return geometryMatches && rowsAreIdentical(rows) && osc8LinksMatch(viewportLinks)
    }

    private fun rowsAreIdentical(rows: List<TerminalRow>): Boolean =
        rows.size == previousRows.size && rows.indices.all { rows[it] === previousRows[it] }

    private fun osc8LinksMatch(viewportLinks: ViewportLinkSnapshot): Boolean {
        if (viewportLinks.segmentCount != osc8Links.size) return false
        return osc8Links.indices.all { index ->
            val candidate = viewportLinks.getSegment(index)
            val previous = osc8Links[index]
            candidate.row == previous.row &&
                candidate.startColumn == previous.startColumn &&
                candidate.endColumnExclusive == previous.endColumnExclusive &&
                candidate.source == previous.source &&
                candidate.url == previous.url
        }
    }

    private fun ViewportLinkSnapshot.toOsc8Links(): List<Osc8Link> =
        List(segmentCount) { index ->
            getSegment(index).let { segment ->
                Osc8Link(
                    row = segment.row,
                    startColumn = segment.startColumn,
                    endColumnExclusive = segment.endColumnExclusive,
                    source = segment.source,
                    url = segment.url
                )
            }
        }

    private data class Osc8Link(
        val row: Int,
        val startColumn: Int,
        val endColumnExclusive: Int,
        val source: Int,
        val url: String
    )
}

data class TerminalFrameContent(
    val palette: TerminalPalette,
    val rows: List<TerminalRow>
)

/** Reuses immutable palette and row objects while applying a complete render-cache snapshot. */
class TerminalFrameContentCache {
    private var content: TerminalFrameContent? = null
    private var frameSequence = Long.MIN_VALUE
    private var paletteColors: IntArray? = null
    private var palette: TerminalPalette? = null
    private var paletteVersion = 0
    private var rows: List<TerminalRow> = emptyList()
    private var dirtyRows = BooleanArray(0)
    private var topRow = 0
    private var columns = -1

    fun update(snapshot: ScreenSnapshot): TerminalFrameContent {
        val current = content
        if (current != null && frameSequence == snapshot.frameSequence) return current

        val nextPalette = updatePalette(snapshot)
        val nextRows = updateRows(snapshot)
        rows = nextRows
        topRow = snapshot.topRow
        columns = snapshot.columns
        frameSequence = snapshot.frameSequence
        return TerminalFrameContent(nextPalette, nextRows).also { content = it }
    }

    private fun updatePalette(snapshot: ScreenSnapshot): TerminalPalette {
        val current = palette
        if (current != null && !snapshot.hasPaletteUpdate()) return current

        val colors = IntArray(TextStyle.NUM_INDEXED_COLORS) { index -> snapshot.getPaletteColor(index) }
        if (current != null && paletteColors?.contentEquals(colors) == true) return current

        paletteColors = colors
        paletteVersion++
        return TerminalPalette.takeOwnership(colors, paletteVersion).also { palette = it }
    }

    private fun updateRows(snapshot: ScreenSnapshot): List<TerminalRow> {
        val previousRows = rows
        val dimensionsMatch = previousRows.size == snapshot.rows && columns == snapshot.columns
        if (dimensionsMatch && !snapshot.isFullRebuild) {
            if (snapshot.topRow == topRow && snapshot.dirtyRowCount == 0) {
                return previousRows
            }
        } else {
            return List(snapshot.rows) { rowIndex ->
                snapshot.reusableTerminalRow(rowIndex, previousRows) ?: snapshot.toTerminalRow(rowIndex)
            }
        }

        if (dirtyRows.size < snapshot.rows) {
            dirtyRows = BooleanArray(snapshot.rows)
        }
        dirtyRows.fill(false, 0, snapshot.rows)
        repeat(snapshot.dirtyRowCount) { dirtyIndex ->
            dirtyRows[snapshot.getDirtyRow(dirtyIndex)] = true
        }
        return List(snapshot.rows) { rowIndex ->
            val previousIndex = snapshot.topRow + rowIndex - topRow
            val previousRow = previousRows.getOrNull(previousIndex)
            if (!dirtyRows[rowIndex] && previousRow?.matches(snapshot.getRow(rowIndex), snapshot.columns) == true) {
                previousRow
            } else {
                snapshot.reusableTerminalRow(rowIndex, previousRows) ?: snapshot.toTerminalRow(rowIndex)
            }
        }
    }
}

private fun ScreenSnapshot.reusableTerminalRow(
    rowIndex: Int,
    previousRows: List<TerminalRow>
): TerminalRow? {
    val source = getRow(rowIndex)
    val samePosition = previousRows.getOrNull(rowIndex)
    if (samePosition?.matches(source, columns) == true) return samePosition
    return previousRows.firstOrNull { it.matches(source, columns) }
}

private fun TerminalRow.matches(source: ScreenSnapshot.RowSnapshot, columns: Int): Boolean =
    this.columns == columns &&
        charsUsed == source.charsUsed &&
        contentHash == source.contentHash &&
        isLineWrap == source.isLineWrap &&
        (cellLayout != null) == source.hasCellLayout()

private fun ScreenSnapshot.toTerminalRow(rowIndex: Int): TerminalRow {
    val source = getRow(rowIndex)
    val columns = this.columns
    val text = source.text.copyOf()
    val styles = LongArray(columns) { column -> source.getStyle(column) }
    val cellLayout = if (source.hasCellLayout()) {
        TerminalCellLayout.takeOwnership(
            start = IntArray(columns) { column -> source.getCellTextStart(column) },
            length = IntArray(columns) { column -> source.getCellTextLength(column) },
            displayWidth = IntArray(columns) { column -> source.getCellDisplayWidth(column) }
        )
    } else {
        null
    }
    return TerminalRow.takeOwnership(
        columns = columns,
        text = text,
        charsUsed = source.charsUsed,
        styles = styles,
        contentHash = source.contentHash,
        cellLayout = cellLayout,
        isLineWrap = source.isLineWrap
    )
}

private class TerminalFrameLinkLayoutBuilder {
    private val urlPattern = Regex(
        pattern =
            """(?i)\b(?:(?:(?:dav|dict|dns|file|finger|ftp(?:s?)|git|gemini|gopher|http(?:s?)|""" +
                """imap(?:s?)|irc(?:[6s]?)|ip[fn]s|ldap(?:s?)|pop3(?:s?)|redis(?:s?)|rsync|""" +
                """rtsp(?:[su]?)|sftp|smb(?:s?)|smtp(?:s?)|ssh|svn(?:\+ssh)?|tcp|telnet|tftp|""" +
                """udp|vnc|ws(?:s?))://|(?:mailto|magnet|news|tel):)[^\s\u0000-\u001F<>"']+)"""
    )
    private var segments = emptyArray<MutableList<TerminalLinkSegment>>()
    private var claimedCells = BooleanArray(0)
    private val text = StringBuilder()
    private var spanRows = IntArray(0)
    private var spanStarts = IntArray(0)
    private var spanEnds = IntArray(0)
    private var spanTextStarts = IntArray(0)
    private var spanTextEnds = IntArray(0)
    private var spanCount = 0

    fun build(snapshot: ScreenSnapshot, viewportLinks: ViewportLinkSnapshot): TerminalLinkLayout {
        val cellCount = snapshot.rows * snapshot.columns
        ensureScratch(snapshot.rows, cellCount)
        addOsc8Segments(snapshot, viewportLinks, segments, claimedCells)
        addLiteralUrls(snapshot, segments, claimedCells)
        return TerminalLinkLayout(
            frameSequence = snapshot.frameSequence,
            topRow = snapshot.topRow,
            rows = snapshot.rows,
            columns = snapshot.columns,
            segmentsPerRow = segments.asList().subList(0, snapshot.rows)
        )
    }

    private fun ensureScratch(rows: Int, cellCount: Int) {
        if (segments.size < rows) {
            val previous = segments
            segments = Array(rows) { row ->
                if (row < previous.size) previous[row] else ArrayList()
            }
        }
        for (row in 0 until rows) segments[row].clear()
        if (claimedCells.size < cellCount) {
            claimedCells = BooleanArray(cellCount)
        } else {
            claimedCells.fill(false, 0, cellCount)
        }
    }

    private fun addOsc8Segments(
        snapshot: ScreenSnapshot,
        viewportLinks: ViewportLinkSnapshot,
        segments: Array<MutableList<TerminalLinkSegment>>,
        claimedCells: BooleanArray
    ) {
        if (!viewportLinks.isCompatibleWith(snapshot)) return
        repeat(viewportLinks.segmentCount) { index ->
            val source = viewportLinks.getSegment(index)
            if (source.url.isEmpty() || source.row !in 0 until snapshot.rows) return@repeat
            if (source.startColumn !in 0 until snapshot.columns) return@repeat
            if (source.endColumnExclusive !in (source.startColumn + 1)..snapshot.columns) return@repeat
            addSegment(
                row = source.row,
                segment = TerminalLinkSegment(source.startColumn, source.endColumnExclusive, source.url),
                columns = snapshot.columns,
                segments = segments,
                claimedCells = claimedCells
            )
        }
    }

    private fun addLiteralUrls(
        snapshot: ScreenSnapshot,
        segments: Array<MutableList<TerminalLinkSegment>>,
        claimedCells: BooleanArray
    ) {
        var firstRow = 0
        while (firstRow < snapshot.rows) {
            var lastRow = firstRow
            while (lastRow < snapshot.rows - 1 && snapshot.getRow(lastRow).isLineWrap) lastRow++
            addLogicalLineUrls(snapshot, firstRow, lastRow, segments, claimedCells)
            firstRow = lastRow + 1
        }
    }

    private fun addLogicalLineUrls(
        snapshot: ScreenSnapshot,
        firstRow: Int,
        lastRow: Int,
        segments: Array<MutableList<TerminalLinkSegment>>,
        claimedCells: BooleanArray
    ) {
        text.setLength(0)
        spanCount = 0
        for (row in firstRow..lastRow) {
            val source = snapshot.getRow(row)
            if (!source.hasCellLayout()) return
            var column = 0
            while (column < snapshot.columns) {
                val width = source.getCellDisplayWidth(column)
                if (width <= 0) {
                    column++
                    continue
                }
                val start = text.length
                val length = source.getCellTextLength(column)
                if (length > 0) {
                    text.append(source.text, source.getCellTextStart(column), length)
                } else {
                    repeat(width) { text.append(' ') }
                }
                appendSpan(row, column, column + width, start, text.length)
                column += width
            }
        }

        urlPattern.findAll(text).forEach { match ->
            val end = trimmedUrlEnd(text, match.range.first, match.range.last + 1)
            if (end > match.range.first) {
                addUrlMatch(
                    match.range.first,
                    end,
                    text.substring(match.range.first, end),
                    snapshot.columns,
                    segments,
                    claimedCells
                )
            }
        }
    }

    private fun appendSpan(row: Int, startColumn: Int, endColumn: Int, textStart: Int, textEnd: Int) {
        if (spanCount == spanRows.size) {
            val nextSize = if (spanRows.isEmpty()) 64 else spanRows.size * 2
            spanRows = spanRows.copyOf(nextSize)
            spanStarts = spanStarts.copyOf(nextSize)
            spanEnds = spanEnds.copyOf(nextSize)
            spanTextStarts = spanTextStarts.copyOf(nextSize)
            spanTextEnds = spanTextEnds.copyOf(nextSize)
        }
        spanRows[spanCount] = row
        spanStarts[spanCount] = startColumn
        spanEnds[spanCount] = endColumn
        spanTextStarts[spanCount] = textStart
        spanTextEnds[spanCount] = textEnd
        spanCount++
    }

    @Suppress("LongParameterList")
    private fun addUrlMatch(
        start: Int,
        end: Int,
        url: String,
        columns: Int,
        segments: Array<MutableList<TerminalLinkSegment>>,
        claimedCells: BooleanArray
    ) {
        val accumulator = UrlSegmentAccumulator(url, columns, segments, claimedCells)
        for (spanIndex in 0 until spanCount) {
            if (spanTextStarts[spanIndex] >= end) break
            if (spanTextEnds[spanIndex] > start) {
                accumulator.append(
                    row = spanRows[spanIndex],
                    startColumn = spanStarts[spanIndex],
                    endColumn = spanEnds[spanIndex]
                )
            }
        }
        accumulator.flush()
    }

    private fun addSegment(
        row: Int,
        segment: TerminalLinkSegment,
        columns: Int,
        segments: Array<MutableList<TerminalLinkSegment>>,
        claimedCells: BooleanArray
    ) {
        segments[row].add(segment)
        for (column in segment.startColumn until segment.endColumnExclusive) {
            claimedCells[(row * columns) + column] = true
        }
    }

    private fun trimmedUrlEnd(text: CharSequence, start: Int, end: Int): Int {
        var result = end
        while (result > start) {
            val trailing = text[result - 1]
            if (trailing in ".,:;!?\'\">" || hasUnmatchedClosingBracket(text, start, result, trailing)) {
                result--
            } else {
                break
            }
        }
        return result
    }

    private fun hasUnmatchedClosingBracket(text: CharSequence, start: Int, end: Int, closing: Char): Boolean {
        val opening = when (closing) {
            ')' -> '('
            ']' -> '['
            '}' -> '{'
            else -> return false
        }
        var balance = 0
        for (index in start until end) {
            if (text[index] == opening) balance++
            if (text[index] == closing) balance--
        }
        return balance < 0
    }

    private inner class UrlSegmentAccumulator(
        private val url: String,
        private val columns: Int,
        private val segments: Array<MutableList<TerminalLinkSegment>>,
        private val claimedCells: BooleanArray
    ) {
        private var activeRow = -1
        private var activeStart = -1
        private var activeEnd = -1

        fun append(row: Int, startColumn: Int, endColumn: Int) {
            for (column in startColumn until endColumn) appendCell(row, column)
        }

        fun flush() {
            if (activeRow < 0) return
            addSegment(
                activeRow,
                TerminalLinkSegment(activeStart, activeEnd, url),
                columns,
                segments,
                claimedCells
            )
            activeRow = -1
        }

        private fun appendCell(row: Int, column: Int) {
            when {
                claimedCells[(row * columns) + column] -> flush()
                activeRow == row && activeEnd == column -> activeEnd = column + 1
                else -> {
                    flush()
                    activeRow = row
                    activeStart = column
                    activeEnd = column + 1
                }
            }
        }
    }
}
