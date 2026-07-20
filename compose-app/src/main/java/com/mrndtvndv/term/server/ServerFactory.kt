package com.mrndtvndv.term.server

import android.content.Context
import android.os.Build
import com.mrndtvndv.term.data.ssh.native.NativeSshSession
import com.mrndtvndv.term.domain.ServerConfig
import com.mrndtvndv.term.domain.SshShellChannel
import com.mrndtvndv.term.domain.toDomainAuth
import com.termux.shared.termux.terminal.TermuxTerminalSessionClientBase
import com.termux.terminal.TerminalSession
import com.termux.terminal.TerminalSessionIO
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Orchestrates the creation of a Server from a ServerConfig.
 * Accepts [context] for resolving local shell paths and building environment.
 */
class ServerFactory(
    private val context: Context,
    private val persistence: WorkspacePersistence,
    private val onSessionClientCreated: () -> TermuxTerminalSessionClientBase,
    private val onServiceBind: (TerminalSession) -> Unit,
    private val onSessionFinished: (serverId: String) -> Unit,
) {
    /**
     * Connect to the server and return a fully wired Server.
     * Throws on failure.
     */
    suspend fun create(config: ServerConfig): Server {
        if (config.isLocal) return createLocal(config)

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
        val wrappedClient = wrapSessionClient(sessionClient) { onSessionFinished(config.id) }
        termSession.updateTerminalSessionClient(wrappedClient)
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

    // ── Session client wrapper ───────────────────────────────────────

    /**
     * Wrap a [TermuxTerminalSessionClientBase] so that [onSessionFinished]
     * also invokes the [onFinished] callback, while delegating every other
     * callback to [original] unchanged.
     */
    private fun wrapSessionClient(
        original: TermuxTerminalSessionClientBase,
        onFinished: () -> Unit,
    ): TermuxTerminalSessionClientBase {
        return object : TermuxTerminalSessionClientBase() {
            override fun onSessionFinished(finishedSession: TerminalSession) {
                original.onSessionFinished(finishedSession)
                onFinished()
            }

            override fun onTextChanged(changedSession: TerminalSession) {
                original.onTextChanged(changedSession)
            }

            override fun onFrameAvailable(changedSession: TerminalSession) {
                original.onFrameAvailable(changedSession)
            }

            override fun onTitleChanged(updatedSession: TerminalSession) {
                original.onTitleChanged(updatedSession)
            }

            override fun onCopyTextToClipboard(session: TerminalSession, text: String) {
                original.onCopyTextToClipboard(session, text)
            }

            override fun onPasteTextFromClipboard(session: TerminalSession?) {
                original.onPasteTextFromClipboard(session)
            }

            override fun onBell(session: TerminalSession) {
                original.onBell(session)
            }

            override fun onColorsChanged(changedSession: TerminalSession) {
                original.onColorsChanged(changedSession)
            }

            override fun onTerminalProtocolNotification(session: TerminalSession, title: String?, body: String?) {
                original.onTerminalProtocolNotification(session, title, body)
            }

            override fun onTerminalProgressChanged(session: TerminalSession) {
                original.onTerminalProgressChanged(session)
            }

            override fun onTerminalCursorStateChange(state: Boolean) {
                original.onTerminalCursorStateChange(state)
            }

            override fun setTerminalShellPid(session: TerminalSession, pid: Int) {
                original.setTerminalShellPid(session, pid)
            }

            override fun getTerminalCursorStyle(): Int? {
                return original.getTerminalCursorStyle()
            }

            override fun logError(tag: String, message: String) {
                original.logError(tag, message)
            }

            override fun logWarn(tag: String, message: String) {
                original.logWarn(tag, message)
            }

            override fun logInfo(tag: String, message: String) {
                original.logInfo(tag, message)
            }

            override fun logDebug(tag: String, message: String) {
                original.logDebug(tag, message)
            }

            override fun logVerbose(tag: String, message: String) {
                original.logVerbose(tag, message)
            }

            override fun logStackTraceWithMessage(tag: String, message: String, e: Exception) {
                original.logStackTraceWithMessage(tag, message, e)
            }

            override fun logStackTrace(tag: String, e: Exception) {
                original.logStackTrace(tag, e)
            }
        }
    }

    // ── Local path ───────────────────────────────────────────────────

    private fun createLocal(config: ServerConfig): Server {
        val shellPath = resolveLocalShell()
            ?: throw IllegalStateException("No shell binary found on device")

        val cwd = "/"
        val args = arrayOf(shellPath, "-l")
        val env = buildLocalEnvironment()
        val transcriptRows = 2000

        val sessionClient = onSessionClientCreated()
        val terminalSession = TerminalSession(
            shellPath, cwd, args, env, transcriptRows, sessionClient,
        )
        val wrappedClient = wrapSessionClient(sessionClient) { onSessionFinished(config.id) }
        terminalSession.updateTerminalSessionClient(wrappedClient)
        onServiceBind(terminalSession)

        return Server(
            config = config,
            terminalSession = terminalSession,
        )
    }

    private fun resolveLocalShell(): String? {
        // 1. Termux prefix (shared Termux install)
        val termuxBash = "/data/data/com.termux/files/usr/bin/bash"
        if (File(termuxBash).canExecute()) return termuxBash

        // 2. System shell
        val systemSh = "/system/bin/sh"
        if (File(systemSh).canExecute()) return systemSh

        return null
    }

    private fun buildLocalEnvironment(): Array<String> {
        val homeDir = context.filesDir.absolutePath
        val tmpDir = context.cacheDir.absolutePath
        val nativeLibDir = context.applicationInfo.nativeLibraryDir
        val prefixBin = File(nativeLibDir).parentFile?.let {
            File(it, "files/usr/bin").absolutePath
        } ?: ""

        val env = mutableListOf(
            "TERM=xterm-ghostty",
            "COLORTERM=truecolor",
            "TERM_PROGRAM=ghostty",
            "HOME=$homeDir",
            "TMPDIR=$tmpDir",
            "PATH=/system/bin:/system/xbin:/vendor/bin:$prefixBin",
        )

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            env.add("ANDROID_SDK_VERSION=${Build.VERSION.SDK_INT}")
        }

        return env.toTypedArray()
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
