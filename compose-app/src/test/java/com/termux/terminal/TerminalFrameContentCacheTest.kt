package com.termux.terminal

import com.mrndtvndv.term.ui.workspace.TerminalFrameContentCache
import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Test

class TerminalFrameContentCacheTest {
    @Test
    fun `same snapshot sequence reuses complete frame content`() {
        val renderCache = RenderFrameCache()
        val contentCache = TerminalFrameContentCache()
        val snapshot = renderCache.applySnapshot(
            snapshot(true, intArrayOf(), longArrayOf(11L, 22L), true, 0),
            sequence = 1L
        )

        val first = contentCache.update(snapshot)
        val second = contentCache.update(snapshot)

        assertSame(first, second)
    }

    @Test
    fun `partial update reuses palette and untouched rows`() {
        val renderCache = RenderFrameCache()
        val contentCache = TerminalFrameContentCache()

        val initial = renderCache.applySnapshot(
            snapshot(
                fullRebuild = true,
                dirtyRows = intArrayOf(),
                rowHashes = longArrayOf(11L, 22L),
                includePalette = true,
                paletteOffset = 0
            ),
            sequence = 1L
        )
        val first = contentCache.update(initial)

        val changed = renderCache.applySnapshot(
            snapshot(
                fullRebuild = false,
                dirtyRows = intArrayOf(1),
                rowHashes = longArrayOf(33L),
                includePalette = false,
                paletteOffset = 0
            ),
            sequence = 2L
        )
        val second = contentCache.update(changed)

        assertSame(first.palette, second.palette)
        assertSame(first.rows[0], second.rows[0])
        assertNotSame(first.rows[1], second.rows[1])
        assertEquals(33L, second.rows[1].contentHash)
    }

    @Test
    fun `viewport shift reuses rows by absolute position`() {
        val renderCache = RenderFrameCache()
        val contentCache = TerminalFrameContentCache()
        val first = contentCache.update(
            renderCache.applySnapshot(
                snapshot(
                    fullRebuild = true,
                    dirtyRows = intArrayOf(),
                    rowHashes = longArrayOf(11L, 22L),
                    includePalette = true,
                    paletteOffset = 0,
                    topRow = 0
                ),
                sequence = 1L
            )
        )

        val second = contentCache.update(
            renderCache.applySnapshot(
                snapshot(
                    fullRebuild = false,
                    dirtyRows = intArrayOf(1),
                    rowHashes = longArrayOf(33L),
                    includePalette = false,
                    paletteOffset = 0,
                    topRow = 1
                ),
                sequence = 2L
            )
        )

        assertSame(first.rows[1], second.rows[0])
        assertEquals(33L, second.rows[1].contentHash)
    }

    @Test
    fun `palette-only update replaces palette and reuses rows`() {
        val renderCache = RenderFrameCache()
        val contentCache = TerminalFrameContentCache()
        val first = contentCache.update(
            renderCache.applySnapshot(
                snapshot(true, intArrayOf(), longArrayOf(11L, 22L), true, 0),
                sequence = 1L
            )
        )

        val second = contentCache.update(
            renderCache.applySnapshot(
                snapshot(false, intArrayOf(), longArrayOf(), true, 100),
                sequence = 2L
            )
        )

        assertNotSame(first.palette, second.palette)
        assertSame(first.rows[0], second.rows[0])
        assertSame(first.rows[1], second.rows[1])
    }

    private fun RenderFrameCache.applySnapshot(snapshot: ScreenSnapshot, sequence: Long): ScreenSnapshot {
        snapshot.setFrameSequence(sequence)
        assertEquals(
            RenderFrameCache.ApplyResult.APPLIED,
            apply(FrameDelta(sequence, FrameDelta.REASON_APPEND, snapshot))
        )
        return getSnapshotForRender(false, true)!!
    }

    private fun snapshot(
        fullRebuild: Boolean,
        dirtyRows: IntArray,
        rowHashes: LongArray,
        includePalette: Boolean,
        paletteOffset: Int,
        topRow: Int = 0
    ): ScreenSnapshot {
        val expectedPayloadRows = if (fullRebuild) RowCount else dirtyRows.size
        require(rowHashes.size == expectedPayloadRows)
        val snapshot = ScreenSnapshot()
        val buffer = snapshot.buffer.order(ByteOrder.nativeOrder())
        buffer.clear()
        buffer.putInt(SnapshotMagic)
        buffer.putInt(topRow)
        buffer.putInt(RowCount)
        buffer.putInt(ColumnCount)
        buffer.putInt(if (fullRebuild) SnapshotFlagFullRebuild else 0)
        buffer.putInt(if (fullRebuild) 0 else dirtyRows.size)
        buffer.putInt(if (includePalette) SnapshotMetadataPalette else 0)
        if (includePalette) {
            repeat(TextStyle.NUM_INDEXED_COLORS) { colorIndex ->
                buffer.putInt(colorIndex + paletteOffset)
            }
        }
        if (!fullRebuild) {
            dirtyRows.forEach(buffer::putInt)
        }
        rowHashes.forEach { contentHash ->
            buffer.alignToLong()
            buffer.putInt(0)
            buffer.putInt(0)
            buffer.putLong(contentHash)
            repeat(ColumnCount) { buffer.putInt(0) }
            repeat(ColumnCount) { buffer.putShort(0) }
            repeat(ColumnCount) { buffer.put(1.toByte()) }
            buffer.alignToLong()
            repeat(ColumnCount) { buffer.putLong(0L) }
        }
        snapshot.markNativeSnapshot(buffer.position())
        return snapshot
    }

    private fun ByteBuffer.alignToLong() {
        while (position() % Long.SIZE_BYTES != 0) put(0.toByte())
    }

    private companion object {
        const val SnapshotMagic = 0x54475832
        const val SnapshotFlagFullRebuild = 1
        const val SnapshotMetadataPalette = 1
        const val RowCount = 2
        const val ColumnCount = 3
    }
}
