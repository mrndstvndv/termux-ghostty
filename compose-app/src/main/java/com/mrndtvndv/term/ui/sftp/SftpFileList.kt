@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)

package com.mrndtvndv.term.ui.sftp

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.InsertDriveFile
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.pulltorefresh.PullToRefreshDefaults
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.mrndtvndv.term.domain.SftpFile
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import kotlin.math.log10
import kotlin.math.pow

@Suppress("LongParameterList")
@Composable
fun SftpDirectory(
    state: SftpUiState,
    isRefreshing: Boolean,
    onRefresh: () -> Unit,
    onOpenFolder: (String) -> Unit,
    onOpenFile: (SftpFile) -> Unit,
    onRename: (SftpFile) -> Unit,
    onDelete: (SftpFile) -> Unit,
    modifier: Modifier = Modifier,
    searchQuery: String = "",
    onClearSearch: () -> Unit = {}
) {
    val pullToRefreshState = rememberPullToRefreshState()
    PullToRefreshBox(
        isRefreshing = isRefreshing,
        state = pullToRefreshState,
        indicator = {
            PullToRefreshDefaults.LoadingIndicator(
                state = pullToRefreshState,
                isRefreshing = isRefreshing,
                modifier = Modifier.align(Alignment.TopCenter),
            )
        },
        onRefresh = onRefresh,
        modifier = modifier.fillMaxSize()
    ) {
        when (state) {
            is SftpUiState.Loading -> {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    LoadingIndicator()
                }
            }
            is SftpUiState.Success -> SftpFileList(
                state = state,
                searchQuery = searchQuery,
                onClearSearch = onClearSearch,
                onOpenFolder = onOpenFolder,
                onOpenFile = onOpenFile,
                onRename = onRename,
                onDelete = onDelete
            )
            is SftpUiState.Error -> {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(16.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = state.message,
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodyLarge
                    )
                }
            }
        }
    }
}

@Composable
private fun SftpFileList(
    state: SftpUiState.Success,
    searchQuery: String,
    onClearSearch: () -> Unit,
    onOpenFolder: (String) -> Unit,
    onOpenFile: (SftpFile) -> Unit,
    onRename: (SftpFile) -> Unit,
    onDelete: (SftpFile) -> Unit
) {
    val filteredFiles = remember(state.files, searchQuery) {
        if (searchQuery.isBlank()) {
            state.files
        } else {
            val query = searchQuery.trim()
            state.files.filter { it.name.contains(query, ignoreCase = true) }
        }
    }

    if (filteredFiles.isEmpty()) {
        SftpEmptyList(
            searchQuery = searchQuery,
            onClearSearch = onClearSearch
        )
    } else {
        SftpFileItems(
            files = filteredFiles,
            gitStatuses = state.gitStatuses,
            onOpenFolder = onOpenFolder,
            onOpenFile = onOpenFile,
            onRename = onRename,
            onDelete = onDelete
        )
    }
}

@Composable
private fun SftpEmptyList(
    searchQuery: String,
    onClearSearch: () -> Unit,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .fillMaxSize()
            .padding(32.dp),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            if (searchQuery.isNotBlank()) {
                Icon(
                    imageVector = Icons.Default.Search,
                    contentDescription = null,
                    modifier = Modifier.size(48.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                )
                Text(
                    text = "No matching files",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Text(
                    text = "No files match \"$searchQuery\"",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                )
                TextButton(onClick = onClearSearch) {
                    Text("Clear search")
                }
            } else {
                Text(
                    text = "Empty folder",
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun SftpFileItems(
    files: List<SftpFile>,
    gitStatuses: Map<String, String>,
    onOpenFolder: (String) -> Unit,
    onOpenFile: (SftpFile) -> Unit,
    onRename: (SftpFile) -> Unit,
    onDelete: (SftpFile) -> Unit
) {
    var menuTargetPath by remember { mutableStateOf<String?>(null) }
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(
            top = 8.dp,
            bottom = 96.dp + WindowInsets.navigationBars
                .asPaddingValues()
                .calculateBottomPadding()
        )
    ) {
        items(files, key = { it.path }) { file ->
            SftpFileRow(
                file = file,
                gitStatus = gitStatuses[file.name]?.trim(),
                menuExpanded = menuTargetPath == file.path,
                onMenuExpandedChange = { expanded -> menuTargetPath = if (expanded) file.path else null },
                onClick = { if (file.isDirectory) onOpenFolder(file.path) else onOpenFile(file) },
                onRename = { onRename(file) },
                onDelete = { onDelete(file) }
            )
            HorizontalDivider(
                thickness = 0.5.dp,
                color = MaterialTheme.colorScheme.outline.copy(alpha = 0.3f)
            )
        }
    }
}

@Composable
private fun SftpFileRow(
    file: SftpFile,
    gitStatus: String?,
    menuExpanded: Boolean,
    onMenuExpandedChange: (Boolean) -> Unit,
    onClick: () -> Unit,
    onRename: () -> Unit,
    onDelete: () -> Unit
) {
    ListItem(
        content = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = file.name,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                if (!gitStatus.isNullOrEmpty()) {
                    GitStatusBadge(gitStatus)
                }
            }
        },
        supportingContent = fileDescription(file)?.let { description ->
            {
                Text(
                    text = description,
                    style = MaterialTheme.typography.bodySmall
                )
            }
        },
        leadingContent = {
            Icon(
                imageVector = if (file.isDirectory) {
                    Icons.Default.Folder
                } else {
                    Icons.AutoMirrored.Filled.InsertDriveFile
                },
                contentDescription = null,
                tint = if (file.isDirectory) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.secondary
                }
            )
        },
        trailingContent = {
            Box {
                IconButton(onClick = { onMenuExpandedChange(true) }) {
                    Icon(
                        imageVector = Icons.Default.MoreVert,
                        contentDescription = "More options",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                SftpFileMenu(
                    expanded = menuExpanded,
                    onDismiss = { onMenuExpandedChange(false) },
                    onRename = onRename,
                    onDelete = onDelete
                )
            }
        },
        modifier = Modifier.clickable(onClick = onClick)
    )
}

@Composable
private fun SftpFileMenu(
    expanded: Boolean,
    onDismiss: () -> Unit,
    onRename: () -> Unit,
    onDelete: () -> Unit
) {
    DropdownMenu(
        expanded = expanded,
        onDismissRequest = onDismiss
    ) {
        DropdownMenuItem(
            text = { Text("Rename") },
            leadingIcon = {
                Icon(
                    imageVector = Icons.Default.Edit,
                    contentDescription = null
                )
            },
            onClick = {
                onDismiss()
                onRename()
            }
        )
        DropdownMenuItem(
            text = { Text("Delete") },
            leadingIcon = {
                Icon(
                    imageVector = Icons.Default.Delete,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.error
                )
            },
            onClick = {
                onDismiss()
                onDelete()
            }
        )
    }
}

@Composable
private fun GitStatusBadge(status: String) {
    val color = when {
        status == "??" || status.contains("A") -> Color(0xFF4CAF50)
        status.contains("D") -> Color(0xFFF44336)
        else -> Color(0xFFFF9800)
    }
    Surface(
        color = color.copy(alpha = 0.2f),
        shape = RoundedCornerShape(4.dp)
    ) {
        Text(
            text = status,
            color = color,
            style = MaterialTheme.typography.labelSmall,
            modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp)
        )
    }
}

private fun fileDescription(file: SftpFile): String? {
    if (file.isDirectory) return null
    val size = formatBytes(file.size)
    val lastModified = formatLastModified(file.modifiedTime)
    return if (lastModified.isNotEmpty()) "$size • $lastModified" else size
}

internal fun formatBytes(bytes: Long): String {
    if (bytes <= 0) return "0 B"
    val units = arrayOf("B", "KB", "MB", "GB", "TB", "PB", "EB")
    val digitGroups = (log10(bytes.toDouble()) / log10(1024.0)).toInt().coerceIn(0, units.size - 1)
    if (digitGroups == 0) return "$bytes B"
    return String.format(Locale.US, "%.2f %s", bytes / 1024.0.pow(digitGroups), units[digitGroups])
}

private fun formatLastModified(mtime: Long): String {
    if (mtime <= 0) return ""
    val formatter = SimpleDateFormat("yyyy-MM-dd hh:mm:ss a", Locale.getDefault()).apply {
        timeZone = TimeZone.getDefault()
    }
    return formatter.format(Date(mtime))
}
