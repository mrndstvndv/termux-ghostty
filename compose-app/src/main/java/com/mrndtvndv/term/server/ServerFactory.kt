package com.mrndtvndv.term.server

import com.mrndtvndv.term.data.ssh.native.NativeSshSession
import com.mrndtvndv.term.domain.ServerConfig
import com.mrndtvndv.term.domain.SshShellChannel
import com.mrndtvndv.term.domain.toDomainAuth
import com.termux.shared.termux.terminal.TermuxTerminalSessionClientBase
import com.termux.terminal.TerminalSession
import com.termux.terminal.TerminalSessionIO
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Orchestrates the creation of a Server from a ServerConfig.
 * This replaces the connection logic currently inside MainViewModel.connectSsh().
 */
class ServerFactory(
    private val persistence: WorkspacePersistence,
    private val onSessionClientCreated: () -> TermuxTerminalSessionClientBase,
    private val onServiceBind: (TerminalSession) -> Unit,
) {
    /**
     * Connect to the server and return a fully wired Server.
     * Throws on failure.
     */
    suspend fun create(config: ServerConfig): Server {
        // 1. Open TCP socket and init native SSH
        val session = NativeSshSession()

        withContext(Dispatchers.IO) {
            session.connect(config.toSshConfig())
            session.authenticate(config.toDomainAuth())
        }

        // 2. Open shell channel
        val channel = withContext(Dispatchers.IO) {
            session.openShellChannel(
                termType = config.termType,
                cols = 80,
                rows = 24,
                herdrIntegration = config.herdrEnabled,
            )
        }

        // 3. Create TerminalSession with IO bridge
        val sessionClient = onSessionClientCreated()
        val sessionIo = TerminalSessionIOBridge(channel = channel)
        val termSession = TerminalSession(2000, sessionClient, sessionIo)
        termSession.setSshSessionHandle(session.nativeSessionHandle)
        onServiceBind(termSession)

        // 4. Open SFTP client
        val sftpClient = withContext(Dispatchers.IO) {
            session.openSftpClient()
        }

        // 5. Build workspace state
        val workspaceState = if (config.herdrEnabled) {
            val resolver = HerdrWorkspaceResolver { cmd -> session.execCommand(cmd) }
            val tracker = WorkspaceTracker(
                host = config.host,
                username = config.username,
                resolver = resolver,
                persistence = persistence,
            )
            tracker.sync()
            WorkspaceState.Tracked(tracker)
        } else {
            WorkspaceState.Untracked
        }

        return Server(
            config = config,
            sshSession = session,
            shellChannel = channel,
            sftpClient = sftpClient,
            terminalSession = termSession,
            workspaceState = workspaceState,
        )
    }
}

/**
 * Converts a ServerConfig to a domain SshConfig for connecting NativeSshSession.
 * Need to keep SshConfig around since SshSession.connect() still uses it.
 */
private fun ServerConfig.toSshConfig() =
    com.mrndtvndv.term.domain.SshConfig(
        host = host,
        port = port,
        username = username,
    )

/**
 * Bridges TerminalSessionIO to the native shell channel.
 * Extracted from the anonymous object in MainViewModel.connectSsh().
 */
private class TerminalSessionIOBridge(
    private val channel: SshShellChannel,
) : TerminalSessionIO {
    override fun write(data: ByteArray?, offset: Int, count: Int) {
        if (data != null && count > 0) {
            try {
                channel.outputStream.write(data, offset, count)
            } catch (_: Exception) { }
        }
    }

    override fun onResize(columns: Int, rows: Int, cellWidth: Int, cellHeight: Int) {
        try {
            channel.resizeWindow(columns, rows, columns * cellWidth, rows * cellHeight)
        } catch (_: Exception) { }
    }

    override fun onClose() {
        // Connection cleanup is handled by Server.disconnect()
    }
}
