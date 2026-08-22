package com.mrndtvndv.term.ui.sftp

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.mrndtvndv.term.domain.SftpClient
import com.mrndtvndv.term.domain.SftpFile
import com.mrndtvndv.term.ui.sftp.transfer.SftpTransfer
import com.mrndtvndv.term.ui.sftp.transfer.SftpTransferManager
import com.mrndtvndv.term.ui.sftp.transfer.TransferType
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.File

sealed interface SftpUiState {
    object Loading : SftpUiState
    data class Success(
        val currentPath: String,
        val files: List<SftpFile>,
        val gitStatuses: Map<String, String> = emptyMap()
    ) : SftpUiState
    data class Error(val message: String) : SftpUiState
}

data class SftpDownloadState(
    val fileName: String,
    val bytesDownloaded: Long,
    val totalBytes: Long,
    val isDownloading: Boolean = false
)

data class SftpUploadState(
    val fileName: String,
    val bytesUploaded: Long,
    val totalBytes: Long,
    val isUploading: Boolean = false
)

class SftpViewModel(
    private val client: SftpClient,
    private val savedStateHandle: SavedStateHandle,
    private val initialPath: String = "/",
    private val execCommand: (suspend (String) -> String)? = null,
    private val transferManager: SftpTransferManager? = SftpTransferManager.current,
    private val ownerKey: String? = null,
) : ViewModel() {
    private val _uiState = MutableStateFlow<SftpUiState>(SftpUiState.Loading)
    val uiState = _uiState.asStateFlow()

    private val _isRefreshing = MutableStateFlow(false)
    val isRefreshing = _isRefreshing.asStateFlow()

    private val _activeDownloadId = MutableStateFlow<String?>(null)
    private val _activeUploadId = MutableStateFlow<String?>(null)

    private val _fallbackTransfers = MutableStateFlow<List<SftpTransfer>>(emptyList())
    val transfers: StateFlow<List<SftpTransfer>> = if (transferManager != null) {
        transferManager.transfers
            .map { list -> list.filter { it.ownerKey == ownerKey } }
            .stateIn(
                scope = viewModelScope,
                started = SharingStarted.Eagerly,
                initialValue = transferManager.transfers.value.filter { it.ownerKey == ownerKey }
            )
    } else {
        _fallbackTransfers.asStateFlow()
    }

    val activeDownload: StateFlow<SftpTransfer?> = transfers
        .map { list -> list.firstOrNull { it.type == TransferType.DOWNLOAD && it.isRunning } }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.Eagerly,
            initialValue = null
        )

    val activeUpload: StateFlow<SftpTransfer?> = transfers
        .map { list -> list.firstOrNull { it.type == TransferType.UPLOAD && it.isRunning } }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.Eagerly,
            initialValue = null
        )

    private val _fallbackDownloadState = MutableStateFlow<SftpDownloadState?>(null)
    val downloadState: StateFlow<SftpDownloadState?> = if (transferManager != null) {
        combine(transfers, _activeDownloadId) { list, activeId ->
            if (activeId == null) return@combine null
            val active = list.firstOrNull {
                it.id == activeId &&
                    it.type == TransferType.DOWNLOAD &&
                    it.isRunning &&
                    !it.isMinimized
            } ?: return@combine null

            SftpDownloadState(
                fileName = active.fileName,
                bytesDownloaded = active.transferredBytes,
                totalBytes = active.totalBytes,
                isDownloading = true
            )
        }.stateIn(
            scope = viewModelScope,
            started = SharingStarted.Eagerly,
            initialValue = null
        )
    } else {
        _fallbackDownloadState.asStateFlow()
    }

    private val _fallbackUploadState = MutableStateFlow<SftpUploadState?>(null)
    val uploadState: StateFlow<SftpUploadState?> = if (transferManager != null) {
        combine(transfers, _activeUploadId) { list, activeId ->
            if (activeId == null) return@combine null
            val active = list.firstOrNull {
                it.id == activeId &&
                    it.type == TransferType.UPLOAD &&
                    it.isRunning &&
                    !it.isMinimized
            } ?: return@combine null

            SftpUploadState(
                fileName = active.fileName,
                bytesUploaded = active.transferredBytes,
                totalBytes = active.totalBytes,
                isUploading = true
            )
        }.stateIn(
            scope = viewModelScope,
            started = SharingStarted.Eagerly,
            initialValue = null
        )
    } else {
        _fallbackUploadState.asStateFlow()
    }

    private var downloadJob: Job? = null
    private var uploadJob: Job? = null

    var onPathChanged: ((String) -> Unit)? = null

    var currentPath: String
        get() = savedStateHandle["current_path"] ?: initialPath
        set(value) { savedStateHandle["current_path"] = value }

    private fun isPathPrefix(prefix: String, fullPath: String): Boolean {
        if (prefix == fullPath || prefix == "/") return true
        val formattedPrefix = if (prefix.endsWith("/")) prefix else "$prefix/"
        return fullPath.startsWith(formattedPrefix)
    }

    private val _trailPath = MutableStateFlow(currentPath)
    val trailPath = _trailPath.asStateFlow()

    init {
        navigateTo(currentPath)
    }

    fun navigateTo(path: String) {
        currentPath = path
        if (!isPathPrefix(path, _trailPath.value)) {
            _trailPath.value = path
        }
        viewModelScope.launch {
            if (_uiState.value !is SftpUiState.Success) {
                _uiState.value = SftpUiState.Loading
            } else {
                _isRefreshing.value = true
            }
            try {
                val list = client.listFiles(path).sortedWith(
                    compareBy<SftpFile> { !it.isDirectory }.thenBy { it.name.lowercase() }
                )
                val gitStatuses = mutableMapOf<String, String>()
                try {
                    val statusOutput = execCommand?.invoke("git -C \"$path\" status --porcelain --ignored=no .")
                    statusOutput?.lines()?.forEach { line ->
                        if (line.length >= 4) { // Status (2 chars), space, then filename
                            val status = line.substring(0, 2)
                            val file = line.substring(3).removeSurrounding("\"")
                            val parts = file.split("/")
                            if (parts.isNotEmpty()) {
                                val topLevelName = parts[0]
                                if (parts.size == 1) {
                                    gitStatuses[topLevelName] = status
                                } else {
                                    gitStatuses[topLevelName] = "M"
                                }
                            }
                        }
                    }
                } catch (e: Exception) {
                    // Ignore git errors (e.g. not a git repo or git not installed)
                }
                _uiState.value = SftpUiState.Success(path, list, gitStatuses)
                onPathChanged?.invoke(path)
            } catch (e: Exception) {
                if (_uiState.value !is SftpUiState.Success) {
                    _uiState.value = SftpUiState.Error(e.localizedMessage ?: "Failed to load directory")
                }
            } finally {
                _isRefreshing.value = false
            }
        }
    }

    @Suppress("SwallowedException")
    fun downloadAndOpenFile(
        file: SftpFile,
        cacheDir: File,
        onFileReady: (File) -> Unit,
        onError: (String) -> Unit
    ) {
        val manager = transferManager
        if (manager != null) {
            var downloadId: String? = null
            downloadId = manager.startDownload(
                sftpClient = client,
                file = file,
                cacheDir = cacheDir,
                ownerKey = ownerKey,
                onFileReady = { localFile ->
                    if (_activeDownloadId.value == downloadId) {
                        _activeDownloadId.value = null
                    }
                    onFileReady(localFile)
                },
                onError = { error ->
                    if (_activeDownloadId.value == downloadId) {
                        _activeDownloadId.value = null
                    }
                    onError(error)
                }
            )
            _activeDownloadId.value = downloadId
            return
        }

        downloadJob?.cancel()
        downloadJob = viewModelScope.launch {
            _fallbackDownloadState.value = SftpDownloadState(file.name, 0L, file.size, true)
            try {
                val tempDir = File(cacheDir, "sftp_cache").apply { mkdirs() }
                val localFile = File(tempDir, file.name)
                client.downloadFile(file.path, localFile) { progress ->
                    if (isActive) {
                        _fallbackDownloadState.value = SftpDownloadState(file.name, progress, file.size, true)
                    }
                }
                if (isActive) {
                    _fallbackDownloadState.value = null
                    onFileReady(localFile)
                }
            } catch (e: CancellationException) {
                _fallbackDownloadState.value = null
            } catch (e: Exception) {
                _fallbackDownloadState.value = null
                onError(e.localizedMessage ?: "Failed to download file")
            }
        }
    }

    fun cancelDownload() {
        val manager = transferManager
        if (manager != null) {
            val id = _activeDownloadId.value ?: activeDownload.value?.id
            if (id != null) {
                manager.cancel(id)
            }
            _activeDownloadId.value = null
            return
        }
        downloadJob?.cancel()
        downloadJob = null
        _fallbackDownloadState.value = null
    }

    @Suppress("SwallowedException")
    fun uploadFile(
        source: File,
        onSuccess: () -> Unit = {},
        onError: (String) -> Unit = {}
    ) {
        val fileName = source.name
        val dir = currentDirectory.trimEnd('/')
        val remotePath = if (dir == "/") "/$fileName" else "$dir/$fileName"

        val manager = transferManager
        if (manager != null) {
            var uploadId: String? = null
            uploadId = manager.startUpload(
                sftpClient = client,
                source = source,
                remotePath = remotePath,
                ownerKey = ownerKey,
                onSuccess = {
                    if (_activeUploadId.value == uploadId) {
                        _activeUploadId.value = null
                    }
                    onSuccess()
                    refresh()
                },
                onError = { error ->
                    if (_activeUploadId.value == uploadId) {
                        _activeUploadId.value = null
                    }
                    onError(error)
                }
            )
            _activeUploadId.value = uploadId
            return
        }

        uploadJob?.cancel()
        uploadJob = viewModelScope.launch {
            _fallbackUploadState.value = SftpUploadState(fileName, 0L, source.length(), true)
            try {
                client.uploadFile(source, remotePath) { progress ->
                    if (isActive) {
                        _fallbackUploadState.value = SftpUploadState(fileName, progress, source.length(), true)
                    }
                }
                if (isActive) {
                    _fallbackUploadState.value = null
                    onSuccess()
                    refresh()
                }
            } catch (e: CancellationException) {
                _fallbackUploadState.value = null
            } catch (e: Exception) {
                _fallbackUploadState.value = null
                onError(e.localizedMessage ?: "Failed to upload file")
            }
        }
    }

    fun cancelUpload() {
        val manager = transferManager
        if (manager != null) {
            val id = _activeUploadId.value ?: activeUpload.value?.id
            if (id != null) {
                manager.cancel(id)
            }
            _activeUploadId.value = null
            return
        }
        uploadJob?.cancel()
        uploadJob = null
        _fallbackUploadState.value = null
    }

    fun backgroundDownload(id: String? = null) {
        val targetId = id ?: _activeDownloadId.value ?: activeDownload.value?.id ?: return
        transferManager?.minimize(targetId)
    }

    fun restoreDownload(id: String? = null) {
        val targetId = id ?: _activeDownloadId.value ?: activeDownload.value?.id ?: return
        _activeDownloadId.value = targetId
        transferManager?.restore(targetId)
    }

    fun backgroundUpload(id: String? = null) {
        val targetId = id ?: _activeUploadId.value ?: activeUpload.value?.id ?: return
        transferManager?.minimize(targetId)
    }

    fun restoreUpload(id: String? = null) {
        val targetId = id ?: _activeUploadId.value ?: activeUpload.value?.id ?: return
        _activeUploadId.value = targetId
        transferManager?.restore(targetId)
    }

    fun cancelTransfer(id: String) {
        transferManager?.cancel(id)
        if (_activeDownloadId.value == id) _activeDownloadId.value = null
        if (_activeUploadId.value == id) _activeUploadId.value = null
    }

    fun dismissTransfer(id: String) {
        transferManager?.dismiss(id)
        if (_activeDownloadId.value == id) _activeDownloadId.value = null
        if (_activeUploadId.value == id) _activeUploadId.value = null
    }

    fun getActiveTransferId(): String? {
        val activeId = _activeDownloadId.value ?: _activeUploadId.value
        if (activeId != null && transferManager?.getTransfer(activeId)?.isRunning == true) {
            return activeId
        }
        return activeDownload.value?.id
            ?: activeUpload.value?.id
            ?: transferManager?.active?.value?.takeIf { it.ownerKey == ownerKey }?.id
    }

    fun refresh() {
        navigateTo(currentPath)
    }

    @Suppress("TooGenericExceptionCaught")
    fun deleteFile(file: SftpFile, onSuccess: () -> Unit = {}, onError: (String) -> Unit = {}) {
        viewModelScope.launch {
            try {
                client.deleteFile(file.path)
                onSuccess()
                refresh()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                onError(e.localizedMessage ?: "Failed to delete ${file.name}")
            }
        }
    }

    @Suppress("TooGenericExceptionCaught")
    fun renameFile(file: SftpFile, newName: String, onSuccess: () -> Unit = {}, onError: (String) -> Unit = {}) {
        val trimmed = newName.trim()
        if (trimmed.isEmpty() || trimmed.contains('/')) {
            onError("Invalid name")
            return
        }
        if (trimmed == file.name) return

        val parent = file.path.substringBeforeLast('/', "")
        val newPath = if (parent.isEmpty()) "/$trimmed" else "$parent/$trimmed"
        viewModelScope.launch {
            try {
                client.renameFile(file.path, newPath)
                onSuccess()
                refresh()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                onError(e.localizedMessage ?: "Failed to rename ${file.name}")
            }
        }
    }

    fun navigateUp() {
        val path = currentPath
        if (path == "/" || path.isEmpty()) return
        val normalized = if (path.endsWith("/")) path.dropLast(1) else path
        val parent = normalized.substringBeforeLast('/').ifEmpty { "/" }
        navigateTo(parent)
    }

    private val currentDirectory: String
        get() {
            val path = currentPath
            return if (path.isBlank()) "/" else path
        }
}
