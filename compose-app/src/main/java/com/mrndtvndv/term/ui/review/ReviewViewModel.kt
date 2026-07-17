package com.mrndtvndv.term.ui.review

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

sealed interface ReviewUiState {
    object Loading : ReviewUiState
    data class Success(
        val stagedFiles: List<GitFileStatus>,
        val unstagedFiles: List<GitFileStatus>
    ) : ReviewUiState
    data class Error(val message: String) : ReviewUiState
}

data class GitFileStatus(
    val originalPath: String, // the raw path from git status (might include quotes, renames)
    val path: String,         // the cleaned path of the file
    val status: String,       // "M", "A", "D", "R", "??"
    val isStaged: Boolean
)

class ReviewViewModel(
    private val execCommand: suspend (String) -> String,
    private val workspaceDir: StateFlow<String>
) : ViewModel() {

    private val _uiState = MutableStateFlow<ReviewUiState>(ReviewUiState.Loading)
    val uiState = _uiState.asStateFlow()

    private val _selectedFile = MutableStateFlow<GitFileStatus?>(null)
    val selectedFile = _selectedFile.asStateFlow()

    private val _selectedFileDiff = MutableStateFlow<String?>(null)
    val selectedFileDiff = _selectedFileDiff.asStateFlow()

    private val _isDiffLoading = MutableStateFlow(false)
    val isDiffLoading = _isDiffLoading.asStateFlow()

    private val _errorMessage = MutableStateFlow<String?>(null)
    val errorMessage = _errorMessage.asStateFlow()

    init {
        viewModelScope.launch {
            workspaceDir.collect {
                refresh()
            }
        }
    }

    fun refresh() {
        viewModelScope.launch {
            _uiState.value = ReviewUiState.Loading
            _selectedFile.value = null
            _selectedFileDiff.value = null
            val dir = workspaceDir.value
            try {
                // Ensure we handle PATH correctly on remote machines
                val command = "export PATH=\$PATH:/opt/homebrew/bin:/usr/local/bin; cd \"$dir\" && git status --porcelain"
                val output = execCommand(command)
                
                val staged = mutableListOf<GitFileStatus>()
                val unstaged = mutableListOf<GitFileStatus>()

                output.split("\n").forEach { line ->
                    if (line.length >= 4) {
                        val col0 = line[0]
                        val col1 = line[1]
                        val rawPath = line.substring(3).trim()
                        
                        // Parse path, handle quotes and renames
                        val cleanPath = if (rawPath.contains(" -> ")) {
                            rawPath.substringAfter(" -> ").trim().removeSurrounding("\"")
                        } else {
                            rawPath.removeSurrounding("\"")
                        }

                        if (col0 != ' ' && col0 != '?') {
                            staged.add(
                                GitFileStatus(
                                    originalPath = rawPath,
                                    path = cleanPath,
                                    status = col0.toString(),
                                    isStaged = true
                                )
                            )
                        }
                        if (col1 != ' ' || col0 == '?') {
                            val status = if (col0 == '?') "??" else col1.toString()
                            unstaged.add(
                                GitFileStatus(
                                    originalPath = rawPath,
                                    path = cleanPath,
                                    status = status,
                                    isStaged = false
                                )
                            )
                        }
                    }
                }
                
                _uiState.value = ReviewUiState.Success(staged, unstaged)
            } catch (e: Exception) {
                _uiState.value = ReviewUiState.Error(e.localizedMessage ?: "Failed to get git status")
            }
        }
    }

    fun selectFile(file: GitFileStatus) {
        _selectedFile.value = file
        loadDiff(file)
    }

    private fun loadDiff(file: GitFileStatus) {
        viewModelScope.launch {
            _isDiffLoading.value = true
            _selectedFileDiff.value = null
            val dir = workspaceDir.value
            try {
                val command = when {
                    file.status == "??" -> {
                        // Untracked file: use git diff --no-index /dev/null <file>
                        "export PATH=\$PATH:/opt/homebrew/bin:/usr/local/bin; cd \"$dir\" && git diff --no-index -- /dev/null \"${file.path}\""
                    }
                    file.isStaged -> {
                        // Staged file: use git diff --cached
                        "export PATH=\$PATH:/opt/homebrew/bin:/usr/local/bin; cd \"$dir\" && git diff --cached -- \"${file.path}\""
                    }
                    else -> {
                        // Unstaged file: use git diff
                        "export PATH=\$PATH:/opt/homebrew/bin:/usr/local/bin; cd \"$dir\" && git diff -- \"${file.path}\""
                    }
                }
                val diffOutput = execCommand(command)
                _selectedFileDiff.value = diffOutput
            } catch (e: Exception) {
                _selectedFileDiff.value = "Failed to load diff: ${e.localizedMessage}"
            } finally {
                _isDiffLoading.value = false
            }
        }
    }

    fun stageFile(file: GitFileStatus) {
        viewModelScope.launch {
            val dir = workspaceDir.value
            try {
                val command = "export PATH=\$PATH:/opt/homebrew/bin:/usr/local/bin; cd \"$dir\" && git add \"${file.path}\""
                execCommand(command)
                refresh()
            } catch (e: Exception) {
                _errorMessage.value = "Failed to stage file: ${e.localizedMessage}"
            }
        }
    }

    fun unstageFile(file: GitFileStatus) {
        viewModelScope.launch {
            val dir = workspaceDir.value
            try {
                // Try restore first, fallback to reset
                val command = "export PATH=\$PATH:/opt/homebrew/bin:/usr/local/bin; cd \"$dir\" && (git restore --staged -- \"${file.path}\" || git reset HEAD -- \"${file.path}\")"
                execCommand(command)
                refresh()
            } catch (e: Exception) {
                _errorMessage.value = "Failed to unstage file: ${e.localizedMessage}"
            }
        }
    }

    fun discardFileChanges(file: GitFileStatus) {
        viewModelScope.launch {
            val dir = workspaceDir.value
            try {
                val command = when {
                    file.status == "??" -> {
                        // Delete untracked file
                        "cd \"$dir\" && rm -rf \"${file.path}\""
                    }
                    file.isStaged -> {
                        // First unstage, then restore
                        "export PATH=\$PATH:/opt/homebrew/bin:/usr/local/bin; cd \"$dir\" && (git restore --staged -- \"${file.path}\" || git reset HEAD -- \"${file.path}\") && (git restore -- \"${file.path}\" || git checkout -- \"${file.path}\")"
                    }
                    else -> {
                        // Restore unstaged file
                        "export PATH=\$PATH:/opt/homebrew/bin:/usr/local/bin; cd \"$dir\" && (git restore -- \"${file.path}\" || git checkout -- \"${file.path}\")"
                    }
                }
                execCommand(command)
                refresh()
            } catch (e: Exception) {
                _errorMessage.value = "Failed to discard changes: ${e.localizedMessage}"
            }
        }
    }

    fun clearErrorMessage() {
        _errorMessage.value = null
    }
}
