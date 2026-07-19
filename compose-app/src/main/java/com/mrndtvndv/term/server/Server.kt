package com.mrndtvndv.term.server

import com.mrndtvndv.term.domain.ServerConfig
import com.mrndtvndv.term.domain.SftpClient
import com.mrndtvndv.term.domain.SshSession
import com.mrndtvndv.term.domain.SshShellChannel
import com.termux.terminal.TerminalSession

/**
 * Aggregate root for a single SSH connection.
 * All resources are cleaned up by calling [disconnect].
 */
class Server(
    val config: ServerConfig,
    val sshSession: SshSession,
    val shellChannel: SshShellChannel,
    val sftpClient: SftpClient,
    val terminalSession: TerminalSession,
    val workspaceState: WorkspaceState,
) {
    /**
     * Clean up all native resources for this connection.
     * Safe to call multiple times.
     */
    fun disconnect() {
        try { terminalSession.finishIfRunning() } catch (_: Exception) { }
        try { sftpClient.close() } catch (_: Exception) { }
        try { shellChannel.close() } catch (_: Exception) { }
        try { sshSession.disconnect() } catch (_: Exception) { }
    }
}
