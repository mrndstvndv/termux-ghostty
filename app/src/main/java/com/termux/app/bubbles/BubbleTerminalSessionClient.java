package com.termux.app.bubbles;

import android.graphics.Typeface;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.termux.app.BubbleSessionActivity;
import com.termux.shared.interact.ShareUtils;
import com.termux.shared.logger.Logger;
import com.termux.shared.termux.TermuxConstants;
import com.termux.shared.termux.terminal.TermuxTerminalSessionClientBase;
import com.termux.terminal.TerminalColors;
import com.termux.terminal.TerminalSession;

import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.util.Properties;

public final class BubbleTerminalSessionClient extends TermuxTerminalSessionClientBase {

    private final BubbleSessionActivity mActivity;

    private static final String LOG_TAG = "BubbleTerminalSessionClient";

    public BubbleTerminalSessionClient(@NonNull BubbleSessionActivity activity) {
        mActivity = activity;
    }

    public void onCreate() {
        applyTerminalStyling();
    }

    public void onResume() {
        updateBackgroundColor();
        mActivity.getTerminalView().setTerminalCursorBlinkerState(true, true);
    }

    public void onStop() {
        mActivity.getTerminalView().setTerminalCursorBlinkerState(false, true);
    }

    @Override
    public void onTextChanged(@NonNull TerminalSession changedSession) {
        if (!mActivity.isVisible()) return;
        if (changedSession != mActivity.getCurrentSession()) return;
        mActivity.getTerminalView().onScreenUpdated();
    }

    @Override
    public void onFrameAvailable(@NonNull TerminalSession changedSession) {
        if (!mActivity.isVisible()) return;
        if (changedSession != mActivity.getCurrentSession()) return;
        mActivity.getTerminalView().onFrameAvailable();
    }

    @Override
    public void onTitleChanged(@NonNull TerminalSession updatedSession) {
        if (updatedSession != mActivity.getCurrentSession()) return;
        mActivity.updateSessionTitle();
    }

    @Override
    public void onSessionFinished(@NonNull TerminalSession finishedSession) {
        if (finishedSession != mActivity.getCurrentSession()) return;
        mActivity.onSessionFinished();
    }

    @Override
    public void onCopyTextToClipboard(@NonNull TerminalSession session, String text) {
        if (!mActivity.isVisible()) return;
        ShareUtils.copyTextToClipboard(mActivity, text);
    }

    @Override
    public void onPasteTextFromClipboard(@Nullable TerminalSession session) {
        if (!mActivity.isVisible()) return;

        String text = ShareUtils.getTextStringFromClipboardIfSet(mActivity, true);
        TerminalSession currentSession = mActivity.getCurrentSession();
        if (text == null || currentSession == null) return;
        currentSession.paste(text);
    }

    @Override
    public void onColorsChanged(@NonNull TerminalSession changedSession) {
        if (changedSession != mActivity.getCurrentSession()) return;
        updateBackgroundColor();
    }

    @Override
    public void onTerminalCursorStateChange(boolean enabled) {
        if (enabled && !mActivity.isVisible()) return;
        mActivity.getTerminalView().setTerminalCursorBlinkerState(enabled, false);
    }

    @Override
    public Integer getTerminalCursorStyle() {
        return mActivity.getProperties().getTerminalCursorStyle();
    }

    public boolean isSessionFocused(@Nullable TerminalSession session) {
        if (session == null) return false;
        if (!mActivity.isVisible()) return false;
        if (!mActivity.hasWindowFocus()) return false;
        return session == mActivity.getCurrentSession();
    }

    public void applyTerminalStyling() {
        try {
            File colorsFile = TermuxConstants.TERMUX_COLOR_PROPERTIES_FILE;
            File fontFile = TermuxConstants.TERMUX_FONT_FILE;

            Properties properties = new Properties();
            if (colorsFile.isFile()) {
                try (InputStream inputStream = new FileInputStream(colorsFile)) {
                    properties.load(inputStream);
                }
            }

            TerminalColors.COLOR_SCHEME.updateWith(properties);
            TerminalSession session = mActivity.getCurrentSession();
            if (session != null) {
                session.reloadColorScheme();
            }

            Typeface typeface = (fontFile.exists() && fontFile.length() > 0)
                ? Typeface.createFromFile(fontFile)
                : Typeface.MONOSPACE;
            mActivity.getTerminalView().setTypeface(typeface);
            updateBackgroundColor();
        } catch (Exception e) {
            Logger.logStackTraceWithMessage(LOG_TAG, "Failed to apply bubble terminal styling", e);
        }
    }

    public void updateBackgroundColor() {
        if (!mActivity.isVisible()) return;

        TerminalSession session = mActivity.getCurrentSession();
        if (session == null) return;
        if (!session.hasActiveTerminalBackend()) return;

        mActivity.getWindow().getDecorView().setBackgroundColor(session.getBackgroundColor());
    }

}
