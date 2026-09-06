package com.mrndtvndv.term.clipboard

import android.content.Context
import android.net.Uri
import android.util.Log
import android.webkit.MimeTypeMap
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.io.File
import java.io.InputStream
import java.io.OutputStream

/** Copies files selected through the system picker into local or staged storage. */
object FileUploadHandler {
    private const val TAG = "FileUploadHandler"

    /** Resolves a provider name into a safe file name for local and remote paths. */
    fun resolveUploadFileName(
        context: Context,
        uri: Uri,
        requestedName: String?,
    ): String {
        val mimeType = try {
            context.contentResolver.getType(uri)
        } catch (_: Exception) {
            null
        }
        return resolveSafeUploadFileName(
            requestedName = requestedName,
            mimeType = mimeType,
        )
    }

    internal fun resolveSafeUploadFileName(
        requestedName: String?,
        mimeType: String?,
        timestamp: Long = System.currentTimeMillis(),
    ): String {
        sanitizeFileName(requestedName)?.let { return it }
        val extension = mimeType?.let { type ->
            try {
                getExtensionFromMimeType(type)
            } catch (_: RuntimeException) {
                null
            }
        }
        return "upload_$timestamp" + (extension?.let { ".${it}" } ?: "")
    }

    /** Saves a picker-selected file to the local terminal's configured directory. */
    @Suppress("TooGenericExceptionCaught")
    suspend fun saveLocalFile(
        context: Context,
        uri: Uri,
        customDirectory: String?,
        fileName: String,
    ): String? {
        val dir = ClipboardPathResolver.resolveLocalDirectory(
            customDirectory = customDirectory,
            homeOverride = context.filesDir.absolutePath,
        ) ?: return null
        val safeFileName = sanitizeFileName(fileName) ?: return null
        val outputFile = File(dir, safeFileName)
        return try {
            val copied = copyUriToFile(context, uri, outputFile)
            if (copied) {
                outputFile.absolutePath
            } else {
                outputFile.delete()
                null
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            outputFile.delete()
            Log.w(TAG, "Failed to save selected file", e)
            null
        }
    }

    /** Saves a picker-selected file to cache before uploading it over SFTP. */
    @Suppress("TooGenericExceptionCaught")
    suspend fun saveTempFile(context: Context, uri: Uri): File? {
        val tempDir = File(context.cacheDir, "file_upload_temp").apply { mkdirs() }
        val tempFile = try {
            File.createTempFile("upload_", ".tmp", tempDir)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to create selected file staging file", e)
            return null
        }
        return try {
            if (copyUriToFile(context, uri, tempFile)) {
                tempFile
            } else {
                tempFile.delete()
                null
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            tempFile.delete()
            Log.w(TAG, "Failed to stage selected file", e)
            null
        }
    }

    private fun getExtensionFromMimeType(mimeType: String): String? {
        return when (mimeType.lowercase()) {
            "video/mp4" -> "mp4"
            "video/webm" -> "webm"
            "video/quicktime" -> "mov"
            "video/3gpp" -> "3gp"
            else -> MimeTypeMap.getSingleton().getExtensionFromMimeType(mimeType)
        }
    }

    internal fun sanitizeFileName(fileName: String?): String? = fileName
        ?.substringAfterLast('/')
        ?.substringAfterLast('\\')
        ?.replace('\u0000', '_')
        ?.trim()
        ?.takeIf { it.isNotEmpty() && it != "." && it != ".." }

    private suspend fun copyUriToFile(context: Context, uri: Uri, target: File): Boolean {
        val input = context.contentResolver.openInputStream(uri) ?: return false
        input.use { source ->
            target.outputStream().use { destination ->
                copyStream(source, destination)
            }
        }
        return true
    }

    private suspend fun copyStream(source: InputStream, destination: OutputStream) {
        val buffer = ByteArray(16 * 1024)
        while (true) {
            currentCoroutineContext().ensureActive()
            val bytesRead = source.read(buffer)
            if (bytesRead < 0) break
            if (bytesRead > 0) destination.write(buffer, 0, bytesRead)
        }
    }
}
