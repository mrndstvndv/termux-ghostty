package com.termux.terminal;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;

public final class ViewportLinkSnapshot {

    public static final int DEFAULT_CAPACITY_BYTES = 256 * 1024;

    private static final int NATIVE_SNAPSHOT_MAGIC = 0x54474c31;
    private static final Segment[] EMPTY_SEGMENTS = new Segment[0];

    private final ByteBuffer mBuffer;
    private ByteBuffer mCachedBuffer;
    private long mFrameSequence;
    private int mTopRow;
    private int mRows;
    private int mColumns;
    private int mRequiredBytes;
    private Segment[] mSegments = EMPTY_SEGMENTS;

    public ViewportLinkSnapshot() {
        this(DEFAULT_CAPACITY_BYTES);
    }

    ViewportLinkSnapshot(int capacityBytes) {
        if (capacityBytes <= 0) {
            throw new IllegalArgumentException("capacityBytes must be > 0");
        }

        mBuffer = ByteBuffer.allocateDirect(capacityBytes).order(ByteOrder.nativeOrder());
    }

    public static ViewportLinkSnapshot create(long frameSequence, int topRow, int rows, int columns,
                                              Segment[] segments) {
        ViewportLinkSnapshot snapshot = new ViewportLinkSnapshot(1);
        snapshot.setParsedState(topRow, rows, columns, segments);
        snapshot.setFrameSequence(frameSequence);
        return snapshot;
    }

    static ViewportLinkSnapshot createEmpty(long frameSequence, int topRow, int rows, int columns) {
        return create(frameSequence, topRow, rows, columns, EMPTY_SEGMENTS);
    }

    ByteBuffer getBuffer() {
        return mBuffer;
    }

    int getCapacityBytes() {
        return mBuffer.capacity();
    }

    void markNativeSnapshot(int requiredBytes) {
        if (requiredBytes < 0) {
            throw new IllegalArgumentException("requiredBytes must be >= 0");
        }
        if (requiredBytes > mBuffer.capacity()) {
            throw new IllegalStateException("Native viewport link snapshot exceeds direct buffer capacity: required="
                + requiredBytes + ", capacity=" + mBuffer.capacity());
        }

        mRequiredBytes = requiredBytes;
        parseNativeSnapshot();
    }

    void setFrameSequence(long frameSequence) {
        mFrameSequence = frameSequence;
    }

    public long getFrameSequence() {
        return mFrameSequence;
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

    public int getSegmentCount() {
        return mSegments.length;
    }

    public Segment getSegment(int index) {
        if (index < 0 || index >= mSegments.length) {
            throw new IllegalArgumentException("segment index out of range: " + index);
        }

        return mSegments[index];
    }

    public boolean isCompatibleWith(ScreenSnapshot snapshot) {
        if (snapshot == null) {
            throw new IllegalArgumentException("snapshot must not be null");
        }

        return mFrameSequence == snapshot.getFrameSequence()
            && mTopRow == snapshot.getTopRow()
            && mRows == snapshot.getRows()
            && mColumns == snapshot.getColumns();
    }

    private void setParsedState(int topRow, int rows, int columns, Segment[] segments) {
        if (rows < 0) {
            throw new IllegalArgumentException("rows must be >= 0");
        }
        if (columns < 0) {
            throw new IllegalArgumentException("columns must be >= 0");
        }
        if (segments == null) {
            throw new IllegalArgumentException("segments must not be null");
        }

        mTopRow = topRow;
        mRows = rows;
        mColumns = columns;
        mRequiredBytes = 0;
        mSegments = segments.length == 0 ? EMPTY_SEGMENTS : segments.clone();
    }

    private void parseNativeSnapshot() {
        ByteBuffer buffer = duplicateBuffer();
        buffer.position(0);
        buffer.limit(mRequiredBytes);

        int magic = buffer.getInt();
        if (magic != NATIVE_SNAPSHOT_MAGIC) {
            throw new IllegalStateException("Unexpected viewport link snapshot magic: 0x"
                + Integer.toHexString(magic));
        }

        int topRow = buffer.getInt();
        int rows = buffer.getInt();
        int columns = buffer.getInt();
        int segmentCount = buffer.getInt();
        int stringTableBytes = buffer.getInt();

        if (rows < 0) {
            throw new IllegalStateException("rows must be >= 0");
        }
        if (columns < 0) {
            throw new IllegalStateException("columns must be >= 0");
        }
        if (segmentCount < 0) {
            throw new IllegalStateException("segmentCount must be >= 0");
        }
        if (stringTableBytes < 0) {
            throw new IllegalStateException("stringTableBytes must be >= 0");
        }

        int recordBytes = segmentCount * (5 * Integer.BYTES);
        int expectedBytes = (6 * Integer.BYTES) + recordBytes + stringTableBytes;
        if (expectedBytes != mRequiredBytes) {
            throw new IllegalStateException("Unexpected viewport link snapshot size: required="
                + mRequiredBytes + ", parsed=" + expectedBytes);
        }

        int[] rowsBySegment = new int[segmentCount];
        int[] startColumns = new int[segmentCount];
        int[] endColumns = new int[segmentCount];
        int[] stringOffsets = new int[segmentCount];
        int[] stringLengths = new int[segmentCount];

        for (int index = 0; index < segmentCount; index++) {
            int row = buffer.getInt();
            int startColumn = buffer.getInt();
            int endColumnExclusive = buffer.getInt();
            int stringOffset = buffer.getInt();
            int stringLength = buffer.getInt();

            if (row < 0 || row >= rows) {
                throw new IllegalStateException("Segment row out of range: " + row + " rows=" + rows);
            }
            if (startColumn < 0 || startColumn >= columns) {
                throw new IllegalStateException("Segment start column out of range: " + startColumn
                    + " columns=" + columns);
            }
            if (endColumnExclusive <= startColumn || endColumnExclusive > columns) {
                throw new IllegalStateException("Segment end column out of range: start=" + startColumn
                    + " end=" + endColumnExclusive + " columns=" + columns);
            }
            if (stringOffset < 0 || stringOffset > stringTableBytes) {
                throw new IllegalStateException("Segment string offset out of range: " + stringOffset
                    + " stringTableBytes=" + stringTableBytes);
            }
            if (stringLength < 0 || stringOffset > stringTableBytes - stringLength) {
                throw new IllegalStateException("Segment string length out of range: offset=" + stringOffset
                    + " length=" + stringLength + " stringTableBytes=" + stringTableBytes);
            }

            rowsBySegment[index] = row;
            startColumns[index] = startColumn;
            endColumns[index] = endColumnExclusive;
            stringOffsets[index] = stringOffset;
            stringLengths[index] = stringLength;
        }

        byte[] stringTable = new byte[stringTableBytes];
        buffer.get(stringTable);

        Segment[] segments = segmentCount == 0 ? EMPTY_SEGMENTS : new Segment[segmentCount];
        for (int index = 0; index < segmentCount; index++) {
            String url = new String(stringTable, stringOffsets[index], stringLengths[index], StandardCharsets.UTF_8);
            segments[index] = new Segment(rowsBySegment[index], startColumns[index],
                endColumns[index], url, TerminalLinkSource.SOURCE_OSC8);
        }

        setParsedState(topRow, rows, columns, segments);
    }

    private ByteBuffer duplicateBuffer() {
        if (mCachedBuffer == null) {
            mCachedBuffer = mBuffer.duplicate().order(ByteOrder.nativeOrder());
        }
        return mCachedBuffer;
    }

    public static final class Segment {

        private final int mRow;
        private final int mStartColumn;
        private final int mEndColumnExclusive;
        private final String mUrl;
        private final int mSource;

        public Segment(int row, int startColumn, int endColumnExclusive, String url) {
            this(row, startColumn, endColumnExclusive, url, TerminalLinkSource.SOURCE_OSC8);
        }

        public Segment(int row, int startColumn, int endColumnExclusive, String url, int source) {
            if (row < 0) {
                throw new IllegalArgumentException("row must be >= 0");
            }
            if (startColumn < 0) {
                throw new IllegalArgumentException("startColumn must be >= 0");
            }
            if (endColumnExclusive <= startColumn) {
                throw new IllegalArgumentException("endColumnExclusive must be > startColumn");
            }
            if (url == null) {
                throw new IllegalArgumentException("url must not be null");
            }

            mRow = row;
            mStartColumn = startColumn;
            mEndColumnExclusive = endColumnExclusive;
            mUrl = url;
            mSource = source;
        }

        public int getRow() {
            return mRow;
        }

        public int getStartColumn() {
            return mStartColumn;
        }

        public int getEndColumnExclusive() {
            return mEndColumnExclusive;
        }

        public String getUrl() {
            return mUrl;
        }

        public int getSource() {
            return mSource;
        }
    }
}
