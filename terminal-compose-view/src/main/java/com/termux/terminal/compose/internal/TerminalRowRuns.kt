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
    var columns = -1
    var hasCellLayout = false
    var runs: Array<RowRun>? = null

    /** Growable scratch backing [runs]; the published build is a fixed-size copy. */
    private var runStore = emptyArray<RowRun>()
    private var runCount = 0

    fun matches(
        hints: RowRenderHints,
        columns: Int,
        hasCellLayout: Boolean,
        contentHash: Long
    ): Boolean =
        selectionUnchanged(hints) && cursorUnchanged(hints, hasCellLayout) &&
            this.columns == columns && this.contentHash == contentHash

    fun selectionUnchanged(hints: RowRenderHints): Boolean =
        selectionStart == hints.selectionStart && selectionEnd == hints.selectionEnd

    fun cursorUnchanged(hints: RowRenderHints, hasCellLayout: Boolean): Boolean =
        cursorX == hints.cursorX && this.hasCellLayout == hasCellLayout

    fun beginBuild(hints: RowRenderHints, columns: Int, hasCellLayout: Boolean, contentHash: Long) {
        this.contentHash = contentHash
        this.columns = columns
        selectionStart = hints.selectionStart
        selectionEnd = hints.selectionEnd
        cursorX = hints.cursorX
        this.hasCellLayout = hasCellLayout
        runs = null
        runCount = 0
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
        if (runCount == runStore.size) {
            runStore = java.util.Arrays.copyOf(
                runStore,
                if (runStore.isEmpty()) 4 else runStore.size * 2
            )
        }
        runStore[runCount++] =
            RowRun(startColumn, widthColumns, startCharIndex, widthChars, measuredWidth, style, flags)
    }

    /** Publishes the accumulated runs as this cache's immutable build. */
    fun finishBuild() {
        runs = java.util.Arrays.copyOf(runStore, runCount)
    }
}

internal fun flags(insideCursor: Boolean, insideSelection: Boolean): Int =
    (if (insideCursor) RunFlagInsideCursor else 0) or
        (if (insideSelection) RunFlagInsideSelection else 0)
