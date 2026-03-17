package com.termux.terminal;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.concurrent.atomic.AtomicLong;

public final class ScreenSnapshot {

    public static final int DEFAULT_CAPACITY_BYTES = 1024 * 1024;

    private static final int BACKING_NONE = 0;
    private static final int BACKING_JAVA = 1;
    private static final int BACKING_NATIVE = 2;

    private static final int NATIVE_SNAPSHOT_MAGIC = 0x54475832;
    private static final int NATIVE_PALETTE_LENGTH = TextStyle.NUM_INDEXED_COLORS;
    private static final int SNAPSHOT_FLAG_FULL_REBUILD = 1;
    static final int SNAPSHOT_METADATA_PALETTE = 1;
    static final int SNAPSHOT_METADATA_RENDER = 1 << 1;
    static final int SNAPSHOT_METADATA_MODE_BITS = 1 << 2;

    private static final AtomicLong NEXT_ROW_GENERATION = new AtomicLong(1);

    private final ByteBuffer mBuffer;
    private ByteBuffer mCachedBuffer;
    private final int[] mPalette = new int[TextStyle.NUM_INDEXED_COLORS];
    private RowSnapshot[] mRowsData = new RowSnapshot[0];
    private int[] mDirtyRows = new int[0];
    private int mBacking = BACKING_NONE;
    private int mTopRow;
    private int mRows;
    private int mColumns;
    private int mRequiredBytes;
    private boolean mFullRebuild;
    private int mDirtyRowCount;
    private int mMetadataFlags;
    private long mFrameSequence;

    // Metadata for rendering
    private int mCursorCol;
    private int mCursorRow;
    private boolean mCursorEnabled;
    private boolean mCursorVisible;
    private int mCursorStyle;
    private boolean mReverseVideo;
    private int mModeBits;

    public ScreenSnapshot() {
        this(DEFAULT_CAPACITY_BYTES);
    }

    public ScreenSnapshot(int capacityBytes) {
        if (capacityBytes <= 0) {
            throw new IllegalArgumentException("capacityBytes must be > 0");
        }

        mBuffer = ByteBuffer.allocateDirect(capacityBytes).order(ByteOrder.nativeOrder());
    }

    ByteBuffer getBuffer() {
        return mBuffer;
    }

    void beginJavaSnapshot(int topRow, int rows, int columns) {
        if (rows < 0) {
            throw new IllegalArgumentException("rows must be >= 0");
        }
        if (columns < 0) {
            throw new IllegalArgumentException("columns must be >= 0");
        }

        ensureRowCapacity(rows);
        mBacking = BACKING_JAVA;
        mTopRow = topRow;
        mRows = rows;
        mColumns = columns;
        mRequiredBytes = 0;
        mFullRebuild = true;
        mDirtyRowCount = 0;
        mMetadataFlags = 0;
        mFrameSequence = 0;
    }

    void finishJavaSnapshot() {
        mRequiredBytes = 0;
    }

    void markNativeSnapshot(int requiredBytes) {
        if (requiredBytes < 0) {
            throw new IllegalArgumentException("requiredBytes must be >= 0");
        }
        if (requiredBytes > mBuffer.capacity()) {
            throw new IllegalStateException("Native snapshot exceeds direct buffer capacity: required=" + requiredBytes + ", capacity=" + mBuffer.capacity());
        }

        mBacking = BACKING_NATIVE;
        mRequiredBytes = requiredBytes;
        parseNativeSnapshot();
    }

    void copyPalette(int[] palette) {
        if (palette == null) {
            throw new IllegalArgumentException("palette must not be null");
        }
        if (palette.length < mPalette.length) {
            throw new IllegalArgumentException("palette length must be >= " + mPalette.length);
        }

        System.arraycopy(palette, 0, mPalette, 0, mPalette.length);
        mMetadataFlags |= SNAPSHOT_METADATA_PALETTE;
    }

    void setRow(int rowIndex, char[] text, int charsUsed, long[] style, boolean lineWrap) {
        if (rowIndex < 0 || rowIndex >= mRows) {
            throw new IllegalArgumentException("rowIndex out of range: " + rowIndex);
        }

        mRowsData[rowIndex].setJava(text, charsUsed, style, mColumns, lineWrap);
    }

    public void setMetadata(int cursorCol, int cursorRow, boolean cursorVisible, int cursorStyle, boolean reverseVideo) {
        mCursorCol = cursorCol;
        mCursorRow = cursorRow;
        mCursorEnabled = cursorVisible;
        mCursorVisible = cursorVisible;
        mCursorStyle = cursorStyle;
        mReverseVideo = reverseVideo;
        mMetadataFlags |= SNAPSHOT_METADATA_RENDER;
    }

    public void setModeBits(int modeBits) {
        mModeBits = modeBits;
        mMetadataFlags |= SNAPSHOT_METADATA_MODE_BITS;
    }

    void applyCursorBlinkState(boolean cursorBlinkingEnabled, boolean cursorBlinkState) {
        mCursorVisible = mCursorEnabled && (!cursorBlinkingEnabled || cursorBlinkState);
    }

    public int getCursorCol() {
        return mCursorCol;
    }

    public int getCursorRow() {
        return mCursorRow;
    }

    public boolean isCursorVisible() {
        return mCursorVisible;
    }

    public boolean isCursorEnabled() {
        return mCursorEnabled;
    }

    public int getCursorStyle() {
        return mCursorStyle;
    }

    public boolean isReverseVideo() {
        return mReverseVideo;
    }

    public int getModeBits() {
        return mModeBits;
    }

    public boolean hasPaletteUpdate() {
        return (mMetadataFlags & SNAPSHOT_METADATA_PALETTE) != 0;
    }

    public boolean hasRenderMetadataUpdate() {
        return (mMetadataFlags & SNAPSHOT_METADATA_RENDER) != 0;
    }

    public boolean hasModeBitsUpdate() {
        return (mMetadataFlags & SNAPSHOT_METADATA_MODE_BITS) != 0;
    }

    public boolean hasJavaBacking() {
        return mBacking == BACKING_JAVA;
    }

    public boolean hasNativeBacking() {
        return mBacking == BACKING_NATIVE;
    }

    public int getTopRow() {
        return mTopRow;
    }

    public int getRows() {
        return mRows;
    }

    public int getColumns() {
        return mColumns;
    }

    public int getRequiredBytes() {
        return mRequiredBytes;
    }

    public int getCapacityBytes() {
        return mBuffer.capacity();
    }

    public boolean isFullRebuild() {
        return mFullRebuild;
    }

    public int getDirtyRowCount() {
        return mDirtyRowCount;
    }

    public int getDirtyRow(int index) {
        if (index < 0 || index >= mDirtyRowCount) {
            throw new IllegalArgumentException("dirty row index out of range: " + index);
        }

        return mDirtyRows[index];
    }

    public long getFrameSequence() {
        return mFrameSequence;
    }

    public int getPaletteColor(int index) {
        if (index < 0 || index >= mPalette.length) {
            throw new IllegalArgumentException("index out of range: " + index);
        }

        return mPalette[index];
    }

    public RowSnapshot getRow(int rowIndex) {
        if (rowIndex < 0 || rowIndex >= mRows) {
            throw new IllegalArgumentException("rowIndex out of range: " + rowIndex);
        }

        return mRowsData[rowIndex];
    }

    public ByteBuffer duplicateBuffer() {
        if (mCachedBuffer == null) {
            mCachedBuffer = mBuffer.duplicate().order(ByteOrder.nativeOrder());
        }
        return mCachedBuffer;
    }

    void setFrameSequence(long frameSequence) {
        mFrameSequence = frameSequence;
    }

    void copyFrom(ScreenSnapshot source) {
        if (source == null) {
            throw new IllegalArgumentException("source must not be null");
        }

        copyCompleteFrameStateFrom(source);
        for (int rowIndex = 0; rowIndex < source.mRows; rowIndex++) {
            copyRowFrom(source, rowIndex, rowIndex);
        }
    }

    void copyPersistentMetadataFrom(ScreenSnapshot source) {
        if (source == null) {
            throw new IllegalArgumentException("source must not be null");
        }

        System.arraycopy(source.mPalette, 0, mPalette, 0, mPalette.length);
        mCursorCol = source.mCursorCol;
        mCursorRow = source.mCursorRow;
        mCursorEnabled = source.mCursorEnabled;
        mCursorVisible = source.mCursorVisible;
        mCursorStyle = source.mCursorStyle;
        mReverseVideo = source.mReverseVideo;
        mModeBits = source.mModeBits;
    }

    void copyFrameStateFrom(ScreenSnapshot source) {
        if (source == null) {
            throw new IllegalArgumentException("source must not be null");
        }

        ensureRowCapacity(source.mRows);
        copyDirtyRowsMetadataFrom(source);
        mBacking = source.mBacking;
        mTopRow = source.mTopRow;
        mRows = source.mRows;
        mColumns = source.mColumns;
        mRequiredBytes = 0;
        mFrameSequence = source.mFrameSequence;
        mMetadataFlags = source.mMetadataFlags;

        if ((source.mMetadataFlags & SNAPSHOT_METADATA_PALETTE) != 0) {
            System.arraycopy(source.mPalette, 0, mPalette, 0, mPalette.length);
        }
        if ((source.mMetadataFlags & SNAPSHOT_METADATA_RENDER) != 0) {
            mCursorCol = source.mCursorCol;
            mCursorRow = source.mCursorRow;
            mCursorEnabled = source.mCursorEnabled;
            mCursorVisible = source.mCursorVisible;
            mCursorStyle = source.mCursorStyle;
            mReverseVideo = source.mReverseVideo;
        }
        if ((source.mMetadataFlags & SNAPSHOT_METADATA_MODE_BITS) != 0) {
            mModeBits = source.mModeBits;
        }
    }

    void copyRowFrom(ScreenSnapshot source, int rowIndex) {
        copyRowFrom(source, rowIndex, rowIndex);
    }

    void copyRowFrom(ScreenSnapshot source, int sourceRowIndex, int targetRowIndex) {
        if (source == null) {
            throw new IllegalArgumentException("source must not be null");
        }
        if (sourceRowIndex < 0 || sourceRowIndex >= source.mRows) {
            throw new IllegalArgumentException("sourceRowIndex out of range: " + sourceRowIndex);
        }
        if (targetRowIndex < 0 || targetRowIndex >= source.mRows) {
            throw new IllegalArgumentException("targetRowIndex out of range: " + targetRowIndex);
        }

        ensureRowCapacity(source.mRows);
        RowSnapshot sourceRow = source.mRowsData[sourceRowIndex];
        RowSnapshot targetRow = mRowsData[targetRowIndex];
        if (sourceRow.mHasCellLayout) {
            targetRow.beginNative(sourceRow.mCharsUsed, sourceRow.mColumns, sourceRow.mLineWrap);
            if (sourceRow.mCharsUsed > 0) {
                System.arraycopy(sourceRow.mText, 0, targetRow.mText, 0, sourceRow.mCharsUsed);
            }
            if (sourceRow.mColumns > 0) {
                System.arraycopy(sourceRow.mStyle, 0, targetRow.mStyle, 0, sourceRow.mColumns);
                System.arraycopy(sourceRow.mCellTextStart, 0, targetRow.mCellTextStart, 0, sourceRow.mColumns);
                System.arraycopy(sourceRow.mCellTextLength, 0, targetRow.mCellTextLength, 0, sourceRow.mColumns);
                System.arraycopy(sourceRow.mCellDisplayWidth, 0, targetRow.mCellDisplayWidth, 0, sourceRow.mColumns);
            }
            targetRow.mContentGeneration = sourceRow.mContentGeneration;
            targetRow.mContentHash = sourceRow.mContentHash;
            return;
        }

        targetRow.setJava(sourceRow.mText, sourceRow.mCharsUsed, sourceRow.mStyle, sourceRow.mColumns, sourceRow.mLineWrap);
        targetRow.mContentGeneration = sourceRow.mContentGeneration;
        targetRow.mContentHash = sourceRow.mContentHash;
    }

    private void copyCompleteFrameStateFrom(ScreenSnapshot source) {
        ensureRowCapacity(source.mRows);
        copyDirtyRowsMetadataFrom(source);
        copyPersistentMetadataFrom(source);
        mBacking = source.mBacking;
        mTopRow = source.mTopRow;
        mRows = source.mRows;
        mColumns = source.mColumns;
        mRequiredBytes = 0;
        mFrameSequence = source.mFrameSequence;
        mMetadataFlags = source.mMetadataFlags;
    }

    private void copyDirtyRowsMetadataFrom(ScreenSnapshot source) {
        mFullRebuild = source.mFullRebuild;
        mDirtyRowCount = source.mDirtyRowCount;
        ensureDirtyRowCapacity(mDirtyRowCount);
        if (mDirtyRowCount > 0) {
            System.arraycopy(source.mDirtyRows, 0, mDirtyRows, 0, mDirtyRowCount);
        }
    }

    private void parseNativeSnapshot() {
        if (mRequiredBytes == 0) {
            ensureRowCapacity(mRows);
            mFullRebuild = false;
            mDirtyRowCount = 0;
            mMetadataFlags = 0;
            return;
        }

        ByteBuffer buffer = duplicateBuffer();
        buffer.position(0);
        buffer.limit(mRequiredBytes);

        int magic = buffer.getInt();
        if (magic != NATIVE_SNAPSHOT_MAGIC) {
            throw new IllegalStateException("Unexpected native snapshot magic: 0x" + Integer.toHexString(magic));
        }

        int topRow = buffer.getInt();
        int rows = buffer.getInt();
        int columns = buffer.getInt();
        int flags = buffer.getInt();
        int dirtyRowCount = buffer.getInt();
        int metadataFlags = buffer.getInt();

        if (rows < 0) {
            throw new IllegalStateException("rows must be >= 0");
        }
        if (columns < 0) {
            throw new IllegalStateException("columns must be >= 0");
        }
        if (dirtyRowCount < 0 || dirtyRowCount > rows) {
            throw new IllegalStateException("Unexpected dirty row count: " + dirtyRowCount + " rows=" + rows);
        }

        int unknownMetadataFlags = metadataFlags & ~(SNAPSHOT_METADATA_PALETTE | SNAPSHOT_METADATA_RENDER | SNAPSHOT_METADATA_MODE_BITS);
        if (unknownMetadataFlags != 0) {
            throw new IllegalStateException("Unexpected native metadata flags: 0x" + Integer.toHexString(unknownMetadataFlags));
        }

        mTopRow = topRow;
        mRows = rows;
        mColumns = columns;
        mFullRebuild = (flags & SNAPSHOT_FLAG_FULL_REBUILD) != 0;
        mDirtyRowCount = dirtyRowCount;
        mMetadataFlags = metadataFlags;
        ensureRowCapacity(rows);
        ensureDirtyRowCapacity(dirtyRowCount);

        if ((metadataFlags & SNAPSHOT_METADATA_PALETTE) != 0) {
            for (int i = 0; i < NATIVE_PALETTE_LENGTH; i++) {
                mPalette[i] = buffer.getInt();
            }
        }

        if ((metadataFlags & SNAPSHOT_METADATA_RENDER) != 0) {
            mCursorCol = buffer.getInt();
            mCursorRow = buffer.getInt();
            mCursorStyle = buffer.getInt();
            mCursorEnabled = buffer.getInt() != 0;
            mCursorVisible = mCursorEnabled;
            mReverseVideo = buffer.getInt() != 0;
        }

        if ((metadataFlags & SNAPSHOT_METADATA_MODE_BITS) != 0) {
            mModeBits = buffer.getInt();
        }

        for (int i = 0; i < dirtyRowCount; i++) {
            int dirtyRow = buffer.getInt();
            if (dirtyRow < 0 || dirtyRow >= rows) {
                throw new IllegalStateException("Dirty row out of range: " + dirtyRow + " rows=" + rows);
            }
            mDirtyRows[i] = dirtyRow;
        }

        int payloadRowCount = mFullRebuild ? rows : dirtyRowCount;
        for (int payloadIndex = 0; payloadIndex < payloadRowCount; payloadIndex++) {
            int rowIndex = mFullRebuild ? payloadIndex : mDirtyRows[payloadIndex];
            int charsUsed = buffer.getInt();
            boolean lineWrap = buffer.getInt() != 0;
            if (charsUsed < 0) {
                throw new IllegalStateException("charsUsed must be >= 0");
            }

            RowSnapshot row = mRowsData[rowIndex];
            row.beginNative(charsUsed, columns, lineWrap);
            for (int column = 0; column < columns; column++) {
                int textStart = buffer.getInt();
                short textLength = buffer.getShort();
                byte displayWidth = buffer.get();
                buffer.get();
                long style = buffer.getLong();

                int unsignedTextLength = textLength & 0xFFFF;
                int unsignedDisplayWidth = displayWidth & 0xFF;
                if (textStart < 0 || textStart > charsUsed) {
                    throw new IllegalStateException("Native row " + rowIndex + " column " + column + " has invalid textStart=" + textStart + " charsUsed=" + charsUsed);
                }
                if (textStart + unsignedTextLength > charsUsed) {
                    throw new IllegalStateException("Native row " + rowIndex + " column " + column + " has invalid text range start=" + textStart + " length=" + unsignedTextLength + " charsUsed=" + charsUsed);
                }
                if (unsignedDisplayWidth > 2) {
                    throw new IllegalStateException("Native row " + rowIndex + " column " + column + " has invalid displayWidth=" + unsignedDisplayWidth);
                }

                row.mCellTextStart[column] = textStart;
                row.mCellTextLength[column] = textLength;
                row.mCellDisplayWidth[column] = displayWidth;
                row.mStyle[column] = style;
            }

            for (int charIndex = 0; charIndex < charsUsed; charIndex++) {
                row.mText[charIndex] = buffer.getChar();
            }
            row.finishNative();
        }
    }

    private void ensureRowCapacity(int rows) {
        if (mRowsData.length >= rows) {
            return;
        }

        RowSnapshot[] newRowsData = new RowSnapshot[rows];
        System.arraycopy(mRowsData, 0, newRowsData, 0, mRowsData.length);
        for (int i = mRowsData.length; i < rows; i++) {
            newRowsData[i] = new RowSnapshot();
        }
        mRowsData = newRowsData;
    }

    private void ensureDirtyRowCapacity(int dirtyRowCount) {
        if (mDirtyRows.length >= dirtyRowCount) {
            return;
        }

        mDirtyRows = new int[dirtyRowCount];
    }

    private void validateNativeRow(int rowIndex, int charsUsed, int columns, int[] cellTextStart, short[] cellTextLength, byte[] cellDisplayWidth) {
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

    public static final class RowSnapshot {

        private char[] mText = new char[0];
        private long[] mStyle = new long[0];
        private int[] mCellTextStart = new int[0];
        private short[] mCellTextLength = new short[0];
        private byte[] mCellDisplayWidth = new byte[0];
        private int mCharsUsed;
        private int mColumns;
        private boolean mLineWrap;
        private boolean mHasCellLayout;
        private long mContentGeneration;
        private long mContentHash;

        private void setJava(char[] text, int charsUsed, long[] style, int columns, boolean lineWrap) {
            if (text == null) {
                throw new IllegalArgumentException("text must not be null");
            }
            if (style == null) {
                throw new IllegalArgumentException("style must not be null");
            }
            if (charsUsed < 0 || charsUsed > text.length) {
                throw new IllegalArgumentException("charsUsed out of range: " + charsUsed);
            }
            if (columns < 0 || columns > style.length) {
                throw new IllegalArgumentException("columns out of range: " + columns);
            }

            ensureTextCapacity(charsUsed);
            ensureStyleCapacity(columns);
            if (charsUsed > 0) {
                System.arraycopy(text, 0, mText, 0, charsUsed);
            }
            if (columns > 0) {
                System.arraycopy(style, 0, mStyle, 0, columns);
            }
            mCharsUsed = charsUsed;
            mColumns = columns;
            mLineWrap = lineWrap;
            mHasCellLayout = false;
            markMutated();
            updateContentHash();
        }

        private void setNative(char[] text, int charsUsed, long[] style, int[] cellTextStart, short[] cellTextLength, byte[] cellDisplayWidth, int columns, boolean lineWrap) {
            if (text == null) {
                throw new IllegalArgumentException("text must not be null");
            }
            if (style == null) {
                throw new IllegalArgumentException("style must not be null");
            }
            if (cellTextStart == null || cellTextLength == null || cellDisplayWidth == null) {
                throw new IllegalArgumentException("native cell layout must not be null");
            }
            if (charsUsed < 0 || charsUsed > text.length) {
                throw new IllegalArgumentException("charsUsed out of range: " + charsUsed);
            }
            if (columns < 0 || columns > style.length) {
                throw new IllegalArgumentException("columns out of range: " + columns);
            }
            if (columns > cellTextStart.length || columns > cellTextLength.length || columns > cellDisplayWidth.length) {
                throw new IllegalArgumentException("native cell layout columns out of range: " + columns);
            }

            beginNative(charsUsed, columns, lineWrap);
            if (charsUsed > 0) {
                System.arraycopy(text, 0, mText, 0, charsUsed);
            }
            if (columns > 0) {
                System.arraycopy(style, 0, mStyle, 0, columns);
                System.arraycopy(cellTextStart, 0, mCellTextStart, 0, columns);
                System.arraycopy(cellTextLength, 0, mCellTextLength, 0, columns);
                System.arraycopy(cellDisplayWidth, 0, mCellDisplayWidth, 0, columns);
            }
            updateContentHash();
        }

        private void beginNative(int charsUsed, int columns, boolean lineWrap) {
            if (charsUsed < 0) {
                throw new IllegalArgumentException("charsUsed must be >= 0");
            }
            if (columns < 0) {
                throw new IllegalArgumentException("columns must be >= 0");
            }

            ensureTextCapacity(charsUsed);
            ensureStyleCapacity(columns);
            ensureCellLayoutCapacity(columns);
            mCharsUsed = charsUsed;
            mColumns = columns;
            mLineWrap = lineWrap;
            mHasCellLayout = true;
            markMutated();
        }

        public char[] getText() {
            return mText;
        }

        public int getCharsUsed() {
            return mCharsUsed;
        }

        public int getColumns() {
            return mColumns;
        }

        public long getStyle(int column) {
            if (column < 0 || column >= mColumns) {
                throw new IllegalArgumentException("column out of range: " + column);
            }

            return mStyle[column];
        }

        public boolean hasCellLayout() {
            return mHasCellLayout;
        }

        public int getCellTextStart(int column) {
            if (column < 0 || column >= mColumns) {
                throw new IllegalArgumentException("column out of range: " + column);
            }

            return mCellTextStart[column];
        }

        public int getCellTextLength(int column) {
            if (column < 0 || column >= mColumns) {
                throw new IllegalArgumentException("column out of range: " + column);
            }

            return mCellTextLength[column] & 0xFFFF;
        }

        public int getCellDisplayWidth(int column) {
            if (column < 0 || column >= mColumns) {
                throw new IllegalArgumentException("column out of range: " + column);
            }

            return mCellDisplayWidth[column] & 0xFF;
        }

        public boolean isLineWrap() {
            return mLineWrap;
        }

        public long getContentGeneration() {
            return mContentGeneration;
        }

        public long getContentHash() {
            return mContentHash;
        }

        private void ensureTextCapacity(int size) {
            if (mText.length >= size) {
                return;
            }

            mText = new char[size];
        }

        private void ensureStyleCapacity(int size) {
            if (mStyle.length >= size) {
                return;
            }

            mStyle = new long[size];
        }

        private void ensureCellLayoutCapacity(int size) {
            if (mCellTextStart.length < size) {
                mCellTextStart = new int[size];
            }
            if (mCellTextLength.length < size) {
                mCellTextLength = new short[size];
            }
            if (mCellDisplayWidth.length < size) {
                mCellDisplayWidth = new byte[size];
            }
        }

        private void finishNative() {
            updateContentHash();
        }

        private void updateContentHash() {
            long hash = 0xcbf29ce484222325L;
            hash = mixHash(hash, mCharsUsed);
            hash = mixHash(hash, mColumns);
            hash = mixHash(hash, mLineWrap ? 1L : 0L);
            hash = mixHash(hash, mHasCellLayout ? 1L : 0L);

            for (int i = 0; i < mCharsUsed; i++) {
                hash = mixHash(hash, mText[i]);
            }
            for (int i = 0; i < mColumns; i++) {
                hash = mixHash(hash, mStyle[i]);
            }
            if (mHasCellLayout) {
                for (int i = 0; i < mColumns; i++) {
                    hash = mixHash(hash, mCellTextStart[i]);
                    hash = mixHash(hash, mCellTextLength[i] & 0xFFFFL);
                    hash = mixHash(hash, mCellDisplayWidth[i] & 0xFFL);
                }
            }

            mContentHash = hash;
        }

        private static long mixHash(long hash, long value) {
            hash ^= value;
            hash *= 0x100000001b3L;
            return hash;
        }

        private void markMutated() {
            mContentGeneration = NEXT_ROW_GENERATION.getAndIncrement();
        }
    }
}
