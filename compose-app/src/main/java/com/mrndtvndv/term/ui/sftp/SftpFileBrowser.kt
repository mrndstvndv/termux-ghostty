package com.mrndtvndv.term.ui.sftp

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.automirrored.filled.InsertDriveFile
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.mrndtvndv.term.domain.SftpFile
import java.io.File

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
    val downloadState by viewModel.downloadState.collectAsState()
    val context = LocalContext.current

    // Show progress dialog when downloading to open
    downloadState?.let { state ->
        AlertDialog(
            onDismissRequest = {},
            confirmButton = {},
            title = { Text("Downloading File") },
            text = {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(state.fileName, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    
                    val progress = if (state.totalBytes > 0) state.bytesDownloaded.toFloat() / state.totalBytes else 0f
                    LinearProgressIndicator(
                        progress = progress,
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

    Column(modifier = modifier.fillMaxSize()) {
        // Path toolbar
        Surface(
            tonalElevation = 8.dp,
            modifier = Modifier.fillMaxWidth()
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = { viewModel.navigateUp() }) {
                    Icon(
                        imageVector = Icons.Default.ArrowUpward,
                        contentDescription = "Up"
                    )
                }
                Text(
                    text = viewModel.currentPath,
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
                IconButton(onClick = { viewModel.refresh() }) {
                    Icon(
                        imageVector = Icons.Default.Refresh,
                        contentDescription = "Refresh"
                    )
                }
            }
        }

        when (val state = uiState) {
            is SftpUiState.Loading -> {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .weight(1f),
                    contentAlignment = Alignment.Center
                ) {
                    CircularProgressIndicator()
                }
            }
            is SftpUiState.Success -> {
                LazyColumn(
                    modifier = Modifier
                        .fillMaxSize()
                        .weight(1f),
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
                                        Spacer(modifier = Modifier.width(8.dp))
                                        val color = when {
                                            gitStatus == "??" || gitStatus.contains("A") -> androidx.compose.ui.graphics.Color(0xFF4CAF50) // Green
                                            gitStatus.contains("D") -> androidx.compose.ui.graphics.Color(0xFFF44336) // Red
                                            else -> androidx.compose.ui.graphics.Color(0xFFFF9800) // Orange for Modified/Renamed
                                        }
                                        Surface(
                                            color = color.copy(alpha = 0.2f),
                                            shape = androidx.compose.foundation.shape.RoundedCornerShape(4.dp)
                                        ) {
                                            Text(
                                                text = gitStatus,
                                                color = color,
                                                style = MaterialTheme.typography.labelSmall,
                                                modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp)
                                            )
                                        }
                                    }
                                }
                            },
                            supportingContent = {
                                val lastModified = formatLastModified(file.modifiedTime)
                                val desc = if (file.isDirectory) {
                                    if (lastModified.isNotEmpty()) "Directory • $lastModified" else "Directory"
                                } else {
                                    val sizeStr = formatBytes(file.size)
                                    if (lastModified.isNotEmpty()) "$sizeStr • $lastModified" else sizeStr
                                }
                                Text(
                                    text = desc,
                                    style = MaterialTheme.typography.bodySmall
                                )
                            },
                            leadingContent = {
                                Icon(
                                    imageVector = if (file.isDirectory) Icons.Default.Folder else Icons.AutoMirrored.Filled.InsertDriveFile,
                                    contentDescription = null,
                                    tint = if (file.isDirectory) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.secondary
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
                        HorizontalDivider(thickness = 0.5.dp, color = MaterialTheme.colorScheme.outline.copy(alpha = 0.3f))
                    }
                }
            }
            is SftpUiState.Error -> {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .weight(1f)
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
    val sdf = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.getDefault()).apply {
        timeZone = java.util.TimeZone.getDefault()
    }
    return sdf.format(date)
}
