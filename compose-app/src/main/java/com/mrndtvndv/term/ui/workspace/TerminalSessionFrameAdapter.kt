package com.mrndtvndv.term.ui.workspace

import com.termux.terminal.RenderFrameCache
import com.termux.terminal.ScreenSnapshot
import com.termux.terminal.TextStyle
import com.termux.terminal.TerminalSession
import com.termux.terminal.compose.TerminalCellLayout
import com.termux.terminal.compose.TerminalCursor
import com.termux.terminal.compose.TerminalFrame
import com.termux.terminal.compose.TerminalLinkLayout
import com.termux.terminal.compose.TerminalLinkSegment
import com.termux.terminal.compose.TerminalModes
import com.termux.terminal.compose.TerminalPalette
import com.termux.terminal.compose.TerminalRow
import com.termux.terminal.compose.TerminalViewport
import com.termux.view.TerminalViewLinkLayout

/** Converts the legacy UI cache into immutable frames owned by the compose library. */
internal class TerminalSessionFrameAdapter(
    private val session: TerminalSession,
    private val view: ComposeInputTerminalView
) {
    private val contentCache = TerminalFrameContentCache()
    private var previousSourceLinkLayout: TerminalViewLinkLayout? = null
    private var previousLinkLayout: TerminalLinkLayout? = null

    fun build(): TerminalFrame? {
        val renderCache: RenderFrameCache = view.getRenderFrameCache()
        val snapshot = renderCache.getSnapshotForRender(
            session.isGhosttyCursorBlinkingEnabled,
            session.getGhosttyCursorBlinkState()
        ) ?: return null

        val content = contentCache.update(snapshot)
        val sourceLinkLayout = view.getVisibleLinkLayout()
        val nextFrame = TerminalFrame(
            sequence = snapshot.frameSequence,
            viewport = TerminalViewport(
                topRow = snapshot.topRow,
                rows = snapshot.rows,
                columns = snapshot.columns,
                transcriptRows = session.getActiveTranscriptRows()
            ),
            cursor = TerminalCursor(
                column = snapshot.cursorCol,
                row = snapshot.cursorRow,
                visible = snapshot.isCursorVisible,
                style = snapshot.cursorStyle
            ),
            modes = TerminalModes(
                reverseVideo = snapshot.isReverseVideo,
                cursorKeysApplicationMode = session.isCursorKeysApplicationMode,
                keypadApplicationMode = session.isKeypadApplicationMode,
                mouseTrackingActive = session.isMouseTrackingActive,
                alternateBufferActive = session.isAlternateBufferActive
            ),
            palette = content.palette,
            rows = content.rows,
            linkLayout = toTerminalLinkLayout(snapshot, sourceLinkLayout)
        )
        return nextFrame
    }

    private fun toTerminalLinkLayout(
        snapshot: ScreenSnapshot,
        source: TerminalViewLinkLayout?
    ): TerminalLinkLayout? {
        if (source === previousSourceLinkLayout) return previousLinkLayout

        val nextLayout = snapshot.toTerminalLinkLayout(source)
        previousSourceLinkLayout = source
        previousLinkLayout = nextLayout
        return nextLayout
    }
}

internal data class TerminalFrameContent(
    val palette: TerminalPalette,
    val rows: List<TerminalRow>
)

/** Reuses immutable palette and row objects while applying a complete render-cache snapshot. */
internal class TerminalFrameContentCache {
    private var content: TerminalFrameContent? = null
    private var frameSequence = Long.MIN_VALUE
    private var paletteColors: IntArray? = null
    private var palette: TerminalPalette? = null
    private var paletteVersion = 0
    private var rows: List<TerminalRow> = emptyList()
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
        if (!dimensionsMatch || snapshot.isFullRebuild) {
            return List(snapshot.rows) { rowIndex ->
                snapshot.reusableTerminalRow(rowIndex, previousRows) ?: snapshot.toTerminalRow(rowIndex)
            }
        }

        val dirtyRows = BooleanArray(snapshot.rows)
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

private fun ScreenSnapshot.toTerminalLinkLayout(
    source: TerminalViewLinkLayout?
): TerminalLinkLayout? {
    if (source == null) return null

    val segmentsPerRow = List(rows) { rowIndex ->
        val absoluteRow = topRow + rowIndex
        val segments = mutableListOf<TerminalLinkSegment>()
        var column = 0
        while (column < columns) {
            val url = source.findAt(absoluteRow, column)?.url
            if (url == null) {
                column++
                continue
            }

            val startColumn = column
            do {
                column++
            } while (column < columns && source.findAt(absoluteRow, column)?.url == url)
            segments += TerminalLinkSegment(startColumn, column, url)
        }
        segments
    }
    return TerminalLinkLayout(
        frameSequence = source.frameSequence,
        topRow = topRow,
        rows = rows,
        columns = columns,
        segmentsPerRow = segmentsPerRow
    )
}
