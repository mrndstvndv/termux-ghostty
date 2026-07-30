package com.mrndtvndv.term

import android.content.ContentValues
import android.content.Context
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Suppress("TooGenericExceptionCaught", "SwallowedException")
object NativeLogcatLogger {

    private const val TAG = "NativeLogcatLogger"
    private const val LOG_FILENAME = "native_runtime_debug.log"
    private const val MAX_LOG_SIZE_BYTES = 20 * 1024 * 1024L // 20 MB

    @Volatile
    private var logcatProcess: Process? = null

    @Synchronized
    fun start(context: Context) {
        if (logcatProcess?.isAlive == true) return
        val logFile = getLogFile(context)

        // Prevent runaway log size
        if (logFile.exists() && logFile.length() > MAX_LOG_SIZE_BYTES) {
            logFile.delete()
        }

        try {
            val builder = ProcessBuilder("logcat", "-f", logFile.absolutePath, "-v", "threadtime", "*:V")
            logcatProcess = builder.start()
            Log.i(TAG, "Started native logcat logger writing to ${logFile.absolutePath}")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start native logcat logger", e)
        }
    }

    @Synchronized
    fun stop() {
        try {
            logcatProcess?.destroy()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to stop native logcat logger", e)
        } finally {
            logcatProcess = null
        }
    }

    fun isRunning(): Boolean = logcatProcess?.isAlive == true

    fun getLogFile(context: Context): File = File(context.filesDir, LOG_FILENAME)

    fun getLogFileSizeMb(context: Context): String {
        val file = getLogFile(context)
        if (!file.exists()) return "0 KB"
        val bytes = file.length()
        return if (bytes >= 1024 * 1024) {
            String.format(Locale.US, "%.2f MB", bytes.toDouble() / (1024 * 1024))
        } else {
            String.format(Locale.US, "%.1f KB", bytes.toDouble() / 1024)
        }
    }

    fun hasDetectedNativeCrash(context: Context): Boolean {
        val file = getLogFile(context)
        if (!file.exists() || file.length() == 0L) return false
        return try {
            file.useLines { lines ->
                lines.any { line ->
                    line.contains("SIGSEGV") ||
                        line.contains("SIGABRT") ||
                        line.contains("SIGBUS") ||
                        line.contains("SIGILL") ||
                        line.contains("backtrace:") ||
                        line.contains("Fatal signal") ||
                        line.contains("tombstoned") ||
                        line.contains("crash_dump")
                }
            }
        } catch (e: Exception) {
            false
        }
    }

    fun clearLog(context: Context): Boolean {
        val file = getLogFile(context)
        return if (file.exists()) file.delete() else true
    }

    fun exportLogToDownloads(context: Context): String? {
        val file = getLogFile(context)
        if (!file.exists() || file.length() == 0L) return null

        val timestamp = SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", Locale.US).format(Date())
        val fileName = "term-native-debug-$timestamp.log"

        return try {
            val content = file.readText(Charsets.UTF_8)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val values = ContentValues().apply {
                    put(MediaStore.Downloads.DISPLAY_NAME, fileName)
                    put(MediaStore.Downloads.MIME_TYPE, "text/plain")
                    put(MediaStore.Downloads.RELATIVE_PATH, "${Environment.DIRECTORY_DOWNLOADS}/")
                }
                val resolver = context.contentResolver
                val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                    ?: error("Unable to create MediaStore entry in Downloads")
                resolver.openOutputStream(uri)?.use { it.write(content.toByteArray(Charsets.UTF_8)) }
                "Downloads/$fileName"
            } else {
                @Suppress("DEPRECATION")
                val downloads = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
                if (downloads.exists() || downloads.mkdirs()) {
                    val target = File(downloads, fileName)
                    target.writeText(content, Charsets.UTF_8)
                    target.absolutePath
                } else null
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to export native log", e)
            null
        }
    }
}
