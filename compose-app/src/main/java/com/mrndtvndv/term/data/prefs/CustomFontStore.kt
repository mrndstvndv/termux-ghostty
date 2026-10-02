package com.mrndtvndv.term.data.prefs

import android.content.Context
import android.graphics.Typeface
import android.net.Uri
import android.util.Log
import com.mrndtvndv.term.data.displayName
import java.io.File

class CustomFontStore(private val context: Context) {
    private val file get() = File(context.filesDir, "font.ttf")

    fun import(uri: Uri): String? =
        runCatching {
            val input = checkNotNull(context.contentResolver.openInputStream(uri)) { "Cannot open $uri" }
            input.use { source -> file.outputStream().use { source.copyTo(it) } }
            context.displayName(uri) ?: "custom_font.ttf"
        }.onFailure { Log.e(TAG, "Failed to copy font", it) }.getOrNull()

    fun clear() {
        file.delete()
    }

    fun loadTypeface(): Typeface? {
        if (!file.isFile || file.length() <= 0L) return null
        return try {
            Typeface.createFromFile(file)
        } catch (_: RuntimeException) {
            null
        }
    }

    private companion object {
        const val TAG = "CustomFontStore"
    }
}
