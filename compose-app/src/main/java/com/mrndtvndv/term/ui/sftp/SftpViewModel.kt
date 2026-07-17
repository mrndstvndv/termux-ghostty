package com.mrndtvndv.term.ui.sftp

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.mrndtvndv.term.domain.SftpClient
import com.mrndtvndv.term.domain.SftpFile
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.File

sealed interface SftpUiState {
    object Loading : SftpUiState
    data class Success(val currentPath: String, val files: List<SftpFile>) : SftpUiState
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
    private val savedStateHandle: SavedStateHandle
) : ViewModel() {
    private val _uiState = MutableStateFlow<SftpUiState>(SftpUiState.Loading)
    val uiState = _uiState.asStateFlow()

    private val _downloadState = MutableStateFlow<SftpDownloadState?>(null)
    val downloadState = _downloadState.asStateFlow()

    var onPathChanged: ((String) -> Unit)? = null

    var currentPath: String
        get() = savedStateHandle["current_path"] ?: "/"
        set(value) { savedStateHandle["current_path"] = value }

    init {
        navigateTo(currentPath)
    }

    fun navigateTo(path: String) {
        currentPath = path
        viewModelScope.launch {
            _uiState.value = SftpUiState.Loading
            try {
                val list = client.listFiles(path)
                _uiState.value = SftpUiState.Success(path, list)
                onPathChanged?.invoke(path)
            } catch (e: Exception) {
                _uiState.value = SftpUiState.Error(e.localizedMessage ?: "Failed to load directory")
            }
        }
    }

    fun downloadAndOpenFile(
        file: SftpFile,
        cacheDir: File,
        onFileReady: (File) -> Unit,
        onError: (String) -> Unit
    ) {
        viewModelScope.launch {
            _downloadState.value = SftpDownloadState(file.name, 0L, file.size, true)
            try {
                val tempDir = File(cacheDir, "sftp_cache").apply { mkdirs() }
                val localFile = File(tempDir, file.name)
                client.downloadFile(file.path, localFile) { progress ->
                    _downloadState.value = SftpDownloadState(file.name, progress, file.size, true)
                }
                _downloadState.value = null
                onFileReady(localFile)
            } catch (e: java.lang.Exception) {
                _downloadState.value = null
                onError(e.localizedMessage ?: "Failed to download file")
            }
        }
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
