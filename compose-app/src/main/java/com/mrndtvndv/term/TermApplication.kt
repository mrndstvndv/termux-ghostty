package com.mrndtvndv.term

import android.app.Application
import android.util.Log

class TermApplication : Application() {

    override fun onCreate() {
        super.onCreate()

        val previousHandler = Thread.getDefaultUncaughtExceptionHandler()
        val crashReporter = CrashReporter(this)
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            crashReporter.report(thread, throwable)
            previousHandler?.uncaughtException(thread, throwable)
                ?: Log.e(TAG, "Uncaught exception", throwable)
        }
    }

    private companion object {
        const val TAG = "TermApplication"
    }
}
