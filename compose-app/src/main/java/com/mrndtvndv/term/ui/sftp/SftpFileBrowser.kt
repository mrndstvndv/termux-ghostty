@file:Suppress("MatchingDeclarationName")

package com.mrndtvndv.term.ui.sftp

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.FileUpload
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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.graphics.Color
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import java.io.File

data class PathSegment(val name: String, val fullPath: String)

private fun parsePathSegments(path: String): List<PathSegment> {
    if (path.isBlank() || path == "/") {
        return listOf(PathSegment("/", "/"))
    }
    val segments = mutableListOf(PathSegment("/", "/"))
    val parts = path.split("/").filter { it.isNotEmpty() }
    var currentPath = ""
    for (part in parts) {
        currentPath += "/$part"
        segments.add(PathSegment(part, currentPath))
    }
    return segments
}

@Composable
fun SftpBreadcrumbs(
    currentPath: String,
    trailPath: String,
    onSegmentClick: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    val effectivePath = if (currentPath == "/" && trailPath.isNotEmpty()) trailPath else currentPath
    val segments = remember(effectivePath) { parsePathSegments(effectivePath) }
    val scrollState = rememberScrollState()

    LaunchedEffect(segments.size) {
        scrollState.animateScrollTo(scrollState.maxValue)
    }

    Surface(
        color = MaterialTheme.colorScheme.surface,
        modifier = modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(scrollState)
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            segments.forEachIndexed { index, segment ->
                val isLast = index == segments.size - 1
                Surface(
                    color = if (isLast) {
                        MaterialTheme.colorScheme.primaryContainer
                    } else {
                        MaterialTheme.colorScheme.surface
                    },
                    shape = RoundedCornerShape(8.dp),
                    modifier = Modifier.clickable {
                        onSegmentClick(segment.fullPath)
                    }
                ) {
                    Text(
                        text = segment.name,
                        style = MaterialTheme.typography.labelLarge,
                        color = if (isLast) {
                            MaterialTheme.colorScheme.onPrimaryContainer
                        } else {
                            MaterialTheme.colorScheme.onSurface
                        },
                        fontWeight = if (isLast) FontWeight.Bold else FontWeight.Medium,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                    )
                }

                if (!isLast) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                        modifier = Modifier.size(16.dp)
                    )
                }
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
    isTabActive: Boolean = true,
    onOpenFile: (File) -> Unit,
    onOpenFileError: (String) -> Unit,
    onDownloadFile: ((SftpFile) -> Unit)? = null,
    onDeleteFile: ((SftpFile) -> Unit)? = null
) {
    val uiState by viewModel.uiState.collectAsState()
    val isRefreshing by viewModel.isRefreshing.collectAsState()
    val trailPath by viewModel.trailPath.collectAsState()
    val downloadState by viewModel.downloadState.collectAsState()
    val uploadState by viewModel.uploadState.collectAsState()
    val context = LocalContext.current
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

    val uploadPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri ->
        uri ?: return@rememberLauncherForActivityResult
        scope.launch {
            val staged = withContext(Dispatchers.IO) { stagePickedFile(context, uri) }
            if (staged == null) {
                snackbarHostState.showSnackbar("Failed to read selected file")
            } else {
                viewModel.uploadFile(
                    source = staged,
                    onSuccess = { scope.launch { snackbarHostState.showSnackbar("Uploaded ${staged.name}") } },
                    onError = { message -> scope.launch { snackbarHostState.showSnackbar(message) } }
                )
            }
        }
    }

    // Show progress dialog when downloading to open
    downloadState?.let { state ->
        SftpTransferDialog(
            title = "Downloading File",
            fileName = state.fileName,
            bytesTransferred = state.bytesDownloaded,
            totalBytes = state.totalBytes,
            onCancel = { viewModel.cancelDownload() }
        )
    }

    // Show progress dialog when uploading
    uploadState?.let { state ->
        SftpTransferDialog(
            title = "Uploading File",
            fileName = state.fileName,
            bytesTransferred = state.bytesUploaded,
            totalBytes = state.totalBytes,
            onCancel = { viewModel.cancelUpload() }
        )
    }

    val initialKey = remember(viewModel.currentPath) { SftpNavKey.Folder(viewModel.currentPath) }
    val sftpBackStack: NavBackStack<NavKey> = rememberNavBackStack(initialKey)

    NavDisplay(
        backStack = sftpBackStack,
        onBack = {
            if (isTabActive && sftpBackStack.size > 1) {
                sftpBackStack.removeLastOrNull()
                val prevPath = (sftpBackStack.lastOrNull() as? SftpNavKey.Folder)?.path
                if (prevPath != null) {
                    viewModel.navigateTo(prevPath)
                }
            }
        },
        entryProvider = entryProvider<NavKey> {
            entry<SftpNavKey.Folder> {
                Scaffold(
                    modifier = modifier.fillMaxSize(),
                    containerColor = Color.Transparent,
                    contentWindowInsets = WindowInsets(0.dp),
                    snackbarHost = { SnackbarHost(snackbarHostState) },
                    floatingActionButton = {
                        FloatingActionButton(onClick = { uploadPicker.launch("*/*") }) {
                            Icon(
                                imageVector = Icons.Default.FileUpload,
                                contentDescription = "Upload file"
                            )
                        }
                    }
                ) { innerPadding ->
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(innerPadding)
                    ) {
                    SftpBreadcrumbs(
                        currentPath = viewModel.currentPath,
                        trailPath = trailPath,
                        onSegmentClick = { targetPath ->
                            if (sftpBackStack.none { (it as? SftpNavKey.Folder)?.path == targetPath }) {
                                sftpBackStack.add(SftpNavKey.Folder(targetPath))
                            } else {
                                while (
                                    sftpBackStack.size > 1 &&
                                    (sftpBackStack.lastOrNull() as? SftpNavKey.Folder)?.path != targetPath
                                ) {
                                    sftpBackStack.removeLastOrNull()
                                }
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
                                            supportingContent = run {
                                                val lastModified = formatLastModified(file.modifiedTime)
                                                val desc = if (file.isDirectory) {
                                                    ""
                                                } else {
                                                    val sizeStr = formatBytes(file.size)
                                                    if (lastModified.isNotEmpty()) {
                                                        "$sizeStr • $lastModified"
                                                    } else {
                                                        sizeStr
                                                    }
                                                }
                                                if (desc.isNotEmpty()) {
                                                    {
                                                        Text(
                                                            text = desc,
                                                            style = MaterialTheme.typography.bodySmall
                                                        )
                                                    }
                                                } else {
                                                    null
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
        }
    )
}

@Composable
private fun SftpTransferDialog(
    title: String,
    fileName: String,
    bytesTransferred: Long,
    totalBytes: Long,
    onCancel: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onCancel,
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onCancel) {
                Text("Cancel")
            }
        },
        title = { Text(title) },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    text = fileName,
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )

                val progress = if (totalBytes > 0) bytesTransferred.toFloat() / totalBytes else 0f
                LinearProgressIndicator(
                    progress = { progress },
                    modifier = Modifier.fillMaxWidth()
                )

                val bytesText = if (totalBytes > 0) {
                    "${formatBytes(bytesTransferred)} / ${formatBytes(totalBytes)}"
                } else {
                    formatBytes(bytesTransferred)
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

@Suppress("TooGenericExceptionCaught", "SwallowedException")
private fun stagePickedFile(context: Context, uri: Uri): File? {
    val resolver = context.contentResolver
    val displayName = resolver.query(
        uri,
        arrayOf(OpenableColumns.DISPLAY_NAME),
        null,
        null,
        null
    )?.use { cursor ->
        val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
        if (index >= 0 && cursor.moveToFirst()) cursor.getString(index) else null
    } ?: uri.lastPathSegment?.substringAfterLast('/')
        ?: "upload_${System.currentTimeMillis()}"
    val stageDir = File(context.cacheDir, "upload_stage").apply { mkdirs() }
    val target = File(stageDir, displayName)
    return try {
        val input = resolver.openInputStream(uri) ?: return null
        input.use { ins -> target.outputStream().use { out -> ins.copyTo(out) } }
        target
    } catch (e: Exception) {
        null
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
    val sdf = java.text.SimpleDateFormat("yyyy-MM-dd hh:mm:ss a", java.util.Locale.getDefault()).apply {
        timeZone = java.util.TimeZone.getDefault()
    }
    return sdf.format(date)
}
