@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)

package com.mrndtvndv.term.ui.sftp

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.InsertDriveFile
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.CircularProgressIndicator
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

private val TREE_INDENT = 16.dp

private data class SftpRow(
    val file: SftpFile,
    val top: SftpFile = file,
    val label: String = file.name,
    val depth: Int = 0,
    val isExpanded: Boolean = false,
    val isLoading: Boolean = false
)

private fun compactTail(top: SftpFile, tree: SftpTreeState): SftpFile {
    var tail = top
    while (tail.path in tree.expanded) {
        tail = tree.children[tail.path]
            ?.singleOrNull()
            ?.takeIf { it.isDirectory && it.path in tree.expanded }
            ?: break
    }
    return tail
}

private fun flattenTree(files: List<SftpFile>, tree: SftpTreeState, depth: Int = 0): List<SftpRow> =
    files.flatMap { top ->
        val tail = compactTail(top, tree)
        val isExpanded = top.path in tree.expanded
        val row = SftpRow(
            file = tail,
            top = top,
            label = if (tail === top) top.name else top.name + "/" + tail.path.removePrefix(top.path + "/"),
            depth = depth,
            isExpanded = isExpanded,
            isLoading = tail.path in tree.loading
        )
        if (isExpanded) {
            listOf(row) + flattenTree(tree.children[tail.path].orEmpty(), tree, depth + 1)
        } else {
            listOf(row)
        }
    }

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
    treeMode: Boolean = false,
    tree: SftpTreeState = SftpTreeState(),
    onToggleFolder: (SftpFile) -> Unit = {},
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
                treeMode = treeMode,
                tree = tree,
                searchQuery = searchQuery,
                onClearSearch = onClearSearch,
                onToggleFolder = onToggleFolder,
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

@Suppress("LongParameterList")
@Composable
private fun SftpFileList(
    state: SftpUiState.Success,
    treeMode: Boolean,
    tree: SftpTreeState,
    searchQuery: String,
    onClearSearch: () -> Unit,
    onToggleFolder: (SftpFile) -> Unit,
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

    val showTree = treeMode && searchQuery.isBlank()
    val rows = remember(filteredFiles, tree, showTree) {
        if (showTree) flattenTree(filteredFiles, tree) else filteredFiles.map { SftpRow(it) }
    }

    if (rows.isEmpty()) {
        SftpEmptyList(
            searchQuery = searchQuery,
            onClearSearch = onClearSearch
        )
    } else {
        SftpFileItems(
            rows = rows,
            rootPath = state.currentPath,
            showTree = showTree,
            gitStatuses = state.gitStatuses,
            onToggleFolder = onToggleFolder,
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

@Suppress("LongParameterList")
@Composable
private fun SftpFileItems(
    rows: List<SftpRow>,
    rootPath: String,
    showTree: Boolean,
    gitStatuses: Map<String, String>,
    onToggleFolder: (SftpFile) -> Unit,
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
        items(rows, key = { it.top.path }) { row ->
            val file = row.file
            SftpFileRow(
                row = row,
                showTree = showTree,
                gitStatus = gitStatuses[file.path.removePrefix(rootPath.trimEnd('/') + "/")]?.trim(),
                menuExpanded = menuTargetPath == file.path,
                onMenuExpandedChange = { expanded -> menuTargetPath = if (expanded) file.path else null },
                onClick = {
                    when {
                        !file.isDirectory -> onOpenFile(file)
                        showTree -> onToggleFolder(row.top)
                        else -> onOpenFolder(file.path)
                    }
                },
                onOpenFolder = { onOpenFolder(file.path) },
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

@Suppress("LongParameterList")
@Composable
private fun SftpFileRow(
    row: SftpRow,
    showTree: Boolean,
    gitStatus: String?,
    menuExpanded: Boolean,
    onMenuExpandedChange: (Boolean) -> Unit,
    onClick: () -> Unit,
    onOpenFolder: () -> Unit,
    onRename: () -> Unit,
    onDelete: () -> Unit
) {
    val file = row.file
    ListItem(
        content = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = row.label,
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
        leadingContent = { SftpRowIcon(row, showTree) },
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
                    onOpenFolder = onOpenFolder.takeIf { showTree && file.isDirectory },
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
    onOpenFolder: (() -> Unit)?,
    onRename: () -> Unit,
    onDelete: () -> Unit
) {
    DropdownMenu(
        expanded = expanded,
        onDismissRequest = onDismiss
    ) {
        if (onOpenFolder != null) {
            DropdownMenuItem(
                text = { Text("Open folder") },
                leadingIcon = {
                    Icon(
                        imageVector = Icons.Default.FolderOpen,
                        contentDescription = null
                    )
                },
                onClick = {
                    onDismiss()
                    onOpenFolder()
                }
            )
        }
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
private fun SftpRowIcon(row: SftpRow, showTree: Boolean) {
    val file = row.file
    Row(verticalAlignment = Alignment.CenterVertically) {
        if (showTree) {
            Spacer(Modifier.width(TREE_INDENT * row.depth))
            TreeChevron(row)
        }
        Icon(
            imageVector = when {
                !file.isDirectory -> Icons.AutoMirrored.Filled.InsertDriveFile
                row.isExpanded -> Icons.Default.FolderOpen
                else -> Icons.Default.Folder
            },
            contentDescription = null,
            tint = if (file.isDirectory) {
                MaterialTheme.colorScheme.primary
            } else {
                MaterialTheme.colorScheme.secondary
            }
        )
    }
}

@Composable
private fun TreeChevron(row: SftpRow) {
    Box(modifier = Modifier.size(24.dp), contentAlignment = Alignment.Center) {
        when {
            !row.file.isDirectory -> Unit
            row.isLoading -> CircularProgressIndicator(modifier = Modifier.size(14.dp), strokeWidth = 2.dp)
            else -> Icon(
                imageVector = if (row.isExpanded) {
                    Icons.Default.KeyboardArrowDown
                } else {
                    Icons.AutoMirrored.Filled.KeyboardArrowRight
                },
                contentDescription = if (row.isExpanded) "Collapse" else "Expand",
                modifier = Modifier.size(20.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
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
