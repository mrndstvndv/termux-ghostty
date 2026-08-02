package com.mrndtvndv.term

import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.mrndtvndv.term.data.prefs.LastSessionStore
import com.mrndtvndv.term.domain.ServerConfig
import com.mrndtvndv.term.server.AppSessionManager
import com.mrndtvndv.term.server.Server
import com.mrndtvndv.term.server.ServerCoordinator
import com.mrndtvndv.term.server.ServerRepository
import com.mrndtvndv.term.ui.notification.NotificationState
import com.mrndtvndv.term.ui.prefs.UserPrefs
import com.mrndtvndv.term.ui.review.ReviewViewModel
import com.mrndtvndv.term.ui.sftp.SftpViewModel
import com.mrndtvndv.term.ui.workspace.WorkspaceTab
import kotlinx.coroutines.launch

data class MainUiState(
    val screen: ScreenState = ScreenState.ServerList,
    val isLoading: Boolean = false,
    val error: String? = null,
    val activeTab: WorkspaceTab = WorkspaceTab.Terminal,
)

sealed interface ScreenState {
    data object ServerList : ScreenState
    data class TerminalWorkspace(val serverId: String) : ScreenState
}

@Suppress("TooManyFunctions") // session restore helpers pushed past the 20-function threshold
class MainViewModel(
    private val sessionManager: AppSessionManager,
    val userPrefs: UserPrefs,
    val notificationState: NotificationState,
    private val lastSessionStore: LastSessionStore,
) : ViewModel() {

    private val coordinator: ServerCoordinator get() = sessionManager.coordinator
    private val serverRepository: ServerRepository get() = sessionManager.serverRepository

    companion object {
        private const val LOCAL_TERMINAL_ID = "local_terminal"
    }

    private val _savedServers = mutableStateOf<List<ServerConfig>>(emptyList())
    val savedServers: State<List<ServerConfig>> = _savedServers

    private val _activeIds = mutableStateOf<Set<String>>(emptySet())
    val activeIds: State<Set<String>> = _activeIds

    private val _disconnectingId = mutableStateOf<String?>(null)
    val disconnectingId: State<String?> = _disconnectingId

    private val _connectingId = mutableStateOf<String?>(null)
    val connectingId: State<String?> = _connectingId

    private val _uiState = mutableStateOf(MainUiState())
    val uiState: State<MainUiState> = _uiState

    init {
        reloadServers()
        _activeIds.value = coordinator.activeIds
        observeSessionFinishes()
        restoreLastSession()
    }

    /**
     * Sessions outlive this ViewModel (owned by the app-scoped
     * AppSessionManager), so when one finishes on its own we only need to
     * reflect that in the UI.
     */
    private fun observeSessionFinishes() {
        viewModelScope.launch {
            sessionManager.sessionFinished.collect { serverId ->
                _activeIds.value = _activeIds.value - serverId
                val screen = _uiState.value.screen
                if (screen is ScreenState.TerminalWorkspace && screen.serverId == serverId) {
                    _uiState.value = _uiState.value.copy(screen = ScreenState.ServerList)
                    persistLastSession()
                }
            }
        }
    }

    /**
     * If the app was previously in a terminal workspace (process killed in
     * background, or ViewModel destroyed), reconnect to that server so the
     * user returns to their last session instead of the server list.
     *
     * If the session is still alive in the app-scoped manager (dismissed
     * from recents), connect() is a no-op re-attach: the live session is
     * returned immediately without a new SSH connection.
     */
    private fun restoreLastSession() {
        val last = lastSessionStore.load() ?: return
        if (serverRepository.get(last.serverId) == null) return
        _uiState.value = _uiState.value.copy(activeTab = last.activeTab)
        connect(last.serverId)
    }

    private fun persistLastSession() {
        lastSessionStore.save(_uiState.value.screen, _uiState.value.activeTab)
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
        coordinator.disconnect(id)
        _activeIds.value = _activeIds.value - id
        serverRepository.remove(id)
        reloadServers()
    }

    fun getServer(serverId: String): Server? = coordinator.getServer(serverId)
    fun getSftpViewModel(serverId: String): SftpViewModel? = coordinator.getSftpViewModel(serverId)
    fun getReviewViewModel(serverId: String): ReviewViewModel? = coordinator.getReviewViewModel(serverId)

    fun getLocalConfig(): ServerConfig? = serverRepository.get(LOCAL_TERMINAL_ID)

    /**
     * Update the startup command for the local terminal.
     * Creates the config first if it doesn't exist yet.
     */
    fun setLocalStartupCommand(command: String) {
        val trimmed = command.trim()
        val existing = serverRepository.get(LOCAL_TERMINAL_ID)
        val updated = (existing ?: ServerConfig(
            id = LOCAL_TERMINAL_ID,
            label = "Local Terminal",
            isLocal = true,
        )).copy(startupCommand = trimmed.ifEmpty { null })
        if (existing != null) {
            serverRepository.update(updated)
        } else {
            serverRepository.add(updated)
        }
        reloadServers()
    }

    // ── Connection ────────────────────────────────────────────────────

    fun connect(id: String) {
        val config = serverRepository.get(id) ?: return
        _connectingId.value = id
        _uiState.value = _uiState.value.copy(isLoading = true, error = null)

        viewModelScope.launch {
            val result = sessionManager.connect(id)
            _connectingId.value = null
            result.fold(
                onSuccess = { server ->
                    _activeIds.value = _activeIds.value + id
                    _uiState.value = _uiState.value.copy(
                        isLoading = false,
                        screen = ScreenState.TerminalWorkspace(id),
                    )
                    persistLastSession()
                },
                onFailure = { error ->
                    _uiState.value = _uiState.value.copy(
                        isLoading = false,
                        error = error.message,
                    )
                    // Don't retry a dead connection on every launch.
                    persistLastSession()
                },
            )
        }
    }

    /**
     * Start a local terminal shell.
     * Uses a singleton config with a well-known ID so tapping "Local Terminal"
     * always reuses the same entry (no duplicates in saved servers).
     */
    fun startLocalTerminal() {
        var config = serverRepository.get(LOCAL_TERMINAL_ID)
        if (config == null) {
            config = ServerConfig(
                id = LOCAL_TERMINAL_ID,
                label = "Local Terminal",
                isLocal = true,
            )
            saveServer(config)
        }
        connect(config.id)
    }

    fun navigateBack() {
        _uiState.value = _uiState.value.copy(screen = ScreenState.ServerList)
        persistLastSession()
    }

    fun disconnect(id: String) {
        _disconnectingId.value = id
        coordinator.disconnect(id)
        _activeIds.value = _activeIds.value - id
        _disconnectingId.value = null
        _uiState.value = _uiState.value.copy(screen = ScreenState.ServerList)
        persistLastSession()
    }

    // ── Workspace tracking ────────────────────────────────────────────

    fun refreshWorkspace(serverId: String) {
        viewModelScope.launch {
            coordinator.refreshWorkspace(serverId)
        }
    }

    fun onDirectoryChanged(serverId: String, path: String) {
        coordinator.onDirectoryChanged(serverId, path)
    }

    // ── Tab & URL ─────────────────────────────────────────────────────

    fun setTab(tab: WorkspaceTab) {
        _uiState.value = _uiState.value.copy(activeTab = tab)
        persistLastSession()
    }
}
