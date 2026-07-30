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

                output.lines().forEach { rawLine ->
                    val line = rawLine.trimEnd('\r')
                    if (line.length >= 4) {
                        val col0 = line[0]
                        val col1 = line[1]
                        val rawPath = line.substring(3).trim()
                        
                        // Parse path, handle quotes and renames
                        val cleanPath = if (rawPath.contains(" -> ")) {
                            rawPath.substringAfter(" -> ").trim().removeSurrounding("\"").trimEnd('/')
                        } else {
                            rawPath.removeSurrounding("\"").trimEnd('/')
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

    fun stageFiles(files: List<GitFileStatus>) {
        if (files.isEmpty()) return
        viewModelScope.launch {
            val dir = workspaceDir.value
            try {
                val repoRoot = getRepoRoot(dir)
                val pathsArg = files.joinToString(" ") { "\"${it.path}\"" }
                val command = "export PATH=\$PATH:/opt/homebrew/bin:/usr/local/bin; " +
                    "cd \"$repoRoot\" && git add -- $pathsArg"
                execCommand(command)
                refresh()
            } catch (e: Exception) {
                _errorMessage.value = "Failed to stage files: ${e.localizedMessage}"
            }
        }
    }

    fun unstageFiles(files: List<GitFileStatus>) {
        if (files.isEmpty()) return
        viewModelScope.launch {
            val dir = workspaceDir.value
            try {
                val repoRoot = getRepoRoot(dir)
                val pathsArg = files.joinToString(" ") { "\"${it.path}\"" }
                val command = "export PATH=\$PATH:/opt/homebrew/bin:/usr/local/bin; " +
                    "cd \"$repoRoot\" && (git restore --staged -- $pathsArg || git reset HEAD -- $pathsArg)"
                execCommand(command)
                refresh()
            } catch (e: Exception) {
                _errorMessage.value = "Failed to unstage files: ${e.localizedMessage}"
            }
        }
    }

    fun discardFiles(files: List<GitFileStatus>) {
        if (files.isEmpty()) return
        viewModelScope.launch {
            val dir = workspaceDir.value
            try {
                val repoRoot = getRepoRoot(dir)
                val untracked = files.filter { it.status == "??" }
                val staged = files.filter { it.isStaged && it.status != "??" }
                val unstaged = files.filter { !it.isStaged && it.status != "??" }

                val commands = mutableListOf<String>()
                if (untracked.isNotEmpty()) {
                    val pathsArg = untracked.joinToString(" ") { "\"${it.path}\"" }
                    commands.add("rm -rf $pathsArg")
                }
                if (staged.isNotEmpty()) {
                    val pathsArg = staged.joinToString(" ") { "\"${it.path}\"" }
                    commands.add("(git restore --staged -- $pathsArg || git reset HEAD -- $pathsArg) && " +
                        "(git restore -- $pathsArg || git checkout -- $pathsArg)")
                }
                if (unstaged.isNotEmpty()) {
                    val pathsArg = unstaged.joinToString(" ") { "\"${it.path}\"" }
                    commands.add("(git restore -- $pathsArg || git checkout -- $pathsArg)")
                }

                if (commands.isNotEmpty()) {
                    val fullCommand = "export PATH=\$PATH:/opt/homebrew/bin:/usr/local/bin; " +
                        "cd \"$repoRoot\" && " + commands.joinToString(" && ")
                    execCommand(fullCommand)
                }
                refresh()
            } catch (e: Exception) {
                _errorMessage.value = "Failed to discard files: ${e.localizedMessage}"
            }
        }
    }

    fun stageFile(file: GitFileStatus) = stageFiles(listOf(file))
    fun unstageFile(file: GitFileStatus) = unstageFiles(listOf(file))
    fun discardFileChanges(file: GitFileStatus) = discardFiles(listOf(file))

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
