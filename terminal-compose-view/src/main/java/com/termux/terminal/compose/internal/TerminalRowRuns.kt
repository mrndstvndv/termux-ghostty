package com.termux.terminal.compose.internal

/** Run-style overlay flags carried by a [RowRun]. */
internal const val RunFlagInsideCursor = 1
internal const val RunFlagInsideSelection = 2

/**
 * One style run of a rendered row: contiguous cells sharing a style and the
 * same cursor/selection overlay state.
 */
internal class RowRun(
    val startColumn: Int,
    val widthColumns: Int,
    val startCharIndex: Int,
    val widthChars: Int,
    val measuredWidth: Float,
    val style: Long,
    val flags: Int
) {
    val insideCursor: Boolean
        get() = (flags and RunFlagInsideCursor) != 0

    val insideSelection: Boolean
        get() = (flags and RunFlagInsideSelection) != 0
}

/** Per-row cache of built runs, reused across frames and shifted on scroll. */
internal class RowRunCache {
    var contentHash = Long.MIN_VALUE
    var selectionStart = Int.MIN_VALUE
    var selectionEnd = Int.MIN_VALUE
    var cursorX = Int.MIN_VALUE
    var hasCellLayout = false
    var runs: Array<RowRun>? = null

    fun matches(hints: RowRenderHints, hasCellLayout: Boolean, contentHash: Long): Boolean =
        selectionUnchanged(hints) && cursorUnchanged(hints, hasCellLayout) &&
            this.contentHash == contentHash

    fun selectionUnchanged(hints: RowRenderHints): Boolean =
        selectionStart == hints.selectionStart && selectionEnd == hints.selectionEnd

    fun cursorUnchanged(hints: RowRenderHints, hasCellLayout: Boolean): Boolean =
        cursorX == hints.cursorX && this.hasCellLayout == hasCellLayout

    fun beginBuild(hints: RowRenderHints, hasCellLayout: Boolean, contentHash: Long) {
        this.contentHash = contentHash
        selectionStart = hints.selectionStart
        selectionEnd = hints.selectionEnd
        cursorX = hints.cursorX
        this.hasCellLayout = hasCellLayout
        runs = null
    }

    fun addRun(
        startColumn: Int,
        widthColumns: Int,
        startCharIndex: Int,
        widthChars: Int,
        measuredWidth: Float,
        style: Long,
        flags: Int
    ) {
        val existing = runs
        val run = RowRun(startColumn, widthColumns, startCharIndex, widthChars, measuredWidth, style, flags)
        runs = if (existing == null) arrayOf(run) else existing + run
    }
}

internal fun flags(insideCursor: Boolean, insideSelection: Boolean): Int =
    (if (insideCursor) RunFlagInsideCursor else 0) or
        (if (insideSelection) RunFlagInsideSelection else 0)
