package com.termux.terminal.compose.internal

import com.termux.terminal.compose.TerminalRow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class TerminalRowRunCacheTest {
    private val hints = RowRenderHints(
        selectionStart = 0,
        selectionEnd = 9,
        cursorX = -1,
        cursorStyle = 0,
        reverseVideo = false
    )

    @Test
    fun buildBeyondInitialCapacityPublishesEveryRunInOrder() {
        val cache = RowRunCache()
        cache.beginBuild(hints, columns = 12, hasCellLayout = false, contentHash = 7L)
        for (column in 0 until 12) {
            cache.addRun(column, 1, column, 1, 0f, 0L, 0)
        }
        cache.finishBuild()

        val runs = cache.runs!!
        assertEquals(12, runs.size)
        runs.forEachIndexed { index, run ->
            assertEquals(index, run.startColumn)
            assertEquals(1, run.widthColumns)
            assertEquals(index, run.startCharIndex)
        }
    }

    @Test
    fun finishBuildPublishesAnImmutableSnapshotPerBuild() {
        val cache = RowRunCache()
        cache.beginBuild(hints, columns = 1, hasCellLayout = false, contentHash = 1L)
        cache.addRun(0, 1, 0, 1, 0f, 0L, 0)
        cache.finishBuild()
        val firstBuild = cache.runs!!

        cache.beginBuild(hints, columns = 2, hasCellLayout = false, contentHash = 2L)
        cache.addRun(5, 2, 3, 2, 0f, 0L, 0)
        cache.finishBuild()
        val secondBuild = cache.runs!!

        assertEquals(1, secondBuild.size)
        assertEquals(5, secondBuild[0].startColumn)
        assertEquals(2, secondBuild[0].widthColumns)
        assertFalse(firstBuild === secondBuild)
        assertSame(cache.runs, secondBuild)
    }

    @Test
    fun emptyBuildPublishesAnEmptyRunSet() {
        val cache = RowRunCache()
        cache.beginBuild(hints, columns = 3, hasCellLayout = true, contentHash = 3L)
        cache.finishBuild()

        assertTrue(cache.runs!!.isEmpty())
    }

    @Test
    fun cacheDoesNotMatchDifferentColumnGeometry() {
        val cache = RowRunCache()
        cache.beginBuild(hints, columns = 3, hasCellLayout = true, contentHash = 7L)
        cache.finishBuild()

        assertFalse(cache.matches(hints, columns = 4, hasCellLayout = true, contentHash = 7L))
    }

    @Test
    fun retainedRowStateTreatsReplacementRowAsChanged() {
        val first = terminalRow(columns = 3, contentHash = 7L)
        val replacement = terminalRow(columns = 4, contentHash = 7L)
        val state = TerminalRowState().apply {
            applyFrame(first, hints, paletteVersion = 0, linkContentHash = 0L)
        }

        assertTrue(rowContentOutdated(state, replacement, linkContentHash = 0L))
    }

    private fun terminalRow(columns: Int, contentHash: Long) = TerminalRow(
        columns = columns,
        text = CharArray(columns),
        charsUsed = 0,
        styles = LongArray(columns),
        contentHash = contentHash,
        cellLayout = null,
        isLineWrap = false
    )
}
