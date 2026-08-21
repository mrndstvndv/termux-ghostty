package com.mrndtvndv.term.ui.sftp.transfer

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import androidx.core.content.ContextCompat
import com.mrndtvndv.term.domain.SftpClient
import com.mrndtvndv.term.domain.SftpFile
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

class SftpTransferManager(
    context: Context? = null,
    val notificationHelper: SftpTransferNotificationHelper? = context?.let { SftpTransferNotificationHelper(it) },
    private val applicationScope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
) {
    private val appContext = context?.applicationContext
    private val jobs = ConcurrentHashMap<String, Job>()

    private val _transfers = MutableStateFlow<List<SftpTransfer>>(emptyList())
    val transfers: StateFlow<List<SftpTransfer>> = _transfers.asStateFlow()

    val active: StateFlow<SftpTransfer?> = _transfers
        .map { list -> list.firstOrNull { it.status == TransferStatus.RUNNING } }
        .stateIn(
            scope = applicationScope,
            started = SharingStarted.Eagerly,
            initialValue = null
        )

    private val cancelReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == SftpTransferNotificationHelper.ACTION_CANCEL_TRANSFER) {
                val transferId = intent.getStringExtra(SftpTransferNotificationHelper.EXTRA_TRANSFER_ID)
                if (!transferId.isNullOrEmpty()) {
                    cancel(transferId)
                }
            }
        }
    }

    init {
        if (appContext != null) {
            val filter = IntentFilter(SftpTransferNotificationHelper.ACTION_CANCEL_TRANSFER)
            ContextCompat.registerReceiver(
                appContext,
                cancelReceiver,
                filter,
                ContextCompat.RECEIVER_NOT_EXPORTED
            )
        }
    }

    fun startDownload(
        sftpClient: SftpClient,
        file: SftpFile,
        cacheDir: File,
        onFileReady: ((File) -> Unit)? = null,
        onError: ((String) -> Unit)? = null,
        ownerKey: String? = null
    ): String {
        val id = UUID.randomUUID().toString()
        val tempDir = File(cacheDir, SFTP_CACHE_DIR_NAME).apply { mkdirs() }
        val localFile = File(tempDir, file.name)

        val transfer = SftpTransfer(
            id = id,
            fileName = file.name,
            remotePath = file.path,
            localFile = localFile,
            totalBytes = file.size,
            transferredBytes = 0L,
            type = TransferType.DOWNLOAD,
            status = TransferStatus.RUNNING,
            isMinimized = false,
            error = null,
            ownerKey = ownerKey
        )

        addTransfer(transfer)
        notificationHelper?.showProgressNotification(transfer)

        val job = applicationScope.launch {
            executeDownload(
                scope = this,
                sftpClient = sftpClient,
                transfer = transfer,
                onFileReady = onFileReady,
                onError = onError
            )
        }
        jobs[id] = job

        return id
    }

    fun startUpload(
        sftpClient: SftpClient,
        source: File,
        remotePath: String,
        onSuccess: (() -> Unit)? = null,
        onError: ((String) -> Unit)? = null,
        ownerKey: String? = null
    ): String {
        val id = UUID.randomUUID().toString()
        val totalBytes = source.length()

        val transfer = SftpTransfer(
            id = id,
            fileName = source.name,
            remotePath = remotePath,
            localFile = source,
            totalBytes = totalBytes,
            transferredBytes = 0L,
            type = TransferType.UPLOAD,
            status = TransferStatus.RUNNING,
            isMinimized = false,
            error = null,
            ownerKey = ownerKey
        )

        addTransfer(transfer)
        notificationHelper?.showProgressNotification(transfer)

        val job = applicationScope.launch {
            executeUpload(
                scope = this,
                sftpClient = sftpClient,
                transfer = transfer,
                onSuccess = onSuccess,
                onError = onError
            )
        }
        jobs[id] = job

        return id
    }

    @Suppress("TooGenericExceptionCaught", "SwallowedException")
    private suspend fun executeDownload(
        scope: CoroutineScope,
        sftpClient: SftpClient,
        transfer: SftpTransfer,
        onFileReady: ((File) -> Unit)?,
        onError: ((String) -> Unit)?
    ) {
        val id = transfer.id
        var lastEmissionTime = 0L
        try {
            sftpClient.downloadFile(transfer.remotePath, transfer.localFile) { bytesRead ->
                if (!scope.isActive) return@downloadFile
                val now = System.currentTimeMillis()
                if (now - lastEmissionTime >= PROGRESS_THROTTLE_MS || bytesRead >= transfer.totalBytes) {
                    lastEmissionTime = now
                    updateProgress(id, bytesRead)
                }
            }
            if (scope.isActive) {
                markCompleted(id)
                withContext(Dispatchers.Main) {
                    onFileReady?.invoke(transfer.localFile)
                }
            }
        } catch (e: CancellationException) {
            markCancelled(id)
        } catch (e: Exception) {
            val errorMessage = e.localizedMessage ?: "Failed to download file"
            markFailed(id, errorMessage)
            withContext(Dispatchers.Main) {
                onError?.invoke(errorMessage)
            }
        } finally {
            jobs.remove(id)
        }
    }

    @Suppress("TooGenericExceptionCaught", "SwallowedException")
    private suspend fun executeUpload(
        scope: CoroutineScope,
        sftpClient: SftpClient,
        transfer: SftpTransfer,
        onSuccess: (() -> Unit)?,
        onError: ((String) -> Unit)?
    ) {
        val id = transfer.id
        var lastEmissionTime = 0L
        try {
            sftpClient.uploadFile(transfer.localFile, transfer.remotePath) { bytesWritten ->
                if (!scope.isActive) return@uploadFile
                val now = System.currentTimeMillis()
                if (now - lastEmissionTime >= PROGRESS_THROTTLE_MS || bytesWritten >= transfer.totalBytes) {
                    lastEmissionTime = now
                    updateProgress(id, bytesWritten)
                }
            }
            if (scope.isActive) {
                markCompleted(id)
                withContext(Dispatchers.Main) {
                    onSuccess?.invoke()
                }
            }
        } catch (e: CancellationException) {
            markCancelled(id)
        } catch (e: Exception) {
            val errorMessage = e.localizedMessage ?: "Failed to upload file"
            markFailed(id, errorMessage)
            withContext(Dispatchers.Main) {
                onError?.invoke(errorMessage)
            }
        } finally {
            jobs.remove(id)
        }
    }

    fun cancel(id: String) {
        val job = jobs.remove(id)
        if (job != null) {
            job.cancel()
        } else {
            markCancelled(id)
        }
    }

    fun minimize(id: String) {
        _transfers.update { list ->
            list.map { transfer ->
                if (transfer.id == id) {
                    transfer.copy(isMinimized = true)
                } else {
                    transfer
                }
            }
        }
    }

    fun restore(id: String) {
        _transfers.update { list ->
            list.map { transfer ->
                if (transfer.id == id) {
                    transfer.copy(isMinimized = false)
                } else {
                    transfer
                }
            }
        }
    }

    fun dismiss(id: String) {
        cancel(id)
        notificationHelper?.cancelNotification(id)
        _transfers.update { list ->
            list.filter { it.id != id }
        }
    }

    fun getTransfer(id: String): SftpTransfer? {
        return _transfers.value.find { it.id == id }
    }

    fun observeTransfer(id: String): Flow<SftpTransfer?> {
        return _transfers
            .map { list -> list.find { it.id == id } }
            .distinctUntilChanged()
    }

    private fun addTransfer(transfer: SftpTransfer) {
        _transfers.update { list ->
            listOf(transfer) + list.filter { it.id != transfer.id }
        }
    }

    private fun updateProgress(id: String, bytesTransferred: Long) {
        _transfers.update { list ->
            list.map { transfer ->
                if (transfer.id == id && transfer.status == TransferStatus.RUNNING) {
                    val updated = transfer.copy(transferredBytes = bytesTransferred)
                    notificationHelper?.showProgressNotification(updated)
                    updated
                } else {
                    transfer
                }
            }
        }
    }

    private fun markCompleted(id: String) {
        _transfers.update { list ->
            list.map { transfer ->
                if (transfer.id == id) {
                    val finalBytes = if (transfer.totalBytes > 0L) {
                        transfer.totalBytes
                    } else {
                        transfer.transferredBytes
                    }
                    val updated = transfer.copy(
                        transferredBytes = finalBytes,
                        status = TransferStatus.COMPLETED,
                        error = null
                    )
                    notificationHelper?.showCompleteNotification(updated)
                    updated
                } else {
                    transfer
                }
            }
        }
    }

    private fun markFailed(id: String, errorMessage: String) {
        _transfers.update { list ->
            list.map { transfer ->
                if (transfer.id == id) {
                    val updated = transfer.copy(
                        status = TransferStatus.FAILED,
                        error = errorMessage
                    )
                    notificationHelper?.showErrorNotification(updated)
                    updated
                } else {
                    transfer
                }
            }
        }
    }

    private fun markCancelled(id: String) {
        _transfers.update { list ->
            list.map { transfer ->
                if (transfer.id == id) {
                    val updated = transfer.copy(status = TransferStatus.CANCELLED)
                    notificationHelper?.cancelNotification(id)
                    updated
                } else {
                    transfer
                }
            }
        }
    }

    companion object {
        private const val PROGRESS_THROTTLE_MS = 200L
        private const val SFTP_CACHE_DIR_NAME = "sftp_cache"

        @Volatile
        private var instance: SftpTransferManager? = null

        fun init(context: Context): SftpTransferManager {
            return instance ?: synchronized(this) {
                instance ?: SftpTransferManager(context.applicationContext).also {
                    instance = it
                }
            }
        }

        fun getInstance(context: Context? = null): SftpTransferManager {
            return instance ?: synchronized(this) {
                instance ?: run {
                    requireNotNull(context) {
                        "SftpTransferManager is not initialized. Call init(context) first."
                    }
                    SftpTransferManager(context.applicationContext).also {
                        instance = it
                    }
                }
            }
        }

        val current: SftpTransferManager?
            get() = instance
    }
}
