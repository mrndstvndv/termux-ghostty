package com.termux.terminal.compose.internal

import org.junit.Assert.assertEquals
import org.junit.Test

class TerminalRenderTilePlannerTest {
    private val planner = TerminalRenderTilePlanner(rowsPerTile = 4)

    @Test
    fun sparseChangeRecordsOnlyOneFourRowTile() {
        val dirtyRows = BooleanArray(40).apply { this[17] = true }

        val work = planner.plan(visibleRows = 40, dirtyRows = dirtyRows)

        assertEquals(listOf(16..19), work.tiles.map { it.rows })
        assertEquals(1, work.recordedLayerCount)
        assertEquals(4, work.recordedRowCount)
    }

    @Test
    fun sustainedChangeRecordsTenTilesInsteadOfFortyLayers() {
        val work = planner.plan(
            visibleRows = 40,
            dirtyRows = BooleanArray(40) { true }
        )

        assertEquals(10, work.recordedLayerCount)
        assertEquals(40, work.recordedRowCount)
    }

    @Test
    fun finalTileStopsAtVisibleRowCount() {
        val dirtyRows = BooleanArray(42).apply { this[41] = true }

        val work = planner.plan(visibleRows = 42, dirtyRows = dirtyRows)

        assertEquals(listOf(40..41), work.tiles.map { it.rows })
        assertEquals(2, work.recordedRowCount)
    }

    @Test
    fun unchangedFrameRecordsNoTiles() {
        val work = planner.plan(visibleRows = 40, dirtyRows = BooleanArray(40))

        assertEquals(0, work.recordedLayerCount)
        assertEquals(0, work.recordedRowCount)
    }
}
