package com.mrndtvndv.term.ui.sftp

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.FileUpload
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation3.runtime.NavBackStack
import androidx.navigation3.runtime.NavKey
import androidx.navigation3.runtime.entryProvider
import com.mrndtvndv.term.domain.SftpFile
import com.mrndtvndv.term.navigation.AppNavDisplay
import com.mrndtvndv.term.navigation.SftpFolder
import com.mrndtvndv.term.ui.sftp.transfer.TransferType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

private sealed interface SftpDialog {
    data class Rename(val file: SftpFile) : SftpDialog
    data class Delete(val file: SftpFile) : SftpDialog
}

@Suppress("LongMethod", "LongParameterList")
@Composable
fun SftpScreenRoute(
    viewModel: SftpViewModel,
    backStack: NavBackStack<NavKey>,
    onOpenFolder: (String) -> Unit,
    onBack: () -> Unit,
    isTabActive: Boolean,
    onOpenFile: (File) -> Unit,
    onOpenFileError: (String) -> Unit
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val isRefreshing by viewModel.isRefreshing.collectAsStateWithLifecycle()
    val trailPath by viewModel.trailPath.collectAsStateWithLifecycle()
    val downloadState by viewModel.downloadState.collectAsStateWithLifecycle()
    val uploadState by viewModel.uploadState.collectAsStateWithLifecycle()
    val transfers by viewModel.transfers.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    var dialog by remember { mutableStateOf<SftpDialog?>(null) }

    fun showMessage(message: String) {
        scope.launch { snackbarHostState.showSnackbar(message) }
    }

    val uploadPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri ->
        uri ?: return@rememberLauncherForActivityResult
        scope.launch {
            val staged = withContext(Dispatchers.IO) { stagePickedFile(context, uri) }
            if (staged == null) {
                showMessage("Failed to read selected file")
            } else {
                viewModel.uploadFile(
                    source = staged,
                    onSuccess = { showMessage("Uploaded ${staged.name}") },
                    onError = ::showMessage
                )
            }
        }
    }

    when (val current = dialog) {
        is SftpDialog.Rename -> SftpRenameDialog(
            file = current.file,
            onDismiss = { dialog = null },
            onRename = { newName ->
                dialog = null
                viewModel.renameFile(
                    file = current.file,
                    newName = newName,
                    onSuccess = { showMessage("Renamed to ${newName.trim()}") },
                    onError = ::showMessage
                )
            }
        )
        is SftpDialog.Delete -> SftpDeleteDialog(
            file = current.file,
            onDismiss = { dialog = null },
            onDelete = {
                dialog = null
                viewModel.deleteFile(
                    file = current.file,
                    onSuccess = { showMessage("Deleted ${current.file.name}") },
                    onError = ::showMessage
                )
            }
        )
        null -> Unit
    }

    SftpTransferDialogs(
        transfers = transfers,
        downloadState = downloadState,
        uploadState = uploadState,
        onCancelTransfer = { viewModel.cancelTransfer(it.id) },
        onBackgroundTransfer = {
            if (it.type == TransferType.DOWNLOAD) {
                viewModel.backgroundDownload(it.id)
            } else {
                viewModel.backgroundUpload(it.id)
            }
        },
        onCancelDownload = viewModel::cancelDownload,
        onCancelUpload = viewModel::cancelUpload
    )

    val currentPath = (backStack.last() as SftpFolder).path
    LaunchedEffect(currentPath) {
        if (currentPath != viewModel.currentPath) viewModel.navigateTo(currentPath)
    }

    AppNavDisplay(
        backStack = backStack,
        onBack = onBack,
        entryProvider = entryProvider<NavKey> {
            entry<SftpFolder> {
                Scaffold(
                    modifier = Modifier.fillMaxSize(),
                    containerColor = Color.Transparent,
                    contentWindowInsets = WindowInsets(0.dp),
                    snackbarHost = { SnackbarHost(snackbarHostState) },
                    floatingActionButton = {
                        FloatingActionButton(
                            onClick = { uploadPicker.launch("*/*") },
                            modifier = Modifier.navigationBarsPadding(),
                        ) {
                            Icon(
                                imageVector = Icons.Default.FileUpload,
                                contentDescription = "Upload file"
                            )
                        }
                    },
                    bottomBar = {
                        SftpMinimizedTransferBanner(
                            transfers = transfers,
                            onRestore = {
                                if (it.type == TransferType.DOWNLOAD) {
                                    viewModel.restoreDownload(it.id)
                                } else {
                                    viewModel.restoreUpload(it.id)
                                }
                            },
                            onCancel = { viewModel.cancelTransfer(it.id) }
                        )
                    }
                ) { innerPadding ->
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(innerPadding)
                    ) {
                        SftpBreadcrumbs(
                            currentPath = currentPath,
                            trailPath = trailPath,
                            isTabActive = isTabActive,
                            onSegmentClick = onOpenFolder
                        )
                        SftpDirectory(
                            state = uiState,
                            isRefreshing = isRefreshing,
                            onRefresh = viewModel::refresh,
                            onOpenFolder = onOpenFolder,
                            onOpenFile = { file ->
                                viewModel.downloadAndOpenFile(
                                    file = file,
                                    cacheDir = context.cacheDir,
                                    onFileReady = onOpenFile,
                                    onError = onOpenFileError
                                )
                            },
                            onRename = { dialog = SftpDialog.Rename(it) },
                            onDelete = { dialog = SftpDialog.Delete(it) },
                            modifier = Modifier.weight(1f)
                        )
                    }
                }
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
