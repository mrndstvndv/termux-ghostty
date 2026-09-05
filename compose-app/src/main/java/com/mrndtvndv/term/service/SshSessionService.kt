package com.mrndtvndv.term.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import com.mrndtvndv.term.MainActivity
import com.mrndtvndv.term.server.AppSessionManager
import com.termux.terminal.TerminalSession
import java.util.concurrent.ConcurrentHashMap

class SshSessionService : Service() {

    private val binder = LocalBinder()
    private val sessions = ConcurrentHashMap<String, TerminalSession>()
    private var wakeLock: PowerManager.WakeLock? = null

    companion object {
        const val ACTION_DISCONNECT = "com.mrndtvndv.term.action.DISCONNECT_SSH"
        const val ACTION_WAKE_LOCK = "com.mrndtvndv.term.action.WAKE_LOCK"
        const val ACTION_WAKE_UNLOCK = "com.mrndtvndv.term.action.WAKE_UNLOCK"
        private const val CHANNEL_ID = "ssh_session_channel"
        private const val NOTIFICATION_ID = 1001
        private const val WAKE_LOCK_TAG = "TermuxGhostty:SshSessionService"
    }

    inner class LocalBinder : Binder() {
        fun getService(): SshSessionService = this@SshSessionService
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        promoteToForeground()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_DISCONNECT -> {
                // Route through the session manager so SSH connections are closed too.
                AppSessionManager.current?.disconnectAll() ?: disconnectAll()
            }
            ACTION_WAKE_LOCK -> {
                acquireWakeLock()
            }
            ACTION_WAKE_UNLOCK -> {
                releaseWakeLock()
            }
            else -> {
                promoteToForeground()
            }
        }
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder {
        return binder
    }

    override fun onDestroy() {
        releaseWakeLockInternal()
        sessions.values.forEach { it.finishIfRunning() }
        sessions.clear()
        super.onDestroy()
    }

    fun getSessions(): Map<String, TerminalSession> = sessions

    fun addSession(session: TerminalSession) {
        sessions[session.mHandle] = session
        updateNotification()
    }

    fun removeSession(handle: String) {
        sessions.remove(handle)?.finishIfRunning()
        updateNotification()
    }

    fun disconnectAll() {
        sessions.values.forEach {
            try { it.finishIfRunning() } catch (_: Exception) { }
            try { it.close() } catch (_: Exception) { }
        }
        sessions.clear()
        updateNotification()
    }

    fun stopIfIdle() {
        if (!isWakeLockHeld() && sessions.isEmpty()) {
            stopForegroundCompat()
            stopSelf()
        }
    }

    fun acquireWakeLock() {
        if (wakeLock?.isHeld == true) return
        val powerManager = getSystemService(Context.POWER_SERVICE) as? PowerManager ?: return
        wakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, WAKE_LOCK_TAG).apply {
            setReferenceCounted(false)
            acquire()
        }
        updateNotification()
    }

    fun releaseWakeLock() {
        releaseWakeLockInternal()
        updateNotification()
    }

    private fun releaseWakeLockInternal() {
        val lock = wakeLock ?: return
        if (lock.isHeld) {
            lock.release()
        }
        wakeLock = null
    }

    fun isWakeLockHeld(): Boolean = wakeLock?.isHeld == true

    private fun updateNotification() {
        if (!isWakeLockHeld() && sessions.isEmpty()) {
            stopForegroundCompat()
            stopSelf()
            return
        }
        promoteToForeground()
    }

    private fun promoteToForeground() {
        val notification = buildNotification()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun stopForegroundCompat() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } else {
            @Suppress("DEPRECATION")
            stopForeground(true)
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val name = "Terminal Session Service"
            val descriptionText = "Keeps terminal and SSH sessions active in the background"
            val importance = NotificationManager.IMPORTANCE_LOW
            val channel = NotificationChannel(CHANNEL_ID, name, importance).apply {
                description = descriptionText
            }
            val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            notificationManager.createNotificationChannel(channel)
        }
    }

    private fun getNotificationTitleAndText(): Pair<String, String> {
        val activeSessions = sessions.values
        val sshCount = activeSessions.count { it.sshSessionHandle != 0L }
        val localCount = activeSessions.count { it.sshSessionHandle == 0L }
        val wakeLockHeld = wakeLock?.isHeld == true
        return SshSessionNotificationFormatter.formatTitleAndText(sshCount, localCount, wakeLockHeld)
    }

    private fun buildNotification(): Notification {
        val openIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val openPendingIntent = PendingIntent.getActivity(
            this, 0, openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val wakeLockHeld = wakeLock?.isHeld == true
        val wakeAction = if (wakeLockHeld) ACTION_WAKE_UNLOCK else ACTION_WAKE_LOCK
        val wakeActionTitle = if (wakeLockHeld) "Release wakelock" else "Acquire wakelock"
        val wakeActionIcon = if (wakeLockHeld) {
            android.R.drawable.ic_lock_idle_lock
        } else {
            android.R.drawable.ic_lock_lock
        }
        val wakeIntent = Intent(this, SshSessionService::class.java).apply {
            action = wakeAction
        }
        val wakePendingIntent = PendingIntent.getService(
            this, 1, wakeIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val disconnectIntent = Intent(this, SshSessionService::class.java).apply {
            action = ACTION_DISCONNECT
        }
        val disconnectPendingIntent = PendingIntent.getService(
            this, 2, disconnectIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val (title, text) = getNotificationTitleAndText()

        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(text)
            .setSmallIcon(android.R.drawable.stat_notify_chat)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setContentIntent(openPendingIntent)
            .addAction(wakeActionIcon, wakeActionTitle, wakePendingIntent)

        if (sessions.isNotEmpty()) {
            builder.addAction(
                android.R.drawable.ic_menu_close_clear_cancel,
                "Disconnect All",
                disconnectPendingIntent
            )
        }

        return builder.build()
    }
}
