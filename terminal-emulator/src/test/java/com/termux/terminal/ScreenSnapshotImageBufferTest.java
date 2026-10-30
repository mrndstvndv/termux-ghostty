package com.termux.terminal;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

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

    @Test
    public void renderFrameCacheRetainsPixelBufferAcrossPartialScrollFrameWithZeroBufferLen() {
        byte[] expectedPixels = {10, 20, 30, 40, 50, 60, 70, 80};
        RenderFrameCache cache = new RenderFrameCache();

        // 1. Initial full snapshot with non-zero pixel buffer
        ScreenSnapshot initialSnapshot = new ScreenSnapshot(1024);
        ByteBuffer buf1 = initialSnapshot.getBuffer().order(ByteOrder.nativeOrder());
        buf1.clear();
        buf1.putInt(SNAPSHOT_MAGIC);
        buf1.putInt(0); // topRow = 0
        buf1.putInt(2); // rows = 2
        buf1.putInt(10); // cols = 10
        buf1.putInt(1); // fullRebuild = 1
        buf1.putInt(0); // dirtyRowCount = 0
        buf1.putInt(ScreenSnapshot.SNAPSHOT_METADATA_IMAGES);
        for (int r = 0; r < 2; r++) {
            align(buf1);
            writeBlankRow(buf1, 10);
        }
        align(buf1);
        buf1.putInt(1); // imageCount
        buf1.putLong(1L); // generation
        writeImagePlacement(buf1, 42, 1, 1L, 0, 0, 0, expectedPixels.length);
        align(buf1);
        buf1.put(expectedPixels);

        initialSnapshot.markNativeSnapshot(buf1.position());
        initialSnapshot.setFrameSequence(1);

        assertEquals(RenderFrameCache.ApplyResult.APPLIED,
            cache.apply(new FrameDelta(1, FrameDelta.REASON_RESET, initialSnapshot)));

        ScreenSnapshot frame1 = cache.getSnapshotForRender(false, true);
        assertNotNull(frame1);
        assertEquals(1, frame1.getImageCount());
        assertEquals(0, frame1.getImagePlacement(0).viewportRow);
        assertEquals(expectedPixels.length, frame1.getImagePlacement(0).bufferLen);
        assertArrayEquals(expectedPixels, frame1.getImagePixelData());

        // 2. Partial scroll snapshot: topRow = 1, fullRebuild = false, 1 dirty row,
        // SNAPSHOT_METADATA_IMAGES with bufferLen = 0 and 0 trailing pixel bytes.
        ScreenSnapshot scrollSnapshot = new ScreenSnapshot(1024);
        ByteBuffer buf2 = scrollSnapshot.getBuffer().order(ByteOrder.nativeOrder());
        buf2.clear();
        buf2.putInt(SNAPSHOT_MAGIC);
        buf2.putInt(1); // topRow = 1 (topRowDelta = +1)
        buf2.putInt(2); // rows = 2
        buf2.putInt(10); // cols = 10
        buf2.putInt(0); // fullRebuild = 0
        buf2.putInt(1); // dirtyRowCount = 1
        buf2.putInt(ScreenSnapshot.SNAPSHOT_METADATA_IMAGES);
        buf2.putInt(1); // dirty row index = 1
        align(buf2);
        writeBlankRow(buf2, 10);
        align(buf2);
        buf2.putInt(1); // imageCount
        buf2.putLong(1L); // generation
        writeImagePlacement(buf2, 42, 1, 1L, 0, 1, 0, 0);
        align(buf2);

        scrollSnapshot.markNativeSnapshot(buf2.position());
        scrollSnapshot.setFrameSequence(2);

        assertEquals(RenderFrameCache.ApplyResult.APPLIED,
            cache.apply(new FrameDelta(2, FrameDelta.REASON_VIEWPORT_SCROLL, scrollSnapshot)));

        ScreenSnapshot frame2 = cache.getSnapshotForRender(false, true);
        assertNotNull(frame2);
        assertEquals(1, frame2.getImageCount());
        ScreenSnapshot.ImagePlacement p2 = frame2.getImagePlacement(0);
        assertEquals(42, p2.imageId);
        assertEquals(1, p2.viewportRow); // updated viewport row
        assertEquals(0, p2.bufferOffset); // preserved
        assertEquals(expectedPixels.length, p2.bufferLen); // preserved
        assertNotNull(frame2.getImagePixelBuffer());
        assertArrayEquals(expectedPixels, frame2.getImagePixelData());
    }

    @Test
    public void renderFrameCachePreservesImageStateWhenPartialFrameOmitsImageMetadata() {
        byte[] expectedPixels = {10, 20, 30, 40, 50, 60, 70, 80};
        RenderFrameCache cache = new RenderFrameCache();

        // 1. Initial full snapshot with image
        ScreenSnapshot initialSnapshot = new ScreenSnapshot(1024);
        ByteBuffer buf1 = initialSnapshot.getBuffer().order(ByteOrder.nativeOrder());
        buf1.clear();
        buf1.putInt(SNAPSHOT_MAGIC);
        buf1.putInt(0);
        buf1.putInt(2);
        buf1.putInt(10);
        buf1.putInt(1);
        buf1.putInt(0);
        buf1.putInt(ScreenSnapshot.SNAPSHOT_METADATA_IMAGES);
        for (int r = 0; r < 2; r++) {
            align(buf1);
            writeBlankRow(buf1, 10);
        }
        align(buf1);
        buf1.putInt(1);
        buf1.putLong(1L);
        writeImagePlacement(buf1, 42, 1, 1L, 0, 0, 0, expectedPixels.length);
        align(buf1);
        buf1.put(expectedPixels);

        initialSnapshot.markNativeSnapshot(buf1.position());
        initialSnapshot.setFrameSequence(1);
        assertEquals(RenderFrameCache.ApplyResult.APPLIED,
            cache.apply(new FrameDelta(1, FrameDelta.REASON_RESET, initialSnapshot)));

        // 2. Partial frame (e.g. cursor blink / text change) WITHOUT SNAPSHOT_METADATA_IMAGES flag
        ScreenSnapshot partialSnapshot = new ScreenSnapshot(1024);
        ByteBuffer buf2 = partialSnapshot.getBuffer().order(ByteOrder.nativeOrder());
        buf2.clear();
        buf2.putInt(SNAPSHOT_MAGIC);
        buf2.putInt(0); // topRow = 0
        buf2.putInt(2);
        buf2.putInt(10);
        buf2.putInt(0); // fullRebuild = 0
        buf2.putInt(1); // dirtyRowCount = 1
        buf2.putInt(ScreenSnapshot.SNAPSHOT_METADATA_RENDER); // NO IMAGES flag!
        buf2.putInt(0); // dirtyRow = 0
        // Render metadata (cursor, etc.)
        buf2.putInt(1); // cursorCol
        buf2.putInt(0); // cursorRow
        buf2.putInt(0); // cursorStyle
        buf2.putInt(1); // cursorEnabled
        buf2.putInt(0); // reverseVideo
        align(buf2);
        writeBlankRow(buf2, 10);

        partialSnapshot.markNativeSnapshot(buf2.position());
        partialSnapshot.setFrameSequence(2);
        assertEquals(RenderFrameCache.ApplyResult.APPLIED,
            cache.apply(new FrameDelta(2, FrameDelta.REASON_CURSOR_BLINK, partialSnapshot)));

        ScreenSnapshot frame2 = cache.getSnapshotForRender(false, true);
        assertNotNull(frame2);
        assertTrue(frame2.hasImageUpdate());
        assertEquals(1, frame2.getImageCount());
        ScreenSnapshot.ImagePlacement p2 = frame2.getImagePlacement(0);
        assertEquals(42, p2.imageId);
        assertEquals(0, p2.bufferOffset);
        assertEquals(expectedPixels.length, p2.bufferLen);
        assertArrayEquals(expectedPixels, frame2.getImagePixelData());
    }

    /**
     * Records actual behavior: once image 11 drops out of a partial frame and later returns with
     * bufferLen=0, see the assertions at the end for what the cache does with it.
     */
    @Test
    public void renderFrameCacheTwoImagesDedupedThenOneDroppedThenReturnsWithZeroBufferLen() {
        byte[] pixels = {1, 2, 3, 4, 5, 6, 7, 8, 11, 12, 13, 14, 15, 16, 17, 18};
        RenderFrameCache cache = new RenderFrameCache();

        // 1. Full frame, two placements sharing one pixel buffer.
        ScreenSnapshot full = buildImageFrame(1, true, 0,
            new int[][] {{11, 0, 0, 8}, {12, 1, 8, 8}}, pixels);
        full.setFrameSequence(1);
        assertEquals(RenderFrameCache.ApplyResult.APPLIED,
            cache.apply(new FrameDelta(1, FrameDelta.REASON_RESET, full)));
        ScreenSnapshot f1 = cache.getSnapshotForRender(false, true);
        assertNotNull(f1);
        assertEquals(2, f1.getImageCount());
        assertArrayEquals(pixels, f1.getImagePixelData());

        // 2. Partial scroll frame: both placements with bufferLen=0, no pixel bytes, new rows.
        ScreenSnapshot scroll = buildImageFrame(2, false, 1,
            new int[][] {{11, 1, 0, 0}, {12, 2, 0, 0}}, null);
        scroll.setFrameSequence(2);
        assertEquals(RenderFrameCache.ApplyResult.APPLIED,
            cache.apply(new FrameDelta(2, FrameDelta.REASON_VIEWPORT_SCROLL, scroll)));
        ScreenSnapshot f2 = cache.getSnapshotForRender(false, true);
        assertNotNull(f2);
        assertEquals(2, f2.getImageCount());
        for (int i = 0; i < 2; i++) {
            ScreenSnapshot.ImagePlacement p = f2.getImagePlacement(i);
            assertEquals(i == 0 ? 11 : 12, p.imageId);
            assertEquals(i + 1, p.viewportRow);
            assertEquals(i == 0 ? 0 : 8, p.bufferOffset);
            assertEquals(8, p.bufferLen);
        }
        assertArrayEquals(pixels, f2.getImagePixelData());

        // 3. Partial frame where only image 12 remains.
        ScreenSnapshot only12 = buildImageFrame(3, false, 2,
            new int[][] {{12, 1, 0, 0}}, null);
        only12.setFrameSequence(3);
        assertEquals(RenderFrameCache.ApplyResult.APPLIED,
            cache.apply(new FrameDelta(3, FrameDelta.REASON_VIEWPORT_SCROLL, only12)));
        ScreenSnapshot f3 = cache.getSnapshotForRender(false, true);
        assertNotNull(f3);
        assertEquals(1, f3.getImageCount());
        assertEquals(12, f3.getImagePlacement(0).imageId);
        assertEquals(8, f3.getImagePlacement(0).bufferOffset);
        assertEquals(8, f3.getImagePlacement(0).bufferLen);

        // 4. Image 11 returns with bufferLen=0.
        ScreenSnapshot back11 = buildImageFrame(4, false, 1,
            new int[][] {{11, 0, 0, 0}, {12, 1, 0, 0}}, null);
        back11.setFrameSequence(4);
        assertEquals(RenderFrameCache.ApplyResult.APPLIED,
            cache.apply(new FrameDelta(4, FrameDelta.REASON_VIEWPORT_SCROLL, back11)));
        ScreenSnapshot f4 = cache.getSnapshotForRender(false, true);
        assertNotNull(f4);
        StringBuilder actual = new StringBuilder("count=" + f4.getImageCount());
        for (int i = 0; i < f4.getImageCount(); i++) {
            ScreenSnapshot.ImagePlacement p = f4.getImagePlacement(i);
            actual.append(" [id=").append(p.imageId).append(" off=").append(p.bufferOffset)
                .append(" len=").append(p.bufferLen).append("]");
        }
        // Actual behavior: image 11 was dropped from the cache in step 3, so it cannot be matched
        // and stays at bufferOffset=0/bufferLen=0 (nothing to render); image 12 keeps its range.
        assertEquals(actual.toString(), 2, f4.getImageCount());
        assertEquals(actual.toString(), 11, f4.getImagePlacement(0).imageId);
        assertEquals(actual.toString(), 0, f4.getImagePlacement(0).bufferLen);
        assertEquals(actual.toString(), 12, f4.getImagePlacement(1).imageId);
        assertEquals(actual.toString(), 8, f4.getImagePlacement(1).bufferOffset);
        assertEquals(actual.toString(), 8, f4.getImagePlacement(1).bufferLen);
    }

    /** Builds a frame (rows=2, cols=10) with images; placements are {imageId, viewportRow, off, len}. */
    private static ScreenSnapshot buildImageFrame(
        long generation, boolean full, int topRow, int[][] placements, byte[] pixels
    ) {
        ScreenSnapshot snapshot = new ScreenSnapshot(2048);
        ByteBuffer buf = snapshot.getBuffer().order(ByteOrder.nativeOrder());
        buf.clear();
        buf.putInt(SNAPSHOT_MAGIC);
        buf.putInt(topRow);
        buf.putInt(2);
        buf.putInt(10);
        buf.putInt(full ? 1 : 0);
        buf.putInt(full ? 0 : 1); // partial frames carry one dirty row (required when topRow moves)
        buf.putInt(ScreenSnapshot.SNAPSHOT_METADATA_IMAGES);
        if (full) {
            for (int r = 0; r < 2; r++) {
                align(buf);
                writeBlankRow(buf, 10);
            }
        } else {
            buf.putInt(1); // dirty row index
            align(buf);
            writeBlankRow(buf, 10);
        }
        align(buf);
        buf.putInt(placements.length);
        buf.putLong(generation);
        for (int[] p : placements) {
            writeImagePlacement(buf, p[0], 1, 1L, 0, p[1], p[2], p[3]);
        }
        align(buf);
        if (pixels != null) {
            buf.put(pixels);
        }
        snapshot.markNativeSnapshot(buf.position());
        return snapshot;
    }

    private static void writeImagePlacement(
        ByteBuffer buffer,
        int imageId,
        int placementId,
        long imageGeneration,
        int viewportCol,
        int viewportRow,
        int bufferOffset,
        int bufferLen
    ) {
        buffer.putInt(imageId);
        buffer.putInt(placementId);
        buffer.putLong(imageGeneration);
        buffer.putInt(viewportCol);
        buffer.putInt(viewportRow);
        buffer.putInt(1); // colSpan
        buffer.putInt(1); // rowSpan
        buffer.putInt(0); // zIndex
        buffer.putInt(0); // srcX
        buffer.putInt(0); // srcY
        buffer.putInt(2); // srcWidth
        buffer.putInt(4); // srcHeight
        buffer.putInt(2); // destWidthPx
        buffer.putInt(2); // destHeightPx
        buffer.putInt(2); // imageWidth
        buffer.putInt(2); // imageHeight
        buffer.putInt(0); // pixelFormat
        buffer.putInt(bufferOffset);
        buffer.putInt(bufferLen);
    }

    private static void writeBlankRow(ByteBuffer buffer, int columns) {
        // Header: charsUsed=0, lineWrap=false, contentHash=0
        buffer.putInt(0);
        buffer.putInt(0);
        buffer.putLong(0L);

        // Contiguous Cell Starts (i32 * columns)
        for (int column = 0; column < columns; column++) {
            buffer.putInt(0);
        }

        // Contiguous Cell Lengths (u16 * columns)
        for (int column = 0; column < columns; column++) {
            buffer.putShort((short) 0);
        }

        // Contiguous Cell Display Widths (u8 * columns)
        for (int column = 0; column < columns; column++) {
            buffer.put((byte) 1);
        }

        // Align to 8-byte boundary before styles array
        align(buffer);

        // Contiguous Cell Styles (u64 * columns)
        for (int column = 0; column < columns; column++) {
            buffer.putLong(0L);
        }
    }

    private static void align(ByteBuffer buffer) {
        while ((buffer.position() & 7) != 0) {
            buffer.put((byte) 0);
        }
    }
}
