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
    private var paletteColors: IntArray? = null
    private var paletteVersion = 0
    private var previousFrame: TerminalFrame? = null
    private var previousSourceLinkLayout: TerminalViewLinkLayout? = null
    private var previousLinkLayout: TerminalLinkLayout? = null

    fun build(): TerminalFrame? {
        val renderCache: RenderFrameCache = view.getRenderFrameCache()
        val snapshot = renderCache.getSnapshotForRender(
            session.isGhosttyCursorBlinkingEnabled,
            session.getGhosttyCursorBlinkState()
        ) ?: return null

        val colors = IntArray(TextStyle.NUM_INDEXED_COLORS) { index -> snapshot.getPaletteColor(index) }
        if (paletteColors?.contentEquals(colors) != true) {
            paletteColors = colors
            paletteVersion++
        }

        val previousRows = previousFrame?.rows.orEmpty()
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
            palette = TerminalPalette.takeOwnership(colors, paletteVersion),
            rows = List(snapshot.rows) { rowIndex ->
                snapshot.reusableTerminalRow(rowIndex, previousRows) ?: snapshot.toTerminalRow(rowIndex)
            },
            linkLayout = toTerminalLinkLayout(snapshot, sourceLinkLayout)
        )
        previousFrame = nextFrame
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
