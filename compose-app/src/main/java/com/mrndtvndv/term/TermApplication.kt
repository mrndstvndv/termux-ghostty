package com.mrndtvndv.term

import android.app.Application
import android.util.Log
import com.mrndtvndv.term.ui.sftp.transfer.SftpTransferManager

class TermApplication : Application() {

    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
        SftpTransferManager.init(this)

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
