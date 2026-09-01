package com.termux.app.terminal;

import com.termux.app.TermuxActivity;
import com.termux.terminal.TerminalSession;

import org.junit.Assert;
import org.junit.Test;

public class TermuxTerminalViewClientTest {

    @Test
    public void finishedSessionImeEnterRemovesSession() {
        RecordingSessionActivityClient sessionActivityClient = new RecordingSessionActivityClient();
        TermuxTerminalViewClient viewClient = new TermuxTerminalViewClient(null, sessionActivityClient);

        for (int codePoint : new int[]{'\r', '\n', 13, 10}) {
            TerminalSession session = finishedSession();

            Assert.assertTrue(viewClient.onCodePoint(codePoint, false, session));
            Assert.assertSame(session, sessionActivityClient.removedSession);
        }

        TerminalSession ctrlJSession = finishedSession();
        Assert.assertTrue(viewClient.onCodePoint(106, true, ctrlJSession));
        Assert.assertSame(ctrlJSession, sessionActivityClient.removedSession);
    }

    @Test
    public void nonEnterCodePointDoesNotRemoveFinishedSession() {
        RecordingSessionActivityClient sessionActivityClient = new RecordingSessionActivityClient();
        TermuxTerminalViewClient viewClient = new TermuxTerminalViewClient(null, sessionActivityClient);
        TerminalSession session = finishedSession();

        Assert.assertFalse(viewClient.onCodePoint(106, false, session));
        Assert.assertNull(sessionActivityClient.removedSession);
    }

    @Test
    public void enterDoesNotRemoveRunningSession() {
        RecordingSessionActivityClient sessionActivityClient = new RecordingSessionActivityClient();
        TermuxTerminalViewClient viewClient = new TermuxTerminalViewClient(null, sessionActivityClient);
        TerminalSession session = newSession();

        try {
            Assert.assertTrue(session.isRunning());
            Assert.assertFalse(viewClient.onCodePoint('\n', false, session));
            Assert.assertNull(sessionActivityClient.removedSession);
        } finally {
            session.close();
        }
    }

    private TerminalSession finishedSession() {
        TerminalSession session = newSession();
        session.close();
        return session;
    }

    private TerminalSession newSession() {
        return new TerminalSession("/bin/sh", "/", new String[]{"sh"}, new String[0], null, null);
    }

    private static final class RecordingSessionActivityClient extends TermuxTerminalSessionActivityClient {
        private TerminalSession removedSession;

        private RecordingSessionActivityClient() {
            super((TermuxActivity) null);
        }

        @Override
        public void removeFinishedSession(TerminalSession finishedSession) {
            removedSession = finishedSession;
        }
    }
}
