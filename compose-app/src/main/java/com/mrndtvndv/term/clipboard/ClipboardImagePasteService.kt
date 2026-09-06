package com.mrndtvndv.term.clipboard

import android.content.ClipData
import android.content.Context
import com.mrndtvndv.term.domain.ServerConfig
import com.mrndtvndv.term.server.Server
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

/** Coordinates local clipboard saves and remote clipboard uploads outside the activity. */
class ClipboardImagePasteService(context: Context) {
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
}
