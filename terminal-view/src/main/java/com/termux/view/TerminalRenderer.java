package com.termux.view;

import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.PorterDuff;
import android.graphics.Typeface;

import androidx.annotation.Nullable;

import com.termux.terminal.ScreenSnapshot;
import com.termux.terminal.TerminalContent;
import com.termux.terminal.TextStyle;
import com.termux.terminal.WcWidth;

/**
 * Renderer of a {@link TerminalContent} into a {@link Canvas}.
 * <p/>
 * Saves font metrics, so needs to be recreated each time the typeface or font size changes.
 */
public final class TerminalRenderer {

    private static final String LOG_TAG = "TermuxGhostty";
    private static final int CURSOR_STYLE_BLOCK = 0;
    private static final int CURSOR_STYLE_UNDERLINE = 1;
    private static final int CURSOR_STYLE_BAR = 2;
    private static final long NO_FRAME_SEQUENCE = Long.MIN_VALUE;

    final int mTextSize;
    final Typeface mTypeface;
    private final Paint mTextPaint = new Paint();

    /** The width of a single mono spaced character obtained by {@link Paint#measureText(String)} on a single 'X'. */
    final float mFontWidth;
    /** The {@link Paint#getFontSpacing()}. See http://www.fampennings.nl/maarten/android/08numgrid/font.png */
    final int mFontLineSpacing;
    /** The {@link Paint#ascent()}. See http://www.fampennings.nl/maarten/android/08numgrid/font.png */
    private final int mFontAscent;
    /** The {@link #mFontLineSpacing} + {@link #mFontAscent}. */
    final int mFontLineSpacingAndAscent;

    private final float[] asciiMeasures = new float[127];
    private RowRenderCache[] mRowRenderCaches = new RowRenderCache[0];
    private long mPreparedFrameSequence = NO_FRAME_SEQUENCE;
    private int mPreparedTopRow;
    private int mPreparedRows = -1;
    private int mPreparedColumns = -1;

    public TerminalRenderer(int textSize, Typeface typeface) {
        mTextSize = textSize;
        mTypeface = typeface;

        mTextPaint.setTypeface(typeface);
        mTextPaint.setAntiAlias(true);
        mTextPaint.setTextSize(textSize);

        mFontLineSpacing = (int) Math.ceil(mTextPaint.getFontSpacing());
        mFontAscent = (int) Math.ceil(mTextPaint.ascent());
        mFontLineSpacingAndAscent = mFontLineSpacing + mFontAscent;
        mFontWidth = mTextPaint.measureText("X");

        StringBuilder sb = new StringBuilder(" ");
        for (int i = 0; i < asciiMeasures.length; i++) {
            sb.setCharAt(0, (char) i);
            asciiMeasures[i] = mTextPaint.measureText(sb, 0, 1);
        }
    }

    /** Render the terminal to a canvas with a specified row scroll, and an optional rectangular selection. */
    public final void render(TerminalContent terminalContent, ScreenSnapshot screenSnapshot, Canvas canvas, int topRow,
                             int selectionY1, int selectionY2, int selectionX1, int selectionX2) {
        if (terminalContent == null) {
            throw new IllegalArgumentException("terminalContent must not be null");
        }
        if (screenSnapshot == null) {
            throw new IllegalArgumentException("screenSnapshot must not be null");
        }

        terminalContent.fillSnapshot(topRow, screenSnapshot);
        render(screenSnapshot, canvas, selectionY1, selectionY2, selectionX1, selectionX2, null);
    }

    /** Render a pre-filled ScreenSnapshot to a canvas. */
    public final void render(ScreenSnapshot screenSnapshot, Canvas canvas,
                             int selectionY1, int selectionY2, int selectionX1, int selectionX2) {
        render(screenSnapshot, canvas, selectionY1, selectionY2, selectionX1, selectionX2, null);
    }

    /** Render a pre-filled ScreenSnapshot to a canvas with an optional synthetic link overlay. */
    public final void render(ScreenSnapshot screenSnapshot, Canvas canvas,
                             int selectionY1, int selectionY2, int selectionX1, int selectionX2,
                             @Nullable TerminalViewLinkLayout linkLayout) {
        if (screenSnapshot == null) {
            throw new IllegalArgumentException("screenSnapshot must not be null");
        }

        render(screenSnapshot, canvas, selectionY1, selectionY2, selectionX1, selectionX2,
            screenSnapshot.getCursorCol(),
            screenSnapshot.getCursorRow(),
            screenSnapshot.isCursorVisible(),
            screenSnapshot.getCursorStyle(),
            screenSnapshot.isReverseVideo(),
            linkLayout);
    }

    public final void render(ScreenSnapshot screenSnapshot, Canvas canvas,
                             int selectionY1, int selectionY2, int selectionX1, int selectionX2,
                             int cursorCol, int cursorRow, boolean cursorVisible, int cursorShape,
                             boolean reverseVideo, @Nullable TerminalViewLinkLayout linkLayout) {
        if (screenSnapshot == null) {
            throw new IllegalArgumentException("screenSnapshot must not be null");
        }

        prepareRowCaches(screenSnapshot);

        final int snapshotTopRow = screenSnapshot.getTopRow();
        final int endRow = snapshotTopRow + screenSnapshot.getRows();
        final int columns = screenSnapshot.getColumns();

        if (reverseVideo) {
            canvas.drawColor(screenSnapshot.getPaletteColor(TextStyle.COLOR_INDEX_FOREGROUND), PorterDuff.Mode.SRC);
        }

        float heightOffset = mFontLineSpacingAndAscent;
        for (int row = snapshotTopRow; row < endRow; row++) {
            heightOffset += mFontLineSpacing;

            final int cursorX = (row == cursorRow && cursorVisible) ? cursorCol : -1;
            int selx1 = -1;
            int selx2 = -1;
            if (row >= selectionY1 && row <= selectionY2) {
                if (row == selectionY1) {
                    selx1 = selectionX1;
                }
                selx2 = (row == selectionY2) ? selectionX2 : columns;
            }

            int rowIndex = row - snapshotTopRow;
            ScreenSnapshot.RowSnapshot lineObject = screenSnapshot.getRow(rowIndex);
            RowRenderCache rowRenderCache = obtainRowRenderCache(lineObject, rowIndex, columns, row, cursorX, selx1, selx2);
            renderCachedRow(canvas, screenSnapshot, lineObject, rowRenderCache, heightOffset, cursorShape, reverseVideo);
        }

        drawSyntheticLinkUnderlines(canvas, screenSnapshot, linkLayout, selectionY1, selectionY2,
            selectionX1, selectionX2, cursorCol, cursorRow, cursorVisible, cursorShape, reverseVideo);
    }

    private void prepareRowCaches(ScreenSnapshot screenSnapshot) {
        int rows = screenSnapshot.getRows();
        int columns = screenSnapshot.getColumns();
        ensureRowCacheCapacity(rows);

        long frameSequence = screenSnapshot.getFrameSequence();
        if (frameSequence <= 0) {
            mPreparedFrameSequence = NO_FRAME_SEQUENCE;
            mPreparedTopRow = screenSnapshot.getTopRow();
            mPreparedRows = rows;
            mPreparedColumns = columns;
            return;
        }

        if (mPreparedFrameSequence == frameSequence) {
            return;
        }

        boolean dimensionsChanged = mPreparedRows != rows || mPreparedColumns != columns;
        if (!dimensionsChanged && mPreparedFrameSequence != NO_FRAME_SEQUENCE) {
            int topRowDelta = screenSnapshot.getTopRow() - mPreparedTopRow;
            if (Math.abs(topRowDelta) < rows && topRowDelta != 0) {
                shiftRowCaches(topRowDelta, rows);
            }
        }

        mPreparedFrameSequence = frameSequence;
        mPreparedTopRow = screenSnapshot.getTopRow();
        mPreparedRows = rows;
        mPreparedColumns = columns;
    }

    private RowRenderCache obtainRowRenderCache(ScreenSnapshot.RowSnapshot lineObject, int rowIndex, int columns,
                                                int row, int cursorX, int selx1, int selx2) {
        long contentHash = lineObject.getContentHash();
        boolean hasCellLayout = lineObject.hasCellLayout();
        RowRenderCache rowRenderCache = mRowRenderCaches[rowIndex];
        if (rowRenderCache != null && rowRenderCache.matches(contentHash, selx1, selx2, cursorX, hasCellLayout)) {
            return rowRenderCache;
        }

        int matchingCacheIndex = findMatchingRowRenderCache(contentHash, selx1, selx2, cursorX, hasCellLayout, rowIndex);
        if (matchingCacheIndex != -1) {
            RowRenderCache matchingCache = mRowRenderCaches[matchingCacheIndex];
            mRowRenderCaches[matchingCacheIndex] = rowRenderCache;
            mRowRenderCaches[rowIndex] = matchingCache;
            return matchingCache;
        }

        if (rowRenderCache == null) {
            rowRenderCache = new RowRenderCache();
            mRowRenderCaches[rowIndex] = rowRenderCache;
        }

        rowRenderCache.beginBuild(contentHash, selx1, selx2, cursorX, hasCellLayout);
        if (hasCellLayout) {
            rebuildNativeRowCache(rowRenderCache, lineObject, columns, row, cursorX, selx1, selx2);
        } else {
            rebuildJavaRowCache(rowRenderCache, lineObject, columns, cursorX, selx1, selx2);
        }
        return rowRenderCache;
    }

    private int findMatchingRowRenderCache(long contentHash, int selectionStart, int selectionEnd,
                                           int cursorX, boolean hasCellLayout, int excludedIndex) {
        for (int index = 0; index < mRowRenderCaches.length; index++) {
            if (index == excludedIndex) {
                continue;
            }

            RowRenderCache candidate = mRowRenderCaches[index];
            if (candidate == null) {
                continue;
            }
            if (candidate.matches(contentHash, selectionStart, selectionEnd, cursorX, hasCellLayout)) {
                return index;
            }
        }

        return -1;
    }

    private void rebuildJavaRowCache(RowRenderCache rowRenderCache, ScreenSnapshot.RowSnapshot lineObject,
                                     int columns, int cursorX, int selx1, int selx2) {
        final char[] line = lineObject.getText();
        final int charsUsedInLine = lineObject.getCharsUsed();
        final int rowColumns = lineObject.getColumns();
        if (columns <= 0) {
            return;
        }
        if (charsUsedInLine <= 0 || rowColumns <= 0) {
            appendBlankRuns(rowRenderCache, lineObject, rowColumns, 0, columns, cursorX, selx1, selx2);
            return;
        }

        long lastRunStyle = 0;
        boolean lastRunInsideCursor = false;
        boolean lastRunInsideSelection = false;
        boolean lastRunFontWidthMismatch = false;
        int lastRunStartColumn = -1;
        int lastRunStartIndex = 0;
        int currentCharIndex = 0;
        float measuredWidthForRun = 0.f;

        for (int column = 0; column < columns; ) {
            if (currentCharIndex >= charsUsedInLine) {
                if (lastRunStartColumn != -1) {
                    rowRenderCache.addRun(lastRunStartColumn, column - lastRunStartColumn,
                        lastRunStartIndex, currentCharIndex - lastRunStartIndex,
                        measuredWidthForRun, lastRunStyle, lastRunInsideCursor, lastRunInsideSelection);
                }
                appendBlankRuns(rowRenderCache, lineObject, rowColumns, column, columns, cursorX, selx1, selx2);
                return;
            }

            final char charAtIndex = line[currentCharIndex];
            final boolean charIsHighsurrogate = Character.isHighSurrogate(charAtIndex);
            final int charsForCodePoint = charIsHighsurrogate ? 2 : 1;
            final int codePoint = charIsHighsurrogate ? Character.toCodePoint(charAtIndex, line[currentCharIndex + 1]) : charAtIndex;
            final int codePointWcWidth = WcWidth.width(codePoint);
            final boolean insideCursor = (cursorX == column || (codePointWcWidth == 2 && cursorX == column + 1));
            final boolean insideSelection = column >= selx1 && column <= selx2;
            final long style = column < rowColumns ? lineObject.getStyle(column) : 0L;

            final float measuredCodePointWidth = (codePoint < asciiMeasures.length)
                ? asciiMeasures[codePoint]
                : mTextPaint.measureText(line, currentCharIndex, charsForCodePoint);
            final boolean fontWidthMismatch = Math.abs(measuredCodePointWidth / mFontWidth - codePointWcWidth) > 0.01f;
            final boolean splitRun = lastRunStartColumn == -1
                || style != lastRunStyle
                || insideCursor != lastRunInsideCursor
                || insideSelection != lastRunInsideSelection
                || fontWidthMismatch
                || lastRunFontWidthMismatch;

            if (splitRun) {
                if (lastRunStartColumn != -1) {
                    rowRenderCache.addRun(lastRunStartColumn, column - lastRunStartColumn,
                        lastRunStartIndex, currentCharIndex - lastRunStartIndex,
                        measuredWidthForRun, lastRunStyle, lastRunInsideCursor, lastRunInsideSelection);
                }
                measuredWidthForRun = 0.f;
                lastRunStyle = style;
                lastRunInsideCursor = insideCursor;
                lastRunInsideSelection = insideSelection;
                lastRunStartColumn = column;
                lastRunStartIndex = currentCharIndex;
                lastRunFontWidthMismatch = fontWidthMismatch;
            }

            measuredWidthForRun += measuredCodePointWidth;
            column += codePointWcWidth;
            currentCharIndex += charsForCodePoint;
            while (currentCharIndex < charsUsedInLine && WcWidth.width(line, currentCharIndex) <= 0) {
                currentCharIndex += Character.isHighSurrogate(line[currentCharIndex]) ? 2 : 1;
            }
        }

        if (lastRunStartColumn == -1) {
            appendBlankRuns(rowRenderCache, lineObject, rowColumns, 0, columns, cursorX, selx1, selx2);
            return;
        }

        rowRenderCache.addRun(lastRunStartColumn, columns - lastRunStartColumn,
            lastRunStartIndex, currentCharIndex - lastRunStartIndex,
            measuredWidthForRun, lastRunStyle, lastRunInsideCursor, lastRunInsideSelection);
    }

    private void rebuildNativeRowCache(RowRenderCache rowRenderCache, ScreenSnapshot.RowSnapshot lineObject,
                                       int columns, int row, int cursorX, int selx1, int selx2) {
        final char[] line = lineObject.getText();
        final int charsUsedInLine = lineObject.getCharsUsed();

        long lastRunStyle = 0;
        boolean lastRunInsideCursor = false;
        boolean lastRunInsideSelection = false;
        boolean lastRunHasText = false;
        boolean lastRunFontWidthMismatch = false;
        int lastRunStartColumn = -1;
        int lastRunStartIndex = 0;
        int lastRunEndIndex = 0;
        float measuredWidthForRun = 0.f;

        for (int column = 0; column < columns; ) {
            int cellTextStart = lineObject.getCellTextStart(column);
            int cellTextLength = lineObject.getCellTextLength(column);
            int cellDisplayWidth = lineObject.getCellDisplayWidth(column);
            long style = lineObject.getStyle(column);

            if (cellDisplayWidth <= 0) {
                column++;
                continue;
            }

            if (cellTextStart < 0 || cellTextLength < 0 || cellTextStart + cellTextLength > charsUsedInLine) {
                throw new IllegalStateException("Invalid native cell layout row=" + row + " column=" + column
                    + " start=" + cellTextStart + " length=" + cellTextLength + " charsUsed=" + charsUsedInLine);
            }

            boolean hasText = cellTextLength > 0;
            boolean insideCursor = cursorX == column || (cellDisplayWidth == 2 && cursorX == column + 1);
            boolean insideSelection = column <= selx2 && (column + cellDisplayWidth - 1) >= selx1;
            float measuredWidth = hasText ? measureNativeCellWidth(line, cellTextStart, cellTextLength) : 0.f;
            boolean fontWidthMismatch = hasText && Math.abs(measuredWidth / mFontWidth - cellDisplayWidth) > 0.01f;
            boolean splitRun = lastRunStartColumn == -1
                || style != lastRunStyle
                || insideCursor != lastRunInsideCursor
                || insideSelection != lastRunInsideSelection
                || hasText != lastRunHasText
                || fontWidthMismatch
                || lastRunFontWidthMismatch
                || (hasText && lastRunHasText && cellTextStart != lastRunEndIndex);

            if (splitRun) {
                if (lastRunStartColumn != -1) {
                    rowRenderCache.addRun(lastRunStartColumn, column - lastRunStartColumn,
                        lastRunStartIndex, lastRunEndIndex - lastRunStartIndex,
                        measuredWidthForRun, lastRunStyle, lastRunInsideCursor, lastRunInsideSelection);
                }
                measuredWidthForRun = 0.f;
                lastRunStyle = style;
                lastRunInsideCursor = insideCursor;
                lastRunInsideSelection = insideSelection;
                lastRunHasText = hasText;
                lastRunStartColumn = column;
                lastRunStartIndex = cellTextStart;
                lastRunEndIndex = cellTextStart;
                lastRunFontWidthMismatch = fontWidthMismatch;
            }

            if (hasText) {
                measuredWidthForRun += measuredWidth;
                lastRunEndIndex = cellTextStart + cellTextLength;
            }
            column += cellDisplayWidth;
        }

        if (lastRunStartColumn == -1) {
            return;
        }

        rowRenderCache.addRun(lastRunStartColumn, columns - lastRunStartColumn,
            lastRunStartIndex, lastRunEndIndex - lastRunStartIndex,
            measuredWidthForRun, lastRunStyle, lastRunInsideCursor, lastRunInsideSelection);
    }

    private void renderCachedRow(Canvas canvas, ScreenSnapshot screenSnapshot, ScreenSnapshot.RowSnapshot lineObject,
                                 RowRenderCache rowRenderCache, float heightOffset, int cursorShape,
                                 boolean reverseVideo) {
        if (rowRenderCache.mRunCount == 0) {
            return;
        }

        final char[] line = lineObject.getText();
        for (int runIndex = 0; runIndex < rowRenderCache.mRunCount; runIndex++) {
            boolean insideCursor = rowRenderCache.mInsideCursor[runIndex];
            int cursorColor = insideCursor ? screenSnapshot.getPaletteColor(TextStyle.COLOR_INDEX_CURSOR) : 0;
            boolean invertCursorTextColor = insideCursor && cursorShape == CURSOR_STYLE_BLOCK;
            drawTextRun(canvas, line, screenSnapshot, heightOffset,
                rowRenderCache.mStartColumns[runIndex],
                rowRenderCache.mRunWidthColumns[runIndex],
                rowRenderCache.mStartCharIndices[runIndex],
                rowRenderCache.mRunWidthChars[runIndex],
                rowRenderCache.mMeasuredWidths[runIndex],
                cursorColor,
                cursorShape,
                rowRenderCache.mTextStyles[runIndex],
                reverseVideo || invertCursorTextColor || rowRenderCache.mInsideSelection[runIndex]);
        }
    }

    private void appendBlankRuns(RowRenderCache rowRenderCache, ScreenSnapshot.RowSnapshot lineObject,
                                 int rowColumns, int startColumn, int endColumnExclusive,
                                 int cursorX, int selx1, int selx2) {
        if (startColumn >= endColumnExclusive) {
            return;
        }

        long lastRunStyle = 0;
        boolean lastRunInsideCursor = false;
        boolean lastRunInsideSelection = false;
        int lastRunStartColumn = -1;

        for (int column = startColumn; column < endColumnExclusive; column++) {
            long style = column < rowColumns ? lineObject.getStyle(column) : 0L;
            boolean insideCursor = cursorX == column;
            boolean insideSelection = column >= selx1 && column <= selx2;
            boolean splitRun = lastRunStartColumn == -1
                || style != lastRunStyle
                || insideCursor != lastRunInsideCursor
                || insideSelection != lastRunInsideSelection;
            if (splitRun) {
                if (lastRunStartColumn != -1) {
                    rowRenderCache.addRun(lastRunStartColumn, column - lastRunStartColumn,
                        0, 0, 0.f, lastRunStyle, lastRunInsideCursor, lastRunInsideSelection);
                }
                lastRunStartColumn = column;
                lastRunStyle = style;
                lastRunInsideCursor = insideCursor;
                lastRunInsideSelection = insideSelection;
            }
        }

        if (lastRunStartColumn != -1) {
            rowRenderCache.addRun(lastRunStartColumn, endColumnExclusive - lastRunStartColumn,
                0, 0, 0.f, lastRunStyle, lastRunInsideCursor, lastRunInsideSelection);
        }
    }

    private float measureNativeCellWidth(char[] line, int start, int length) {
        if (length <= 0) {
            return 0.f;
        }
        if (length == 1) {
            char codeUnit = line[start];
            if (codeUnit < asciiMeasures.length) {
                return asciiMeasures[codeUnit];
            }
        }
        return mTextPaint.measureText(line, start, length);
    }

    private void ensureRowCacheCapacity(int rows) {
        if (mRowRenderCaches.length == rows) {
            return;
        }

        mRowRenderCaches = new RowRenderCache[rows];
        mPreparedFrameSequence = NO_FRAME_SEQUENCE;
        mPreparedRows = rows;
    }

    private void invalidateAllRowCaches() {
        for (RowRenderCache rowRenderCache : mRowRenderCaches) {
            if (rowRenderCache != null) {
                rowRenderCache.invalidate();
            }
        }
    }

    private void invalidateRowCache(int rowIndex) {
        if (rowIndex < 0 || rowIndex >= mRowRenderCaches.length) {
            return;
        }

        RowRenderCache rowRenderCache = mRowRenderCaches[rowIndex];
        if (rowRenderCache != null) {
            rowRenderCache.invalidate();
        }
    }

    private void shiftRowCaches(int topRowDelta, int rows) {
        if (topRowDelta == 0) {
            return;
        }

        if (topRowDelta > 0) {
            int retainedRows = rows - topRowDelta;
            if (retainedRows > 0) {
                System.arraycopy(mRowRenderCaches, topRowDelta, mRowRenderCaches, 0, retainedRows);
            }
            for (int row = retainedRows; row < rows; row++) {
                mRowRenderCaches[row] = null;
            }
            return;
        }

        int shift = -topRowDelta;
        int retainedRows = rows - shift;
        if (retainedRows > 0) {
            System.arraycopy(mRowRenderCaches, 0, mRowRenderCaches, shift, retainedRows);
        }
        for (int row = 0; row < shift; row++) {
            mRowRenderCaches[row] = null;
        }
    }

    private void drawTextRun(Canvas canvas, char[] text, ScreenSnapshot screenSnapshot, float y, int startColumn, int runWidthColumns,
                             int startCharIndex, int runWidthChars, float mes, int cursor, int cursorStyle,
                             long textStyle, boolean reverseVideo) {
        final int effect = TextStyle.decodeEffect(textStyle);
        final boolean bold = (effect & (TextStyle.CHARACTER_ATTRIBUTE_BOLD | TextStyle.CHARACTER_ATTRIBUTE_BLINK)) != 0;
        final boolean underline = (effect & TextStyle.CHARACTER_ATTRIBUTE_UNDERLINE) != 0;
        final boolean italic = (effect & TextStyle.CHARACTER_ATTRIBUTE_ITALIC) != 0;
        final boolean strikeThrough = (effect & TextStyle.CHARACTER_ATTRIBUTE_STRIKETHROUGH) != 0;

        long packedColors = resolveEffectiveColors(screenSnapshot, textStyle, reverseVideo);
        int foreColor = unpackForegroundColor(packedColors);
        int backColor = unpackBackgroundColor(packedColors);

        float left = startColumn * mFontWidth;
        float right = left + runWidthColumns * mFontWidth;

        boolean hasText = runWidthChars > 0 && mes > 0.f;
        boolean savedMatrix = false;
        if (hasText) {
            mes = mes / mFontWidth;
            if (Math.abs(mes - runWidthColumns) > 0.01) {
                canvas.save();
                canvas.scale(runWidthColumns / mes, 1.f);
                left *= mes / runWidthColumns;
                right *= mes / runWidthColumns;
                savedMatrix = true;
            }
        }

        if (backColor != screenSnapshot.getPaletteColor(TextStyle.COLOR_INDEX_BACKGROUND)) {
            mTextPaint.setColor(backColor);
            canvas.drawRect(left, y - mFontLineSpacingAndAscent + mFontAscent, right, y, mTextPaint);
        }

        if (cursor != 0) {
            mTextPaint.setColor(cursor);
            float cursorHeight = mFontLineSpacingAndAscent - mFontAscent;
            if (cursorStyle == CURSOR_STYLE_UNDERLINE) {
                cursorHeight /= 4.f;
            } else if (cursorStyle == CURSOR_STYLE_BAR) {
                right -= ((right - left) * 3) / 4.f;
            }
            canvas.drawRect(left, y - cursorHeight, right, y, mTextPaint);
        }

        if (hasText && (effect & TextStyle.CHARACTER_ATTRIBUTE_INVISIBLE) == 0) {
            mTextPaint.setFakeBoldText(bold);
            mTextPaint.setUnderlineText(underline);
            mTextPaint.setTextSkewX(italic ? -0.35f : 0.f);
            mTextPaint.setStrikeThruText(strikeThrough);
            mTextPaint.setColor(foreColor);
            canvas.drawTextRun(text, startCharIndex, runWidthChars, startCharIndex, runWidthChars,
                left, y - mFontLineSpacingAndAscent, false, mTextPaint);
        }

        if (savedMatrix) {
            canvas.restore();
        }
    }

    private void drawSyntheticLinkUnderlines(Canvas canvas, ScreenSnapshot screenSnapshot,
                                             @Nullable TerminalViewLinkLayout linkLayout,
                                             int selectionY1, int selectionY2, int selectionX1, int selectionX2,
                                             int cursorCol, int cursorRow, boolean cursorVisible,
                                             int cursorShape, boolean reverseVideo) {
        if (linkLayout == null || !linkLayout.isCompatibleWith(screenSnapshot)) {
            return;
        }

        float heightOffset = mFontLineSpacingAndAscent;
        int topRow = screenSnapshot.getTopRow();
        for (int rowIndex = 0; rowIndex < screenSnapshot.getRows(); rowIndex++) {
            heightOffset += mFontLineSpacing;
            TerminalViewLinkLayout.Segment[] segments = linkLayout.getRowSegments(rowIndex);
            if (segments.length == 0) {
                continue;
            }

            int row = topRow + rowIndex;
            int selectionStart = -1;
            int selectionEnd = -1;
            if (row >= selectionY1 && row <= selectionY2) {
                if (row == selectionY1) {
                    selectionStart = selectionX1;
                }
                selectionEnd = (row == selectionY2) ? selectionX2 : screenSnapshot.getColumns();
            }

            drawSyntheticRowUnderlines(canvas, screenSnapshot, screenSnapshot.getRow(rowIndex), segments,
                heightOffset, selectionStart, selectionEnd,
                row == cursorRow && cursorVisible && cursorShape == CURSOR_STYLE_BLOCK,
                cursorCol, reverseVideo);
        }
    }

    private void drawSyntheticRowUnderlines(Canvas canvas, ScreenSnapshot screenSnapshot,
                                            ScreenSnapshot.RowSnapshot rowSnapshot,
                                            TerminalViewLinkLayout.Segment[] segments, float y,
                                            int selectionStart, int selectionEnd,
                                            boolean rowHasBlockCursor, int cursorCol,
                                            boolean reverseVideo) {
        for (TerminalViewLinkLayout.Segment segment : segments) {
            drawSyntheticUnderlineSegment(canvas, screenSnapshot, rowSnapshot, segment, y,
                selectionStart, selectionEnd, rowHasBlockCursor, cursorCol, reverseVideo);
        }
    }

    private void drawSyntheticUnderlineSegment(Canvas canvas, ScreenSnapshot screenSnapshot,
                                               ScreenSnapshot.RowSnapshot rowSnapshot,
                                               TerminalViewLinkLayout.Segment segment, float y,
                                               int selectionStart, int selectionEnd,
                                               boolean rowHasBlockCursor, int cursorCol,
                                               boolean reverseVideo) {
        int batchStartColumn = -1;
        int batchEndColumn = -1;
        int batchColor = 0;

        for (int column = segment.mStartColumn; column < segment.mEndColumnExclusive; ) {
            int displayWidth = rowSnapshot.getCellDisplayWidth(column);
            if (displayWidth <= 0) {
                column++;
                continue;
            }

            int cellEndColumn = Math.min(segment.mEndColumnExclusive, column + displayWidth);
            long textStyle = rowSnapshot.getStyle(column);
            if ((TextStyle.decodeEffect(textStyle) & TextStyle.CHARACTER_ATTRIBUTE_UNDERLINE) != 0) {
                if (batchStartColumn != -1) {
                    drawSyntheticUnderlineBatch(canvas, y, batchStartColumn, batchEndColumn, batchColor);
                    batchStartColumn = -1;
                }
                column += displayWidth;
                continue;
            }

            boolean insideSelection = isCellInsideSelection(column, displayWidth, selectionStart, selectionEnd);
            boolean insideBlockCursor = rowHasBlockCursor && isCellInsideCursor(column, displayWidth, cursorCol);
            int underlineColor = resolveEffectiveForegroundColor(screenSnapshot, textStyle,
                reverseVideo || insideSelection || insideBlockCursor);
            if (batchStartColumn != -1 && batchEndColumn == column && batchColor == underlineColor) {
                batchEndColumn = cellEndColumn;
            } else {
                if (batchStartColumn != -1) {
                    drawSyntheticUnderlineBatch(canvas, y, batchStartColumn, batchEndColumn, batchColor);
                }
                batchStartColumn = column;
                batchEndColumn = cellEndColumn;
                batchColor = underlineColor;
            }

            column += displayWidth;
        }

        if (batchStartColumn != -1) {
            drawSyntheticUnderlineBatch(canvas, y, batchStartColumn, batchEndColumn, batchColor);
        }
    }

    private void drawSyntheticUnderlineBatch(Canvas canvas, float y, int startColumn,
                                             int endColumnExclusive, int color) {
        if (endColumnExclusive <= startColumn) {
            return;
        }

        float thickness = Math.max(1f, Math.round(mTextSize / 14f));
        float underlineBottom = y - Math.max(1f, thickness * 0.5f);
        float underlineTop = underlineBottom - thickness;
        float left = startColumn * mFontWidth;
        float right = endColumnExclusive * mFontWidth;

        mTextPaint.setColor(color);
        canvas.drawRect(left, underlineTop, right, underlineBottom, mTextPaint);
    }

    private static boolean isCellInsideSelection(int column, int displayWidth,
                                                 int selectionStart, int selectionEnd) {
        return column <= selectionEnd && (column + displayWidth - 1) >= selectionStart;
    }

    private static boolean isCellInsideCursor(int column, int displayWidth, int cursorCol) {
        return cursorCol == column || (displayWidth == 2 && cursorCol == column + 1);
    }

    private int resolveEffectiveForegroundColor(ScreenSnapshot screenSnapshot, long textStyle,
                                                boolean reverseVideo) {
        return unpackForegroundColor(resolveEffectiveColors(screenSnapshot, textStyle, reverseVideo));
    }

    private long resolveEffectiveColors(ScreenSnapshot screenSnapshot, long textStyle,
                                        boolean reverseVideo) {
        int foreColor = TextStyle.decodeForeColor(textStyle);
        int backColor = TextStyle.decodeBackColor(textStyle);
        int effect = TextStyle.decodeEffect(textStyle);
        boolean bold = (effect & (TextStyle.CHARACTER_ATTRIBUTE_BOLD | TextStyle.CHARACTER_ATTRIBUTE_BLINK)) != 0;
        boolean dim = (effect & TextStyle.CHARACTER_ATTRIBUTE_DIM) != 0;

        foreColor = resolvePaletteColor(screenSnapshot, foreColor, bold);
        backColor = resolvePaletteColor(screenSnapshot, backColor, false);

        boolean reverseVideoHere = reverseVideo
            ^ ((effect & TextStyle.CHARACTER_ATTRIBUTE_INVERSE) != 0);
        if (reverseVideoHere) {
            int swap = foreColor;
            foreColor = backColor;
            backColor = swap;
        }

        if (dim) {
            foreColor = applyDim(foreColor);
        }

        return packColors(foreColor, backColor);
    }

    private int resolvePaletteColor(ScreenSnapshot screenSnapshot, int color, boolean bold) {
        if ((color & 0xff000000) == 0xff000000) {
            return color;
        }

        int resolved = color;
        if (bold && resolved >= 0 && resolved < 8) {
            resolved += 8;
        }
        return screenSnapshot.getPaletteColor(resolved);
    }

    private static int applyDim(int color) {
        int red = (color >> 16) & 0xFF;
        int green = (color >> 8) & 0xFF;
        int blue = color & 0xFF;
        red = red * 2 / 3;
        green = green * 2 / 3;
        blue = blue * 2 / 3;
        return 0xFF000000 | (red << 16) | (green << 8) | blue;
    }

    private static long packColors(int foreground, int background) {
        return (((long) foreground) << 32) | (background & 0xffffffffL);
    }

    private static int unpackForegroundColor(long packedColors) {
        return (int) (packedColors >>> 32);
    }

    private static int unpackBackgroundColor(long packedColors) {
        return (int) packedColors;
    }

    public float getFontWidth() {
        return mFontWidth;
    }

    public int getFontLineSpacing() {
        return mFontLineSpacing;
    }

    private static final class RowRenderCache {
        private long mContentHash = Long.MIN_VALUE;
        private int mSelectionStart = Integer.MIN_VALUE;
        private int mSelectionEnd = Integer.MIN_VALUE;
        private int mCursorX = Integer.MIN_VALUE;
        private boolean mHasCellLayout;
        private int mRunCount;
        private int[] mStartColumns = new int[0];
        private int[] mRunWidthColumns = new int[0];
        private int[] mStartCharIndices = new int[0];
        private int[] mRunWidthChars = new int[0];
        private float[] mMeasuredWidths = new float[0];
        private long[] mTextStyles = new long[0];
        private boolean[] mInsideCursor = new boolean[0];
        private boolean[] mInsideSelection = new boolean[0];

        boolean matches(long contentHash, int selectionStart, int selectionEnd, int cursorX, boolean hasCellLayout) {
            return mContentHash == contentHash
                && mSelectionStart == selectionStart
                && mSelectionEnd == selectionEnd
                && mCursorX == cursorX
                && mHasCellLayout == hasCellLayout;
        }

        void beginBuild(long contentHash, int selectionStart, int selectionEnd, int cursorX, boolean hasCellLayout) {
            mContentHash = contentHash;
            mSelectionStart = selectionStart;
            mSelectionEnd = selectionEnd;
            mCursorX = cursorX;
            mHasCellLayout = hasCellLayout;
            mRunCount = 0;
        }

        void invalidate() {
            mContentHash = Long.MIN_VALUE;
            mRunCount = 0;
        }

        void addRun(int startColumn, int runWidthColumns, int startCharIndex, int runWidthChars,
                    float measuredWidth, long textStyle, boolean insideCursor, boolean insideSelection) {
            ensureRunCapacity(mRunCount + 1);
            mStartColumns[mRunCount] = startColumn;
            mRunWidthColumns[mRunCount] = runWidthColumns;
            mStartCharIndices[mRunCount] = startCharIndex;
            mRunWidthChars[mRunCount] = runWidthChars;
            mMeasuredWidths[mRunCount] = measuredWidth;
            mTextStyles[mRunCount] = textStyle;
            mInsideCursor[mRunCount] = insideCursor;
            mInsideSelection[mRunCount] = insideSelection;
            mRunCount++;
        }

        private void ensureRunCapacity(int requiredCapacity) {
            if (mStartColumns.length >= requiredCapacity) {
                return;
            }

            int newCapacity = Math.max(requiredCapacity, (mStartColumns.length * 2) + 4);
            mStartColumns = grow(mStartColumns, newCapacity);
            mRunWidthColumns = grow(mRunWidthColumns, newCapacity);
            mStartCharIndices = grow(mStartCharIndices, newCapacity);
            mRunWidthChars = grow(mRunWidthChars, newCapacity);
            mMeasuredWidths = grow(mMeasuredWidths, newCapacity);
            mTextStyles = grow(mTextStyles, newCapacity);
            mInsideCursor = grow(mInsideCursor, newCapacity);
            mInsideSelection = grow(mInsideSelection, newCapacity);
        }

        private static int[] grow(int[] source, int newCapacity) {
            int[] result = new int[newCapacity];
            System.arraycopy(source, 0, result, 0, source.length);
            return result;
        }

        private static float[] grow(float[] source, int newCapacity) {
            float[] result = new float[newCapacity];
            System.arraycopy(source, 0, result, 0, source.length);
            return result;
        }

        private static long[] grow(long[] source, int newCapacity) {
            long[] result = new long[newCapacity];
            System.arraycopy(source, 0, result, 0, source.length);
            return result;
        }

        private static boolean[] grow(boolean[] source, int newCapacity) {
            boolean[] result = new boolean[newCapacity];
            System.arraycopy(source, 0, result, 0, source.length);
            return result;
        }
    }
}
