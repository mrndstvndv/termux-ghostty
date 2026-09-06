package com.mrndtvndv.term

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import com.mrndtvndv.term.domain.ServerConfig
import com.mrndtvndv.term.server.Server
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.KeyEvent
import android.view.inputmethod.InputMethodManager
import android.webkit.MimeTypeMap
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.runtime.*
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import com.mrndtvndv.term.data.prefs.LastSessionStore
import com.mrndtvndv.term.clipboard.ClipboardImageHandler
import com.mrndtvndv.term.clipboard.FileUploadService
import com.mrndtvndv.term.server.AppSessionManager
import com.mrndtvndv.term.server.SessionHost
import com.mrndtvndv.term.ui.notification.NotificationState
import com.mrndtvndv.term.ui.prefs.UserPrefs
import com.mrndtvndv.term.ui.MainContent
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import com.termux.shared.interact.ShareUtils
import com.termux.terminal.TerminalSession
import androidx.core.content.FileProvider
import android.widget.Toast
import java.io.File

@Suppress("TooManyFunctions")
class MainActivity : ComponentActivity(), SessionHost {

    private val sharedPreferences by lazy {
        getSharedPreferences("ssh_prefs", Context.MODE_PRIVATE)
    }

    private val sessionManager: AppSessionManager by lazy {
        AppSessionManager.current ?: error("AppSessionManager must be initialized in TermApplication")
    }
    private val lastSessionStore by lazy { LastSessionStore(sharedPreferences) }
    private val userPrefs by lazy { UserPrefs() }
    private val notificationState by lazy { NotificationState() }
    private val fileUploadService by lazy { FileUploadService(applicationContext) }

    private val viewModel: MainViewModel by viewModels {
        object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST") // safe: VM factory creates only MainViewModel, cast is correct
            override fun <T : ViewModel> create(modelClass: Class<T>): T =
                MainViewModel(sessionManager, userPrefs, notificationState, lastSessionStore) as T
        }
    }

    private val viewingFileState = mutableStateOf<File?>(null)
    private val uploadInProgressState = mutableStateOf(false)
    private var uploadJob: Job? = null
    private var fileUploadTargetSession: TerminalSession? = null
    private val filePickerLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        handlePickedFile(uri)
    }
    private var windowHasFocus = false
    private var focusedTerminalSession: TerminalSession? = null

    // ── SessionHost: live UI half of AppSessionManager ───────────────

    override fun onFrameAvailable(session: TerminalSession) {
        // Backend now observes TerminalSession.FrameCallback directly; no host routing required.
    }

    override fun copyToClipboard(text: String) {
        ShareUtils.copyTextToClipboard(this, text)
    }

    private class ActiveSessionContext(
        val session: TerminalSession?,
        val server: Server?,
        val config: ServerConfig?,
    )

    private data class ImagePasteRequest(
        val config: ServerConfig,
    )

    private fun resolveActiveSessionContext(session: TerminalSession?): ActiveSessionContext {
        val activeSession = session ?: focusedTerminalSession
        val serverId = activeSession?.let { sessionManager.serverIdForSession(it) }
        val server = serverId?.let { viewModel.getServer(it) }
        val config = server?.config ?: serverId?.let { viewModel.getServerConfig(it) }
        return ActiveSessionContext(activeSession, server, config)
    }

    override fun handlePaste(session: TerminalSession?) {
        val sessionContext = resolveActiveSessionContext(session)
        val targetSession = sessionContext.session ?: return
        val clipboardManager = getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
        val clipData = clipboardManager?.primaryClip

        val request = resolveImagePasteRequest(sessionContext.config, clipData)
        if (request == null) {
            pasteClipboardText(targetSession)
            return
        }
        val imageClipData = clipData ?: return

        startClipboardImagePaste(
            targetSession = targetSession,
            clipData = imageClipData,
            config = request.config,
            server = sessionContext.server,
            pasteClipboardTextOnFailure = true,
        )
    }

    private fun requestMediaUpload(session: TerminalSession) {
        requestFilePicker(session, arrayOf("image/*", "video/*"))
    }

    private fun requestFileUpload(session: TerminalSession) {
        requestFilePicker(session, arrayOf("*/*"))
    }

    private fun requestFilePicker(session: TerminalSession, mimeTypes: Array<String>) {
        if (uploadInProgressState.value) return
        val sessionContext = resolveActiveSessionContext(session)
        if (!canUploadFile(sessionContext.config, sessionContext.server)) {
            showUploadConfigurationError()
            return
        }
        fileUploadTargetSession = session
        filePickerLauncher.launch(mimeTypes)
    }

    private fun handlePickedFile(uri: Uri?) {
        val targetSession = fileUploadTargetSession
        fileUploadTargetSession = null
        if (uri == null || targetSession == null) return

        val sessionContext = resolveActiveSessionContext(targetSession)
        val config = sessionContext.config ?: run {
            showUploadConfigurationError()
            return
        }
        if (!canUploadFile(config, sessionContext.server)) {
            showUploadConfigurationError()
            return
        }
        startFileUpload(
            targetSession = targetSession,
            uri = uri,
            fileName = getFileName(this, uri),
            config = config,
            server = sessionContext.server,
        )
    }

    private fun canUploadFile(config: ServerConfig?, server: Server?): Boolean =
        config?.isImagePasteActive == true && (config.isLocal || server != null)

    private fun startClipboardImagePaste(
        targetSession: TerminalSession,
        clipData: ClipData,
        config: ServerConfig,
        server: Server?,
        pasteClipboardTextOnFailure: Boolean,
    ) {
        if (uploadInProgressState.value) return
        uploadInProgressState.value = true
        uploadJob = lifecycleScope.launch {
            try {
                val path = fileUploadService.saveImage(
                    clipData = clipData,
                    config = config,
                    server = server,
                )
                if (path == null && !pasteClipboardTextOnFailure) {
                    Toast.makeText(this@MainActivity, "Image paste upload failed", Toast.LENGTH_SHORT).show()
                }
                val textToPaste = path ?: if (pasteClipboardTextOnFailure) {
                    ShareUtils.getTextStringFromClipboardIfSet(this@MainActivity, true)
                } else {
                    null
                }
                textToPaste?.let(targetSession::paste)
            } finally {
                uploadInProgressState.value = false
                uploadJob = null
            }
        }
    }

    private fun startFileUpload(
        targetSession: TerminalSession,
        uri: Uri,
        fileName: String?,
        config: ServerConfig,
        server: Server?,
    ) {
        if (uploadInProgressState.value) return
        uploadInProgressState.value = true
        uploadJob = lifecycleScope.launch {
            try {
                val path = fileUploadService.uploadFile(
                    uri = uri,
                    fileName = fileName,
                    config = config,
                    server = server,
                )
                if (path == null) {
                    Toast.makeText(this@MainActivity, "File upload failed", Toast.LENGTH_SHORT).show()
                } else {
                    targetSession.paste(path)
                }
            } finally {
                uploadInProgressState.value = false
                uploadJob = null
            }
        }
    }

    private fun cancelUpload() {
        if (uploadJob?.isActive != true) return
        uploadJob?.cancel()
        Toast.makeText(this, "File upload cancelled", Toast.LENGTH_SHORT).show()
    }

    private fun showUploadConfigurationError() {
        Toast.makeText(
            this,
            "Enable uploads and set an upload directory first",
            Toast.LENGTH_SHORT,
        ).show()
    }

    @Suppress("ReturnCount")
    private fun resolveImagePasteRequest(
        config: ServerConfig?,
        clipData: ClipData?,
    ): ImagePasteRequest? {
        val imageConfig = config ?: return null
        if (!imageConfig.imagePasteEnabled) return null
        if (imageConfig.imagePasteDirectory?.trim().isNullOrEmpty()) return null
        if (!ClipboardImageHandler.isImageClip(this, clipData)) return null
        return ImagePasteRequest(imageConfig)
    }

    private fun pasteClipboardText(session: TerminalSession) {
        ShareUtils.getTextStringFromClipboardIfSet(this, true)?.let(session::paste)
    }

    override fun isAtLeast(state: Lifecycle.State): Boolean =
        lifecycle.currentState.isAtLeast(state)

    override fun showInAppNotification(title: String?, body: String?, serverId: String?) {
        notificationState.post(title, body, serverId)
    }

    // ── Activity lifecycle ───────────────────────────────────────────

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        windowHasFocus = hasFocus
        focusedTerminalSession?.sendTerminalFocus(hasFocus)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        sessionManager.setHost(this)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) !=
                android.content.pm.PackageManager.PERMISSION_GRANTED
            ) {
                requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 101)
            }
        }

        if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.P &&
            checkSelfPermission(android.Manifest.permission.WRITE_EXTERNAL_STORAGE) !=
            android.content.pm.PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(android.Manifest.permission.WRITE_EXTERNAL_STORAGE), 102)
        }

        userPrefs.init(sharedPreferences)

        setContent {
            val uiState by viewModel.uiState

            val customFontName by userPrefs.customFontName.collectAsState()
            val useCustomFontForWholeUi by userPrefs.useCustomFontForWholeUi.collectAsState()

            val customFontFamily = remember(customFontName, useCustomFontForWholeUi) {
                if (useCustomFontForWholeUi && customFontName != null) {
                    val file = File(filesDir, "font.ttf")
                    if (file.exists() && file.length() > 0) {
                        try {
                            androidx.compose.ui.text.font.FontFamily(
                                androidx.compose.ui.text.font.Typeface(
                                    android.graphics.Typeface.createFromFile(file)
                                )
                            )
                        } catch (e: Exception) {
                            null
                        }
                    } else null
                } else null
            }

            MainContent(
                viewModel = viewModel,
                sharedPreferences = sharedPreferences,
                customFontFamily = customFontFamily,
                onBackendCreated = { _, _ -> },
                onBackendReleased = { _, _ -> },
                onActiveTerminalSessionChanged = { session ->
                    updateFocusedTerminalSession(session)
                },
                uploadInProgress = uploadInProgressState.value,
                onCancelUpload = { cancelUpload() },
                onRequestMediaUpload = { session -> requestMediaUpload(session) },
                onRequestFileUpload = { session -> requestFileUpload(session) },
                onOpenFile = { file -> openDownloadedFile(file) },
                onOpenFileError = { errorMsg ->
                    sessionManager.handleTerminalNotification("SFTP Error", errorMsg)
                },
                onOpenUrl = { url ->
                    ShareUtils.openUrl(this@MainActivity, url)
                },
                onRefreshWorkspace = { serverId ->
                    viewModel.refreshWorkspace(serverId)
                },
                viewingFile = viewingFileState.value,
                onCloseFile = { viewingFileState.value = null },
                getFileName = { uri -> getFileName(this, uri) },
                copyFontFile = { uri ->
                    try {
                        contentResolver.openInputStream(uri)?.use { input ->
                            File(filesDir, "font.ttf").outputStream().use { output ->
                                input.copyTo(output)
                            }
                        }
                        val name = getFileName(this, uri) ?: "custom_font.ttf"
                        userPrefs.setCustomFontName(name, sharedPreferences)
                    } catch (e: Exception) {
                        android.util.Log.e("MainActivity", "Failed to copy font", e)
                    }
                },
                deleteFontFile = {
                    File(filesDir, "font.ttf").delete()
                    userPrefs.setCustomFontName(null, sharedPreferences)
                },
                fontFileExists = { File(filesDir, "font.ttf").exists() },
            )
        }

        handleNotificationTap(intent)
    }

    override fun onDestroy() {
        updateFocusedTerminalSession(null)
        sessionManager.setHost(null)
        super.onDestroy()
    }

    private fun updateFocusedTerminalSession(session: TerminalSession?) {
        if (focusedTerminalSession === session) {
            return
        }

        focusedTerminalSession?.sendTerminalFocus(false)
        focusedTerminalSession = session
        session?.sendTerminalFocus(windowHasFocus)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleNotificationTap(intent)
    }

    private fun handleNotificationTap(intent: Intent?) {
        val serverId = intent?.getStringExtra(AppSessionManager.EXTRA_NOTIFICATION_SERVER_ID) ?: return
        intent.removeExtra(AppSessionManager.EXTRA_NOTIFICATION_SERVER_ID)
        val body = intent.getStringExtra(AppSessionManager.EXTRA_NOTIFICATION_BODY)
        viewModel.focusTerminalNotification(serverId, body)
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.keyCode == KeyEvent.KEYCODE_BACK && event.action == KeyEvent.ACTION_DOWN) {
            // Only intercept when on the terminal workspace screen
            val currentScreen = viewModel.uiState.value.screen
            if (currentScreen is ScreenState.TerminalWorkspace) {
                // Hide keyboard BEFORE the IME can consume the event via onKeyPreIme.
                // This way the back event propagates through to OnBackPressedDispatcher
                // and navigator.goBack() fires on the SAME press.
                val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
                imm?.hideSoftInputFromWindow(currentFocus?.windowToken, 0)
            }
            // On API 33+ the gesture back path uses OnBackInvokedDispatcher
            // (handled by BackPressInterceptor). The hardware back button still
            // goes through dispatchKeyEvent on all API levels.
        }
        return super.dispatchKeyEvent(event)
    }

    private fun openDownloadedFile(file: File) {
        try {
            val extension = file.extension.lowercase()
            val mimeType = MimeTypeMap.getSingleton().getMimeTypeFromExtension(extension)
            val sourceCodeExts = listOf(
                "kt", "java", "py", "js", "md", "rs", "zig", "c", "cpp",
                "h", "hpp", "sh", "txt", "xml", "json", "yml", "yaml", "gradle", "kts", "go",
            )

            if (mimeType?.startsWith("text/") == true || sourceCodeExts.contains(extension)) {
                viewingFileState.value = file
                return
            }

            val authority = "${packageName}.fileprovider"
            val uri = FileProvider.getUriForFile(this, authority, file)

            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, mimeType ?: "*/*")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }

            val chooser = Intent.createChooser(intent, "Open file with...")
            startActivity(chooser)
        } catch (e: Exception) {
            android.util.Log.e("MainActivity", "Failed to open file", e)
            sessionManager.handleTerminalNotification("Error", "Failed to open file: ${e.localizedMessage}")
        }
    }

    private fun getFileName(context: Context, uri: Uri): String? {
        var result: String? = null
        if (uri.scheme == "content") {
            val cursor = context.contentResolver.query(uri, null, null, null, null)
            try {
                if (cursor != null && cursor.moveToFirst()) {
                    val index = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                    if (index != -1) {
                        result = cursor.getString(index)
                    }
                }
            } catch (_: Exception) { } finally {
                cursor?.close()
            }
        }
        if (result == null) {
            result = uri.path
            val cut = result?.lastIndexOf('/') ?: -1
            if (cut != -1) {
                result = result?.substring(cut + 1)
            }
        }
        return result
    }
}
