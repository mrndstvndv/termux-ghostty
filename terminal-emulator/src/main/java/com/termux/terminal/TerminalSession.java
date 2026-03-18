package com.termux.terminal;

import android.annotation.SuppressLint;
import android.os.Handler;
import android.os.Message;
import android.os.SystemClock;
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
import java.util.concurrent.atomic.AtomicReference;
import java.util.Objects;
import java.util.UUID;

/**
 * A terminal session, consisting of a process coupled to a terminal interface.
 * <p>
 * The subprocess will be executed by the constructor, and when the size is made known by a call to
 * {@link #updateSize(int, int, int, int)} terminal emulation will begin and threads will be spawned to handle the subprocess I/O.
 * All terminal emulation and callback methods will be performed on the main thread.
 * <p>
 * The child process may be exited forcefully by using the {@link #finishIfRunning()} method.
 * <p>
 * NOTE: The terminal session may outlive the EmulatorView, so be careful with callbacks!
 */
public final class TerminalSession extends TerminalOutput {

    private static final int MSG_NEW_INPUT = 1;
    private static final int MSG_PROCESS_EXITED = 4;
    private static final int MAIN_THREAD_INPUT_BUFFER_BYTES = 16 * 1024;
    private static final long MAIN_THREAD_INPUT_BUDGET_MILLIS = 8;
    private static final long MAIN_THREAD_INPUT_RESCHEDULE_DELAY_MILLIS = 1;

    /** Temporary development toggle for bringing up the Ghostty backend in-app. */
    private static final boolean FORCE_GHOSTTY_BACKEND = true;

    public static final int GHOSTTY_PROGRESS_STATE_NONE = GhosttyNative.PROGRESS_STATE_NONE;
    public static final int GHOSTTY_PROGRESS_STATE_SET = GhosttyNative.PROGRESS_STATE_SET;
    public static final int GHOSTTY_PROGRESS_STATE_ERROR = GhosttyNative.PROGRESS_STATE_ERROR;
    public static final int GHOSTTY_PROGRESS_STATE_INDETERMINATE = GhosttyNative.PROGRESS_STATE_INDETERMINATE;
    public static final int GHOSTTY_PROGRESS_STATE_PAUSE = GhosttyNative.PROGRESS_STATE_PAUSE;

    public final String mHandle = UUID.randomUUID().toString();

    TerminalEmulator mEmulator;
    private GhosttyTerminalContent mGhosttyTerminalContent;
    private GhosttySessionWorker mGhosttySessionWorker;
    private final JavaTerminalContentAdapter mJavaTerminalContentAdapter = new JavaTerminalContentAdapter();

    /**
     * A queue written to from a separate thread when the process outputs, and read by main thread to process by
     * terminal emulator.
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

        if (mEmulator != null) {
            mEmulator.updateTerminalSessionClient(client);
        }
    }

    /** Inform the attached pty of the new size and reflow or initialize the emulator. */
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
            return;
        }

        if (mEmulator != null) {
            mEmulator.resize(columns, rows, cellWidthPixels, cellHeightPixels);
        }
    }

    /** The terminal title as set through escape sequences or null if none set. */
    @Nullable
    public String getTitle() {
        if (mGhosttyTerminalContent != null) {
            return mTitle;
        }
        return (mEmulator == null) ? null : mEmulator.getTitle();
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
        boolean requestedGhosttyBackend = shouldUseGhosttyBackend();
        GhosttyLog.info("initializeTerminalBackend requestedGhostty=" + requestedGhosttyBackend + " columns=" + columns + " rows=" + rows + " transcriptRows=" + resolveTranscriptRows());

        this.mColumns = columns;
        this.mRows = rows;
        this.mCellWidthPixels = cellWidthPixels;
        this.mCellHeightPixels = cellHeightPixels;
        this.mLastKnownActiveRows = rows;

        if (requestedGhosttyBackend) {
            try {
                mGhosttyTerminalContent = new GhosttyTerminalContent(columns, rows, resolveTranscriptRows(), cellWidthPixels, cellHeightPixels);
                mLastKnownGhosttyTranscriptRows = mGhosttyTerminalContent.getActiveTranscriptRows();
                mGhosttySessionWorker = new GhosttySessionWorker(this, mGhosttyTerminalContent, mProcessToTerminalIOQueue, mMainThreadHandler, cellWidthPixels, cellHeightPixels);
                mGhosttySessionWorker.start();
                mEmulator = null;
                mJavaTerminalContentAdapter.setTerminalEmulator(null);
                GhosttyLog.info("Ghostty backend selected for session " + mHandle);
            } catch (Throwable error) {
                GhosttyLog.warn("Ghostty backend creation failed for session " + mHandle + ", falling back to Java emulator", error);
                Logger.logWarn(mClient, LOG_TAG, "Ghostty backend unavailable, falling back to Java emulator: " + error.getMessage());
                mGhosttyTerminalContent = null;
            }
        }

        if (mGhosttyTerminalContent == null) {
            GhosttyLog.info("Java terminal emulator selected for session " + mHandle);
            mEmulator = new TerminalEmulator(this, columns, rows, cellWidthPixels, cellHeightPixels, mTranscriptRows, mClient);
            mJavaTerminalContentAdapter.setTerminalEmulator(mEmulator);
        }

        int[] processId = new int[1];
        mTerminalFileDescriptor = JNI.createSubprocess(mShellPath, mCwd, mArgs, mEnv, processId, rows, columns, cellWidthPixels, cellHeightPixels);
        mShellPid = processId[0];
        GhosttyLog.info("Subprocess created session=" + mHandle + " pid=" + mShellPid + " fd=" + mTerminalFileDescriptor + " backend=" + (mGhosttyTerminalContent != null ? "ghostty" : "java"));
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
                        } else if (!mMainThreadHandler.hasMessages(MSG_NEW_INPUT)) {
                            mMainThreadHandler.sendEmptyMessage(MSG_NEW_INPUT);
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
        return mGhosttyTerminalContent != null || mEmulator != null;
    }

    public boolean isUsingGhosttyBackend() {
        return mGhosttyTerminalContent != null;
    }

    @Nullable
    public TerminalContent getTerminalContent() {
        if (mGhosttyTerminalContent != null) {
            return mGhosttyTerminalContent;
        }
        if (mEmulator == null) {
            return null;
        }

        mJavaTerminalContentAdapter.setTerminalEmulator(mEmulator);
        return mJavaTerminalContentAdapter;
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
    public TerminalEmulator getEmulator() {
        return mEmulator;
    }

    @Nullable
    public String getSelectedText(int startColumn, int startRow, int endColumn, int endRow) {
        if (mGhosttySessionWorker != null) {
            return mGhosttyTerminalContent.getSelectedText(startColumn, startRow, endColumn, endRow);
        }
        return mEmulator == null ? null : mEmulator.getSelectedText(startColumn, startRow, endColumn, endRow);
    }

    public int getActiveRows() {
        if (mGhosttySessionWorker != null) {
            return mLastKnownActiveRows;
        }
        return mEmulator == null ? 0 : mEmulator.getScreen().getActiveRows();
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
        if (mGhosttySessionWorker != null) {
            return mGhosttyCursorRow;
        }

        TerminalContent terminalContent = getTerminalContent();
        return terminalContent == null ? 0 : terminalContent.getCursorRow();
    }

    public int getCursorCol() {
        if (mGhosttySessionWorker != null) {
            return mGhosttyCursorCol;
        }

        TerminalContent terminalContent = getTerminalContent();
        return terminalContent == null ? 0 : terminalContent.getCursorCol();
    }

    public int getCursorStyle() {
        if (mGhosttySessionWorker != null) {
            return mGhosttyCursorStyle;
        }

        TerminalContent terminalContent = getTerminalContent();
        return terminalContent == null ? 0 : terminalContent.getCursorStyle();
    }

    public boolean shouldCursorBeVisible() {
        if (mGhosttySessionWorker != null) {
            return mGhosttyCursorVisible && (!mGhosttyCursorBlinkingEnabled || mGhosttyCursorBlinkState);
        }

        TerminalContent terminalContent = getTerminalContent();
        return terminalContent != null && terminalContent.shouldCursorBeVisible();
    }

    public boolean isReverseVideo() {
        if (mGhosttySessionWorker != null) {
            return mGhosttyReverseVideo;
        }

        TerminalContent terminalContent = getTerminalContent();
        return terminalContent != null && terminalContent.isReverseVideo();
    }

    public boolean isMouseTrackingActive() {
        if (mGhosttySessionWorker != null) {
            return (mGhosttyModeBits & GhosttyNative.MODE_MOUSE_TRACKING) != 0;
        }
        TerminalContent terminalContent = getTerminalContent();
        return terminalContent != null && terminalContent.isMouseTrackingActive();
    }

    public boolean isAlternateBufferActive() {
        if (mGhosttySessionWorker != null) {
            return mGhosttyAlternateBufferActive;
        }
        TerminalContent terminalContent = getTerminalContent();
        return terminalContent != null && terminalContent.isAlternateBufferActive();
    }

    public boolean isCursorKeysApplicationMode() {
        if (mGhosttySessionWorker != null) {
            return (mGhosttyModeBits & GhosttyNative.MODE_CURSOR_KEYS_APPLICATION) != 0;
        }
        return mEmulator != null && mEmulator.isCursorKeysApplicationMode();
    }

    public boolean isKeypadApplicationMode() {
        if (mGhosttySessionWorker != null) {
            return (mGhosttyModeBits & GhosttyNative.MODE_KEYPAD_APPLICATION) != 0;
        }
        return mEmulator != null && mEmulator.isKeypadApplicationMode();
    }

    public boolean isBracketedPasteMode() {
        if (mGhosttySessionWorker != null) {
            return (mGhosttyModeBits & GhosttyNative.MODE_BRACKETED_PASTE) != 0;
        }
        return false;
    }

    public void sendMouseEvent(int mouseButton, int column, int row, boolean pressed) {
        if (mGhosttyTerminalContent != null) {
            return;
        }

        if (mEmulator != null) {
            mEmulator.sendMouseEvent(mouseButton, column, row, pressed);
        }
    }

    public void sendGhosttyMouseEvent(GhosttyMouseEvent event) {
        if (mGhosttySessionWorker == null) {
            return;
        }

        mGhosttySessionWorker.sendMouseEvent(event);
    }

    public void paste(String text) {
        if (text == null) {
            return;
        }

        if (mGhosttyTerminalContent == null) {
            if (mEmulator != null) {
                mEmulator.paste(text);
            }
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
        if (mGhosttyTerminalContent != null) {
            mGhosttyCursorBlinkingEnabled = cursorBlinkingEnabled;
            mGhosttyTerminalContent.setCursorBlinkingEnabled(cursorBlinkingEnabled);
            return;
        }
        if (mEmulator != null) {
            mEmulator.setCursorBlinkingEnabled(cursorBlinkingEnabled);
        }
    }

    public void setCursorBlinkState(boolean cursorBlinkState) {
        if (mGhosttyTerminalContent != null) {
            mGhosttyCursorBlinkState = cursorBlinkState;
            mGhosttyTerminalContent.setCursorBlinkState(cursorBlinkState);
            return;
        }
        if (mEmulator != null) {
            mEmulator.setCursorBlinkState(cursorBlinkState);
        }
    }

    public boolean isCursorEnabled() {
        if (mGhosttySessionWorker != null) {
            return mGhosttyCursorVisible;
        }
        return mEmulator != null && mEmulator.isCursorEnabled();
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

    /** Notify the {@link #mClient} that terminal text changed and higher-level listeners should refresh. */
    protected void notifyTextChanged() {
        mClient.onTextChanged(this);
    }

    /** Notify the {@link #mClient} that a new frame is ready to draw. */
    protected void notifyFrameAvailable() {
        mClient.onFrameAvailable(this);
    }

    /** Reset state for terminal emulator state. */
    public void reset() {
        if (mGhosttySessionWorker != null) {
            GhosttyLog.debug("Resetting Ghostty session via worker " + mHandle);
            mGhosttySessionWorker.reset();
            return;
        }

        if (mEmulator != null) {
            mEmulator.reset();
            notifyTextChanged();
        }
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
        if (mGhosttySessionWorker != null) {
            return mLastKnownGhosttyTranscriptRows;
        }
        return mEmulator == null ? 0 : mEmulator.getScreen().getActiveTranscriptRows();
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
        if (mGhosttyTerminalContent != null) {
            return mAutoScrollDisabled;
        }
        return mEmulator != null && mEmulator.isAutoScrollDisabled();
    }

    public void toggleAutoScrollDisabled() {
        if (mGhosttyTerminalContent != null) {
            mAutoScrollDisabled = !mAutoScrollDisabled;
            return;
        }
        if (mEmulator != null) {
            mEmulator.toggleAutoScrollDisabled();
        }
    }

    public int getScrollCounter() {
        if (mGhosttySessionWorker != null) {
            return mScrollCounter.get();
        }
        return mEmulator == null ? 0 : mEmulator.getScrollCounter();
    }

    public void clearScrollCounter() {
        if (mGhosttySessionWorker != null) {
            mScrollCounter.set(0);
            return;
        }
        if (mEmulator != null) {
            mEmulator.clearScrollCounter();
        }
    }

    public int getBackgroundColor() {
        if (mEmulator != null) {
            return mEmulator.mColors.mCurrentColors[TextStyle.COLOR_INDEX_BACKGROUND];
        }
        FrameDelta frameDelta = getGhosttyPublishedFrameDelta();
        if (frameDelta != null) {
            return frameDelta.getTransportSnapshot().getPaletteColor(TextStyle.COLOR_INDEX_BACKGROUND);
        }
        return TerminalColors.COLOR_SCHEME.mDefaultColors[TextStyle.COLOR_INDEX_BACKGROUND];
    }

    private boolean shouldUseGhosttyBackend() {
        return FORCE_GHOSTTY_BACKEND && GhosttyNative.isLibraryLoaded();
    }

    private int resolveTranscriptRows() {
        if (mTranscriptRows == null) {
            return TerminalEmulator.DEFAULT_TERMINAL_TRANSCRIPT_ROWS;
        }
        if (mTranscriptRows < TerminalEmulator.TERMINAL_TRANSCRIPT_ROWS_MIN) {
            return TerminalEmulator.DEFAULT_TERMINAL_TRANSCRIPT_ROWS;
        }
        if (mTranscriptRows > TerminalEmulator.TERMINAL_TRANSCRIPT_ROWS_MAX) {
            return TerminalEmulator.DEFAULT_TERMINAL_TRANSCRIPT_ROWS;
        }
        return mTranscriptRows;
    }

    private void appendToActiveBackend(byte[] buffer, int bytesRead) {
        if (mEmulator != null) {
            mEmulator.append(buffer, bytesRead);
        }
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

        final byte[] mReceiveBuffer = new byte[MAIN_THREAD_INPUT_BUFFER_BYTES];

        @Override
        public void handleMessage(Message msg) {
            removeMessages(MSG_NEW_INPUT);

            if (drainProcessToTerminalQueue(msg.what != MSG_PROCESS_EXITED)) {
                notifyTextChanged();
            }

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
                mGhosttySessionWorker.appendDirect(bytesToWrite);
            } else {
                appendToActiveBackend(bytesToWrite, bytesToWrite.length);
                notifyTextChanged();
            }

            mClient.onSessionFinished(TerminalSession.this);
        }

        private boolean drainProcessToTerminalQueue(boolean timeSlice) {
            boolean screenUpdated = false;
            long deadline = timeSlice ? SystemClock.uptimeMillis() + MAIN_THREAD_INPUT_BUDGET_MILLIS : Long.MAX_VALUE;

            while (true) {
                int bytesRead = mProcessToTerminalIOQueue.read(mReceiveBuffer, false);
                if (bytesRead <= 0) {
                    return screenUpdated;
                }

                if (mGhosttySessionWorker != null) {
                    mGhosttySessionWorker.onOutputAvailable();
                    return screenUpdated;
                }

                appendToActiveBackend(mReceiveBuffer, bytesRead);
                screenUpdated = true;

                if (!timeSlice) {
                    continue;
                }
                if (SystemClock.uptimeMillis() < deadline) {
                    continue;
                }

                if (!hasMessages(MSG_NEW_INPUT)) {
                    sendEmptyMessageDelayed(MSG_NEW_INPUT, MAIN_THREAD_INPUT_RESCHEDULE_DELAY_MILLIS);
                }
                return screenUpdated;
            }
        }

    }

}
