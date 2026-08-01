package com.mrndtvndv.term.server

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Manages workspace tracking for a single herdr-enabled server.
 *
 * Core invariant:
 * - Workspace unchanged → MUST NOT overwrite workspaceDir
 *   (the user manually navigated in SFTP, respect it)
 * - Workspace changed → load saved dir for new workspace,
 *   or default to active pane's cwd
 */
class WorkspaceTracker(
    private val host: String,
    private val username: String,
    private val resolver: HerdrWorkspaceResolver,
    private val persistence: WorkspacePersistence,
) {
    private var currentWorkspaceKey: String? = null
    private val _workspaceDir = MutableStateFlow("/")
    val workspaceDir: StateFlow<String> = _workspaceDir.asStateFlow()

    /**
     * Sync workspace state with the herdr daemon.
     * Called when the user taps the SFTP or Review tab.
     *
     * @return SyncResult describing what (if anything) changed
     */
    suspend fun sync(): SyncResult {
        val info = resolver.resolve(host, username) ?: return SyncResult.NoChange

        val prevKey = currentWorkspaceKey
        val currentKey = info.workspaceKey

        return if (currentKey != prevKey) {
            // Workspace changed — load saved state for new workspace
            currentWorkspaceKey = currentKey

            val savedDir = persistence.loadLastDir(currentKey)
            val newDir = savedDir ?: info.cwd
            _workspaceDir.value = newDir

            SyncResult.WorkspaceChanged(
                newWorkspaceKey = currentKey,
                workspaceDir = newDir,
            )
        } else {
            // Workspace unchanged — respect user's manual navigation
            SyncResult.NoChange
        }
    }

    fun onDirectoryChanged(path: String) {
        _workspaceDir.value = path
        currentWorkspaceKey?.let { persistence.saveLastDir(it, path) }
    }

    sealed interface SyncResult {
        data object NoChange : SyncResult
        data class WorkspaceChanged(
            val newWorkspaceKey: String,
            val workspaceDir: String,
        ) : SyncResult
    }
}
