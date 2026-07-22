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
import com.termux.terminal.TerminalSession
import java.util.concurrent.ConcurrentHashMap

class SshSessionService : Service() {

    private val binder = LocalBinder()
    private val sessions = ConcurrentHashMap<String, TerminalSession>()
    private var wakeLock: PowerManager.WakeLock? = null

    companion object {
        const val ACTION_DISCONNECT = "com.mrndtvndv.term.action.DISCONNECT_SSH"
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
        acquireWakeLock()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_DISCONNECT) {
            disconnectAll()
            return START_NOT_STICKY
        }
        promoteToForeground()
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder {
        return binder
    }

    override fun onDestroy() {
        releaseWakeLock()
        sessions.values.forEach { it.finishIfRunning() }
        sessions.clear()
        super.onDestroy()
    }

    fun getSessions(): Map<String, TerminalSession> = sessions

    fun addSession(session: TerminalSession) {
        sessions[session.mHandle] = session
        promoteToForeground()
    }

    fun removeSession(handle: String) {
        sessions.remove(handle)?.finishIfRunning()
        if (sessions.isEmpty()) {
            stopForegroundCompat()
            stopSelf()
        } else {
            promoteToForeground()
        }
    }

    private fun disconnectAll() {
        sessions.values.forEach { it.finishIfRunning() }
        sessions.clear()
        stopForegroundCompat()
        stopSelf()
    }

    private fun promoteToForeground() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(
                NOTIFICATION_ID,
                buildNotification(),
                android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
            )
        } else {
            startForeground(NOTIFICATION_ID, buildNotification())
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

    private fun acquireWakeLock() {
        val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, WAKE_LOCK_TAG).apply {
            acquire()
        }
    }

    private fun releaseWakeLock() {
        wakeLock?.let {
            if (it.isHeld) {
                it.release()
            }
        }
        wakeLock = null
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

        val title = when {
            sshCount > 0 && localCount > 0 -> "Active Terminal Sessions"
            sshCount > 0 -> "SSH Session Active"
            else -> "Local Session Active"
        }

        val text = when {
            sshCount > 0 && localCount > 0 ->
                "Maintaining $sshCount SSH and $localCount local terminal sessions"
            sshCount > 0 -> {
                if (sshCount > 1) "Maintaining $sshCount active SSH terminal sessions"
                else "Maintaining active SSH terminal session"
            }
            else -> {
                if (localCount > 1) "Maintaining $localCount active local terminal sessions"
                else "Maintaining active local terminal session"
            }
        }

        return Pair(title, text)
    }

    private fun buildNotification(): Notification {
        val openIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val openPendingIntent = PendingIntent.getActivity(
            this, 0, openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val disconnectIntent = Intent(this, SshSessionService::class.java).apply {
            action = ACTION_DISCONNECT
        }
        val disconnectPendingIntent = PendingIntent.getService(
            this, 1, disconnectIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val (title, text) = getNotificationTitleAndText()

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(text)
            .setSmallIcon(android.R.drawable.stat_notify_chat)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setContentIntent(openPendingIntent)
            .addAction(
                android.R.drawable.ic_menu_close_clear_cancel,
                "Disconnect All",
                disconnectPendingIntent
            )
            .build()
    }
}
