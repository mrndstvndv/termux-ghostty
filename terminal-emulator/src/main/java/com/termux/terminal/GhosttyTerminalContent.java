package com.termux.terminal;

import android.os.SystemClock;

import androidx.annotation.Nullable;

public final class GhosttyTerminalContent implements TerminalContent, AutoCloseable {

    private static final int PERF_LOG_INTERVAL_FRAMES = 120;
    private static final long SLOW_SNAPSHOT_FILL_NANOS = 8_000_000L;
    private static final long SLOW_SNAPSHOT_PARSE_NANOS = 4_000_000L;

    private long mNativeHandle;
    private boolean mCursorBlinkingEnabled;
    private boolean mCursorBlinkState = true;
    private long mSnapshotFillCount;
    private long mSnapshotNativeFillTotalNanos;
    private long mSnapshotParseTotalNanos;
    private long mSnapshotTotalNanos;

    public GhosttyTerminalContent(int columns, int rows, int transcriptRows, int cellWidthPixels, int cellHeightPixels) {
        if (!GhosttyNative.isLibraryLoaded()) {
            throw new IllegalStateException("libtermux-ghostty.so is not available");
        }

        mNativeHandle = GhosttyNative.nativeCreate(columns, rows, transcriptRows, cellWidthPixels, cellHeightPixels);
        if (mNativeHandle == 0) {
            GhosttyLog.error("nativeCreate returned null handle for columns=" + columns + ", rows=" + rows + ", transcriptRows=" + transcriptRows + ", cellWidth=" + cellWidthPixels + ", cellHeight=" + cellHeightPixels);
            throw new IllegalStateException("Failed to create Ghostty terminal");
        }

        GhosttyLog.info("Created Ghostty terminal handle=0x" + Long.toHexString(mNativeHandle) + " columns=" + columns + " rows=" + rows + " transcriptRows=" + transcriptRows + " cellWidth=" + cellWidthPixels + " cellHeight=" + cellHeightPixels);
    }

    @Override
    public void close() {
        long nativeHandle = mNativeHandle;
        if (nativeHandle == 0) {
            return;
        }

        GhosttyLog.info("Destroying Ghostty terminal handle=0x" + Long.toHexString(nativeHandle));
        mNativeHandle = 0;
        GhosttyNative.nativeDestroy(nativeHandle);
    }

    public synchronized void reset() {
        long nativeHandle = requireNativeHandle();
        GhosttyLog.debug("Resetting Ghostty terminal handle=0x" + Long.toHexString(nativeHandle));
        GhosttyNative.nativeReset(nativeHandle);
    }

    public synchronized int resize(int columns, int rows, int cellWidthPixels, int cellHeightPixels) {
        long nativeHandle = requireNativeHandle();
        int result = GhosttyNative.nativeResize(nativeHandle, columns, rows, cellWidthPixels, cellHeightPixels);
        if (result != 0) {
            GhosttyLog.error("nativeResize failed handle=0x" + Long.toHexString(nativeHandle) + " columns=" + columns + " rows=" + rows + " cellWidth=" + cellWidthPixels + " cellHeight=" + cellHeightPixels + " result=" + result);
        } else {
            GhosttyLog.debug("Resized Ghostty terminal handle=0x" + Long.toHexString(nativeHandle) + " columns=" + columns + " rows=" + rows + " cellWidth=" + cellWidthPixels + " cellHeight=" + cellHeightPixels);
        }
        return result;
    }

    public synchronized int setViewportTopRow(int topRow) {
        long nativeHandle = requireNativeHandle();
        return GhosttyNative.nativeSetViewportTopRow(nativeHandle, topRow);
    }

    public synchronized void requestFullSnapshotRefresh() {
        GhosttyNative.nativeRequestFullSnapshotRefresh(requireNativeHandle());
    }

    public synchronized int append(byte[] data, int offset, int length) {
        validateRange(data, offset, length);
        if (length == 0) {
            return 0;
        }

        return GhosttyNative.nativeAppend(requireNativeHandle(), data, offset, length);
    }

    public synchronized int queueMouseEvent(GhosttyMouseEvent event) {
        return GhosttyNative.nativeQueueMouseEvent(
            requireNativeHandle(),
            event.action,
            event.button,
            event.modifiers,
            event.surfaceX,
            event.surfaceY,
            event.screenWidthPx,
            event.screenHeightPx,
            event.cellWidthPx,
            event.cellHeightPx,
            event.paddingTopPx,
            event.paddingRightPx,
            event.paddingBottomPx,
            event.paddingLeftPx
        );
    }

    public synchronized int drainPendingOutput(byte[] buffer, int offset, int length) {
        validateRange(buffer, offset, length);
        if (length == 0) {
            return 0;
        }

        return GhosttyNative.nativeDrainPendingOutput(requireNativeHandle(), buffer, offset, length);
    }

    @Nullable
    public synchronized String consumePendingTitle() {
        return GhosttyNative.nativeConsumeTitle(requireNativeHandle());
    }

    @Nullable
    public synchronized String consumePendingClipboardText() {
        return GhosttyNative.nativeConsumeClipboardText(requireNativeHandle());
    }

    @Nullable
    public synchronized String consumePendingNotificationTitle() {
        return GhosttyNative.nativeConsumeNotificationTitle(requireNativeHandle());
    }

    @Nullable
    public synchronized String consumePendingNotificationBody() {
        return GhosttyNative.nativeConsumeNotificationBody(requireNativeHandle());
    }

    public synchronized int getProgressState() {
        return GhosttyNative.nativeGetProgressState(requireNativeHandle());
    }

    public synchronized int getProgressValue() {
        return GhosttyNative.nativeGetProgressValue(requireNativeHandle());
    }

    public synchronized long getProgressGeneration() {
        return GhosttyNative.nativeGetProgressGeneration(requireNativeHandle());
    }

    public synchronized void clearProgress() {
        GhosttyNative.nativeClearProgress(requireNativeHandle());
    }

    public synchronized boolean isCursorKeysApplicationMode() {
        return (getModeBits() & GhosttyNative.MODE_CURSOR_KEYS_APPLICATION) != 0;
    }

    public synchronized boolean isKeypadApplicationMode() {
        return (getModeBits() & GhosttyNative.MODE_KEYPAD_APPLICATION) != 0;
    }

    public synchronized boolean isBracketedPasteMode() {
        return (getModeBits() & GhosttyNative.MODE_BRACKETED_PASTE) != 0;
    }

    public synchronized boolean isMouseProtocolSgr() {
        return (getModeBits() & GhosttyNative.MODE_MOUSE_PROTOCOL_SGR) != 0;
    }

    public synchronized void setCursorBlinkingEnabled(boolean enabled) {
        mCursorBlinkingEnabled = enabled;
    }

    public synchronized void setCursorBlinkState(boolean visible) {
        mCursorBlinkState = visible;
    }

    public synchronized boolean isCursorEnabled() {
        return GhosttyNative.nativeIsCursorVisible(requireNativeHandle());
    }

    @Override
    public synchronized int getColumns() {
        return GhosttyNative.nativeGetColumns(requireNativeHandle());
    }

    @Override
    public synchronized int getRows() {
        return GhosttyNative.nativeGetRows(requireNativeHandle());
    }

    @Override
    public synchronized int getActiveRows() {
        return GhosttyNative.nativeGetActiveRows(requireNativeHandle());
    }

    @Override
    public synchronized int getActiveTranscriptRows() {
        return GhosttyNative.nativeGetActiveTranscriptRows(requireNativeHandle());
    }

    public synchronized int getModeBits() {
        return GhosttyNative.nativeGetModeBits(requireNativeHandle());
    }

    @Override
    public synchronized boolean isAlternateBufferActive() {
        return GhosttyNative.nativeIsAlternateBufferActive(requireNativeHandle());
    }

    @Override
    public synchronized boolean isMouseTrackingActive() {
        return (getModeBits() & GhosttyNative.MODE_MOUSE_TRACKING) != 0;
    }

    @Override
    public synchronized boolean isReverseVideo() {
        return GhosttyNative.nativeIsReverseVideo(requireNativeHandle());
    }

    @Override
    public synchronized int getCursorRow() {
        return GhosttyNative.nativeGetCursorRow(requireNativeHandle());
    }

    @Override
    public synchronized int getCursorCol() {
        return GhosttyNative.nativeGetCursorCol(requireNativeHandle());
    }

    @Override
    public synchronized int getCursorStyle() {
        return GhosttyNative.nativeGetCursorStyle(requireNativeHandle());
    }

    @Override
    public synchronized boolean shouldCursorBeVisible() {
        if (!GhosttyNative.nativeIsCursorVisible(requireNativeHandle())) {
            return false;
        }

        return !mCursorBlinkingEnabled || mCursorBlinkState;
    }

    @Nullable
    @Override
    public synchronized String getSelectedText(int startColumn, int startRow, int endColumn, int endRow) {
        return GhosttyNative.nativeGetSelectedText(requireNativeHandle(), startColumn, startRow, endColumn, endRow, 0);
    }

    @Nullable
    @Override
    public synchronized String getWordAtLocation(int column, int row) {
        return GhosttyNative.nativeGetWordAtLocation(requireNativeHandle(), column, row);
    }

    @Nullable
    @Override
    public synchronized String getTranscriptText(boolean joinLines, boolean trim) {
        int flags = 0;
        if (joinLines) {
            flags |= GhosttyNative.TRANSCRIPT_FLAG_JOIN_LINES;
        }
        if (trim) {
            flags |= GhosttyNative.TRANSCRIPT_FLAG_TRIM;
        }

        return GhosttyNative.nativeGetTranscriptText(requireNativeHandle(), flags);
    }

    @Override
    public int fillSnapshot(int topRow, ScreenSnapshot snapshot) {
        setViewportTopRow(topRow);
        return fillSnapshot(snapshot);
    }

    public int fillSnapshot(ScreenSnapshot snapshot) {
        long snapshotStartNanos = SystemClock.elapsedRealtimeNanos();
        long nativeHandle;
        int requiredBytes;

        synchronized (this) {
            nativeHandle = requireNativeHandle();
            snapshot.getBuffer().clear();

            requiredBytes = GhosttyNative.nativeFillSnapshotCurrentViewport(nativeHandle, snapshot.getBuffer(), snapshot.getCapacityBytes());
            if (requiredBytes < 0) {
                GhosttyLog.error("nativeFillSnapshotCurrentViewport failed handle=0x" + Long.toHexString(nativeHandle) + " capacity=" + snapshot.getCapacityBytes());
                throw new IllegalStateException("nativeFillSnapshotCurrentViewport failed");
            }
            if (requiredBytes > snapshot.getCapacityBytes()) {
                GhosttyLog.error("nativeFillSnapshotCurrentViewport buffer too small handle=0x" + Long.toHexString(nativeHandle) + " required=" + requiredBytes + " capacity=" + snapshot.getCapacityBytes());
                throw new IllegalStateException("nativeFillSnapshotCurrentViewport buffer too small: required=" + requiredBytes + ", capacity=" + snapshot.getCapacityBytes());
            }
        }

        long nativeFillDurationNanos = SystemClock.elapsedRealtimeNanos() - snapshotStartNanos;
        try {
            snapshot.markNativeSnapshot(requiredBytes);
            snapshot.applyCursorBlinkState(mCursorBlinkingEnabled, mCursorBlinkState);
        } catch (RuntimeException error) {
            GhosttyLog.error("markNativeSnapshot failed required=" + requiredBytes, error);
            throw error;
        }

        long totalDurationNanos = SystemClock.elapsedRealtimeNanos() - snapshotStartNanos;
        long parseDurationNanos = Math.max(0L, totalDurationNanos - nativeFillDurationNanos);
        logSnapshotFillPerfIfNeeded(nativeHandle, snapshot.getTopRow(), snapshot.getRows(), snapshot.getColumns(), requiredBytes,
            nativeFillDurationNanos, parseDurationNanos, totalDurationNanos);
        return requiredBytes;
    }

    public int fillViewportLinks(ViewportLinkSnapshot snapshot) {
        if (snapshot == null) {
            throw new IllegalArgumentException("snapshot must not be null");
        }

        long nativeHandle;
        int requiredBytes;
        synchronized (this) {
            nativeHandle = requireNativeHandle();
            snapshot.getBuffer().clear();

            requiredBytes = GhosttyNative.nativeFillViewportLinks(nativeHandle, snapshot.getBuffer(), snapshot.getCapacityBytes());
            if (requiredBytes < 0) {
                GhosttyLog.error("nativeFillViewportLinks failed handle=0x" + Long.toHexString(nativeHandle)
                    + " capacity=" + snapshot.getCapacityBytes());
                throw new IllegalStateException("nativeFillViewportLinks failed");
            }
            if (requiredBytes > snapshot.getCapacityBytes()) {
                GhosttyLog.error("nativeFillViewportLinks buffer too small handle=0x"
                    + Long.toHexString(nativeHandle) + " required=" + requiredBytes
                    + " capacity=" + snapshot.getCapacityBytes());
                throw new IllegalStateException("nativeFillViewportLinks buffer too small: required="
                    + requiredBytes + ", capacity=" + snapshot.getCapacityBytes());
            }
        }

        try {
            snapshot.markNativeSnapshot(requiredBytes);
        } catch (RuntimeException error) {
            GhosttyLog.error("markNativeViewportLinkSnapshot failed required=" + requiredBytes, error);
            throw error;
        }

        return requiredBytes;
    }

    private void logSnapshotFillPerfIfNeeded(long nativeHandle, int topRow, int rows, int columns, int requiredBytes,
                                             long nativeFillDurationNanos, long parseDurationNanos, long totalDurationNanos) {
        mSnapshotFillCount++;
        mSnapshotNativeFillTotalNanos += nativeFillDurationNanos;
        mSnapshotParseTotalNanos += parseDurationNanos;
        mSnapshotTotalNanos += totalDurationNanos;

        boolean slowFill = totalDurationNanos >= SLOW_SNAPSHOT_FILL_NANOS || parseDurationNanos >= SLOW_SNAPSHOT_PARSE_NANOS;
        boolean periodic = GhosttyLog.isEnabled() && (mSnapshotFillCount % PERF_LOG_INTERVAL_FRAMES) == 0;
        if (!slowFill && !periodic) {
            return;
        }

        long averageNativeFillNanos = mSnapshotNativeFillTotalNanos / mSnapshotFillCount;
        long averageParseNanos = mSnapshotParseTotalNanos / mSnapshotFillCount;
        long averageTotalNanos = mSnapshotTotalNanos / mSnapshotFillCount;
        String message = "Snapshot fill perf handle=0x" + Long.toHexString(nativeHandle)
            + " count=" + mSnapshotFillCount
            + " topRow=" + topRow
            + " rows=" + rows
            + " columns=" + columns
            + " requiredBytes=" + requiredBytes
            + " nativeFillMs=" + formatDurationMillis(nativeFillDurationNanos)
            + " parseMs=" + formatDurationMillis(parseDurationNanos)
            + " totalMs=" + formatDurationMillis(totalDurationNanos)
            + " avgNativeFillMs=" + formatDurationMillis(averageNativeFillNanos)
            + " avgParseMs=" + formatDurationMillis(averageParseNanos)
            + " avgTotalMs=" + formatDurationMillis(averageTotalNanos);

        if (slowFill) {
            GhosttyLog.warn(message);
            return;
        }

        GhosttyLog.debug(message);
    }

    private static String formatDurationMillis(long durationNanos) {
        return Double.toString(durationNanos / 1_000_000.0d);
    }

    private long requireNativeHandle() {
        if (mNativeHandle == 0) {
            throw new IllegalStateException("Ghostty terminal is closed");
        }

        return mNativeHandle;
    }

    private static void validateRange(byte[] data, int offset, int length) {
        if (data == null) {
            throw new IllegalArgumentException("data must not be null");
        }
        if (offset < 0) {
            throw new IllegalArgumentException("offset must be >= 0");
        }
        if (length < 0) {
            throw new IllegalArgumentException("length must be >= 0");
        }
        if (offset > data.length - length) {
            throw new IllegalArgumentException("offset + length exceeds array length");
        }
    }
}
