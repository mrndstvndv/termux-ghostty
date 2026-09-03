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
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.FileUpload
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.material.icons.automirrored.filled.InsertDriveFile
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
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
import com.mrndtvndv.term.ui.sftp.transfer.SftpTransfer
import com.mrndtvndv.term.ui.sftp.transfer.TransferType
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
    modifier: Modifier = Modifier,
    isTabActive: Boolean = true
) {
    val effectivePath = if (trailPath.isNotBlank()) trailPath else currentPath
    val segments = remember(effectivePath) { parsePathSegments(effectivePath) }
    val scrollState = rememberScrollState()
    val activeSegmentRequester = remember { BringIntoViewRequester() }
    val activeSegmentIndex = segments.indexOfFirst { it.fullPath == currentPath }

    // BringIntoView propagates to ALL ancestors including HorizontalPager: an offscreen
    // SFTP page requesting it yanks the pager Terminal->Git overshooting onto SFTP.
    // Only request when this tab is active (pager already settled on SFTP), so the
    // request scopes to the breadcrumb Row's own horizontalScroll.
    LaunchedEffect(currentPath, effectivePath, isTabActive) {
        if (!isTabActive || activeSegmentIndex < 0) return@LaunchedEffect
        activeSegmentRequester.bringIntoView()
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
                val isCurrent = segment.fullPath == currentPath
                SftpBreadcrumbSegment(
                    segment = segment,
                    isCurrent = isCurrent,
                    activeSegmentRequester = activeSegmentRequester,
                    onSegmentClick = onSegmentClick
                )

                if (index < segments.lastIndex) {
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

@Composable
private fun SftpBreadcrumbSegment(
    segment: PathSegment,
    isCurrent: Boolean,
    activeSegmentRequester: BringIntoViewRequester,
    onSegmentClick: (String) -> Unit
) {
    Surface(
        color = if (isCurrent) {
            MaterialTheme.colorScheme.primaryContainer
        } else {
            MaterialTheme.colorScheme.surface
        },
        shape = RoundedCornerShape(8.dp),
        modifier = Modifier
            .then(
                if (isCurrent) {
                    Modifier.bringIntoViewRequester(activeSegmentRequester)
                } else {
                    Modifier
                }
            )
            .clickable {
                onSegmentClick(segment.fullPath)
            }
    ) {
        Text(
            text = segment.name,
            style = MaterialTheme.typography.labelLarge,
            color = if (isCurrent) {
                MaterialTheme.colorScheme.onPrimaryContainer
            } else {
                MaterialTheme.colorScheme.onSurface
            },
            fontWeight = if (isCurrent) FontWeight.Bold else FontWeight.Medium,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
        )
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
    onOpenFileError: (String) -> Unit
) {
    val uiState by viewModel.uiState.collectAsState()
    val isRefreshing by viewModel.isRefreshing.collectAsState()
    val trailPath by viewModel.trailPath.collectAsState()
    val downloadState by viewModel.downloadState.collectAsState()
    val uploadState by viewModel.uploadState.collectAsState()
    val transfers by viewModel.transfers.collectAsState()
    val context = LocalContext.current
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

    var menuTargetPath by remember { mutableStateOf<String?>(null) }
    var renameTarget by remember { mutableStateOf<SftpFile?>(null) }
    var deleteTarget by remember { mutableStateOf<SftpFile?>(null) }

    fun showError(message: String) {
        scope.launch { snackbarHostState.showSnackbar(message) }
    }

    renameTarget?.let { target ->
        var newName by remember(target.path) { mutableStateOf(target.name) }
        val isValid = newName.isNotBlank() && !newName.contains('/') && newName.trim() != target.name
        AlertDialog(
            onDismissRequest = { renameTarget = null },
            title = { Text("Rename") },
            text = {
                OutlinedTextField(
                    value = newName,
                    onValueChange = { newName = it },
                    singleLine = true,
                    label = { Text("New name") },
                    isError = newName.contains('/'),
                    supportingText = {
                        if (newName.contains('/')) Text("Name cannot contain '/'")
                    }
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        renameTarget = null
                        viewModel.renameFile(
                            file = target,
                            newName = newName,
                            onSuccess = { showError("Renamed to ${newName.trim()}") },
                            onError = ::showError
                        )
                    },
                    enabled = isValid
                ) { Text("Rename") }
            },
            dismissButton = {
                TextButton(onClick = { renameTarget = null }) { Text("Cancel") }
            }
        )
    }

    deleteTarget?.let { target ->
        AlertDialog(
            onDismissRequest = { deleteTarget = null },
            title = { Text("Delete \"${target.name}\"?") },
            text = {
                Text(
                    if (target.isDirectory) {
                        "Empty directories can be deleted. This cannot be undone."
                    } else {
                        "This file will be permanently deleted. This cannot be undone."
                    }
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    deleteTarget = null
                    viewModel.deleteFile(
                        file = target,
                        onSuccess = { showError("Deleted ${target.name}") },
                        onError = ::showError
                    )
                }) {
                    Text("Delete", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { deleteTarget = null }) { Text("Cancel") }
            }
        )
    }

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

    if (transfers.isNotEmpty()) {
        val activeTransfer = transfers.firstOrNull { it.isRunning && !it.isMinimized }
        if (activeTransfer != null) {
            SftpTransferDialog(
                title = if (activeTransfer.type == TransferType.DOWNLOAD) "Downloading File" else "Uploading File",
                fileName = activeTransfer.fileName,
                bytesTransferred = activeTransfer.transferredBytes,
                totalBytes = activeTransfer.totalBytes,
                onCancel = { viewModel.cancelTransfer(activeTransfer.id) },
                onBackground = {
                    if (activeTransfer.type == TransferType.DOWNLOAD) {
                        viewModel.backgroundDownload(activeTransfer.id)
                    } else {
                        viewModel.backgroundUpload(activeTransfer.id)
                    }
                }
            )
        }
    } else {
        // Show progress dialog when downloading to open (fallback)
        downloadState?.let { state ->
            SftpTransferDialog(
                title = "Downloading File",
                fileName = state.fileName,
                bytesTransferred = state.bytesDownloaded,
                totalBytes = state.totalBytes,
                onCancel = { viewModel.cancelDownload() }
            )
        }

        // Show progress dialog when uploading (fallback)
        uploadState?.let { state ->
            SftpTransferDialog(
                title = "Uploading File",
                fileName = state.fileName,
                bytesTransferred = state.bytesUploaded,
                totalBytes = state.totalBytes,
                onCancel = { viewModel.cancelUpload() }
            )
        }
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
                val minimizedTransfers = transfers.filter { it.isRunning && it.isMinimized }
                val latestMinimized = minimizedTransfers.firstOrNull()

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
                    },
                    bottomBar = {
                        if (latestMinimized != null) {
                            SftpMinimizedTransferBanner(
                                transfer = latestMinimized,
                                count = minimizedTransfers.size,
                                onRestore = {
                                    if (latestMinimized.type == TransferType.DOWNLOAD) {
                                        viewModel.restoreDownload(latestMinimized.id)
                                    } else {
                                        viewModel.restoreUpload(latestMinimized.id)
                                    }
                                },
                                onCancel = {
                                    viewModel.cancelTransfer(latestMinimized.id)
                                }
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
                        isTabActive = isTabActive,
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
                                                Box {
                                                    IconButton(onClick = { menuTargetPath = file.path }) {
                                                        Icon(
                                                            imageVector = Icons.Default.MoreVert,
                                                            contentDescription = "More options",
                                                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                                                        )
                                                    }
                                                    DropdownMenu(
                                                        expanded = menuTargetPath == file.path,
                                                        onDismissRequest = { menuTargetPath = null }
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
                                                                menuTargetPath = null
                                                                renameTarget = file
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
                                                                menuTargetPath = null
                                                                deleteTarget = file
                                                            }
                                                        )
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
    onCancel: () -> Unit,
    onBackground: (() -> Unit)? = null
) {
    AlertDialog(
        onDismissRequest = onCancel,
        confirmButton = {
            if (onBackground != null) {
                TextButton(onClick = onBackground) {
                    Text("Background")
                }
            }
        },
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

@Composable
private fun SftpMinimizedTransferBanner(
    transfer: SftpTransfer,
    count: Int,
    onRestore: () -> Unit,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surfaceVariant,
        tonalElevation = 3.dp
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            SftpBannerHeader(transfer = transfer, count = count)
            LinearProgressIndicator(
                progress = { transfer.progress },
                modifier = Modifier.fillMaxWidth()
            )
            SftpBannerActions(onRestore = onRestore, onCancel = onCancel)
        }
    }
}

@Composable
private fun SftpBannerHeader(
    transfer: SftpTransfer,
    count: Int
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        val title = if (count > 1) {
            "${transfer.fileName} (+${count - 1})"
        } else {
            transfer.fileName
        }
        Text(
            text = title,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Medium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f)
        )
        val bytesText = if (transfer.totalBytes > 0L) {
            "${formatBytes(transfer.transferredBytes)} / ${formatBytes(transfer.totalBytes)}"
        } else {
            formatBytes(transfer.transferredBytes)
        }
        Text(
            text = bytesText,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = 8.dp)
        )
    }
}

@Composable
private fun SftpBannerActions(
    onRestore: () -> Unit,
    onCancel: () -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.End,
        verticalAlignment = Alignment.CenterVertically
    ) {
        TextButton(
            onClick = onCancel,
            colors = ButtonDefaults.textButtonColors(
                contentColor = MaterialTheme.colorScheme.error
            )
        ) {
            Text("Cancel")
        }
        Spacer(modifier = Modifier.width(8.dp))
        TextButton(onClick = onRestore) {
            Text("Restore")
        }
    }
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
