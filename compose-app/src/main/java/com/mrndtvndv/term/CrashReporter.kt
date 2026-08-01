package com.mrndtvndv.term

import android.content.ContentValues
import android.content.Context
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class CrashReporter(private val context: Context) {

    @Suppress("TooGenericExceptionCaught")
    fun report(thread: Thread, throwable: Throwable) {
        try {
            val timestamp = SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", Locale.US).format(Date())
            val fileName = "term-crash-$timestamp.txt"
            val report = buildReport(thread, throwable, timestamp)

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                writeWithMediaStore(fileName, report)
            } else {
                writeToPublicDownloads(fileName, report)
            }
        } catch (error: Throwable) {
            Log.e(TAG, "Unable to save crash report", error)
        }
    }

    private fun buildReport(thread: Thread, throwable: Throwable, timestamp: String): String {
        val stackTrace = StringWriter()
        throwable.printStackTrace(PrintWriter(stackTrace))
        return """
            Ecto crash report
            Time: $timestamp
            Thread: ${thread.name}

            $stackTrace
        """.trimIndent()
    }

    private fun writeWithMediaStore(fileName: String, report: String) {
        val values = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, fileName)
            put(MediaStore.Downloads.MIME_TYPE, "text/plain")
            put(MediaStore.Downloads.RELATIVE_PATH, "${Environment.DIRECTORY_DOWNLOADS}/")
        }

        val resolver = context.contentResolver
        val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
            ?: error("Unable to create a file in Downloads")
        var reportWritten = false

        try {
            val output = resolver.openOutputStream(uri) ?: error("Unable to open the crash report")
            output.use { it.write(report.toByteArray(Charsets.UTF_8)) }
            reportWritten = true
        } finally {
            if (!reportWritten) {
                resolver.delete(uri, null, null)
            }
        }
    }

    @Suppress("DEPRECATION")
    private fun writeToPublicDownloads(fileName: String, report: String) {
        val downloads = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
        check(downloads.exists() || downloads.mkdirs()) {
            "Unable to create the public Downloads directory"
        }
        File(downloads, fileName).writeText(report, Charsets.UTF_8)
    }

    private companion object {
        const val TAG = "CrashReporter"
    }
}
