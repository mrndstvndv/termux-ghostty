package com.termux.terminal;

import androidx.annotation.Nullable;

public interface TerminalContent {

    int getColumns();

    int getRows();

    int getActiveRows();

    int getActiveTranscriptRows();

    boolean isAlternateBufferActive();

    boolean isMouseTrackingActive();

    boolean isReverseVideo();

    int getCursorRow();

    int getCursorCol();

    int getCursorStyle();

    boolean shouldCursorBeVisible();

    @Nullable
    String getSelectedText(int startColumn, int startRow, int endColumn, int endRow);

    @Nullable
    String getWordAtLocation(int column, int row);

    @Nullable
    String getTranscriptText(boolean joinLines, boolean trim);

    int fillSnapshot(int topRow, ScreenSnapshot snapshot);
}
