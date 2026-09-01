package com.mrndtvndv.term.server

import android.app.NotificationManager
import android.app.PendingIntent
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Build
import android.os.IBinder
import androidx.lifecycle.Lifecycle
import com.mrndtvndv.term.data.prefs.SharedPreferencesWorkspacePersistence
import com.mrndtvndv.term.service.SshSessionService
import com.mrndtvndv.term.ui.sftp.transfer.SftpTransferManager
import com.termux.shared.termux.TermuxConstants
import com.termux.shared.termux.terminal.TermuxTerminalSessionClientBase
import com.termux.terminal.TerminalSession
import kotlinx.coroutines.flow.StateFlow
import java.lang.ref.WeakReference

/**
 * Process-scoped owner of all terminal/SSH connections.
 *
 * Lives for the whole process (created by TermApplication), so connections
 * survive the activity being destroyed — dismissing the app from recents no
 * longer kills sessions. The activity (via [SessionHost]) only renders and
 * forwards input; the manager keeps connections, service binding and
 * notifications independent of the UI.
 */
class AppSessionManager private constructor(context: Context) : AppSessionManagerAccess {

    private val appContext = context.applicationContext
    private val prefs by lazy { appContext.getSharedPreferences("ssh_prefs", Context.MODE_PRIVATE) }

    override val serverRepository by lazy { ServerRepository(prefs) }
    private val persistence by lazy { SharedPreferencesWorkspacePersistence(prefs) }

    private val serverFactory by lazy {
        ServerFactory(
            context = appContext,
            persistence = persistence,
            onSessionClientCreated = { createDefaultSessionClient() },
            onServiceBind = { bindTerminalSession(it) },
            onSessionFinished = { serverId -> onServerSessionFinished(serverId) },
        )
    }
    private val serverManager by lazy { ServerManager(serverFactory) }
    override val coordinator by lazy {
        ServerCoordinator(
            serverManager = serverManager,
            serverRepository = serverRepository,
            transferManager = SftpTransferManager.getInstance(appContext),
        )
    }

    private val finishedSessions = SessionFinishedEvents()
    /** Emits a server id whenever one of its sessions finished on its own. */
    override val sessionFinished = finishedSessions.flow
    private val terminalProgress = TerminalProgressStore()

    private var hostRef: WeakReference<SessionHost>? = null

    // ── Host (activity) ──────────────────────────────────────────────

    fun setHost(host: SessionHost?) {
        hostRef = host?.let { WeakReference(it) }
    }

    override fun observeTerminalProgress(session: TerminalSession): StateFlow<TerminalProgress?> =
        terminalProgress.observe(session)

    // ── Connection lifecycle ─────────────────────────────────────────

    override suspend fun connect(id: String): Result<Server> = coordinator.connect(id)

    fun disconnectAll() {
        coordinator.disconnectAll()
        val service = sshService
        service?.disconnectAll()
        synchronized(this) {
            activeSessions.clear()
            pendingSessions.clear()
            sshService?.stopIfIdle()
            unbindServiceIfNeeded()
        }
    }

    private fun onServerSessionFinished(serverId: String) {
        coordinator.disconnect(serverId)
        finishedSessions.emit(serverId)
    }

    // ── Terminal protocol notifications ──────────────────────────────

    fun handleTerminalNotification(title: String?, body: String?, serverId: String? = null) {
        val host = hostRef?.get()
        if (host != null && host.isAtLeast(Lifecycle.State.RESUMED)) {
            host.showInAppNotification(title, body, serverId)
        } else {
            showSystemNotification(title, body, serverId)
        }
    }

    private var nextNotificationId =
        TermuxConstants.TERMUX_TERMINAL_PROTOCOL_NOTIFICATION_ID_BASE

    @Synchronized
    private fun getNextTerminalProtocolNotificationId(): Int {
        val id = nextNotificationId
        nextNotificationId++
        return id
    }

    private fun showSystemNotification(title: String?, body: String?, serverId: String?) {
        val notificationManager =
            com.termux.shared.notification.NotificationUtils.getNotificationManager(appContext) ?: return

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            com.termux.shared.notification.NotificationUtils.setupNotificationChannel(
                appContext,
                TermuxConstants.TERMUX_TERMINAL_PROTOCOL_NOTIFICATIONS_NOTIFICATION_CHANNEL_ID,
                TermuxConstants.TERMUX_TERMINAL_PROTOCOL_NOTIFICATIONS_NOTIFICATION_CHANNEL_NAME,
                NotificationManager.IMPORTANCE_DEFAULT,
            )
        }

        val normalizedTitle = title ?: "Terminal Notification"
        val normalizedBody = body ?: ""

        val intent = Intent(appContext, com.mrndtvndv.term.MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
            putExtra(EXTRA_NOTIFICATION_BODY, normalizedBody)
            if (serverId != null) {
                putExtra(EXTRA_NOTIFICATION_SERVER_ID, serverId)
            }
        }
        val contentIntent = PendingIntent.getActivity(
            appContext,
            0,
            intent,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M)
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            else PendingIntent.FLAG_UPDATE_CURRENT,
        )

        val builder = com.termux.shared.notification.NotificationUtils.geNotificationBuilder(
            appContext,
            TermuxConstants.TERMUX_TERMINAL_PROTOCOL_NOTIFICATIONS_NOTIFICATION_CHANNEL_ID,
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

    // ── Session service binding ──────────────────────────────────────

    private var sshService: SshSessionService? = null
    private val activeSessions = mutableSetOf<TerminalSession>()
    private val pendingSessions = mutableSetOf<TerminalSession>()
    private var isBound = false

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(className: ComponentName, service: IBinder) {
            val binder = service as SshSessionService.LocalBinder
            val s = binder.getService()
            synchronized(this@AppSessionManager) {
                sshService = s
                pendingSessions.forEach { s.addSession(it) }
                pendingSessions.clear()
                if (activeSessions.isEmpty()) {
                    s.stopIfIdle()
                    unbindServiceIfNeeded()
                }
            }
        }

        override fun onServiceDisconnected(arg0: ComponentName) {
            synchronized(this@AppSessionManager) {
                sshService = null
                pendingSessions.addAll(activeSessions)
            }
        }
    }

    @Synchronized
    private fun bindTerminalSession(termSession: TerminalSession) {
        activeSessions.add(termSession)
        val intent = Intent(appContext, SshSessionService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            appContext.startForegroundService(intent)
        } else {
            appContext.startService(intent)
        }
        val service = sshService
        if (service != null) {
            service.addSession(termSession)
        } else {
            pendingSessions.add(termSession)
        }
        if (!isBound) {
            isBound = appContext.bindService(intent, connection, Context.BIND_AUTO_CREATE)
        }
    }

    @Synchronized
    private fun unbindTerminalSession(termSession: TerminalSession) {
        activeSessions.remove(termSession)
        pendingSessions.remove(termSession)
        sshService?.removeSession(termSession.mHandle)
        if (activeSessions.isEmpty()) {
            sshService?.stopIfIdle()
            unbindServiceIfNeeded()
        }
    }

    @Synchronized
    private fun unbindServiceIfNeeded() {
        if (!isBound) return
        appContext.unbindService(connection)
        isBound = false
        sshService = null
    }

    // ── Default session client ───────────────────────────────────────

    /**
     * Activity-agnostic session client: every callback is routed through the
     * current [SessionHost] (or dropped when the UI is gone), so one client
     * stays valid across activity recreations.
     */
    private fun createDefaultSessionClient(): TermuxTerminalSessionClientBase {
        return object : TermuxTerminalSessionClientBase() {
            override fun onFrameAvailable(changedSession: TerminalSession) {
                val host = hostRef?.get() ?: return
                if (!host.isAtLeast(Lifecycle.State.STARTED)) return
                host.onFrameAvailable(changedSession)
            }

            override fun onCopyTextToClipboard(session: TerminalSession, text: String) {
                hostRef?.get()?.copyToClipboard(text)
            }

            override fun onPasteTextFromClipboard(session: TerminalSession?) {
                val host = hostRef?.get() ?: return
                val text = host.pasteFromClipboard() ?: return
                session?.paste(text)
            }

            override fun onTerminalProtocolNotification(
                session: TerminalSession,
                title: String?,
                body: String?,
            ) {
                val serverId = serverManager.serverIdForSession(session)
                handleTerminalNotification(title, body, serverId)
            }

            override fun onTerminalProgressChanged(session: TerminalSession) {
                terminalProgress.update(session)
            }

            override fun onSessionFinished(finishedSession: TerminalSession) {
                terminalProgress.remove(finishedSession)
                unbindTerminalSession(finishedSession)
            }
        }
    }

    companion object {
        /** Intent extra: originating server id for a tapped terminal notification. */
        const val EXTRA_NOTIFICATION_SERVER_ID = "termux.terminal.notification.server_id"
        /** Intent extra: notification body, so the tap handler can re-parse the focus target. */
        const val EXTRA_NOTIFICATION_BODY = "termux.terminal.notification.body"

        @Volatile
        private var instance: AppSessionManager? = null

        fun init(context: Context) {
            if (instance == null) {
                instance = AppSessionManager(context.applicationContext)
            }
        }

        val current: AppSessionManager?
            get() = instance
    }
}
