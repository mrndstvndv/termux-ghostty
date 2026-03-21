package com.termux.terminal;

import org.junit.Test;

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
}
