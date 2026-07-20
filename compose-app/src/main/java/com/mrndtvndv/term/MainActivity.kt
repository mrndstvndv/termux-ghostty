package com.mrndtvndv.term

import android.app.NotificationManager
import android.app.PendingIntent
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.webkit.MimeTypeMap
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.runtime.*
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import com.mrndtvndv.term.data.prefs.SharedPreferencesWorkspacePersistence
import com.mrndtvndv.term.server.ServerCoordinator
import com.mrndtvndv.term.server.ServerFactory
import com.mrndtvndv.term.server.ServerManager
import com.mrndtvndv.term.server.ServerRepository
import com.mrndtvndv.term.ui.notification.NotificationState
import com.mrndtvndv.term.ui.prefs.UserPrefs
import com.mrndtvndv.term.service.SshSessionService
import com.mrndtvndv.term.ui.MainContent
import com.termux.shared.interact.ShareUtils
import com.termux.shared.termux.terminal.TermuxTerminalSessionClientBase
import com.termux.terminal.TerminalSession
import com.termux.view.TerminalView
import org.bouncycastle.jce.provider.BouncyCastleProvider
import org.conscrypt.Conscrypt
import androidx.core.content.FileProvider
import java.io.File
import java.security.Security

class MainActivity : ComponentActivity() {

    private val sharedPreferences by lazy {
        getSharedPreferences("ssh_prefs", Context.MODE_PRIVATE)
    }

    private val serverRepository by lazy { ServerRepository(sharedPreferences) }
    private val persistence by lazy { SharedPreferencesWorkspacePersistence(sharedPreferences) }
    private val serverFactory by lazy {
        ServerFactory(
            context = this@MainActivity,
            persistence = persistence,
            onSessionClientCreated = { createSessionClient() },
            onServiceBind = { termSession -> bindTerminalSession(termSession) },
            onSessionFinished = { serverId -> viewModel.disconnect(serverId) },
        )
    }
    private val serverManager by lazy { ServerManager(serverFactory) }
    private val coordinator by lazy { ServerCoordinator(serverManager, serverRepository) }
    private val userPrefs by lazy { UserPrefs() }
    private val notificationState by lazy { NotificationState() }

    private val viewModel: MainViewModel by viewModels {
        object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST") // safe: VM factory creates only MainViewModel, cast is correct
            override fun <T : ViewModel> create(modelClass: Class<T>): T =
                MainViewModel(serverRepository, coordinator, userPrefs, notificationState) as T
        }
    }

    private var activeTerminalView: TerminalView? = null
    private var sshService: SshSessionService? = null
    private var isBound = false

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(className: ComponentName, service: IBinder) {
            sshService = (service as SshSessionService.LocalBinder).getService()
            isBound = true
        }

        override fun onServiceDisconnected(arg0: ComponentName) {
            isBound = false
            sshService = null
        }
    }

    private val viewingFileState = mutableStateOf<File?>(null)
    private var nextNotificationId =
        com.termux.shared.termux.TermuxConstants.TERMUX_TERMINAL_PROTOCOL_NOTIFICATION_ID_BASE

    @Synchronized
    private fun getNextTerminalProtocolNotificationId(): Int {
        val id = nextNotificationId
        nextNotificationId++
        return id
    }

    private fun handleNotification(title: String?, body: String?) {
        val isForeground = lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.RESUMED)
        if (isForeground) {
            notificationState.post(title, body)
        } else {
            showSystemNotification(title, body)
        }
    }

    private fun showSystemNotification(title: String?, body: String?) {
        val notificationManager =
            com.termux.shared.notification.NotificationUtils.getNotificationManager(this) ?: return

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            com.termux.shared.notification.NotificationUtils.setupNotificationChannel(
                this,
                com.termux.shared.termux.TermuxConstants.TERMUX_TERMINAL_PROTOCOL_NOTIFICATIONS_NOTIFICATION_CHANNEL_ID,
                com.termux.shared.termux.TermuxConstants.TERMUX_TERMINAL_PROTOCOL_NOTIFICATIONS_NOTIFICATION_CHANNEL_NAME,
                NotificationManager.IMPORTANCE_DEFAULT,
            )
        }

        val normalizedTitle = title ?: "Terminal Notification"
        val normalizedBody = body ?: ""

        val intent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val contentIntent = PendingIntent.getActivity(
            this,
            0,
            intent,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M)
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            else PendingIntent.FLAG_UPDATE_CURRENT,
        )

        val builder = com.termux.shared.notification.NotificationUtils.geNotificationBuilder(
            this,
            com.termux.shared.termux.TermuxConstants.TERMUX_TERMINAL_PROTOCOL_NOTIFICATIONS_NOTIFICATION_CHANNEL_ID,
            0, // Notification.PRIORITY_DEFAULT (deprecated in Java, inlined)
            normalizedTitle,
            normalizedBody,
            normalizedBody,
            contentIntent,
            null,
            com.termux.shared.notification.NotificationUtils.NOTIFICATION_MODE_ALL,
        ) ?: return

        builder.setSmallIcon(android.R.drawable.ic_dialog_info)
        builder.setAutoCancel(true)

        notificationManager.notify(
            getNextTerminalProtocolNotificationId(),
            builder.build(),
        )
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

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
                    handleNotification("SFTP Error", errorMsg)
                },
                onOpenUrl = { url ->
                    if (sharedPreferences.getBoolean("use_in_app_browser", false)) {
                        viewModel.setBrowserUrl(url)
                    } else {
                        ShareUtils.openUrl(this@MainActivity, url)
                    }
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

    private fun createSessionClient(): TermuxTerminalSessionClientBase {
        return object : TermuxTerminalSessionClientBase() {
            override fun onFrameAvailable(changedSession: TerminalSession) {
                if (!lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.STARTED)) return
                activeTerminalView?.onFrameAvailable()
            }

            override fun onCopyTextToClipboard(session: TerminalSession, text: String) {
                ShareUtils.copyTextToClipboard(this@MainActivity, text)
            }

            override fun onPasteTextFromClipboard(session: TerminalSession?) {
                val text = ShareUtils.getTextStringFromClipboardIfSet(this@MainActivity, true)
                if (text != null) session?.paste(text)
            }

            override fun onTerminalProtocolNotification(
                session: TerminalSession,
                title: String?,
                body: String?,
            ) {
                handleNotification(title, body)
            }
        }
    }

    @Suppress("UNUSED_PARAMETER") // kept for future service integration; binding is handled internally
    private fun bindTerminalSession(termSession: TerminalSession) {
        val intent = Intent(this@MainActivity, SshSessionService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }
        bindService(intent, connection, Context.BIND_AUTO_CREATE)
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

    override fun onDestroy() {
        if (isBound) {
            try { unbindService(connection) } catch (_: Exception) { }
            isBound = false
            sshService = null
        }
        super.onDestroy()
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
            handleNotification("Error", "Failed to open file: ${e.localizedMessage}")
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
