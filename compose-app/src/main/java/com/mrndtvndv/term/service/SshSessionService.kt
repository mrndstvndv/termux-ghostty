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
        startForeground(NOTIFICATION_ID, buildNotification())
        acquireWakeLock()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
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
    }

    fun removeSession(handle: String) {
        sessions.remove(handle)?.finishIfRunning()
        if (sessions.isEmpty()) {
            stopSelf()
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
            val name = "SSH Session Service"
            val descriptionText = "Keeps SSH and SFTP connections active in the background"
            val importance = NotificationManager.IMPORTANCE_LOW
            val channel = NotificationChannel(CHANNEL_ID, name, importance).apply {
                description = descriptionText
            }
            val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            notificationManager.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(): Notification {
        val intent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            this, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("SSH Session Active")
            .setContentText("Termux Ghostty is maintaining active terminal sessions")
            .setSmallIcon(android.R.drawable.stat_notify_chat)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setContentIntent(pendingIntent)
            .build()
    }
}
