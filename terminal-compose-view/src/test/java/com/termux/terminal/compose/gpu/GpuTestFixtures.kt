package com.termux.terminal.compose.gpu

import com.termux.terminal.TextStyle
import com.termux.terminal.compose.TerminalCursor
import com.termux.terminal.compose.TerminalFrame
import com.termux.terminal.compose.TerminalMetrics
import com.termux.terminal.compose.TerminalModes
import com.termux.terminal.compose.TerminalPalette
import com.termux.terminal.compose.TerminalRow
import com.termux.terminal.compose.TerminalViewport

internal fun testSnapshot(sequence: Long): GlesTerminalSnapshot {
    val frame = testFrame(sequence)
    val metrics = TerminalMetrics.of(
        cellWidthPx = 10f,
        cellHeightPx = 20f,
        ascentPx = -15f,
        lineSpacingAndAscentPx = 5f,
        viewportWidthPx = 30,
        viewportHeightPx = 20
    )
    return GlesTerminalSnapshot(frame = frame, metrics = metrics)
}

internal fun testFrame(sequence: Long): TerminalFrame {
    val styles = longArrayOf(
        indexedStyle(1, 2),
        indexedStyle(3, 0),
        indexedStyle(3, 0)
    )
    val row = TerminalRow(
        columns = 3,
        text = "A🧠".toCharArray(),
        charsUsed = 3,
        styles = styles,
        contentHash = sequence,
        cellLayout = com.termux.terminal.compose.TerminalCellLayout(
            start = intArrayOf(0, 1, 1),
            length = intArrayOf(1, 2, 0),
            displayWidth = intArrayOf(1, 2, 0)
        ),
        isLineWrap = false
    )
    return TerminalFrame(
        sequence = sequence,
        viewport = TerminalViewport(0, 1, 3, 1),
        cursor = TerminalCursor(2, 0, true, TerminalCursor.STYLE_BLOCK),
        modes = TerminalModes(false, false, false, false, false),
        palette = testPalette(),
        rows = listOf(row),
        linkLayout = null
    )
}

internal fun indexedStyle(foreground: Int, background: Int, effect: Int = 0): Long =
    (foreground.toLong() shl 40) or
        (background.toLong() shl 16) or
        effect.toLong()

internal fun testPalette(): TerminalPalette = TerminalPalette.of(
    IntArray(TextStyle.NUM_INDEXED_COLORS) { index ->
        when (index) {
            0 -> 0xFF101010.toInt()
            1 -> 0xFFFF0000.toInt()
            2 -> 0xFF0000FF.toInt()
            3 -> 0xFF00FF00.toInt()
            TerminalPalette.COLOR_INDEX_FOREGROUND -> 0xFFFFFFFF.toInt()
            TerminalPalette.COLOR_INDEX_BACKGROUND -> 0xFF000000.toInt()
            TerminalPalette.COLOR_INDEX_CURSOR -> 0xFFFFFF00.toInt()
            else -> 0xFF202020.toInt()
        }
    }
)

internal fun atlasKey(text: String): GlyphAtlasKey = GlyphAtlasKey(
    text = text,
    foregroundArgb = 0xFFFFFFFF.toInt(),
    typeface = null,
    fontSizePx = 14f,
    cellWidthPx = 8f,
    cellHeightPx = 16f,
    fontAscentPx = -12f,
    cellSpan = 1,
    textScaleX = 1f,
    bold = false,
    italic = false,
    underline = false,
    strikeThrough = false
)
