@file:OptIn(
    ExperimentalFoundationApi::class,
    ExperimentalMaterial3Api::class,
    ExperimentalMaterial3ExpressiveApi::class
)

package com.mrndtvndv.term.ui.review

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkHorizontally
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material.icons.filled.AddCircleOutline
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.DeleteForever
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.RemoveCircleOutline
import androidx.compose.material.icons.filled.Restore
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.pulltorefresh.PullToRefreshDefaults
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.mrndtvndv.term.ui.theme.codeFontFamily

private const val MaxRefChips = 2

@Composable
private fun StatusBadge(status: String) {
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

@Suppress("LongParameterList", "LongMethod")
@Composable
internal fun FileChangesList(
    state: ReviewScreenState,
    onToggleStagedExpanded: () -> Unit,
    onToggleUnstagedExpanded: () -> Unit,
    onToggleCommitsExpanded: () -> Unit,
    onFileSelected: (GitFileStatus) -> Unit,
    onCommitSelected: (GitCommit) -> Unit,
    onRenameCommitClick: (GitCommit) -> Unit,
    onSoftResetClick: (GitCommit) -> Unit,
    onHardResetClick: (GitCommit) -> Unit,
    onLoadMoreCommits: () -> Unit,
    onStage: (GitFileStatus) -> Unit,
    onUnstage: (GitFileStatus) -> Unit,
    onDiscard: (GitFileStatus) -> Unit,
    onStageBatch: (List<GitFileStatus>) -> Unit,
    onUnstageBatch: (List<GitFileStatus>) -> Unit,
    onDiscardBatch: (List<GitFileStatus>) -> Unit,
    onCommit: () -> Unit,
    onRefresh: () -> Unit,
    onBranchHeaderClick: () -> Unit,
    onFetch: () -> Unit,
    onPull: () -> Unit,
    onPush: () -> Unit,
    modifier: Modifier = Modifier
) {
    val content = state.content
    val stagedFiles = (content as? ReviewUiState.Success)?.stagedFiles.orEmpty()
    val unstagedFiles = (content as? ReviewUiState.Success)?.unstagedFiles.orEmpty()
    val allFiles = remember(stagedFiles, unstagedFiles) { stagedFiles + unstagedFiles }

    var checkedFiles by remember { mutableStateOf(setOf<GitFileStatus>()) }
    var showBatchDiscardDialog by remember { mutableStateOf(false) }

    LaunchedEffect(allFiles) {
        checkedFiles = checkedFiles.filter { it in allFiles }.toSet()
    }

    if (showBatchDiscardDialog && checkedFiles.isNotEmpty()) {
        BatchDiscardDialog(
            count = checkedFiles.size,
            onConfirm = {
                onDiscardBatch(checkedFiles.toList())
                checkedFiles = emptySet()
                showBatchDiscardDialog = false
            },
            onDismissRequest = { showBatchDiscardDialog = false }
        )
    }

    val inSelectionMode = checkedFiles.isNotEmpty()
    val setChecked: (GitFileStatus, Boolean) -> Unit = { file, checked ->
        checkedFiles = if (checked) checkedFiles + file else checkedFiles - file
    }
    val fileItem: @Composable (GitFileStatus, () -> Unit) -> Unit = { file, onAction ->
        val isChecked = file in checkedFiles
        FileItem(
            file = file,
            isChecked = isChecked,
            inSelectionMode = inSelectionMode,
            onClick = { if (inSelectionMode) setChecked(file, !isChecked) else onFileSelected(file) },
            onLongClick = { setChecked(file, !isChecked) },
            onCheckedChange = { checked -> setChecked(file, checked) },
            onAction = onAction,
            onDiscard = { onDiscard(file) }
        )
    }

    Box(modifier = modifier.fillMaxSize()) {
        val pullToRefreshState = rememberPullToRefreshState()
            PullToRefreshBox(
                isRefreshing = state.isRefreshing,
                state = pullToRefreshState,
                indicator = {
                    PullToRefreshDefaults.LoadingIndicator(
                        state = pullToRefreshState,
                        isRefreshing = state.isRefreshing,
                        modifier = Modifier.align(Alignment.TopCenter),
                    )
                },
                onRefresh = onRefresh,
                modifier = Modifier.fillMaxSize()
            ) {
                when (content) {
                    is ReviewUiState.Loading -> {
                        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            LoadingIndicator()
                        }
                    }
                    is ReviewUiState.Error -> {
                        Box(
                            modifier = Modifier.fillMaxSize().padding(16.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(text = content.message, color = MaterialTheme.colorScheme.error)
                        }
                    }
                    is ReviewUiState.Success -> {
                        val graph = remember(content.recentCommits) { layoutGraph(content.recentCommits) }
                        LazyColumn(
                            modifier = Modifier.fillMaxSize(),
                            contentPadding = PaddingValues(
                                top = 8.dp,
                                bottom = 88.dp + WindowInsets.navigationBars
                                    .asPaddingValues()
                                    .calculateBottomPadding()
                            )
                        ) {
                            if (content.stagedFiles.isEmpty() && content.unstagedFiles.isEmpty()) {
                                item { WorkingTreeClean() }
                            } else {
                                fileSection(
                                    title = "Staged Changes",
                                    files = content.stagedFiles,
                                    isExpanded = state.isStagedExpanded,
                                    onToggleExpanded = onToggleStagedExpanded
                                ) { file -> fileItem(file) { onUnstage(file) } }
                                fileSection(
                                    title = "Unstaged Changes",
                                    files = content.unstagedFiles,
                                    isExpanded = state.isUnstagedExpanded,
                                    onToggleExpanded = onToggleUnstagedExpanded
                                ) { file -> fileItem(file) { onStage(file) } }
                            }

                            if (content.recentCommits.isNotEmpty()) {
                                item {
                                    SectionHeader(
                                        title = "Commit History (${content.recentCommits.size})",
                                        isExpanded = state.isCommitsExpanded,
                                        onToggle = onToggleCommitsExpanded
                                    )
                                }
                                if (state.isCommitsExpanded) {
                                    itemsIndexed(content.recentCommits) { index, commit ->
                                        CommitItem(
                                            commit = commit,
                                            graphRow = graph.rows[index],
                                            graphLaneCount = graph.laneCount,
                                            onClick = { onCommitSelected(commit) },
                                            onRenameClick = { onRenameCommitClick(commit) },
                                            onSoftResetClick = { onSoftResetClick(commit) },
                                            onHardResetClick = { onHardResetClick(commit) }
                                        )
                                    }
                                    if (content.hasMoreCommits) {
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

            if (content is ReviewUiState.Success) {
                val selectedStaged = checkedFiles.filter { it.isStaged }
                val selectedUnstaged = checkedFiles.filter { !it.isStaged }

                GitActionBar(
                    currentBranch = content.currentBranch,
                    aheadCount = content.aheadCount,
                    behindCount = content.behindCount,
                    onBranchClick = onBranchHeaderClick,
                    isSelecting = inSelectionMode,
                    canCommit = stagedFiles.isNotEmpty(),
                    isCommitInProgress = state.isCommitInProgress,
                    isSyncInProgress = state.isSyncInProgress,
                    selectedStagedCount = selectedStaged.size,
                    selectedUnstagedCount = selectedUnstaged.size,
                    onFetch = onFetch,
                    onPull = onPull,
                    onPush = onPush,
                    onCommit = onCommit,
                    onStageSelected = {
                        onStageBatch(selectedUnstaged)
                        checkedFiles = emptySet()
                    },
                    onUnstageSelected = {
                        onUnstageBatch(selectedStaged)
                        checkedFiles = emptySet()
                    },
                    onDiscardSelected = { showBatchDiscardDialog = true },
                    onSelectAll = {
                        checkedFiles = if (checkedFiles.size == allFiles.size) emptySet() else allFiles.toSet()
                    },
                    onClearSelection = { checkedFiles = emptySet() },
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .navigationBarsPadding()
                        .padding(bottom = 16.dp)
                )
            }
    }
}

private fun LazyListScope.fileSection(
    title: String,
    files: List<GitFileStatus>,
    isExpanded: Boolean,
    onToggleExpanded: () -> Unit,
    fileItem: @Composable (GitFileStatus) -> Unit
) {
    if (files.isEmpty()) return
    item {
        SectionHeader(
            title = "$title (${files.size})",
            isExpanded = isExpanded,
            onToggle = onToggleExpanded
        )
    }
    if (isExpanded) {
        items(files) { file -> fileItem(file) }
    }
}

@Composable
private fun WorkingTreeClean() {
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

@Composable
private fun SectionHeader(
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

@Suppress("LongParameterList", "LongMethod")
@Composable
private fun FileItem(
    file: GitFileStatus,
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

@Suppress("LongMethod")
@Composable
private fun CommitItem(
    commit: GitCommit,
    graphRow: GraphRow,
    graphLaneCount: Int,
    onClick: () -> Unit,
    onRenameClick: () -> Unit,
    onSoftResetClick: () -> Unit,
    onHardResetClick: () -> Unit
) {
    var showMenu by remember { mutableStateOf(false) }

    Box(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(IntrinsicSize.Min)
                .combinedClickable(
                    onClick = onClick,
                    onLongClick = { showMenu = true }
                )
                .padding(horizontal = 16.dp)
        ) {
            CommitGraph(graphRow, graphLaneCount, Modifier.fillMaxHeight())
            Spacer(modifier = Modifier.width(8.dp))
            Column(modifier = Modifier.weight(1f).padding(top = 6.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = commit.subject,
                        style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Medium),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false)
                    )
                    commit.refs.take(MaxRefChips).forEach { RefChip(it) }
                }
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
                Spacer(modifier = Modifier.height(4.dp))
                HorizontalDivider(thickness = 0.5.dp, color = MaterialTheme.colorScheme.outline.copy(alpha = 0.15f))
            }
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
private fun RefChip(ref: String) {
    val isHead = ref.startsWith("HEAD")
    Surface(
        shape = RoundedCornerShape(4.dp),
        color = if (isHead) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant,
        modifier = Modifier.padding(start = 6.dp)
    ) {
        Text(
            text = ref.removePrefix("HEAD -> ").removePrefix("tag: "),
            style = MaterialTheme.typography.labelSmall,
            color = if (isHead) {
                MaterialTheme.colorScheme.onPrimaryContainer
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            },
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
        )
    }
}
