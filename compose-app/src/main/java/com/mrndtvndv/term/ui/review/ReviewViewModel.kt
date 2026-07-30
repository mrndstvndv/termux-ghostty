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

    private val _isFullFileMode = MutableStateFlow(false)
    val isFullFileMode = _isFullFileMode.asStateFlow()

    private val _isCommitInProgress = MutableStateFlow(false)
    val isCommitInProgress = _isCommitInProgress.asStateFlow()

    private companion object {
        const val COMMIT_EXIT_MARKER = "__REVIEW_COMMIT_EXIT__"
    }

    init {
        viewModelScope.launch {
            workspaceDir.collect {
                refresh()
            }
        }
    }

    fun toggleFullFileMode() {
        _isFullFileMode.value = !_isFullFileMode.value
        _selectedFile.value?.let { file ->
            loadDiff(file)
        }
    }

    private suspend fun getRepoRoot(dir: String): String {
        return try {
            val command = "export PATH=\$PATH:/opt/homebrew/bin:/usr/local/bin; cd \"$dir\" && git rev-parse --show-toplevel"
            val output = execCommand(command).trim()
            if (output.isNotEmpty() && !output.startsWith("fatal:") && !output.contains("error")) {
                output
            } else {
                dir
            }
        } catch (e: Exception) {
            dir
        }
    }

    fun refresh() {
        viewModelScope.launch {
            _uiState.value = ReviewUiState.Loading
            _selectedFile.value = null
            _selectedFileDiff.value = null
            val dir = workspaceDir.value
            try {
                val repoRoot = getRepoRoot(dir)
                // Ensure we handle PATH correctly on remote machines
                val command = "export PATH=\$PATH:/opt/homebrew/bin:/usr/local/bin; cd \"$repoRoot\" && git status --porcelain"
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
            val contextFlag = if (_isFullFileMode.value) "-U999999 " else ""
            try {
                val repoRoot = getRepoRoot(dir)
                val command = when {
                    file.status == "??" -> {
                        "export PATH=\$PATH:/opt/homebrew/bin:/usr/local/bin; cd \"$repoRoot\" && " +
                            "git diff --no-index ${contextFlag}-- /dev/null \"${file.path}\""
                    }
                    file.isStaged -> {
                        "export PATH=\$PATH:/opt/homebrew/bin:/usr/local/bin; cd \"$repoRoot\" && " +
                            "git diff --cached ${contextFlag}-- \"${file.path}\""
                    }
                    else -> {
                        "export PATH=\$PATH:/opt/homebrew/bin:/usr/local/bin; cd \"$repoRoot\" && " +
                            "git diff ${contextFlag}-- \"${file.path}\""
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
                val repoRoot = getRepoRoot(dir)
                val command = "export PATH=\$PATH:/opt/homebrew/bin:/usr/local/bin; cd \"$repoRoot\" && git add \"${file.path}\""
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
                val repoRoot = getRepoRoot(dir)
                // Try restore first, fallback to reset
                val command = "export PATH=\$PATH:/opt/homebrew/bin:/usr/local/bin; cd \"$repoRoot\" && (git restore --staged -- \"${file.path}\" || git reset HEAD -- \"${file.path}\")"
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
                val repoRoot = getRepoRoot(dir)
                val command = when {
                    file.status == "??" -> {
                        // Delete untracked file
                        "cd \"$repoRoot\" && rm -rf \"${file.path}\""
                    }
                    file.isStaged -> {
                        // First unstage, then restore
                        "export PATH=\$PATH:/opt/homebrew/bin:/usr/local/bin; cd \"$repoRoot\" && (git restore --staged -- \"${file.path}\" || git reset HEAD -- \"${file.path}\") && (git restore -- \"${file.path}\" || git checkout -- \"${file.path}\")"
                    }
                    else -> {
                        // Restore unstaged file
                        "export PATH=\$PATH:/opt/homebrew/bin:/usr/local/bin; cd \"$repoRoot\" && (git restore -- \"${file.path}\" || git checkout -- \"${file.path}\")"
                    }
                }
                execCommand(command)
                refresh()
            } catch (e: Exception) {
                _errorMessage.value = "Failed to discard changes: ${e.localizedMessage}"
            }
        }
    }

    fun commit(message: String) {
        if (_isCommitInProgress.value) return
        val commitMessage = message.trim()
        val stagedFiles = (_uiState.value as? ReviewUiState.Success)?.stagedFiles.orEmpty()
        if (commitMessage.isEmpty()) {
            _errorMessage.value = "Commit message cannot be empty"
            return
        }
        if (stagedFiles.isEmpty()) {
            _errorMessage.value = "Stage at least one file before committing"
            return
        }

        viewModelScope.launch {
            _isCommitInProgress.value = true
            val dir = workspaceDir.value
            try {
                val repoRoot = getRepoRoot(dir)
                val command = "export PATH=\$PATH:/opt/homebrew/bin:/usr/local/bin; " +
                    "cd ${shellQuote(repoRoot)} && " +
                    "git commit -m ${shellQuote(commitMessage)} 2>&1; " +
                    "commitExitCode=\$?; " +
                    "printf '\\n$COMMIT_EXIT_MARKER%s\\n' \"\$commitExitCode\""
                val output = execCommand(command)
                val markerIndex = output.lastIndexOf(COMMIT_EXIT_MARKER)
                val exitCode = if (markerIndex >= 0) {
                    output.substring(markerIndex + COMMIT_EXIT_MARKER.length)
                        .lineSequence()
                        .firstOrNull()
                        ?.trim()
                        ?.toIntOrNull()
                } else {
                    null
                }
                val details = if (markerIndex >= 0) {
                    output.substring(0, markerIndex).trim()
                } else {
                    output.trim()
                }

                if (exitCode == 0) {
                    _errorMessage.value = null
                    refresh()
                } else {
                    val suffix = details.takeIf { it.isNotEmpty() }?.let { ": $it" }.orEmpty()
                    _errorMessage.value = "Commit failed$suffix"
                }
            } catch (e: Exception) {
                _errorMessage.value = "Failed to commit changes: ${e.localizedMessage}"
            } finally {
                _isCommitInProgress.value = false
            }
        }
    }

    fun deselectFile() {
        _selectedFile.value = null
        _selectedFileDiff.value = null
    }

    fun clearErrorMessage() {
        _errorMessage.value = null
    }

    private fun shellQuote(value: String): String = "'${value.replace("'", "'\"'\"'")}'"
}
