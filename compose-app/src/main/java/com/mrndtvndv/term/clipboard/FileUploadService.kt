package com.mrndtvndv.term.clipboard

import android.content.ClipData
import android.content.Context
import android.net.Uri
import com.mrndtvndv.term.domain.ServerConfig
import com.mrndtvndv.term.server.Server
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

/** Coordinates clipboard pastes and picker-selected file uploads outside the activity. */
class FileUploadService(context: Context) {
    private val appContext = context.applicationContext

    suspend fun saveImage(
        clipData: ClipData?,
        config: ServerConfig,
        server: Server?,
    ): String? = withContext(Dispatchers.IO) {
        currentCoroutineContext().ensureActive()
        if (config.isLocal) {
            ClipboardImageHandler.saveLocalClipboardImage(
                context = appContext,
                clipData = clipData,
                customDirectory = config.imagePasteDirectory,
                autoCleanup = config.imagePasteAutoCleanup,
                maxRetentionCount = config.safeImagePasteMaxFiles,
            )
        } else {
            val remoteServer = server ?: return@withContext null
            val tempFile = ClipboardImageHandler.saveTempClipboardImage(appContext, clipData)
            if (tempFile == null) return@withContext null
            try {
                ClipboardImageHandler.uploadToRemote(
                    server = remoteServer,
                    localFile = tempFile,
                    customRemoteDir = config.imagePasteDirectory,
                    autoCleanup = config.imagePasteAutoCleanup,
                    maxRetentionCount = config.safeImagePasteMaxFiles,
                )
            } finally {
                tempFile.delete()
            }
        }
    }

    /** Uploads a picker-selected file and returns the path that should be pasted. */
    suspend fun uploadFile(
        uri: Uri,
        fileName: String?,
        config: ServerConfig,
        server: Server?,
    ): String? = withContext(Dispatchers.IO) {
        currentCoroutineContext().ensureActive()
        val resolvedFileName = FileUploadHandler.resolveUploadFileName(
            context = appContext,
            uri = uri,
            requestedName = fileName,
        )
        if (config.isLocal) {
            return@withContext FileUploadHandler.saveLocalFile(
                context = appContext,
                uri = uri,
                customDirectory = config.imagePasteDirectory,
                fileName = resolvedFileName,
            )
        }

        val remoteServer = server ?: return@withContext null
        val tempFile = FileUploadHandler.saveTempFile(appContext, uri)
            ?: return@withContext null
        try {
            ClipboardImageHandler.uploadToRemote(
                server = remoteServer,
                localFile = tempFile,
                customRemoteDir = config.imagePasteDirectory,
                remoteFileName = resolvedFileName,
                autoCleanup = false,
            )
        } finally {
            tempFile.delete()
        }
    }
}
