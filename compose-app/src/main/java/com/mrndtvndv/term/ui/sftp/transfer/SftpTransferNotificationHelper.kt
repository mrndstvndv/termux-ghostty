package com.mrndtvndv.term.ui.sftp.transfer

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.webkit.MimeTypeMap
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import com.mrndtvndv.term.MainActivity
import java.io.File
import java.util.Locale

open class SftpTransferNotificationHelper(private val context: Context) {

    private val notificationManager: NotificationManager? by lazy {
        context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
    }

    init {
        createNotificationChannel()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                CHANNEL_NAME,
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = CHANNEL_DESCRIPTION
            }
            notificationManager?.createNotificationChannel(channel)
        }
    }

    open fun canPostNotifications(): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            return ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
        }
        return NotificationManagerCompat.from(context).areNotificationsEnabled()
    }

    fun notificationIdFor(transferId: String): Int {
        return NOTIFICATION_ID_BASE + ((transferId.hashCode() and HASH_MASK) % ID_MODULO)
    }

    fun buildProgressNotification(transfer: SftpTransfer): Notification {
        val title = if (transfer.type == TransferType.DOWNLOAD) {
            "Downloading ${transfer.fileName}"
        } else {
            "Uploading ${transfer.fileName}"
        }

        val text = if (transfer.totalBytes > 0L) {
            "${formatBytes(transfer.transferredBytes)} / ${formatBytes(transfer.totalBytes)}"
        } else {
            formatBytes(transfer.transferredBytes)
        }

        val smallIcon = if (transfer.type == TransferType.DOWNLOAD) {
            android.R.drawable.stat_sys_download
        } else {
            android.R.drawable.stat_sys_upload
        }

        val progressPercent = (transfer.progress * PERCENT_MULTIPLIER).toInt()
        val indeterminate = transfer.totalBytes <= 0L

        val cancelPendingIntent = buildCancelPendingIntent(transfer.id)
        val contentPendingIntent = buildOpenAppPendingIntent()

        return NotificationCompat.Builder(context, CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(text)
            .setSmallIcon(smallIcon)
            .setProgress(MAX_PROGRESS, progressPercent, indeterminate)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setContentIntent(contentPendingIntent)
            .addAction(
                android.R.drawable.ic_menu_close_clear_cancel,
                "Cancel",
                cancelPendingIntent
            )
            .build()
    }

    fun buildCompleteNotification(transfer: SftpTransfer): Notification {
        val title = if (transfer.type == TransferType.DOWNLOAD) {
            "Download Complete"
        } else {
            "Upload Complete"
        }

        val smallIcon = if (transfer.type == TransferType.DOWNLOAD) {
            android.R.drawable.stat_sys_download_done
        } else {
            android.R.drawable.stat_sys_upload_done
        }

        val openFilePendingIntent = if (transfer.type == TransferType.DOWNLOAD) {
            buildOpenFilePendingIntent(transfer.localFile)
        } else {
            null
        }

        val contentPendingIntent = openFilePendingIntent ?: buildOpenAppPendingIntent()

        val builder = NotificationCompat.Builder(context, CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(transfer.fileName)
            .setSmallIcon(smallIcon)
            .setOngoing(false)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setContentIntent(contentPendingIntent)

        if (openFilePendingIntent != null) {
            builder.addAction(
                android.R.drawable.ic_menu_view,
                "Open",
                openFilePendingIntent
            )
        }

        return builder.build()
    }

    fun buildErrorNotification(transfer: SftpTransfer): Notification {
        val title = if (transfer.type == TransferType.DOWNLOAD) {
            "Download Failed"
        } else {
            "Upload Failed"
        }
        val errorMessage = transfer.error ?: "Transfer failed"
        val text = "${transfer.fileName}: $errorMessage"

        return NotificationCompat.Builder(context, CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(text)
            .setSmallIcon(android.R.drawable.stat_notify_error)
            .setOngoing(false)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setContentIntent(buildOpenAppPendingIntent())
            .build()
    }

    open fun showProgressNotification(transfer: SftpTransfer) {
        if (!canPostNotifications()) return
        notificationManager?.notify(notificationIdFor(transfer.id), buildProgressNotification(transfer))
    }

    open fun showCompleteNotification(transfer: SftpTransfer) {
        if (!canPostNotifications()) return
        notificationManager?.notify(notificationIdFor(transfer.id), buildCompleteNotification(transfer))
    }

    open fun showErrorNotification(transfer: SftpTransfer) {
        if (!canPostNotifications()) return
        notificationManager?.notify(notificationIdFor(transfer.id), buildErrorNotification(transfer))
    }

    open fun cancelNotification(transferId: String) {
        notificationManager?.cancel(notificationIdFor(transferId))
    }

    private fun buildOpenAppPendingIntent(): PendingIntent {
        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        return PendingIntent.getActivity(
            context,
            0,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    private fun buildCancelPendingIntent(transferId: String): PendingIntent {
        val intent = Intent(ACTION_CANCEL_TRANSFER).apply {
            setPackage(context.packageName)
            putExtra(EXTRA_TRANSFER_ID, transferId)
        }
        return PendingIntent.getBroadcast(
            context,
            notificationIdFor(transferId),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    @Suppress("TooGenericExceptionCaught", "SwallowedException")
    fun buildOpenFilePendingIntent(file: File): PendingIntent? {
        if (!file.exists()) return null
        return try {
            val authority = "${context.packageName}.fileprovider"
            val uri = FileProvider.getUriForFile(context, authority, file)
            val extension = file.extension.lowercase(Locale.ROOT)
            val mimeType = MimeTypeMap.getSingleton().getMimeTypeFromExtension(extension) ?: "*/*"

            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, mimeType)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            PendingIntent.getActivity(
                context,
                file.hashCode(),
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
        } catch (e: Exception) {
            null
        }
    }

    companion object {
        const val CHANNEL_ID = "sftp_transfers"
        private const val CHANNEL_NAME = "SFTP Transfers"
        private const val CHANNEL_DESCRIPTION = "Notifications for SFTP file downloads and uploads"

        const val ACTION_CANCEL_TRANSFER = "com.mrndtvndv.term.action.CANCEL_SFTP_TRANSFER"
        const val EXTRA_TRANSFER_ID = "transfer_id"

        private const val NOTIFICATION_ID_BASE = 2000
        private const val HASH_MASK = 0x7FFFFFFF
        private const val ID_MODULO = 100000
        private const val MAX_PROGRESS = 100
        private const val PERCENT_MULTIPLIER = 100f

        fun formatBytes(bytes: Long): String {
            if (bytes <= 0L) return "0 B"
            val units = arrayOf("B", "KB", "MB", "GB", "TB", "PB", "EB")
            val digitGroups = (Math.log10(bytes.toDouble()) / Math.log10(1024.0)).toInt().coerceIn(0, units.size - 1)
            if (digitGroups == 0) return "$bytes B"
            return String.format(
                Locale.US,
                "%.2f %s",
                bytes / Math.pow(1024.0, digitGroups.toDouble()),
                units[digitGroups]
            )
        }
    }
}
