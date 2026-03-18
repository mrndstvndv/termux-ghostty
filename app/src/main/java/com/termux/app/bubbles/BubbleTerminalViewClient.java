package com.termux.app.bubbles;

import android.os.Build;
import android.view.KeyEvent;
import android.view.MotionEvent;

import androidx.annotation.NonNull;

import com.termux.app.BubbleSessionActivity;
import com.termux.shared.logger.Logger;
import com.termux.shared.termux.extrakeys.SpecialButton;
import com.termux.shared.termux.terminal.TermuxTerminalViewClientBase;
import com.termux.shared.view.KeyboardUtils;
import com.termux.terminal.TerminalSession;

public final class BubbleTerminalViewClient extends TermuxTerminalViewClientBase {

    private final BubbleSessionActivity mActivity;

    private static final String LOG_TAG = "BubbleTerminalViewClient";

    public BubbleTerminalViewClient(@NonNull BubbleSessionActivity activity) {
        mActivity = activity;
    }

    @Override
    public float onScale(float scale) {
        if (scale < 0.9f || scale > 1.1f) {
            changeFontSize(scale > 1.f);
            return 1.0f;
        }

        return scale;
    }

    @Override
    public void onSingleTapUp(MotionEvent e) {
        TerminalSession session = mActivity.getCurrentSession();
        if (session == null) return;
        if (!session.hasActiveTerminalBackend()) return;
        if (KeyboardUtils.areDisableSoftKeyboardFlagsSet(mActivity)) return;

        mActivity.getTerminalView().requestFocus();
        KeyboardUtils.showSoftKeyboard(mActivity, mActivity.getTerminalView());
    }

    @Override
    public boolean shouldBackButtonBeMappedToEscape() {
        return mActivity.getProperties().isBackKeyTheEscapeKey();
    }

    @Override
    public boolean shouldEnforceCharBasedInput() {
        return mActivity.getProperties().isEnforcingCharBasedInput();
    }

    @Override
    public boolean shouldUseCtrlSpaceWorkaround() {
        return mActivity.getProperties().isUsingCtrlSpaceWorkaround();
    }

    @Override
    public boolean isTerminalViewSelected() {
        return true;
    }

    @Override
    public boolean readControlKey() {
        return readExtraKeysSpecialButton(SpecialButton.CTRL);
    }

    @Override
    public boolean readAltKey() {
        return readExtraKeysSpecialButton(SpecialButton.ALT);
    }

    @Override
    public boolean readShiftKey() {
        return readExtraKeysSpecialButton(SpecialButton.SHIFT);
    }

    @Override
    public boolean readFnKey() {
        return readExtraKeysSpecialButton(SpecialButton.FN);
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent e, TerminalSession session) {
        if (keyCode != KeyEvent.KEYCODE_ENTER) return false;
        if (session == null) return false;
        if (session.isRunning()) return false;

        mActivity.finishActivityIfNotFinishing();
        return true;
    }

    @Override
    public boolean onKeyUp(int keyCode, KeyEvent e) {
        TerminalSession currentSession = mActivity.getCurrentSession();
        if (keyCode != KeyEvent.KEYCODE_BACK) return false;
        if (currentSession != null && currentSession.hasActiveTerminalBackend()) return false;

        mActivity.finishActivityIfNotFinishing();
        return true;
    }

    @Override
    public void onSoftKeyboardDismissed() {
        KeyboardUtils.hideSoftKeyboard(mActivity, mActivity.getTerminalView());
    }

    public void onToggleSoftKeyboardRequest() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && KeyboardUtils.isSoftKeyboardVisible(mActivity)) {
            KeyboardUtils.hideSoftKeyboard(mActivity, mActivity.getTerminalView());
            return;
        }

        KeyboardUtils.clearDisableSoftKeyboardFlags(mActivity);
        mActivity.getTerminalView().requestFocus();
        KeyboardUtils.showSoftKeyboard(mActivity, mActivity.getTerminalView());
    }

    @Override
    public void onEmulatorSet() {
        if (!mActivity.isVisible()) {
            Logger.logVerbose(LOG_TAG, "Ignoring cursor blinker start since bubble activity is not visible");
            return;
        }

        mActivity.getTerminalView().setTerminalCursorBlinkerState(true, true);
    }

    private void changeFontSize(boolean increase) {
        mActivity.getPreferences().changeFontSize(increase);
        mActivity.getTerminalView().setTextSize(mActivity.getPreferences().getFontSize());
    }

    private boolean readExtraKeysSpecialButton(@NonNull SpecialButton specialButton) {
        if (mActivity.getExtraKeysView() == null) return false;

        Boolean state = mActivity.getExtraKeysView().readSpecialButton(specialButton, true);
        if (state == null) {
            Logger.logError(LOG_TAG, "Failed to read an unregistered " + specialButton + " special button value from extra keys.");
            return false;
        }

        return state;
    }

}
