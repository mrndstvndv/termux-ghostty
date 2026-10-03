package com.mrndtvndv.term.ui.sftp

import android.content.Context
import android.content.Intent
import android.util.Log
import android.webkit.MimeTypeMap
import androidx.core.content.FileProvider
import java.io.File

private val SourceCodeExtensions = setOf(
    "kt", "java", "py", "js", "md", "markdown", "rs", "zig", "c", "cpp",
    "h", "hpp", "sh", "txt", "xml", "json", "yml", "yaml", "gradle", "kts", "go",
)

@Suppress("TooGenericExceptionCaught")
fun openDownloadedFile(
    context: Context,
    file: File,
    onViewText: (File) -> Unit,
    onError: (title: String, message: String) -> Unit,
) {
    try {
        val extension = file.extension.lowercase()
        val mimeType = MimeTypeMap.getSingleton().getMimeTypeFromExtension(extension)

        if (mimeType?.startsWith("text/") == true || extension in SourceCodeExtensions) {
            onViewText(file)
            return
        }

        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, mimeType ?: "*/*")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(Intent.createChooser(intent, "Open file with..."))
    } catch (e: Exception) {
        Log.e("OpenDownloadedFile", "Failed to open file", e)
        onError("Error", "Failed to open file: ${e.localizedMessage}")
    }
}
