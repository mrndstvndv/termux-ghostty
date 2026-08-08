package com.termux.terminal;

import static org.junit.Assert.assertEquals;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

import org.junit.Test;

public final class FrameUpdateKindTest {

    private static final int SNAPSHOT_MAGIC = 0x54475832;

    @Test
    public void classifiesHeaderOnlySnapshotAsUnchanged() {
        assertEquals(FrameUpdateKind.UNCHANGED, createSnapshot(false, 0, 0).getUpdateKind());
    }

    @Test
    public void classifiesFullSnapshotAsFull() {
        assertEquals(FrameUpdateKind.FULL, createSnapshot(true, 0, 0).getUpdateKind());
    }

    @Test
    public void classifiesDirtyRowsAndMetadataAsRows() {
        assertEquals(FrameUpdateKind.ROWS, createSnapshot(false, 1, 0).getUpdateKind());
        assertEquals(FrameUpdateKind.ROWS,
            createSnapshot(false, 0, ScreenSnapshot.SNAPSHOT_METADATA_RENDER).getUpdateKind());
    }

    private static ScreenSnapshot createSnapshot(boolean fullRebuild, int dirtyRowCount,
                                                 int metadataFlags) {
        ScreenSnapshot snapshot = new ScreenSnapshot();
        ByteBuffer buffer = snapshot.getBuffer().order(ByteOrder.nativeOrder());
        buffer.clear();
        buffer.putInt(SNAPSHOT_MAGIC);
        buffer.putInt(0);
        buffer.putInt(dirtyRowCount > 0 ? 1 : 0);
        buffer.putInt(0);
        buffer.putInt(fullRebuild ? 1 : 0);
        buffer.putInt(dirtyRowCount);
        buffer.putInt(metadataFlags);
        if ((metadataFlags & ScreenSnapshot.SNAPSHOT_METADATA_RENDER) != 0) {
            buffer.putInt(0);
            buffer.putInt(0);
            buffer.putInt(0);
            buffer.putInt(0);
            buffer.putInt(0);
        }
        if (dirtyRowCount > 0) {
            buffer.putInt(0);
            while ((buffer.position() & 7) != 0) {
                buffer.put((byte) 0);
            }
            buffer.putInt(0);
            buffer.putInt(0);
            buffer.putLong(0L);
        }
        snapshot.markNativeSnapshot(buffer.position());
        return snapshot;
    }
}
