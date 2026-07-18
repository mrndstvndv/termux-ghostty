package com.mrndtvndv.term

import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.mrndtvndv.term.data.ssh.native.NativeSshSession
import com.mrndtvndv.term.domain.SftpClient
import com.mrndtvndv.term.domain.SshAuth
import com.mrndtvndv.term.domain.SshConfig
import com.mrndtvndv.term.domain.SshSession
import com.mrndtvndv.term.domain.SshShellChannel
import com.mrndtvndv.term.ui.review.ReviewViewModel
import com.mrndtvndv.term.ui.sftp.SftpViewModel
import com.mrndtvndv.term.ui.workspace.WorkspaceTab
import com.termux.terminal.TerminalSession
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File

data class ActiveNotification(
    val title: String,
    val body: String,
    val timestamp: Long = System.currentTimeMillis()
)

sealed interface ScreenState {
    object Dashboard : ScreenState
    data class TerminalWorkspace(
        val session: TerminalSession,
        val sftp: SftpViewModel? = null,
        val review: ReviewViewModel? = null
    ) : ScreenState
}

data class MainUiState(
    val screen: ScreenState = ScreenState.Dashboard,
    val isLoading: Boolean = false,
    val error: String? = null,
    val activeTab: WorkspaceTab = WorkspaceTab.Terminal,
    val browserUrl: String = "",
    val notification: ActiveNotification? = null,
    val customFontName: String? = null,
    val useCustomFontForWholeUi: Boolean = false
)

class MainViewModel : ViewModel() {

    var sshSession: SshSession? = null
    var shellChannel: SshShellChannel? = null
    var sftpClient: SftpClient? = null

    private var currentHost: String? = null
    private var currentUsername: String? = null
    private var currentPort: Int = 22

    private val _uiState = mutableStateOf(MainUiState())
    val uiState: State<MainUiState> = _uiState

    val workspaceDirState = MutableStateFlow("/")
    var activeWorkspaceKey: String? = null

    fun setScreen(screen: ScreenState) {
        _uiState.value = _uiState.value.copy(screen = screen)
    }

    fun setTab(tab: WorkspaceTab) {
        _uiState.value = _uiState.value.copy(activeTab = tab)
    }

    fun setBrowserUrl(url: String) {
        _uiState.value = _uiState.value.copy(browserUrl = url)
    }

    fun setCustomFontName(name: String?) {
        _uiState.value = _uiState.value.copy(customFontName = name)
    }

    fun setUseCustomFontForWholeUi(enabled: Boolean) {
        _uiState.value = _uiState.value.copy(useCustomFontForWholeUi = enabled)
    }

    fun initPrefs(sharedPreferences: android.content.SharedPreferences) {
        _uiState.value = _uiState.value.copy(
            customFontName = sharedPreferences.getString("custom_font_name", null),
            useCustomFontForWholeUi = sharedPreferences.getBoolean("use_custom_font_for_whole_ui", false)
        )
    }

    fun dismissNotification() {
        _uiState.value = _uiState.value.copy(notification = null)
    }

    fun postNotification(title: String?, body: String?) {
        _uiState.value = _uiState.value.copy(
            notification = ActiveNotification(
                title = title ?: "Terminal Notification",
                body = body ?: ""
            )
        )
    }

    fun cleanupConnection() {
        try {
            shellChannel?.close()
        } catch (e: Exception) {}
        shellChannel = null
        try {
            sftpClient?.close()
        } catch (e: Exception) {}
        sftpClient = null
        
        _uiState.value = _uiState.value.copy(
            screen = ScreenState.Dashboard,
            activeTab = WorkspaceTab.Terminal,
            isLoading = false,
            error = null
        )
        
        activeWorkspaceKey = null
        try {
            sshSession?.disconnect()
        } catch (e: Exception) {}
        sshSession = null
    }

    fun updateTerminalSession(session: TerminalSession?) {
        val currentScreen = _uiState.value.screen
        if (session != null) {
            if (currentScreen is ScreenState.TerminalWorkspace) {
                _uiState.value = _uiState.value.copy(screen = currentScreen.copy(session = session))
            } else {
                _uiState.value = _uiState.value.copy(screen = ScreenState.TerminalWorkspace(session))
            }
        } else {
            _uiState.value = _uiState.value.copy(screen = ScreenState.Dashboard)
        }
    }

    fun connectSsh(
        host: String,
        port: Int,
        username: String,
        passwordString: String,
        herdrEnabled: Boolean,
        onSessionClientCreated: () -> com.termux.shared.termux.terminal.TermuxTerminalSessionClientBase,
        onSuccess: (String, Int, String, String) -> Unit,
        onServiceBind: (TerminalSession) -> Unit,
        getSavedDir: (String) -> String?,
        getSavedUrl: (String) -> String,
        onPathChanged: (String, String) -> Unit
    ) {
        this.currentHost = host
        this.currentPort = port
        this.currentUsername = username
        activeWorkspaceKey = null
        _uiState.value = _uiState.value.copy(isLoading = true, error = null)

        viewModelScope.launch {
            try {
                val session = NativeSshSession()
                sshSession = session

                withContext(Dispatchers.IO) {
                    session.connect(SshConfig(host, port, username))
                    session.authenticate(SshAuth.Password(passwordString.toCharArray()))
                }

                val termType = if (herdrEnabled) "xterm-ghostty" else "xterm-256color"
                val channel = session.openShellChannel(termType, 80, 24, herdrEnabled)
                shellChannel = channel

                val sessionClient = onSessionClientCreated()
                val sessionIo = object : com.termux.terminal.TerminalSessionIO {
                    override fun write(data: ByteArray?, offset: Int, count: Int) {
                        if (data != null && count > 0) {
                            try {
                                channel.outputStream.write(data, offset, count)
                            } catch (e: Exception) {}
                        }
                    }

                    override fun onResize(columns: Int, rows: Int, cellWidth: Int, cellHeight: Int) {
                        viewModelScope.launch(Dispatchers.IO) {
                            try {
                                channel.resizeWindow(columns, rows, columns * cellWidth, rows * cellHeight)
                            } catch (e: Exception) {}
                        }
                    }

                    override fun onClose() {
                        viewModelScope.launch(Dispatchers.Main) {
                            cleanupConnection()
                            setScreen(ScreenState.Dashboard)
                        }
                    }
                }

                val termSession = TerminalSession(2000, sessionClient, sessionIo)
                termSession.setSshSessionHandle(session.nativeSessionHandle)

                onServiceBind(termSession)
                onSuccess(host, port, username, passwordString)

                // Resolve workspace and configure browser/SFTP in background
                viewModelScope.launch(Dispatchers.IO) {
                    var workspaceName: String? = null
                    var resolvedCwd = "/"

                    if (herdrEnabled) {
                        try {
                            val output = session.execCommand("export PATH=\$PATH:/opt/homebrew/bin:/usr/local/bin; herdr workspace list; herdr pane list")
                            var focusedWsId: String? = null
                            var wsLabel: String? = null
                            val panes = mutableListOf<JSONObject>()

                            output.split("\n").forEach { line ->
                                val trimmed = line.trim()
                                if (trimmed.isNotEmpty()) {
                                    try {
                                        val json = JSONObject(trimmed)
                                        val id = json.optString("id")
                                        val result = json.optJSONObject("result")
                                        if (result != null) {
                                            if (id == "cli:workspace:list") {
                                                val wsArray = result.optJSONArray("workspaces")
                                                if (wsArray != null) {
                                                    for (i in 0 until wsArray.length()) {
                                                        val ws = wsArray.optJSONObject(i)
                                                        if (ws != null && ws.optBoolean("focused", false)) {
                                                            focusedWsId = ws.optString("workspace_id").takeIf { it.isNotEmpty() }
                                                            wsLabel = ws.optString("label").takeIf { it.isNotEmpty() }
                                                            break
                                                        }
                                                    }
                                                }
                                            } else if (id == "cli:pane:list") {
                                                val paneArray = result.optJSONArray("panes")
                                                if (paneArray != null) {
                                                    for (i in 0 until paneArray.length()) {
                                                        val pane = paneArray.optJSONObject(i)
                                                        if (pane != null) panes.add(pane)
                                                    }
                                                }
                                            }
                                        }
                                    } catch (je: Exception) {}
                                }
                            }

                            var paneCwd: String? = null
                            if (!focusedWsId.isNullOrEmpty()) {
                                for (pane in panes) {
                                    if (pane.optString("workspace_id") == focusedWsId && pane.optBoolean("focused", false)) {
                                        paneCwd = pane.optString("cwd").takeIf { it.isNotEmpty() }
                                        break
                                    }
                                }
                            }
                            if (!paneCwd.isNullOrEmpty()) resolvedCwd = paneCwd
                            if (!wsLabel.isNullOrEmpty()) workspaceName = wsLabel else if (!focusedWsId.isNullOrEmpty()) workspaceName = focusedWsId
                        } catch (e: Exception) {}
                    }

                    val key = if (!workspaceName.isNullOrEmpty()) {
                        "${workspaceName}_${host}_$username"
                    } else {
                        "$username@$host:$port"
                    }

                    activeWorkspaceKey = key
                    val savedUrl = getSavedUrl(key)

                    withContext(Dispatchers.Main) {
                        setBrowserUrl(savedUrl)
                        if (herdrEnabled) {
                            workspaceDirState.value = resolvedCwd
                            val reviewVM = ReviewViewModel(
                                execCommand = { cmd -> session.execCommand(cmd) },
                                workspaceDir = workspaceDirState
                            )
                            val currentScreen = _uiState.value.screen
                            if (currentScreen is ScreenState.TerminalWorkspace) {
                                _uiState.value = _uiState.value.copy(screen = currentScreen.copy(review = reviewVM))
                            }
                        }
                    }

                    try {
                        val sftp = session.openSftpClient()
                        sftpClient = sftp
                        val savedDir = getSavedDir(key)
                        val initialDir = if (!savedDir.isNullOrEmpty()) savedDir else resolvedCwd

                        withContext(Dispatchers.Main) {
                            if (herdrEnabled) workspaceDirState.value = initialDir
                            val sftpVM = SftpViewModel(
                                client = sftp,
                                savedStateHandle = SavedStateHandle(),
                                initialPath = initialDir,
                                execCommand = { cmd -> session.execCommand(cmd) }
                            )
                            sftpVM.onPathChanged = { path ->
                                activeWorkspaceKey?.let { k -> onPathChanged(k, path) }
                                workspaceDirState.value = path
                            }
                            val currentScreen = _uiState.value.screen
                            if (currentScreen is ScreenState.TerminalWorkspace) {
                                _uiState.value = _uiState.value.copy(screen = currentScreen.copy(sftp = sftpVM))
                            }
                        }
                    } catch (e: Exception) {
                        android.util.Log.e("MainViewModel", "Failed to initialize native SFTP", e)
                    }
                }

                _uiState.value = _uiState.value.copy(isLoading = false, screen = ScreenState.TerminalWorkspace(termSession))

            } catch (e: Exception) {
                cleanupConnection()
                _uiState.value = _uiState.value.copy(isLoading = false, error = e.localizedMessage ?: "Failed to connect")
            }
        }
    }

    fun refreshWorkspace(herdrEnabled: Boolean, getSavedDir: (String) -> String?, getSavedUrl: (String) -> String?) {
        if (!herdrEnabled) return
        val currentSession = sshSession ?: return
        val host = currentHost ?: return
        val username = currentUsername ?: return
        val port = currentPort

        viewModelScope.launch(Dispatchers.IO) {
            try {
                val output = currentSession.execCommand("export PATH=\$PATH:/opt/homebrew/bin:/usr/local/bin; herdr workspace list; herdr pane list")
                var focusedWsId: String? = null
                var wsLabel: String? = null
                val panes = mutableListOf<JSONObject>()

                output.split("\n").forEach { line ->
                    val trimmed = line.trim()
                    if (trimmed.isNotEmpty()) {
                        try {
                            val json = JSONObject(trimmed)
                            val id = json.optString("id")
                            val result = json.optJSONObject("result")
                            if (result != null) {
                                if (id == "cli:workspace:list") {
                                    val wsArray = result.optJSONArray("workspaces")
                                    if (wsArray != null) {
                                        for (i in 0 until wsArray.length()) {
                                            val ws = wsArray.optJSONObject(i)
                                            if (ws != null && ws.optBoolean("focused", false)) {
                                                focusedWsId = ws.optString("workspace_id").takeIf { it.isNotEmpty() }
                                                wsLabel = ws.optString("label").takeIf { it.isNotEmpty() }
                                                break
                                            }
                                        }
                                    }
                                } else if (id == "cli:pane:list") {
                                    val paneArray = result.optJSONArray("panes")
                                    if (paneArray != null) {
                                        for (i in 0 until paneArray.length()) {
                                            val pane = paneArray.optJSONObject(i)
                                            if (pane != null) panes.add(pane)
                                        }
                                    }
                                }
                            }
                        } catch (je: Exception) {}
                    }
                }

                val workspaceName = wsLabel ?: focusedWsId
                val newWorkspaceKey = if (!workspaceName.isNullOrEmpty()) {
                    "${workspaceName}_${host}_$username"
                } else {
                    "$username@$host:$port"
                }

                var paneCwd: String? = null
                if (!focusedWsId.isNullOrEmpty()) {
                    for (pane in panes) {
                        if (pane.optString("workspace_id") == focusedWsId && pane.optBoolean("focused", false)) {
                            paneCwd = pane.optString("cwd").takeIf { it.isNotEmpty() }
                            break
                        }
                    }
                }

                withContext(Dispatchers.Main) {
                    if (newWorkspaceKey != activeWorkspaceKey) {
                        activeWorkspaceKey = newWorkspaceKey
                        val savedDir = getSavedDir(newWorkspaceKey)
                        val finalCwd = if (!savedDir.isNullOrEmpty()) savedDir else (paneCwd ?: "/")
                        
                        workspaceDirState.value = finalCwd
                        val currentScreen = _uiState.value.screen
                        if (currentScreen is ScreenState.TerminalWorkspace) {
                            currentScreen.sftp?.navigateTo(finalCwd)
                        }
                        
                        val savedUrl = getSavedUrl(newWorkspaceKey) ?: ""
                        setBrowserUrl(savedUrl)
                    }
                }
            } catch (e: Exception) {}
        }
    }

    fun initSftpAndReview(
        session: SshSession,
        workspaceName: String?,
        resolvedCwd: String,
        key: String,
        isHerdrEnabled: Boolean,
        savedUrl: String,
        savedDir: String?,
        onPathChanged: (String) -> Unit
    ) {
        activeWorkspaceKey = key
        setBrowserUrl(savedUrl)
        
        var reviewVM: ReviewViewModel? = null
        if (isHerdrEnabled) {
            workspaceDirState.value = resolvedCwd
            reviewVM = ReviewViewModel(
                execCommand = { cmd -> session.execCommand(cmd) },
                workspaceDir = workspaceDirState
            )
        }

        viewModelScope.launch(Dispatchers.IO) {
            try {
                val sftp = session.openSftpClient()
                sftpClient = sftp

                val initialDir = if (!savedDir.isNullOrEmpty()) savedDir else resolvedCwd

                withContext(Dispatchers.Main) {
                    if (isHerdrEnabled) {
                        workspaceDirState.value = initialDir
                    }
                    val sftpVM = SftpViewModel(
                        client = sftp,
                        savedStateHandle = SavedStateHandle(),
                        initialPath = initialDir,
                        execCommand = { cmd -> session.execCommand(cmd) }
                    )
                    sftpVM.onPathChanged = { path ->
                        onPathChanged(path)
                        workspaceDirState.value = path
                    }
                    
                    val currentScreen = _uiState.value.screen
                    if (currentScreen is ScreenState.TerminalWorkspace) {
                        _uiState.value = _uiState.value.copy(
                            screen = currentScreen.copy(sftp = sftpVM, review = reviewVM)
                        )
                    }
                }
            } catch (e: Exception) {
                android.util.Log.e("MainViewModel", "Failed to initialize native SFTP", e)
            }
        }
    }
}
