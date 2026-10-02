package com.mrndtvndv.term.ui.workspace

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.mrndtvndv.term.server.AppSessionManagerAccess
import com.mrndtvndv.term.server.HerdrWorkspaceResolver
import com.mrndtvndv.term.server.herdrResolver
import com.termux.terminal.TerminalSession
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

class WorkspaceViewModel(
    private val serverId: String,
    private val sessionManager: AppSessionManagerAccess,
) : ViewModel() {

    private val coordinator get() = sessionManager.coordinator

    private val _herdrState = MutableStateFlow(HerdrUiState())
    val herdrState: StateFlow<HerdrUiState> = _herdrState.asStateFlow()

    fun observeTerminalProgress(session: TerminalSession) = sessionManager.observeTerminalProgress(session)

    fun loadHerdrAgents() {
        val resolver = coordinator.getServer(serverId)?.herdrResolver()
        if (resolver == null) {
            _herdrState.value = HerdrUiState(error = "Herdr is unavailable for this session")
            return
        }

        _herdrState.update { it.copy(isLoading = true, error = null) }
        viewModelScope.launch {
            runCatching { resolver.listWorkspaceTabs() }.fold(
                onSuccess = { workspaces -> _herdrState.value = HerdrUiState(workspaces = workspaces) },
                onFailure = { error ->
                    _herdrState.value =
                        HerdrUiState(error = error.message ?: "Unable to query Herdr workspaces")
                },
            )
        }
    }

    fun focusTab(tab: HerdrWorkspaceResolver.HerdrTabNode) {
        viewModelScope.launch { coordinator.focusHerdr(serverId) { this.focusTab(tab) } }
    }

    fun focusPane(pane: HerdrWorkspaceResolver.HerdrPaneNode) {
        viewModelScope.launch { coordinator.focusHerdr(serverId) { this.focusPane(pane) } }
    }

    fun closePane(pane: HerdrWorkspaceResolver.HerdrPaneNode) {
        val resolver = coordinator.getServer(serverId)?.herdrResolver() ?: return
        viewModelScope.launch {
            runCatching { resolver.closePane(pane.paneId) }
            loadHerdrAgents()
        }
    }
}
