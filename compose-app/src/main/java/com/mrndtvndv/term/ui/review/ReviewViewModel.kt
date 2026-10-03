package com.mrndtvndv.term.ui.review

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

sealed interface ReviewUiState {
    object Loading : ReviewUiState
    data class Success(
        val stagedFiles: List<GitFileStatus>,
        val unstagedFiles: List<GitFileStatus>,
        val recentCommits: List<GitCommit> = emptyList(),
        val hasMoreCommits: Boolean = true,
        val currentBranch: String = "",
        val branches: List<GitBranch> = emptyList(),
        val aheadCount: Int = 0,
        val behindCount: Int = 0
    ) : ReviewUiState
    data class Error(val message: String) : ReviewUiState
}

data class GitBranch(
    val name: String,
    val isCurrent: Boolean,
    val isRemote: Boolean = false
)

data class GitFileStatus(
    val originalPath: String, // the raw path from git status (might include quotes, renames)
    val path: String,         // the cleaned path of the file
    val status: String,       // "M", "A", "D", "R", "??"
    val isStaged: Boolean
)

data class GitCommit(
    val hash: String,
    val shortHash: String,
    val author: String,
    val relativeDate: String,
    val subject: String,
    val parents: List<String> = emptyList(),
    val refs: List<String> = emptyList()
)

internal sealed interface DiffContentState {
    object Loading : DiffContentState
    data class Ready(val rawDiff: String) : DiffContentState
    data class Error(val message: String) : DiffContentState
}

internal data class ReviewScreenState(
    val content: ReviewUiState = ReviewUiState.Loading,
    val selectedFile: GitFileStatus? = null,
    val selectedCommit: GitCommit? = null,
    val diffContent: DiffContentState? = null,
    val errorMessage: String? = null,
    val isFullFileMode: Boolean = false,
    val showLineNumbers: Boolean = true,
    val isWordDiffEnabled: Boolean = true,
    val isCommitInProgress: Boolean = false,
    val isBranchOperationInProgress: Boolean = false,
    val isSyncInProgress: Boolean = false,
    val isRefreshing: Boolean = false,
    val isStagedExpanded: Boolean = true,
    val isUnstagedExpanded: Boolean = true,
    val isCommitsExpanded: Boolean = true
)

@Suppress("LargeClass", "TooManyFunctions")
class ReviewViewModel(
    private val execCommand: suspend (String) -> String,
    private val workspaceDir: StateFlow<String>
) : ViewModel() {

    private data class BranchSyncStatus(
        val aheadCount: Int = 0,
        val behindCount: Int = 0
    )

    private val _uiState = MutableStateFlow(ReviewScreenState())
    internal val uiState = _uiState.asStateFlow()

    /** Cancels an in-flight diff load when a new file/commit is selected. */
    private var diffLoadJob: Job? = null

    fun toggleStagedExpanded() {
        _uiState.update { it.copy(isStagedExpanded = !it.isStagedExpanded) }
    }

    fun toggleUnstagedExpanded() {
        _uiState.update { it.copy(isUnstagedExpanded = !it.isUnstagedExpanded) }
    }

    fun toggleCommitsExpanded() {
        _uiState.update { it.copy(isCommitsExpanded = !it.isCommitsExpanded) }
    }

    private companion object {
        const val COMMIT_EXIT_MARKER = "__REVIEW_COMMIT_EXIT__"
        const val BRANCH_EXIT_MARKER = "__REVIEW_BRANCH_EXIT__"
        const val SYNC_EXIT_MARKER = "__REVIEW_SYNC_EXIT__"
    }

    init {
        viewModelScope.launch {
            workspaceDir.collect {
                refresh()
            }
        }
    }

    fun toggleFullFileMode() {
        _uiState.update { it.copy(isFullFileMode = !it.isFullFileMode) }
        _uiState.value.selectedFile?.let { file ->
            loadDiff(file)
        }
    }

    fun toggleLineNumbers() {
        _uiState.update { it.copy(showLineNumbers = !it.showLineNumbers) }
    }

    fun toggleWordDiff() {
        _uiState.update { it.copy(isWordDiffEnabled = !it.isWordDiffEnabled) }
    }

    private suspend fun fetchCommits(repoRoot: String, limit: Int = 15, skip: Int = 0): List<GitCommit> {
        return try {
            val command = "export PATH=\$PATH:/opt/homebrew/bin:/usr/local/bin; cd \"$repoRoot\" && " +
                "git log --topo-order --skip=$skip -n $limit " +
                "--pretty=format:\"%H%x1f%h%x1f%an%x1f%ar%x1f%P%x1f%D%x1f%s\""
            val output = execCommand(command)
            output.lines().filter { it.isNotBlank() }.mapNotNull { line ->
                val parts = line.split('\u001f')
                if (parts.size >= 7) {
                    GitCommit(
                        hash = parts[0],
                        shortHash = parts[1],
                        author = parts[2],
                        relativeDate = parts[3],
                        subject = parts[6],
                        parents = parts[4].split(' ').filter { it.isNotEmpty() },
                        refs = parts[5].split(", ").filter { it.isNotEmpty() && !it.endsWith("/HEAD") }
                    )
                } else {
                    null
                }
            }
        } catch (e: Exception) {
            emptyList()
        }
    }

    fun loadMoreCommits() {
        val currentState = _uiState.value.content as? ReviewUiState.Success ?: return
        val currentCommits = currentState.recentCommits
        viewModelScope.launch {
            val dir = workspaceDir.value
            val repoRoot = getRepoRoot(execCommand, dir)
            val nextCommits = fetchCommits(repoRoot, limit = 15, skip = currentCommits.size)
            val updatedList = currentCommits + nextCommits
            setContent(
                currentState.copy(
                    recentCommits = updatedList,
                    hasMoreCommits = nextCommits.size == 15
                )
            )
        }
    }

    @Suppress("NestedBlockDepth")
    private suspend fun fetchBranchInfo(repoRoot: String): Pair<String, List<GitBranch>> {
        return try {
            val pathEnv = "export PATH=\$PATH:/opt/homebrew/bin:/usr/local/bin; "
            val currentCmd = "$pathEnv cd ${shellQuote(repoRoot)} && git rev-parse --abbrev-ref HEAD"
            val rawCurrent = execCommand(currentCmd).trim()
            val currentBranchName = if (rawCurrent == "HEAD") {
                val shortHashCmd = "$pathEnv cd ${shellQuote(repoRoot)} && git rev-parse --short HEAD"
                val hash = try { execCommand(shortHashCmd).trim() } catch (e: Exception) { "" }
                if (hash.isNotEmpty()) "HEAD ($hash)" else "HEAD"
            } else {
                rawCurrent
            }

            val listCmd = "$pathEnv cd ${shellQuote(repoRoot)} && " +
                "git branch -a --format=\"%(refname)|%(refname:short)|%(HEAD)\""
            val output = execCommand(listCmd)
            val branches = mutableListOf<GitBranch>()

            output.lines().forEach { line ->
                val parts = line.split('|')
                if (parts.size >= 3) {
                    val refName = parts[0].trim()
                    val shortName = parts[1].trim()
                    val isHead = parts[2].trim() == "*"

                    if (refName.startsWith("refs/heads/")) {
                        branches.add(
                            GitBranch(
                                name = shortName,
                                isCurrent = isHead || shortName == rawCurrent,
                                isRemote = false
                            )
                        )
                    } else if (refName.startsWith("refs/remotes/")) {
                        if (!shortName.endsWith("/HEAD")) {
                            branches.add(
                                GitBranch(
                                    name = shortName,
                                    isCurrent = isHead,
                                    isRemote = true
                                )
                            )
                        }
                    }
                }
            }
            Pair(currentBranchName, branches)
        } catch (e: Exception) {
            Pair("", emptyList())
        }
    }

    private suspend fun fetchBranchSyncStatus(repoRoot: String): BranchSyncStatus {
        return try {
            val pathEnv = "export PATH=\$PATH:/opt/homebrew/bin:/usr/local/bin; "
            val command = "$pathEnv cd ${shellQuote(repoRoot)} && " +
                "git rev-list --left-right --count HEAD...@{upstream}"
            val counts = execCommand(command).trim().split(Regex("\\s+"))
            if (counts.size < 2) return BranchSyncStatus()

            BranchSyncStatus(
                aheadCount = counts[0].toIntOrNull() ?: 0,
                behindCount = counts[1].toIntOrNull() ?: 0
            )
        } catch (e: Exception) {
            BranchSyncStatus()
        }
    }

    @Suppress("LongMethod")
    private val gitReset = GitResetOperations(execCommand, workspaceDir)

    fun refresh() {
        viewModelScope.launch {
            if (_uiState.value.content !is ReviewUiState.Success) {
                setContent(ReviewUiState.Loading)
            } else {
                _uiState.update { it.copy(isRefreshing = true) }
            }

            val dir = workspaceDir.value
            try {
                val repoRoot = getRepoRoot(execCommand, dir)
                val command = "export PATH=\$PATH:/opt/homebrew/bin:/usr/local/bin; " +
                    "cd \"$repoRoot\" && git status --porcelain"
                val output = execCommand(command)
                val (staged, unstaged) = parseStatusOutput(output)

                val currentSelFile = _uiState.value.selectedFile
                if (currentSelFile != null) {
                    val matchingFile = (staged + unstaged).find {
                        it.path == currentSelFile.path && it.isStaged == currentSelFile.isStaged
                    }
                    if (matchingFile != null) {
                        _uiState.update { it.copy(selectedFile = matchingFile) }
                    } else {
                        _uiState.update { it.copy(selectedFile = null, diffContent = null) }
                    }
                }
                
                val initialCommits = fetchCommits(repoRoot, limit = 15, skip = 0)
                val (currentBranch, branches) = fetchBranchInfo(repoRoot)
                val branchSyncStatus = fetchBranchSyncStatus(repoRoot)
                setContent(
                    ReviewUiState.Success(
                        stagedFiles = staged,
                        unstagedFiles = unstaged,
                        recentCommits = initialCommits,
                        hasMoreCommits = initialCommits.size == 15,
                        currentBranch = currentBranch,
                        branches = branches,
                        aheadCount = branchSyncStatus.aheadCount,
                        behindCount = branchSyncStatus.behindCount
                    )
                )
            } catch (e: Exception) {
                if (_uiState.value.content !is ReviewUiState.Success) {
                    setContent(ReviewUiState.Error(e.localizedMessage ?: "Failed to get git status"))
                } else {
                    _uiState.update { it.copy(errorMessage = e.localizedMessage ?: "Failed to update git status") }
                }
            } finally {
                _uiState.update { it.copy(isRefreshing = false) }
            }
        }
    }

    private fun parseStatusOutput(output: String): Pair<List<GitFileStatus>, List<GitFileStatus>> {
        val staged = mutableListOf<GitFileStatus>()
        val unstaged = mutableListOf<GitFileStatus>()

        output.lines().forEach { rawLine ->
            val line = rawLine.trimEnd('\r')
            if (line.length < 4) return@forEach
            val col0 = line[0]
            val col1 = line[1]
            val rawPath = line.substring(3).trim()

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
        return staged to unstaged
    }

    fun selectFile(file: GitFileStatus) {
        _uiState.update { it.copy(selectedFile = file, selectedCommit = null) }
        loadDiff(file)
    }

    fun selectCommit(commit: GitCommit) {
        _uiState.update { it.copy(selectedFile = null, selectedCommit = commit) }
        loadCommitDiff(commit)
    }

    fun deselectFile() {
        _uiState.update { it.copy(selectedFile = null, selectedCommit = null, diffContent = null) }
    }

    private fun setContent(content: ReviewUiState) {
        _uiState.update { it.copy(content = content) }
    }

    private fun setDiffContent(diffContent: DiffContentState?) {
        _uiState.update { it.copy(diffContent = diffContent) }
    }

    private fun loadCommitDiff(commit: GitCommit) {
        diffLoadJob?.cancel()
        diffLoadJob = viewModelScope.launch {
            setDiffContent(DiffContentState.Loading)
            val dir = workspaceDir.value
            try {
                val repoRoot = getRepoRoot(execCommand, dir)
                val command = buildCommitDiffCommand(repoRoot, commit.hash)
                val diffOutput = execCommand(command)
                setDiffContent(DiffContentState.Ready(diffOutput))
            } catch (e: Exception) {
                setDiffContent(DiffContentState.Error("Failed to load commit diff: ${e.localizedMessage}"))
            }
        }
    }

    private fun loadDiff(file: GitFileStatus) {
        diffLoadJob?.cancel()
        diffLoadJob = viewModelScope.launch {
            setDiffContent(DiffContentState.Loading)
            val dir = workspaceDir.value
            val contextFlag = if (_uiState.value.isFullFileMode) "-U999999 " else ""
            try {
                val repoRoot = getRepoRoot(execCommand, dir)
                val command = buildDiffCommand(repoRoot, file, contextFlag)
                val diffOutput = execCommand(command)
                setDiffContent(DiffContentState.Ready(diffOutput))
            } catch (e: Exception) {
                setDiffContent(DiffContentState.Error("Failed to load diff: ${e.localizedMessage}"))
            }
        }
    }

    fun stageFiles(files: List<GitFileStatus>) {
        if (files.isEmpty()) return
        viewModelScope.launch {
            val dir = workspaceDir.value
            try {
                val repoRoot = getRepoRoot(execCommand, dir)
                val pathsArg = files.joinToString(" ") { "\"${it.path}\"" }
                val command = "export PATH=\$PATH:/opt/homebrew/bin:/usr/local/bin; " +
                    "cd \"$repoRoot\" && git add -- $pathsArg"
                execCommand(command)
                refresh()
            } catch (e: Exception) {
                _uiState.update { it.copy(errorMessage = "Failed to stage files: ${e.localizedMessage}") }
            }
        }
    }

    fun unstageFiles(files: List<GitFileStatus>) {
        if (files.isEmpty()) return
        viewModelScope.launch {
            val dir = workspaceDir.value
            try {
                val repoRoot = getRepoRoot(execCommand, dir)
                val pathsArg = files.joinToString(" ") { "\"${it.path}\"" }
                val command = "export PATH=\$PATH:/opt/homebrew/bin:/usr/local/bin; " +
                    "cd \"$repoRoot\" && (git restore --staged -- $pathsArg || git reset HEAD -- $pathsArg)"
                execCommand(command)
                refresh()
            } catch (e: Exception) {
                _uiState.update { it.copy(errorMessage = "Failed to unstage files: ${e.localizedMessage}") }
            }
        }
    }

    fun discardFiles(files: List<GitFileStatus>) {
        if (files.isEmpty()) return
        viewModelScope.launch {
            val dir = workspaceDir.value
            try {
                val repoRoot = getRepoRoot(execCommand, dir)
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
                _uiState.update { it.copy(errorMessage = "Failed to discard files: ${e.localizedMessage}") }
            }
        }
    }

    fun stageFile(file: GitFileStatus) = stageFiles(listOf(file))
    fun unstageFile(file: GitFileStatus) = unstageFiles(listOf(file))
    fun discardFileChanges(file: GitFileStatus) = discardFiles(listOf(file))

    fun commit(message: String) {
        if (_uiState.value.isCommitInProgress) return
        val commitMessage = message.trim()
        val stagedFiles = (_uiState.value.content as? ReviewUiState.Success)?.stagedFiles.orEmpty()
        if (commitMessage.isEmpty()) {
            _uiState.update { it.copy(errorMessage = "Commit message cannot be empty") }
            return
        }
        if (stagedFiles.isEmpty()) {
            _uiState.update { it.copy(errorMessage = "Stage at least one file before committing") }
            return
        }

        viewModelScope.launch {
            _uiState.update { it.copy(isCommitInProgress = true) }
            val dir = workspaceDir.value
            try {
                val repoRoot = getRepoRoot(execCommand, dir)
                val command = "export PATH=\$PATH:/opt/homebrew/bin:/usr/local/bin; " +
                    "cd ${shellQuote(repoRoot)} && " +
                    "git commit -m ${shellQuote(commitMessage)} 2>&1; " +
                    "commitExitCode=\$?; " +
                    "printf '\\n$COMMIT_EXIT_MARKER%s\\n' \"\$commitExitCode\""
                val output = execCommand(command)
                val (exitCode, details) = parseExitMarker(output, COMMIT_EXIT_MARKER)

                if (exitCode == 0) {
                    _uiState.update { it.copy(errorMessage = null) }
                    refresh()
                } else {
                    val suffix = details.takeIf { it.isNotEmpty() }?.let { ": $it" }.orEmpty()
                    _uiState.update { it.copy(errorMessage = "Commit failed$suffix") }
                }
            } catch (e: Exception) {
                _uiState.update { it.copy(errorMessage = "Failed to commit changes: ${e.localizedMessage}") }
            } finally {
                _uiState.update { it.copy(isCommitInProgress = false) }
            }
        }
    }

    @Suppress("LongMethod")
    fun renameCommit(commit: GitCommit, newSubject: String) {
        viewModelScope.launch {
            _uiState.update { it.copy(isCommitInProgress = true) }
            val dir = workspaceDir.value
            try {
                val repoRoot = getRepoRoot(execCommand, dir)
                val escapedSubject = shellQuote(newSubject)
                val pathEnv = "export PATH=\$PATH:/opt/homebrew/bin:/usr/local/bin; "
                val headHash = try {
                    execCommand(
                        pathEnv + "cd ${shellQuote(repoRoot)} && git rev-parse HEAD"
                    ).trim()
                } catch (e: Exception) {
                    ""
                }
                val isHead = headHash == commit.hash ||
                    (headHash.isNotEmpty() && headHash.startsWith(commit.shortHash))

                val command = if (isHead) {
                    pathEnv + "cd ${shellQuote(repoRoot)} && " +
                        "git commit --amend -m $escapedSubject"
                } else {
                    pathEnv + "cd ${shellQuote(repoRoot)} && " +
                        "git rebase -x \"if [ \\\$(git rev-parse HEAD) = '${commit.hash}' ]; " +
                        "then git commit --amend -m $escapedSubject; fi\" \"${commit.hash}^\""
                }
                execCommand(command)
                refresh()
            } catch (e: Exception) {
                _uiState.update { it.copy(errorMessage = "Failed to rename commit: ${e.localizedMessage}") }
            } finally {
                _uiState.update { it.copy(isCommitInProgress = false) }
            }
        }
    }

    fun softReset(commit: GitCommit) {
        runReset(commit) { gitReset.softReset(it) }
    }

    fun hardReset(commit: GitCommit) {
        runReset(commit) { gitReset.hardReset(it) }
    }

    private fun runReset(commit: GitCommit, operation: suspend (GitCommit) -> String?) {
        if (_uiState.value.isCommitInProgress) return
        viewModelScope.launch {
            _uiState.update { it.copy(isCommitInProgress = true) }
            try {
                val error = operation(commit)
                if (error == null) {
                    _uiState.update { it.copy(errorMessage = null) }
                    refresh()
                } else {
                    _uiState.update { it.copy(errorMessage = error) }
                }
            } catch (e: Exception) {
                _uiState.update { it.copy(errorMessage = "Failed to reset: ${e.localizedMessage}") }
            } finally {
                _uiState.update { it.copy(isCommitInProgress = false) }
            }
        }
    }

    @Suppress("LongMethod", "CyclomaticComplexMethod")
    fun checkoutBranch(branch: GitBranch) {
        if (_uiState.value.isBranchOperationInProgress) return
        viewModelScope.launch {
            _uiState.update { it.copy(isBranchOperationInProgress = true) }
            val dir = workspaceDir.value
            try {
                val repoRoot = getRepoRoot(execCommand, dir)
                val targetName = branch.name
                val pathEnv = "export PATH=\$PATH:/opt/homebrew/bin:/usr/local/bin; "
                val command = if (branch.isRemote) {
                    val localCandidate = targetName.substringAfter('/')
                    pathEnv + "cd ${shellQuote(repoRoot)} && " +
                        "(git checkout ${shellQuote(localCandidate)} || " +
                        "git checkout ${shellQuote(targetName)}) 2>&1; " +
                        "branchExitCode=\$?; " +
                        "printf '\\n$BRANCH_EXIT_MARKER%s\\n' \"\$branchExitCode\""
                } else {
                    pathEnv + "cd ${shellQuote(repoRoot)} && " +
                        "git checkout ${shellQuote(targetName)} 2>&1; " +
                        "branchExitCode=\$?; " +
                        "printf '\\n$BRANCH_EXIT_MARKER%s\\n' \"\$branchExitCode\""
                }

                val output = execCommand(command)
                val (exitCode, details) = parseExitMarker(output, BRANCH_EXIT_MARKER)

                if (exitCode == 0) {
                    _uiState.update { it.copy(errorMessage = null) }
                    refresh()
                } else {
                    val suffix = details.takeIf { it.isNotEmpty() }?.let { ": $it" }.orEmpty()
                    _uiState.update { it.copy(errorMessage = "Failed to switch branch$suffix") }
                }
            } catch (e: Exception) {
                _uiState.update { it.copy(errorMessage = "Failed to switch branch: ${e.localizedMessage}") }
            } finally {
                _uiState.update { it.copy(isBranchOperationInProgress = false) }
            }
        }
    }

    @Suppress("LongMethod")
    fun createAndCheckoutBranch(newBranchName: String) {
        if (_uiState.value.isBranchOperationInProgress) return
        val name = newBranchName.trim()
        if (name.isEmpty()) {
            _uiState.update { it.copy(errorMessage = "Branch name cannot be empty") }
            return
        }
        viewModelScope.launch {
            _uiState.update { it.copy(isBranchOperationInProgress = true) }
            val dir = workspaceDir.value
            try {
                val repoRoot = getRepoRoot(execCommand, dir)
                val pathEnv = "export PATH=\$PATH:/opt/homebrew/bin:/usr/local/bin; "
                val command = pathEnv + "cd ${shellQuote(repoRoot)} && " +
                    "git checkout -b ${shellQuote(name)} 2>&1; " +
                    "branchExitCode=\$?; " +
                    "printf '\\n$BRANCH_EXIT_MARKER%s\\n' \"\$branchExitCode\""

                val output = execCommand(command)
                val (exitCode, details) = parseExitMarker(output, BRANCH_EXIT_MARKER)

                if (exitCode == 0) {
                    _uiState.update { it.copy(errorMessage = null) }
                    refresh()
                } else {
                    val suffix = details.takeIf { it.isNotEmpty() }?.let { ": $it" }.orEmpty()
                    _uiState.update { it.copy(errorMessage = "Failed to create branch$suffix") }
                }
            } catch (e: Exception) {
                _uiState.update { it.copy(errorMessage = "Failed to create branch: ${e.localizedMessage}") }
            } finally {
                _uiState.update { it.copy(isBranchOperationInProgress = false) }
            }
        }
    }

    fun clearErrorMessage() {
        _uiState.update { it.copy(errorMessage = null) }
    }

    fun fetchRemote() {
        runSyncOperation("Fetch", "git fetch")
    }

    fun pullBranch() {
        runSyncOperation("Pull", "git pull --ff-only")
    }

    fun pushBranch() {
        runSyncOperation("Push", "git push")
    }

    private fun runSyncOperation(label: String, gitCommand: String) {
        if (_uiState.value.isSyncInProgress) return
        viewModelScope.launch {
            _uiState.update { it.copy(isSyncInProgress = true) }
            try {
                val dir = workspaceDir.value
                val repoRoot = getRepoRoot(execCommand, dir)
                val pathEnv = "export PATH=\$PATH:/opt/homebrew/bin:/usr/local/bin; "
                val command = pathEnv + "cd ${shellQuote(repoRoot)} && " +
                    "$gitCommand 2>&1; " +
                    "syncExitCode=\$?; " +
                    "printf '\\n$SYNC_EXIT_MARKER%s\\n' \"\$syncExitCode\""

                val output = execCommand(command)
                val (exitCode, details) = parseExitMarker(output, SYNC_EXIT_MARKER)

                if (exitCode == 0) {
                    _uiState.update { it.copy(errorMessage = null) }
                    refresh()
                } else {
                    val suffix = details.takeIf { it.isNotEmpty() }?.let { ": $it" }.orEmpty()
                    _uiState.update { it.copy(errorMessage = "$label failed$suffix") }
                }
            } catch (e: Exception) {
                _uiState.update { it.copy(errorMessage = "$label failed: ${e.localizedMessage}") }
            } finally {
                _uiState.update { it.copy(isSyncInProgress = false) }
            }
        }
    }
}

private fun parseExitMarker(output: String, marker: String): Pair<Int?, String> {
    val markerIndex = output.lastIndexOf(marker)
    if (markerIndex < 0) return null to output.trim()
    val exitCode = output.substring(markerIndex + marker.length)
        .lineSequence()
        .firstOrNull()
        ?.trim()
        ?.toIntOrNull()
    return exitCode to output.substring(0, markerIndex).trim()
}

internal fun buildCommitDiffCommand(repoRoot: String, commitHash: String): String {
    val quotedRepo = shellQuote(repoRoot)
    val quotedHash = shellQuote(commitHash)
    return "export PATH=\$PATH:/opt/homebrew/bin:/usr/local/bin; cd $quotedRepo && " +
        "git show --stat -p $quotedHash"
}

internal fun buildDiffCommand(
    repoRoot: String,
    file: GitFileStatus,
    contextFlag: String = ""
): String {
    val quotedRepo = shellQuote(repoRoot)
    val quotedPath = shellQuote(file.path)
    val pathEnv = "export PATH=\$PATH:/opt/homebrew/bin:/usr/local/bin; "
    return when {
        file.status == "??" -> {
            "$pathEnv cd $quotedRepo && " +
                "if [ ! -e $quotedPath ]; then " +
                "echo 'File not found' >&2; exit 1; " +
                "elif [ -d $quotedPath ]; then " +
                "EMPTY_TMP=\$(mktemp -d) && " +
                "git diff --no-index ${contextFlag}-- \"\$EMPTY_TMP\" $quotedPath; " +
                "diff_status=\$?; " +
                "rmdir \"\$EMPTY_TMP\" 2>/dev/null; " +
                "test \"\$diff_status\" -le 1; " +
                "else " +
                "git diff --no-index ${contextFlag}-- /dev/null $quotedPath; " +
                "test \"\$?\" -le 1; " +
                "fi"
        }
        file.isStaged -> {
            "$pathEnv cd $quotedRepo && git diff --cached ${contextFlag}-- $quotedPath"
        }
        else -> {
            "$pathEnv cd $quotedRepo && git diff ${contextFlag}-- $quotedPath"
        }
    }
}
