@file:Suppress("TooManyFunctions")

package com.mrndtvndv.term.ui.review

import androidx.compose.animation.AnimatedVisibility
import androidx.navigation3.runtime.NavBackStack
import androidx.navigation3.runtime.NavKey
import androidx.navigation3.runtime.entryProvider
import androidx.navigation3.runtime.rememberNavBackStack
import androidx.navigation3.ui.NavDisplay
import com.mrndtvndv.term.ui.ReviewNavKey
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.ui.draw.shadow
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.gestures.rememberTransformableState
import androidx.compose.foundation.gestures.transformable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.InsertDriveFile
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.text.withStyle

@Suppress("LongMethod", "CyclomaticComplexMethod")
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GitReviewScreen(
    viewModel: ReviewViewModel,
    isTabActive: Boolean = true,
    modifier: Modifier = Modifier
) {
    val uiState by viewModel.uiState.collectAsState()
    val selectedFile by viewModel.selectedFile.collectAsState()
    val selectedCommit by viewModel.selectedCommit.collectAsState()
    val selectedFileDiff by viewModel.selectedFileDiff.collectAsState()
    val isDiffLoading by viewModel.isDiffLoading.collectAsState()
    val isFullFileMode by viewModel.isFullFileMode.collectAsState()
    val showLineNumbers by viewModel.showLineNumbers.collectAsState()
    val isStagedExpanded by viewModel.isStagedExpanded.collectAsState()
    val isUnstagedExpanded by viewModel.isUnstagedExpanded.collectAsState()
    val isCommitsExpanded by viewModel.isCommitsExpanded.collectAsState()
    val isRefreshing by viewModel.isRefreshing.collectAsState()
    val errorMessage by viewModel.errorMessage.collectAsState()
    val isCommitInProgress by viewModel.isCommitInProgress.collectAsState()
    val isBranchOperationInProgress by viewModel.isBranchOperationInProgress.collectAsState()

    val configuration = LocalConfiguration.current
    val isWideScreen = configuration.screenWidthDp >= 600

    val reviewBackStack: NavBackStack<NavKey> = rememberNavBackStack(ReviewNavKey.ChangesList)

    var discardConfirmFile by remember { mutableStateOf<GitFileStatus?>(null) }
    var showCommitDialog by remember { mutableStateOf(false) }
    var commitMessage by remember { mutableStateOf("") }
    var renameCommitTarget by remember { mutableStateOf<GitCommit?>(null) }
    var renameCommitSubject by remember { mutableStateOf("") }
    var showBranchDialog by remember { mutableStateOf(false) }
    var showCreateBranchDialog by remember { mutableStateOf(false) }

    val stagedFileCount = (uiState as? ReviewUiState.Success)?.stagedFiles?.size ?: 0

    if (renameCommitTarget != null) {
        AlertDialog(
            onDismissRequest = { if (!isCommitInProgress) renameCommitTarget = null },
            title = { Text("Edit Commit Message") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        text = "Commit ${renameCommitTarget?.shortHash}",
                        style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                    )
                    OutlinedTextField(
                        value = renameCommitSubject,
                        onValueChange = { renameCommitSubject = it },
                        label = { Text("Commit Message") },
                        enabled = !isCommitInProgress,
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        val commit = renameCommitTarget
                        if (commit != null && renameCommitSubject.isNotBlank()) {
                            viewModel.renameCommit(commit, renameCommitSubject.trim())
                        }
                        renameCommitTarget = null
                    },
                    enabled = renameCommitSubject.isNotBlank() && !isCommitInProgress
                ) {
                    if (isCommitInProgress) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(18.dp),
                            strokeWidth = 2.dp
                        )
                    } else {
                        Text("Save")
                    }
                }
            },
            dismissButton = {
                TextButton(
                    onClick = { renameCommitTarget = null },
                    enabled = !isCommitInProgress
                ) {
                    Text("Cancel")
                }
            }
        )
    }

    if (showCommitDialog) {
        AlertDialog(
            onDismissRequest = { if (!isCommitInProgress) showCommitDialog = false },
            title = { Text("Commit Changes") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("Commit $stagedFileCount staged file${if (stagedFileCount == 1) "" else "s"}.")
                    OutlinedTextField(
                        value = commitMessage,
                        onValueChange = { commitMessage = it },
                        label = { Text("Commit message") },
                        placeholder = { Text("Describe your changes") },
                        minLines = 2,
                        maxLines = 5,
                        enabled = !isCommitInProgress,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        viewModel.commit(commitMessage)
                        showCommitDialog = false
                        commitMessage = ""
                    },
                    enabled = commitMessage.isNotBlank() && !isCommitInProgress
                ) {
                    if (isCommitInProgress) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(18.dp),
                            strokeWidth = 2.dp
                        )
                    } else {
                        Text("Commit")
                    }
                }
            },
            dismissButton = {
                TextButton(
                    onClick = { showCommitDialog = false },
                    enabled = !isCommitInProgress
                ) {
                    Text("Cancel")
                }
            }
        )
    }

    // Error Alert Dialog
    errorMessage?.let { error ->
        AlertDialog(
            onDismissRequest = { viewModel.clearErrorMessage() },
            title = { Text("Git Error") },
            text = { Text(error) },
            confirmButton = {
                TextButton(onClick = { viewModel.clearErrorMessage() }) {
                    Text("OK")
                }
            }
        )
    }

    // Discard Confirmation Dialog
    discardConfirmFile?.let { file ->
        AlertDialog(
            onDismissRequest = { discardConfirmFile = null },
            title = { Text("Discard Changes?") },
            text = {
                Text(
                    if (file.status == "??") {
                        "Are you sure you want to permanently delete untracked file '${file.path}'?"
                    } else {
                        "Are you sure you want to discard all local changes to '${file.path}'? This cannot be undone."
                    }
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        viewModel.discardFileChanges(file)
                        discardConfirmFile = null
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                ) {
                    Text("Discard")
                }
            },
            dismissButton = {
                TextButton(onClick = { discardConfirmFile = null }) {
                    Text("Cancel")
                }
            }
        )
    }

    if (showBranchDialog) {
        val successState = uiState as? ReviewUiState.Success
        BranchSelectorDialog(
            currentBranch = successState?.currentBranch.orEmpty(),
            branches = successState?.branches.orEmpty(),
            isOperationInProgress = isBranchOperationInProgress,
            onBranchSelected = { branch ->
                viewModel.checkoutBranch(branch)
                showBranchDialog = false
            },
            onCreateNewBranchClick = {
                showBranchDialog = false
                showCreateBranchDialog = true
            },
            onDismissRequest = { showBranchDialog = false }
        )
    }

    if (showCreateBranchDialog) {
        CreateBranchDialog(
            isOperationInProgress = isBranchOperationInProgress,
            onConfirm = { newBranch ->
                viewModel.createAndCheckoutBranch(newBranch)
                showCreateBranchDialog = false
            },
            onDismissRequest = { showCreateBranchDialog = false }
        )
    }

    if (isWideScreen) {
        Row(modifier = modifier.fillMaxSize()) {
            // Left Panel - File List (40% width)
            Box(modifier = Modifier.weight(0.4f).fillMaxHeight()) {
                FileChangesList(
                    uiState = uiState,
                    selectedFile = selectedFile,
                    selectedCommit = selectedCommit,
                    isStagedExpanded = isStagedExpanded,
                    isUnstagedExpanded = isUnstagedExpanded,
                    isCommitsExpanded = isCommitsExpanded,
                    isRefreshing = isRefreshing,
                    onToggleStagedExpanded = { viewModel.toggleStagedExpanded() },
                    onToggleUnstagedExpanded = { viewModel.toggleUnstagedExpanded() },
                    onToggleCommitsExpanded = { viewModel.toggleCommitsExpanded() },
                    onFileSelected = { viewModel.selectFile(it) },
                    onCommitSelected = { viewModel.selectCommit(it) },
                    onRenameCommitClick = { commit ->
                        renameCommitTarget = commit
                        renameCommitSubject = commit.subject
                    },
                    onLoadMoreCommits = { viewModel.loadMoreCommits() },
                    onStage = { viewModel.stageFile(it) },
                    onUnstage = { viewModel.unstageFile(it) },
                    onDiscard = { discardConfirmFile = it },
                    onStageBatch = { viewModel.stageFiles(it) },
                    onUnstageBatch = { viewModel.unstageFiles(it) },
                    onDiscardBatch = { viewModel.discardFiles(it) },
                    onCommit = { showCommitDialog = true },
                    isCommitInProgress = isCommitInProgress,
                    onRefresh = { viewModel.refresh() },
                    onBranchHeaderClick = { showBranchDialog = true }
                )
            }

            VerticalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.3f))

            // Right Panel - Diff Viewer (60% width)
            Box(modifier = Modifier.weight(0.6f).fillMaxHeight()) {
                DiffViewer(
                    selectedFile = selectedFile,
                    selectedCommit = selectedCommit,
                    diffText = selectedFileDiff,
                    isLoading = isDiffLoading,
                    isFullFileMode = isFullFileMode,
                    onToggleFullFileMode = { viewModel.toggleFullFileMode() },
                    showLineNumbers = showLineNumbers,
                    onToggleLineNumbers = { viewModel.toggleLineNumbers() },
                    showHeader = true
                )
            }
        }
    } else {
        // Mobile layout: use Navigation 3 backStack for list vs diff view
        NavDisplay(
            backStack = reviewBackStack,
            onBack = {
                if (isTabActive && reviewBackStack.size > 1) {
                    viewModel.deselectFile()
                    reviewBackStack.removeLastOrNull()
                }
            },
            entryProvider = entryProvider<NavKey> {
                entry<ReviewNavKey.ChangesList> {
                    FileChangesList(
                        uiState = uiState,
                        selectedFile = null,
                        selectedCommit = null,
                        isStagedExpanded = isStagedExpanded,
                        isUnstagedExpanded = isUnstagedExpanded,
                        isCommitsExpanded = isCommitsExpanded,
                        isRefreshing = isRefreshing,
                        onToggleStagedExpanded = { viewModel.toggleStagedExpanded() },
                        onToggleUnstagedExpanded = { viewModel.toggleUnstagedExpanded() },
                        onToggleCommitsExpanded = { viewModel.toggleCommitsExpanded() },
                        onFileSelected = { file ->
                            viewModel.selectFile(file)
                            reviewBackStack.add(ReviewNavKey.FileDiff(file.path, file.isStaged))
                        },
                        onCommitSelected = { commit ->
                            viewModel.selectCommit(commit)
                            reviewBackStack.add(ReviewNavKey.CommitDiff(commit.hash))
                        },
                        onRenameCommitClick = { commit ->
                            renameCommitTarget = commit
                            renameCommitSubject = commit.subject
                        },
                        onLoadMoreCommits = { viewModel.loadMoreCommits() },
                        onStage = { viewModel.stageFile(it) },
                        onUnstage = { viewModel.unstageFile(it) },
                        onDiscard = { discardConfirmFile = it },
                        onStageBatch = { viewModel.stageFiles(it) },
                        onUnstageBatch = { viewModel.unstageFiles(it) },
                        onDiscardBatch = { viewModel.discardFiles(it) },
                        onCommit = { showCommitDialog = true },
                        isCommitInProgress = isCommitInProgress,
                        onRefresh = { viewModel.refresh() },
                        onBranchHeaderClick = { showBranchDialog = true },
                        modifier = modifier
                    )
                }
                entry<ReviewNavKey.FileDiff> { navKey ->
                    Scaffold(
                        topBar = {
                            TopAppBar(
                                title = {
                                    Column {
                                        Text(
                                            text = selectedFile?.path ?: navKey.path,
                                            style = MaterialTheme.typography.titleMedium,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                        Text(
                                            text = if (navKey.isStaged) "Staged Changes" else "Unstaged Changes",
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                                        )
                                    }
                                },
                                navigationIcon = {
                                    IconButton(onClick = {
                                        viewModel.deselectFile()
                                        reviewBackStack.removeLastOrNull()
                                    }) {
                                        Icon(
                                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                                            contentDescription = "Back"
                                        )
                                    }
                                },
                                actions = {
                                    Row(
                                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                        modifier = Modifier.padding(end = 8.dp)
                                    ) {
                                        FilterChip(
                                            selected = showLineNumbers,
                                            onClick = { viewModel.toggleLineNumbers() },
                                            label = { Text("Line Numbers") },
                                            leadingIcon = {
                                                Icon(
                                                    imageVector = Icons.Default.FormatListNumbered,
                                                    contentDescription = "Toggle line numbers",
                                                    modifier = Modifier.size(16.dp)
                                                )
                                            }
                                        )
                                        FilterChip(
                                            selected = isFullFileMode,
                                            onClick = { viewModel.toggleFullFileMode() },
                                            label = { Text(if (isFullFileMode) "Full File" else "Diff Only") },
                                            leadingIcon = {
                                                Icon(
                                                    imageVector = if (isFullFileMode) {
                                                        Icons.Default.Visibility
                                                    } else {
                                                        Icons.Default.UnfoldMore
                                                    },
                                                    contentDescription = "Toggle full file mode",
                                                    modifier = Modifier.size(16.dp)
                                                )
                                            }
                                        )
                                    }
                                },
                                colors = TopAppBarDefaults.topAppBarColors(
                                    containerColor = MaterialTheme.colorScheme.surface
                                )
                            )
                        }
                    ) { padding ->
                        Box(modifier = modifier.fillMaxSize().padding(padding)) {
                            DiffViewer(
                                selectedFile = selectedFile,
                                selectedCommit = selectedCommit,
                                diffText = selectedFileDiff,
                                isLoading = isDiffLoading,
                                isFullFileMode = isFullFileMode,
                                onToggleFullFileMode = { viewModel.toggleFullFileMode() },
                                showLineNumbers = showLineNumbers,
                                onToggleLineNumbers = { viewModel.toggleLineNumbers() },
                                showHeader = false
                            )
                        }
                    }
                }
                entry<ReviewNavKey.CommitDiff> { navKey ->
                    Scaffold(
                        topBar = {
                            TopAppBar(
                                title = {
                                    Column {
                                        Text(
                                            text = selectedCommit?.subject ?: "Commit Details",
                                            style = MaterialTheme.typography.titleMedium,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                        Text(
                                            text = selectedCommit?.let {
                                                "Commit ${it.shortHash} • ${it.relativeDate}"
                                            } ?: "Commit ${navKey.hash.take(7)}",
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                                        )
                                    }
                                },
                                navigationIcon = {
                                    IconButton(onClick = {
                                        viewModel.deselectFile()
                                        reviewBackStack.removeLastOrNull()
                                    }) {
                                        Icon(
                                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                                            contentDescription = "Back"
                                        )
                                    }
                                },
                                actions = {
                                    FilterChip(
                                        selected = showLineNumbers,
                                        onClick = { viewModel.toggleLineNumbers() },
                                        label = { Text("Line Numbers") },
                                        leadingIcon = {
                                            Icon(
                                                imageVector = Icons.Default.FormatListNumbered,
                                                contentDescription = "Toggle line numbers",
                                                modifier = Modifier.size(16.dp)
                                            )
                                        },
                                        modifier = Modifier.padding(end = 8.dp)
                                    )
                                },
                                colors = TopAppBarDefaults.topAppBarColors(
                                    containerColor = MaterialTheme.colorScheme.surface
                                )
                            )
                        }
                    ) { padding ->
                        Box(modifier = modifier.fillMaxSize().padding(padding)) {
                            DiffViewer(
                                selectedFile = selectedFile,
                                selectedCommit = selectedCommit,
                                diffText = selectedFileDiff,
                                isLoading = isDiffLoading,
                                isFullFileMode = isFullFileMode,
                                onToggleFullFileMode = { viewModel.toggleFullFileMode() },
                                showLineNumbers = showLineNumbers,
                                onToggleLineNumbers = { viewModel.toggleLineNumbers() },
                                showHeader = false
                            )
                        }
                    }
                }
            }
        )
    }
}

@Composable
fun StatusBadge(status: String) {
    val isDark = MaterialTheme.colorScheme.background.luminance() < 0.5f
    val (bg, fg, label) = when (status) {
        "A" -> Triple(
            if (isDark) Color(0x2256D364) else Color(0xFFE6FFEC),
            if (isDark) Color(0xFF56D364) else Color(0xFF22863A),
            "A"
        )
        "M" -> Triple(
            if (isDark) Color(0x22E3B341) else Color(0xFFFFF8E1),
            if (isDark) Color(0xFFE3B341) else Color(0xFFB78103),
            "M"
        )
        "D" -> Triple(
            if (isDark) Color(0x22F85149) else Color(0xFFFFEEEE),
            if (isDark) Color(0xFFF85149) else Color(0xFFCB2431),
            "D"
        )
        "??" -> Triple(
            if (isDark) Color(0x228B949E) else Color(0xFFF6F8FA),
            if (isDark) Color(0xFF8B949E) else Color(0xFF57606A),
            "U"
        )
        else -> Triple(
            MaterialTheme.colorScheme.surfaceVariant,
            MaterialTheme.colorScheme.onSurfaceVariant,
            status
        )
    }

    Surface(
        color = bg,
        shape = RoundedCornerShape(4.dp),
        modifier = Modifier.size(24.dp)
    ) {
        Box(contentAlignment = Alignment.Center) {
            Text(
                text = label,
                color = fg,
                style = MaterialTheme.typography.bodySmall.copy(
                    fontWeight = FontWeight.Bold,
                    fontFamily = FontFamily.Monospace
                )
            )
        }
    }
}

private enum class TokenType {
    COMMENT, STRING, KEYWORD, NUMBER, ANNOTATION, TYPE
}

private data class TokenRule(val type: TokenType, val regex: Regex)

private data class Match(val type: TokenType, val range: IntRange)

private val syntaxRules = listOf(
    // Comments (single line and multi line)
    TokenRule(TokenType.COMMENT, Regex("//.*|/\\*[\\s\\S]*?\\*/|#.*")),
    // Strings
    TokenRule(TokenType.STRING, Regex("\"[^\"\\\\]*(?:\\\\.[^\"\\\\]*)*\"|'[^'\\\\]*(?:\\\\.[^'\\\\]*)*'|`[^`\\\\]*(?:\\\\.[^`\\\\]*)*`")),
    // Keywords
    TokenRule(TokenType.KEYWORD, Regex("\\b(val|var|fun|class|interface|object|import|package|return|if|else|for|while|do|when|is|as|in|out|try|catch|finally|throw|this|super|new|private|protected|public|internal|lateinit|init|companion|const|null|true|false|void|int|double|float|long|short|byte|char|boolean|string|def|elif|from|lambda|pass|global|nonlocal|async|await|let|const|var|function|export|default|extends|implements|struct|enum|fn|mut|impl|use|pub|sizeof|typeof)\\b")),
    // Numbers (decimal and hex)
    TokenRule(TokenType.NUMBER, Regex("\\b(0x[0-9a-fA-F]+|\\d+(\\.\\d+)?)\\b")),
    // Annotations
    TokenRule(TokenType.ANNOTATION, Regex("@[A-Za-z0-9_]+")),
    // Types (Capitalized words)
    TokenRule(TokenType.TYPE, Regex("\\b[A-Z][A-Za-z0-9_]*\\b"))
)

private fun highlightCode(code: String, isDark: Boolean): androidx.compose.ui.text.AnnotatedString {
    val matches = mutableListOf<Match>()
    for (rule in syntaxRules) {
        rule.regex.findAll(code).forEach { result ->
            matches.add(Match(rule.type, result.range))
        }
    }
    
    // Sort matches: first by start index ascending, then by length descending
    matches.sortWith(compareBy<Match> { it.range.first }.thenByDescending { it.range.last - it.range.first })
    
    val nonOverlapping = mutableListOf<Match>()
    var lastEnd = -1
    for (match in matches) {
        if (match.range.first > lastEnd) {
            nonOverlapping.add(match)
            lastEnd = match.range.last
        }
    }
    
    val builder = androidx.compose.ui.text.AnnotatedString.Builder(code)
    for (match in nonOverlapping) {
        val style = if (isDark) {
            when (match.type) {
                TokenType.COMMENT -> androidx.compose.ui.text.SpanStyle(color = Color(0xFF808080), fontStyle = androidx.compose.ui.text.font.FontStyle.Italic)
                TokenType.STRING -> androidx.compose.ui.text.SpanStyle(color = Color(0xFF6A8759))
                TokenType.KEYWORD -> androidx.compose.ui.text.SpanStyle(color = Color(0xFFCC7832), fontWeight = FontWeight.Bold)
                TokenType.NUMBER -> androidx.compose.ui.text.SpanStyle(color = Color(0xFF6897BB))
                TokenType.ANNOTATION -> androidx.compose.ui.text.SpanStyle(color = Color(0xFFBBB529))
                TokenType.TYPE -> androidx.compose.ui.text.SpanStyle(color = Color(0xFF287BDE))
            }
        } else {
            when (match.type) {
                TokenType.COMMENT -> androidx.compose.ui.text.SpanStyle(color = Color(0xFF8C8C8C), fontStyle = androidx.compose.ui.text.font.FontStyle.Italic)
                TokenType.STRING -> androidx.compose.ui.text.SpanStyle(color = Color(0xFF067D17))
                TokenType.KEYWORD -> androidx.compose.ui.text.SpanStyle(color = Color(0xFF0033B0), fontWeight = FontWeight.Bold)
                TokenType.NUMBER -> androidx.compose.ui.text.SpanStyle(color = Color(0xFF1750EB))
                TokenType.ANNOTATION -> androidx.compose.ui.text.SpanStyle(color = Color(0xFF9E880D))
                TokenType.TYPE -> androidx.compose.ui.text.SpanStyle(color = Color(0xFF007F7F))
            }
        }
        builder.addStyle(style, match.range.first, match.range.last + 1)
    }
    return builder.toAnnotatedString()
}

@Suppress("LongParameterList", "LongMethod", "CyclomaticComplexMethod")
@Composable
fun FileChangesList(
    uiState: ReviewUiState,
    selectedFile: GitFileStatus?,
    selectedCommit: GitCommit? = null,
    isStagedExpanded: Boolean = true,
    isUnstagedExpanded: Boolean = true,
    isCommitsExpanded: Boolean = true,
    isRefreshing: Boolean = false,
    onToggleStagedExpanded: () -> Unit = {},
    onToggleUnstagedExpanded: () -> Unit = {},
    onToggleCommitsExpanded: () -> Unit = {},
    onFileSelected: (GitFileStatus) -> Unit,
    onCommitSelected: (GitCommit) -> Unit = {},
    onRenameCommitClick: (GitCommit) -> Unit = {},
    onLoadMoreCommits: () -> Unit = {},
    onStage: (GitFileStatus) -> Unit,
    onUnstage: (GitFileStatus) -> Unit,
    onDiscard: (GitFileStatus) -> Unit,
    onStageBatch: (List<GitFileStatus>) -> Unit,
    onUnstageBatch: (List<GitFileStatus>) -> Unit,
    onDiscardBatch: (List<GitFileStatus>) -> Unit,
    onCommit: () -> Unit,
    isCommitInProgress: Boolean,
    onRefresh: () -> Unit,
    onBranchHeaderClick: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    val stagedFiles = (uiState as? ReviewUiState.Success)?.stagedFiles.orEmpty()
    val unstagedFiles = (uiState as? ReviewUiState.Success)?.unstagedFiles.orEmpty()
    val allFiles = remember(stagedFiles, unstagedFiles) { stagedFiles + unstagedFiles }

    var checkedFiles by remember { mutableStateOf(setOf<GitFileStatus>()) }
    var showBatchDiscardDialog by remember { mutableStateOf(false) }

    LaunchedEffect(allFiles) {
        checkedFiles = checkedFiles.filter { it in allFiles }.toSet()
    }

    if (showBatchDiscardDialog && checkedFiles.isNotEmpty()) {
        AlertDialog(
            onDismissRequest = { showBatchDiscardDialog = false },
            title = { Text("Discard ${checkedFiles.size} Changes?") },
            text = {
                Text(
                    "Are you sure you want to discard all local changes to the ${checkedFiles.size} " +
                        "selected file(s)? This cannot be undone."
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        onDiscardBatch(checkedFiles.toList())
                        checkedFiles = emptySet()
                        showBatchDiscardDialog = false
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                ) {
                    Text("Discard All")
                }
            },
            dismissButton = {
                TextButton(onClick = { showBatchDiscardDialog = false }) {
                    Text("Cancel")
                }
            }
        )
    }

    val inSelectionMode = checkedFiles.isNotEmpty()

    Scaffold(
        modifier = modifier.fillMaxSize(),
        floatingActionButton = {
            if (stagedFiles.isNotEmpty() && !inSelectionMode) {
                FloatingActionButton(
                    onClick = { if (!isCommitInProgress) onCommit() },
                ) {
                    if (isCommitInProgress) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(24.dp),
                            strokeWidth = 2.dp
                        )
                    } else {
                        Icon(
                            imageVector = Icons.Default.Commit,
                            contentDescription = "Commit staged changes"
                        )
                    }
                }
            }
        }
    ) { contentPadding ->
        Box(modifier = Modifier.fillMaxSize()) {
            PullToRefreshBox(
                isRefreshing = isRefreshing,
                onRefresh = onRefresh,
                modifier = Modifier.fillMaxSize().padding(contentPadding)
            ) {
                when (uiState) {
                    is ReviewUiState.Loading -> {
                        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            CircularProgressIndicator()
                        }
                    }
                    is ReviewUiState.Error -> {
                        Box(
                            modifier = Modifier.fillMaxSize().padding(16.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(text = uiState.message, color = MaterialTheme.colorScheme.error)
                        }
                    }
                    is ReviewUiState.Success -> {
                        LazyColumn(
                            modifier = Modifier.fillMaxSize(),
                            contentPadding = PaddingValues(top = 8.dp, bottom = 88.dp)
                        ) {
                            item {
                                BranchHeader(
                                    currentBranch = uiState.currentBranch,
                                    onBranchClick = onBranchHeaderClick
                                )
                            }
                            if (uiState.stagedFiles.isEmpty() && uiState.unstagedFiles.isEmpty()) {
                                item {
                                    Box(
                                        modifier = Modifier.fillMaxWidth().padding(32.dp),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Column(
                                            horizontalAlignment = Alignment.CenterHorizontally,
                                            verticalArrangement = Arrangement.spacedBy(8.dp)
                                        ) {
                                            Icon(
                                                imageVector = Icons.Default.CheckCircle,
                                                contentDescription = "Clean",
                                                tint = Color(0xFF2EA043),
                                                modifier = Modifier.size(48.dp)
                                            )
                                            Text(
                                                "Working Tree Clean",
                                                style = MaterialTheme.typography.titleMedium,
                                                fontWeight = FontWeight.Bold
                                            )
                                            Text(
                                                "No staged or unstaged changes found.",
                                                style = MaterialTheme.typography.bodyMedium,
                                                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                                            )
                                        }
                                    }
                                }
                            } else {
                                if (uiState.stagedFiles.isNotEmpty()) {
                                    item {
                                        SectionHeader(
                                            title = "Staged Changes (${uiState.stagedFiles.size})",
                                            isExpanded = isStagedExpanded,
                                            onToggle = onToggleStagedExpanded
                                        )
                                    }
                                    if (isStagedExpanded) {
                                        items(uiState.stagedFiles) { file ->
                                            val isChecked = file in checkedFiles
                                            FileItem(
                                                file = file,
                                                isSelected = selectedFile == file,
                                                isChecked = isChecked,
                                                inSelectionMode = inSelectionMode,
                                                onClick = {
                                                    if (inSelectionMode) {
                                                        checkedFiles = if (isChecked) {
                                                            checkedFiles - file
                                                        } else {
                                                            checkedFiles + file
                                                        }
                                                    } else {
                                                        onFileSelected(file)
                                                    }
                                                },
                                                onLongClick = {
                                                    checkedFiles = if (isChecked) {
                                                        checkedFiles - file
                                                    } else {
                                                        checkedFiles + file
                                                    }
                                                },
                                                onCheckedChange = { checked ->
                                                    checkedFiles = if (checked) {
                                                        checkedFiles + file
                                                    } else {
                                                        checkedFiles - file
                                                    }
                                                },
                                                onAction = { onUnstage(file) },
                                                onDiscard = { onDiscard(file) }
                                            )
                                        }
                                    }
                                }

                                if (uiState.unstagedFiles.isNotEmpty()) {
                                    item {
                                        SectionHeader(
                                            title = "Unstaged Changes (${uiState.unstagedFiles.size})",
                                            isExpanded = isUnstagedExpanded,
                                            onToggle = onToggleUnstagedExpanded
                                        )
                                    }
                                    if (isUnstagedExpanded) {
                                        items(uiState.unstagedFiles) { file ->
                                            val isChecked = file in checkedFiles
                                            FileItem(
                                                file = file,
                                                isSelected = selectedFile == file,
                                                isChecked = isChecked,
                                                inSelectionMode = inSelectionMode,
                                                onClick = {
                                                    if (inSelectionMode) {
                                                        checkedFiles = if (isChecked) {
                                                            checkedFiles - file
                                                        } else {
                                                            checkedFiles + file
                                                        }
                                                    } else {
                                                        onFileSelected(file)
                                                    }
                                                },
                                                onLongClick = {
                                                    checkedFiles = if (isChecked) {
                                                        checkedFiles - file
                                                    } else {
                                                        checkedFiles + file
                                                    }
                                                },
                                                onCheckedChange = { checked ->
                                                    checkedFiles = if (checked) {
                                                        checkedFiles + file
                                                    } else {
                                                        checkedFiles - file
                                                    }
                                                },
                                                onAction = { onStage(file) },
                                                onDiscard = { onDiscard(file) }
                                            )
                                        }
                                    }
                                }
                            }

                            if (uiState.recentCommits.isNotEmpty()) {
                                item {
                                    SectionHeader(
                                        title = "Commit History (${uiState.recentCommits.size})",
                                        isExpanded = isCommitsExpanded,
                                        onToggle = onToggleCommitsExpanded
                                    )
                                }
                                if (isCommitsExpanded) {
                                    items(uiState.recentCommits) { commit ->
                                        CommitItem(
                                            commit = commit,
                                            isSelected = selectedCommit == commit,
                                            onClick = { onCommitSelected(commit) },
                                            onRenameClick = { onRenameCommitClick(commit) }
                                        )
                                    }
                                    if (uiState.hasMoreCommits) {
                                        item {
                                            Box(
                                                modifier = Modifier
                                                    .fillMaxWidth()
                                                    .padding(vertical = 8.dp),
                                                contentAlignment = Alignment.Center
                                            ) {
                                                TextButton(onClick = onLoadMoreCommits) {
                                                    Text("Load More Commits")
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }

            AnimatedVisibility(
                visible = inSelectionMode,
                enter = slideInVertically { it } + fadeIn(),
                exit = slideOutVertically { it } + fadeOut(),
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 16.dp)
            ) {
                val selectedList = checkedFiles.toList()
                val selectedStaged = selectedList.filter { it.isStaged }
                val selectedUnstaged = selectedList.filter { !it.isStaged }

                M3FloatingToolbar(
                    stagedCount = selectedStaged.size,
                    unstagedCount = selectedUnstaged.size,
                    onStageSelected = {
                        if (selectedUnstaged.isNotEmpty()) {
                            onStageBatch(selectedUnstaged)
                            checkedFiles = emptySet()
                        }
                    },
                    onUnstageSelected = {
                        if (selectedStaged.isNotEmpty()) {
                            onUnstageBatch(selectedStaged)
                            checkedFiles = emptySet()
                        }
                    },
                    onDiscardSelected = {
                        showBatchDiscardDialog = true
                    },
                    onSelectAll = {
                        checkedFiles = if (checkedFiles.size == allFiles.size) {
                            emptySet()
                        } else {
                            allFiles.toSet()
                        }
                    },
                    onClearSelection = {
                        checkedFiles = emptySet()
                    }
                )
            }
        }
    }
}

@Suppress("LongParameterList", "LongMethod", "CyclomaticComplexMethod")
@Composable
fun M3FloatingToolbar(
    stagedCount: Int,
    unstagedCount: Int,
    onStageSelected: () -> Unit,
    onUnstageSelected: () -> Unit,
    onDiscardSelected: () -> Unit,
    onSelectAll: () -> Unit,
    onClearSelection: () -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier
            .shadow(elevation = 8.dp, shape = RoundedCornerShape(28.dp)),
        shape = RoundedCornerShape(28.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHighest,
        contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
        tonalElevation = 6.dp
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            if (unstagedCount > 0) {
                IconButton(onClick = onStageSelected) {
                    Icon(
                        imageVector = Icons.Default.AddCircleOutline,
                        contentDescription = "Stage selected",
                        tint = MaterialTheme.colorScheme.primary
                    )
                }
            }

            if (stagedCount > 0) {
                IconButton(onClick = onUnstageSelected) {
                    Icon(
                        imageVector = Icons.Default.RemoveCircleOutline,
                        contentDescription = "Unstage selected",
                        tint = MaterialTheme.colorScheme.error
                    )
                }
            }

            IconButton(onClick = onDiscardSelected) {
                Icon(
                    imageVector = Icons.Default.Restore,
                    contentDescription = "Discard selected",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            VerticalDivider(
                modifier = Modifier
                    .height(20.dp)
                    .padding(horizontal = 2.dp),
                color = MaterialTheme.colorScheme.outline.copy(alpha = 0.3f)
            )

            IconButton(onClick = onSelectAll) {
                Icon(
                    imageVector = Icons.Default.SelectAll,
                    contentDescription = "Select all",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            IconButton(onClick = onClearSelection) {
                Icon(
                    imageVector = Icons.Default.Close,
                    contentDescription = "Clear selection",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
fun SectionHeader(
    title: String,
    isExpanded: Boolean,
    onToggle: () -> Unit
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onToggle() }
            .padding(horizontal = 16.dp, vertical = 8.dp)
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold),
            color = MaterialTheme.colorScheme.primary
        )
        Icon(
            imageVector = if (isExpanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
            contentDescription = if (isExpanded) "Collapse $title" else "Expand $title",
            tint = MaterialTheme.colorScheme.primary
        )
    }
}

@Suppress("LongParameterList", "LongMethod", "CyclomaticComplexMethod")
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun FileItem(
    file: GitFileStatus,
    isSelected: Boolean,
    isChecked: Boolean,
    inSelectionMode: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    onCheckedChange: (Boolean) -> Unit,
    onAction: () -> Unit,
    onDiscard: () -> Unit
) {
    val bg = when {
        isChecked -> MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.5f)
        isSelected -> MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.4f)
        else -> Color.Transparent
    }
    val normalizedPath = file.path.trimEnd('/')
    val fileName = normalizedPath.substringAfterLast('/').ifEmpty { file.path }
    val parentDir = if (normalizedPath.contains('/')) normalizedPath.substringBeforeLast('/') else null

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(bg)
            .combinedClickable(
                onClick = onClick,
                onLongClick = onLongClick
            )
            .padding(vertical = 4.dp, horizontal = 16.dp)
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth()
        ) {
            if (inSelectionMode) {
                Checkbox(
                    checked = isChecked,
                    onCheckedChange = onCheckedChange,
                    modifier = Modifier.padding(end = 8.dp)
                )
            }
            StatusBadge(status = file.status)
            Spacer(modifier = Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = fileName,
                    style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.SemiBold),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                if (!parentDir.isNullOrEmpty()) {
                    Text(
                        text = parentDir,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
            if (!inSelectionMode) {
                IconButton(onClick = onAction) {
                    Icon(
                        imageVector = if (file.isStaged) {
                            Icons.Default.RemoveCircleOutline
                        } else {
                            Icons.Default.AddCircleOutline
                        },
                        contentDescription = if (file.isStaged) "Unstage" else "Stage",
                        tint = if (file.isStaged) {
                            MaterialTheme.colorScheme.error
                        } else {
                            MaterialTheme.colorScheme.primary
                        }
                    )
                }
                IconButton(onClick = onDiscard) {
                    Icon(
                        imageVector = Icons.Default.Restore,
                        contentDescription = "Discard Changes",
                        tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                    )
                }
            }
        }
    HorizontalDivider(thickness = 0.5.dp, color = MaterialTheme.colorScheme.outline.copy(alpha = 0.15f))
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Suppress("LongMethod")
@Composable
fun CommitItem(
    commit: GitCommit,
    isSelected: Boolean,
    onClick: () -> Unit,
    onRenameClick: () -> Unit = {}
) {
    val bg = if (isSelected) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.4f) else Color.Transparent
    var showMenu by remember { mutableStateOf(false) }

    Box(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(bg)
                .combinedClickable(
                    onClick = onClick,
                    onLongClick = { showMenu = true }
                )
                .padding(vertical = 6.dp, horizontal = 16.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth()
            ) {
                Icon(
                    imageVector = Icons.Default.Commit,
                    contentDescription = "Commit",
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(20.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Surface(
                    shape = RoundedCornerShape(4.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant,
                    modifier = Modifier.padding(end = 8.dp)
                ) {
                    Text(
                        text = commit.shortHash,
                        style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                    )
                }
                Text(
                    text = commit.subject,
                    style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Medium),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
            }
            Spacer(modifier = Modifier.height(2.dp))
            Row(
                modifier = Modifier.fillMaxWidth().padding(start = 28.dp),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = commit.author,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = commit.relativeDate,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
                )
            }
            Spacer(modifier = Modifier.height(4.dp))
            HorizontalDivider(thickness = 0.5.dp, color = MaterialTheme.colorScheme.outline.copy(alpha = 0.15f))
        }

        DropdownMenu(
            expanded = showMenu,
            onDismissRequest = { showMenu = false }
        ) {
            DropdownMenuItem(
                text = { Text("Edit Commit Message") },
                leadingIcon = {
                    Icon(
                        imageVector = Icons.Default.Edit,
                        contentDescription = "Edit Commit Message"
                    )
                },
                onClick = {
                    showMenu = false
                    onRenameClick()
                }
            )
        }
    }
}

@Composable
fun CommitDiffHeader(commit: GitCommit) {
    Surface(
        tonalElevation = 2.dp,
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    text = commit.subject,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
                Surface(
                    shape = RoundedCornerShape(4.dp),
                    color = MaterialTheme.colorScheme.primaryContainer,
                    modifier = Modifier.padding(start = 8.dp)
                ) {
                    Text(
                        text = commit.shortHash,
                        style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace),
                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                    )
                }
            }
            Text(
                text = "${commit.author} • ${commit.relativeDate}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
            )
        }
    }
    HorizontalDivider(thickness = 0.5.dp, color = MaterialTheme.colorScheme.outline.copy(alpha = 0.2f))
}

@Suppress("LongMethod")
@Composable
fun DiffHeader(
    selectedFile: GitFileStatus,
    isFullFileMode: Boolean,
    onToggleFullFileMode: () -> Unit,
    showLineNumbers: Boolean = true,
    onToggleLineNumbers: () -> Unit = {}
) {
    Surface(
        tonalElevation = 2.dp,
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = selectedFile.path,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = if (selectedFile.isStaged) "Staged Changes" else "Unstaged Changes",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                )
            }
            Spacer(modifier = Modifier.width(8.dp))
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                FilterChip(
                    selected = showLineNumbers,
                    onClick = onToggleLineNumbers,
                    label = { Text("Line Numbers") },
                    leadingIcon = {
                        Icon(
                            imageVector = Icons.Default.FormatListNumbered,
                            contentDescription = "Toggle line numbers",
                            modifier = Modifier.size(16.dp)
                        )
                    }
                )
                FilterChip(
                    selected = isFullFileMode,
                    onClick = onToggleFullFileMode,
                    label = { Text(if (isFullFileMode) "Full File" else "Diff Only") },
                    leadingIcon = {
                        Icon(
                            imageVector = if (isFullFileMode) {
                                Icons.Default.Visibility
                            } else {
                                Icons.Default.UnfoldMore
                            },
                            contentDescription = "Toggle full file mode",
                            modifier = Modifier.size(16.dp)
                        )
                    }
                )
            }
        }
    }
    HorizontalDivider(thickness = 0.5.dp, color = MaterialTheme.colorScheme.outline.copy(alpha = 0.2f))
}

@Suppress("LongParameterList", "LongMethod")
@Composable
fun DiffViewer(
    selectedFile: GitFileStatus?,
    selectedCommit: GitCommit? = null,
    diffText: String?,
    isLoading: Boolean,
    isFullFileMode: Boolean = false,
    onToggleFullFileMode: () -> Unit = {},
    showLineNumbers: Boolean = true,
    onToggleLineNumbers: () -> Unit = {},
    showHeader: Boolean = false
) {
    if (selectedFile == null && selectedCommit == null) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text(
                "Select a file or commit to view details",
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
            )
        }
        return
    }

    Column(modifier = Modifier.fillMaxSize()) {
        if (showHeader) {
            if (selectedFile != null) {
                DiffHeader(
                    selectedFile = selectedFile,
                    isFullFileMode = isFullFileMode,
                    onToggleFullFileMode = onToggleFullFileMode,
                    showLineNumbers = showLineNumbers,
                    onToggleLineNumbers = onToggleLineNumbers
                )
            } else if (selectedCommit != null) {
                CommitDiffHeader(commit = selectedCommit)
            }
        }

        Box(modifier = Modifier.fillMaxSize().weight(1f)) {
            when {
                isLoading -> {
                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator()
                    }
                }
                diffText == null -> {
                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text("No diff details loaded.")
                    }
                }
                diffText.trim().isEmpty() -> {
                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text("No changes detected in file.")
                    }
                }
                else -> {
                    DiffContent(
                        diffText = diffText,
                        showLineNumbers = showLineNumbers
                    )
                }
            }
        }
    }
}

private fun parseDiffLines(diffText: String): List<ParsedDiffLine> {
    var currentOldLine = 0
    var currentNewLine = 0
    return diffText.split("\n").map { line ->
        when {
            line.startsWith("@@ ") -> {
                val match = Regex("""@@ -(\d+)(?:,\d+)? \+(\d+)(?:,\d+)? @@""").find(line)
                if (match != null) {
                    currentOldLine = match.groupValues[1].toInt()
                    currentNewLine = match.groupValues[2].toInt()
                }
                ParsedDiffLine(line, "", "", DiffLineType.HUNK_HEADER)
            }
            line.startsWith("+") && !line.startsWith("+++") -> {
                val lineNum = currentNewLine.toString()
                currentNewLine++
                ParsedDiffLine(line, "", lineNum, DiffLineType.ADDITION)
            }
            line.startsWith("-") && !line.startsWith("---") -> {
                val lineNum = currentOldLine.toString()
                currentOldLine++
                ParsedDiffLine(line, lineNum, "", DiffLineType.DELETION)
            }
            line.startsWith(" ") -> {
                val oldNum = currentOldLine.toString()
                val newNum = currentNewLine.toString()
                currentOldLine++
                currentNewLine++
                ParsedDiffLine(line, oldNum, newNum, DiffLineType.CONTEXT)
            }
            line.startsWith("diff --git") || line.startsWith("index ") ||
                line.startsWith("--- ") || line.startsWith("+++ ") ||
                line.startsWith("\\ ") -> {
                ParsedDiffLine(line, "", "", DiffLineType.METADATA)
            }
            else -> {
                if (currentOldLine > 0) {
                    val oldNum = currentOldLine.toString()
                    val newNum = currentNewLine.toString()
                    currentOldLine++
                    currentNewLine++
                    ParsedDiffLine(line, oldNum, newNum, DiffLineType.CONTEXT)
                } else {
                    ParsedDiffLine(line, "", "", DiffLineType.METADATA)
                }
            }
        }
    }
}

data class FileDiffSection(
    val filePath: String,
    val lines: List<ParsedDiffLine>
)

@Suppress("NestedBlockDepth")
private fun parseFileDiffSections(diffText: String): List<FileDiffSection> {
    if (diffText.isBlank()) return emptyList()

    val rawLines = diffText.split("\n")
    val sections = mutableListOf<FileDiffSection>()
    var currentPath = ""
    var currentLines = mutableListOf<String>()

    fun flushSection() {
        if (currentLines.isNotEmpty()) {
            val parsed = parseDiffLines(currentLines.joinToString("\n"))
            val path = currentPath.ifBlank { "Diff Details" }
            sections.add(FileDiffSection(path, parsed))
            currentLines = mutableListOf()
            currentPath = ""
        }
    }

    rawLines.forEach { line ->
        if (line.startsWith("diff --git ")) {
            flushSection()
            val bPath = if (line.contains(" b/")) line.substringAfter(" b/").trim() else ""
            val aPath = if (line.contains(" a/")) {
                line.substringAfter(" a/").substringBefore(" b/").trim()
            } else ""
            currentPath = bPath.ifEmpty { aPath }
            currentLines.add(line)
        } else {
            if (currentPath.isEmpty()) {
                if (line.startsWith("+++ b/")) {
                    currentPath = line.substringAfter("+++ b/").trim()
                } else if (line.startsWith("--- a/")) {
                    currentPath = line.substringAfter("--- a/").trim()
                }
            }
            currentLines.add(line)
        }
    }
    flushSection()

    return sections.ifEmpty {
        listOf(FileDiffSection("Diff Details", parseDiffLines(diffText)))
    }
}

@Composable
fun FileDiffHeader(
    filePath: String,
    isCollapsed: Boolean,
    onToggleCollapse: () -> Unit
) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant,
        tonalElevation = 2.dp,
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onToggleCollapse() }
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.weight(1f)
            ) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.InsertDriveFile,
                    contentDescription = "File",
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = filePath,
                    style = MaterialTheme.typography.bodyMedium.copy(
                        fontWeight = FontWeight.Bold,
                        fontFamily = FontFamily.Monospace
                    ),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Icon(
                imageVector = if (isCollapsed) Icons.Default.ExpandMore else Icons.Default.ExpandLess,
                contentDescription = if (isCollapsed) "Expand $filePath" else "Collapse $filePath",
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
    HorizontalDivider(thickness = 0.5.dp, color = MaterialTheme.colorScheme.outline.copy(alpha = 0.2f))
}

@Suppress("LongMethod")
@Composable
fun DiffContent(
    diffText: String,
    showLineNumbers: Boolean = true,
    modifier: Modifier = Modifier
) {
    var fontScale by remember { mutableFloatStateOf(1f) }
    val transformState = rememberTransformableState { zoomChange, _, _ ->
        fontScale = (fontScale * zoomChange).coerceIn(0.6f, 3.0f)
    }

    val scrollState = rememberScrollState()
    val horizScrollState = rememberScrollState()
    val isDark = MaterialTheme.colorScheme.background.luminance() < 0.5f
    val fallbackColor = MaterialTheme.colorScheme.onSurface

    val sections = remember(diffText) { parseFileDiffSections(diffText) }
    var collapsedSections by remember { mutableStateOf(setOf<String>()) }

    val allParsedLines = remember(sections) { sections.flatMap { it.lines } }
    val maxLineNum = remember(allParsedLines) {
        allParsedLines.maxOfOrNull {
            maxOf(it.oldLineNum.toIntOrNull() ?: 0, it.newLineNum.toIntOrNull() ?: 0)
        } ?: 0
    }
    val digitCount = maxLineNum.toString().length.coerceAtLeast(1)
    val dims = remember(digitCount, fontScale) {
        val numW = ((digitCount * 7 + 4) * fontScale).dp
        ScaledDiffDimensions(
            numWidth = numW,
            columnWidth = numW * 2 + (8 * fontScale).dp,
            lineHeight = (20 * fontScale).dp,
            codeFontSize = (12 * fontScale).sp,
            lineNumFontSize = (10 * fontScale).sp
        )
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .transformable(state = transformState)
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(scrollState)
                .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.1f))
        ) {
            sections.forEach { section ->
                FileDiffHeader(
                    filePath = section.filePath,
                    isCollapsed = section.filePath in collapsedSections,
                    onToggleCollapse = {
                        collapsedSections = if (section.filePath in collapsedSections) {
                            collapsedSections - section.filePath
                        } else {
                            collapsedSections + section.filePath
                        }
                    }
                )

                if (section.filePath !in collapsedSections) {
                    DiffRowsLayout(
                        parsedLines = section.lines,
                        dims = dims,
                        horizScrollState = horizScrollState,
                        isDark = isDark,
                        fallbackColor = fallbackColor,
                        showLineNumbers = showLineNumbers
                    )
                }
            }
        }

        if (fontScale != 1f) {
            Surface(
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.9f),
                shape = RoundedCornerShape(16.dp),
                shadowElevation = 4.dp,
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(16.dp)
                    .clickable { fontScale = 1f }
            ) {
                Text(
                    text = "${(fontScale * 100).toInt()}% (Tap to reset)",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
                )
            }
        }
    }
}

@Composable
private fun DiffRowsLayout(
    parsedLines: List<ParsedDiffLine>,
    dims: ScaledDiffDimensions,
    horizScrollState: androidx.compose.foundation.ScrollState,
    isDark: Boolean,
    fallbackColor: Color,
    showLineNumbers: Boolean = true
) {
    Row(
        modifier = Modifier.fillMaxWidth()
    ) {
        if (showLineNumbers) {
            LineNumberColumn(
                parsedLines = parsedLines,
                numWidth = dims.numWidth,
                columnWidth = dims.columnWidth,
                lineHeight = dims.lineHeight,
                fontSize = dims.lineNumFontSize,
                isDark = isDark,
                fallbackColor = fallbackColor
            )

            Box(
                modifier = Modifier
                    .width(1.dp)
                    .height((parsedLines.size * dims.lineHeight.value).dp)
                    .background(MaterialTheme.colorScheme.outline.copy(alpha = 0.2f))
            )
        }

        CodeLinesColumn(
            parsedLines = parsedLines,
            horizScrollState = horizScrollState,
            lineHeight = dims.lineHeight,
            fontSize = dims.codeFontSize,
            isDark = isDark,
            fallbackColor = fallbackColor
        )
    }
}

private data class ScaledDiffDimensions(
    val numWidth: androidx.compose.ui.unit.Dp,
    val columnWidth: androidx.compose.ui.unit.Dp,
    val lineHeight: androidx.compose.ui.unit.Dp,
    val codeFontSize: androidx.compose.ui.unit.TextUnit,
    val lineNumFontSize: androidx.compose.ui.unit.TextUnit
)

@Composable
private fun LineNumberColumn(
    parsedLines: List<ParsedDiffLine>,
    numWidth: androidx.compose.ui.unit.Dp,
    columnWidth: androidx.compose.ui.unit.Dp,
    lineHeight: androidx.compose.ui.unit.Dp,
    fontSize: androidx.compose.ui.unit.TextUnit,
    isDark: Boolean,
    fallbackColor: Color
) {
    Column(
        modifier = Modifier
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f))
            .width(columnWidth)
    ) {
        parsedLines.forEach { parsedLine ->
            val (bgColor, _) = getColors(parsedLine.type, isDark, fallbackColor)

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(lineHeight)
                    .background(bgColor)
                    .padding(horizontal = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.End
            ) {
                Text(
                    text = parsedLine.oldLineNum,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f),
                    fontSize = fontSize,
                    fontFamily = FontFamily.Monospace,
                    modifier = Modifier.width(numWidth),
                    textAlign = androidx.compose.ui.text.style.TextAlign.End
                )
                Spacer(modifier = Modifier.width(4.dp))
                Text(
                    text = parsedLine.newLineNum,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f),
                    fontSize = fontSize,
                    fontFamily = FontFamily.Monospace,
                    modifier = Modifier.width(numWidth),
                    textAlign = androidx.compose.ui.text.style.TextAlign.End
                )
            }
        }
    }
}

@Composable
private fun RowScope.CodeLinesColumn(
    parsedLines: List<ParsedDiffLine>,
    horizScrollState: androidx.compose.foundation.ScrollState,
    lineHeight: androidx.compose.ui.unit.Dp,
    fontSize: androidx.compose.ui.unit.TextUnit,
    isDark: Boolean,
    fallbackColor: Color
) {
    Column(
        modifier = Modifier
            .weight(1f)
            .horizontalScroll(horizScrollState)
    ) {
        parsedLines.forEach { parsedLine ->
            val (bgColor, textColor) = getColors(parsedLine.type, isDark, fallbackColor)

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(lineHeight)
                    .background(bgColor)
                    .padding(horizontal = 8.dp),
                contentAlignment = Alignment.CenterStart
            ) {
                val annotatedText = remember(parsedLine.text, parsedLine.type, isDark, textColor) {
                    val type = parsedLine.type
                    if (type == DiffLineType.ADDITION || type == DiffLineType.DELETION ||
                        type == DiffLineType.CONTEXT
                    ) {
                        val prefix = parsedLine.text.take(1)
                        val remainingText = parsedLine.text.drop(1)

                        androidx.compose.ui.text.buildAnnotatedString {
                            withStyle(androidx.compose.ui.text.SpanStyle(color = textColor)) {
                                append(prefix)
                            }
                            append(highlightCode(remainingText, isDark))
                        }
                    } else {
                        androidx.compose.ui.text.buildAnnotatedString {
                            withStyle(androidx.compose.ui.text.SpanStyle(color = textColor)) {
                                append(parsedLine.text)
                            }
                        }
                    }
                }

                Text(
                    text = annotatedText,
                    fontSize = fontSize,
                    fontFamily = FontFamily.Monospace,
                    softWrap = false
                )
            }
        }
    }
}

data class ParsedDiffLine(
    val text: String,
    val oldLineNum: String,
    val newLineNum: String,
    val type: DiffLineType
)

enum class DiffLineType {
    METADATA,
    HUNK_HEADER,
    CONTEXT,
    ADDITION,
    DELETION
}

private fun getColors(type: DiffLineType, isDark: Boolean, fallbackColor: Color): Pair<Color, Color> {
    return when (type) {
        DiffLineType.ADDITION -> {
            if (isDark) {
                Color(0x222EA043) to Color(0xFF56D364)
            } else {
                Color(0xFFE6FFEC) to Color(0xFF22863A)
            }
        }
        DiffLineType.DELETION -> {
            if (isDark) {
                Color(0x22F85149) to Color(0xFFF85149)
            } else {
                Color(0xFFFFEEEE) to Color(0xFFCB2431)
            }
        }
        DiffLineType.HUNK_HEADER -> {
            if (isDark) {
                Color(0x15388BFD) to Color(0xFF79C0FF)
            } else {
                Color(0xFFF1F8FF) to Color(0xFF032F62)
            }
        }
        DiffLineType.METADATA -> {
            Color.Transparent to fallbackColor.copy(alpha = 0.5f)
        }
        DiffLineType.CONTEXT -> {
            Color.Transparent to fallbackColor.copy(alpha = 0.85f)
        }
    }
}

@Suppress("LongMethod")
@Composable
fun BranchHeader(
    currentBranch: String,
    onBranchClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 6.dp),
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
    ) {
        Row(
            modifier = Modifier
                .clickable(onClick = onBranchClick)
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                modifier = Modifier.weight(1f)
            ) {
                Surface(
                    color = MaterialTheme.colorScheme.primaryContainer,
                    shape = RoundedCornerShape(8.dp),
                    modifier = Modifier.size(32.dp)
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            imageVector = Icons.Default.AccountTree,
                            contentDescription = "Current branch",
                            tint = MaterialTheme.colorScheme.onPrimaryContainer,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                }
                Column {
                    Text(
                        text = "Current Branch",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        text = currentBranch.ifBlank { "HEAD" },
                        style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }

        }
    }
}

@Suppress("LongMethod", "CyclomaticComplexMethod")
@Composable
fun BranchSelectorDialog(
    currentBranch: String,
    branches: List<GitBranch>,
    isOperationInProgress: Boolean,
    onBranchSelected: (GitBranch) -> Unit,
    onCreateNewBranchClick: () -> Unit,
    onDismissRequest: () -> Unit
) {
    var searchQuery by remember { mutableStateOf("") }
    val filteredBranches = remember(branches, searchQuery) {
        if (searchQuery.isBlank()) {
            branches
        } else {
            branches.filter { it.name.contains(searchQuery, ignoreCase = true) }
        }
    }
    val localBranches = remember(filteredBranches) { filteredBranches.filter { !it.isRemote } }
    val remoteBranches = remember(filteredBranches) { filteredBranches.filter { it.isRemote } }

    AlertDialog(
        onDismissRequest = { if (!isOperationInProgress) onDismissRequest() },
        title = {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("Switch Branch")
                if (isOperationInProgress) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(20.dp),
                        strokeWidth = 2.dp
                    )
                }
            }
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 420.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                OutlinedTextField(
                    value = searchQuery,
                    onValueChange = { searchQuery = it },
                    placeholder = { Text("Filter branches...") },
                    leadingIcon = {
                        Icon(
                            imageVector = Icons.Default.Search,
                            contentDescription = "Search"
                        )
                    },
                    trailingIcon = {
                        if (searchQuery.isNotEmpty()) {
                            IconButton(onClick = { searchQuery = "" }) {
                                Icon(
                                    imageVector = Icons.Default.Clear,
                                    contentDescription = "Clear search"
                                )
                            }
                        }
                    },
                    singleLine = true,
                    enabled = !isOperationInProgress,
                    modifier = Modifier.fillMaxWidth()
                )

                OutlinedButton(
                    onClick = onCreateNewBranchClick,
                    enabled = !isOperationInProgress,
                    modifier = Modifier.fillMaxWidth(),
                    contentPadding = PaddingValues(vertical = 8.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Add,
                        contentDescription = "Create new branch",
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("Create New Branch")
                }

                if (filteredBranches.isEmpty()) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 24.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = "No matching branches found",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                } else {
                    LazyColumn(
                        modifier = Modifier.weight(1f),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        if (localBranches.isNotEmpty()) {
                            item {
                                Text(
                                    text = "Local Branches",
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.padding(top = 4.dp, bottom = 4.dp)
                                )
                            }
                            items(localBranches) { branch ->
                                BranchItem(
                                    branch = branch,
                                    currentBranch = currentBranch,
                                    isEnabled = !isOperationInProgress,
                                    onClick = { onBranchSelected(branch) }
                                )
                            }
                        }

                        if (remoteBranches.isNotEmpty()) {
                            item {
                                Text(
                                    text = "Remote Branches",
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.padding(top = 8.dp, bottom = 4.dp)
                                )
                            }
                            items(remoteBranches) { branch ->
                                BranchItem(
                                    branch = branch,
                                    currentBranch = currentBranch,
                                    isEnabled = !isOperationInProgress,
                                    onClick = { onBranchSelected(branch) }
                                )
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(
                onClick = onDismissRequest,
                enabled = !isOperationInProgress
            ) {
                Text("Cancel")
            }
        }
    )
}

@Suppress("LongMethod")
@Composable
fun BranchItem(
    branch: GitBranch,
    currentBranch: String,
    isEnabled: Boolean,
    onClick: () -> Unit
) {
    val isCurrent = branch.isCurrent || branch.name == currentBranch
    Surface(
        onClick = onClick,
        enabled = isEnabled,
        shape = RoundedCornerShape(8.dp),
        color = if (isCurrent) {
            MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.6f)
        } else {
            Color.Transparent
        },
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.weight(1f)
            ) {
                Icon(
                    imageVector = if (branch.isRemote) Icons.Default.Cloud else Icons.Default.AccountTree,
                    contentDescription = null,
                    tint = if (isCurrent) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                    modifier = Modifier.size(18.dp)
                )
                Text(
                    text = branch.name,
                    style = MaterialTheme.typography.bodyMedium.copy(
                        fontWeight = if (isCurrent) FontWeight.Bold else FontWeight.Normal
                    ),
                    color = if (isCurrent) {
                        MaterialTheme.colorScheme.onPrimaryContainer
                    } else {
                        MaterialTheme.colorScheme.onSurface
                    },
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            if (isCurrent) {
                Icon(
                    imageVector = Icons.Default.Check,
                    contentDescription = "Active branch",
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(18.dp)
                )
            }
        }
    }
}

@Composable
fun CreateBranchDialog(
    isOperationInProgress: Boolean,
    onConfirm: (String) -> Unit,
    onDismissRequest: () -> Unit
) {
    var branchName by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = { if (!isOperationInProgress) onDismissRequest() },
        title = { Text("Create New Branch") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = "Enter a name for the new branch based on your current HEAD.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                OutlinedTextField(
                    value = branchName,
                    onValueChange = { branchName = it },
                    label = { Text("Branch Name") },
                    placeholder = { Text("e.g. feature/my-feature") },
                    singleLine = true,
                    enabled = !isOperationInProgress,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    if (branchName.isNotBlank()) {
                        onConfirm(branchName.trim())
                    }
                },
                enabled = branchName.isNotBlank() && !isOperationInProgress
            ) {
                if (isOperationInProgress) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(18.dp),
                        strokeWidth = 2.dp
                    )
                } else {
                    Text("Create & Checkout")
                }
            }
        },
        dismissButton = {
            TextButton(
                onClick = onDismissRequest,
                enabled = !isOperationInProgress
            ) {
                Text("Cancel")
            }
        }
    )
}
