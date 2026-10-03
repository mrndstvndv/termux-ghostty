package com.mrndtvndv.term.ui.review

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountTree
import androidx.compose.material.icons.filled.AddCircleOutline
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.CloudUpload
import androidx.compose.material.icons.filled.Commit
import androidx.compose.material.icons.filled.RemoveCircleOutline
import androidx.compose.material.icons.filled.Restore
import androidx.compose.material.icons.filled.SelectAll
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.mrndtvndv.term.ui.components.FloatingPill
import com.mrndtvndv.term.ui.components.PillActions
import com.mrndtvndv.term.ui.components.PillDivider
import com.mrndtvndv.term.ui.components.PillIconButton

private enum class GitBarMode { Sync, Commit, Selection }

/** Git pill: branch switcher, fetch/pull/push and commit; swaps to batch actions while files are selected. */
@Suppress("LongParameterList")
@Composable
internal fun GitActionBar(
    currentBranch: String,
    aheadCount: Int,
    behindCount: Int,
    isSelecting: Boolean,
    canCommit: Boolean,
    isCommitInProgress: Boolean,
    isSyncInProgress: Boolean,
    selectedStagedCount: Int,
    selectedUnstagedCount: Int,
    onBranchClick: () -> Unit,
    onFetch: () -> Unit,
    onPull: () -> Unit,
    onPush: () -> Unit,
    onCommit: () -> Unit,
    onStageSelected: () -> Unit,
    onUnstageSelected: () -> Unit,
    onDiscardSelected: () -> Unit,
    onSelectAll: () -> Unit,
    onClearSelection: () -> Unit,
    modifier: Modifier = Modifier
) {
    val mode = when {
        isSelecting -> GitBarMode.Selection
        canCommit -> GitBarMode.Commit
        else -> GitBarMode.Sync
    }

    FloatingPill(state = mode, modifier = modifier, label = "GitActionBar") { target ->
        PillActions {
            if (target == GitBarMode.Selection) {
                if (selectedUnstagedCount > 0) {
                    PillIconButton(
                        Icons.Default.AddCircleOutline,
                        "Stage selected",
                        onStageSelected,
                        tint = MaterialTheme.colorScheme.primary
                    )
                }
                if (selectedStagedCount > 0) {
                    PillIconButton(
                        Icons.Default.RemoveCircleOutline,
                        "Unstage selected",
                        onUnstageSelected,
                        tint = MaterialTheme.colorScheme.error
                    )
                }
                PillIconButton(Icons.Default.Restore, "Discard selected", onDiscardSelected)
                PillDivider()
                PillIconButton(Icons.Default.SelectAll, "Select all", onSelectAll)
                PillIconButton(Icons.Default.Close, "Clear selection", onClearSelection)
            } else {
                BranchChip(currentBranch, aheadCount, behindCount, onBranchClick)
                PillDivider()
                SyncButtons(isSyncInProgress, onFetch, onPull, onPush)
                if (target == GitBarMode.Commit) {
                    PillDivider()
                    CommitButton(isCommitInProgress, onCommit)
                }
            }
        }
    }
}

@Composable
private fun BranchChip(
    branch: String,
    aheadCount: Int,
    behindCount: Int,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .height(44.dp)
            .clip(RoundedCornerShape(22.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Icon(
            imageVector = Icons.Default.AccountTree,
            contentDescription = "Switch branch",
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(18.dp)
        )
        Text(
            text = branch.ifBlank { "HEAD" },
            style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.widthIn(max = 120.dp)
        )
        if (aheadCount > 0) {
            SyncCount("↑$aheadCount", MaterialTheme.colorScheme.primary)
        }
        if (behindCount > 0) {
            SyncCount("↓$behindCount", MaterialTheme.colorScheme.secondary)
        }
    }
}

@Composable
private fun SyncCount(text: String, color: Color) {
    Text(text = text, style = MaterialTheme.typography.labelMedium, color = color)
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun SyncButtons(
    isSyncInProgress: Boolean,
    onFetch: () -> Unit,
    onPull: () -> Unit,
    onPush: () -> Unit
) {
    if (isSyncInProgress) {
        LoadingIndicator(modifier = Modifier.size(44.dp))
    } else {
        PillIconButton(Icons.Default.Sync, "Fetch from remote", onFetch)
        PillIconButton(Icons.Default.CloudDownload, "Pull from remote", onPull)
        PillIconButton(Icons.Default.CloudUpload, "Push to remote", onPush)
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun CommitButton(
    isCommitInProgress: Boolean,
    onCommit: () -> Unit
) {
    FilledIconButton(
        onClick = { if (!isCommitInProgress) onCommit() },
        modifier = Modifier.size(44.dp)
    ) {
        if (isCommitInProgress) {
            LoadingIndicator(modifier = Modifier.size(24.dp))
        } else {
            Icon(Icons.Default.Commit, contentDescription = "Commit staged changes")
        }
    }
}
