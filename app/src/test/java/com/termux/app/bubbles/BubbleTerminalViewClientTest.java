package com.termux.app.bubbles;

import com.termux.app.BubbleSessionActivity;
import com.termux.terminal.TerminalSession;

import org.junit.Assert;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;

@RunWith(RobolectricTestRunner.class)
public class BubbleTerminalViewClientTest {

    @Test
    public void finishedSessionImeEnterFinishesActivity() {
        BubbleSessionActivity activity = newActivity();
        BubbleTerminalViewClient viewClient = new BubbleTerminalViewClient(activity);
        TerminalSession session = finishedSession();

        for (int codePoint : new int[]{'\r', '\n', 13, 10}) {
            Assert.assertTrue(viewClient.onCodePoint(codePoint, false, session));
            Assert.assertTrue(activity.isFinishing());
        }

        Assert.assertTrue(viewClient.onCodePoint(106, true, session));
        Assert.assertTrue(activity.isFinishing());
    }

    @Test
    public void nonEnterCodePointDoesNotFinishActivity() {
        BubbleSessionActivity activity = newActivity();
        BubbleTerminalViewClient viewClient = new BubbleTerminalViewClient(activity);
        TerminalSession session = finishedSession();

        Assert.assertFalse(viewClient.onCodePoint(106, false, session));
        Assert.assertFalse(activity.isFinishing());
    }

    @Test
    public void enterDoesNotFinishActivityForRunningSession() {
        BubbleSessionActivity activity = newActivity();
        BubbleTerminalViewClient viewClient = new BubbleTerminalViewClient(activity);
        TerminalSession session = newSession();

        try {
            Assert.assertTrue(session.isRunning());
            Assert.assertFalse(viewClient.onCodePoint('\n', false, session));
            Assert.assertFalse(activity.isFinishing());
        } finally {
            session.close();
        }
    }

    private BubbleSessionActivity newActivity() {
        return Robolectric.buildActivity(BubbleSessionActivity.class).get();
    }

    private TerminalSession finishedSession() {
        TerminalSession session = newSession();
        session.close();
        return session;
    }

    private TerminalSession newSession() {
        return new TerminalSession("/bin/sh", "/", new String[]{"sh"}, new String[0], null, null);
    }
}
