package com.termux.terminal;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

import org.junit.Test;

public final class ScreenSnapshotImageBufferTest {

    private static final int SNAPSHOT_MAGIC = 0x54475832;

    @Test
    public void imagePixelsRemainStableAfterNativeStagingBufferReuse() {
        byte[] expectedPixels = {1, 2, 3, 4, 5, 6, 7, 8};
        ScreenSnapshot snapshot = new ScreenSnapshot(256);
        ByteBuffer buffer = snapshot.getBuffer().order(ByteOrder.nativeOrder());
        buffer.clear();
        buffer.putInt(SNAPSHOT_MAGIC);
        buffer.putInt(0);
        buffer.putInt(0);
        buffer.putInt(0);
        buffer.putInt(0);
        buffer.putInt(0);
        buffer.putInt(ScreenSnapshot.SNAPSHOT_METADATA_IMAGES);

        align(buffer);
        buffer.putInt(1);
        buffer.putLong(1L);
        buffer.putInt(7);
        buffer.putInt(11);
        buffer.putLong(1L);
        buffer.putInt(0);
        buffer.putInt(0);
        buffer.putInt(1);
        buffer.putInt(1);
        buffer.putInt(0);
        buffer.putInt(0);
        buffer.putInt(0);
        buffer.putInt(2);
        buffer.putInt(4);
        buffer.putInt(2);
        buffer.putInt(2);
        buffer.putInt(2);
        buffer.putInt(2);
        buffer.putInt(0);
        buffer.putInt(0);
        buffer.putInt(expectedPixels.length);
        align(buffer);
        buffer.put(expectedPixels);

        snapshot.markNativeSnapshot(buffer.position());
        assertArrayEquals(expectedPixels, snapshot.getImagePixelData());

        buffer.clear();
        while (buffer.position() < snapshot.getRequiredBytes()) {
            buffer.put((byte) 0x5A);
        }

        assertArrayEquals(expectedPixels, snapshot.getImagePixelData());
    }

    @Test
    public void imagePlacementWithOverflowingBufferRangeIsSanitized() {
        ScreenSnapshot snapshot = new ScreenSnapshot(256);
        ByteBuffer buffer = snapshot.getBuffer().order(ByteOrder.nativeOrder());
        buffer.clear();
        buffer.putInt(SNAPSHOT_MAGIC);
        buffer.putInt(0);
        buffer.putInt(0);
        buffer.putInt(0);
        buffer.putInt(0);
        buffer.putInt(0);
        buffer.putInt(ScreenSnapshot.SNAPSHOT_METADATA_IMAGES);

        align(buffer);
        buffer.putInt(1);
        buffer.putLong(1L);
        buffer.putInt(0);
        buffer.putInt(0);
        buffer.putLong(1L);
        buffer.putInt(0);
        buffer.putInt(0);
        buffer.putInt(1);
        buffer.putInt(1);
        buffer.putInt(0);
        buffer.putInt(0);
        buffer.putInt(0);
        buffer.putInt(1);
        buffer.putInt(1);
        buffer.putInt(1);
        buffer.putInt(1);
        buffer.putInt(1);
        buffer.putInt(1);
        buffer.putInt(0);
        buffer.putInt(Integer.MAX_VALUE - 5);
        buffer.putInt(10);
        align(buffer);

        snapshot.markNativeSnapshot(buffer.position());

        ScreenSnapshot.ImagePlacement placement = snapshot.getImagePlacement(0);
        assertEquals(0, placement.bufferOffset);
        assertEquals(0, placement.bufferLen);
    }

    private static void align(ByteBuffer buffer) {
        while ((buffer.position() & 7) != 0) {
            buffer.put((byte) 0);
        }
    }
}
