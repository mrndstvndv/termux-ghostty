package com.mrndtvndv.term.ui.workspace

import android.view.View
import android.view.Window
import android.view.WindowManager
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.draw.clip
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.InsertDriveFile
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.RestartAlt
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogWindowProvider
import com.termux.shared.interact.ShareUtils
import com.termux.shared.shell.ShellUtils
import com.termux.shared.termux.data.TermuxUrlUtils
import com.termux.terminal.TerminalSession
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private data class ContextMenuItemData(
    val icon: ImageVector,
    val title: String,
    val subtitle: String,
    val enabled: Boolean = true,
    val onClick: () -> Unit
)

private class ContextMenuCallbacks(
    val onDismiss: () -> Unit,
    val onUploadMedia: () -> Unit,
    val onUploadFile: () -> Unit,
    val onSelectUrls: () -> Unit,
    val onShowUrls: (List<String>) -> Unit
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
@Suppress("LongMethod")
fun TerminalContextMenu(
    session: TerminalSession,
    onOpenUrl: (String) -> Unit,
    onUploadMedia: () -> Unit,
    onUploadFile: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier
) {
    var isSheetVisible by remember { mutableStateOf(true) }
    var urlsForDialog by remember { mutableStateOf<List<String>?>(null) }
    var isSelectingUrls by remember { mutableStateOf(false) }
    var isTranscriptLoading by remember { mutableStateOf(false) }
    val context = LocalContext.current

    val callbacks = remember(onDismiss, onUploadMedia, onUploadFile) {
        ContextMenuCallbacks(
            onDismiss = {
                isSelectingUrls = false
                isTranscriptLoading = false
                isSheetVisible = false
                onDismiss()
            },
            onUploadMedia = onUploadMedia,
            onUploadFile = onUploadFile,
            onSelectUrls = { isSelectingUrls = true },
            onShowUrls = { urls ->
                urlsForDialog = urls
                isSheetVisible = false
            }
        )
    }

    LaunchedEffect(isSelectingUrls) {
        if (!isSelectingUrls) return@LaunchedEffect
        isTranscriptLoading = true
        val urls = withContext(Dispatchers.IO) { extractUrls(session) }
        isSelectingUrls = false
        isTranscriptLoading = false
        if (urls.isEmpty()) {
            Toast.makeText(context, "No URLs found in transcript", Toast.LENGTH_SHORT).show()
            callbacks.onDismiss()
        } else {
            callbacks.onShowUrls(urls.reversed())
        }
    }

    val items = rememberContextMenuItems(session, callbacks)

    if (isSheetVisible) {
        ModalBottomSheet(
            onDismissRequest = onDismiss,
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
            contentColor = MaterialTheme.colorScheme.onSurface,
            tonalElevation = 4.dp,
            dragHandle = {
                Box(
                    modifier = Modifier
                        .padding(vertical = 6.dp)
                        .size(width = 40.dp, height = 4.dp)
                        .clip(RoundedCornerShape(50))
                        .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.65f))
                )
            },
            modifier = modifier
        ) {
            PlaceSheetAboveIme()
            if (isTranscriptLoading) {
                TranscriptLoadingContent()
            } else {
                TerminalContextMenuContent(items = items)
            }
        }
    }

    ContextMenuDialogs(
        urlsForDialog = urlsForDialog,
        onOpenUrl = { url ->
            urlsForDialog = null
            onDismiss()
            onOpenUrl(url)
        },
        onDismissUrls = {
            urlsForDialog = null
            onDismiss()
        }
    )
}

@Composable
private fun ContextMenuDialogs(
    urlsForDialog: List<String>?,
    onOpenUrl: (String) -> Unit,
    onDismissUrls: () -> Unit
) {
    urlsForDialog?.let { urls ->
        SelectUrlDialog(
            urls = urls,
            onOpenUrl = onOpenUrl,
            onDismiss = onDismissUrls
        )
    }
}

@Suppress("LongMethod")
@Composable
private fun rememberContextMenuItems(
    session: TerminalSession,
    callbacks: ContextMenuCallbacks
): List<ContextMenuItemData> {
    val context = LocalContext.current
    return remember(session, callbacks) {
        listOfNotNull(
            ContextMenuItemData(
                icon = Icons.Default.ContentPaste,
                title = "Paste",
                subtitle = "Paste clipboard text or image",
            ) {
                callbacks.onDismiss()
                session.onPasteTextFromClipboard()
            },
            ContextMenuItemData(
                icon = Icons.Default.Image,
                title = "Upload Image",
                subtitle = "Choose an image and paste its path",
            ) {
                callbacks.onDismiss()
                callbacks.onUploadMedia()
            },
            ContextMenuItemData(
                icon = Icons.AutoMirrored.Filled.InsertDriveFile,
                title = "Upload File",
                subtitle = "Choose any file and paste its path",
            ) {
                callbacks.onDismiss()
                callbacks.onUploadFile()
            },
            ContextMenuItemData(
                icon = Icons.Default.Link,
                title = "Select URL",
                subtitle = "Extract and open URLs from session",
            ) {
                callbacks.onSelectUrls()
            },
            ContextMenuItemData(
                icon = Icons.Default.RestartAlt,
                title = "Reset Terminal",
                subtitle = "Reset terminal state and clear screen",
            ) {
                callbacks.onDismiss()
                session.reset()
                Toast.makeText(context, "Terminal reset", Toast.LENGTH_SHORT).show()
            }
        )
    }
}

private fun extractUrls(session: TerminalSession): List<String> {
    val transcript = ShellUtils.getTerminalSessionTranscriptText(session, true, true)
    return transcript?.let { TermuxUrlUtils.extractUrls(it).map { url -> url.toString() } }.orEmpty()
}

@Composable
private fun TranscriptLoadingContent() {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        CircularProgressIndicator()
        Text("Reading terminal transcript…")
    }
}

@Composable
@Suppress("LongMethod")
private fun TerminalContextMenuContent(
    items: List<ContextMenuItemData>
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(max = 640.dp)
            .verticalScroll(rememberScrollState())
            .padding(start = 12.dp, end = 12.dp, bottom = 16.dp)
    ) {
        items.forEachIndexed { index, item ->
            ContextMenuItem(
                icon = item.icon,
                title = item.title,
                subtitle = item.subtitle,
                enabled = item.enabled,
                onClick = item.onClick
            )
            if (index < items.lastIndex) {
                HorizontalDivider(
                    modifier = Modifier.padding(start = 52.dp),
                    color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.45f)
                )
            }
        }
    }
}

@Composable
private fun ContextMenuItem(
    icon: ImageVector,
    title: String,
    subtitle: String,
    enabled: Boolean = true,
    onClick: () -> Unit
) {
    val contentAlpha = if (enabled) 1f else 0.38f
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 60.dp)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary.copy(alpha = contentAlpha),
            modifier = Modifier.size(28.dp)
        )
        Spacer(modifier = Modifier.width(12.dp))
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = contentAlpha),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = contentAlpha),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@Composable
private fun SelectUrlDialog(
    urls: List<String>,
    onOpenUrl: (String) -> Unit,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(text = "Select URL (${urls.size})")
        },
        text = {
            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 360.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(urls) { url ->
                    UrlListItem(
                        url = url,
                        onCopy = {
                            ShareUtils.copyTextToClipboard(
                                context,
                                url,
                                "URL copied to clipboard"
                            )
                        },
                        onOpen = { onOpenUrl(url) }
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text("Close")
            }
        }
    )
}

@Composable
private fun UrlListItem(
    url: String,
    onCopy: () -> Unit,
    onOpen: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                text = url,
                style = MaterialTheme.typography.bodyMedium,
                fontFamily = FontFamily.Monospace,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .weight(1f)
                    .padding(end = 8.dp)
            )
            Row {
                IconButton(onClick = onCopy, modifier = Modifier.size(36.dp)) {
                    Icon(
                        imageVector = Icons.Default.ContentCopy,
                        contentDescription = "Copy URL",
                        modifier = Modifier.size(18.dp)
                    )
                }
                IconButton(onClick = onOpen, modifier = Modifier.size(36.dp)) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.OpenInNew,
                        contentDescription = "Open URL",
                        modifier = Modifier.size(18.dp)
                    )
                }
            }
        }
    }
}

@Composable
private fun PlaceSheetAboveIme() {
    val view = LocalView.current
    DisposableEffect(view) {
        val window = view.findDialogWindow()
        window?.addFlags(WindowManager.LayoutParams.FLAG_ALT_FOCUSABLE_IM)
        onDispose {
            window?.clearFlags(WindowManager.LayoutParams.FLAG_ALT_FOCUSABLE_IM)
        }
    }
}

private fun View.findDialogWindow(): Window? {
    var current: View? = this
    while (current != null) {
        if (current is DialogWindowProvider) {
            return current.window
        }
        val parent = current.parent
        current = parent as? View
    }
    return null
}
