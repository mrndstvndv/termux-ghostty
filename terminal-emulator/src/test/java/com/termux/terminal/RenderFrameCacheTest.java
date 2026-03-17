package com.termux.terminal;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

import org.junit.Test;

public final class RenderFrameCacheTest {

    private static final int SNAPSHOT_MAGIC = 0x54475832;
    private static final int SNAPSHOT_FLAG_FULL_REBUILD = 1;

    @Test
    public void applyRejectsPartialResizeThatRequiresFullRebuild() {
        RenderFrameCache cache = new RenderFrameCache();

        ScreenSnapshot initialSnapshot = createFullNativeBlankSnapshot(0, 2, 63);
        initialSnapshot.setFrameSequence(1);
        assertEquals(RenderFrameCache.ApplyResult.APPLIED,
            cache.apply(new FrameDelta(1, FrameDelta.REASON_RESET, initialSnapshot)));

        ScreenSnapshot reusedTransportSnapshot = createFullNativeBlankSnapshot(0, 2, 63);
        overwriteWithNativeBlankSnapshot(reusedTransportSnapshot, 0, 2, 64, false, new int[] {0});
        reusedTransportSnapshot.setFrameSequence(2);

        assertEquals(RenderFrameCache.ApplyResult.REJECTED_PARTIAL_REQUIRING_FULL_REBUILD,
            cache.apply(new FrameDelta(2, FrameDelta.REASON_RESIZE, reusedTransportSnapshot)));
        assertTrue(cache.isInitialized());

        ScreenSnapshot renderSnapshot = cache.getSnapshotForRender(false, true);
        assertNotNull(renderSnapshot);
        assertEquals(63, renderSnapshot.getColumns());
        assertEquals(63, renderSnapshot.getRow(0).getColumns());
        assertEquals(63, renderSnapshot.getRow(1).getColumns());
    }

    @Test
    public void applyFullRebuildAfterRejectedPartialResizeReinitializesDimensions() {
        RenderFrameCache cache = new RenderFrameCache();

        ScreenSnapshot initialSnapshot = createFullNativeBlankSnapshot(0, 2, 63);
        initialSnapshot.setFrameSequence(1);
        assertEquals(RenderFrameCache.ApplyResult.APPLIED,
            cache.apply(new FrameDelta(1, FrameDelta.REASON_RESET, initialSnapshot)));

        ScreenSnapshot reusedTransportSnapshot = createFullNativeBlankSnapshot(0, 2, 63);
        overwriteWithNativeBlankSnapshot(reusedTransportSnapshot, 0, 2, 64, false, new int[] {0});
        reusedTransportSnapshot.setFrameSequence(2);
        assertEquals(RenderFrameCache.ApplyResult.REJECTED_PARTIAL_REQUIRING_FULL_REBUILD,
            cache.apply(new FrameDelta(2, FrameDelta.REASON_RESIZE, reusedTransportSnapshot)));

        ScreenSnapshot fullResizeSnapshot = createFullNativeBlankSnapshot(0, 2, 64);
        fullResizeSnapshot.setFrameSequence(3);
        assertEquals(RenderFrameCache.ApplyResult.APPLIED,
            cache.apply(new FrameDelta(3, FrameDelta.REASON_RESIZE, fullResizeSnapshot)));

        ScreenSnapshot renderSnapshot = cache.getSnapshotForRender(false, true);
        assertNotNull(renderSnapshot);
        assertEquals(64, renderSnapshot.getColumns());
        assertEquals(64, renderSnapshot.getRow(0).getColumns());
        assertEquals(64, renderSnapshot.getRow(1).getColumns());
    }

    @Test
    public void applyRejectsViewportShiftWithoutDirtyRows() {
        RenderFrameCache cache = new RenderFrameCache();

        ScreenSnapshot initialSnapshot = createFullNativeBlankSnapshot(0, 2, 63);
        initialSnapshot.setFrameSequence(1);
        assertEquals(RenderFrameCache.ApplyResult.APPLIED,
            cache.apply(new FrameDelta(1, FrameDelta.REASON_RESET, initialSnapshot)));

        ScreenSnapshot reusedTransportSnapshot = createFullNativeBlankSnapshot(0, 2, 63);
        overwriteWithNativeBlankSnapshot(reusedTransportSnapshot, 1, 2, 63, false, new int[0]);
        reusedTransportSnapshot.setFrameSequence(2);

        assertEquals(RenderFrameCache.ApplyResult.REJECTED_PARTIAL_REQUIRING_FULL_REBUILD,
            cache.apply(new FrameDelta(2, FrameDelta.REASON_VIEWPORT_SCROLL, reusedTransportSnapshot)));

        ScreenSnapshot renderSnapshot = cache.getSnapshotForRender(false, true);
        assertNotNull(renderSnapshot);
        assertEquals(0, renderSnapshot.getTopRow());
        assertEquals(63, renderSnapshot.getColumns());
    }

    private static ScreenSnapshot createFullNativeBlankSnapshot(int topRow, int rows, int columns) {
        ScreenSnapshot snapshot = new ScreenSnapshot();
        overwriteWithNativeBlankSnapshot(snapshot, topRow, rows, columns, true, new int[0]);
        return snapshot;
    }

    private static void overwriteWithNativeBlankSnapshot(ScreenSnapshot snapshot, int topRow, int rows, int columns,
                                                         boolean fullRebuild, int[] dirtyRows) {
        if (snapshot == null) {
            throw new IllegalArgumentException("snapshot must not be null");
        }
        if (dirtyRows == null) {
            throw new IllegalArgumentException("dirtyRows must not be null");
        }

        ByteBuffer buffer = snapshot.getBuffer().order(ByteOrder.nativeOrder());
        buffer.clear();
        buffer.putInt(SNAPSHOT_MAGIC);
        buffer.putInt(topRow);
        buffer.putInt(rows);
        buffer.putInt(columns);
        buffer.putInt(fullRebuild ? SNAPSHOT_FLAG_FULL_REBUILD : 0);
        buffer.putInt(fullRebuild ? 0 : dirtyRows.length);
        buffer.putInt(0);

        if (!fullRebuild) {
            for (int dirtyRow : dirtyRows) {
                buffer.putInt(dirtyRow);
            }
        }

        int payloadRowCount = fullRebuild ? rows : dirtyRows.length;
        for (int rowIndex = 0; rowIndex < payloadRowCount; rowIndex++) {
            writeBlankRow(buffer, columns);
        }

        snapshot.markNativeSnapshot(buffer.position());
    }

    private static void writeBlankRow(ByteBuffer buffer, int columns) {
        buffer.putInt(0);
        buffer.putInt(0);
        for (int column = 0; column < columns; column++) {
            buffer.putInt(0);
            buffer.putShort((short) 0);
            buffer.put((byte) 1);
            buffer.put((byte) 0);
            buffer.putLong(0L);
        }
    }
}
