package com.mrndtvndv.term.clipboard

import android.content.ClipData
import android.content.Context
import android.util.Log
import android.webkit.MimeTypeMap
import com.mrndtvndv.term.domain.SftpClient
import com.mrndtvndv.term.domain.SftpFile
import com.mrndtvndv.term.domain.ServerConfig
import com.mrndtvndv.term.server.Server
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File

object ClipboardImageHandler {
    private const val TAG = "ClipboardImageHandler"
    const val MAX_IMAGE_RETENTION_COUNT = ServerConfig.DEFAULT_IMAGE_PASTE_MAX_FILES
    const val MAX_IMAGE_RETENTION_AGE_MS = 24 * 60 * 60 * 1000L // 24 hours

    /**
     * Checks whether the given [ClipData] contains image data.
     */
    fun isImageClip(context: Context, clipData: ClipData?): Boolean {
        if (clipData == null || clipData.itemCount == 0) return false
        val hasImageMime = clipData.description?.hasMimeType("image/*") == true
        val hasImageUri = clipData.getItemAt(0)?.uri?.let { uri ->
            context.contentResolver.getType(uri)?.startsWith("image/")
        } == true
        return hasImageMime || hasImageUri
    }

    /**
     * Saves the image from [clipData] to a local directory and returns its absolute path.
     * The application files directory is used when expanding a leading `~`, so the path
     * has the same home as the local shell created by [Server].
     */
    @Suppress("TooGenericExceptionCaught")
    fun saveLocalClipboardImage(
        context: Context,
        clipData: ClipData?,
        customDirectory: String? = null,
        autoCleanup: Boolean = true,
        maxRetentionCount: Int = MAX_IMAGE_RETENTION_COUNT,
    ): String? {
        val uri = clipData?.getItemAt(0)?.uri ?: return null
        val dir = ClipboardPathResolver.resolveLocalDirectory(
            customDirectory = customDirectory,
            homeOverride = context.filesDir.absolutePath,
        ) ?: return null
        return try {
            val mimeType = context.contentResolver.getType(uri) ?: "image/png"
            val extension = getExtensionForMimeType(mimeType)
            val outputFile = File(dir, "clipboard_${System.currentTimeMillis()}.$extension")

            val copySuccess = context.contentResolver.openInputStream(uri)?.use { input ->
                outputFile.outputStream().use { output ->
                    input.copyTo(output)
                    true
                }
            } ?: false

            if (copySuccess) {
                if (autoCleanup) {
                    pruneOldImages(dir, maxRetentionCount = maxRetentionCount)
                }
                outputFile.absolutePath
            } else {
                null
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "Failed to save local clipboard image", e)
            null
        }
    }

    /**
     * Saves the image from [clipData] to a temporary file in app cache.
     * Useful before uploading to a remote server.
     */
    @Suppress("TooGenericExceptionCaught")
    fun saveTempClipboardImage(context: Context, clipData: ClipData?): File? {
        val uri = clipData?.getItemAt(0)?.uri ?: return null
        return try {
            val mimeType = context.contentResolver.getType(uri) ?: "image/png"
            val extension = getExtensionForMimeType(mimeType)
            val tempDir = File(context.cacheDir, "clipboard_temp").apply { mkdirs() }
            val tempFile = File(tempDir, "clipboard_${System.currentTimeMillis()}.$extension")
            val copied = context.contentResolver.openInputStream(uri)?.use { input ->
                tempFile.outputStream().use { output ->
                    input.copyTo(output)
                    true
                }
            } ?: false
            if (copied) tempFile else null
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "Failed to save temp clipboard image", e)
            null
        }
    }

    /**
     * Uploads a local image file to the server's existing SFTP client.
     * Remote directory creation is the only shell operation; upload and cleanup use SFTP paths.
     */
    @Suppress("TooGenericExceptionCaught", "SwallowedException")
    suspend fun uploadToRemote(
        server: Server,
        localFile: File,
        customRemoteDir: String? = null,
        autoCleanup: Boolean = true,
        maxRetentionCount: Int = MAX_IMAGE_RETENTION_COUNT,
    ): String? = withContext(Dispatchers.IO) {
        try {
            val ssh = server.sshSession ?: return@withContext null
            val sftp = server.sftpClient ?: run {
                Log.w(TAG, "Remote image upload requires the server SFTP client")
                return@withContext null
            }
            val resolvedDir = ClipboardPathResolver.resolveRemoteDir(ssh, customRemoteDir)
                ?: return@withContext null
            val remoteFilePath = "${resolvedDir.removeSuffix("/")}/${localFile.name}"

            val uploadContext = currentCoroutineContext()
            sftp.uploadFile(localFile, remoteFilePath) {
                uploadContext.ensureActive()
            }
            if (autoCleanup) {
                try {
                    pruneRemoteImages(
                        sftp = sftp,
                        directory = resolvedDir,
                        maxRetentionCount = maxRetentionCount,
                    )
                } catch (e: CancellationException) {
                    throw e
                } catch (error: Exception) {
                    Log.d(TAG, "Failed to prune remote clipboard images", error)
                }
            }
            remoteFilePath
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "Failed to upload clipboard image to remote server", e)
            null
        } finally {
            try {
                localFile.delete()
            } catch (_: Exception) { }
        }
    }

    @Suppress("TooGenericExceptionCaught", "SwallowedException")
    private suspend fun pruneRemoteImages(
        sftp: SftpClient,
        directory: String,
        maxRetentionCount: Int,
    ) {
        val files = sftp.listFiles(directory)
        selectRemoteImageFilesForDeletion(
            files = files,
            maxRetentionCount = maxRetentionCount,
        ).forEach { file ->
            try {
                sftp.deleteFile(file.path)
            } catch (e: CancellationException) {
                throw e
            } catch (error: Exception) {
                Log.d(TAG, "Failed to delete remote clipboard image ${file.path}", error)
            }
        }
    }

    internal fun selectRemoteImageFilesForDeletion(
        files: List<SftpFile>,
        maxRetentionCount: Int,
        nowMs: Long = System.currentTimeMillis(),
    ): List<SftpFile> {
        val imageFiles = files.filter { !it.isDirectory && it.name.startsWith("clipboard_") }
        val expiryTime = nowMs - MAX_IMAGE_RETENTION_AGE_MS
        val expired = imageFiles.filter { it.modifiedTime > 0L && it.modifiedTime < expiryTime }
        val retained = imageFiles
            .filterNot { it in expired }
            .sortedWith(compareByDescending<SftpFile> { it.modifiedTime }.thenByDescending { it.name })
        return expired + retained.drop(maxRetentionCount.coerceAtLeast(1))
    }

    /**
     * Prunes files in [directory]: removes files older than [MAX_IMAGE_RETENTION_AGE_MS]
     * and trims the total file count to a positive retention limit.
     */
    @Suppress("TooGenericExceptionCaught")
    fun pruneOldImages(
        directory: File,
        maxRetentionCount: Int = MAX_IMAGE_RETENTION_COUNT,
        nowMs: Long = System.currentTimeMillis(),
    ) {
        try {
            val files = directory.listFiles() ?: return
            val imageFiles = files.filter { it.isFile && it.name.startsWith("clipboard_") }
            val expiryTime = nowMs - MAX_IMAGE_RETENTION_AGE_MS

            for (file in imageFiles) {
                if (file.lastModified() < expiryTime) {
                    file.delete()
                }
            }

            val remaining = directory.listFiles()
                ?.filter { it.isFile && it.name.startsWith("clipboard_") }
                ?.sortedByDescending { it.lastModified() }
                ?: return
            val retentionLimit = maxRetentionCount.coerceAtLeast(1)
            if (remaining.size > retentionLimit) {
                remaining.drop(retentionLimit).forEach { it.delete() }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to prune clipboard images", e)
        }
    }

    fun getExtensionForMimeType(mimeType: String): String {
        return when (mimeType.lowercase()) {
            "image/png" -> "png"
            "image/jpeg", "image/jpg" -> "jpg"
            "image/webp" -> "webp"
            "image/gif" -> "gif"
            "image/svg+xml" -> "svg"
            else -> MimeTypeMap.getSingleton().getExtensionFromMimeType(mimeType) ?: "png"
        }
    }
}
