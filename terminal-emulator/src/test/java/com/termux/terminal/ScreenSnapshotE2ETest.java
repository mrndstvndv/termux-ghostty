package com.termux.terminal;

import org.junit.Test;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Arrays;

import static org.junit.Assert.*;

public class ScreenSnapshotE2ETest {

    private static final boolean USE_CONTIGUOUS_LAYOUT = true;

    // Helper representation of a row's mock state
    private static class MockRow {
        int charsUsed;
        boolean lineWrap;
        int[] cellTextStart;
        short[] cellTextLength;
        byte[] cellDisplayWidth;
        long[] style;
        char[] text;

        public MockRow(int columns) {
            this.charsUsed = 0;
            this.lineWrap = false;
            this.cellTextStart = new int[columns];
            this.cellTextLength = new short[columns];
            this.cellDisplayWidth = new byte[columns];
            this.style = new long[columns];
            this.text = new char[0];
        }

        public MockRow(String content, long[] styles, int columns, boolean wrap) {
            this.charsUsed = content.length();
            this.lineWrap = wrap;
            this.text = content.toCharArray();
            this.cellTextStart = new int[columns];
            this.cellTextLength = new short[columns];
            this.cellDisplayWidth = new byte[columns];
            this.style = new long[columns];
            for (int i = 0; i < columns; i++) {
                if (i < content.length()) {
                    cellTextStart[i] = i;
                    cellTextLength[i] = 1;
                    cellDisplayWidth[i] = 1;
                } else {
                    cellTextStart[i] = content.length();
                    cellTextLength[i] = 0;
                    cellDisplayWidth[i] = 0;
                }
                if (styles != null && i < styles.length) {
                    this.style[i] = styles[i];
                }
            }
        }
    }

    private static int[] createMockPalette() {
        int[] palette = new int[259];
        for (int i = 0; i < 259; i++) {
            palette[i] = 0xFF000000 | (i << 16) | (i << 8) | i;
        }
        return palette;
    }

    private static ByteBuffer serialize(
            int topRow, int rows, int columns, boolean fullRebuild,
            int[] dirtyRows, int metadataFlags, int[] palette,
            int cursorCol, int cursorRow, int cursorStyle, boolean cursorEnabled, boolean reverseVideo,
            int modeBits, MockRow[] rowsData) {

        ByteBuffer buffer = ByteBuffer.allocateDirect(1024 * 1024).order(ByteOrder.nativeOrder());
        buffer.putInt(0x54475832); // magic
        buffer.putInt(topRow);
        buffer.putInt(rows);
        buffer.putInt(columns);
        buffer.putInt(fullRebuild ? 1 : 0);
        buffer.putInt(dirtyRows == null ? 0 : dirtyRows.length);
        buffer.putInt(metadataFlags);

        if ((metadataFlags & 1) != 0) { // SNAPSHOT_METADATA_PALETTE
            for (int i = 0; i < 259; i++) {
                buffer.putInt(palette != null ? palette[i] : 0);
            }
        }

        if ((metadataFlags & 2) != 0) { // SNAPSHOT_METADATA_RENDER
            buffer.putInt(cursorCol);
            buffer.putInt(cursorRow);
            buffer.putInt(cursorStyle);
            buffer.putInt(cursorEnabled ? 1 : 0);
            buffer.putInt(reverseVideo ? 1 : 0);
        }

        if ((metadataFlags & 4) != 0) { // SNAPSHOT_METADATA_MODE_BITS
            buffer.putInt(modeBits);
        }

        int dirtyRowCount = dirtyRows == null ? 0 : dirtyRows.length;
        for (int i = 0; i < dirtyRowCount; i++) {
            buffer.putInt(dirtyRows[i]);
        }

        int payloadRowCount = fullRebuild ? rows : dirtyRowCount;
        for (int payloadIndex = 0; payloadIndex < payloadRowCount; payloadIndex++) {
            int rowIndex = fullRebuild ? payloadIndex : dirtyRows[payloadIndex];
            MockRow mockRow = rowsData[rowIndex];

            if (USE_CONTIGUOUS_LAYOUT) {
                // 1. Align buffer position to 8-byte boundary for the start of the row
                int pos = buffer.position();
                int alignedPos = (pos + 7) & ~7;
                while (buffer.position() < alignedPos) {
                    buffer.put((byte) 0);
                }

                // 2. Read headers
                buffer.putInt(mockRow.charsUsed);
                buffer.putInt(mockRow.lineWrap ? 1 : 0);

                // Compute FNV-1a hash matching the original algorithm and put it into the byte buffer
                long hash = 0xcbf29ce484222325L;
                hash = mixHash(hash, mockRow.charsUsed);
                hash = mixHash(hash, columns);
                hash = mixHash(hash, mockRow.lineWrap ? 1L : 0L);
                hash = mixHash(hash, 1L); // mHasCellLayout is always 1 (true) for serialized rows

                for (int i = 0; i < mockRow.charsUsed; i++) {
                    hash = mixHash(hash, mockRow.text[i]);
                }
                for (int i = 0; i < columns; i++) {
                    hash = mixHash(hash, mockRow.style[i]);
                }
                for (int i = 0; i < columns; i++) {
                    hash = mixHash(hash, mockRow.cellTextStart[i]);
                    hash = mixHash(hash, mockRow.cellTextLength[i] & 0xFFFFL);
                    hash = mixHash(hash, mockRow.cellDisplayWidth[i] & 0xFFL);
                }
                buffer.putLong(hash);

                // 3. Bulk write cell starts (4-byte aligned)
                for (int i = 0; i < columns; i++) {
                    buffer.putInt(mockRow.cellTextStart[i]);
                }

                // 4. Bulk write cell lengths (2-byte aligned)
                for (int i = 0; i < columns; i++) {
                    buffer.putShort(mockRow.cellTextLength[i]);
                }

                // 5. Bulk write cell display widths (1-byte aligned)
                for (int i = 0; i < columns; i++) {
                    buffer.put(mockRow.cellDisplayWidth[i]);
                }

                // 6. Align buffer position to 8-byte boundary for styles array
                int posStyle = buffer.position();
                int alignedPosStyle = (posStyle + 7) & ~7;
                while (buffer.position() < alignedPosStyle) {
                    buffer.put((byte) 0);
                }

                // 7. Bulk write cell styles (8-byte aligned)
                for (int i = 0; i < columns; i++) {
                    buffer.putLong(mockRow.style[i]);
                }

                // 8. Bulk write characters (2-byte aligned)
                for (int i = 0; i < mockRow.charsUsed; i++) {
                    buffer.putChar(mockRow.text[i]);
                }
            } else {
                // Interleaved layout
                buffer.putInt(mockRow.charsUsed);
                buffer.putInt(mockRow.lineWrap ? 1 : 0);

                for (int i = 0; i < columns; i++) {
                    buffer.putInt(mockRow.cellTextStart[i]);
                    buffer.putShort(mockRow.cellTextLength[i]);
                    buffer.put(mockRow.cellDisplayWidth[i]);
                    buffer.put((byte) 0); // padding
                    buffer.putLong(mockRow.style[i]);
                }

                for (int i = 0; i < mockRow.charsUsed; i++) {
                    buffer.putChar(mockRow.text[i]);
                }
            }
        }

        return buffer;
    }

    private static void parse(ScreenSnapshot snapshot, ByteBuffer serializedBuffer, int requiredBytes) {
        if (USE_CONTIGUOUS_LAYOUT) {
            parseContiguous(snapshot, serializedBuffer, requiredBytes);
        } else {
            ByteBuffer snapshotBuf = snapshot.getBuffer();
            snapshotBuf.clear();
            serializedBuffer.position(0);
            serializedBuffer.limit(requiredBytes);
            snapshotBuf.put(serializedBuffer);
            snapshot.markNativeSnapshot(requiredBytes);
        }
    }

    private static void parseContiguous(ScreenSnapshot snapshot, ByteBuffer buffer, int requiredBytes) {
        ByteBuffer buf = buffer.duplicate().order(ByteOrder.nativeOrder());
        buf.position(0);
        buf.limit(requiredBytes);

        int magic = buf.getInt();
        if (magic != 0x54475832) {
            throw new IllegalStateException("Unexpected native snapshot magic: 0x" + Integer.toHexString(magic));
        }

        int topRow = buf.getInt();
        int rows = buf.getInt();
        int columns = buf.getInt();
        int flags = buf.getInt();
        int dirtyRowCount = buf.getInt();
        int metadataFlags = buf.getInt();

        if (rows < 0) {
            throw new IllegalStateException("rows must be >= 0");
        }
        if (columns < 0) {
            throw new IllegalStateException("columns must be >= 0");
        }
        if (dirtyRowCount < 0 || dirtyRowCount > rows) {
            throw new IllegalStateException("Unexpected dirty row count: " + dirtyRowCount + " rows=" + rows);
        }

        int unknownMetadataFlags = metadataFlags & ~(1 | 2 | 4); // SNAPSHOT_METADATA_PALETTE | SNAPSHOT_METADATA_RENDER | SNAPSHOT_METADATA_MODE_BITS
        if (unknownMetadataFlags != 0) {
            throw new IllegalStateException("Unexpected native metadata flags: 0x" + Integer.toHexString(unknownMetadataFlags));
        }

        boolean fullRebuild = (flags & 1) != 0;

        snapshot.beginJavaSnapshot(topRow, rows, columns);

        try {
            java.lang.reflect.Field mFullRebuildField = ScreenSnapshot.class.getDeclaredField("mFullRebuild");
            mFullRebuildField.setAccessible(true);
            mFullRebuildField.setBoolean(snapshot, fullRebuild);

            java.lang.reflect.Field mDirtyRowCountField = ScreenSnapshot.class.getDeclaredField("mDirtyRowCount");
            mDirtyRowCountField.setAccessible(true);
            mDirtyRowCountField.setInt(snapshot, dirtyRowCount);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }

        if ((metadataFlags & 1) != 0) { // SNAPSHOT_METADATA_PALETTE
            int[] palette = new int[259];
            for (int i = 0; i < 259; i++) {
                palette[i] = buf.getInt();
            }
            snapshot.copyPalette(palette);
        }

        int cursorCol = 0, cursorRow = 0, cursorStyle = 0;
        boolean cursorEnabled = false, reverseVideo = false;
        if ((metadataFlags & 2) != 0) { // SNAPSHOT_METADATA_RENDER
            cursorCol = buf.getInt();
            cursorRow = buf.getInt();
            cursorStyle = buf.getInt();
            cursorEnabled = buf.getInt() != 0;
            reverseVideo = buf.getInt() != 0;
            snapshot.setMetadata(cursorCol, cursorRow, cursorEnabled, cursorStyle, reverseVideo);
        }

        if ((metadataFlags & 4) != 0) { // SNAPSHOT_METADATA_MODE_BITS
            int modeBits = buf.getInt();
            snapshot.setModeBits(modeBits);
        }

        int[] dirtyRows = new int[dirtyRowCount];
        for (int i = 0; i < dirtyRowCount; i++) {
            dirtyRows[i] = buf.getInt();
            if (dirtyRows[i] < 0 || dirtyRows[i] >= rows) {
                throw new IllegalStateException("Dirty row out of range: " + dirtyRows[i] + " rows=" + rows);
            }
        }

        try {
            java.lang.reflect.Field mDirtyRowsField = ScreenSnapshot.class.getDeclaredField("mDirtyRows");
            mDirtyRowsField.setAccessible(true);
            int[] dirtyRowsArr = (int[]) mDirtyRowsField.get(snapshot);
            if (dirtyRowsArr == null || dirtyRowsArr.length < dirtyRowCount) {
                dirtyRowsArr = new int[dirtyRowCount];
                mDirtyRowsField.set(snapshot, dirtyRowsArr);
            }
            System.arraycopy(dirtyRows, 0, dirtyRowsArr, 0, dirtyRowCount);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }

        int payloadRowCount = fullRebuild ? rows : dirtyRowCount;
        for (int payloadIndex = 0; payloadIndex < payloadRowCount; payloadIndex++) {
            int rowIndex = fullRebuild ? payloadIndex : dirtyRows[payloadIndex];

            // 1. Align buffer position to 8-byte boundary for the start of the row
            buf.position((buf.position() + 7) & ~7);

            // 2. Read headers
            int charsUsed = buf.getInt();
            boolean lineWrap = buf.getInt() != 0;
            long contentHash = buf.getLong();

            int[] cellTextStart = new int[columns];
            short[] cellTextLength = new short[columns];
            byte[] cellDisplayWidth = new byte[columns];
            long[] style = new long[columns];

            // 3. Bulk read cell starts (4-byte aligned)
            buf.asIntBuffer().get(cellTextStart, 0, columns);
            buf.position(buf.position() + columns * 4);

            // 4. Bulk read cell lengths (2-byte aligned)
            buf.asShortBuffer().get(cellTextLength, 0, columns);
            buf.position(buf.position() + columns * 2);

            // 5. Bulk read cell display widths (1-byte aligned)
            buf.get(cellDisplayWidth, 0, columns);

            // 6. Align buffer position to 8-byte boundary for styles array
            buf.position((buf.position() + 7) & ~7);

            // 7. Bulk read cell styles (8-byte aligned)
            buf.asLongBuffer().get(style, 0, columns);
            buf.position(buf.position() + columns * 8);

            // 8. Bulk read characters (2-byte aligned)
            char[] text = new char[charsUsed];
            buf.asCharBuffer().get(text, 0, charsUsed);
            buf.position(buf.position() + charsUsed * 2);

            // Bounds checks
            validateMockRow(rowIndex, charsUsed, columns, cellTextStart, cellTextLength, cellDisplayWidth);

            setRowNativeFields(snapshot.getRow(rowIndex), text, charsUsed, style, cellTextStart, cellTextLength, cellDisplayWidth, columns, lineWrap, contentHash);
        }

        setBackingNative(snapshot);
    }

    private static void validateMockRow(int rowIndex, int charsUsed, int columns, int[] cellTextStart, short[] cellTextLength, byte[] cellDisplayWidth) {
        for (int column = 0; column < columns; column++) {
            int textStart = cellTextStart[column];
            int textLength = cellTextLength[column] & 0xFFFF;
            int displayWidth = cellDisplayWidth[column] & 0xFF;
            if (textStart < 0 || textStart > charsUsed) {
                throw new IllegalStateException("Native row " + rowIndex + " column " + column + " has invalid textStart=" + textStart + " charsUsed=" + charsUsed);
            }
            if (textLength < 0 || textStart + textLength > charsUsed) {
                throw new IllegalStateException("Native row " + rowIndex + " column " + column + " has invalid text range start=" + textStart + " length=" + textLength + " charsUsed=" + charsUsed);
            }
            if (displayWidth > 2) {
                throw new IllegalStateException("Native row " + rowIndex + " column " + column + " has invalid displayWidth=" + displayWidth);
            }
        }
    }

    private static void setRowNativeFields(ScreenSnapshot.RowSnapshot row, char[] text, int charsUsed, long[] style, int[] cellTextStart, short[] cellTextLength, byte[] cellDisplayWidth, int columns, boolean lineWrap, long contentHash) {
        try {
            java.lang.reflect.Method beginNative = ScreenSnapshot.RowSnapshot.class.getDeclaredMethod("beginNative", int.class, int.class, boolean.class);
            beginNative.setAccessible(true);
            beginNative.invoke(row, charsUsed, columns, lineWrap);

            java.lang.reflect.Field mText = ScreenSnapshot.RowSnapshot.class.getDeclaredField("mText");
            mText.setAccessible(true);
            char[] rowText = (char[]) mText.get(row);
            if (charsUsed > 0) {
                System.arraycopy(text, 0, rowText, 0, charsUsed);
            }

            java.lang.reflect.Field mStyle = ScreenSnapshot.RowSnapshot.class.getDeclaredField("mStyle");
            mStyle.setAccessible(true);
            long[] rowStyle = (long[]) mStyle.get(row);
            if (columns > 0) {
                System.arraycopy(style, 0, rowStyle, 0, columns);
            }

            java.lang.reflect.Field mCellTextStart = ScreenSnapshot.RowSnapshot.class.getDeclaredField("mCellTextStart");
            mCellTextStart.setAccessible(true);
            int[] rowCellTextStart = (int[]) mCellTextStart.get(row);
            System.arraycopy(cellTextStart, 0, rowCellTextStart, 0, columns);

            java.lang.reflect.Field mCellTextLength = ScreenSnapshot.RowSnapshot.class.getDeclaredField("mCellTextLength");
            mCellTextLength.setAccessible(true);
            short[] rowCellTextLength = (short[]) mCellTextLength.get(row);
            System.arraycopy(cellTextLength, 0, rowCellTextLength, 0, columns);

            java.lang.reflect.Field mCellDisplayWidth = ScreenSnapshot.RowSnapshot.class.getDeclaredField("mCellDisplayWidth");
            mCellDisplayWidth.setAccessible(true);
            byte[] rowCellDisplayWidth = (byte[]) mCellDisplayWidth.get(row);
            System.arraycopy(cellDisplayWidth, 0, rowCellDisplayWidth, 0, columns);

            java.lang.reflect.Method finishNative = ScreenSnapshot.RowSnapshot.class.getDeclaredMethod("finishNative");
            finishNative.setAccessible(true);
            finishNative.invoke(row);

            java.lang.reflect.Field mContentHash = ScreenSnapshot.RowSnapshot.class.getDeclaredField("mContentHash");
            mContentHash.setAccessible(true);
            mContentHash.setLong(row, contentHash);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private static long mixHash(long hash, long value) {
        hash ^= value;
        hash *= 0x100000001b3L;
        return hash;
    }

    private static void setBackingNative(ScreenSnapshot snapshot) {
        try {
            java.lang.reflect.Field mBacking = ScreenSnapshot.class.getDeclaredField("mBacking");
            mBacking.setAccessible(true);
            mBacking.set(snapshot, 2); // BACKING_NATIVE
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    // ==========================================
    // TIER 1: FEATURE COVERAGE (30 Tests)
    // ==========================================

    // --- FEATURE 1: Magic Header Validation ---

    @Test
    public void testTier1_F1_1() {
        // Happy path: correct magic header parses successfully
        MockRow[] rows = { new MockRow("A", null, 2, false) };
        ByteBuffer buf = serialize(0, 1, 2, true, null, 0, null, 0, 0, 0, false, false, 0, rows);
        ScreenSnapshot snapshot = new ScreenSnapshot();
        parse(snapshot, buf, buf.position());
        assertTrue(snapshot.hasNativeBacking());
    }

    @Test(expected = IllegalStateException.class)
    public void testTier1_F1_2() {
        // Bad magic header throws IllegalStateException
        ByteBuffer buf = ByteBuffer.allocateDirect(100).order(ByteOrder.nativeOrder());
        buf.putInt(0x12345678); // Incorrect magic
        buf.putInt(0); // topRow
        buf.putInt(1); // rows
        buf.putInt(1); // cols
        buf.putInt(1); // fullRebuild
        buf.putInt(0); // dirtyRowCount
        buf.putInt(0); // metadataFlags
        ScreenSnapshot snapshot = new ScreenSnapshot();
        parse(snapshot, buf, buf.position());
    }

    @Test
    public void testTier1_F1_3() {
        // Magic header parsing with different dimensions
        MockRow[] rows = { new MockRow("Test", null, 4, false) };
        ByteBuffer buf = serialize(0, 1, 4, true, null, 0, null, 0, 0, 0, false, false, 0, rows);
        ScreenSnapshot snapshot = new ScreenSnapshot();
        parse(snapshot, buf, buf.position());
        assertEquals(4, snapshot.getColumns());
    }

    @Test(expected = Exception.class)
    public void testTier1_F1_4() {
        // Magic verification with buffer too small throws exception
        ByteBuffer buf = ByteBuffer.allocateDirect(2).order(ByteOrder.nativeOrder());
        buf.putShort((short) 1);
        ScreenSnapshot snapshot = new ScreenSnapshot();
        parse(snapshot, buf, buf.position());
    }

    @Test
    public void testTier1_F1_5() {
        // Correct magic with metadata flags parses successfully
        MockRow[] rows = { new MockRow("X", null, 1, false) };
        ByteBuffer buf = serialize(0, 1, 1, true, null, 4, null, 0, 0, 0, false, false, 42, rows);
        ScreenSnapshot snapshot = new ScreenSnapshot();
        parse(snapshot, buf, buf.position());
        assertEquals(42, snapshot.getModeBits());
    }

    // --- FEATURE 2: Viewport Dimensions & Metadata Sync ---

    @Test
    public void testTier1_F2_1() {
        // Sync basic dimensions: 80 cols, 24 rows, topRow 0
        MockRow[] rows = new MockRow[24];
        for (int i = 0; i < 24; i++) rows[i] = new MockRow("Row" + i, null, 80, false);
        ByteBuffer buf = serialize(0, 24, 80, true, null, 0, null, 0, 0, 0, false, false, 0, rows);
        ScreenSnapshot snapshot = new ScreenSnapshot();
        parse(snapshot, buf, buf.position());
        assertEquals(24, snapshot.getRows());
        assertEquals(80, snapshot.getColumns());
        assertEquals(0, snapshot.getTopRow());
    }

    @Test
    public void testTier1_F2_2() {
        // Sync non-zero topRow
        MockRow[] rows = { new MockRow("A", null, 2, false) };
        ByteBuffer buf = serialize(100, 1, 2, true, null, 0, null, 0, 0, 0, false, false, 0, rows);
        ScreenSnapshot snapshot = new ScreenSnapshot();
        parse(snapshot, buf, buf.position());
        assertEquals(100, snapshot.getTopRow());
    }

    @Test
    public void testTier1_F2_3() {
        // Verify full rebuild flag is processed correctly
        MockRow[] rows = { new MockRow("A", null, 2, false) };
        ByteBuffer buf = serialize(0, 1, 2, true, null, 0, null, 0, 0, 0, false, false, 0, rows);
        ScreenSnapshot snapshot = new ScreenSnapshot();
        parse(snapshot, buf, buf.position());
        assertTrue(snapshot.isFullRebuild());
    }

    @Test
    public void testTier1_F2_4() {
        // Verify partial update flag with dirty row list
        MockRow[] rows = { new MockRow("A", null, 2, false), new MockRow("B", null, 2, false) };
        int[] dirtyRows = { 1 };
        ByteBuffer buf = serialize(0, 2, 2, false, dirtyRows, 0, null, 0, 0, 0, false, false, 0, rows);
        ScreenSnapshot snapshot = new ScreenSnapshot();
        parse(snapshot, buf, buf.position());
        assertFalse(snapshot.isFullRebuild());
        assertEquals(1, snapshot.getDirtyRowCount());
        assertEquals(1, snapshot.getDirtyRow(0));
    }

    @Test
    public void testTier1_F2_5() {
        // Sync small dimensions (e.g. 10x5)
        MockRow[] rows = new MockRow[5];
        for (int i = 0; i < 5; i++) rows[i] = new MockRow("Hello", null, 10, false);
        ByteBuffer buf = serialize(0, 5, 10, true, null, 0, null, 0, 0, 0, false, false, 0, rows);
        ScreenSnapshot snapshot = new ScreenSnapshot();
        parse(snapshot, buf, buf.position());
        assertEquals(5, snapshot.getRows());
        assertEquals(10, snapshot.getColumns());
    }

    // --- FEATURE 3: Cursor State Synchronization ---

    @Test
    public void testTier1_F3_1() {
        // Cursor position sync
        MockRow[] rows = { new MockRow("A", null, 2, false) };
        ByteBuffer buf = serialize(0, 1, 2, true, null, 2, null, 1, 0, 0, true, false, 0, rows);
        ScreenSnapshot snapshot = new ScreenSnapshot();
        parse(snapshot, buf, buf.position());
        assertEquals(1, snapshot.getCursorCol());
        assertEquals(0, snapshot.getCursorRow());
    }

    @Test
    public void testTier1_F3_2() {
        // Cursor visibility sync
        MockRow[] rows = { new MockRow("A", null, 2, false) };
        ByteBuffer buf = serialize(0, 1, 2, true, null, 2, null, 0, 0, 0, true, false, 0, rows);
        ScreenSnapshot snapshot = new ScreenSnapshot();
        parse(snapshot, buf, buf.position());
        assertTrue(snapshot.isCursorVisible());
        assertTrue(snapshot.isCursorEnabled());
    }

    @Test
    public void testTier1_F3_3() {
        // Cursor style sync
        MockRow[] rows = { new MockRow("A", null, 2, false) };
        ByteBuffer buf = serialize(0, 1, 2, true, null, 2, null, 0, 0, 3, true, false, 0, rows);
        ScreenSnapshot snapshot = new ScreenSnapshot();
        parse(snapshot, buf, buf.position());
        assertEquals(3, snapshot.getCursorStyle());
    }

    @Test
    public void testTier1_F3_4() {
        // Reverse video flag sync
        MockRow[] rows = { new MockRow("A", null, 2, false) };
        ByteBuffer buf = serialize(0, 1, 2, true, null, 2, null, 0, 0, 0, true, true, 0, rows);
        ScreenSnapshot snapshot = new ScreenSnapshot();
        parse(snapshot, buf, buf.position());
        assertTrue(snapshot.isReverseVideo());
    }

    @Test
    public void testTier1_F3_5() {
        // Cursor disabled sync
        MockRow[] rows = { new MockRow("A", null, 2, false) };
        ByteBuffer buf = serialize(0, 1, 2, true, null, 2, null, 0, 0, 0, false, false, 0, rows);
        ScreenSnapshot snapshot = new ScreenSnapshot();
        parse(snapshot, buf, buf.position());
        assertFalse(snapshot.isCursorEnabled());
        assertFalse(snapshot.isCursorVisible());
    }

    // --- FEATURE 4: Mode Bits Configuration ---

    @Test
    public void testTier1_F4_1() {
        // Mode bits zero
        MockRow[] rows = { new MockRow("A", null, 2, false) };
        ByteBuffer buf = serialize(0, 1, 2, true, null, 4, null, 0, 0, 0, false, false, 0, rows);
        ScreenSnapshot snapshot = new ScreenSnapshot();
        parse(snapshot, buf, buf.position());
        assertEquals(0, snapshot.getModeBits());
    }

    @Test
    public void testTier1_F4_2() {
        // Mode bits single bit set
        MockRow[] rows = { new MockRow("A", null, 2, false) };
        ByteBuffer buf = serialize(0, 1, 2, true, null, 4, null, 0, 0, 0, false, false, 0x10, rows);
        ScreenSnapshot snapshot = new ScreenSnapshot();
        parse(snapshot, buf, buf.position());
        assertEquals(0x10, snapshot.getModeBits());
    }

    @Test
    public void testTier1_F4_3() {
        // Mode bits multiple bits set
        MockRow[] rows = { new MockRow("A", null, 2, false) };
        ByteBuffer buf = serialize(0, 1, 2, true, null, 4, null, 0, 0, 0, false, false, 0x15, rows);
        ScreenSnapshot snapshot = new ScreenSnapshot();
        parse(snapshot, buf, buf.position());
        assertEquals(0x15, snapshot.getModeBits());
    }

    @Test
    public void testTier1_F4_4() {
        // Mode bits maximum integer value
        MockRow[] rows = { new MockRow("A", null, 2, false) };
        ByteBuffer buf = serialize(0, 1, 2, true, null, 4, null, 0, 0, 0, false, false, -1, rows);
        ScreenSnapshot snapshot = new ScreenSnapshot();
        parse(snapshot, buf, buf.position());
        assertEquals(-1, snapshot.getModeBits());
    }

    @Test
    public void testTier1_F4_5() {
        // Mode bits ignored when flag not set
        MockRow[] rows = { new MockRow("A", null, 2, false) };
        ByteBuffer buf = serialize(0, 1, 2, true, null, 0, null, 0, 0, 0, false, false, 999, rows);
        ScreenSnapshot snapshot = new ScreenSnapshot();
        parse(snapshot, buf, buf.position());
        assertEquals(0, snapshot.getModeBits());
    }

    // --- FEATURE 5: Contiguous Buffer Serialization Layout ---

    @Test
    public void testTier1_F5_1() {
        // Basic row cell layout synchronization
        MockRow[] rows = { new MockRow("AB", new long[]{10L, 20L}, 2, false) };
        ByteBuffer buf = serialize(0, 1, 2, true, null, 0, null, 0, 0, 0, false, false, 0, rows);
        ScreenSnapshot snapshot = new ScreenSnapshot();
        parse(snapshot, buf, buf.position());
        ScreenSnapshot.RowSnapshot row = snapshot.getRow(0);
        assertEquals(2, row.getColumns());
        assertEquals(10L, row.getStyle(0));
        assertEquals(20L, row.getStyle(1));
    }

    @Test
    public void testTier1_F5_2() {
        // Line wrap flag roundtrip sync
        MockRow[] rows = { new MockRow("A", null, 2, true) };
        ByteBuffer buf = serialize(0, 1, 2, true, null, 0, null, 0, 0, 0, false, false, 0, rows);
        ScreenSnapshot snapshot = new ScreenSnapshot();
        parse(snapshot, buf, buf.position());
        assertTrue(snapshot.getRow(0).isLineWrap());
    }

    @Test
    public void testTier1_F5_3() {
        // Columns text characters map
        MockRow[] rows = { new MockRow("XYZ", null, 3, false) };
        ByteBuffer buf = serialize(0, 1, 3, true, null, 0, null, 0, 0, 0, false, false, 0, rows);
        ScreenSnapshot snapshot = new ScreenSnapshot();
        parse(snapshot, buf, buf.position());
        char[] text = snapshot.getRow(0).getText();
        assertEquals('X', text[0]);
        assertEquals('Y', text[1]);
        assertEquals('Z', text[2]);
    }

    @Test
    public void testTier1_F5_4() {
        // Cell layout starts, lengths mapping
        MockRow row = new MockRow(2);
        row.charsUsed = 4;
        row.text = new char[]{'H', 'e', 'l', 'o'};
        row.cellTextStart[0] = 0;
        row.cellTextLength[0] = 2; // "He"
        row.cellDisplayWidth[0] = 1;
        row.cellTextStart[1] = 2;
        row.cellTextLength[1] = 2; // "lo"
        row.cellDisplayWidth[1] = 1;

        MockRow[] rows = { row };
        ByteBuffer buf = serialize(0, 1, 2, true, null, 0, null, 0, 0, 0, false, false, 0, rows);
        ScreenSnapshot snapshot = new ScreenSnapshot();
        parse(snapshot, buf, buf.position());
        ScreenSnapshot.RowSnapshot parsedRow = snapshot.getRow(0);
        assertEquals(0, parsedRow.getCellTextStart(0));
        assertEquals(2, parsedRow.getCellTextLength(0));
        assertEquals(2, parsedRow.getCellTextStart(1));
        assertEquals(2, parsedRow.getCellTextLength(1));
    }

    @Test
    public void testTier1_F5_5() {
        // Display width mapping (e.g. 1 and 2)
        MockRow row = new MockRow(2);
        row.charsUsed = 2;
        row.text = new char[]{'A', 'B'};
        row.cellTextStart[0] = 0;
        row.cellTextLength[0] = 1;
        row.cellDisplayWidth[0] = 1;
        row.cellTextStart[1] = 1;
        row.cellTextLength[1] = 1;
        row.cellDisplayWidth[1] = 2; // wide display width

        MockRow[] rows = { row };
        ByteBuffer buf = serialize(0, 1, 2, true, null, 0, null, 0, 0, 0, false, false, 0, rows);
        ScreenSnapshot snapshot = new ScreenSnapshot();
        parse(snapshot, buf, buf.position());
        ScreenSnapshot.RowSnapshot parsedRow = snapshot.getRow(0);
        assertEquals(1, parsedRow.getCellDisplayWidth(0));
        assertEquals(2, parsedRow.getCellDisplayWidth(1));
    }

    // --- FEATURE 6: Out-of-Bounds & Malformed Input Hardening ---

    @Test(expected = Exception.class)
    public void testTier1_F6_1() {
        // Malformed negative charsUsed throws exception
        MockRow row = new MockRow("A", null, 1, false);
        row.charsUsed = -5; // Malformed
        MockRow[] rows = { row };
        ByteBuffer buf = serialize(0, 1, 1, true, null, 0, null, 0, 0, 0, false, false, 0, rows);
        ScreenSnapshot snapshot = new ScreenSnapshot();
        parse(snapshot, buf, buf.position());
    }

    @Test(expected = Exception.class)
    public void testTier1_F6_2() {
        // Mismatched dirty row index throws exception
        MockRow[] rows = { new MockRow("A", null, 2, false) };
        int[] dirtyRows = { 5 }; // Index out of bounds (rows count is 1)
        ByteBuffer buf = serialize(0, 1, 2, false, dirtyRows, 0, null, 0, 0, 0, false, false, 0, rows);
        ScreenSnapshot snapshot = new ScreenSnapshot();
        parse(snapshot, buf, buf.position());
    }

    @Test(expected = Exception.class)
    public void testTier1_F6_3() {
        // Cell start offset pointing outside charsUsed throws exception
        MockRow row = new MockRow("A", null, 2, false);
        row.cellTextStart[1] = 10; // Out of bounds for charsUsed = 1
        MockRow[] rows = { row };
        ByteBuffer buf = serialize(0, 1, 2, true, null, 0, null, 0, 0, 0, false, false, 0, rows);
        ScreenSnapshot snapshot = new ScreenSnapshot();
        parse(snapshot, buf, buf.position());
    }

    @Test(expected = Exception.class)
    public void testTier1_F6_4() {
        // Cell range extending beyond charsUsed throws exception
        MockRow row = new MockRow("A", null, 2, false);
        row.cellTextStart[0] = 0;
        row.cellTextLength[0] = 5; // Start + Length = 5 > charsUsed (1)
        MockRow[] rows = { row };
        ByteBuffer buf = serialize(0, 1, 2, true, null, 0, null, 0, 0, 0, false, false, 0, rows);
        ScreenSnapshot snapshot = new ScreenSnapshot();
        parse(snapshot, buf, buf.position());
    }

    @Test(expected = Exception.class)
    public void testTier1_F6_5() {
        // Display width > 2 throws exception
        MockRow row = new MockRow("A", null, 2, false);
        row.cellDisplayWidth[0] = 3; // Invalid display width
        MockRow[] rows = { row };
        ByteBuffer buf = serialize(0, 1, 2, true, null, 0, null, 0, 0, 0, false, false, 0, rows);
        ScreenSnapshot snapshot = new ScreenSnapshot();
        parse(snapshot, buf, buf.position());
    }

    // ==========================================
    // TIER 2: BOUNDARY & CORNER CASES (30 Tests)
    // ==========================================

    // --- FEATURE 1: Magic Header Validation ---

    @Test(expected = Exception.class)
    public void testTier2_F1_1() {
        // Truncated buffer where requiredBytes > actual capacity fails safely
        ByteBuffer buf = ByteBuffer.allocateDirect(2).order(ByteOrder.nativeOrder());
        ScreenSnapshot snapshot = new ScreenSnapshot();
        parse(snapshot, buf, 10);
    }

    @Test(expected = Exception.class)
    public void testTier2_F1_2() {
        // Short buffer throws buffer underflow exception
        ByteBuffer buf = ByteBuffer.allocateDirect(4).order(ByteOrder.nativeOrder());
        buf.putInt(0x54475832);
        ScreenSnapshot snapshot = new ScreenSnapshot();
        parse(snapshot, buf, 4);
    }

    @Test(expected = Exception.class)
    public void testTier2_F1_3() {
        // Correct magic but truncated metadata throws exception
        ByteBuffer buf = ByteBuffer.allocateDirect(10).order(ByteOrder.nativeOrder());
        buf.putInt(0x54475832);
        buf.putInt(0); // topRow
        buf.putShort((short) 1); // Truncated remaining
        ScreenSnapshot snapshot = new ScreenSnapshot();
        parse(snapshot, buf, 10);
    }

    @Test(expected = Exception.class)
    public void testTier2_F1_4() {
        // Magic header offset (off-by-one byte alignment in buffer) throws exception
        ByteBuffer buf = ByteBuffer.allocateDirect(100).order(ByteOrder.nativeOrder());
        buf.put((byte) 0);
        buf.putInt(0x54475832); // Shifted magic
        ScreenSnapshot snapshot = new ScreenSnapshot();
        parse(snapshot, buf, 5);
    }

    @Test(expected = Exception.class)
    public void testTier2_F1_5() {
        // Correct magic but invalid metadata flags set (causes exception)
        ByteBuffer buf = ByteBuffer.allocateDirect(100).order(ByteOrder.nativeOrder());
        buf.putInt(0x54475832);
        buf.putInt(0); // topRow
        buf.putInt(1); // rows
        buf.putInt(1); // columns
        buf.putInt(1); // flags
        buf.putInt(0); // dirtyRowCount
        buf.putInt(0x8); // Invalid metadata flags (throws exception)
        ScreenSnapshot snapshot = new ScreenSnapshot();
        parse(snapshot, buf, 100);
    }

    // --- FEATURE 2: Viewport Dimensions & Metadata Sync ---

    @Test(expected = Exception.class)
    public void testTier2_F2_1() {
        // Columns set to negative
        MockRow[] rows = { new MockRow("A", null, 0, false) };
        ByteBuffer buf = serialize(0, 1, -1, true, null, 0, null, 0, 0, 0, false, false, 0, rows);
        ScreenSnapshot snapshot = new ScreenSnapshot();
        parse(snapshot, buf, buf.position());
    }

    @Test(expected = Exception.class)
    public void testTier2_F2_2() {
        // Rows set to negative
        MockRow[] rows = new MockRow[0];
        ByteBuffer buf = serialize(0, -1, 80, true, null, 0, null, 0, 0, 0, false, false, 0, rows);
        ScreenSnapshot snapshot = new ScreenSnapshot();
        parse(snapshot, buf, buf.position());
    }

    @Test
    public void testTier2_F2_3() {
        // Columns set to large value
        MockRow[] rows = { new MockRow("A", null, 500, false) };
        ByteBuffer buf = serialize(0, 1, 500, true, null, 0, null, 0, 0, 0, false, false, 0, rows);
        ScreenSnapshot snapshot = new ScreenSnapshot();
        parse(snapshot, buf, buf.position());
        assertEquals(500, snapshot.getColumns());
    }

    @Test
    public void testTier2_F2_4() {
        // Rows set to large value
        MockRow[] rows = new MockRow[500];
        for (int i = 0; i < 500; i++) rows[i] = new MockRow("A", null, 2, false);
        ByteBuffer buf = serialize(0, 500, 2, true, null, 0, null, 0, 0, 0, false, false, 0, rows);
        ScreenSnapshot snapshot = new ScreenSnapshot();
        parse(snapshot, buf, buf.position());
        assertEquals(500, snapshot.getRows());
    }

    @Test(expected = Exception.class)
    public void testTier2_F2_5() {
        // Mismatched dirty row count (greater than actual dirty rows array size)
        ByteBuffer buf = ByteBuffer.allocateDirect(100).order(ByteOrder.nativeOrder());
        buf.putInt(0x54475832);
        buf.putInt(0); // topRow
        buf.putInt(2); // rows
        buf.putInt(2); // cols
        buf.putInt(0); // fullRebuild = false
        buf.putInt(5); // dirtyRowCount = 5 (but buffer terminates)
        buf.putInt(0); // metadataFlags
        buf.putInt(0); // only 1 dirty row written
        ScreenSnapshot snapshot = new ScreenSnapshot();
        parse(snapshot, buf, buf.position());
    }

    // --- FEATURE 3: Cursor State Synchronization ---

    @Test
    public void testTier2_F3_1() {
        // Cursor Col set to negative value
        MockRow[] rows = { new MockRow("A", null, 2, false) };
        ByteBuffer buf = serialize(0, 1, 2, true, null, 2, null, -1, 0, 0, true, false, 0, rows);
        ScreenSnapshot snapshot = new ScreenSnapshot();
        parse(snapshot, buf, buf.position());
        assertEquals(-1, snapshot.getCursorCol());
    }

    @Test
    public void testTier2_F3_2() {
        // Cursor Row set to negative value
        MockRow[] rows = { new MockRow("A", null, 2, false) };
        ByteBuffer buf = serialize(0, 1, 2, true, null, 2, null, 0, -1, 0, true, false, 0, rows);
        ScreenSnapshot snapshot = new ScreenSnapshot();
        parse(snapshot, buf, buf.position());
        assertEquals(-1, snapshot.getCursorRow());
    }

    @Test
    public void testTier2_F3_3() {
        // Cursor Col set to viewport column limit and beyond
        MockRow[] rows = { new MockRow("A", null, 2, false) };
        ByteBuffer buf = serialize(0, 1, 2, true, null, 2, null, 100, 0, 0, true, false, 0, rows);
        ScreenSnapshot snapshot = new ScreenSnapshot();
        parse(snapshot, buf, buf.position());
        assertEquals(100, snapshot.getCursorCol());
    }

    @Test
    public void testTier2_F3_4() {
        // Cursor Row set to viewport row limit and beyond
        MockRow[] rows = { new MockRow("A", null, 2, false) };
        ByteBuffer buf = serialize(0, 1, 2, true, null, 2, null, 0, 100, 0, true, false, 0, rows);
        ScreenSnapshot snapshot = new ScreenSnapshot();
        parse(snapshot, buf, buf.position());
        assertEquals(100, snapshot.getCursorRow());
    }

    @Test
    public void testTier2_F3_5() {
        // Alternate cursor styles outside standard enumeration
        MockRow[] rows = { new MockRow("A", null, 2, false) };
        ByteBuffer buf = serialize(0, 1, 2, true, null, 2, null, 0, 0, 99, true, false, 0, rows);
        ScreenSnapshot snapshot = new ScreenSnapshot();
        parse(snapshot, buf, buf.position());
        assertEquals(99, snapshot.getCursorStyle());
    }

    // --- FEATURE 4: Mode Bits Configuration ---

    @Test
    public void testTier2_F4_1() {
        // Set negative mode bits (bitwise representation remains identical)
        MockRow[] rows = { new MockRow("A", null, 2, false) };
        ByteBuffer buf = serialize(0, 1, 2, true, null, 4, null, 0, 0, 0, false, false, -99, rows);
        ScreenSnapshot snapshot = new ScreenSnapshot();
        parse(snapshot, buf, buf.position());
        assertEquals(-99, snapshot.getModeBits());
    }

    @Test
    public void testTier2_F4_2() {
        // Toggle single bit transitions sequentially
        MockRow[] rows = { new MockRow("A", null, 2, false) };
        int[] modes = { 1, 2, 4, 8, 16, 32, 64, 128 };
        for (int m : modes) {
            ByteBuffer buf = serialize(0, 1, 2, true, null, 4, null, 0, 0, 0, false, false, m, rows);
            ScreenSnapshot snapshot = new ScreenSnapshot();
            parse(snapshot, buf, buf.position());
            assertEquals(m, snapshot.getModeBits());
        }
    }

    @Test(expected = Exception.class)
    public void testTier2_F4_3() {
        // Mode bits flag set but buffer underflows before reading
        ByteBuffer buf = ByteBuffer.allocateDirect(40).order(ByteOrder.nativeOrder());
        buf.putInt(0x54475832);
        buf.putInt(0); // topRow
        buf.putInt(1); // rows
        buf.putInt(1); // cols
        buf.putInt(1); // fullRebuild
        buf.putInt(0); // dirtyRowCount
        buf.putInt(4); // metadataFlags = SNAPSHOT_METADATA_MODE_BITS (but we don't write mode bits)
        // Buffer terminates here
        ScreenSnapshot snapshot = new ScreenSnapshot();
        parse(snapshot, buf, buf.position());
    }

    @Test
    public void testTier2_F4_4() {
        // Mode bits matching custom terminal application bits
        MockRow[] rows = { new MockRow("A", null, 2, false) };
        ByteBuffer buf = serialize(0, 1, 2, true, null, 4, null, 0, 0, 0, false, false, 0xABCDEF, rows);
        ScreenSnapshot snapshot = new ScreenSnapshot();
        parse(snapshot, buf, buf.position());
        assertEquals(0xABCDEF, snapshot.getModeBits());
    }

    @Test
    public void testTier2_F4_5() {
        // Mode bits parsed with invalid rendering metadata flag (but correct length)
        MockRow[] rows = { new MockRow("A", null, 2, false) };
        ByteBuffer buf = serialize(0, 1, 2, true, null, 6, null, 0, 0, 0, false, false, 42, rows);
        ScreenSnapshot snapshot = new ScreenSnapshot();
        parse(snapshot, buf, buf.position());
        assertEquals(42, snapshot.getModeBits());
    }

    // --- FEATURE 5: Contiguous Buffer Serialization Layout ---

    @Test
    public void testTier2_F5_1() {
        // Single character row with 0 styles
        MockRow[] rows = { new MockRow("X", new long[]{0L}, 1, false) };
        ByteBuffer buf = serialize(0, 1, 1, true, null, 0, null, 0, 0, 0, false, false, 0, rows);
        ScreenSnapshot snapshot = new ScreenSnapshot();
        parse(snapshot, buf, buf.position());
        assertEquals(0L, snapshot.getRow(0).getStyle(0));
    }

    @Test
    public void testTier2_F5_2() {
        // 8-byte alignment padding boundary (columns=80: requires 0 bytes padding)
        MockRow[] rows = { new MockRow("A", null, 80, false) };
        ByteBuffer buf = serialize(0, 1, 80, true, null, 0, null, 0, 0, 0, false, false, 0, rows);
        ScreenSnapshot snapshot = new ScreenSnapshot();
        parse(snapshot, buf, buf.position());
        assertEquals(80, snapshot.getRow(0).getColumns());
    }

    @Test
    public void testTier2_F5_3() {
        // 8-byte alignment padding boundary (columns=81: requires padding)
        MockRow[] rows = { new MockRow("A", null, 81, false) };
        ByteBuffer buf = serialize(0, 1, 81, true, null, 0, null, 0, 0, 0, false, false, 0, rows);
        ScreenSnapshot snapshot = new ScreenSnapshot();
        parse(snapshot, buf, buf.position());
        assertEquals(81, snapshot.getRow(0).getColumns());
    }

    @Test
    public void testTier2_F5_4() {
        // 8-byte alignment padding boundary (columns=83: requires padding)
        MockRow[] rows = { new MockRow("A", null, 83, false) };
        ByteBuffer buf = serialize(0, 1, 83, true, null, 0, null, 0, 0, 0, false, false, 0, rows);
        ScreenSnapshot snapshot = new ScreenSnapshot();
        parse(snapshot, buf, buf.position());
        assertEquals(83, snapshot.getRow(0).getColumns());
    }

    @Test
    public void testTier2_F5_5() {
        // Row end padding boundary (charsUsed = 1, 2, 3 code units)
        for (int len = 1; len <= 3; len++) {
            char[] txt = new char[len];
            Arrays.fill(txt, 'a');
            MockRow row = new MockRow(2);
            row.charsUsed = len;
            row.text = txt;
            MockRow[] rows = { row };
            ByteBuffer buf = serialize(0, 1, 2, true, null, 0, null, 0, 0, 0, false, false, 0, rows);
            ScreenSnapshot snapshot = new ScreenSnapshot();
            parse(snapshot, buf, buf.position());
            assertEquals(len, snapshot.getRow(0).getCharsUsed());
        }
    }

    // --- FEATURE 6: Out-of-Bounds & Malformed Input Hardening ---

    @Test(expected = Exception.class)
    public void testTier2_F6_1() {
        // Empty character text row but cell layout declares non-zero length cells
        MockRow row = new MockRow(2);
        row.charsUsed = 0;
        row.text = new char[0];
        row.cellTextStart[0] = 0;
        row.cellTextLength[0] = 2; // Declares length 2 but charsUsed is 0
        MockRow[] rows = { row };
        ByteBuffer buf = serialize(0, 1, 2, true, null, 0, null, 0, 0, 0, false, false, 0, rows);
        ScreenSnapshot snapshot = new ScreenSnapshot();
        parse(snapshot, buf, buf.position());
    }

    @Test(expected = Exception.class)
    public void testTier2_F6_2() {
        // Style array index out of bounds during read (trying to read column style outside layout bounds)
        MockRow[] rows = { new MockRow("A", null, 1, false) };
        ByteBuffer buf = serialize(0, 1, 1, true, null, 0, null, 0, 0, 0, false, false, 0, rows);
        ScreenSnapshot snapshot = new ScreenSnapshot();
        parse(snapshot, buf, buf.position());
        snapshot.getRow(0).getStyle(5); // Column index 5 is out of bounds (cols=1)
    }

    @Test(expected = Exception.class)
    public void testTier2_F6_3() {
        // Truncated character payload (buffer terminates before expected characters)
        MockRow row = new MockRow("Hello", null, 5, false);
        MockRow[] rows = { row };
        ByteBuffer buf = serialize(0, 1, 5, true, null, 0, null, 0, 0, 0, false, false, 0, rows);
        int truncatedLen = buf.position() - 4; // Truncate last characters
        ScreenSnapshot snapshot = new ScreenSnapshot();
        parse(snapshot, buf, truncatedLen);
    }

    @Test(expected = Exception.class)
    public void testTier2_F6_4() {
        // Truncated styles array payload
        MockRow row = new MockRow("Hello", null, 5, false);
        MockRow[] rows = { row };
        ByteBuffer buf = serialize(0, 1, 5, true, null, 0, null, 0, 0, 0, false, false, 0, rows);
        int truncatedLen = buf.position() - 30; // Truncate style entries
        ScreenSnapshot snapshot = new ScreenSnapshot();
        parse(snapshot, buf, truncatedLen);
    }

    @Test(expected = Exception.class)
    public void testTier2_F6_5() {
        // Display width set to negative byte (e.g. -1 is parsed as unsigned, which is 255 > 2)
        MockRow row = new MockRow("A", null, 1, false);
        row.cellDisplayWidth[0] = (byte) -1; // Unsigned representation is 255
        MockRow[] rows = { row };
        ByteBuffer buf = serialize(0, 1, 1, true, null, 0, null, 0, 0, 0, false, false, 0, rows);
        ScreenSnapshot snapshot = new ScreenSnapshot();
        parse(snapshot, buf, buf.position());
    }

    // ==========================================
    // TIER 3: CROSS-FEATURE COMBINATIONS (6 Tests)
    // ==========================================

    @Test
    public void testTier3_1() {
        // Combine resize dimensions, full rebuild, and palette updates in a single stream
        int[] palette = createMockPalette();
        MockRow[] rows = { new MockRow("A", null, 3, false), new MockRow("B", null, 3, false) };
        ByteBuffer buf = serialize(0, 2, 3, true, null, 1, palette, 0, 0, 0, false, false, 0, rows);
        ScreenSnapshot snapshot = new ScreenSnapshot();
        parse(snapshot, buf, buf.position());
        assertEquals(3, snapshot.getColumns());
        assertTrue(snapshot.isFullRebuild());
        assertEquals(0xFF000000, snapshot.getPaletteColor(0));
    }

    @Test
    public void testTier3_2() {
        // Scroll viewport (changing topRow) while applying partial updates with multiple dirty rows
        MockRow[] rows = { new MockRow("A", null, 2, false), new MockRow("B", null, 2, false), new MockRow("C", null, 2, false) };
        int[] dirtyRows = { 0, 2 };
        ByteBuffer buf = serialize(10, 3, 2, false, dirtyRows, 0, null, 0, 0, 0, false, false, 0, rows);
        ScreenSnapshot snapshot = new ScreenSnapshot();
        parse(snapshot, buf, buf.position());
        assertEquals(10, snapshot.getTopRow());
        assertFalse(snapshot.isFullRebuild());
        assertEquals(2, snapshot.getDirtyRowCount());
        assertEquals(0, snapshot.getDirtyRow(0));
        assertEquals(2, snapshot.getDirtyRow(1));
    }

    @Test
    public void testTier3_3() {
        // Dense text containing multi-byte surrogate pairs and TrueColor text attributes on a wide grid
        // Emoji: U+1F600 (Grinning Face) is parsed as surrogate pair "\uD83D\uDE00" (2 chars)
        String content = "Hello\uD83D\uDE00World";
        MockRow row = new MockRow(12);
        row.charsUsed = content.length();
        row.text = content.toCharArray();
        // Setup positions: "Hello" (5 chars, 5 cols)
        for (int i = 0; i < 5; i++) {
            row.cellTextStart[i] = i;
            row.cellTextLength[i] = 1;
            row.cellDisplayWidth[i] = 1;
        }
        // Emoji at cell 5 starts at char 5, length 2, display width 2
        row.cellTextStart[5] = 5;
        row.cellTextLength[5] = 2;
        row.cellDisplayWidth[5] = 2;
        // Tail cell 6 starts at char 7, length 0, display width 0
        row.cellTextStart[6] = 7;
        row.cellTextLength[6] = 0;
        row.cellDisplayWidth[6] = 0;
        // "World" starts at cell 7
        for (int i = 7; i < 12; i++) {
            row.cellTextStart[i] = i;
            row.cellTextLength[i] = 1;
            row.cellDisplayWidth[i] = 1;
        }
        row.style[0] = 0xFF123456L; // Custom TrueColor style entry
        MockRow[] rows = { row };

        ByteBuffer buf = serialize(0, 1, 12, true, null, 0, null, 0, 0, 0, false, false, 0, rows);
        ScreenSnapshot snapshot = new ScreenSnapshot();
        parse(snapshot, buf, buf.position());
        ScreenSnapshot.RowSnapshot parsedRow = snapshot.getRow(0);
        assertEquals(5, parsedRow.getCellTextStart(5));
        assertEquals(2, parsedRow.getCellTextLength(5));
        assertEquals(2, parsedRow.getCellDisplayWidth(5));
        assertEquals(0xFF123456L, parsedRow.getStyle(0));
    }

    @Test
    public void testTier3_4() {
        // Empty cell wrapping rows combined with active cursor visibility and blinking states
        MockRow[] rows = { new MockRow("", null, 5, true) };
        ByteBuffer buf = serialize(0, 1, 5, true, null, 2, null, 2, 0, 1, true, false, 0, rows);
        ScreenSnapshot snapshot = new ScreenSnapshot();
        parse(snapshot, buf, buf.position());
        assertTrue(snapshot.getRow(0).isLineWrap());
        assertEquals(2, snapshot.getCursorCol());
        assertTrue(snapshot.isCursorVisible());
    }

    @Test
    public void testTier3_5() {
        // Max dimensions combined with sparse updates (only 1 dirty row)
        MockRow[] rows = new MockRow[100];
        for (int i = 0; i < 100; i++) rows[i] = new MockRow("A", null, 100, false);
        int[] dirtyRows = { 42 };
        ByteBuffer buf = serialize(0, 100, 100, false, dirtyRows, 0, null, 0, 0, 0, false, false, 0, rows);
        ScreenSnapshot snapshot = new ScreenSnapshot();
        parse(snapshot, buf, buf.position());
        assertEquals(100, snapshot.getRows());
        assertEquals(100, snapshot.getColumns());
        assertFalse(snapshot.isFullRebuild());
        assertEquals(1, snapshot.getDirtyRowCount());
        assertEquals(42, snapshot.getDirtyRow(0));
    }

    @Test
    public void testTier3_6() {
        // Multiple metadata changes combined with zero row payload updates (dirtyRowCount = 0)
        MockRow[] rows = new MockRow[5];
        for (int i = 0; i < 5; i++) rows[i] = new MockRow("A", null, 5, false);
        int[] dirtyRows = {};
        int[] palette = createMockPalette();
        ByteBuffer buf = serialize(0, 5, 5, false, dirtyRows, 7, palette, 1, 2, 3, true, true, 42, rows);
        ScreenSnapshot snapshot = new ScreenSnapshot();
        parse(snapshot, buf, buf.position());
        assertEquals(1, snapshot.getCursorCol());
        assertEquals(2, snapshot.getCursorRow());
        assertEquals(42, snapshot.getModeBits());
        assertEquals(0, snapshot.getDirtyRowCount());
    }

    // ==========================================
    // TIER 4: REAL-WORLD APPLICATION SCENARIOS (5 Tests)
    // ==========================================

    @Test
    public void testTier4_1() {
        // Simulate a standard terminal session startup
        // Empty screen, cursor at 0,0, default modes, default colors
        MockRow[] rows = new MockRow[24];
        for (int i = 0; i < 24; i++) rows[i] = new MockRow("", null, 80, false);
        ByteBuffer buf = serialize(0, 24, 80, true, null, 6, createMockPalette(), 0, 0, 0, true, false, 0, rows);
        ScreenSnapshot snapshot = new ScreenSnapshot();
        parse(snapshot, buf, buf.position());
        assertEquals(24, snapshot.getRows());
        assertEquals(80, snapshot.getColumns());
        assertEquals(0, snapshot.getCursorCol());
        assertEquals(0, snapshot.getCursorRow());
        assertTrue(snapshot.isCursorVisible());
    }

    @Test
    public void testTier4_2() {
        // Simulate running a text editor like Vim
        // Dense grid, line wrap flag set, reverse cursor style, specific mode bits
        MockRow[] rows = new MockRow[24];
        for (int i = 0; i < 24; i++) {
            rows[i] = new MockRow("~" + i, null, 80, false);
        }
        rows[0] = new MockRow("Vim Text Editor Screen Line 1", new long[]{1L, 1L, 1L}, 80, true);
        ByteBuffer buf = serialize(0, 24, 80, true, null, 6, createMockPalette(), 10, 0, 2, true, true, 0x1000, rows);
        ScreenSnapshot snapshot = new ScreenSnapshot();
        parse(snapshot, buf, buf.position());
        assertEquals("Vim Text Editor Screen Line 1", new String(snapshot.getRow(0).getText()).trim());
        assertTrue(snapshot.getRow(0).isLineWrap());
        assertEquals(2, snapshot.getCursorStyle());
        assertEquals(0x1000, snapshot.getModeBits());
    }

    @Test
    public void testTier4_3() {
        // Simulate running htop
        // Dense text, frequent color usage, multiple dirty rows, cursor disabled
        MockRow[] rows = new MockRow[40];
        for (int i = 0; i < 40; i++) {
            rows[i] = new MockRow("CPU[|||||||||||||||||] " + i + "%", null, 120, false);
        }
        int[] dirtyRows = { 1, 2, 5, 6, 12, 20 };
        ByteBuffer buf = serialize(0, 40, 120, false, dirtyRows, 6, createMockPalette(), 0, 0, 0, false, false, 0, rows);
        ScreenSnapshot snapshot = new ScreenSnapshot();
        parse(snapshot, buf, buf.position());
        assertEquals(40, snapshot.getRows());
        assertEquals(120, snapshot.getColumns());
        assertFalse(snapshot.isCursorEnabled());
        assertEquals(6, snapshot.getDirtyRowCount());
    }

    @Test
    public void testTier4_4() {
        // Simulate rapid window resizing event sequence
        // Resize sequence: 80x24 -> 120x30 -> 100x25
        int[][] sizes = {{80, 24}, {120, 30}, {100, 25}};
        for (int[] s : sizes) {
            int cols = s[0];
            int rws = s[1];
            MockRow[] rows = new MockRow[rws];
            for (int i = 0; i < rws; i++) rows[i] = new MockRow("ResizedRow" + i, null, cols, false);
            ByteBuffer buf = serialize(0, rws, cols, true, null, 0, null, 0, 0, 0, false, false, 0, rows);
            ScreenSnapshot snapshot = new ScreenSnapshot();
            parse(snapshot, buf, buf.position());
            assertEquals(rws, snapshot.getRows());
            assertEquals(cols, snapshot.getColumns());
        }
    }

    @Test
    public void testTier4_5() {
        // Simulate scrollback history lookup
        // Shift topRow backward by 100, populate historical row data
        MockRow[] rows = new MockRow[30];
        for (int i = 0; i < 30; i++) rows[i] = new MockRow("HistoryRow" + i, null, 80, false);
        ByteBuffer buf = serialize(-100, 30, 80, true, null, 0, null, 0, 0, 0, false, false, 0, rows);
        ScreenSnapshot snapshot = new ScreenSnapshot();
        parse(snapshot, buf, buf.position());
        assertEquals(-100, snapshot.getTopRow());
        assertEquals("HistoryRow15", new String(snapshot.getRow(15).getText()).trim());
    }
}
