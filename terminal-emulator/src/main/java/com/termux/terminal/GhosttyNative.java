package com.termux.terminal;

import androidx.annotation.Nullable;

import java.nio.ByteBuffer;

final class GhosttyNative {

    static final int APPEND_RESULT_SCREEN_CHANGED = 1;
    static final int APPEND_RESULT_CURSOR_CHANGED = 1 << 1;
    static final int APPEND_RESULT_TITLE_CHANGED = 1 << 2;
    static final int APPEND_RESULT_BELL = 1 << 3;
    static final int APPEND_RESULT_CLIPBOARD_COPY = 1 << 4;
    static final int APPEND_RESULT_COLORS_CHANGED = 1 << 5;
    static final int APPEND_RESULT_REPLY_BYTES_AVAILABLE = 1 << 6;
    static final int APPEND_RESULT_DESKTOP_NOTIFICATION = 1 << 7;
    static final int APPEND_RESULT_PROGRESS = 1 << 8;

    static final int MODE_CURSOR_KEYS_APPLICATION = 1;
    static final int MODE_KEYPAD_APPLICATION = 1 << 1;
    static final int MODE_MOUSE_TRACKING = 1 << 2;
    static final int MODE_BRACKETED_PASTE = 1 << 3;
    static final int MODE_MOUSE_PROTOCOL_SGR = 1 << 4;

    static final int PROGRESS_STATE_NONE = 0;
    static final int PROGRESS_STATE_SET = 1;
    static final int PROGRESS_STATE_ERROR = 2;
    static final int PROGRESS_STATE_INDETERMINATE = 3;
    static final int PROGRESS_STATE_PAUSE = 4;

    static final int TRANSCRIPT_FLAG_JOIN_LINES = 1;
    static final int TRANSCRIPT_FLAG_TRIM = 1 << 1;

    private static final boolean LIBRARY_LOADED;

    static {
        boolean loaded;
        try {
            System.loadLibrary("termux-ghostty");
            loaded = true;
            GhosttyLog.info("Loaded libtermux-ghostty.so");
        } catch (UnsatisfiedLinkError error) {
            loaded = false;
            GhosttyLog.warn("Failed to load libtermux-ghostty.so", error);
        }
        LIBRARY_LOADED = loaded;
    }

    private GhosttyNative() {
    }

    static boolean isLibraryLoaded() {
        return LIBRARY_LOADED;
    }

    static native long nativeCreate(int columns, int rows, int transcriptRows, int cellWidthPixels, int cellHeightPixels);

    static native void nativeDestroy(long nativeHandle);

    static native void nativeReset(long nativeHandle);

    static native int nativeResize(long nativeHandle, int columns, int rows, int cellWidthPixels, int cellHeightPixels);

    static native int nativeQueueMouseEvent(
        long nativeHandle,
        int action,
        int button,
        int modifiers,
        float surfaceX,
        float surfaceY,
        int screenWidthPx,
        int screenHeightPx,
        int cellWidthPx,
        int cellHeightPx,
        int paddingTopPx,
        int paddingRightPx,
        int paddingBottomPx,
        int paddingLeftPx
    );

    static native int nativeAppend(long nativeHandle, byte[] data, int offset, int length);

    static native int nativeDrainPendingOutput(long nativeHandle, byte[] buffer, int offset, int length);

    static native int nativeSetViewportTopRow(long nativeHandle, int topRow);

    static native void nativeRequestFullSnapshotRefresh(long nativeHandle);

    static native int nativeFillSnapshotCurrentViewport(long nativeHandle, ByteBuffer buffer, int capacity);

    static native int nativeFillViewportLinks(long nativeHandle, ByteBuffer buffer, int capacity);

    static native int nativeFillSnapshot(long nativeHandle, int topRow, ByteBuffer buffer, int capacity);

    @Nullable
    static native String nativeConsumeTitle(long nativeHandle);

    @Nullable
    static native String nativeConsumeClipboardText(long nativeHandle);

    @Nullable
    static native String nativeConsumeNotificationTitle(long nativeHandle);

    @Nullable
    static native String nativeConsumeNotificationBody(long nativeHandle);

    static native int nativeGetProgressState(long nativeHandle);

    static native int nativeGetProgressValue(long nativeHandle);

    static native long nativeGetProgressGeneration(long nativeHandle);

    static native void nativeClearProgress(long nativeHandle);

    static native int nativeGetColumns(long nativeHandle);

    static native int nativeGetRows(long nativeHandle);

    static native int nativeGetActiveRows(long nativeHandle);

    static native int nativeGetActiveTranscriptRows(long nativeHandle);

    static native int nativeGetModeBits(long nativeHandle);

    static native int nativeGetCursorRow(long nativeHandle);

    static native int nativeGetCursorCol(long nativeHandle);

    static native int nativeGetCursorStyle(long nativeHandle);

    static native boolean nativeIsCursorVisible(long nativeHandle);

    static native boolean nativeIsReverseVideo(long nativeHandle);

    static native boolean nativeIsAlternateBufferActive(long nativeHandle);

    @Nullable
    static native String nativeGetSelectedText(long nativeHandle, int startColumn, int startRow, int endColumn, int endRow, int flags);

    @Nullable
    static native String nativeGetWordAtLocation(long nativeHandle, int column, int row);

    @Nullable
    static native String nativeGetTranscriptText(long nativeHandle, int flags);
}
