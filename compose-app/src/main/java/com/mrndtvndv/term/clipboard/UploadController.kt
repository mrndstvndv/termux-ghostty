package com.mrndtvndv.term.clipboard

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.net.Uri
import com.mrndtvndv.term.data.displayName
import com.mrndtvndv.term.domain.ServerConfig
import com.mrndtvndv.term.server.Server
import com.termux.shared.interact.ShareUtils
import com.termux.terminal.TerminalSession
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class UploadTarget(
    val session: TerminalSession?,
    val server: Server?,
    val config: ServerConfig?,
)

/** Clipboard-image pastes and picker-selected file uploads into a terminal session. */
class UploadController(
    private val context: Context,
    private val scope: CoroutineScope,
    private val fileUploadService: FileUploadService,
    private val resolveTarget: (TerminalSession?) -> UploadTarget,
    private val pickMedia: () -> Unit,
    private val pickFile: () -> Unit,
    private val showToast: (String) -> Unit,
) {
    private val _inProgress = MutableStateFlow(false)
    val inProgress: StateFlow<Boolean> = _inProgress.asStateFlow()

    private var job: Job? = null
    private var pickerTargetSession: TerminalSession? = null

    fun requestMediaUpload(session: TerminalSession) {
        if (preparePicker(session)) pickMedia()
    }

    fun requestFileUpload(session: TerminalSession) {
        if (preparePicker(session)) pickFile()
    }

    fun onFilePicked(uri: Uri?) {
        val targetSession = pickerTargetSession
        pickerTargetSession = null
        if (uri == null || targetSession == null) return

        val target = resolveTarget(targetSession)
        val config = target.config
        if (config == null || !canUpload(config, target.server)) {
            showUploadConfigurationError()
            return
        }
        launchUpload {
            val path = fileUploadService.uploadFile(
                uri = uri,
                fileName = context.displayName(uri),
                config = config,
                server = target.server,
            )
            if (path == null) showToast("File upload failed") else targetSession.paste(path)
        }
    }

    fun handlePaste(session: TerminalSession?) {
        val target = resolveTarget(session)
        val targetSession = target.session ?: return
        val clipData = (context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager)?.primaryClip
        val config = clipData?.let { imagePasteConfig(target.config, it) }
        if (clipData == null || config == null) {
            pasteClipboardText(targetSession)
            return
        }
        pasteClipboardImage(targetSession, clipData, config, target.server, pasteTextOnFailure = true)
    }

    fun handleCommittedContent(targetSession: TerminalSession, clipData: ClipData): Boolean {
        val target = resolveTarget(targetSession)
        val config = imagePasteConfig(target.config, clipData) ?: return false
        if (!canUpload(config, target.server)) return false
        return pasteClipboardImage(targetSession, clipData, config, target.server, pasteTextOnFailure = false)
    }

    fun cancel() {
        if (job?.isActive != true) return
        job?.cancel()
        showToast("File upload cancelled")
    }

    private fun preparePicker(session: TerminalSession): Boolean {
        if (_inProgress.value) return false
        val target = resolveTarget(session)
        if (!canUpload(target.config, target.server)) {
            showUploadConfigurationError()
            return false
        }
        pickerTargetSession = session
        return true
    }

    private fun pasteClipboardImage(
        targetSession: TerminalSession,
        clipData: ClipData,
        config: ServerConfig,
        server: Server?,
        pasteTextOnFailure: Boolean,
    ): Boolean = launchUpload {
        val path = fileUploadService.saveImage(clipData = clipData, config = config, server = server)
        if (path == null && !pasteTextOnFailure) showToast("Image paste upload failed")
        val textToPaste = path ?: if (pasteTextOnFailure) {
            ShareUtils.getTextStringFromClipboardIfSet(context, true)
        } else {
            null
        }
        textToPaste?.let(targetSession::paste)
    }

    private fun launchUpload(block: suspend () -> Unit): Boolean {
        if (_inProgress.value) return false
        _inProgress.value = true
        job = scope.launch {
            try {
                block()
            } finally {
                _inProgress.value = false
                job = null
            }
        }
        return true
    }

    private fun canUpload(config: ServerConfig?, server: Server?): Boolean =
        config?.isImagePasteActive == true && (config.isLocal || server != null)

    private fun imagePasteConfig(config: ServerConfig?, clipData: ClipData): ServerConfig? =
        config?.takeIf {
            it.imagePasteEnabled &&
                !it.imagePasteDirectory?.trim().isNullOrEmpty() &&
                ClipboardImageHandler.isImageClip(context, clipData)
        }

    private fun pasteClipboardText(session: TerminalSession) {
        ShareUtils.getTextStringFromClipboardIfSet(context, true)?.let(session::paste)
    }

    private fun showUploadConfigurationError() {
        showToast("Enable uploads and set an upload directory first")
    }
}
