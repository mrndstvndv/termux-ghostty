package com.termux.terminal

import com.mrndtvndv.term.ui.workspace.TerminalFrameContentCache
import com.mrndtvndv.term.ui.workspace.TerminalFrameSessionState
import com.mrndtvndv.term.ui.workspace.TerminalSessionFrameStore
import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Test

class TerminalFrameContentCacheTest {
    @Test
    fun `frame store eagerly publishes immutable frames from session deltas`() {
        val store = TerminalSessionFrameStore()
        val firstSnapshot = snapshot(true, intArrayOf(), longArrayOf(11L, 22L), true, 0)
        firstSnapshot.setFrameSequence(1L)
        val links = ViewportLinkSnapshot.create(
            1L,
            0,
            RowCount,
            ColumnCount,
            arrayOf(ViewportLinkSnapshot.Segment(0, 0, 2, "https://example.com"))
        )

        assertEquals(
            TerminalSessionFrameStore.ApplyResult.UPDATED,
            store.apply(FrameDelta(1L, FrameDelta.REASON_RESET, firstSnapshot, links), frameState())
        )
        val firstFrame = store.currentFrame()!!

        val secondSnapshot = snapshot(false, intArrayOf(1), longArrayOf(33L), false, 0)
        secondSnapshot.setFrameSequence(2L)
        assertEquals(
            TerminalSessionFrameStore.ApplyResult.UPDATED,
            store.apply(
                FrameDelta(
                    2L,
                    FrameDelta.REASON_APPEND,
                    secondSnapshot,
                    ViewportLinkSnapshot.create(2L, 0, RowCount, ColumnCount, emptyArray())
                ),
                frameState()
            )
        )

        val secondFrame = store.currentFrame()!!
        assertEquals(1L, firstFrame.sequence)
        assertEquals(11L, firstFrame.rows[0].contentHash)
        assertEquals(22L, firstFrame.rows[1].contentHash)
        assertEquals("https://example.com", firstFrame.linkLayout?.findAt(0, 1)?.url)
        assertEquals(2L, secondFrame.sequence)
        assertSame(firstFrame.rows[0], secondFrame.rows[0])
        assertEquals(33L, secondFrame.rows[1].contentHash)
    }

    @Test
    fun `frame store requests full refresh after a sequence gap`() {
        val store = TerminalSessionFrameStore()
        val initial = snapshot(true, intArrayOf(), longArrayOf(11L, 22L), true, 0)
        initial.setFrameSequence(1L)
        assertEquals(
            TerminalSessionFrameStore.ApplyResult.UPDATED,
            store.apply(FrameDelta(1L, FrameDelta.REASON_RESET, initial), frameState())
        )

        val partial = snapshot(false, intArrayOf(0), longArrayOf(11L), false, 0)
        partial.setFrameSequence(3L)

        assertEquals(
            TerminalSessionFrameStore.ApplyResult.NEEDS_FULL_REFRESH,
            store.apply(FrameDelta(3L, FrameDelta.REASON_APPEND, partial), frameState())
        )
    }

    @Test
    fun `frame store detects literal urls without a view link layout`() {
        val store = TerminalSessionFrameStore()
        val text = "https://example.com."
        val snapshot = textSnapshot(text)
        snapshot.setFrameSequence(1L)
        val links = ViewportLinkSnapshot.create(1L, 0, 1, text.length, emptyArray())

        assertEquals(
            TerminalSessionFrameStore.ApplyResult.UPDATED,
            store.apply(FrameDelta(1L, FrameDelta.REASON_RESET, snapshot, links), frameState())
        )

        val layout = store.currentFrame()?.linkLayout
        assertEquals("https://example.com", layout?.findAt(0, 0)?.url)
        assertEquals("https://example.com", layout?.findAt(0, text.length - 2)?.url)
        assertEquals(null, layout?.findAt(0, text.length - 1))
    }

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

    private fun textSnapshot(text: String): ScreenSnapshot {
        val columns = text.length
        val snapshot = ScreenSnapshot()
        val buffer = snapshot.buffer.order(ByteOrder.nativeOrder())
        buffer.clear()
        buffer.putInt(SnapshotMagic)
        buffer.putInt(0)
        buffer.putInt(1)
        buffer.putInt(columns)
        buffer.putInt(SnapshotFlagFullRebuild)
        buffer.putInt(0)
        buffer.putInt(0)
        buffer.alignToLong()
        buffer.putInt(text.length)
        buffer.putInt(0)
        buffer.putLong(text.hashCode().toLong())
        repeat(columns) { column -> buffer.putInt(column) }
        repeat(columns) { buffer.putShort(1) }
        repeat(columns) { buffer.put(1.toByte()) }
        buffer.alignToLong()
        repeat(columns) { buffer.putLong(0L) }
        text.forEach(buffer::putChar)
        snapshot.markNativeSnapshot(buffer.position())
        return snapshot
    }

    private fun frameState() = TerminalFrameSessionState(
        transcriptRows = 0,
        cursorBlinkingEnabled = false,
        cursorBlinkState = true,
        cursorKeysApplicationMode = false,
        keypadApplicationMode = false,
        mouseTrackingActive = false,
        alternateBufferActive = false
    )

    private companion object {
        const val SnapshotMagic = 0x54475832
        const val SnapshotFlagFullRebuild = 1
        const val SnapshotMetadataPalette = 1
        const val RowCount = 2
        const val ColumnCount = 3
    }
}
