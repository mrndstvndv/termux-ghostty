package com.termux.view;

import androidx.annotation.Nullable;

import com.termux.terminal.ScreenSnapshot;
import com.termux.terminal.TerminalLinkSource;
import com.termux.terminal.ViewportLinkSnapshot;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public final class TerminalViewLinkLayout {

    private static final Segment[] EMPTY_SEGMENTS = new Segment[0];

    private final long mFrameSequence;
    private final int mTopRow;
    private final int mRows;
    private final int mColumns;
    private final Segment[][] mSegmentsByRow;

    private TerminalViewLinkLayout(long frameSequence, int topRow, int rows, int columns,
                                   Segment[][] segmentsByRow) {
        mFrameSequence = frameSequence;
        mTopRow = topRow;
        mRows = rows;
        mColumns = columns;
        mSegmentsByRow = segmentsByRow;
    }

    static TerminalViewLinkLayout build(ScreenSnapshot screenSnapshot,
                                        @Nullable ViewportLinkSnapshot osc8Snapshot) {
        if (screenSnapshot == null) {
            throw new IllegalArgumentException("screenSnapshot must not be null");
        }

        int rows = screenSnapshot.getRows();
        int columns = screenSnapshot.getColumns();
        @SuppressWarnings("unchecked")
        ArrayList<Segment>[] rowSegments = new ArrayList[rows];
        boolean[] claimedCells = new boolean[Math.max(0, rows * columns)];

        addOsc8Segments(screenSnapshot, osc8Snapshot, rowSegments, claimedCells);
        addLiteralUrlSegments(screenSnapshot, rowSegments, claimedCells);

        return new TerminalViewLinkLayout(
            screenSnapshot.getFrameSequence(),
            screenSnapshot.getTopRow(),
            rows,
            columns,
            freezeRowSegments(rowSegments, rows)
        );
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

    public boolean isCompatibleWith(ScreenSnapshot screenSnapshot) {
        if (screenSnapshot == null) {
            throw new IllegalArgumentException("screenSnapshot must not be null");
        }

        return mFrameSequence == screenSnapshot.getFrameSequence()
            && mTopRow == screenSnapshot.getTopRow()
            && mRows == screenSnapshot.getRows()
            && mColumns == screenSnapshot.getColumns();
    }

    @Nullable
    public LinkHit findAt(int externalRow, int column) {
        if (column < 0 || column >= mColumns) {
            return null;
        }

        int row = externalRow - mTopRow;
        if (row < 0 || row >= mRows) {
            return null;
        }

        for (Segment segment : mSegmentsByRow[row]) {
            if (column < segment.mStartColumn) {
                return null;
            }
            if (column < segment.mEndColumnExclusive) {
                return new LinkHit(segment.mUrl, segment.mSource);
            }
        }
        return null;
    }

    Segment[] getRowSegments(int row) {
        if (row < 0 || row >= mRows) {
            return EMPTY_SEGMENTS;
        }
        return mSegmentsByRow[row];
    }

    private static void addOsc8Segments(ScreenSnapshot screenSnapshot,
                                        @Nullable ViewportLinkSnapshot osc8Snapshot,
                                        ArrayList<Segment>[] rowSegments,
                                        boolean[] claimedCells) {
        if (osc8Snapshot == null || !osc8Snapshot.isCompatibleWith(screenSnapshot)) {
            return;
        }

        for (int index = 0; index < osc8Snapshot.getSegmentCount(); index++) {
            ViewportLinkSnapshot.Segment segment = osc8Snapshot.getSegment(index);
            if (segment.getUrl().isEmpty()) {
                continue;
            }
            validateSegmentBounds(segment.getRow(), segment.getStartColumn(),
                segment.getEndColumnExclusive(), screenSnapshot.getRows(), screenSnapshot.getColumns());
            addSegment(rowSegments, claimedCells, screenSnapshot.getColumns(), new Segment(
                segment.getRow(),
                segment.getStartColumn(),
                segment.getEndColumnExclusive(),
                segment.getUrl(),
                segment.getSource()
            ));
        }
    }

    private static void addLiteralUrlSegments(ScreenSnapshot screenSnapshot, ArrayList<Segment>[] rowSegments,
                                              boolean[] claimedCells) {
        int rows = screenSnapshot.getRows();
        for (int row = 0; row < rows; ) {
            int lastRow = row;
            while (lastRow < rows - 1 && screenSnapshot.getRow(lastRow).isLineWrap()) {
                lastRow++;
            }

            addLiteralUrlSegmentsForLogicalLine(screenSnapshot, row, lastRow, rowSegments, claimedCells);
            row = lastRow + 1;
        }
    }

    private static void addLiteralUrlSegmentsForLogicalLine(ScreenSnapshot screenSnapshot, int firstRow,
                                                            int lastRow, ArrayList<Segment>[] rowSegments,
                                                            boolean[] claimedCells) {
        StringBuilder text = new StringBuilder();
        List<CellTextSpan> spans = new ArrayList<>();
        for (int row = firstRow; row <= lastRow; row++) {
            ScreenSnapshot.RowSnapshot rowSnapshot = screenSnapshot.getRow(row);
            if (!rowSnapshot.hasCellLayout()) {
                return;
            }

            appendLogicalLineRow(rowSnapshot, row, screenSnapshot.getColumns(), text, spans);
        }

        if (text.length() == 0 || spans.isEmpty()) {
            return;
        }

        List<TerminalUrlMatcher.UrlMatch> matches = TerminalUrlMatcher.DEFAULT.findMatches(text);
        for (TerminalUrlMatcher.UrlMatch match : matches) {
            addLiteralUrlMatch(match, spans, rowSegments, claimedCells, screenSnapshot.getColumns());
        }
    }

    private static void appendLogicalLineRow(ScreenSnapshot.RowSnapshot rowSnapshot, int row,
                                             int columns, StringBuilder text,
                                             List<CellTextSpan> spans) {
        char[] rowText = rowSnapshot.getText();
        for (int column = 0; column < columns; ) {
            int displayWidth = rowSnapshot.getCellDisplayWidth(column);
            if (displayWidth <= 0) {
                column++;
                continue;
            }

            int startCharIndex = text.length();
            int cellTextLength = rowSnapshot.getCellTextLength(column);
            if (cellTextLength > 0) {
                int cellTextStart = rowSnapshot.getCellTextStart(column);
                text.append(rowText, cellTextStart, cellTextLength);
            } else {
                for (int width = 0; width < displayWidth; width++) {
                    text.append(' ');
                }
            }
            int endCharIndex = text.length();
            spans.add(new CellTextSpan(row, column, column + displayWidth, startCharIndex, endCharIndex));
            column += displayWidth;
        }
    }

    private static void addLiteralUrlMatch(TerminalUrlMatcher.UrlMatch match, List<CellTextSpan> spans,
                                           ArrayList<Segment>[] rowSegments, boolean[] claimedCells,
                                           int columns) {
        int currentRow = -1;
        int currentStartColumn = -1;
        int currentEndColumn = -1;
        String url = match.getUrl();

        for (CellTextSpan span : spans) {
            if (span.mEndCharIndexExclusive <= match.getStart()) {
                continue;
            }
            if (span.mStartCharIndex >= match.getEndExclusive()) {
                break;
            }

            for (int column = span.mStartColumn; column < span.mEndColumnExclusive; column++) {
                if (isClaimed(claimedCells, columns, span.mRow, column)) {
                    if (currentRow != -1) {
                        addSegment(rowSegments, claimedCells, columns,
                            new Segment(currentRow, currentStartColumn, currentEndColumn,
                                url, TerminalLinkSource.SOURCE_VISIBLE_URL));
                        currentRow = -1;
                    }
                    continue;
                }

                if (currentRow == span.mRow && currentEndColumn == column) {
                    currentEndColumn = column + 1;
                    continue;
                }

                if (currentRow != -1) {
                    addSegment(rowSegments, claimedCells, columns,
                        new Segment(currentRow, currentStartColumn, currentEndColumn,
                            url, TerminalLinkSource.SOURCE_VISIBLE_URL));
                }

                currentRow = span.mRow;
                currentStartColumn = column;
                currentEndColumn = column + 1;
            }
        }

        if (currentRow != -1) {
            addSegment(rowSegments, claimedCells, columns,
                new Segment(currentRow, currentStartColumn, currentEndColumn,
                    url, TerminalLinkSource.SOURCE_VISIBLE_URL));
        }
    }

    private static void addSegment(ArrayList<Segment>[] rowSegments, boolean[] claimedCells, int columns,
                                   Segment segment) {
        ArrayList<Segment> segmentsForRow = rowSegments[segment.mRow];
        if (segmentsForRow == null) {
            segmentsForRow = new ArrayList<>();
            rowSegments[segment.mRow] = segmentsForRow;
        }
        segmentsForRow.add(segment);
        markClaimed(claimedCells, columns, segment.mRow, segment.mStartColumn, segment.mEndColumnExclusive);
    }

    private static Segment[][] freezeRowSegments(ArrayList<Segment>[] rowSegments, int rows) {
        Segment[][] result = new Segment[rows][];
        Arrays.fill(result, EMPTY_SEGMENTS);
        for (int row = 0; row < rows; row++) {
            ArrayList<Segment> segments = rowSegments[row];
            if (segments == null || segments.isEmpty()) {
                continue;
            }
            result[row] = segments.toArray(new Segment[0]);
        }
        return result;
    }

    private static boolean isClaimed(boolean[] claimedCells, int columns, int row, int column) {
        return claimedCells[(row * columns) + column];
    }

    private static void markClaimed(boolean[] claimedCells, int columns, int row, int startColumn,
                                    int endColumnExclusive) {
        for (int column = startColumn; column < endColumnExclusive; column++) {
            claimedCells[(row * columns) + column] = true;
        }
    }

    private static void validateSegmentBounds(int row, int startColumn, int endColumnExclusive,
                                              int rows, int columns) {
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
    }

    static final class Segment {
        final int mRow;
        final int mStartColumn;
        final int mEndColumnExclusive;
        final String mUrl;
        final int mSource;

        Segment(int row, int startColumn, int endColumnExclusive, String url, int source) {
            mRow = row;
            mStartColumn = startColumn;
            mEndColumnExclusive = endColumnExclusive;
            mUrl = url;
            mSource = source;
        }
    }

    private static final class CellTextSpan {
        private final int mRow;
        private final int mStartColumn;
        private final int mEndColumnExclusive;
        private final int mStartCharIndex;
        private final int mEndCharIndexExclusive;

        private CellTextSpan(int row, int startColumn, int endColumnExclusive,
                             int startCharIndex, int endCharIndexExclusive) {
            mRow = row;
            mStartColumn = startColumn;
            mEndColumnExclusive = endColumnExclusive;
            mStartCharIndex = startCharIndex;
            mEndCharIndexExclusive = endCharIndexExclusive;
        }
    }

    public static final class LinkHit {

        private final String mUrl;
        private final int mSource;

        LinkHit(String url, int source) {
            mUrl = url;
            mSource = source;
        }

        public String getUrl() {
            return mUrl;
        }

        public int getSource() {
            return mSource;
        }
    }
}
