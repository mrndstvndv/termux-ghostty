@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)

package com.mrndtvndv.term.ui.review

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountTree
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.CloudUpload
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.PlainTooltip
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TooltipAnchorPosition
import androidx.compose.material3.TooltipBox
import androidx.compose.material3.TooltipDefaults
import androidx.compose.material3.rememberTooltipState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

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
                LoadingIndicator(
                    modifier = Modifier
                        .padding(12.dp)
                        .size(24.dp),
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
                    LoadingIndicator(
                        modifier = Modifier.size(20.dp),
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
                    LoadingIndicator(
                        modifier = Modifier.size(18.dp),
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
