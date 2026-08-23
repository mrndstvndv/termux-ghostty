package com.mrndtvndv.term.gpu

import com.termux.terminal.TextStyle
import com.termux.terminal.compose.TerminalCellLayout
import com.termux.terminal.compose.TerminalCursor
import com.termux.terminal.compose.TerminalFrame
import com.termux.terminal.compose.TerminalLinkLayout
import com.termux.terminal.compose.TerminalLinkSegment
import com.termux.terminal.compose.TerminalModes
import com.termux.terminal.compose.TerminalPalette
import com.termux.terminal.compose.TerminalRow
import com.termux.terminal.compose.TerminalSize
import com.termux.terminal.compose.TerminalSelection
import com.termux.terminal.compose.TerminalViewport

/** Immutable input supplied to one deterministic scene render. */
internal data class GpuLabSceneContext(
    val sceneId: String,
    val sceneIndex: Int,
    val size: TerminalSize,
    val frameIndex: Int,
    val topRow: Int,
    val sequence: Long
) {
    val columns: Int get() = size.columns
    val rows: Int get() = size.rows
}

/** One deterministic frame recipe. */
internal class GpuLabScene(
    val id: String,
    val title: String,
    val expectedInvariants: List<String>,
    val transcriptRows: Int = 0,
    val scrollsViewport: Boolean = false,
    val render: (GpuLabSceneContext) -> GpuLabFrameContent
)

/** Complete immutable data needed to construct a [TerminalFrame]. */
internal data class GpuLabFrameContent(
    val palette: TerminalPalette,
    val rows: List<TerminalRow>,
    val cursor: TerminalCursor,
    val modes: TerminalModes,
    val linkRows: List<List<TerminalLinkSegment>>,
    val selection: TerminalSelection = TerminalSelection.EMPTY
)

/** Cell input used only while constructing an immutable [TerminalRow]. */
private data class GpuLabCell(
    val text: String,
    val width: Int = 1,
    val style: Long = GpuLabStyles.NORMAL
) {
    init {
        require(width > 0) { "A fake-terminal cell must have a positive display width" }
    }
}

internal object GpuLabStyles {
    val NORMAL: Long =
        (TextStyle.COLOR_INDEX_FOREGROUND.toLong() shl 40) or
            (TextStyle.COLOR_INDEX_BACKGROUND.toLong() shl 16)

    fun indexed(
        foreground: Int = TextStyle.COLOR_INDEX_FOREGROUND,
        background: Int = TextStyle.COLOR_INDEX_BACKGROUND,
        effects: Int = 0
    ): Long =
        (effects.toLong() and 0x7ffL) or
            ((foreground.toLong() and 0x1ffL) shl 40) or
            ((background.toLong() and 0x1ffL) shl 16)

    fun trueColor(foreground: Int, background: Int, effects: Int = 0): Long =
        (effects.toLong() and 0x7ffL) or
            (1L shl 9) or
            (1L shl 10) or
            ((foreground.toLong() and 0xffffffL) shl 40) or
            ((background.toLong() and 0xffffffL) shl 16)
}

/** Fixed scene matrix for the debug-only Ecto renderer laboratory. */
internal object GpuLabScenes {
    val all: List<GpuLabScene> = listOf(
        GpuLabScene(
            id = "colors-grid",
            title = "Colors and one-cell grid",
            expectedInvariants = listOf(
                "solid sRGB primary, secondary, and gray swatches preserve channel order",
                "sRGB red/green/blue channels and ANSI palette indexes stay in order",
                "checkerboard cells have no seams, clipping, or origin flip",
                "background rectangles cover the complete viewport"
            ),
            render = ::renderColorsGrid
        ),
        GpuLabScene(
            id = "ascii-shell",
            title = "ASCII shell and tmux",
            expectedInvariants = listOf(
                "80x24-style shell rows remain complete and deterministic",
                "box-drawing separators meet at adjacent-cell boundaries",
                "row placement uses the top-left terminal origin",
                "wrapped and non-wrapped rows retain their explicit line-wrap flag"
            ),
            render = ::renderAsciiShell
        ),
        GpuLabScene(
            id = "ansi-styles",
            title = "ANSI colors and styles",
            expectedInvariants = listOf(
                "normal and bright ANSI colors preserve palette indexes",
                "bold, faint, italic, inverse, invisible, strike, and underline are distinct",
                "overline is represented by a visible sentinel because TerminalFrame has no overline bit"
            ),
            render = ::renderAnsiStyles
        ),
        GpuLabScene(
            id = "unicode-wide-emoji",
            title = "Unicode, wide cells, and emoji",
            expectedInvariants = listOf(
                "surrogate pairs occupy one logical glyph and do not split",
                "CJK and emoji wide cells consume two columns with one continuation cell",
                "combining marks, variation selectors, ZWJ sequences, and fallback text stay grouped"
            ),
            render = ::renderUnicode
        ),
        GpuLabScene(
            id = "cursor-selection-links",
            title = "Cursor, selection, and links",
            expectedInvariants = listOf(
                "block, underline, and bar cursors move through first, middle, and last columns",
                "selection backgrounds cover one cell, a wide cell, and multiple rows",
                "OSC 8-style and literal URL link segments have stable hit targets"
            ),
            render = ::renderCursorSelectionLinks
        ),
        GpuLabScene(
            id = "sparse-update",
            title = "Sparse one-cell update",
            expectedInvariants = listOf(
                "only the progress row changes between steps",
                "unchanged row content hashes remain stable",
                "frame publication is complete even when damage is sparse"
            ),
            render = ::renderSparseUpdate
        ),
        GpuLabScene(
            id = "full-update",
            title = "Full-screen update",
            expectedInvariants = listOf(
                "every visible row changes on each step",
                "a single sequence presents one complete frame",
                "no partially uploaded or mixed-revision rows are accepted"
            ),
            render = ::renderFullUpdate
        ),
        GpuLabScene(
            id = "scrollback",
            title = "Scrollback and deterministic scroll",
            expectedInvariants = listOf(
                "absolute row identity advances monotonically through bounded transcript data",
                "viewport topRow and visible rows agree after every step",
                "scrolling does not require a PTY or native session"
            ),
            transcriptRows = 512,
            scrollsViewport = true,
            render = ::renderScrollback
        ),
        GpuLabScene(
            id = "alternate-screen",
            title = "Alternate-screen entry and exit",
            expectedInvariants = listOf(
                "alternateBufferActive toggles only at deterministic frame boundaries",
                "main and alternate buffers have visibly different sentinels",
                "cursor and palette state belong to the published frame"
            ),
            render = ::renderAlternateScreen
        ),
        GpuLabScene(
            id = "atlas-churn",
            title = "Bounded atlas churn",
            expectedInvariants = listOf(
                "unique glyph/run inputs are deterministic and bounded",
                "ASCII, CJK, symbols, and color-emoji candidates are mixed",
                "atlas reset/rebuild must recover the same latest frame"
            ),
            render = ::renderAtlasChurn
        ),
        GpuLabScene(
            id = "odd-resize",
            title = "Odd pixel sizes and resize",
            expectedInvariants = listOf(
                "raw viewport pixels can be odd without changing cell ownership",
                "minimum-grid clamping keeps columns and rows positive",
                "geometry diagnostics identify pixels, grid, cell size, and content top"
            ),
            render = ::renderOddResize
        ),
        GpuLabScene(
            id = "surface-recreation",
            title = "Surface recreation recovery",
            expectedInvariants = listOf(
                "destroy/recreate presents the newest immutable frame",
                "surface generation is separate from terminal sequence",
                "no native, storage, network, or permission dependency exists"
            ),
            render = ::renderSurfaceRecreation
        )
    )

    private val ansiColors = intArrayOf(
        0xFF000000.toInt(), 0xFFCD0000.toInt(), 0xFF00CD00.toInt(), 0xFFCDCD00.toInt(),
        0xFF0000EE.toInt(), 0xFFCD00CD.toInt(), 0xFF00CDCD.toInt(), 0xFFE5E5E5.toInt(),
        0xFF7F7F7F.toInt(), 0xFFFF0000.toInt(), 0xFF00FF00.toInt(), 0xFFFFFF00.toInt(),
        0xFF5C5CFF.toInt(), 0xFFFF00FF.toInt(), 0xFF00FFFF.toInt(), 0xFFFFFFFF.toInt()
    )

    private val atlasGlyphs = listOf(
        "A", "g", "0", "@", "#", "&", "%", "§", "Ω", "Ж", "λ", "中", "界", "語",
        "╭", "─", "╮", "╰", "╯", "█", "░", "▸", "✓", "⚙", "→", "←", "👩‍💻", "🧪",
        "☕️", "🛰️", "🦄", "✦"
    )

    private fun renderColorsGrid(context: GpuLabSceneContext): GpuLabFrameContent {
        val swatches = listOf(
            "R" to 0xFFFF0000.toInt(),
            "G" to 0xFF00FF00.toInt(),
            "B" to 0xFF0000FF.toInt(),
            "Y" to 0xFFFFFF00.toInt(),
            "C" to 0xFF00FFFF.toInt(),
            "M" to 0xFFFF00FF.toInt(),
            "W" to 0xFFFFFFFF.toInt(),
            "K" to 0xFF808080.toInt()
        )
        val rows = buildList {
            if (context.rows > 1) {
                add(
                    makeRow(
                        context.columns,
                        List(context.columns) { column ->
                            val swatch = swatches[(column / 4) % swatches.size]
                            GpuLabCell(
                                text = if (column % 4 == 0) swatch.first else "",
                                style = GpuLabStyles.trueColor(0xFFFFFFFF.toInt(), swatch.second)
                            )
                        }
                    )
                )
            }
            repeat((context.rows - 2).coerceAtLeast(0)) { rowIndex ->
                val cells = List(context.columns) { column ->
                    val colorIndex = if (rowIndex < 8) {
                        (rowIndex * 2 + column / 4) % 16
                    } else if ((rowIndex + column) % 2 == 0) {
                        7
                    } else {
                        0
                    }
                    val foreground = if (colorIndex in 0..7) 15 else 0
                    GpuLabCell(
                        text = if (column % 4 == 0) colorIndex.toString(16).uppercase() else "",
                        style = GpuLabStyles.indexed(foreground, colorIndex)
                    )
                }
                add(makeRow(context.columns, cells))
            }
        }
        return finish(context, "colors-grid", rows)
    }

    private fun renderAsciiShell(context: GpuLabSceneContext): GpuLabFrameContent {
        val lines = listOf(
            "┌─ ecto@gpu-lab: ~/terminal " +
                "───────────────────────────────────────────────" +
                "┐",
            "│ deterministic shell fixture                                │",
            "└───────────────────────────────────────────────" +
                "─────────────────" +
                "┘",
            "",
            "ecto@gpu-lab:~$ printf 'frame-stable\\n'",
            "frame-stable",
            "ecto@gpu-lab:~$ tmux list-windows",
            "0: bash* (1 panes)  1: tests-✓ (1 panes)",
            "[0] bash  [1] tests  [2] logs                              ",
            "progress [██████████░░░░░░░░] 50%",
            "",
            "GPU renderer laboratory: press Step to advance deterministically",
            "",
            "ecto@gpu-lab:~$ _"
        )
        val rows = lines.mapIndexed { index, line ->
            val style = when {
                index == 0 || index == 2 -> GpuLabStyles.indexed(14, 4)
                index == 4 || index == 6 || index == lines.lastIndex ->
                    GpuLabStyles.indexed(10, TextStyle.COLOR_INDEX_BACKGROUND, TextStyle.CHARACTER_ATTRIBUTE_BOLD)
                else -> GpuLabStyles.NORMAL
            }
            textRow(context.columns, line, style, isLineWrap = index == 11)
        }
        return finish(context, "ascii-shell", rows)
    }

    private fun renderAnsiStyles(context: GpuLabSceneContext): GpuLabFrameContent {
        val styleRows = listOf(
            "normal      The quick brown fox",
            "bold        The quick brown fox",
            "faint       The quick brown fox",
            "italic      The quick brown fox",
            "inverse     The quick brown fox",
            "invisible   The quick brown fox",
            "strike      The quick brown fox",
            "underline   The quick brown fox",
            "overline    sentinel: no TerminalFrame bit",
            "bright ANSI  0 1 2 3 4 5 6 7 8 9 A B C D E F"
        )
        val effects = intArrayOf(
            0,
            TextStyle.CHARACTER_ATTRIBUTE_BOLD,
            TextStyle.CHARACTER_ATTRIBUTE_DIM,
            TextStyle.CHARACTER_ATTRIBUTE_ITALIC,
            TextStyle.CHARACTER_ATTRIBUTE_INVERSE,
            TextStyle.CHARACTER_ATTRIBUTE_INVISIBLE,
            TextStyle.CHARACTER_ATTRIBUTE_STRIKETHROUGH,
            TextStyle.CHARACTER_ATTRIBUTE_UNDERLINE,
            0,
            TextStyle.CHARACTER_ATTRIBUTE_BOLD
        )
        val rows = styleRows.mapIndexed { index, line ->
            val foreground = if (index == 9) 15 else (index + 1).coerceAtMost(15)
            textRow(
                context.columns,
                line,
                GpuLabStyles.indexed(foreground, TextStyle.COLOR_INDEX_BACKGROUND, effects[index])
            )
        } + (0 until 2).map { rowIndex ->
            val cells = (0 until context.columns).map { column ->
                val color = (column + rowIndex * 8) % 16
                GpuLabCell(text = if (column % 2 == 0) "a" else "", style = GpuLabStyles.indexed(15, color))
            }
            makeRow(context.columns, cells)
        }
        return finish(context, "ansi-styles", rows)
    }

    private fun renderUnicode(context: GpuLabSceneContext): GpuLabFrameContent {
        val style = GpuLabStyles.indexed(14, TextStyle.COLOR_INDEX_BACKGROUND)
        val wideStyle = GpuLabStyles.indexed(11, TextStyle.COLOR_INDEX_BACKGROUND)
        val rows = listOf(
            labeledCells("narrow: ", "ASCII abc XYZ 123", style),
            labeledCells("surrogate: ", "𝄞 𝛼 𐐷", wideStyle),
            textCells("CJK wide: ", style) + listOf(
                GpuLabCell("界", 2, wideStyle), GpuLabCell("語", 2, wideStyle), GpuLabCell("中", 2, wideStyle)
            ),
            labeledCells("combining: ", "e\u0301 a\u0308 n\u0303", style),
            textCells("variation: ", style) + listOf(GpuLabCell("☕️", 2, wideStyle)),
            textCells("ZWJ: ", style) + listOf(GpuLabCell("👩‍💻", 2, wideStyle)),
            textCells("color emoji: ", style) + listOf(
                GpuLabCell("🧪", 2, wideStyle), GpuLabCell("🛰️", 2, wideStyle), GpuLabCell("🦄", 2, wideStyle)
            ),
            labeledCells("fallback: ", "Ω Ж مرحبا שלום", wideStyle)
        ).map { cells -> makeRow(context.columns, cells) }
        return finish(context, "unicode-wide-emoji", rows)
    }

    private fun renderCursorSelectionLinks(context: GpuLabSceneContext): GpuLabFrameContent {
        val normal = GpuLabStyles.indexed(15, TextStyle.COLOR_INDEX_BACKGROUND)
        val selected = GpuLabStyles.indexed(15, 4)
        val rows = mutableListOf<TerminalRow>()
        rows += textRow(context.columns, "selection: [single] [multi-row] [wide 界]", normal)
        rows += makeRow(
            context.columns,
            textCells("selected: ", normal) + listOf(
                GpuLabCell("one", 1, selected), GpuLabCell(" ", 1, normal),
                GpuLabCell("界", 2, selected), GpuLabCell(" range", 1, selected)
            )
        )
        val linkText = "OSC8: https://example.invalid/gpu-lab  literal: https://termux.dev/"
        rows += textRow(context.columns, linkText, normal)
        rows += textRow(context.columns, "cursor phase ${context.frameIndex % 3}: block / underline / bar", normal)
        rows += textRow(context.columns, "reverse video and selection stay frame-local", GpuLabStyles.indexed(0, 7))

        val linkRow = 3
        val firstStart = "OSC8: ".length
        val firstEnd = (firstStart + "https://example.invalid/gpu-lab".length).coerceAtMost(context.columns)
        val secondStart = "OSC8: https://example.invalid/gpu-lab  literal: ".length
        val secondEnd = (secondStart + "https://termux.dev/".length).coerceAtMost(context.columns)
        val linkSegments = if (linkRow < context.rows) {
            mapOf(
                linkRow to listOf(
                    TerminalLinkSegment(firstStart, firstEnd, "https://example.invalid/gpu-lab"),
                    TerminalLinkSegment(secondStart, secondEnd, "https://termux.dev/")
                )
            )
        } else {
            emptyMap()
        }
        val cursorColumn = when (context.frameIndex % 3) {
            0 -> 0
            1 -> (context.columns / 2).coerceAtLeast(0)
            else -> (context.columns - 1).coerceAtLeast(0)
        }
        val cursorStyle = when (context.frameIndex % 3) {
            0 -> TerminalCursor.STYLE_BLOCK
            1 -> TerminalCursor.STYLE_UNDERLINE
            else -> TerminalCursor.STYLE_BAR
        }
        val selectedRow = context.topRow + 2.coerceAtMost((context.rows - 1).coerceAtLeast(0))
        val selection = when (context.frameIndex % 3) {
            0 -> {
                val column = 9.coerceAtMost((context.columns - 1).coerceAtLeast(0))
                TerminalSelection(column, selectedRow, column, selectedRow)
            }
            1 -> {
                val startColumn = 4.coerceAtMost((context.columns - 1).coerceAtLeast(0))
                val endColumn = 13.coerceAtMost((context.columns - 1).coerceAtLeast(0))
                val startRow = context.topRow + 1.coerceAtMost((context.rows - 1).coerceAtLeast(0))
                val endRow = context.topRow + 3.coerceAtMost((context.rows - 1).coerceAtLeast(0))
                TerminalSelection(startColumn, startRow, endColumn, endRow)
            }
            else -> {
                val startColumn = 11.coerceAtMost((context.columns - 2).coerceAtLeast(0))
                val endColumn = (startColumn + 1).coerceAtMost((context.columns - 1).coerceAtLeast(0))
                TerminalSelection(startColumn, selectedRow, endColumn, selectedRow)
            }
        }
        return finish(
            context = context,
            label = "cursor-selection-links",
            rows = rows,
            cursor = TerminalCursor(
                column = cursorColumn,
                row = context.topRow + 4.coerceAtMost((context.rows - 1).coerceAtLeast(0)),
                visible = true,
                style = cursorStyle
            ),
            modes = TerminalModes(
                reverseVideo = context.frameIndex % 4 == 3,
                cursorKeysApplicationMode = false,
                keypadApplicationMode = false,
                mouseTrackingActive = false,
                alternateBufferActive = false
            ),
            links = linkSegments,
            selection = selection
        )
    }

    private fun renderSparseUpdate(context: GpuLabSceneContext): GpuLabFrameContent {
        val rows = listOf(
            textRow(context.columns, "stable row A | content hash must not change", GpuLabStyles.NORMAL),
            textRow(context.columns, "stable row B | retained glyphs should survive", GpuLabStyles.NORMAL),
            textRow(
                context.columns,
                "progress row | tick=${context.frameIndex.toString().padStart(4, '0')} | one-row damage",
                GpuLabStyles.indexed(11, TextStyle.COLOR_INDEX_BACKGROUND)
            ),
            textRow(context.columns, "stable row C | deterministic sparse sentinel", GpuLabStyles.NORMAL),
            textRow(context.columns, "stable row D | no dropped frame data", GpuLabStyles.NORMAL)
        )
        return finish(context, "sparse-update", rows, dynamicSentinel = false)
    }

    private fun renderFullUpdate(context: GpuLabSceneContext): GpuLabFrameContent {
        val rows = (0 until (context.rows - 1).coerceAtLeast(0)).map { rowIndex ->
            val style = GpuLabStyles.indexed(
                foreground = if ((rowIndex + context.frameIndex) % 2 == 0) 15 else 0,
                background = (rowIndex + context.frameIndex) % 16,
                effects = if (rowIndex % 3 == 0) TextStyle.CHARACTER_ATTRIBUTE_BOLD else 0
            )
            textRow(
                context.columns,
                "FULL rev=${context.frameIndex.toString().padStart(4, '0')} row=$rowIndex all-cell update",
                style
            )
        }
        return finish(context, "full-update", rows)
    }

    private fun renderScrollback(context: GpuLabSceneContext): GpuLabFrameContent {
        val rows = (0 until (context.rows - 1).coerceAtLeast(0)).map { rowIndex ->
            val absoluteRow = context.topRow + rowIndex
            textRow(
                context.columns,
                "scrollback absolute-row=${absoluteRow.toString().padStart(4, '0')} " +
                    "viewport=${context.topRow} line=${absoluteRow % 17}",
                GpuLabStyles.indexed(if (absoluteRow % 2 == 0) 14 else 10)
            )
        }
        return finish(context, "scrollback", rows)
    }

    private fun renderAlternateScreen(context: GpuLabSceneContext): GpuLabFrameContent {
        val alternate = context.frameIndex % 4 in 2..3
        val background = if (alternate) 4 else TextStyle.COLOR_INDEX_BACKGROUND
        val foreground = if (alternate) 15 else TextStyle.COLOR_INDEX_FOREGROUND
        val rows = listOf(
            textRow(
                context.columns,
                if (alternate) "ALTERNATE SCREEN | fullscreen app buffer" else "MAIN SCREEN | shell buffer restored",
                GpuLabStyles.indexed(foreground, background, TextStyle.CHARACTER_ATTRIBUTE_BOLD)
            ),
            textRow(context.columns, "toggle phase=${context.frameIndex % 4} active=$alternate", normalStyle()),
            textRow(context.columns, "cursor and palette are part of this immutable frame", normalStyle())
        )
        return finish(
            context,
            "alternate-screen",
            rows,
            cursor = TerminalCursor(
                column = context.frameIndex * 3 % context.columns.coerceAtLeast(1),
                row = context.topRow + 1.coerceAtMost((context.rows - 1).coerceAtLeast(0)),
                visible = true,
                style = TerminalCursor.STYLE_BLOCK
            ),
            modes = TerminalModes(
                reverseVideo = false,
                cursorKeysApplicationMode = alternate,
                keypadApplicationMode = alternate,
                mouseTrackingActive = alternate,
                alternateBufferActive = alternate
            )
        )
    }

    private fun renderAtlasChurn(context: GpuLabSceneContext): GpuLabFrameContent {
        val rows = (0 until (context.rows - 1).coerceAtLeast(0)).map { rowIndex ->
            val cells = textCells("atlas[$rowIndex]: ", GpuLabStyles.NORMAL).toMutableList()
            val glyphCount = (context.columns - cells.size).coerceAtLeast(0)
            repeat(glyphCount) { column ->
                val glyph = atlasGlyphs[(context.frameIndex + rowIndex + column) % atlasGlyphs.size]
                val width = if (glyph.any { it.code > 0x7f }) 2 else 1
                cells += GpuLabCell(glyph, width, GpuLabStyles.indexed(11, TextStyle.COLOR_INDEX_BACKGROUND))
            }
            makeRow(context.columns, cells)
        }
        return finish(context, "atlas-churn", rows)
    }

    private fun renderOddResize(context: GpuLabSceneContext): GpuLabFrameContent {
        val rows = listOf(
            textRow(
                context.columns,
                "odd pixels=${context.size.widthPx}x${context.size.heightPx} " +
                    "cell=${context.size.cellWidthPx}x${context.size.cellHeightPx}",
                GpuLabStyles.indexed(14, 4)
            ),
            textRow(
                context.columns,
                "grid=${context.columns}x${context.rows} top=${context.size.contentTopPx} raw geometry is preserved",
                GpuLabStyles.NORMAL
            ),
            makeRow(
                context.columns,
                (0 until context.columns).map { column ->
                    GpuLabCell(
                        text = if (column % 2 == 0) "·" else "",
                        style = GpuLabStyles.indexed(12, if (column % 2 == 0) 8 else 0)
                    )
                }
            )
        )
        return finish(context, "odd-resize", rows, dynamicSentinel = false)
    }

    private fun renderSurfaceRecreation(context: GpuLabSceneContext): GpuLabFrameContent {
        val rows = listOf(
            textRow(context.columns, "SURFACE RECREATE | latest frame must be restored", GpuLabStyles.indexed(10, 4)),
            textRow(context.columns, "terminal sequence=${context.sequence} remains monotonic", GpuLabStyles.NORMAL),
            textRow(context.columns, "GL generation is owned by the renderer, not this backend", GpuLabStyles.NORMAL)
        )
        return finish(context, "surface-recreation", rows, dynamicSentinel = false)
    }

    private fun finish(
        context: GpuLabSceneContext,
        label: String,
        rows: List<TerminalRow>,
        cursor: TerminalCursor = TerminalCursor(0, context.topRow, false, TerminalCursor.STYLE_BLOCK),
        modes: TerminalModes = TerminalModes(false, false, false, false, false),
        links: Map<Int, List<TerminalLinkSegment>> = emptyMap(),
        selection: TerminalSelection = TerminalSelection.EMPTY,
        dynamicSentinel: Boolean = true
    ): GpuLabFrameContent {
        val sentinel = if (dynamicSentinel) {
            "GPU-LAB GLES $label rev=${context.frameIndex} seq=${context.sequence} " +
                "grid=${context.columns}x${context.rows}"
        } else {
            "GPU-LAB GLES $label | stable sentinel"
        }
        val allRows = ArrayList<TerminalRow>(context.rows)
        allRows += textRow(context.columns, sentinel, GpuLabStyles.indexed(15, 8))
        allRows += rows
        while (allRows.size < context.rows) allRows += makeRow(context.columns, emptyList())
        val completeRows = allRows.take(context.rows).toList()
        val completeLinks = List(context.rows) { rowIndex -> links[rowIndex].orEmpty() }
        return GpuLabFrameContent(
            palette = paletteFor(context.sceneIndex),
            rows = completeRows,
            cursor = cursor,
            modes = modes,
            linkRows = completeLinks,
            selection = selection
        )
    }

    private fun normalStyle(): Long = GpuLabStyles.NORMAL

    private fun labeledCells(label: String, value: String, style: Long): List<GpuLabCell> =
        textCells(label, style) + textCells(value, style)

    private fun textRow(columns: Int, text: String, style: Long, isLineWrap: Boolean = false): TerminalRow =
        makeRow(columns, textCells(text, style), isLineWrap)

    private fun textCells(text: String, style: Long): List<GpuLabCell> {
        val result = ArrayList<GpuLabCell>()
        var index = 0
        while (index < text.length) {
            val codePoint = text.codePointAt(index)
            val charCount = Character.charCount(codePoint)
            val value = text.substring(index, index + charCount)
            val width = displayWidth(codePoint)
            if (width == 0 && result.isNotEmpty()) {
                val previous = result.removeAt(result.lastIndex)
                result += previous.copy(text = previous.text + value)
            } else {
                result += GpuLabCell(value, width.coerceAtLeast(1), style)
            }
            index += charCount
        }
        return result
    }

    private fun displayWidth(codePoint: Int): Int {
        if (codePoint in 0x0300..0x036f || codePoint in 0xFE00..0xFE0F || codePoint == 0x200d) return 0
        if (codePoint in 0x1100..0x11ff || codePoint in 0x2e80..0xa4cf ||
            codePoint in 0xac00..0xd7ff || codePoint in 0xf900..0xfaff ||
            codePoint in 0x1f300..0x1faff
        ) return 2
        return 1
    }

    private fun makeRow(columns: Int, cells: List<GpuLabCell>, isLineWrap: Boolean = false): TerminalRow {
        val text = StringBuilder()
        val styles = LongArray(columns) { GpuLabStyles.NORMAL }
        val starts = IntArray(columns) { -1 }
        val lengths = IntArray(columns)
        val displayWidths = IntArray(columns) { 1 }
        var column = 0
        for (cell in cells) {
            if (column >= columns) break
            val width = cell.width.coerceAtMost(columns - column)
            val start = text.length
            if (cell.text.isNotEmpty()) text.append(cell.text)
            val length = text.length - start
            starts[column] = if (length == 0) -1 else start
            lengths[column] = length
            displayWidths[column] = width
            styles[column] = cell.style
            repeat(width - 1) { continuationOffset ->
                val continuationColumn = column + continuationOffset + 1
                starts[continuationColumn] = -1
                lengths[continuationColumn] = 0
                displayWidths[continuationColumn] = 0
                styles[continuationColumn] = cell.style
            }
            column += width
        }
        val chars = text.toString().toCharArray()
        return TerminalRow.takeOwnership(
            columns = columns,
            text = chars,
            charsUsed = chars.size,
            styles = styles,
            contentHash = rowHash(chars, styles, displayWidths, isLineWrap),
            cellLayout = TerminalCellLayout.takeOwnership(starts, lengths, displayWidths),
            isLineWrap = isLineWrap
        )
    }

    private fun rowHash(chars: CharArray, styles: LongArray, displayWidths: IntArray, isLineWrap: Boolean): Long {
        var hash = -3750763034362895579L
        for (char in chars) hash = (hash xor char.code.toLong()) * 1099511628211L
        for (style in styles) hash = (hash xor style) * 1099511628211L
        for (width in displayWidths) hash = (hash xor width.toLong()) * 1099511628211L
        if (isLineWrap) hash = hash xor 1L
        return hash
    }

    private fun paletteFor(sceneIndex: Int): TerminalPalette {
        val colors = IntArray(TextStyle.NUM_INDEXED_COLORS)
        for (index in colors.indices) {
            colors[index] = when {
                index < ansiColors.size -> ansiColors[index]
                index in 16..231 -> xtermColor(index)
                index in 232..255 -> {
                    val value = 8 + (index - 232) * 10
                    0xFF000000.toInt() or (value shl 16) or (value shl 8) or value
                }
                index == TextStyle.COLOR_INDEX_FOREGROUND -> 0xFFE6EDF3.toInt()
                index == TextStyle.COLOR_INDEX_BACKGROUND -> 0xFF101820.toInt()
                index == TextStyle.COLOR_INDEX_CURSOR -> 0xFFFFD166.toInt()
                else -> 0xFF000000.toInt()
            }
        }
        return TerminalPalette.of(colors, sceneIndex + 1)
    }

    private fun xtermColor(index: Int): Int {
        val offset = index - 16
        val red = offset / 36
        val green = (offset / 6) % 6
        val blue = offset % 6
        fun component(value: Int): Int = if (value == 0) 0 else 55 + value * 40
        return 0xFF000000.toInt() or
            (component(red) shl 16) or
            (component(green) shl 8) or
            component(blue)
    }
}

internal fun GpuLabFrameContent.toTerminalFrame(
    sequence: Long,
    topRow: Int,
    size: TerminalSize,
    transcriptRows: Int
): TerminalFrame {
    val links = if (linkRows.any { it.isNotEmpty() }) {
        TerminalLinkLayout(
            frameSequence = sequence,
            topRow = topRow,
            rows = size.rows,
            columns = size.columns,
            segmentsPerRow = linkRows
        )
    } else {
        null
    }
    return TerminalFrame(
        sequence = sequence,
        viewport = TerminalViewport(
            topRow = topRow,
            rows = size.rows,
            columns = size.columns,
            transcriptRows = transcriptRows
        ),
        cursor = cursor,
        modes = modes,
        palette = palette,
        rows = rows.toList(),
        linkLayout = links
    )
}
