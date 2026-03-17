package com.termux.terminal;

import android.util.Log;

import com.termux.emulator.BuildConfig;

final class GhosttyLog {

    private static final String LOG_TAG = "TermuxGhostty";

    private GhosttyLog() {
    }

    static boolean isEnabled() {
        return BuildConfig.TERMUX_GHOSTTY_DEBUG_LOG || Log.isLoggable(LOG_TAG, Log.DEBUG);
    }

    static void debug(String message) {
        if (!isEnabled()) return;
        Log.d(LOG_TAG, message);
    }

    static void info(String message) {
        if (!BuildConfig.TERMUX_GHOSTTY_DEBUG_LOG && !Log.isLoggable(LOG_TAG, Log.INFO) && !Log.isLoggable(LOG_TAG, Log.DEBUG)) {
            return;
        }
        Log.i(LOG_TAG, message);
    }

    static void warn(String message) {
        Log.w(LOG_TAG, message);
    }

    static void warn(String message, Throwable error) {
        Log.w(LOG_TAG, message, error);
    }

    static void error(String message) {
        Log.e(LOG_TAG, message);
    }

    static void error(String message, Throwable error) {
        Log.e(LOG_TAG, message, error);
    }
}
