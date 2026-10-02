package com.mrndtvndv.term.data

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns

fun Context.displayName(uri: Uri): String? {
    val queried = if (uri.scheme == "content") queryDisplayName(uri) else null
    return queried ?: uri.path?.substringAfterLast('/')
}

private fun Context.queryDisplayName(uri: Uri): String? =
    runCatching {
        contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (cursor.moveToFirst() && index != -1) cursor.getString(index) else null
        }
    }.getOrNull()
