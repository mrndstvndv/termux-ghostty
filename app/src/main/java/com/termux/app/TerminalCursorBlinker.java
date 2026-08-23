package com.termux.app;

import android.os.Handler;
import android.os.Looper;

import androidx.annotation.Nullable;

import com.termux.terminal.TerminalConstants;
import com.termux.terminal.TerminalSession;

/** Activity-owned cursor blink policy shared by the legacy Java hosts and Compose canvas. */
public final class TerminalCursorBlinker {

    private final Handler mHandler = new Handler(Looper.getMainLooper());
    private final Runnable mOnStateChanged;
    private TerminalSession mSession;
    private Runnable mRunnable;
    private int mBlinkRate;

    public TerminalCursorBlinker(Runnable onStateChanged) {
        mOnStateChanged = onStateChanged;
    }

    public void setSession(@Nullable TerminalSession session) {
        stop();
        mSession = session;
    }

    public boolean setRate(int blinkRate) {
        if (blinkRate != 0 && (blinkRate < TerminalConstants.TERMINAL_CURSOR_BLINK_RATE_MIN ||
            blinkRate > TerminalConstants.TERMINAL_CURSOR_BLINK_RATE_MAX)) {
            mBlinkRate = 0;
            stop();
            return false;
        }
        mBlinkRate = blinkRate;
        if (blinkRate == 0) stop();
        return true;
    }

    public void setState(boolean start, boolean startOnlyIfCursorEnabled) {
        stop();
        TerminalSession session = mSession;
        if (session == null || !session.hasActiveTerminalBackend()) return;

        session.setCursorBlinkingEnabled(false);
        if (!start || mBlinkRate < TerminalConstants.TERMINAL_CURSOR_BLINK_RATE_MIN ||
            mBlinkRate > TerminalConstants.TERMINAL_CURSOR_BLINK_RATE_MAX ||
            (startOnlyIfCursorEnabled && !session.isCursorEnabled())) {
            mOnStateChanged.run();
            return;
        }

        mRunnable = new Runnable() {
            private boolean mCursorVisible;

            @Override
            public void run() {
                TerminalSession current = mSession;
                if (current == null || !current.hasActiveTerminalBackend()) return;
                mCursorVisible = !mCursorVisible;
                current.setCursorBlinkState(mCursorVisible);
                mOnStateChanged.run();
                mHandler.postDelayed(this, mBlinkRate);
            }
        };
        session.setCursorBlinkingEnabled(true);
        mRunnable.run();
    }

    public void stop() {
        if (mRunnable != null) {
            mHandler.removeCallbacks(mRunnable);
            mRunnable = null;
        }
        if (mSession != null && mSession.hasActiveTerminalBackend()) {
            mSession.setCursorBlinkingEnabled(false);
        }
    }

}
