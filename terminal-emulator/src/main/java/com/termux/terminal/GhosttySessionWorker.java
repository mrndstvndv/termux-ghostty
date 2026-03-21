package com.termux.terminal;

import android.os.Handler;
import android.os.Looper;
import android.os.Message;
import android.os.Process;
import android.os.SystemClock;

import androidx.annotation.NonNull;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * A dedicated worker thread for Ghostty-backed terminal sessions.
 * <p>
 * This thread is the sole owner of the Ghostty native state and is responsible for:
 * <ul>
 *     <li>Appending PTY output to the native Ghostty terminal.</li>
 *     <li>Handling resizes, viewport scroll, and resets.</li>
 *     <li>Building frame publications for rendering on the UI thread.</li>
 *     <li>Draining pending output from Ghostty (e.g. CPR, DSR).</li>
 *     <li>Handling side-effects like title changes, bell, and clipboard.</li>
 * </ul>
 */
final class GhosttySessionWorker extends Thread {

    private static final int MSG_APPEND = 1;
    private static final int MSG_RESIZE = 2;
    private static final int MSG_RESET = 3;
    private static final int MSG_SHUTDOWN = 4;
    private static final int MSG_APPEND_DIRECT = 5;
    private static final int MSG_BUILD_SNAPSHOT = 6;
    private static final int MSG_VIEWPORT_SCROLL = 7;
    private static final int MSG_REQUEST_FULL_SNAPSHOT_REFRESH = 8;
    private static final int MSG_MOUSE_EVENT = 9;
    private static final int MSG_PROGRESS_TIMEOUT = 10;
    private static final int MSG_APPLY_COLOR_SCHEME = 11;

    private static final long SNAPSHOT_INTERVAL_MILLIS = 16; // ~60fps
    private static final long SNAPSHOT_INTERVAL_BUSY_MILLIS = 33; // ~30fps under sustained backlog
    private static final int MAX_APPEND_BYTES_PER_SLICE = 64 * 1024;
    private static final long MAX_APPEND_TIME_MILLIS = 8;
    private static final int PERF_LOG_INTERVAL_FRAMES = 120;
    private static final long SLOW_SNAPSHOT_BUILD_NANOS = 8_000_000L;
    private static final long PROGRESS_TIMEOUT_MILLIS = 15_000L;

    private final TerminalSession mSession;
    private final GhosttyTerminalContent mContent;
    private final ByteQueue mQueue;
    private final Handler mMainThreadHandler;

    private Handler mWorkerHandler;
    private final byte[] mReadBuffer = new byte[32 * 1024];
    private final byte[] mDrainBuffer = new byte[4096];

    private final AtomicReference<ScreenSnapshot> mPublishedSnapshot = new AtomicReference<>();
    private final AtomicReference<FrameDelta> mPublishedFrameDelta = new AtomicReference<>();
    private final ScreenSnapshot mSnapshotA = new ScreenSnapshot();
    private final ScreenSnapshot mSnapshotB = new ScreenSnapshot();
    private final ViewportLinkSnapshot mViewportLinksA = new ViewportLinkSnapshot();
    private final ViewportLinkSnapshot mViewportLinksB = new ViewportLinkSnapshot();
    private ScreenSnapshot mCurrentStaging = mSnapshotA;
    private ViewportLinkSnapshot mCurrentViewportLinkStaging = mViewportLinksA;

    private final AtomicBoolean mSnapshotDirty = new AtomicBoolean(true);
    private final AtomicBoolean mUIUpdatePending = new AtomicBoolean(false);
    private final AtomicBoolean mAppendMessageQueued = new AtomicBoolean(false);
    private final AtomicInteger mPendingFrameReasonFlags = new AtomicInteger(0);

    private int mPendingColumns;
    private int mPendingRows;
    private int mPendingCellWidthPixels;
    private int mPendingCellHeightPixels;
    private int mCurrentCellWidthPixels;
    private int mCurrentCellHeightPixels;
    private int mPendingTopRow;
    private int mCurrentTopRow;
    private long mLastSnapshotTime;
    private long mPublishedFrameCount;
    private long mSnapshotBuildTotalNanos;
    private long mCoalescedBuildRequestCount;
    private long mCoalescedUiWakeupCount;

    GhosttySessionWorker(
        TerminalSession session,
        GhosttyTerminalContent content,
        ByteQueue queue,
        Handler mainThreadHandler,
        int cellWidthPixels,
        int cellHeightPixels
    ) {
        super("GhosttyWorker-" + session.mHandle);
        mSession = session;
        mContent = content;
        mQueue = queue;
        mMainThreadHandler = mainThreadHandler;
        mPendingColumns = content.getColumns();
        mPendingRows = content.getRows();
        mPendingCellWidthPixels = cellWidthPixels;
        mPendingCellHeightPixels = cellHeightPixels;
        mCurrentCellWidthPixels = cellWidthPixels;
        mCurrentCellHeightPixels = cellHeightPixels;
        mPendingTopRow = 0;
    }

    @Override
    public void run() {
        Process.setThreadPriority(Process.THREAD_PRIORITY_DEFAULT + 2);
        Looper.prepare();
        synchronized (this) {
            mWorkerHandler = new WorkerHandler(Looper.myLooper());
            notifyAll();
        }
        Looper.loop();
        GhosttyLog.info("Ghostty worker thread exiting: " + getName());
    }

    private synchronized Handler getWorkerHandler() {
        while (mWorkerHandler == null) {
            try {
                wait();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        return mWorkerHandler;
    }

    void onOutputAvailable() {
        if (!mAppendMessageQueued.compareAndSet(false, true)) {
            return;
        }
        getWorkerHandler().sendEmptyMessage(MSG_APPEND);
    }

    void resize(int columns, int rows, int cellWidthPixels, int cellHeightPixels) {
        synchronized (this) {
            mPendingColumns = columns;
            mPendingRows = rows;
            mPendingCellWidthPixels = cellWidthPixels;
            mPendingCellHeightPixels = cellHeightPixels;
        }

        Handler handler = getWorkerHandler();
        if (handler.hasMessages(MSG_RESIZE)) {
            handler.removeMessages(MSG_RESIZE);
        }
        handler.sendEmptyMessage(MSG_RESIZE);
    }

    void reset() {
        getWorkerHandler().sendEmptyMessage(MSG_RESET);
    }

    void sendMouseEvent(GhosttyMouseEvent event) {
        getWorkerHandler().obtainMessage(MSG_MOUSE_EVENT, event).sendToTarget();
    }

    void shutdown() {
        getWorkerHandler().sendEmptyMessage(MSG_SHUTDOWN);
    }

    void appendDirect(byte[] data) {
        getWorkerHandler().obtainMessage(MSG_APPEND_DIRECT, data).sendToTarget();
    }

    void requestFullSnapshotRefresh() {
        Handler handler = getWorkerHandler();
        if (handler.hasMessages(MSG_REQUEST_FULL_SNAPSHOT_REFRESH)) {
            return;
        }
        handler.sendEmptyMessage(MSG_REQUEST_FULL_SNAPSHOT_REFRESH);
    }

    void applyColorScheme(int[] colors) {
        if (colors == null) {
            return;
        }

        Handler handler = getWorkerHandler();
        if (handler.hasMessages(MSG_APPLY_COLOR_SCHEME)) {
            handler.removeMessages(MSG_APPLY_COLOR_SCHEME);
        }
        handler.obtainMessage(MSG_APPLY_COLOR_SCHEME, colors.clone()).sendToTarget();
    }

    FrameDelta getPublishedFrameDelta() {
        return mPublishedFrameDelta.get();
    }

    private void handleAppend() {
        boolean changed = false;
        int bytesProcessed = 0;
        long deadline = SystemClock.uptimeMillis() + MAX_APPEND_TIME_MILLIS;

        while (bytesProcessed < MAX_APPEND_BYTES_PER_SLICE && SystemClock.uptimeMillis() < deadline) {
            int read = mQueue.read(mReadBuffer, false);
            if (read <= 0) {
                break;
            }

            appendToNative(mReadBuffer, 0, read);
            bytesProcessed += read;
            changed = true;
        }

        int queuedBytes = mQueue.available();
        mAppendMessageQueued.set(false);
        if (queuedBytes > 0) {
            onOutputAvailable();
        }

        if (!changed) {
            return;
        }

        addPendingFrameReason(FrameDelta.REASON_APPEND);
        mSnapshotDirty.set(true);
        scheduleSnapshotBuild(queuedBytes > 0);
    }

    private void appendToNative(byte[] buffer, int offset, int length) {
        int previousTranscriptRows = mSession.mLastKnownGhosttyTranscriptRows;
        int appendResult = mContent.append(buffer, offset, length);
        updateCachedState();
        if (mSession.mLastKnownGhosttyTranscriptRows > previousTranscriptRows) {
            mSession.mScrollCounter.addAndGet(mSession.mLastKnownGhosttyTranscriptRows - previousTranscriptRows);
        }

        processAppendResult(appendResult);
    }

    private void updateCachedState() {
        mSession.mLastKnownGhosttyTranscriptRows = mContent.getActiveTranscriptRows();
        mSession.mLastKnownActiveRows = mContent.getActiveRows();
        mSession.mGhosttyModeBits = mContent.getModeBits();
        mSession.mGhosttyAlternateBufferActive = mContent.isAlternateBufferActive();
        mSession.mGhosttyReverseVideo = mContent.isReverseVideo();
        mSession.mGhosttyCursorVisible = mContent.isCursorEnabled();
        mSession.mGhosttyCursorRow = mContent.getCursorRow();
        mSession.mGhosttyCursorCol = mContent.getCursorCol();
        mSession.mGhosttyCursorStyle = mContent.getCursorStyle();
    }

    private void handleResize() {
        int columns;
        int rows;
        int cellWidthPixels;
        int cellHeightPixels;
        synchronized (this) {
            columns = mPendingColumns;
            rows = mPendingRows;
            cellWidthPixels = mPendingCellWidthPixels;
            cellHeightPixels = mPendingCellHeightPixels;
        }

        boolean sizeUnchanged = columns == mContent.getColumns()
            && rows == mContent.getRows()
            && cellWidthPixels == mCurrentCellWidthPixels
            && cellHeightPixels == mCurrentCellHeightPixels;
        if (sizeUnchanged) {
            return;
        }

        addPendingFrameReason(FrameDelta.REASON_RESIZE);
        mContent.resize(columns, rows, cellWidthPixels, cellHeightPixels);
        mCurrentCellWidthPixels = cellWidthPixels;
        mCurrentCellHeightPixels = cellHeightPixels;
        mContent.requestFullSnapshotRefresh();
        updateCachedState();
        mSnapshotDirty.set(true);
        scheduleSnapshotBuild(false);
    }

    private void handleReset() {
        addPendingFrameReason(FrameDelta.REASON_RESET);
        mContent.reset();
        mContent.applyColorScheme(TerminalColors.COLOR_SCHEME.mDefaultColors);
        mContent.requestFullSnapshotRefresh();
        updateCachedState();
        handleProgressUpdate();
        mSnapshotDirty.set(true);
        buildAndPublishSnapshot();
        mMainThreadHandler.post(mSession::onColorsChanged);
    }

    private void handleApplyColorScheme(int[] colors) {
        addPendingFrameReason(FrameDelta.REASON_COLOR_SCHEME);
        mContent.applyColorScheme(colors);
        mContent.requestFullSnapshotRefresh();
        mSnapshotDirty.set(true);
        buildAndPublishSnapshot();
        mMainThreadHandler.post(mSession::onColorsChanged);
    }

    private void handleViewportScroll() {
        int requestedTopRow;
        synchronized (this) {
            requestedTopRow = mPendingTopRow;
        }

        int updatedTopRow = mContent.setViewportTopRow(requestedTopRow);
        if (updatedTopRow == mCurrentTopRow) {
            return;
        }

        mCurrentTopRow = updatedTopRow;
        addPendingFrameReason(FrameDelta.REASON_VIEWPORT_SCROLL);
        mSnapshotDirty.set(true);
        scheduleSnapshotBuild(false);
    }

    private void handleMouseEvent(GhosttyMouseEvent event) {
        int appendedBytes = mContent.queueMouseEvent(event);
        if (appendedBytes < 0) {
            GhosttyLog.error("Ghostty mouse event failed session=" + mSession.mHandle + " action=" + event.action + " button=" + event.button);
            return;
        }
        if (appendedBytes == 0) {
            return;
        }

        drainPendingOutput();
    }

    private void processAppendResult(int result) {
        if ((result & GhosttyNative.APPEND_RESULT_REPLY_BYTES_AVAILABLE) != 0) {
            drainPendingOutput();
        }
        if ((result & GhosttyNative.APPEND_RESULT_TITLE_CHANGED) != 0) {
            String title = mContent.consumePendingTitle();
            mMainThreadHandler.post(() -> mSession.titleChanged(null, title));
        }
        if ((result & GhosttyNative.APPEND_RESULT_CLIPBOARD_COPY) != 0) {
            String text = mContent.consumePendingClipboardText();
            mMainThreadHandler.post(() -> mSession.onCopyTextToClipboard(text));
        }
        if ((result & GhosttyNative.APPEND_RESULT_DESKTOP_NOTIFICATION) != 0) {
            handleDesktopNotification();
        }
        if ((result & GhosttyNative.APPEND_RESULT_BELL) != 0) {
            mMainThreadHandler.post(mSession::onBell);
        }
        if ((result & GhosttyNative.APPEND_RESULT_COLORS_CHANGED) != 0) {
            mMainThreadHandler.post(mSession::onColorsChanged);
        }
        if ((result & GhosttyNative.APPEND_RESULT_PROGRESS) != 0) {
            handleProgressUpdate();
        }
    }

    private void handleDesktopNotification() {
        String title = mContent.consumePendingNotificationTitle();
        String body = mContent.consumePendingNotificationBody();
        if (title == null && body == null) {
            return;
        }

        mMainThreadHandler.post(() -> mSession.onTerminalProtocolNotification(title, body));
    }

    private void handleProgressUpdate() {
        int previousState = mSession.mGhosttyProgressState;
        int previousValue = mSession.mGhosttyProgressValue;
        long previousGeneration = mSession.mGhosttyProgressGeneration;

        int state = mContent.getProgressState();
        int value = mContent.getProgressValue();
        long generation = mContent.getProgressGeneration();
        if (previousState == state && previousValue == value && previousGeneration == generation) {
            return;
        }

        mSession.updateGhosttyProgressState(state, value, generation);
        if (state == TerminalSession.GHOSTTY_PROGRESS_STATE_NONE) {
            cancelProgressTimeout();
        } else {
            scheduleProgressTimeout(generation);
        }

        mMainThreadHandler.post(() -> mSession.dispatchGhosttyProgressChanged(generation));
    }

    private void scheduleProgressTimeout(long generation) {
        Handler handler = getWorkerHandler();
        handler.removeMessages(MSG_PROGRESS_TIMEOUT);
        handler.sendMessageDelayed(handler.obtainMessage(MSG_PROGRESS_TIMEOUT, Long.valueOf(generation)), PROGRESS_TIMEOUT_MILLIS);
    }

    private void cancelProgressTimeout() {
        getWorkerHandler().removeMessages(MSG_PROGRESS_TIMEOUT);
    }

    private void handleProgressTimeout(long expectedGeneration) {
        if (mContent.getProgressGeneration() != expectedGeneration) {
            return;
        }
        if (mContent.getProgressState() == TerminalSession.GHOSTTY_PROGRESS_STATE_NONE) {
            return;
        }

        GhosttyLog.debug("Clearing stale Ghostty progress session=" + mSession.mHandle + " generation=" + expectedGeneration);
        mContent.clearProgress();
        handleProgressUpdate();
    }

    private void handleRequestFullSnapshotRefresh() {
        GhosttyLog.debug("Forcing full Ghostty snapshot refresh session=" + mSession.mHandle);
        mContent.requestFullSnapshotRefresh();
        mSnapshotDirty.set(true);
        buildAndPublishSnapshot();
    }

    private void drainPendingOutput() {
        while (true) {
            int written = mContent.drainPendingOutput(mDrainBuffer, 0, mDrainBuffer.length);
            if (written <= 0) break;
            mSession.write(mDrainBuffer, 0, written);
        }
    }

    private void scheduleSnapshotBuild(boolean busy) {
        if (!mSnapshotDirty.get()) return;

        long snapshotInterval = busy ? SNAPSHOT_INTERVAL_BUSY_MILLIS : SNAPSHOT_INTERVAL_MILLIS;
        long now = SystemClock.uptimeMillis();
        long delay = Math.max(0, (mLastSnapshotTime + snapshotInterval) - now);

        Handler handler = getWorkerHandler();
        if (handler.hasMessages(MSG_BUILD_SNAPSHOT)) {
            mCoalescedBuildRequestCount++;
            handler.removeMessages(MSG_BUILD_SNAPSHOT);
        }
        if (delay == 0) {
            handler.sendEmptyMessage(MSG_BUILD_SNAPSHOT);
            return;
        }
        handler.sendEmptyMessageDelayed(MSG_BUILD_SNAPSHOT, delay);
    }

    private void buildAndPublishSnapshot() {
        if (!mSnapshotDirty.compareAndSet(true, false)) return;

        ScreenSnapshot stagingSnapshot = mCurrentStaging;
        ViewportLinkSnapshot stagingViewportLinks = mCurrentViewportLinkStaging;
        ScreenSnapshot publishedSnapshot = mPublishedSnapshot.get();
        if (publishedSnapshot != null && publishedSnapshot != stagingSnapshot) {
            stagingSnapshot.copyPersistentMetadataFrom(publishedSnapshot);
        }

        long buildStartNanos = SystemClock.elapsedRealtimeNanos();
        mContent.fillSnapshot(stagingSnapshot);
        mContent.fillViewportLinks(stagingViewportLinks);
        long buildDurationNanos = SystemClock.elapsedRealtimeNanos() - buildStartNanos;

        mLastSnapshotTime = SystemClock.uptimeMillis();
        mPublishedFrameCount++;
        stagingSnapshot.setFrameSequence(mPublishedFrameCount);
        stagingViewportLinks.setFrameSequence(mPublishedFrameCount);
        mSnapshotBuildTotalNanos += buildDurationNanos;
        logSnapshotBuildPerfIfNeeded(stagingSnapshot, buildDurationNanos);

        mPublishedSnapshot.set(stagingSnapshot);
        mPublishedFrameDelta.set(new FrameDelta(
            mPublishedFrameCount,
            mPendingFrameReasonFlags.getAndSet(0),
            stagingSnapshot,
            stagingViewportLinks
        ));

        // Swap staging buffers.
        mCurrentStaging = (stagingSnapshot == mSnapshotA) ? mSnapshotB : mSnapshotA;
        mCurrentViewportLinkStaging = (stagingViewportLinks == mViewportLinksA)
            ? mViewportLinksB : mViewportLinksA;

        if (mUIUpdatePending.compareAndSet(false, true)) {
            mMainThreadHandler.post(() -> {
                mUIUpdatePending.set(false);
                mSession.notifyFrameAvailable();
            });
            return;
        }

        mCoalescedUiWakeupCount++;
    }

    void setTopRow(int topRow) {
        synchronized (this) {
            mPendingTopRow = topRow;
        }

        Handler handler = getWorkerHandler();
        if (handler.hasMessages(MSG_VIEWPORT_SCROLL)) {
            handler.removeMessages(MSG_VIEWPORT_SCROLL);
        }
        handler.sendEmptyMessage(MSG_VIEWPORT_SCROLL);
    }

    private void addPendingFrameReason(int reasonFlag) {
        while (true) {
            int currentFlags = mPendingFrameReasonFlags.get();
            int updatedFlags = currentFlags | reasonFlag;
            if (mPendingFrameReasonFlags.compareAndSet(currentFlags, updatedFlags)) {
                return;
            }
        }
    }

    private void logSnapshotBuildPerfIfNeeded(ScreenSnapshot snapshot, long buildDurationNanos) {
        boolean slowFrame = buildDurationNanos >= SLOW_SNAPSHOT_BUILD_NANOS;
        boolean periodic = GhosttyLog.isEnabled() && (mPublishedFrameCount % PERF_LOG_INTERVAL_FRAMES) == 0;
        if (!slowFrame && !periodic) {
            return;
        }

        long averageBuildNanos = mPublishedFrameCount == 0 ? 0 : mSnapshotBuildTotalNanos / mPublishedFrameCount;
        String message = "Worker frame perf session=" + mSession.mHandle
            + " frame=" + mPublishedFrameCount
            + " topRow=" + snapshot.getTopRow()
            + " rows=" + snapshot.getRows()
            + " columns=" + snapshot.getColumns()
            + " requiredBytes=" + snapshot.getRequiredBytes()
            + " buildMs=" + formatDurationMillis(buildDurationNanos)
            + " avgBuildMs=" + formatDurationMillis(averageBuildNanos)
            + " coalescedBuilds=" + mCoalescedBuildRequestCount
            + " coalescedUiWakeups=" + mCoalescedUiWakeupCount;

        if (slowFrame) {
            GhosttyLog.warn(message);
            return;
        }

        GhosttyLog.debug(message);
    }

    private static String formatDurationMillis(long durationNanos) {
        return Double.toString(durationNanos / 1_000_000.0d);
    }

    private class WorkerHandler extends Handler {
        WorkerHandler(Looper looper) {
            super(looper);
        }

        @Override
        public void handleMessage(@NonNull Message msg) {
            switch (msg.what) {
                case MSG_APPEND:
                    handleAppend();
                    break;
                case MSG_APPEND_DIRECT:
                    byte[] data = (byte[]) msg.obj;
                    appendToNative(data, 0, data.length);
                    addPendingFrameReason(FrameDelta.REASON_APPEND_DIRECT);
                    mSnapshotDirty.set(true);
                    scheduleSnapshotBuild(false);
                    break;
                case MSG_BUILD_SNAPSHOT:
                    buildAndPublishSnapshot();
                    break;
                case MSG_RESIZE:
                    handleResize();
                    break;
                case MSG_RESET:
                    handleReset();
                    break;
                case MSG_VIEWPORT_SCROLL:
                    handleViewportScroll();
                    break;
                case MSG_REQUEST_FULL_SNAPSHOT_REFRESH:
                    handleRequestFullSnapshotRefresh();
                    break;
                case MSG_MOUSE_EVENT:
                    handleMouseEvent((GhosttyMouseEvent) msg.obj);
                    break;
                case MSG_PROGRESS_TIMEOUT:
                    handleProgressTimeout((Long) msg.obj);
                    break;
                case MSG_APPLY_COLOR_SCHEME:
                    handleApplyColorScheme((int[]) msg.obj);
                    break;
                case MSG_SHUTDOWN:
                    cancelProgressTimeout();
                    getLooper().quit();
                    break;
            }
        }
    }
}
