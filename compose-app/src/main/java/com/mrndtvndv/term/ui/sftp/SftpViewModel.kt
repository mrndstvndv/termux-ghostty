package com.mrndtvndv.term.ui.sftp

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.mrndtvndv.term.domain.SftpClient
import com.mrndtvndv.term.domain.SftpFile
import kotlinx.coroutines.Job
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.isActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
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

class SftpViewModel(
    private val client: SftpClient,
    private val savedStateHandle: SavedStateHandle,
    private val initialPath: String = "/",
    private val execCommand: (suspend (String) -> String)? = null
) : ViewModel() {
    private val _uiState = MutableStateFlow<SftpUiState>(SftpUiState.Loading)
    val uiState = _uiState.asStateFlow()

    private val _downloadState = MutableStateFlow<SftpDownloadState?>(null)
    val downloadState = _downloadState.asStateFlow()

    private var downloadJob: Job? = null

    var onPathChanged: ((String) -> Unit)? = null

    var currentPath: String
        get() = savedStateHandle["current_path"] ?: initialPath
        set(value) { savedStateHandle["current_path"] = value }

    init {
        navigateTo(currentPath)
    }

    fun navigateTo(path: String) {
        currentPath = path
        viewModelScope.launch {
            _uiState.value = SftpUiState.Loading
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
                _uiState.value = SftpUiState.Error(e.localizedMessage ?: "Failed to load directory")
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
        downloadJob?.cancel()
        downloadJob = viewModelScope.launch {
            _downloadState.value = SftpDownloadState(file.name, 0L, file.size, true)
            try {
                val tempDir = File(cacheDir, "sftp_cache").apply { mkdirs() }
                val localFile = File(tempDir, file.name)
                client.downloadFile(file.path, localFile) { progress ->
                    if (isActive) {
                        _downloadState.value = SftpDownloadState(file.name, progress, file.size, true)
                    }
                }
                if (isActive) {
                    _downloadState.value = null
                    onFileReady(localFile)
                }
            } catch (e: CancellationException) {
                _downloadState.value = null
            } catch (e: Exception) {
                _downloadState.value = null
                onError(e.localizedMessage ?: "Failed to download file")
            }
        }
    }

    fun cancelDownload() {
        downloadJob?.cancel()
        downloadJob = null
        _downloadState.value = null
    }

    fun refresh() {
        navigateTo(currentPath)
    }

    fun navigateUp() {
        val path = currentPath
        if (path == "/" || path.isEmpty()) return
        val normalized = if (path.endsWith("/")) path.dropLast(1) else path
        val parent = normalized.substringBeforeLast('/').ifEmpty { "/" }
        navigateTo(parent)
    }
}
