package com.termux.terminal.compose.internal

import androidx.compose.ui.graphics.layer.GraphicsLayer
import com.termux.terminal.compose.TerminalRow

/** Retained state of one rendered row layer: content plus overlay bookkeeping. */
internal class TerminalRowState(
    val layer: GraphicsLayer
) {
    var contentHash = Long.MIN_VALUE
    var selectionStart = Int.MIN_VALUE
    var selectionEnd = Int.MIN_VALUE
    var cursorX = Int.MIN_VALUE
    var cursorStyle = Int.MIN_VALUE
    var reverseVideo = false
    var paletteVersion = Int.MIN_VALUE
    var linkContentHash = Long.MIN_VALUE

    fun applyFrame(row: TerminalRow, hints: RowRenderHints, paletteVersion: Int, linkContentHash: Long) {
        contentHash = row.contentHash
        selectionStart = hints.selectionStart
        selectionEnd = hints.selectionEnd
        cursorX = hints.cursorX
        cursorStyle = hints.cursorStyle
        reverseVideo = hints.reverseVideo
        this.paletteVersion = paletteVersion
        this.linkContentHash = linkContentHash
    }
}

/** True when the row's content or link spans changed. */
internal fun rowContentOutdated(rowState: TerminalRowState, row: TerminalRow, linkContentHash: Long): Boolean =
    rowState.contentHash != row.contentHash || rowState.linkContentHash != linkContentHash

/** True when the row-local selection overlay changed. */
internal fun rowSelectionOutdated(rowState: TerminalRowState, hints: RowRenderHints): Boolean =
    rowState.selectionStart != hints.selectionStart || rowState.selectionEnd != hints.selectionEnd

/** True when the row-local cursor overlay changed. */
internal fun rowCursorOutdated(rowState: TerminalRowState, hints: RowRenderHints): Boolean =
    rowState.cursorX != hints.cursorX || rowState.cursorStyle != hints.cursorStyle

/** True when the reverse-video flag or the palette changed. */
internal fun rowStyleOutdated(rowState: TerminalRowState, hints: RowRenderHints, paletteVersion: Int): Boolean =
    rowState.reverseVideo != hints.reverseVideo || rowState.paletteVersion != paletteVersion
