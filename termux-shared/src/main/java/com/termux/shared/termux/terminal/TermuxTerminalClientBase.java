package com.termux.shared.termux.terminal;

import android.view.InputDevice;
import android.view.KeyEvent;
import android.view.MotionEvent;

import androidx.annotation.Nullable;

import com.termux.shared.logger.Logger;
import com.termux.shared.termux.settings.properties.TermuxSharedProperties;
import com.termux.terminal.TerminalSession;
import com.termux.terminal.compose.TerminalComposeView;

/** Shared application policy defaults for Compose-backed terminal hosts. */
public class TermuxTerminalClientBase {

    public float onScale(float scale) {
        return 1.0f;
    }

    public boolean onSingleTapUp(MotionEvent e) {
        return false;
    }

    public boolean shouldBackButtonBeMappedToEscape() {
        return false;
    }

    public boolean shouldEnforceCharBasedInput() {
        return false;
    }

    public boolean shouldUseCtrlSpaceWorkaround() {
        return false;
    }

    public boolean shouldOpenTerminalTranscriptURLOnClick() {
        return false;
    }

    @Nullable
    public String getTerminalTranscriptUrlOnTap(MotionEvent e) {
        return null;
    }

    @Nullable
    protected final String getTerminalTranscriptUrlOnTap(
        MotionEvent e,
        @Nullable TerminalSession session,
        TerminalComposeView terminalView,
        TermuxSharedProperties properties
    ) {
        if (session == null || !session.hasActiveTerminalBackend()) return null;
        if (!properties.shouldOpenTerminalTranscriptURLOnClick()) return null;
        if (terminalView.isSelectingText()) return null;

        boolean touchTapWhileMouseTracking = session.isMouseTrackingActive()
            && !e.isFromSource(InputDevice.SOURCE_MOUSE);
        if (touchTapWhileMouseTracking
            && !properties.shouldOpenTerminalTranscriptURLOnClickWhenMouseTrackingActive()) {
            return null;
        }

        return terminalView.getVisibleLinkUrl(e);
    }

    public boolean isTerminalViewSelected() {
        return true;
    }

    public void copyModeChanged(boolean copyMode) {
    }

    public boolean onKeyDown(int keyCode, KeyEvent e, TerminalSession session) {
        return false;
    }

    public boolean onKeyUp(int keyCode, KeyEvent e) {
        return false;
    }

    public boolean onLongPress(MotionEvent event) {
        return false;
    }

    public boolean readControlKey() {
        return false;
    }

    public boolean readAltKey() {
        return false;
    }

    public boolean readShiftKey() {
        return false;
    }

    public boolean readFnKey() {
        return false;
    }

    public boolean onCodePoint(int codePoint, boolean ctrlDown, TerminalSession session) {
        return false;
    }

    public void onSoftKeyboardDismissed() {
    }

    public void onSoftKeyboardVisibilityChanged(boolean visible) {
    }

    public void onTerminalReady() {
    }

    public void logError(String tag, String message) {
        Logger.logError(tag, message);
    }

    public void logWarn(String tag, String message) {
        Logger.logWarn(tag, message);
    }

    public void logInfo(String tag, String message) {
        Logger.logInfo(tag, message);
    }

    public void logDebug(String tag, String message) {
        Logger.logDebug(tag, message);
    }

    public void logVerbose(String tag, String message) {
        Logger.logVerbose(tag, message);
    }

    public void logStackTraceWithMessage(String tag, String message, Exception e) {
        Logger.logStackTraceWithMessage(tag, message, e);
    }

    public void logStackTrace(String tag, Exception e) {
        Logger.logStackTrace(tag, e);
    }
}
