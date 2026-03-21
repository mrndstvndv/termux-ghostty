package com.termux.terminal;

import android.annotation.SuppressLint;
import android.os.Handler;
import android.os.Message;
import android.system.ErrnoException;
import android.system.Os;
import android.system.OsConstants;

import androidx.annotation.Nullable;

import java.io.File;
import java.io.FileDescriptor;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.UUID;

/**
 * A terminal session, consisting of a process coupled to a terminal interface.
 * <p>
 * The subprocess will be executed after the first successful call to
 * {@link #updateSize(int, int, int, int)}. At that point the Ghostty backend is initialized and
 * threads are spawned to handle subprocess I/O.
 * <p>
 * Terminal mutation happens on the Ghostty worker thread. UI callbacks are posted back to the main
 * thread.
 * <p>
 * The child process may be exited forcefully by using the {@link #finishIfRunning()} method.
 * <p>
 * NOTE: The terminal session may outlive the terminal view, so be careful with callbacks!
 */
public final class TerminalSession extends TerminalOutput {

    private static final int MSG_PROCESS_EXITED = 4;

    public static final int GHOSTTY_PROGRESS_STATE_NONE = GhosttyNative.PROGRESS_STATE_NONE;
    public static final int GHOSTTY_PROGRESS_STATE_SET = GhosttyNative.PROGRESS_STATE_SET;
    public static final int GHOSTTY_PROGRESS_STATE_ERROR = GhosttyNative.PROGRESS_STATE_ERROR;
    public static final int GHOSTTY_PROGRESS_STATE_INDETERMINATE = GhosttyNative.PROGRESS_STATE_INDETERMINATE;
    public static final int GHOSTTY_PROGRESS_STATE_PAUSE = GhosttyNative.PROGRESS_STATE_PAUSE;

    public final String mHandle = UUID.randomUUID().toString();

    private GhosttyTerminalContent mGhosttyTerminalContent;
    private GhosttySessionWorker mGhosttySessionWorker;

    /**
     * A queue written to from a separate thread when the process outputs and read by the Ghostty worker.
     */
    final ByteQueue mProcessToTerminalIOQueue = new ByteQueue(64 * 1024);
    /**
     * A queue written to from the main thread due to user interaction, and read by another thread which forwards by
     * writing to the {@link #mTerminalFileDescriptor}.
     */
    final ByteQueue mTerminalToProcessIOQueue = new ByteQueue(4096);
    /** Buffer to write translate code points into utf8 before writing to mTerminalToProcessIOQueue */
    private final byte[] mUtf8InputBuffer = new byte[5];

    /** Callback which gets notified when a session finishes or changes title. */
    TerminalSessionClient mClient;

    /** The pid of the shell process. 0 if not started and -1 if finished running. */
    int mShellPid;

    /** The exit status of the shell process. Only valid if ${@link #mShellPid} is -1. */
    int mShellExitStatus;

    /**
     * The file descriptor referencing the master half of a pseudo-terminal pair, resulting from calling
     * {@link JNI#createSubprocess(String, String, String[], String[], int[], int, int, int, int)}.
     */
    private int mTerminalFileDescriptor;

    /** Set by the application for user identification of session, not by terminal. */
    public String mSessionName;

    final Handler mMainThreadHandler = new MainThreadHandler();

    private final String mShellPath;
    private final String mCwd;
    private final String[] mArgs;
    private final String[] mEnv;
    private final Integer mTranscriptRows;

    private boolean mAutoScrollDisabled;
    final AtomicInteger mScrollCounter = new AtomicInteger(0);
    volatile int mLastKnownGhosttyTranscriptRows;
    volatile int mLastKnownActiveRows;
    volatile int mGhosttyModeBits;
    volatile boolean mGhosttyAlternateBufferActive;
    volatile boolean mGhosttyReverseVideo;
    volatile boolean mGhosttyCursorVisible;
    volatile int mGhosttyCursorRow;
    volatile int mGhosttyCursorCol;
    volatile int mGhosttyCursorStyle;
    volatile int mGhosttyProgressState = GHOSTTY_PROGRESS_STATE_NONE;
    volatile int mGhosttyProgressValue = -1;
    volatile long mGhosttyProgressGeneration;
    volatile boolean mGhosttyCursorBlinkingEnabled;
    volatile boolean mGhosttyCursorBlinkState = true;
    volatile int mColumns;
    volatile int mRows;
    volatile int mCellWidthPixels;
    volatile int mCellHeightPixels;
    volatile long mGhosttyProtocolNotificationGeneration;
    @Nullable
    volatile String mGhosttyProtocolNotificationTitle;
    @Nullable
    volatile String mGhosttyProtocolNotificationBody;
    @Nullable
    private String mTitle;

    private static final String LOG_TAG = "TerminalSession";

    public TerminalSession(String shellPath, String cwd, String[] args, String[] env, Integer transcriptRows, TerminalSessionClient client) {
        this.mShellPath = shellPath;
        this.mCwd = cwd;
        this.mArgs = args;
        this.mEnv = env;
        this.mTranscriptRows = transcriptRows;
        this.mClient = client;
    }

    /**
     * @param client The {@link TerminalSessionClient} interface implementation to allow
     *               for communication between {@link TerminalSession} and its client.
     */
    public void updateTerminalSessionClient(TerminalSessionClient client) {
        mClient = client;
    }

    /** Inform the attached pty of the new size or initialize the Ghostty backend. */
    public void updateSize(int columns, int rows, int cellWidthPixels, int cellHeightPixels) {
        if (!hasActiveTerminalBackend()) {
            GhosttyLog.info("Initializing terminal backend columns=" + columns + " rows=" + rows + " cellWidth=" + cellWidthPixels + " cellHeight=" + cellHeightPixels);
            initializeTerminalBackend(columns, rows, cellWidthPixels, cellHeightPixels);
            return;
        }

        this.mColumns = columns;
        this.mRows = rows;
        this.mCellWidthPixels = cellWidthPixels;
        this.mCellHeightPixels = cellHeightPixels;
        this.mLastKnownActiveRows = rows;

        JNI.setPtyWindowSize(mTerminalFileDescriptor, rows, columns, cellWidthPixels, cellHeightPixels);
        if (mGhosttySessionWorker != null) {
            GhosttyLog.debug("Enqueuing Ghostty resize pid=" + mShellPid + " columns=" + columns + " rows=" + rows + " cellWidth=" + cellWidthPixels + " cellHeight=" + cellHeightPixels);
            mGhosttySessionWorker.resize(columns, rows, cellWidthPixels, cellHeightPixels);
        }
    }

    /** The terminal title as set through escape sequences or null if none set. */
    @Nullable
    public String getTitle() {
        return mTitle;
    }

    public int getGhosttyProgressState() {
        return mGhosttyProgressState;
    }

    public int getGhosttyProgressValue() {
        return mGhosttyProgressValue;
    }

    public long getGhosttyProgressGeneration() {
        return mGhosttyProgressGeneration;
    }

    public long getGhosttyProtocolNotificationGeneration() {
        return mGhosttyProtocolNotificationGeneration;
    }

    @Nullable
    public String getGhosttyProtocolNotificationTitle() {
        return mGhosttyProtocolNotificationTitle;
    }

    @Nullable
    public String getGhosttyProtocolNotificationBody() {
        return mGhosttyProtocolNotificationBody;
    }

    /**
     * Set the terminal backend's window size and start terminal emulation.
     *
     * @param columns The number of columns in the terminal window.
     * @param rows    The number of rows in the terminal window.
     */
    public void initializeTerminalBackend(int columns, int rows, int cellWidthPixels, int cellHeightPixels) {
        if (!GhosttyNative.isLibraryLoaded()) {
            String message = "Ghostty native library is not loaded";
            mShellPid = -1;
            mShellExitStatus = 1;
            GhosttyLog.error(message + " for session " + mHandle);
            Logger.logError(mClient, LOG_TAG, message);
            throw new IllegalStateException(message);
        }

        GhosttyLog.info("initializeTerminalBackend columns=" + columns + " rows=" + rows + " transcriptRows=" + resolveTranscriptRows());

        this.mColumns = columns;
        this.mRows = rows;
        this.mCellWidthPixels = cellWidthPixels;
        this.mCellHeightPixels = cellHeightPixels;
        this.mLastKnownActiveRows = rows;

        try {
            mGhosttyTerminalContent = new GhosttyTerminalContent(columns, rows, resolveTranscriptRows(), cellWidthPixels, cellHeightPixels);
            mLastKnownGhosttyTranscriptRows = mGhosttyTerminalContent.getActiveTranscriptRows();
            mLastKnownActiveRows = mGhosttyTerminalContent.getActiveRows();
            mGhosttyModeBits = mGhosttyTerminalContent.getModeBits();
            mGhosttyAlternateBufferActive = mGhosttyTerminalContent.isAlternateBufferActive();
            mGhosttyReverseVideo = mGhosttyTerminalContent.isReverseVideo();
            mGhosttyCursorVisible = mGhosttyTerminalContent.isCursorEnabled();
            mGhosttyCursorRow = mGhosttyTerminalContent.getCursorRow();
            mGhosttyCursorCol = mGhosttyTerminalContent.getCursorCol();
            mGhosttyCursorStyle = mGhosttyTerminalContent.getCursorStyle();
            mGhosttySessionWorker = new GhosttySessionWorker(this, mGhosttyTerminalContent, mProcessToTerminalIOQueue, mMainThreadHandler, cellWidthPixels, cellHeightPixels);
            mGhosttySessionWorker.start();
            GhosttyLog.info("Ghostty backend selected for session " + mHandle);
        } catch (Throwable error) {
            if (mGhosttyTerminalContent != null) {
                try {
                    mGhosttyTerminalContent.close();
                } catch (Exception ignored) {
                }
                mGhosttyTerminalContent = null;
            }
            mGhosttySessionWorker = null;
            mShellPid = -1;
            mShellExitStatus = 1;
            GhosttyLog.error("Ghostty backend creation failed for session " + mHandle, error);
            Logger.logError(mClient, LOG_TAG, "Failed to initialize Ghostty backend: " + error.getMessage());
            throw new IllegalStateException("Failed to initialize Ghostty backend", error);
        }

        int[] processId = new int[1];
        mTerminalFileDescriptor = JNI.createSubprocess(mShellPath, mCwd, mArgs, mEnv, processId, rows, columns, cellWidthPixels, cellHeightPixels);
        mShellPid = processId[0];
        GhosttyLog.info("Subprocess created session=" + mHandle + " pid=" + mShellPid + " fd=" + mTerminalFileDescriptor + " backend=ghostty");
        mClient.setTerminalShellPid(this, mShellPid);

        final FileDescriptor terminalFileDescriptorWrapped = wrapFileDescriptor(mTerminalFileDescriptor, mClient);

        new Thread("TermSessionInputReader[pid=" + mShellPid + "]") {
            @Override
            public void run() {
                try (InputStream termIn = new FileInputStream(terminalFileDescriptorWrapped)) {
                    final byte[] buffer = new byte[4096];
                    while (true) {
                        int read = termIn.read(buffer);
                        if (read == -1) return;
                        if (!mProcessToTerminalIOQueue.write(buffer, 0, read)) return;
                        if (mGhosttySessionWorker != null) {
                            mGhosttySessionWorker.onOutputAvailable();
                        }
                    }
                } catch (Exception e) {
                    // Ignore, just shutting down.
                }
            }
        }.start();

        new Thread("TermSessionOutputWriter[pid=" + mShellPid + "]") {
            @Override
            public void run() {
                final byte[] buffer = new byte[4096];
                try (FileOutputStream termOut = new FileOutputStream(terminalFileDescriptorWrapped)) {
                    while (true) {
                        int bytesToWrite = mTerminalToProcessIOQueue.read(buffer, true);
                        if (bytesToWrite == -1) return;
                        termOut.write(buffer, 0, bytesToWrite);
                    }
                } catch (IOException e) {
                    // Ignore.
                }
            }
        }.start();

        new Thread("TermSessionWaiter[pid=" + mShellPid + "]") {
            @Override
            public void run() {
                int processExitCode = JNI.waitFor(mShellPid);
                mMainThreadHandler.sendMessage(mMainThreadHandler.obtainMessage(MSG_PROCESS_EXITED, processExitCode));
            }
        }.start();
    }

    public boolean hasActiveTerminalBackend() {
        return mGhosttyTerminalContent != null;
    }

    @Nullable
    public TerminalContent getTerminalContent() {
        return mGhosttyTerminalContent;
    }

    /** Write data to the shell process. */
    @Override
    public void write(byte[] data, int offset, int count) {
        if (mShellPid > 0) mTerminalToProcessIOQueue.write(data, offset, count);
    }

    /** Write the Unicode code point to the terminal encoded in UTF-8. */
    public void writeCodePoint(boolean prependEscape, int codePoint) {
        if (codePoint > 1114111 || (codePoint >= 0xD800 && codePoint <= 0xDFFF)) {
            throw new IllegalArgumentException("Invalid code point: " + codePoint);
        }

        int bufferPosition = 0;
        if (prependEscape) mUtf8InputBuffer[bufferPosition++] = 27;

        if (codePoint <= /* 7 bits */0b1111111) {
            mUtf8InputBuffer[bufferPosition++] = (byte) codePoint;
        } else if (codePoint <= /* 11 bits */0b11111111111) {
            mUtf8InputBuffer[bufferPosition++] = (byte) (0b11000000 | (codePoint >> 6));
            mUtf8InputBuffer[bufferPosition++] = (byte) (0b10000000 | (codePoint & 0b111111));
        } else if (codePoint <= /* 16 bits */0b1111111111111111) {
            mUtf8InputBuffer[bufferPosition++] = (byte) (0b11100000 | (codePoint >> 12));
            mUtf8InputBuffer[bufferPosition++] = (byte) (0b10000000 | ((codePoint >> 6) & 0b111111));
            mUtf8InputBuffer[bufferPosition++] = (byte) (0b10000000 | (codePoint & 0b111111));
        } else {
            mUtf8InputBuffer[bufferPosition++] = (byte) (0b11110000 | (codePoint >> 18));
            mUtf8InputBuffer[bufferPosition++] = (byte) (0b10000000 | ((codePoint >> 12) & 0b111111));
            mUtf8InputBuffer[bufferPosition++] = (byte) (0b10000000 | ((codePoint >> 6) & 0b111111));
            mUtf8InputBuffer[bufferPosition++] = (byte) (0b10000000 | (codePoint & 0b111111));
        }
        write(mUtf8InputBuffer, 0, bufferPosition);
    }

    @Nullable
    public String getSelectedText(int startColumn, int startRow, int endColumn, int endRow) {
        return mGhosttyTerminalContent == null
            ? null
            : mGhosttyTerminalContent.getSelectedText(startColumn, startRow, endColumn, endRow);
    }

    public int getActiveRows() {
        return mGhosttyTerminalContent == null ? 0 : mLastKnownActiveRows;
    }

    public int getRows() {
        return mRows;
    }

    public int getColumns() {
        return mColumns;
    }

    public int getCellWidthPixels() {
        return mCellWidthPixels;
    }

    public int getCellHeightPixels() {
        return mCellHeightPixels;
    }

    public int getCursorRow() {
        return mGhosttyCursorRow;
    }

    public int getCursorCol() {
        return mGhosttyCursorCol;
    }

    public int getCursorStyle() {
        return mGhosttyCursorStyle;
    }

    public boolean shouldCursorBeVisible() {
        return mGhosttyCursorVisible && (!mGhosttyCursorBlinkingEnabled || mGhosttyCursorBlinkState);
    }

    public boolean isReverseVideo() {
        return mGhosttyReverseVideo;
    }

    public boolean isMouseTrackingActive() {
        return (mGhosttyModeBits & GhosttyNative.MODE_MOUSE_TRACKING) != 0;
    }

    public boolean isAlternateBufferActive() {
        return mGhosttyAlternateBufferActive;
    }

    public boolean isCursorKeysApplicationMode() {
        return (mGhosttyModeBits & GhosttyNative.MODE_CURSOR_KEYS_APPLICATION) != 0;
    }

    public boolean isKeypadApplicationMode() {
        return (mGhosttyModeBits & GhosttyNative.MODE_KEYPAD_APPLICATION) != 0;
    }

    public boolean isBracketedPasteMode() {
        return (mGhosttyModeBits & GhosttyNative.MODE_BRACKETED_PASTE) != 0;
    }

    public void sendGhosttyMouseEvent(GhosttyMouseEvent event) {
        if (mGhosttySessionWorker == null) {
            return;
        }

        mGhosttySessionWorker.sendMouseEvent(event);
    }

    public void paste(String text) {
        if (text == null || mGhosttyTerminalContent == null) {
            return;
        }

        text = text.replaceAll("(\u001B|[\u0080-\u009F])", "");
        text = text.replaceAll("\r?\n", "\r");

        boolean bracketedPasteMode = isBracketedPasteMode();
        GhosttyLog.debug("paste textLength=" + text.length() + " bracketed=" + bracketedPasteMode);
        if (bracketedPasteMode) write("\033[200~");
        write(text);
        if (bracketedPasteMode) write("\033[201~");
    }

    public void setCursorBlinkingEnabled(boolean cursorBlinkingEnabled) {
        if (mGhosttyTerminalContent == null) {
            return;
        }

        mGhosttyCursorBlinkingEnabled = cursorBlinkingEnabled;
        mGhosttyTerminalContent.setCursorBlinkingEnabled(cursorBlinkingEnabled);
    }

    public void setCursorBlinkState(boolean cursorBlinkState) {
        if (mGhosttyTerminalContent == null) {
            return;
        }

        mGhosttyCursorBlinkState = cursorBlinkState;
        mGhosttyTerminalContent.setCursorBlinkState(cursorBlinkState);
    }

    public boolean isCursorEnabled() {
        return mGhosttyCursorVisible;
    }

    public void setGhosttyTopRow(int topRow) {
        if (mGhosttySessionWorker != null) {
            mGhosttySessionWorker.setTopRow(topRow);
        }
    }

    public void requestGhosttyFullSnapshotRefresh() {
        if (mGhosttySessionWorker != null) {
            mGhosttySessionWorker.requestFullSnapshotRefresh();
        }
    }

    public void reloadColorScheme() {
        if (mGhosttySessionWorker != null) {
            mGhosttySessionWorker.applyColorScheme(TerminalColors.COLOR_SCHEME.mDefaultColors);
        }
    }

    void updateGhosttyProgressState(int state, int value, long generation) {
        mGhosttyProgressState = state;
        mGhosttyProgressValue = value;
        mGhosttyProgressGeneration = generation;
    }

    void dispatchGhosttyProgressChanged(long expectedGeneration) {
        if (mGhosttyProgressGeneration != expectedGeneration) {
            return;
        }

        mClient.onTerminalProgressChanged(this);
    }

    void onTerminalProtocolNotification(@Nullable String title, @Nullable String body) {
        mGhosttyProtocolNotificationTitle = title;
        mGhosttyProtocolNotificationBody = body;
        mGhosttyProtocolNotificationGeneration++;
        mClient.onTerminalProtocolNotification(this, title, body);
    }

    /** Notify the {@link #mClient} that a new frame is ready to draw. */
    protected void notifyFrameAvailable() {
        mClient.onFrameAvailable(this);
    }

    /** Reset state for the active terminal backend. */
    public void reset() {
        if (mGhosttySessionWorker == null) {
            return;
        }

        GhosttyLog.debug("Resetting Ghostty session via worker " + mHandle);
        mGhosttySessionWorker.reset();
    }

    /** Finish this terminal session by sending SIGKILL to the shell. */
    public void finishIfRunning() {
        if (isRunning()) {
            try {
                Os.kill(mShellPid, OsConstants.SIGKILL);
            } catch (ErrnoException e) {
                Logger.logWarn(mClient, LOG_TAG, "Failed sending SIGKILL: " + e.getMessage());
            }
        }
    }

    /** Cleanup resources when the process exits. */
    void cleanupResources(int exitStatus) {
        GhosttyLog.info("Cleaning up session " + mHandle + " exitStatus=" + exitStatus + " pid=" + mShellPid);
        synchronized (this) {
            mShellPid = -1;
            mShellExitStatus = exitStatus;
        }

        mTerminalToProcessIOQueue.close();
        mProcessToTerminalIOQueue.close();
        JNI.close(mTerminalFileDescriptor);
    }

    @Override
    public void titleChanged(String oldTitle, String newTitle) {
        mTitle = newTitle;
        mClient.onTitleChanged(this);
    }

    public int getActiveTranscriptRows() {
        return mGhosttyTerminalContent == null ? 0 : mLastKnownGhosttyTranscriptRows;
    }

    @Nullable
    public FrameDelta getGhosttyPublishedFrameDelta() {
        return mGhosttySessionWorker == null ? null : mGhosttySessionWorker.getPublishedFrameDelta();
    }

    public boolean isGhosttyCursorBlinkingEnabled() {
        return mGhosttySessionWorker != null && mGhosttyCursorBlinkingEnabled;
    }

    public boolean getGhosttyCursorBlinkState() {
        return mGhosttySessionWorker == null || mGhosttyCursorBlinkState;
    }

    public synchronized boolean isRunning() {
        return mShellPid != -1;
    }

    /** Only valid if not {@link #isRunning()}. */
    public synchronized int getExitStatus() {
        return mShellExitStatus;
    }

    @Override
    public void onCopyTextToClipboard(String text) {
        mClient.onCopyTextToClipboard(this, text);
    }

    @Override
    public void onPasteTextFromClipboard() {
        mClient.onPasteTextFromClipboard(this);
    }

    @Override
    public void onBell() {
        mClient.onBell(this);
    }

    @Override
    public void onColorsChanged() {
        mClient.onColorsChanged(this);
    }

    public int getPid() {
        return mShellPid;
    }

    /** Returns the shell's working directory or null if it was unavailable. */
    public String getCwd() {
        if (mShellPid < 1) {
            return null;
        }
        try {
            final String cwdSymlink = String.format("/proc/%s/cwd/", mShellPid);
            String outputPath = new File(cwdSymlink).getCanonicalPath();
            String outputPathWithTrailingSlash = outputPath;
            if (!outputPath.endsWith("/")) {
                outputPathWithTrailingSlash += '/';
            }
            if (!cwdSymlink.equals(outputPathWithTrailingSlash)) {
                return outputPath;
            }
        } catch (IOException | SecurityException e) {
            Logger.logStackTraceWithMessage(mClient, LOG_TAG, "Error getting current directory", e);
        }
        return null;
    }

    public boolean isAutoScrollDisabled() {
        return mGhosttyTerminalContent != null && mAutoScrollDisabled;
    }

    public void toggleAutoScrollDisabled() {
        if (mGhosttyTerminalContent != null) {
            mAutoScrollDisabled = !mAutoScrollDisabled;
        }
    }

    public int getScrollCounter() {
        return mGhosttySessionWorker == null ? 0 : mScrollCounter.get();
    }

    public void clearScrollCounter() {
        if (mGhosttySessionWorker != null) {
            mScrollCounter.set(0);
        }
    }

    public int getBackgroundColor() {
        FrameDelta frameDelta = getGhosttyPublishedFrameDelta();
        if (frameDelta != null) {
            return frameDelta.getTransportSnapshot().getPaletteColor(TextStyle.COLOR_INDEX_BACKGROUND);
        }
        return TerminalColors.COLOR_SCHEME.mDefaultColors[TextStyle.COLOR_INDEX_BACKGROUND];
    }

    static int resolveTranscriptRows(@Nullable Integer transcriptRows) {
        if (transcriptRows == null) {
            return TerminalConstants.DEFAULT_TERMINAL_TRANSCRIPT_ROWS;
        }
        if (transcriptRows < TerminalConstants.TERMINAL_TRANSCRIPT_ROWS_MIN) {
            return TerminalConstants.DEFAULT_TERMINAL_TRANSCRIPT_ROWS;
        }
        if (transcriptRows > TerminalConstants.TERMINAL_TRANSCRIPT_ROWS_MAX) {
            return TerminalConstants.DEFAULT_TERMINAL_TRANSCRIPT_ROWS;
        }
        return transcriptRows;
    }

    private int resolveTranscriptRows() {
        return resolveTranscriptRows(mTranscriptRows);
    }

    private static FileDescriptor wrapFileDescriptor(int fileDescriptor, TerminalSessionClient client) {
        FileDescriptor result = new FileDescriptor();
        try {
            Field descriptorField;
            try {
                descriptorField = FileDescriptor.class.getDeclaredField("descriptor");
            } catch (NoSuchFieldException e) {
                descriptorField = FileDescriptor.class.getDeclaredField("fd");
            }
            descriptorField.setAccessible(true);
            descriptorField.set(result, fileDescriptor);
        } catch (NoSuchFieldException | IllegalAccessException | IllegalArgumentException e) {
            Logger.logStackTraceWithMessage(client, LOG_TAG, "Error accessing FileDescriptor#descriptor private field", e);
            System.exit(1);
        }
        return result;
    }

    @SuppressLint("HandlerLeak")
    class MainThreadHandler extends Handler {

        @Override
        public void handleMessage(Message msg) {
            if (msg.what != MSG_PROCESS_EXITED) {
                return;
            }

            int exitCode = (Integer) msg.obj;
            GhosttyLog.info("Process exited for session " + mHandle + " exitCode=" + exitCode);
            cleanupResources(exitCode);

            String exitDescription = "\r\n[Process completed";
            if (exitCode > 0) {
                exitDescription += " (code " + exitCode + ")";
            } else if (exitCode < 0) {
                exitDescription += " (signal " + (-exitCode) + ")";
            }
            exitDescription += " - press Enter]";

            byte[] bytesToWrite = exitDescription.getBytes(StandardCharsets.UTF_8);
            if (mGhosttySessionWorker != null) {
                mGhosttySessionWorker.onOutputAvailable();
                mGhosttySessionWorker.appendDirect(bytesToWrite);
            }

            mClient.onSessionFinished(TerminalSession.this);
        }

    }

}
