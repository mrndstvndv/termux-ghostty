package com.mrndtvndv.term.server

import com.mrndtvndv.term.domain.ServerConfig
import com.mrndtvndv.term.ui.review.ReviewViewModel
import com.mrndtvndv.term.ui.sftp.SftpViewModel
import com.termux.terminal.TerminalSession
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

interface ServerCoordinatorAccess {
    val activeIds: Set<String>

    fun getServer(id: String): Server?

    fun getSftpViewModel(id: String): SftpViewModel?

    fun getReviewViewModel(id: String): ReviewViewModel?

    fun disconnect(id: String)

    suspend fun refreshWorkspace(serverId: String): ServerCoordinator.WorkspaceChange?

    fun onDirectoryChanged(serverId: String, path: String)
}

interface ServerRepositoryAccess {
    fun loadAll(): List<ServerConfig>

    fun add(config: ServerConfig)

    fun update(config: ServerConfig)

    fun remove(id: String)

    fun get(id: String): ServerConfig?
}

interface AppSessionManagerAccess {
    val coordinator: ServerCoordinatorAccess
    val serverRepository: ServerRepositoryAccess
    val sessionFinished: Flow<String>

    suspend fun connect(id: String): Result<Server>

    fun observeTerminalProgress(session: TerminalSession): StateFlow<TerminalProgress?>
}
