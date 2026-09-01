package com.termux.terminal;

import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.assertEquals;

public class TerminalSessionGhosttyOnlyTest {

    @Test
    public void resolveTranscriptRowsUsesDefaultForNullAndOutOfRangeValues() {
        assertEquals(
            TerminalConstants.DEFAULT_TERMINAL_TRANSCRIPT_ROWS,
            TerminalSession.resolveTranscriptRows(null)
        );
        assertEquals(
            TerminalConstants.DEFAULT_TERMINAL_TRANSCRIPT_ROWS,
            TerminalSession.resolveTranscriptRows(TerminalConstants.TERMINAL_TRANSCRIPT_ROWS_MIN - 1)
        );
        assertEquals(
            TerminalConstants.DEFAULT_TERMINAL_TRANSCRIPT_ROWS,
            TerminalSession.resolveTranscriptRows(TerminalConstants.TERMINAL_TRANSCRIPT_ROWS_MAX + 1)
        );
    }

    @Test
    public void resolveTranscriptRowsKeepsValidValue() {
        assertEquals(
            TerminalConstants.TERMINAL_TRANSCRIPT_ROWS_MIN,
            TerminalSession.resolveTranscriptRows(TerminalConstants.TERMINAL_TRANSCRIPT_ROWS_MIN)
        );
        assertEquals(
            TerminalConstants.TERMINAL_TRANSCRIPT_ROWS_MAX,
            TerminalSession.resolveTranscriptRows(TerminalConstants.TERMINAL_TRANSCRIPT_ROWS_MAX)
        );
        assertEquals(4096, TerminalSession.resolveTranscriptRows(4096));
    }

    @Test
    public void backgroundColorFallsBackToCurrentThemeBeforeFirstFrame() {
        TerminalSession session = new TerminalSession("/bin/sh", "/", new String[]{"sh"}, new String[0], null, null);
        assertEquals(
            TerminalColors.COLOR_SCHEME.mDefaultColors[TextStyle.COLOR_INDEX_BACKGROUND],
            session.getBackgroundColor()
        );
    }

    @Test
    public void kittyKeyboardFlagsUsesWorkerPublishedCache() {
        TerminalSession session = new TerminalSession("/bin/sh", "/", new String[]{"sh"}, new String[0], null, null);
        session.mGhosttyKittyKeyboardFlags = 1;

        assertEquals(1, session.getKittyKeyboardFlags());
    }

    @Test
    public void cursorBlinkStateUpdatesWithoutTerminalContent() {
        TerminalSession session = new TerminalSession("/bin/sh", "/", new String[]{"sh"}, new String[0], null, null);
        session.mGhosttyCursorVisible = true;
        session.mGhosttyCursorBlinkingEnabled = true;

        session.setCursorBlinkState(false);

        assertFalse(session.shouldCursorBeVisible());
    }

    @Test
    public void processExitAppendsExitMessageBeforeSessionFinish() {
        List<String> events = new ArrayList<>();
        RecordingBackendResources backendResources = new RecordingBackendResources(events);
        TerminalSession session = new TerminalSession(new RecordingClient(events), backendResources);

        session.onProcessExited(23);

        assertEquals(
            Arrays.asList("append:\r\n[Process completed (code 23) - press Enter]", "finish"),
            events
        );
        assertFalse(session.isRunning());
    }

    @Test
    public void processExitKeepsBackendActiveUntilSessionIsClosed() {
        RecordingBackendResources backendResources = new RecordingBackendResources(new ArrayList<>());
        TerminalSession session = new TerminalSession(null, backendResources);

        session.onProcessExited(0);

        assertTrue(session.hasActiveTerminalBackend());
        session.close();
        assertFalse(session.hasActiveTerminalBackend());
    }

    @Test
    public void closeShutsDownWorkerAndNativeContentOnce() {
        List<String> events = new ArrayList<>();
        RecordingBackendResources backendResources = new RecordingBackendResources(events);
        TerminalSession session = new TerminalSession(null, backendResources);

        session.close();
        session.close();

        assertEquals(Arrays.asList("worker-shutdown", "native-content-close"), events);
        assertFalse(session.hasActiveTerminalBackend());
    }

    private static final class RecordingBackendResources implements TerminalSession.BackendResources {
        private final List<String> events;
        private boolean active = true;

        RecordingBackendResources(List<String> events) {
            this.events = events;
        }

        @Override
        public boolean isActive() {
            return active;
        }

        @Override
        public void appendDirect(byte[] data) {
            events.add("append:" + new String(data, StandardCharsets.UTF_8));
        }

        @Override
        public void shutdownWorker() {
            events.add("worker-shutdown");
        }

        @Override
        public void closeNativeContent() {
            active = false;
            events.add("native-content-close");
        }
    }

    private static final class RecordingClient implements TerminalSessionClient {
        private final List<String> events;

        RecordingClient(List<String> events) {
            this.events = events;
        }

        @Override
        public void onTextChanged(TerminalSession changedSession) {
        }

        @Override
        public void onFrameAvailable(TerminalSession changedSession) {
        }

        @Override
        public void onTitleChanged(TerminalSession changedSession) {
        }

        @Override
        public void onSessionFinished(TerminalSession finishedSession) {
            events.add("finish");
        }

        @Override
        public void onCopyTextToClipboard(TerminalSession session, String text) {
        }

        @Override
        public void onPasteTextFromClipboard(TerminalSession session) {
        }

        @Override
        public void onBell(TerminalSession session) {
        }

        @Override
        public void onColorsChanged(TerminalSession session) {
        }

        @Override
        public void onTerminalProtocolNotification(TerminalSession session, String title, String body) {
        }

        @Override
        public void onTerminalProgressChanged(TerminalSession session) {
        }

        @Override
        public void onTerminalCursorStateChange(boolean state) {
        }

        @Override
        public void setTerminalShellPid(TerminalSession session, int pid) {
        }

        @Override
        public Integer getTerminalCursorStyle() {
            return null;
        }

        @Override
        public void logError(String tag, String message) {
        }

        @Override
        public void logWarn(String tag, String message) {
        }

        @Override
        public void logInfo(String tag, String message) {
        }

        @Override
        public void logDebug(String tag, String message) {
        }

        @Override
        public void logVerbose(String tag, String message) {
        }

        @Override
        public void logStackTraceWithMessage(String tag, String message, Exception e) {
        }

        @Override
        public void logStackTrace(String tag, Exception e) {
        }
    }
}
