package com.termux.app.bubbles;

import android.os.Build;
import android.view.KeyEvent;
import android.view.MotionEvent;

import androidx.annotation.NonNull;

import com.termux.app.BubbleSessionActivity;
import com.termux.shared.logger.Logger;
import com.termux.shared.termux.extrakeys.SpecialButton;
import com.termux.shared.termux.terminal.TermuxTerminalViewClientBase;
import com.termux.shared.termux.settings.preferences.TermuxPreferenceConstants.TERMUX_APP;
import com.termux.shared.view.KeyboardUtils;
import com.termux.terminal.TerminalSession;

public final class BubbleTerminalViewClient extends TermuxTerminalViewClientBase {

    private final BubbleSessionActivity mActivity;

    private Runnable mShowSoftKeyboardRunnable;
    private boolean mShowSoftKeyboardIgnoreOnce;

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
        showSoftKeyboardAndRemember();
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
    public boolean shouldOpenTerminalTranscriptURLOnClick() {
        return false;
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
        hideSoftKeyboardAndRemember();
    }

    public void onToggleSoftKeyboardRequest() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && KeyboardUtils.isSoftKeyboardVisible(mActivity)) {
            hideSoftKeyboardAndRemember();
            return;
        }

        KeyboardUtils.clearDisableSoftKeyboardFlags(mActivity);
        mActivity.getTerminalView().requestFocus();
        showSoftKeyboardAndRemember();
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

    /**
     * Called during {@link BubbleSessionActivity#onResume()} to set the soft keyboard state
     */
    public void setSoftKeyboardState() {
        if (restoreRememberedSoftKeyboardState()) {
            return;
        }

        // If soft keyboard is to be hidden on startup
        if (mActivity.getProperties().shouldSoftKeyboardBeHiddenOnStartup()) {
            Logger.logVerbose(LOG_TAG, "Hiding soft keyboard on startup");
            KeyboardUtils.setSoftKeyboardAlwaysHiddenFlags(mActivity);
            hideSoftKeyboardAndRemember();
            mShowSoftKeyboardIgnoreOnce = true;
        }
    }

    private void showSoftKeyboardAndRemember() {
        showSoftKeyboardAndRemember(false);
    }

    private void showSoftKeyboardAndRemember(boolean withDelay) {
        showSoftKeyboardAndRemember(withDelay, 300);
    }

    private void showSoftKeyboardAndRemember(boolean withDelay, long delayInMillis) {
        KeyboardUtils.clearDisableSoftKeyboardFlags(mActivity);
        if (!withDelay) {
            getShowSoftKeyboardRunnable().run();
            return;
        }

        mActivity.getTerminalView().postDelayed(getShowSoftKeyboardRunnable(), delayInMillis);
    }

    private void hideSoftKeyboardAndRemember() {
        mActivity.getTerminalView().removeCallbacks(getShowSoftKeyboardRunnable());
        KeyboardUtils.hideSoftKeyboard(mActivity, mActivity.getTerminalView());
        rememberSoftKeyboardState(TERMUX_APP.VALUE_LAST_SOFT_KEYBOARD_STATE_HIDDEN);
    }

    private boolean restoreRememberedSoftKeyboardState() {
        if (!mActivity.getProperties().shouldRememberSoftKeyboardState()) return false;

        String lastSoftKeyboardState = mActivity.getPreferences().getLastSoftKeyboardState();
        if (TERMUX_APP.VALUE_LAST_SOFT_KEYBOARD_STATE_UNKNOWN.equals(lastSoftKeyboardState)) return false;

        if (TERMUX_APP.VALUE_LAST_SOFT_KEYBOARD_STATE_VISIBLE.equals(lastSoftKeyboardState)) {
            Logger.logVerbose(LOG_TAG, "Restoring remembered visible soft keyboard state");
            mActivity.getTerminalView().requestFocus();
            showSoftKeyboardAndRemember(true);
            return true;
        }

        Logger.logVerbose(LOG_TAG, "Restoring remembered hidden soft keyboard state");
        KeyboardUtils.setSoftKeyboardAlwaysHiddenFlags(mActivity);
        hideSoftKeyboardAndRemember();
        mActivity.getTerminalView().requestFocus();
        mShowSoftKeyboardIgnoreOnce = true;
        return true;
    }

    private void rememberSoftKeyboardState(String state) {
        if (!mActivity.getProperties().shouldRememberSoftKeyboardState()) return;
        mActivity.getPreferences().setLastSoftKeyboardState(state);
    }

    private Runnable getShowSoftKeyboardRunnable() {
        if (mShowSoftKeyboardRunnable == null) {
            mShowSoftKeyboardRunnable = () -> {
                if (KeyboardUtils.areDisableSoftKeyboardFlagsSet(mActivity)) {
                    Logger.logVerbose(LOG_TAG, "Not showing soft keyboard since it is disabled");
                    return;
                }

                KeyboardUtils.showSoftKeyboard(mActivity, mActivity.getTerminalView());
                rememberSoftKeyboardState(TERMUX_APP.VALUE_LAST_SOFT_KEYBOARD_STATE_VISIBLE);
            };
        }
        return mShowSoftKeyboardRunnable;
    }

}
