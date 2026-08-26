package com.termux.terminal.compose.gpu

import com.termux.terminal.TextStyle
import com.termux.terminal.compose.TerminalCellLayout
import com.termux.terminal.compose.TerminalCursor
import com.termux.terminal.compose.TerminalFrame
import com.termux.terminal.compose.TerminalLinkLayout
import com.termux.terminal.compose.TerminalLinkSegment
import com.termux.terminal.compose.TerminalRow
import com.termux.terminal.compose.TerminalSelection
import com.termux.terminal.compose.TerminalViewport
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class TerminalRenderPlannerTest {
    @Test
    fun addsTopAndBottomOverscanRowsWithoutChangingVisibleRowPlacement() {
        val snapshot = testSnapshot(14L)

        val plan = TerminalRenderPlanner().plan(snapshot)

        assertEquals(snapshot.frame.rowsVisible + 2, plan.rows.size)
        assertNull(plan.rows.first())
        assertNull(plan.rows.last())
        assertEquals(-1, plan.rowOrigin)
        assertTrue(plan.rows[1] != null)
    }

    @Test
    fun reusesPlanAndRowPacketsWhenSnapshotIsUnchanged() {
        val snapshot = testSnapshot(14L)
        val planner = TerminalRenderPlanner()

        val first = planner.plan(snapshot)
        val second = planner.plan(snapshot)

        assertTrue(first === second)
        assertTrue(first.glyphs[0] === second.glyphs[0])
        assertTrue(first.cellBackgrounds[0] === second.cellBackgrounds[0])
    }

    @Test
    fun reusesUnchangedRowsAcrossFrameSequences() {
        val firstSnapshot = testSnapshot(15L)
        val secondFrame = TerminalFrame(
            sequence = 16L,
            viewport = firstSnapshot.frame.viewport,
            cursor = firstSnapshot.frame.cursor,
            modes = firstSnapshot.frame.modes,
            palette = firstSnapshot.frame.palette,
            rows = firstSnapshot.frame.rows,
            linkLayout = null
        )
        val secondSnapshot = GlesTerminalSnapshot(
            frame = secondFrame,
            metrics = firstSnapshot.metrics
        )
        val planner = TerminalRenderPlanner()

        val first = planner.plan(firstSnapshot)
        val second = planner.plan(secondSnapshot)

        assertTrue(first !== second)
        assertTrue(first.glyphs[0] === second.glyphs[0])
        assertTrue(first.cellBackgrounds[0] === second.cellBackgrounds[0])
    }

    @Test
    fun reusesRowPacketsWhenScrollingMovesRowsToNewViewportSlots() {
        val rows = listOf(
            scrollRow("A", 1L),
            scrollRow("B", 2L),
            scrollRow("C", 3L),
            scrollRow("D", 4L)
        )
        val palette = testPalette()
        val firstSnapshot = GlesTerminalSnapshot(
            frame = scrollFrame(20L, topRow = 0, rows = rows.take(3), palette = palette),
            metrics = testSnapshot(20L).metrics
        )
        val secondSnapshot = GlesTerminalSnapshot(
            frame = scrollFrame(21L, topRow = 1, rows = rows.drop(1), palette = palette),
            metrics = firstSnapshot.metrics
        )
        val planner = TerminalRenderPlanner()

        val first = planner.plan(firstSnapshot)
        val second = planner.plan(secondSnapshot)

        assertSame(first.rows[2], second.rows[1])
        assertSame(first.rows[3], second.rows[2])
        assertNotSame(first.rows[1], second.rows[3])
    }

    @Test
    fun presentationOverlayChangesInvalidateCachedRowPackets() {
        val snapshot = testSnapshot(17L)
        val planner = TerminalRenderPlanner()

        val first = planner.plan(snapshot)
        val second = planner.plan(
            snapshot.copy(selection = TerminalSelection(0, 0, 0, 0))
        )

        assertNotSame(first.glyphs[0], second.glyphs[0])
        assertNotSame(first.cellBackgrounds[0], second.cellBackgrounds[0])
    }

    @Test
    fun plansUnicodeGlyphsBackgroundsSelectionLinksAndWideCursor() {
        val base = testFrame(11L)
        val frame = frameWith(
            base = base,
            cursorStyle = TerminalCursor.STYLE_BLOCK,
            linkLayout = TerminalLinkLayout(
                frameSequence = base.sequence,
                topRow = 0,
                rows = 1,
                columns = 3,
                segmentsPerRow = listOf(listOf(TerminalLinkSegment(0, 3, "https://example.com")))
            )
        )
        val snapshot = GlesTerminalSnapshot(
            frame = frame,
            metrics = testSnapshot(11L).metrics,
            selection = TerminalSelection(0, 0, 0, 0)
        )

        val plan = TerminalRenderPlanner().plan(snapshot)

        assertEquals(2, plan.glyphs.size)
        assertEquals(GlyphAtlasKey.RASTER_MODE_MASK, plan.glyphs[0].key.rasterMode)
        assertEquals("🧠", plan.glyphs[1].key.text)
        assertEquals(GlyphAtlasKey.RASTER_MODE_RGBA, plan.glyphs[1].key.rasterMode)
        // The GLES planner paints the block cursor before text and swaps the
        // cell colors, so the wide glyph uses its original background as ink.
        assertEquals(0xFF101010.toInt(), plan.glyphs[1].key.foregroundArgb)
        assertTrue(plan.cellBackgrounds.any { it.left == 0f && it.style != null })
        assertTrue(plan.cellBackgrounds.any { it.left == 10f && it.style != null })
        assertEquals(1, plan.cursorQuads.size)
        assertEquals(10f, plan.cursorQuads.single().left, 0f)
        assertEquals(30f, plan.cursorQuads.single().right, 0f)
        assertEquals(2, plan.decorations.size)
        assertEquals(18f, plan.decorations.first().top, 0f)
    }

    @Test
    fun keepsItalicAtlasVariantScopedToItalicCells() {
        val base = testFrame(13L)
        val italicStyle = indexedStyle(3, 0, TextStyle.CHARACTER_ATTRIBUTE_ITALIC)
        val row = TerminalRow(
            columns = 2,
            text = "AB".toCharArray(),
            charsUsed = 2,
            styles = longArrayOf(indexedStyle(3, 0), italicStyle),
            contentHash = 13L,
            cellLayout = TerminalCellLayout(
                start = intArrayOf(0, 1),
                length = intArrayOf(1, 1),
                displayWidth = intArrayOf(1, 1)
            ),
            isLineWrap = false
        )
        val frame = TerminalFrame(
            sequence = base.sequence,
            viewport = base.viewport.copy(columns = 2),
            cursor = base.cursor,
            modes = base.modes,
            palette = base.palette,
            rows = listOf(row),
            linkLayout = null
        )

        val plan = TerminalRenderPlanner().plan(
            GlesTerminalSnapshot(frame, testSnapshot(13L).metrics)
        )

        assertEquals(2, plan.glyphs.size)
        assertFalse(plan.glyphs[0].key.italic)
        assertTrue(plan.glyphs[1].key.italic)
    }

    @Test
    fun plansAllCursorShapesWithoutChangingTheSnapshot() {
        val base = testFrame(12L)
        val metrics = testSnapshot(12L).metrics
        val expectedRight = mapOf(
            TerminalCursor.STYLE_BLOCK to 30f,
            TerminalCursor.STYLE_UNDERLINE to 30f,
            TerminalCursor.STYLE_BAR to 15f
        )

        expectedRight.forEach { (style, right) ->
            val frame = frameWith(base, style, null)
            val plan = TerminalRenderPlanner().plan(GlesTerminalSnapshot(frame, metrics))
            assertEquals(right, plan.cursorQuads.single().right, 0f)
        }
        assertEquals(12L, base.sequence)
    }

    private fun scrollFrame(
        sequence: Long,
        topRow: Int,
        rows: List<TerminalRow>,
        palette: com.termux.terminal.compose.TerminalPalette
    ): TerminalFrame = TerminalFrame(
        sequence = sequence,
        viewport = TerminalViewport(topRow, rows.size, 3, 4),
        cursor = TerminalCursor(0, -1, false, TerminalCursor.STYLE_BLOCK),
        modes = com.termux.terminal.compose.TerminalModes(false, false, false, false, false),
        palette = palette,
        rows = rows,
        linkLayout = null
    )

    private fun scrollRow(text: String, contentHash: Long): TerminalRow = TerminalRow(
        columns = 3,
        text = text.toCharArray(),
        charsUsed = text.length,
        styles = LongArray(3) { indexedStyle(3, 0) },
        contentHash = contentHash,
        cellLayout = TerminalCellLayout(
            start = intArrayOf(0, -1, -1),
            length = intArrayOf(1, 0, 0),
            displayWidth = intArrayOf(1, 1, 1)
        ),
        isLineWrap = false
    )

    private fun frameWith(
        base: TerminalFrame,
        cursorStyle: Int,
        linkLayout: TerminalLinkLayout?
    ): TerminalFrame = TerminalFrame(
        sequence = base.sequence,
        viewport = base.viewport,
        cursor = TerminalCursor(base.cursor.column, base.cursor.row, base.cursor.visible, cursorStyle),
        modes = base.modes,
        palette = base.palette,
        rows = base.rows,
        linkLayout = linkLayout
    )
}
