package com.termux.terminal;

import org.junit.Test;

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
    public void snapshotCadenceSlowsOnlyForFloodBacklogs() {
        // Interactive TUI output (spinners, shimmer, status bars) keeps a small
        // backlog below one append slice: it must keep the fast 16 ms cadence.
        assertFalse(GhosttySessionWorker.isFloodBacklog(0));
        assertFalse(GhosttySessionWorker.isFloodBacklog(1024));
        assertFalse(GhosttySessionWorker.isFloodBacklog(64 * 1024 - 1));
        // A sustained flood (at least one full slice still queued) falls back
        // to the 33 ms throttle that keeps burst rendering stable.
        assertTrue(GhosttySessionWorker.isFloodBacklog(64 * 1024));
        assertTrue(GhosttySessionWorker.isFloodBacklog(128 * 1024));
    }
}
