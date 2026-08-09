package com.termux.terminal.compose.internal

import com.termux.terminal.compose.TerminalRow

/** Retained content and overlay identity for one rendered viewport row. */
internal class TerminalRowState {
    var row: TerminalRow? = null
    var contentHash = Long.MIN_VALUE
    var selectionStart = Int.MIN_VALUE
    var selectionEnd = Int.MIN_VALUE
    var cursorX = Int.MIN_VALUE
    var cursorStyle = Int.MIN_VALUE
    var reverseVideo = false
    var paletteVersion = Int.MIN_VALUE
    var linkContentHash = Long.MIN_VALUE

    fun applyFrame(row: TerminalRow, hints: RowRenderHints, paletteVersion: Int, linkContentHash: Long) {
        this.row = row
        contentHash = row.contentHash
        selectionStart = hints.selectionStart
        selectionEnd = hints.selectionEnd
        cursorX = hints.cursorX
        cursorStyle = hints.cursorStyle
        reverseVideo = hints.reverseVideo
        this.paletteVersion = paletteVersion
        this.linkContentHash = linkContentHash
    }

    fun clear() {
        row = null
        contentHash = Long.MIN_VALUE
        selectionStart = Int.MIN_VALUE
        selectionEnd = Int.MIN_VALUE
        cursorX = Int.MIN_VALUE
        cursorStyle = Int.MIN_VALUE
        reverseVideo = false
        paletteVersion = Int.MIN_VALUE
        linkContentHash = Long.MIN_VALUE
    }
}

/** True when the row's content or link spans changed. */
internal fun rowContentOutdated(rowState: TerminalRowState, row: TerminalRow, linkContentHash: Long): Boolean =
    rowState.row !== row || rowState.linkContentHash != linkContentHash

/** True when the row-local selection overlay changed. */
internal fun rowSelectionOutdated(rowState: TerminalRowState, selectionStart: Int, selectionEnd: Int): Boolean =
    rowState.selectionStart != selectionStart || rowState.selectionEnd != selectionEnd

/** True when the row-local cursor overlay changed. */
internal fun rowCursorOutdated(rowState: TerminalRowState, cursorX: Int, cursorStyle: Int): Boolean =
    rowState.cursorX != cursorX || rowState.cursorStyle != cursorStyle

/** True when the reverse-video flag or the palette changed. */
internal fun rowStyleOutdated(rowState: TerminalRowState, reverseVideo: Boolean, paletteVersion: Int): Boolean =
    rowState.reverseVideo != reverseVideo || rowState.paletteVersion != paletteVersion
