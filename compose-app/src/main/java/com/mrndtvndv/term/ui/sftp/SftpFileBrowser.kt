@file:Suppress("MatchingDeclarationName")

package com.mrndtvndv.term.ui.sftp

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Folder
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.automirrored.filled.InsertDriveFile
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.navigation3.runtime.NavBackStack
import androidx.navigation3.runtime.NavKey
import androidx.navigation3.runtime.entryProvider
import androidx.navigation3.runtime.rememberNavBackStack
import androidx.navigation3.ui.NavDisplay
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.mrndtvndv.term.domain.SftpFile
import com.mrndtvndv.term.ui.SftpNavKey
import java.io.File

data class PathSegment(val name: String, val fullPath: String)

private fun parsePathSegments(path: String): List<PathSegment> {
    if (path.isBlank() || path == "/") {
        return listOf(PathSegment("/", "/"))
    }
    val parts = path.trim('/').split('/')
    val segments = mutableListOf<PathSegment>()
    var currentAcc = ""
    segments.add(PathSegment("/", "/"))
    parts.forEach { part ->
        if (part.isNotEmpty()) {
            currentAcc += "/$part"
            segments.add(PathSegment(part, currentAcc))
        }
    }
    return segments
}

@Composable
fun SftpBreadcrumbs(
    path: String,
    onSegmentClick: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    val segments = remember(path) { parsePathSegments(path) }
    val scrollState = rememberScrollState()

    LaunchedEffect(path) {
        scrollState.animateScrollTo(scrollState.maxValue)
    }

    Row(
        modifier = modifier
            .fillMaxWidth()
            .horizontalScroll(scrollState)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        segments.forEachIndexed { index, segment ->
            val isLast = index == segments.size - 1
            Text(
                text = segment.name,
                style = if (isLast) {
                    MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold)
                } else {
                    MaterialTheme.typography.bodyMedium
                },
                color = if (isLast) {
                    MaterialTheme.colorScheme.onSurface
                } else {
                    MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                },
                modifier = Modifier
                    .clickable(enabled = !isLast) { onSegmentClick(segment.fullPath) }
                    .padding(vertical = 4.dp, horizontal = 4.dp)
            )
            if (!isLast) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f),
                    modifier = Modifier.size(18.dp)
                )
            }
        }
    }
}

@Suppress("LongMethod", "CyclomaticComplexMethod")
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SftpFileBrowser(
    viewModel: SftpViewModel,
    modifier: Modifier = Modifier,
    onOpenFile: (File) -> Unit,
    onOpenFileError: (String) -> Unit,
    onDownloadFile: ((SftpFile) -> Unit)? = null,
    onDeleteFile: ((SftpFile) -> Unit)? = null
) {
    val uiState by viewModel.uiState.collectAsState()
    val isRefreshing by viewModel.isRefreshing.collectAsState()
    val downloadState by viewModel.downloadState.collectAsState()
    val context = LocalContext.current

    // Show progress dialog when downloading to open
    downloadState?.let { state ->
        AlertDialog(
            onDismissRequest = { viewModel.cancelDownload() },
            confirmButton = {},
            dismissButton = {
                TextButton(onClick = { viewModel.cancelDownload() }) {
                    Text("Cancel")
                }
            },
            title = { Text("Downloading File") },
            text = {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(state.fileName, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    
                    val progress = if (state.totalBytes > 0) state.bytesDownloaded.toFloat() / state.totalBytes else 0f
                    LinearProgressIndicator(
                        progress = { progress },
                        modifier = Modifier.fillMaxWidth()
                    )
                    
                    val bytesText = if (state.totalBytes > 0) {
                        "${formatBytes(state.bytesDownloaded)} / ${formatBytes(state.totalBytes)}"
                    } else {
                        formatBytes(state.bytesDownloaded)
                    }
                    Text(
                        text = bytesText,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.align(Alignment.End)
                    )
                }
            }
        )
    }

    val initialKey = remember(viewModel.currentPath) { SftpNavKey.Folder(viewModel.currentPath) }
    val sftpBackStack: NavBackStack<NavKey> = rememberNavBackStack(initialKey)

    NavDisplay(
        backStack = sftpBackStack,
        onBack = {
            if (sftpBackStack.size > 1) {
                sftpBackStack.removeLastOrNull()
                val prevPath = (sftpBackStack.lastOrNull() as? SftpNavKey.Folder)?.path
                if (prevPath != null) {
                    viewModel.navigateTo(prevPath)
                }
            } else {
                viewModel.navigateUp()
            }
        },
        entryProvider = entryProvider<NavKey> {
            entry<SftpNavKey.Folder> {
                Column(modifier = modifier.fillMaxSize()) {
                    SftpBreadcrumbs(
                        path = viewModel.currentPath,
                        onSegmentClick = { targetPath ->
                            while (
                                sftpBackStack.size > 1 &&
                                (sftpBackStack.lastOrNull() as? SftpNavKey.Folder)?.path != targetPath
                            ) {
                                sftpBackStack.removeLastOrNull()
                            }
                            if ((sftpBackStack.lastOrNull() as? SftpNavKey.Folder)?.path != targetPath) {
                                sftpBackStack.add(SftpNavKey.Folder(targetPath))
                            }
                            viewModel.navigateTo(targetPath)
                        }
                    )

                    PullToRefreshBox(
                        isRefreshing = isRefreshing,
                        onRefresh = { viewModel.refresh() },
                        modifier = Modifier.fillMaxSize().weight(1f)
                    ) {
                        when (val state = uiState) {
                            is SftpUiState.Loading -> {
                                Box(
                                    modifier = Modifier.fillMaxSize(),
                                    contentAlignment = Alignment.Center
                                ) {
                                    CircularProgressIndicator()
                                }
                            }
                            is SftpUiState.Success -> {
                                LazyColumn(
                                    modifier = Modifier.fillMaxSize(),
                                    contentPadding = PaddingValues(vertical = 8.dp)
                                ) {
                                    items(state.files) { file ->
                                        ListItem(
                                            headlineContent = {
                                                Row(verticalAlignment = Alignment.CenterVertically) {
                                                    Text(
                                                        text = file.name,
                                                        maxLines = 1,
                                                        overflow = TextOverflow.Ellipsis
                                                    )
                                                    val gitStatus = state.gitStatuses[file.name]?.trim()
                                                    if (!gitStatus.isNullOrEmpty()) {
                                                        val green = androidx.compose.ui.graphics.Color(0xFF4CAF50)
                                                        val red = androidx.compose.ui.graphics.Color(0xFFF44336)
                                                        val orange = androidx.compose.ui.graphics.Color(0xFFFF9800)
                                                        val color = when {
                                                            gitStatus == "??" || gitStatus.contains("A") -> green
                                                            gitStatus.contains("D") -> red
                                                            else -> orange
                                                        }
                                                        Surface(
                                                            color = color.copy(alpha = 0.2f),
                                                            shape = RoundedCornerShape(4.dp)
                                                        ) {
                                                            Text(
                                                                text = gitStatus,
                                                                color = color,
                                                                style = MaterialTheme.typography.labelSmall,
                                                                modifier = Modifier
                                                                    .padding(horizontal = 4.dp, vertical = 2.dp)
                                                            )
                                                        }
                                                    }
                                                }
                                            },
                                            supportingContent = {
                                                val lastModified = formatLastModified(file.modifiedTime)
                                                val desc = if (file.isDirectory) {
                                                    if (lastModified.isNotEmpty()) {
                                                        "Directory • $lastModified"
                                                    } else {
                                                        "Directory"
                                                    }
                                                } else {
                                                    val sizeStr = formatBytes(file.size)
                                                    if (lastModified.isNotEmpty()) {
                                                        "$sizeStr • $lastModified"
                                                    } else {
                                                        sizeStr
                                                    }
                                                }
                                                Text(
                                                    text = desc,
                                                    style = MaterialTheme.typography.bodySmall
                                                )
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
                                                Row {
                                                    if (!file.isDirectory && onDownloadFile != null) {
                                                        IconButton(onClick = { onDownloadFile(file) }) {
                                                            Icon(
                                                                imageVector = Icons.Default.Download,
                                                                contentDescription = "Download"
                                                            )
                                                        }
                                                    }
                                                    if (onDeleteFile != null) {
                                                        IconButton(onClick = { onDeleteFile(file) }) {
                                                            Icon(
                                                                imageVector = Icons.Default.Delete,
                                                                contentDescription = "Delete",
                                                                tint = MaterialTheme.colorScheme.error
                                                            )
                                                        }
                                                    }
                                                }
                                            },
                                            modifier = Modifier
                                                .clickable {
                                                    if (file.isDirectory) {
                                                        sftpBackStack.add(SftpNavKey.Folder(file.path))
                                                        viewModel.navigateTo(file.path)
                                                    } else {
                                                        viewModel.downloadAndOpenFile(
                                                            file = file,
                                                            cacheDir = context.cacheDir,
                                                            onFileReady = onOpenFile,
                                                            onError = onOpenFileError
                                                        )
                                                    }
                                                }
                                        )
                                        HorizontalDivider(
                                            thickness = 0.5.dp,
                                            color = MaterialTheme.colorScheme.outline.copy(alpha = 0.3f)
                                        )
                                    }
                                }
                            }
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
            }
        }
    )
}

private fun formatBytes(bytes: Long): String {
    if (bytes <= 0) return "0 B"
    val units = arrayOf("B", "KB", "MB", "GB", "TB", "PB", "EB")
    val digitGroups = (Math.log10(bytes.toDouble()) / Math.log10(1024.0)).toInt().coerceIn(0, units.size - 1)
    if (digitGroups == 0) return "$bytes B"
    return String.format(java.util.Locale.US, "%.2f %s", bytes / Math.pow(1024.0, digitGroups.toDouble()), units[digitGroups])
}

private fun formatLastModified(mtime: Long): String {
    if (mtime <= 0) return ""
    val date = java.util.Date(mtime)
    val sdf = java.text.SimpleDateFormat("yyyy-MM-dd hh:mm:ss a", java.util.Locale.getDefault()).apply {
        timeZone = java.util.TimeZone.getDefault()
    }
    return sdf.format(date)
}
