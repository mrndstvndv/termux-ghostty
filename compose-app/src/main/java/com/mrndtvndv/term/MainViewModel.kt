package com.mrndtvndv.term

import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.mrndtvndv.term.domain.ServerConfig
import com.mrndtvndv.term.server.Server
import com.mrndtvndv.term.server.ServerManager
import com.mrndtvndv.term.server.ServerRepository
import com.mrndtvndv.term.server.WorkspaceState
import com.mrndtvndv.term.server.WorkspaceTracker
import com.mrndtvndv.term.ui.review.ReviewViewModel
import com.mrndtvndv.term.ui.sftp.SftpViewModel
import com.mrndtvndv.term.ui.workspace.WorkspaceTab
import kotlinx.coroutines.launch

data class ActiveNotification(
    val title: String,
    val body: String,
    val timestamp: Long = System.currentTimeMillis(),
)

data class MainUiState(
    val screen: ScreenState = ScreenState.ServerList,
    val isLoading: Boolean = false,
    val error: String? = null,
    val activeTab: WorkspaceTab = WorkspaceTab.Terminal,
    val browserUrl: String = "",
    val notification: ActiveNotification? = null,
    val customFontName: String? = null,
    val useCustomFontForWholeUi: Boolean = false,
)

sealed interface ScreenState {
    data object ServerList : ScreenState
    data class TerminalWorkspace(val serverId: String) : ScreenState
}

@Suppress("TooManyFunctions")
class MainViewModel(
    private val serverRepository: ServerRepository,
    private val serverManager: ServerManager,
) : ViewModel() {

    private val _savedServers = mutableStateOf<List<ServerConfig>>(emptyList())
    val savedServers: State<List<ServerConfig>> = _savedServers

    private val _uiState = mutableStateOf(MainUiState())
    val uiState: State<MainUiState> = _uiState

    private val sftpViewModels = mutableMapOf<String, SftpViewModel>()
    private val reviewViewModels = mutableMapOf<String, ReviewViewModel?>()

    init {
        reloadServers()
    }

    // ── Server management ─────────────────────────────────────────────

    fun reloadServers() {
        _savedServers.value = serverRepository.loadAll()
    }

    fun saveServer(config: ServerConfig) {
        serverRepository.add(config)
        reloadServers()
    }

    fun deleteServer(id: String) {
        serverManager.disconnect(id)
        sftpViewModels.remove(id)
        reviewViewModels.remove(id)
        serverRepository.remove(id)
        reloadServers()
    }

    fun getServer(serverId: String): Server? = serverManager.get(serverId)

    fun getSftpViewModel(serverId: String): SftpViewModel? = sftpViewModels[serverId]
    fun getReviewViewModel(serverId: String): ReviewViewModel? = reviewViewModels[serverId]

    // ── Connection ────────────────────────────────────────────────────

    fun connect(id: String) {
        val config = serverRepository.get(id) ?: return
        _uiState.value = _uiState.value.copy(isLoading = true, error = null)

        viewModelScope.launch {
            try {
                val server = serverManager.connect(config)
                sftpViewModels[id] = createSftpViewModel(server)
                reviewViewModels[id] = createReviewViewModel(server)

                val browserUrl = when (val ws = server.workspaceState) {
                    is WorkspaceState.Tracked -> ws.tracker.browserUrl
                    is WorkspaceState.Untracked -> ""
                }

                _uiState.value = _uiState.value.copy(
                    isLoading = false,
                    browserUrl = browserUrl,
                    screen = ScreenState.TerminalWorkspace(id),
                )
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(
                    isLoading = false,
                    error = e.message,
                )
            }
        }
    }

    fun navigateBack() {
        // Connection stays alive in ServerManager — just pop the workspace
        _uiState.value = _uiState.value.copy(screen = ScreenState.ServerList)
    }

    fun disconnect(id: String) {
        serverManager.disconnect(id)
        sftpViewModels.remove(id)
        reviewViewModels.remove(id)
        _uiState.value = _uiState.value.copy(screen = ScreenState.ServerList)
    }

    // ── Workspace tracking ────────────────────────────────────────────

    fun refreshWorkspace(serverId: String) {
        val server = serverManager.get(serverId) ?: return
        val tracker = (server.workspaceState as? WorkspaceState.Tracked)?.tracker ?: return
        viewModelScope.launch {
            val result = tracker.sync()
            if (result is WorkspaceTracker.SyncResult.WorkspaceChanged) {
                _uiState.value = _uiState.value.copy(browserUrl = result.browserUrl)
                sftpViewModels[serverId]?.navigateTo(result.workspaceDir)
            }
        }
    }

    fun onDirectoryChanged(serverId: String, path: String) {
        val server = serverManager.get(serverId) ?: return
        (server.workspaceState as? WorkspaceState.Tracked)?.tracker?.onDirectoryChanged(path)
    }

    fun onBrowserUrlChanged(serverId: String, url: String) {
        _uiState.value = _uiState.value.copy(browserUrl = url)
        val server = serverManager.get(serverId) ?: return
        (server.workspaceState as? WorkspaceState.Tracked)?.tracker?.onBrowserUrlChanged(url)
    }

    // ── Tab & URL ─────────────────────────────────────────────────────

    fun setTab(tab: WorkspaceTab) {
        _uiState.value = _uiState.value.copy(activeTab = tab)
    }

    fun setBrowserUrl(url: String) {
        _uiState.value = _uiState.value.copy(browserUrl = url)
    }

    // ── Notifications ─────────────────────────────────────────────────

    fun postNotification(title: String?, body: String?) {
        _uiState.value = _uiState.value.copy(
            notification = ActiveNotification(
                title = title ?: "Terminal Notification",
                body = body ?: "",
            )
        )
    }

    fun dismissNotification() {
        _uiState.value = _uiState.value.copy(notification = null)
    }

    // ── Font ──────────────────────────────────────────────────────────

    fun setCustomFontName(name: String?) {
        _uiState.value = _uiState.value.copy(customFontName = name)
    }

    fun setUseCustomFontForWholeUi(enabled: Boolean) {
        _uiState.value = _uiState.value.copy(useCustomFontForWholeUi = enabled)
    }

    fun initPrefs(prefs: android.content.SharedPreferences) {
        _uiState.value = _uiState.value.copy(
            customFontName = prefs.getString("custom_font_name", null),
            useCustomFontForWholeUi = prefs.getBoolean("use_custom_font_for_whole_ui", false),
        )
    }

    // ── Lifecycle ─────────────────────────────────────────────────────

    override fun onCleared() {
        serverManager.disconnectAll()
        super.onCleared()
    }

    // ── Internal helpers ──────────────────────────────────────────────

    private suspend fun createSftpViewModel(server: Server): SftpViewModel {
        val initialDir = when (val ws = server.workspaceState) {
            is WorkspaceState.Tracked -> ws.tracker.workspaceDir.value
            is WorkspaceState.Untracked -> "/"
        }
        val sftpVM = SftpViewModel(
            client = server.sftpClient,
            savedStateHandle = SavedStateHandle(),
            initialPath = initialDir,
            execCommand = { cmd -> server.sshSession.execCommand(cmd) },
        )
        sftpVM.onPathChanged = { path ->
            when (val ws = server.workspaceState) {
                is WorkspaceState.Tracked -> ws.tracker.onDirectoryChanged(path)
                is WorkspaceState.Untracked -> { /* no persistence needed */ }
            }
        }
        return sftpVM
    }

    private suspend fun createReviewViewModel(server: Server): ReviewViewModel? {
        val tracker = (server.workspaceState as? WorkspaceState.Tracked)?.tracker ?: return null
        return ReviewViewModel(
            execCommand = { cmd -> server.sshSession.execCommand(cmd) },
            workspaceDir = tracker.workspaceDir,
        )
    }
}
