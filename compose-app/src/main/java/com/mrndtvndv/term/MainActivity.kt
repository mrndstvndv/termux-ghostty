package com.mrndtvndv.term

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.KeyEvent
import android.view.inputmethod.InputMethodManager
import android.webkit.MimeTypeMap
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.runtime.*
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import com.mrndtvndv.term.data.prefs.LastSessionStore
import com.mrndtvndv.term.server.AppSessionManager
import com.mrndtvndv.term.server.SessionHost
import com.mrndtvndv.term.ui.notification.NotificationState
import com.mrndtvndv.term.ui.prefs.UserPrefs
import com.mrndtvndv.term.ui.MainContent
import com.termux.shared.interact.ShareUtils
import com.termux.view.TerminalView
import org.bouncycastle.jce.provider.BouncyCastleProvider
import org.conscrypt.Conscrypt
import androidx.core.content.FileProvider
import java.io.File
import java.security.Security

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

    private val viewModel: MainViewModel by viewModels {
        object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST") // safe: VM factory creates only MainViewModel, cast is correct
            override fun <T : ViewModel> create(modelClass: Class<T>): T =
                MainViewModel(sessionManager, userPrefs, notificationState, lastSessionStore) as T
        }
    }

    private var activeTerminalView: TerminalView? = null

    private val viewingFileState = mutableStateOf<File?>(null)

    // ── SessionHost: live UI half of AppSessionManager ───────────────

    override fun onFrameAvailable() {
        activeTerminalView?.onFrameAvailable()
    }

    override fun copyToClipboard(text: String) {
        ShareUtils.copyTextToClipboard(this, text)
    }

    override fun pasteFromClipboard(): String? =
        ShareUtils.getTextStringFromClipboardIfSet(this, true)

    override fun isAtLeast(state: Lifecycle.State): Boolean =
        lifecycle.currentState.isAtLeast(state)

    override fun showInAppNotification(title: String?, body: String?) {
        notificationState.post(title, body)
    }

    // ── Activity lifecycle ───────────────────────────────────────────

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
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

        Security.removeProvider("BC")
        Security.insertProviderAt(BouncyCastleProvider(), 1)
        Security.insertProviderAt(Conscrypt.newProvider(), 2)

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
                onViewCreated = { view ->
                    activeTerminalView = view
                    registerForContextMenu(view)
                },
                onViewReleased = { view ->
                    activeTerminalView?.let { unregisterForContextMenu(it) }
                    if (activeTerminalView === view) {
                        activeTerminalView = null
                    }
                },
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
    }

    override fun onDestroy() {
        sessionManager.setHost(null)
        super.onDestroy()
    }

    override fun onCreateContextMenu(
        menu: android.view.ContextMenu,
        v: android.view.View,
        menuInfo: android.view.ContextMenu.ContextMenuInfo?,
    ) {
        super.onCreateContextMenu(menu, v, menuInfo)
        val view = activeTerminalView ?: return
        if (v === view) {
            menu.add(android.view.Menu.NONE, 1, android.view.Menu.NONE, "Share selected text").apply {
                isEnabled = !view.storedSelectedText.isNullOrEmpty()
            }
            menu.add(android.view.Menu.NONE, 2, android.view.Menu.NONE, "Share transcript")
        }
    }

    override fun onContextItemSelected(item: android.view.MenuItem): Boolean {
        val view = activeTerminalView
        val serverId = (viewModel.uiState.value.screen as? ScreenState.TerminalWorkspace)?.serverId
        val server = serverId?.let { viewModel.getServer(it) }

        if (view == null || server == null) {
            return super.onContextItemSelected(item)
        }

        val currentSession = server.terminalSession
        return when (item.itemId) {
            1 -> {
                val selectedText = view.storedSelectedText
                if (!selectedText.isNullOrEmpty()) {
                    ShareUtils.shareText(this, "Terminal selection", selectedText)
                    view.unsetStoredSelectedText()
                }
                true
            }
            2 -> {
                val transcript = currentSession.getTerminalContent()
                    ?.getTranscriptText(true, true) ?: ""
                ShareUtils.shareText(this, "Terminal transcript", transcript)
                true
            }
            else -> super.onContextItemSelected(item)
        }
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
