@file:Suppress("TooManyFunctions")

package com.mrndtvndv.term.ui.review

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.expandHorizontally
import androidx.navigation3.runtime.NavBackStack
import androidx.navigation3.runtime.NavKey
import androidx.navigation3.runtime.entryProvider
import androidx.navigation3.runtime.rememberNavBackStack
import androidx.navigation3.ui.NavDisplay
import com.mrndtvndv.term.ui.CodeMatch
import com.mrndtvndv.term.ui.ReviewNavKey
import com.mrndtvndv.term.ui.buildHighlighted
import com.mrndtvndv.term.ui.theme.codeFontFamily
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.shrinkHorizontally
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
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.gestures.rememberTransformableState
import androidx.compose.foundation.gestures.transformable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.DisableSelection
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.InsertDriveFile
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.zIndex

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
    val diffContent by viewModel.diffContent.collectAsState()
    val isFullFileMode by viewModel.isFullFileMode.collectAsState()
    val showLineNumbers by viewModel.showLineNumbers.collectAsState()
    val isWordDiffEnabled by viewModel.isWordDiffEnabled.collectAsState()
    val isStagedExpanded by viewModel.isStagedExpanded.collectAsState()
    val isUnstagedExpanded by viewModel.isUnstagedExpanded.collectAsState()
    val isCommitsExpanded by viewModel.isCommitsExpanded.collectAsState()
    val isRefreshing by viewModel.isRefreshing.collectAsState()
    val errorMessage by viewModel.errorMessage.collectAsState()
    val isCommitInProgress by viewModel.isCommitInProgress.collectAsState()
    val isBranchOperationInProgress by viewModel.isBranchOperationInProgress.collectAsState()
    val isSyncInProgress by viewModel.isSyncInProgress.collectAsState()
    val keyboardController = LocalSoftwareKeyboardController.current

    val reviewBackStack: NavBackStack<NavKey> = rememberNavBackStack(ReviewNavKey.ChangesList)

    var discardConfirmFile by remember { mutableStateOf<GitFileStatus?>(null) }
    var showCommitDialog by remember { mutableStateOf(false) }
    var commitMessage by remember { mutableStateOf("") }
    var renameCommitTarget by remember { mutableStateOf<GitCommit?>(null) }
    var renameCommitSubject by remember { mutableStateOf("") }
    var softResetTarget by remember { mutableStateOf<GitCommit?>(null) }
    var hardResetTarget by remember { mutableStateOf<GitCommit?>(null) }
    var showBranchDialog by remember { mutableStateOf(false) }
    var showCreateBranchDialog by remember { mutableStateOf(false) }
    var isDiffSearchVisible by remember { mutableStateOf(false) }
    var diffSearchQuery by remember { mutableStateOf("") }
    var diffSearchMatchIndex by remember { mutableIntStateOf(0) }
    var diffSearchMatchCount by remember { mutableIntStateOf(0) }

    val closeDiffSearch = {
        keyboardController?.hide()
        isDiffSearchVisible = false
        diffSearchQuery = ""
        diffSearchMatchIndex = 0
        diffSearchMatchCount = 0
    }
    val updateDiffSearchQuery: (String) -> Unit = { query ->
        diffSearchQuery = query
        diffSearchMatchIndex = 0
    }

    fun moveDiffSearchMatch(offset: Int) {
        if (diffSearchMatchCount == 0) return
        diffSearchMatchIndex =
            (diffSearchMatchIndex + offset + diffSearchMatchCount) % diffSearchMatchCount
    }

    val stagedFileCount = (uiState as? ReviewUiState.Success)?.stagedFiles?.size ?: 0

    if (renameCommitTarget != null) {
        AlertDialog(
            onDismissRequest = { if (!isCommitInProgress) renameCommitTarget = null },
            title = { Text("Edit Commit Message") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        text = "Commit ${renameCommitTarget?.shortHash}",
                        style = MaterialTheme.typography.bodySmall.copy(fontFamily = codeFontFamily()),
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

    if (softResetTarget != null) {
        AlertDialog(
            onDismissRequest = { if (!isCommitInProgress) softResetTarget = null },
            title = { Text("Soft Reset to Commit") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        text = "Reset to ${softResetTarget?.shortHash}?",
                        style = MaterialTheme.typography.bodySmall.copy(fontFamily = codeFontFamily()),
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                    )
                    Text(
                        "Commits after ${softResetTarget?.shortHash} will be undone and their changes " +
                            "moved to the staged area. Working tree changes are kept."
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        val commit = softResetTarget
                        softResetTarget = null
                        if (commit != null) viewModel.softReset(commit)
                    },
                    enabled = !isCommitInProgress
                ) {
                    if (isCommitInProgress) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(18.dp),
                            strokeWidth = 2.dp
                        )
                    } else {
                        Text("Reset")
                    }
                }
            },
            dismissButton = {
                TextButton(
                    onClick = { softResetTarget = null },
                    enabled = !isCommitInProgress
                ) {
                    Text("Cancel")
                }
            }
        )
    }

    if (hardResetTarget != null) {
        AlertDialog(
            onDismissRequest = { if (!isCommitInProgress) hardResetTarget = null },
            title = { Text("Hard Reset to Commit") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        text = "Reset to ${hardResetTarget?.shortHash}?",
                        style = MaterialTheme.typography.bodySmall.copy(fontFamily = codeFontFamily()),
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                    )
                    Text(
                        "All commits after ${hardResetTarget?.shortHash} AND all uncommitted changes " +
                            "will be permanently discarded. This cannot be undone."
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        val commit = hardResetTarget
                        hardResetTarget = null
                        if (commit != null) viewModel.hardReset(commit)
                    },
                    enabled = !isCommitInProgress,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.error,
                        contentColor = MaterialTheme.colorScheme.onError
                    )
                ) {
                    if (isCommitInProgress) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(18.dp),
                            strokeWidth = 2.dp
                        )
                    } else {
                        Text("Delete & Reset")
                    }
                }
            },
            dismissButton = {
                TextButton(
                    onClick = { hardResetTarget = null },
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

        // Mobile layout: use Navigation 3 backStack for list vs diff view
        NavDisplay(
            backStack = reviewBackStack,
            onBack = {
                if (isTabActive && reviewBackStack.size > 1) {
                    closeDiffSearch()
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
                            closeDiffSearch()
                            viewModel.selectFile(file)
                            reviewBackStack.add(ReviewNavKey.FileDiff(file.path, file.isStaged))
                        },
                        onCommitSelected = { commit ->
                            closeDiffSearch()
                            viewModel.selectCommit(commit)
                            reviewBackStack.add(ReviewNavKey.CommitDiff(commit.hash))
                        },
                        onRenameCommitClick = { commit ->
                            renameCommitTarget = commit
                            renameCommitSubject = commit.subject
                        },
                        onSoftResetClick = { softResetTarget = it },
                        onHardResetClick = { hardResetTarget = it },
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
                        isSyncInProgress = isSyncInProgress,
                        onFetch = { viewModel.fetchRemote() },
                        onPull = { viewModel.pullBranch() },
                        onPush = { viewModel.pushBranch() },
                        modifier = modifier
                    )
                }
                entry<ReviewNavKey.FileDiff> { navKey ->
                    Scaffold(
                        modifier = modifier.fillMaxSize(),
                        contentWindowInsets = WindowInsets(0, 0, 0, 0),
                        topBar = {
                            TopAppBar(
                                windowInsets = WindowInsets(0, 0, 0, 0),
                                title = {
                                    if (isDiffSearchVisible) {
                                        DiffSearchField(
                                            query = diffSearchQuery,
                                            onQueryChange = updateDiffSearchQuery,
                                            onNext = { moveDiffSearchMatch(1) }
                                        )
                                    } else {
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
                                    }
                                },
                                navigationIcon = {
                                    IconButton(onClick = {
                                        closeDiffSearch()
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
                                    if (isDiffSearchVisible) {
                                        DiffSearchActions(
                                            query = diffSearchQuery,
                                            currentMatchIndex = diffSearchMatchIndex,
                                            matchCount = diffSearchMatchCount,
                                            onPrevious = { moveDiffSearchMatch(-1) },
                                            onNext = { moveDiffSearchMatch(1) },
                                            onClose = closeDiffSearch
                                        )
                                    } else {
                                        Row(
                                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                                            verticalAlignment = Alignment.CenterVertically,
                                            modifier = Modifier.padding(end = 8.dp)
                                        ) {
                                            IconButton(onClick = { isDiffSearchVisible = true }) {
                                                Icon(
                                                    imageVector = Icons.Default.Search,
                                                    contentDescription = "Search diff"
                                                )
                                            }
                                            WordDiffToggle(
                                                isEnabled = isWordDiffEnabled,
                                                onToggle = { viewModel.toggleWordDiff() }
                                            )
                                            TooltipBox(
                                                positionProvider = TooltipDefaults.rememberTooltipPositionProvider(
                                                    positioning = TooltipAnchorPosition.Above
                                                ),
                                                tooltip = { PlainTooltip { Text("Line Numbers") } },
                                                state = rememberTooltipState()
                                            ) {
                                                IconToggleButton(
                                                    checked = showLineNumbers,
                                                    onCheckedChange = { viewModel.toggleLineNumbers() }
                                                ) {
                                                    Icon(
                                                        imageVector = Icons.Default.FormatListNumbered,
                                                        contentDescription = "Toggle line numbers",
                                                        modifier = Modifier.size(16.dp)
                                                    )
                                                }
                                            }
                                            TooltipBox(
                                                positionProvider = TooltipDefaults.rememberTooltipPositionProvider(
                                                    positioning = TooltipAnchorPosition.Above
                                                ),
                                                tooltip = {
                                                    PlainTooltip {
                                                        Text(if (isFullFileMode) "Full File" else "Diff Only")
                                                    }
                                                },
                                                state = rememberTooltipState()
                                            ) {
                                                IconToggleButton(
                                                    checked = isFullFileMode,
                                                    onCheckedChange = { viewModel.toggleFullFileMode() }
                                                ) {
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
                                            }
                                        }
                                    }
                                },
                                colors = TopAppBarDefaults.topAppBarColors(
                                    containerColor = MaterialTheme.colorScheme.surface
                                )
                            )
                        }
                    ) { padding ->
                        Box(modifier = Modifier.fillMaxSize().padding(padding)) {
                            DiffViewer(
                                selectedFile = selectedFile,
                                selectedCommit = selectedCommit,
                                diffContent = diffContent,
                                isFullFileMode = isFullFileMode,
                                onToggleFullFileMode = { viewModel.toggleFullFileMode() },
                                showLineNumbers = showLineNumbers,
                                onToggleLineNumbers = { viewModel.toggleLineNumbers() },
                                isWordDiffEnabled = isWordDiffEnabled,
                                onToggleWordDiff = { viewModel.toggleWordDiff() },
                                searchVisible = isDiffSearchVisible,
                                searchQuery = diffSearchQuery,
                                searchMatchIndex = diffSearchMatchIndex,
                                onSearchMatchCountChange = { diffSearchMatchCount = it },
                                onSearchMatchIndexChange = { diffSearchMatchIndex = it },
                                showHeader = false
                            )
                        }
                    }
                }
                entry<ReviewNavKey.CommitDiff> { navKey ->
                    Scaffold(
                        modifier = modifier.fillMaxSize(),
                        contentWindowInsets = WindowInsets(0, 0, 0, 0),
                        topBar = {
                            TopAppBar(
                                windowInsets = WindowInsets(0, 0, 0, 0),
                                title = {
                                    if (isDiffSearchVisible) {
                                        DiffSearchField(
                                            query = diffSearchQuery,
                                            onQueryChange = updateDiffSearchQuery,
                                            onNext = { moveDiffSearchMatch(1) }
                                        )
                                    } else {
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
                                    }
                                },
                                navigationIcon = {
                                    IconButton(onClick = {
                                        closeDiffSearch()
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
                                    if (isDiffSearchVisible) {
                                        DiffSearchActions(
                                            query = diffSearchQuery,
                                            currentMatchIndex = diffSearchMatchIndex,
                                            matchCount = diffSearchMatchCount,
                                            onPrevious = { moveDiffSearchMatch(-1) },
                                            onNext = { moveDiffSearchMatch(1) },
                                            onClose = closeDiffSearch
                                        )
                                    } else {
                                        Row(
                                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                                            verticalAlignment = Alignment.CenterVertically,
                                            modifier = Modifier.padding(end = 8.dp)
                                        ) {
                                            IconButton(onClick = { isDiffSearchVisible = true }) {
                                                Icon(
                                                    imageVector = Icons.Default.Search,
                                                    contentDescription = "Search diff"
                                                )
                                            }
                                            WordDiffToggle(
                                                isEnabled = isWordDiffEnabled,
                                                onToggle = { viewModel.toggleWordDiff() }
                                            )
                                            TooltipBox(
                                                positionProvider = TooltipDefaults.rememberTooltipPositionProvider(
                                                    positioning = TooltipAnchorPosition.Above
                                                ),
                                                tooltip = { PlainTooltip { Text("Line Numbers") } },
                                                state = rememberTooltipState()
                                            ) {
                                                IconToggleButton(
                                                    checked = showLineNumbers,
                                                    onCheckedChange = { viewModel.toggleLineNumbers() }
                                                ) {
                                                    Icon(
                                                        imageVector = Icons.Default.FormatListNumbered,
                                                        contentDescription = "Toggle line numbers",
                                                        modifier = Modifier.size(16.dp)
                                                    )
                                                }
                                            }
                                        }
                                    }
                                },
                                colors = TopAppBarDefaults.topAppBarColors(
                                    containerColor = MaterialTheme.colorScheme.surface
                                )
                            )
                        }
                    ) { padding ->
                        Box(modifier = Modifier.fillMaxSize().padding(padding)) {
                            DiffViewer(
                                selectedFile = selectedFile,
                                selectedCommit = selectedCommit,
                                diffContent = diffContent,
                                isFullFileMode = isFullFileMode,
                                onToggleFullFileMode = { viewModel.toggleFullFileMode() },
                                showLineNumbers = showLineNumbers,
                                onToggleLineNumbers = { viewModel.toggleLineNumbers() },
                                isWordDiffEnabled = isWordDiffEnabled,
                                onToggleWordDiff = { viewModel.toggleWordDiff() },
                                searchVisible = isDiffSearchVisible,
                                searchQuery = diffSearchQuery,
                                searchMatchIndex = diffSearchMatchIndex,
                                onSearchMatchCountChange = { diffSearchMatchCount = it },
                                onSearchMatchIndexChange = { diffSearchMatchIndex = it },
                                showHeader = false
                            )
                        }
                    }
                }
            }
        )
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
                    fontFamily = codeFontFamily()
                )
            )
        }
    }
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
    onSoftResetClick: (GitCommit) -> Unit = {},
    onHardResetClick: (GitCommit) -> Unit = {},
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
    isSyncInProgress: Boolean = false,
    onFetch: () -> Unit = {},
    onPull: () -> Unit = {},
    onPush: () -> Unit = {},
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
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        floatingActionButton = {
            AnimatedVisibility(
                visible = stagedFiles.isNotEmpty() && !inSelectionMode,
                enter = scaleIn(initialScale = 0.6f) + fadeIn(),
                exit = scaleOut(targetScale = 0.6f) + fadeOut()
            ) {
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
                                    aheadCount = uiState.aheadCount,
                                    behindCount = uiState.behindCount,
                                    isSyncInProgress = isSyncInProgress,
                                    onBranchClick = onBranchHeaderClick,
                                    onFetch = onFetch,
                                    onPull = onPull,
                                    onPush = onPush
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
                                            onRenameClick = { onRenameCommitClick(commit) },
                                            onSoftResetClick = { onSoftResetClick(commit) },
                                            onHardResetClick = { onHardResetClick(commit) }
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
    val bg by animateColorAsState(
        targetValue = when {
            isChecked -> MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.5f)
            isSelected -> MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.4f)
            else -> Color.Transparent
        },
        label = "fileItemBackground"
    )
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
            AnimatedVisibility(
                visible = inSelectionMode,
                enter = expandHorizontally(expandFrom = Alignment.Start) + fadeIn(),
                exit = shrinkHorizontally(shrinkTowards = Alignment.Start) + fadeOut()
            ) {
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
            AnimatedVisibility(
                visible = !inSelectionMode,
                enter = expandHorizontally(expandFrom = Alignment.End) + fadeIn(),
                exit = shrinkHorizontally(shrinkTowards = Alignment.End) + fadeOut()
            ) {
                Row {
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
    onRenameClick: () -> Unit = {},
    onSoftResetClick: () -> Unit = {},
    onHardResetClick: () -> Unit = {}
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
                Surface(
                    shape = RoundedCornerShape(4.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant,
                    modifier = Modifier.padding(end = 8.dp)
                ) {
                    Text(
                        text = commit.shortHash,
                        style = MaterialTheme.typography.labelSmall.copy(fontFamily = codeFontFamily()),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                    )
                }
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = commit.subject,
                        style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Medium),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            text = commit.author,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f)
                        )
                        Text(
                            text = commit.relativeDate,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
                        )
                    }
                }
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
            DropdownMenuItem(
                text = { Text("Soft Reset to Here") },
                leadingIcon = {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.Undo,
                        contentDescription = "Soft Reset to Here"
                    )
                },
                onClick = {
                    showMenu = false
                    onSoftResetClick()
                }
            )
            DropdownMenuItem(
                text = { Text("Hard Reset to Here") },
                leadingIcon = {
                    Icon(
                        imageVector = Icons.Default.DeleteForever,
                        contentDescription = "Hard Reset to Here",
                        tint = MaterialTheme.colorScheme.error
                    )
                },
                onClick = {
                    showMenu = false
                    onHardResetClick()
                }
            )
        }
    }
}

@Composable
fun CommitDiffHeader(
    commit: GitCommit,
    isWordDiffEnabled: Boolean = true,
    onToggleWordDiff: () -> Unit = {}
) {
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
                WordDiffToggle(
                    isEnabled = isWordDiffEnabled,
                    onToggle = onToggleWordDiff
                )
                Surface(
                    shape = RoundedCornerShape(4.dp),
                    color = MaterialTheme.colorScheme.primaryContainer,
                    modifier = Modifier.padding(start = 8.dp)
                ) {
                    Text(
                        text = commit.shortHash,
                        style = MaterialTheme.typography.labelSmall.copy(fontFamily = codeFontFamily()),
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

@Composable
@OptIn(ExperimentalMaterial3Api::class)
private fun WordDiffToggle(
    isEnabled: Boolean,
    onToggle: () -> Unit
) {
    TooltipBox(
        positionProvider = TooltipDefaults.rememberTooltipPositionProvider(
            positioning = TooltipAnchorPosition.Above
        ),
        tooltip = {
            PlainTooltip {
                Text(if (isEnabled) "Word-level diff on" else "Word-level diff off")
            }
        },
        state = rememberTooltipState()
    ) {
        IconToggleButton(
            checked = isEnabled,
            onCheckedChange = { onToggle() }
        ) {
            Icon(
                imageVector = Icons.Default.CompareArrows,
                contentDescription = if (isEnabled) {
                    "Disable word-level diff"
                } else {
                    "Enable word-level diff"
                },
                modifier = Modifier.size(16.dp)
            )
        }
    }
}

@Suppress("LongMethod")
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DiffHeader(
    selectedFile: GitFileStatus,
    isFullFileMode: Boolean,
    onToggleFullFileMode: () -> Unit,
    showLineNumbers: Boolean = true,
    onToggleLineNumbers: () -> Unit = {},
    isWordDiffEnabled: Boolean = true,
    onToggleWordDiff: () -> Unit = {}
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
                WordDiffToggle(
                    isEnabled = isWordDiffEnabled,
                    onToggle = onToggleWordDiff
                )
                TooltipBox(
                    positionProvider = TooltipDefaults.rememberTooltipPositionProvider(
                        positioning = TooltipAnchorPosition.Above
                    ),
                    tooltip = { PlainTooltip { Text("Line Numbers") } },
                    state = rememberTooltipState()
                ) {
                    IconToggleButton(
                        checked = showLineNumbers,
                        onCheckedChange = { onToggleLineNumbers() }
                    ) {
                        Icon(
                            imageVector = Icons.Default.FormatListNumbered,
                            contentDescription = "Toggle line numbers",
                            modifier = Modifier.size(16.dp)
                        )
                    }
                }
                TooltipBox(
                    positionProvider = TooltipDefaults.rememberTooltipPositionProvider(
                        positioning = TooltipAnchorPosition.Above
                    ),
                    tooltip = { PlainTooltip { Text(if (isFullFileMode) "Full File" else "Diff Only") } },
                    state = rememberTooltipState()
                ) {
                    IconToggleButton(
                        checked = isFullFileMode,
                        onCheckedChange = { onToggleFullFileMode() }
                    ) {
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
                }
            }
        }
    }
    HorizontalDivider(thickness = 0.5.dp, color = MaterialTheme.colorScheme.outline.copy(alpha = 0.2f))
}

@Suppress("LongParameterList", "LongMethod")
@Composable
internal fun DiffViewer(
    selectedFile: GitFileStatus?,
    selectedCommit: GitCommit? = null,
    diffContent: DiffContentState?,
    isFullFileMode: Boolean = false,
    onToggleFullFileMode: () -> Unit = {},
    showLineNumbers: Boolean = true,
    onToggleLineNumbers: () -> Unit = {},
    isWordDiffEnabled: Boolean = true,
    onToggleWordDiff: () -> Unit = {},
    searchVisible: Boolean = false,
    searchQuery: String = "",
    searchMatchIndex: Int = 0,
    onSearchMatchCountChange: (Int) -> Unit = {},
    onSearchMatchIndexChange: (Int) -> Unit = {},
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
                    onToggleLineNumbers = onToggleLineNumbers,
                    isWordDiffEnabled = isWordDiffEnabled,
                    onToggleWordDiff = onToggleWordDiff
                )
            } else if (selectedCommit != null) {
                CommitDiffHeader(
                    commit = selectedCommit,
                    isWordDiffEnabled = isWordDiffEnabled,
                    onToggleWordDiff = onToggleWordDiff
                )
            }
        }

        Box(modifier = Modifier.fillMaxSize().weight(1f)) {
            when (val content = diffContent) {
                null -> EmptyDiffMessage("No diff details loaded.")
                is DiffContentState.Loading -> {
                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator()
                    }
                }
                is DiffContentState.Error -> EmptyDiffMessage(content.message)
                is DiffContentState.Ready -> {
                    if (content.sections.isEmpty()) {
                        EmptyDiffMessage("No changes detected in file.")
                    } else {
                        DiffContent(
                            sections = content.sections,
                            showLineNumbers = showLineNumbers,
                            isWordDiffEnabled = isWordDiffEnabled,
                            searchVisible = searchVisible,
                            searchQuery = searchQuery,
                            searchMatchIndex = searchMatchIndex,
                            onSearchMatchCountChange = onSearchMatchCountChange,
                            onSearchMatchIndexChange = onSearchMatchIndexChange
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun EmptyDiffMessage(message: String) {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text(message)
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
                        fontFamily = codeFontFamily()
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

@Suppress("LongMethod", "LongParameterList")
@Composable
private fun DiffContent(
    sections: List<DiffSectionView>,
    showLineNumbers: Boolean = true,
    isWordDiffEnabled: Boolean = true,
    searchVisible: Boolean = false,
    searchQuery: String = "",
    searchMatchIndex: Int = 0,
    onSearchMatchCountChange: (Int) -> Unit = {},
    onSearchMatchIndexChange: (Int) -> Unit = {},
    modifier: Modifier = Modifier
) {
    var fontScale by remember { mutableFloatStateOf(1f) }
    val transformState = rememberTransformableState { _, zoomChange, _, _ ->
        fontScale = (fontScale * zoomChange).coerceIn(0.6f, 3.0f)
    }

    val horizScrollState = rememberScrollState()
    val isDark = MaterialTheme.colorScheme.background.luminance() < 0.5f
    val fallbackColor = MaterialTheme.colorScheme.onSurface

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

    val allRowsBySection = remember(sections, isWordDiffEnabled) {
        sections.map { section -> flattenRows(section, isWordDiffEnabled) }
    }
    val rowsBySection = remember(allRowsBySection, collapsedSections) {
        sections.mapIndexed { index, section ->
            if (section.filePath in collapsedSections) emptyList() else allRowsBySection[index]
        }
    }
    val activeSearchQuery = searchQuery.takeIf { searchVisible }.orEmpty()
    val searchMatches = remember(allRowsBySection, activeSearchQuery) {
        findDiffSearchMatches(allRowsBySection, activeSearchQuery)
    }
    LaunchedEffect(searchMatches.size) {
        onSearchMatchCountChange(searchMatches.size)
        val adjustedIndex = if (searchMatches.isEmpty()) {
            0
        } else {
            searchMatchIndex.coerceIn(0, searchMatches.lastIndex)
        }
        if (adjustedIndex != searchMatchIndex) {
            onSearchMatchIndexChange(adjustedIndex)
        }
    }
    val currentSearchMatch = searchMatches.getOrNull(searchMatchIndex)
    val matchesByRow = remember(searchMatches) {
        searchMatches.groupBy { it.sectionIndex to it.rowIndex }
    }
    val listState = rememberLazyListState()

    LaunchedEffect(currentSearchMatch, collapsedSections) {
        if (!searchVisible || currentSearchMatch == null) return@LaunchedEffect

        val section = sections.getOrNull(currentSearchMatch.sectionIndex) ?: return@LaunchedEffect
        if (section.filePath in collapsedSections) {
            collapsedSections = collapsedSections - section.filePath
            return@LaunchedEffect
        }

        val targetItemIndex = diffRowItemIndex(
            rowsBySection = rowsBySection,
            sectionIndex = currentSearchMatch.sectionIndex,
            rowIndex = currentSearchMatch.rowIndex
        )
        listState.animateScrollToItem(targetItemIndex)
    }

    Column(modifier = modifier.fillMaxSize()) {
        BoxWithConstraints(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.1f))
                .transformable(state = transformState)
        ) {
            val viewportWidth = maxWidth
            SelectionContainer(modifier = Modifier.fillMaxSize()) {
                LazyColumn(
                    state = listState,
                    modifier = Modifier
                        .fillMaxSize()
                        .horizontalScroll(horizScrollState)
                ) {
                sections.forEachIndexed { sectionIndex, section ->
                    item(key = "header:$sectionIndex") {
                        DisableSelection {
                            Box(
                                modifier = Modifier
                                    .width(viewportWidth)
                                    .offset { IntOffset(horizScrollState.value, 0) }
                                    .zIndex(1f)
                            ) {
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
                            }
                        }
                    }
                    items(
                        count = rowsBySection[sectionIndex].size,
                        key = { rowIndex -> "row:$sectionIndex:$rowIndex" }
                    ) { rowIndex ->
                        val rowMatches = matchesByRow[sectionIndex to rowIndex].orEmpty()
                        val searchHighlights = rowMatches.map { match ->
                            DiffSearchHighlight(
                                range = match.range,
                                isCurrent = match == currentSearchMatch
                            )
                        }
                        DiffRowItem(
                            row = rowsBySection[sectionIndex][rowIndex],
                            dims = dims,
                            horizScrollState = horizScrollState,
                            isDark = isDark,
                            fallbackColor = fallbackColor,
                            showLineNumbers = showLineNumbers,
                            minimumWidth = viewportWidth,
                            searchHighlights = searchHighlights
                        )
                    }
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
}

@Composable
private fun DiffSearchField(
    query: String,
    onQueryChange: (String) -> Unit,
    onNext: () -> Unit
) {
    val focusRequester = remember { FocusRequester() }
    val keyboardController = LocalSoftwareKeyboardController.current

    LaunchedEffect(Unit) {
        focusRequester.requestFocus()
        keyboardController?.show()
    }

    OutlinedTextField(
        value = query,
        onValueChange = onQueryChange,
        modifier = Modifier
            .fillMaxWidth()
            .focusRequester(focusRequester),
        placeholder = { Text("Search diff") },
        leadingIcon = {
            Icon(
                imageVector = Icons.Default.Search,
                contentDescription = "Search diff"
            )
        },
        trailingIcon = {
            if (query.isNotEmpty()) {
                IconButton(onClick = { onQueryChange("") }) {
                    Icon(
                        imageVector = Icons.Default.Clear,
                        contentDescription = "Clear search"
                    )
                }
            }
        },
        singleLine = true,
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
        keyboardActions = KeyboardActions(onSearch = { onNext() })
    )
}

@Composable
private fun DiffSearchActions(
    query: String,
    currentMatchIndex: Int,
    matchCount: Int,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
    onClose: () -> Unit
) {
    val matchLabel = when {
        query.isEmpty() -> null
        matchCount == 0 -> "No matches"
        else -> "${currentMatchIndex + 1}/$matchCount"
    }

    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.padding(end = 8.dp)
    ) {
        matchLabel?.let { label ->
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                modifier = Modifier.padding(horizontal = 4.dp)
            )
        }
        IconButton(
            onClick = onPrevious,
            enabled = matchCount > 0
        ) {
            Icon(
                imageVector = Icons.Default.KeyboardArrowUp,
                contentDescription = "Previous match"
            )
        }
        IconButton(
            onClick = onNext,
            enabled = matchCount > 0
        ) {
            Icon(
                imageVector = Icons.Default.KeyboardArrowDown,
                contentDescription = "Next match"
            )
        }
        IconButton(onClick = onClose) {
            Icon(
                imageVector = Icons.Default.Close,
                contentDescription = "Close search"
            )
        }
    }
}

private data class DiffSearchMatch(
    val sectionIndex: Int,
    val rowIndex: Int,
    val range: TextMatch
)

private data class DiffSearchHighlight(
    val range: TextMatch,
    val isCurrent: Boolean
)

private fun findDiffSearchMatches(
    rowsBySection: List<List<DiffRow>>,
    query: String
): List<DiffSearchMatch> {
    if (query.isEmpty()) return emptyList()

    return buildList {
        rowsBySection.forEachIndexed { sectionIndex, rows ->
            rows.forEachIndexed { rowIndex, row ->
                val (searchableText, prefixLength) = searchableDiffText(row.line)
                findTextMatches(searchableText, query).forEach { match ->
                    add(
                        DiffSearchMatch(
                            sectionIndex = sectionIndex,
                            rowIndex = rowIndex,
                            range = TextMatch(
                                start = match.start + prefixLength,
                                endExclusive = match.endExclusive + prefixLength
                            )
                        )
                    )
                }
            }
        }
    }
}

private fun searchableDiffText(line: ParsedDiffLine): Pair<String, Int> {
    return when (line.type) {
        DiffLineType.ADDITION,
        DiffLineType.DELETION,
        DiffLineType.CONTEXT -> line.text.drop(1) to 1
        DiffLineType.METADATA,
        DiffLineType.HUNK_HEADER -> line.text to 0
    }
}

private fun diffRowItemIndex(
    rowsBySection: List<List<DiffRow>>,
    sectionIndex: Int,
    rowIndex: Int
): Int {
    if (sectionIndex !in rowsBySection.indices) return 0

    var itemIndex = 0
    rowsBySection.take(sectionIndex).forEach { rows ->
        itemIndex += rows.size + 1
    }
    return itemIndex + 1 + rowIndex
}

private fun applySearchHighlights(
    text: AnnotatedString,
    highlights: List<DiffSearchHighlight>,
    isDark: Boolean
): AnnotatedString {
    if (highlights.isEmpty()) return text

    return androidx.compose.ui.text.buildAnnotatedString {
        append(text)
        highlights.forEach { highlight ->
            val start = highlight.range.start
            val endExclusive = highlight.range.endExclusive
            if (start >= 0 && start < endExclusive && endExclusive <= text.length) {
                addStyle(
                    style = androidx.compose.ui.text.SpanStyle(
                        background = searchMatchBackground(isDark, highlight.isCurrent)
                    ),
                    start = start,
                    end = endExclusive
                )
            }
        }
    }
}

private fun searchMatchBackground(isDark: Boolean, isCurrent: Boolean): Color {
    if (isCurrent) return Color(0xFFFFB300)
    return if (isDark) Color(0x665E5200) else Color(0xFFFFE082)
}

/**
 * One rendered diff row. [Single] is a single parsed line; [Grouped] is one
 * side of a word-diff pair (its line still carries numbers and type).
 */
private sealed interface DiffRow {
    val line: ParsedDiffLine

    class Single(override val line: ParsedDiffLine) : DiffRow
    class Grouped(
        override val line: ParsedDiffLine,
        val tokens: List<String>,
        val unchanged: BooleanArray,
        val ranges: List<CodeMatch>
    ) : DiffRow
}

/** Flattens a section's grouped rows into the per-row items a lazy list renders. */
private fun flattenRows(section: DiffSectionView, isWordDiffEnabled: Boolean): List<DiffRow> {
    if (!isWordDiffEnabled) {
        return section.lines.map { DiffRow.Single(it) }
    }
    return buildList {
        section.groups.forEach { group ->
            when (group) {
                is DiffRowGroup.Single -> add(DiffRow.Single(group.line))
                is DiffRowGroup.WordDiffPair -> {
                    add(DiffRow.Grouped(group.oldLine, group.oldTokens, group.oldUnchanged, group.oldRanges))
                    add(DiffRow.Grouped(group.newLine, group.newTokens, group.newUnchanged, group.newRanges))
                }
            }
        }
    }
}

private data class ScaledDiffDimensions(
    val numWidth: androidx.compose.ui.unit.Dp,
    val columnWidth: androidx.compose.ui.unit.Dp,
    val lineHeight: androidx.compose.ui.unit.Dp,
    val codeFontSize: androidx.compose.ui.unit.TextUnit,
    val lineNumFontSize: androidx.compose.ui.unit.TextUnit
)

@Suppress("LongMethod", "LongParameterList")
@Composable
private fun DiffRowItem(
    row: DiffRow,
    dims: ScaledDiffDimensions,
    horizScrollState: androidx.compose.foundation.ScrollState,
    isDark: Boolean,
    fallbackColor: Color,
    showLineNumbers: Boolean,
    minimumWidth: androidx.compose.ui.unit.Dp,
    searchHighlights: List<DiffSearchHighlight> = emptyList()
) {
    val line = row.line
    val (bgColor, textColor) = getColors(line.type, isDark, fallbackColor)
    val annotatedText = remember(row, isDark, textColor, searchHighlights) {
        val baseText = when (row) {
            is DiffRow.Single -> buildDiffLineText(line, isDark, textColor)
            is DiffRow.Grouped -> buildWordDiffLineText(
                line, row.tokens, row.unchanged, row.ranges,
                isDark, textColor,
                changedWordBackground(line.type, isDark)
            )
        }
        applySearchHighlights(baseText, searchHighlights, isDark)
    }
    Row(
        modifier = Modifier
            .widthIn(min = minimumWidth)
            .height(dims.lineHeight)
            .background(bgColor)
    ) {
        if (showLineNumbers) {
            DisableSelection {
                LineNumberCell(
                    modifier = Modifier
                        .offset { IntOffset(horizScrollState.value, 0) }
                        .zIndex(1f),
                    line = line,
                    fontSize = dims.lineNumFontSize,
                    numWidth = dims.numWidth,
                    columnWidth = dims.columnWidth,
                    isDark = isDark,
                    fallbackColor = fallbackColor
                )
                Box(
                    modifier = Modifier
                        .offset { IntOffset(horizScrollState.value, 0) }
                        .zIndex(1f)
                        .width(1.dp)
                        .fillMaxHeight()
                        .background(MaterialTheme.colorScheme.outline.copy(alpha = 0.2f))
                )
            }
        }
        Box(
            modifier = Modifier
                .fillMaxHeight()
                .wrapContentWidth(unbounded = true)
                .padding(horizontal = 8.dp),
            contentAlignment = Alignment.CenterStart
        ) {
            Text(
                text = annotatedText,
                fontSize = dims.codeFontSize,
                fontFamily = codeFontFamily(),
                softWrap = false
            )
        }
    }
}

@Composable
private fun LineNumberCell(
    line: ParsedDiffLine,
    fontSize: androidx.compose.ui.unit.TextUnit,
    numWidth: androidx.compose.ui.unit.Dp,
    columnWidth: androidx.compose.ui.unit.Dp,
    isDark: Boolean,
    fallbackColor: Color,
    modifier: Modifier = Modifier
) {
    val (bgColor, _) = getColors(line.type, isDark, fallbackColor)
    Column(
        modifier = modifier
            .width(columnWidth)
            .fillMaxHeight()
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f))
    ) {
        Row(
            modifier = Modifier
                .fillMaxSize()
                .background(bgColor)
                .padding(horizontal = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.End
        ) {
            Text(
                text = line.oldLineNum,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f),
                fontSize = fontSize,
                fontFamily = codeFontFamily(),
                modifier = Modifier.width(numWidth),
                textAlign = androidx.compose.ui.text.style.TextAlign.End
            )
            Spacer(modifier = Modifier.width(4.dp))
            Text(
                text = line.newLineNum,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f),
                fontSize = fontSize,
                fontFamily = codeFontFamily(),
                modifier = Modifier.width(numWidth),
                textAlign = androidx.compose.ui.text.style.TextAlign.End
            )
        }
    }
}

private fun buildDiffLineText(
    line: ParsedDiffLine,
    isDark: Boolean,
    textColor: Color
): AnnotatedString {
    val type = line.type
    return if (type == DiffLineType.ADDITION || type == DiffLineType.DELETION ||
        type == DiffLineType.CONTEXT
    ) {
        val prefix = line.text.take(1)
        val remainingText = line.text.drop(1)

        androidx.compose.ui.text.buildAnnotatedString {
            withStyle(androidx.compose.ui.text.SpanStyle(color = textColor)) {
                append(prefix)
            }
            append(buildHighlighted(remainingText, line.highlightRanges, isDark))
        }
    } else {
        androidx.compose.ui.text.buildAnnotatedString {
            withStyle(androidx.compose.ui.text.SpanStyle(color = textColor)) {
                append(line.text)
            }
        }
    }
}

/**
 * Renders one side of a word-diff pair: syntax highlighting for the whole
 * line, then a stronger background (plus the line's accent color) over the
 * tokens that changed, so the changed words stand out inside the line.
 */
private fun buildWordDiffLineText(
    line: ParsedDiffLine,
    tokens: List<String>,
    unchanged: BooleanArray,
    ranges: List<CodeMatch>,
    isDark: Boolean,
    textColor: Color,
    changedBackground: Color
): AnnotatedString {
    val prefix = line.text.take(1)
    val content = line.text.drop(1)
    val builder = androidx.compose.ui.text.AnnotatedString.Builder()
    builder.withStyle(androidx.compose.ui.text.SpanStyle(color = textColor)) {
        append(prefix)
    }
    builder.append(buildHighlighted(content, ranges, isDark))
    var offset = prefix.length
    tokens.forEachIndexed { index, token ->
        if (!unchanged[index]) {
            builder.addStyle(
                androidx.compose.ui.text.SpanStyle(color = textColor, background = changedBackground),
                offset,
                offset + token.length
            )
        }
        offset += token.length
    }
    return builder.toAnnotatedString()
}

private fun changedWordBackground(type: DiffLineType, isDark: Boolean): Color = when (type) {
    DiffLineType.ADDITION -> if (isDark) Color(0x662EA043) else Color(0xFFACF2BD)
    DiffLineType.DELETION -> if (isDark) Color(0x66F85149) else Color(0xFFFDB8C0)
    else -> Color.Transparent
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

@Suppress("LongMethod", "LongParameterList")
@Composable
fun BranchHeader(
    currentBranch: String,
    onBranchClick: () -> Unit,
    aheadCount: Int = 0,
    behindCount: Int = 0,
    isSyncInProgress: Boolean = false,
    onFetch: () -> Unit = {},
    onPull: () -> Unit = {},
    onPush: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier
            .fillMaxWidth()
            .padding(start = 16.dp, end = 16.dp, bottom = 6.dp),
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                modifier = Modifier
                    .weight(1f)
                    .clickable(onClick = onBranchClick)
                    .padding(vertical = 4.dp)
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
                Column(modifier = Modifier.weight(1f, fill = false)) {
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
            BranchSyncIndicators(
                aheadCount = aheadCount,
                behindCount = behindCount
            )
            if (isSyncInProgress) {
                CircularProgressIndicator(
                    modifier = Modifier
                        .padding(12.dp)
                        .size(24.dp),
                    strokeWidth = 2.dp
                )
            } else {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    SyncActionButton(
                        icon = Icons.Default.Sync,
                        tooltip = "Fetch from remote",
                        onClick = onFetch
                    )
                    SyncActionButton(
                        icon = Icons.Default.CloudDownload,
                        tooltip = "Pull from remote",
                        onClick = onPull
                    )
                    SyncActionButton(
                        icon = Icons.Default.CloudUpload,
                        tooltip = "Push to remote",
                        onClick = onPush
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SyncActionButton(
    icon: ImageVector,
    tooltip: String,
    onClick: () -> Unit
) {
    TooltipBox(
        positionProvider = TooltipDefaults.rememberTooltipPositionProvider(
            positioning = TooltipAnchorPosition.Above
        ),
        tooltip = { PlainTooltip { Text(tooltip) } },
        state = rememberTooltipState()
    ) {
        IconButton(onClick = onClick) {
            Icon(
                imageVector = icon,
                contentDescription = tooltip,
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun BranchSyncIndicators(
    aheadCount: Int,
    behindCount: Int
) {
    if (aheadCount <= 0 && behindCount <= 0) return

    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        if (aheadCount > 0) {
            BranchSyncIndicator(
                arrow = "↑",
                count = aheadCount,
                tint = MaterialTheme.colorScheme.primary
            )
        }
        if (behindCount > 0) {
            BranchSyncIndicator(
                arrow = "↓",
                count = behindCount,
                tint = MaterialTheme.colorScheme.secondary
            )
        }
    }
}

@Composable
private fun BranchSyncIndicator(
    arrow: String,
    count: Int,
    tint: Color
) {
    Text(
        text = "$arrow $count",
        style = MaterialTheme.typography.labelMedium,
        color = tint
    )
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
