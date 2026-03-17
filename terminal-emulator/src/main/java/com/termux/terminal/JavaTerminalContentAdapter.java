package com.termux.terminal;

import androidx.annotation.Nullable;

public final class JavaTerminalContentAdapter implements TerminalContent {

    @Nullable
    private TerminalEmulator mTerminalEmulator;

    public JavaTerminalContentAdapter() {
    }

    public JavaTerminalContentAdapter(TerminalEmulator terminalEmulator) {
        setTerminalEmulator(terminalEmulator);
    }

    public void setTerminalEmulator(@Nullable TerminalEmulator terminalEmulator) {
        mTerminalEmulator = terminalEmulator;
    }

    @Nullable
    public TerminalEmulator getTerminalEmulator() {
        return mTerminalEmulator;
    }

    @Override
    public int getColumns() {
        return requireTerminalEmulator().mColumns;
    }

    @Override
    public int getRows() {
        return requireTerminalEmulator().mRows;
    }

    @Override
    public int getActiveRows() {
        return requireScreen().getActiveRows();
    }

    @Override
    public int getActiveTranscriptRows() {
        return requireScreen().getActiveTranscriptRows();
    }

    @Override
    public boolean isAlternateBufferActive() {
        return requireTerminalEmulator().isAlternateBufferActive();
    }

    @Override
    public boolean isMouseTrackingActive() {
        return requireTerminalEmulator().isMouseTrackingActive();
    }

    @Override
    public boolean isReverseVideo() {
        return requireTerminalEmulator().isReverseVideo();
    }

    @Override
    public int getCursorRow() {
        return requireTerminalEmulator().getCursorRow();
    }

    @Override
    public int getCursorCol() {
        return requireTerminalEmulator().getCursorCol();
    }

    @Override
    public int getCursorStyle() {
        return requireTerminalEmulator().getCursorStyle();
    }

    @Override
    public boolean shouldCursorBeVisible() {
        return requireTerminalEmulator().shouldCursorBeVisible();
    }

    @Nullable
    @Override
    public String getSelectedText(int startColumn, int startRow, int endColumn, int endRow) {
        return requireTerminalEmulator().getSelectedText(startColumn, startRow, endColumn, endRow);
    }

    @Nullable
    @Override
    public String getWordAtLocation(int column, int row) {
        return requireScreen().getWordAtLocation(column, row);
    }

    @Nullable
    @Override
    public String getTranscriptText(boolean joinLines, boolean trim) {
        TerminalEmulator terminalEmulator = requireTerminalEmulator();
        TerminalBuffer screen = terminalEmulator.getScreen();
        String transcriptText;
        if (joinLines) {
            transcriptText = screen.getSelectedText(
                0,
                -screen.getActiveTranscriptRows(),
                terminalEmulator.mColumns,
                terminalEmulator.mRows,
                true,
                true
            );
        } else {
            transcriptText = screen.getSelectedText(
                0,
                -screen.getActiveTranscriptRows(),
                terminalEmulator.mColumns,
                terminalEmulator.mRows,
                false
            );
        }

        if (!trim || transcriptText == null) {
            return transcriptText;
        }

        return transcriptText.trim();
    }

    @Override
    public int fillSnapshot(int topRow, ScreenSnapshot snapshot) {
        if (snapshot == null) {
            throw new IllegalArgumentException("snapshot must not be null");
        }

        TerminalEmulator terminalEmulator = requireTerminalEmulator();
        TerminalBuffer screen = terminalEmulator.getScreen();
        int columns = terminalEmulator.mColumns;
        int rows = terminalEmulator.mRows;
        int clampedTopRow = clampTopRow(topRow, screen.getActiveTranscriptRows());

        snapshot.beginJavaSnapshot(clampedTopRow, rows, columns);
        snapshot.copyPalette(terminalEmulator.mColors.mCurrentColors);
        snapshot.setMetadata(
            terminalEmulator.getCursorCol(),
            terminalEmulator.getCursorRow(),
            terminalEmulator.shouldCursorBeVisible(),
            terminalEmulator.getCursorStyle(),
            terminalEmulator.isReverseVideo()
        );

        for (int rowIndex = 0; rowIndex < rows; rowIndex++) {
            int externalRow = clampedTopRow + rowIndex;
            TerminalRow row = screen.allocateFullLineIfNecessary(screen.externalToInternalRow(externalRow));
            snapshot.setRow(
                rowIndex,
                row.mText,
                row.getSpaceUsed(),
                row.mStyle,
                row.mLineWrap
            );
        }

        snapshot.finishJavaSnapshot();
        return 0;
    }

    private TerminalEmulator requireTerminalEmulator() {
        TerminalEmulator terminalEmulator = mTerminalEmulator;
        if (terminalEmulator != null) {
            return terminalEmulator;
        }

        throw new IllegalStateException("Terminal emulator not attached");
    }

    private TerminalBuffer requireScreen() {
        return requireTerminalEmulator().getScreen();
    }

    private static int clampTopRow(int topRow, int activeTranscriptRows) {
        if (topRow > 0) {
            return 0;
        }

        return Math.max(-activeTranscriptRows, topRow);
    }
}
