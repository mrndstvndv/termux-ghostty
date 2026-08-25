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
        applyColorScheme(TerminalColors.COLOR_SCHEME.mDefaultColors);
    }

    @Override
    public synchronized void close() {
        long nativeHandle = mNativeHandle;
        if (nativeHandle == 0) {
            return;
        }

        GhosttyLog.info("Destroying Ghostty terminal handle=0x" + Long.toHexString(nativeHandle));
        mNativeHandle = 0;
        GhosttyNative.nativeDestroy(nativeHandle);
    }

    public synchronized void reset() {
        if (mNativeHandle == 0) {
            GhosttyLog.warn("reset called on a closed terminal content");
            return;
        }
        long nativeHandle = mNativeHandle;
        GhosttyLog.debug("Resetting Ghostty terminal handle=0x" + Long.toHexString(nativeHandle));
        GhosttyNative.nativeReset(nativeHandle);
    }

    public synchronized void applyColorScheme(int[] colors) {
        validateColorScheme(colors);
        if (mNativeHandle == 0) {
            GhosttyLog.warn("applyColorScheme called on a closed terminal content");
            return;
        }
        int result = GhosttyNative.nativeSetColorScheme(mNativeHandle, colors);
        if (result != 0) {
            throw new IllegalStateException("Failed to apply Ghostty color scheme: " + result);
        }
    }

    public synchronized int resize(int columns, int rows, int cellWidthPixels, int cellHeightPixels) {
        if (mNativeHandle == 0) {
            GhosttyLog.warn("resize called on a closed terminal content");
            return 0;
        }
        long nativeHandle = mNativeHandle;
        int result = GhosttyNative.nativeResize(nativeHandle, columns, rows, cellWidthPixels, cellHeightPixels);
        if (result != 0) {
            GhosttyLog.error("nativeResize failed handle=0x" + Long.toHexString(nativeHandle) + " columns=" + columns + " rows=" + rows + " cellWidth=" + cellWidthPixels + " cellHeight=" + cellHeightPixels + " result=" + result);
        } else {
            GhosttyLog.debug("Resized Ghostty terminal handle=0x" + Long.toHexString(nativeHandle) + " columns=" + columns + " rows=" + rows + " cellWidth=" + cellWidthPixels + " cellHeight=" + cellHeightPixels);
        }
        return result;
    }

    public synchronized int setViewportTopRow(int topRow) {
        if (mNativeHandle == 0) {
            GhosttyLog.warn("setViewportTopRow called on a closed terminal content");
            return 0;
        }
        return GhosttyNative.nativeSetViewportTopRow(mNativeHandle, topRow);
    }

    /**
     * Current kitty keyboard protocol flags (0 = disabled). Bit 0 is
     * "disambiguate escape codes"; when set the input layer should encode keys
     * such as ESC as CSI-u (e.g. ESC -> "\e[27u") so the remote does not wait
     * an escape-sequence disambiguation timeout on a lone ESC.
     */
    public synchronized int getKittyKeyboardFlags() {
        if (mNativeHandle == 0) {
            return 0;
        }
        return GhosttyNative.nativeGetKittyKeyboardFlags(mNativeHandle);
    }


    public synchronized void requestFullSnapshotRefresh() {
        if (mNativeHandle == 0) {
            GhosttyLog.warn("requestFullSnapshotRefresh called on a closed terminal content");
            return;
        }
        GhosttyNative.nativeRequestFullSnapshotRefresh(mNativeHandle);
    }

    public synchronized int append(byte[] data, int offset, int length) {
        validateRange(data, offset, length);
        if (length == 0) {
            return 0;
        }
        if (mNativeHandle == 0) {
            GhosttyLog.warn("append called on a closed terminal content");
            return 0;
        }

        return GhosttyNative.nativeAppend(mNativeHandle, data, offset, length);
    }

    public synchronized long getCompressionActivity() {
        if (mNativeHandle == 0) {
            return 0L;
        }
        return GhosttyNative.nativeGetCompressionActivity(mNativeHandle);
    }

    public synchronized int compressScrollback() {
        if (mNativeHandle == 0) {
            return GhosttyNative.COMPRESSION_RESULT_UNSUPPORTED;
        }
        return GhosttyNative.nativeCompressScrollback(mNativeHandle);
    }

    public synchronized byte[] captureStateSnapshot() {
        if (mNativeHandle == 0) {
            throw new IllegalStateException("Cannot capture a closed terminal");
        }
        byte[] snapshot = GhosttyNative.nativeCaptureStateSnapshot(mNativeHandle);
        if (snapshot == null) {
            throw new IllegalStateException("Could not capture terminal state snapshot");
        }
        return snapshot;
    }

    public synchronized void restoreStateSnapshot(byte[] snapshot) {
        if (snapshot == null || snapshot.length == 0) {
            throw new IllegalArgumentException("snapshot must not be empty");
        }
        if (mNativeHandle == 0) {
            throw new IllegalStateException("Cannot restore a closed terminal");
        }
        if (GhosttyNative.nativeRestoreStateSnapshot(mNativeHandle, snapshot) != 0) {
            throw new IllegalArgumentException("Invalid terminal state snapshot");
        }
    }

    public synchronized int queueMouseEvent(GhosttyMouseEvent event) {
        if (mNativeHandle == 0) {
            GhosttyLog.warn("queueMouseEvent called on a closed terminal content");
            return 0;
        }
        return GhosttyNative.nativeQueueMouseEvent(
            mNativeHandle,
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

    public synchronized int setFocus(boolean focused) {
        if (mNativeHandle == 0) {
            GhosttyLog.warn("setFocus called on a closed terminal content");
            return -1;
        }
        return GhosttyNative.nativeSetFocus(mNativeHandle, focused);
    }

    public synchronized int drainPendingOutput(byte[] buffer, int offset, int length) {
        validateRange(buffer, offset, length);
        if (length == 0) {
            return 0;
        }
        if (mNativeHandle == 0) {
            GhosttyLog.warn("drainPendingOutput called on a closed terminal content");
            return 0;
        }

        return GhosttyNative.nativeDrainPendingOutput(mNativeHandle, buffer, offset, length);
    }

    @Nullable
    public synchronized String consumePendingTitle() {
        if (mNativeHandle == 0) {
            return null;
        }
        return GhosttyNative.nativeConsumeTitle(mNativeHandle);
    }

    @Nullable
    public synchronized String consumePendingClipboardText() {
        if (mNativeHandle == 0) {
            return null;
        }
        return GhosttyNative.nativeConsumeClipboardText(mNativeHandle);
    }

    @Nullable
    public synchronized String consumePendingNotificationTitle() {
        if (mNativeHandle == 0) {
            return null;
        }
        return GhosttyNative.nativeConsumeNotificationTitle(mNativeHandle);
    }

    @Nullable
    public synchronized String consumePendingNotificationBody() {
        if (mNativeHandle == 0) {
            return null;
        }
        return GhosttyNative.nativeConsumeNotificationBody(mNativeHandle);
    }

    public synchronized int getProgressState() {
        if (mNativeHandle == 0) {
            return 0;
        }
        return GhosttyNative.nativeGetProgressState(mNativeHandle);
    }

    public synchronized int getProgressValue() {
        if (mNativeHandle == 0) {
            return 0;
        }
        return GhosttyNative.nativeGetProgressValue(mNativeHandle);
    }

    public synchronized long getProgressGeneration() {
        if (mNativeHandle == 0) {
            return 0L;
        }
        return GhosttyNative.nativeGetProgressGeneration(mNativeHandle);
    }

    public synchronized void clearProgress() {
        if (mNativeHandle == 0) {
            return;
        }
        GhosttyNative.nativeClearProgress(mNativeHandle);
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
        if (mNativeHandle == 0) {
            return false;
        }
        return GhosttyNative.nativeIsCursorVisible(mNativeHandle);
    }

    @Override
    public synchronized int getColumns() {
        if (mNativeHandle == 0) {
            return 0;
        }
        return GhosttyNative.nativeGetColumns(mNativeHandle);
    }

    @Override
    public synchronized int getRows() {
        if (mNativeHandle == 0) {
            return 0;
        }
        return GhosttyNative.nativeGetRows(mNativeHandle);
    }

    @Override
    public synchronized int getActiveRows() {
        if (mNativeHandle == 0) {
            return 0;
        }
        return GhosttyNative.nativeGetActiveRows(mNativeHandle);
    }

    @Override
    public synchronized int getActiveTranscriptRows() {
        if (mNativeHandle == 0) {
            return 0;
        }
        return GhosttyNative.nativeGetActiveTranscriptRows(mNativeHandle);
    }

    public synchronized int getModeBits() {
        if (mNativeHandle == 0) {
            return 0;
        }
        return GhosttyNative.nativeGetModeBits(mNativeHandle);
    }

    @Override
    public synchronized boolean isAlternateBufferActive() {
        if (mNativeHandle == 0) {
            return false;
        }
        return GhosttyNative.nativeIsAlternateBufferActive(mNativeHandle);
    }

    @Override
    public synchronized boolean isMouseTrackingActive() {
        return (getModeBits() & GhosttyNative.MODE_MOUSE_TRACKING) != 0;
    }

    @Override
    public synchronized boolean isReverseVideo() {
        if (mNativeHandle == 0) {
            return false;
        }
        return GhosttyNative.nativeIsReverseVideo(mNativeHandle);
    }

    @Override
    public synchronized int getCursorRow() {
        if (mNativeHandle == 0) {
            return 0;
        }
        return GhosttyNative.nativeGetCursorRow(mNativeHandle);
    }

    @Override
    public synchronized int getCursorCol() {
        if (mNativeHandle == 0) {
            return 0;
        }
        return GhosttyNative.nativeGetCursorCol(mNativeHandle);
    }

    @Override
    public synchronized int getCursorStyle() {
        if (mNativeHandle == 0) {
            return 0;
        }
        return GhosttyNative.nativeGetCursorStyle(mNativeHandle);
    }

    @Override
    public synchronized boolean shouldCursorBeVisible() {
        if (mNativeHandle == 0) {
            return false;
        }
        if (!GhosttyNative.nativeIsCursorVisible(mNativeHandle)) {
            return false;
        }

        return !mCursorBlinkingEnabled || mCursorBlinkState;
    }

    @Nullable
    @Override
    public synchronized String getSelectedText(int startColumn, int startRow, int endColumn, int endRow) {
        if (mNativeHandle == 0) {
            return null;
        }
        return GhosttyNative.nativeGetSelectedText(mNativeHandle, startColumn, startRow, endColumn, endRow, 0);
    }

    @Nullable
    @Override
    public synchronized String getWordAtLocation(int column, int row) {
        if (mNativeHandle == 0) {
            return null;
        }
        return GhosttyNative.nativeGetWordAtLocation(mNativeHandle, column, row);
    }

    @Nullable
    @Override
    public synchronized String getTranscriptText(boolean joinLines, boolean trim) {
        if (mNativeHandle == 0) {
            return null;
        }
        int flags = 0;
        if (joinLines) {
            flags |= GhosttyNative.TRANSCRIPT_FLAG_JOIN_LINES;
        }
        if (trim) {
            flags |= GhosttyNative.TRANSCRIPT_FLAG_TRIM;
        }

        return GhosttyNative.nativeGetTranscriptText(mNativeHandle, flags);
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
            if (mNativeHandle == 0) {
                GhosttyLog.warn("fillSnapshot called on a closed terminal content");
                return 0;
            }
            nativeHandle = mNativeHandle;
            snapshot.getBuffer().clear();

            requiredBytes = GhosttyNative.nativeFillSnapshotCurrentViewport(nativeHandle, snapshot.getBuffer(), snapshot.getCapacityBytes());
            if (requiredBytes < 0) {
                GhosttyLog.error("nativeFillSnapshotCurrentViewport failed handle=0x" + Long.toHexString(nativeHandle) + " capacity=" + snapshot.getCapacityBytes());
                throw new IllegalStateException("nativeFillSnapshotCurrentViewport failed");
            }
            if (requiredBytes > snapshot.getCapacityBytes()) {
                snapshot.ensureCapacity(requiredBytes);
                snapshot.getBuffer().clear();
                requiredBytes = GhosttyNative.nativeFillSnapshotCurrentViewport(nativeHandle, snapshot.getBuffer(), snapshot.getCapacityBytes());
                if (requiredBytes < 0 || requiredBytes > snapshot.getCapacityBytes()) {
                    GhosttyLog.error("nativeFillSnapshotCurrentViewport buffer too small after resize handle=0x" + Long.toHexString(nativeHandle) + " required=" + requiredBytes + " capacity=" + snapshot.getCapacityBytes());
                    throw new IllegalStateException("nativeFillSnapshotCurrentViewport buffer too small: required=" + requiredBytes + ", capacity=" + snapshot.getCapacityBytes());
                }
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
            if (mNativeHandle == 0) {
                GhosttyLog.warn("fillViewportLinks called on a closed terminal content");
                return 0;
            }
            nativeHandle = mNativeHandle;
            snapshot.getBuffer().clear();

            requiredBytes = GhosttyNative.nativeFillViewportLinks(nativeHandle, snapshot.getBuffer(), snapshot.getCapacityBytes());
            if (requiredBytes < 0) {
                GhosttyLog.error("nativeFillViewportLinks failed handle=0x" + Long.toHexString(nativeHandle)
                    + " capacity=" + snapshot.getCapacityBytes());
                throw new IllegalStateException("nativeFillViewportLinks failed");
            }
            if (requiredBytes > snapshot.getCapacityBytes()) {
                snapshot.ensureCapacity(requiredBytes);
                snapshot.getBuffer().clear();
                requiredBytes = GhosttyNative.nativeFillViewportLinks(nativeHandle, snapshot.getBuffer(), snapshot.getCapacityBytes());
                if (requiredBytes < 0 || requiredBytes > snapshot.getCapacityBytes()) {
                    GhosttyLog.error("nativeFillViewportLinks buffer too small after resize handle=0x"
                        + Long.toHexString(nativeHandle) + " required=" + requiredBytes
                        + " capacity=" + snapshot.getCapacityBytes());
                    throw new IllegalStateException("nativeFillViewportLinks buffer too small: required="
                        + requiredBytes + ", capacity=" + snapshot.getCapacityBytes());
                }
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

    long requireNativeHandle() {
        return mNativeHandle;
    }

    private static void validateColorScheme(int[] colors) {
        if (colors == null) {
            throw new IllegalArgumentException("colors must not be null");
        }
        if (colors.length < TextStyle.NUM_INDEXED_COLORS) {
            throw new IllegalArgumentException("colors length must be >= " + TextStyle.NUM_INDEXED_COLORS);
        }
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
