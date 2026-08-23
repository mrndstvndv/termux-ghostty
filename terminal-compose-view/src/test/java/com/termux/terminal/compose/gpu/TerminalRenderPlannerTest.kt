package com.termux.terminal.compose.gpu

import com.termux.terminal.TextStyle
import com.termux.terminal.compose.TerminalCellLayout
import com.termux.terminal.compose.TerminalCursor
import com.termux.terminal.compose.TerminalFrame
import com.termux.terminal.compose.TerminalLinkLayout
import com.termux.terminal.compose.TerminalLinkSegment
import com.termux.terminal.compose.TerminalRow
import com.termux.terminal.compose.TerminalSelection
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class TerminalRenderPlannerTest {
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
        assertEquals("🧠", plan.glyphs[1].key.text)
        // TerminalRowRenderer paints the block cursor before text and swaps the
        // cell colors, so the wide glyph uses its original background as ink.
        assertEquals(0xFF101010.toInt(), plan.glyphs[1].key.foregroundArgb)
        assertTrue(plan.cellBackgrounds.any { it.left == 0f && it.argb == 0xFFFF0000.toInt() })
        assertTrue(plan.cellBackgrounds.any { it.left == 10f })
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
