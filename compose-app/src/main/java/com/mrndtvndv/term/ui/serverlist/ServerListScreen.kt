package com.mrndtvndv.term.ui.serverlist

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.mrndtvndv.term.domain.ServerConfig
import com.mrndtvndv.term.ui.components.ImagePasteSettingsSection
import com.mrndtvndv.term.ui.components.rememberImagePasteSettingsState

@OptIn(ExperimentalMaterial3Api::class)
@Suppress("LongParameterList", "LongMethod")
@Composable
fun ServerListScreen(
    servers: List<ServerConfig>,
    activeIds: Set<String>,
    onTap: (String) -> Unit,
    onEdit: (String) -> Unit,
    onDelete: (String) -> Unit,
    onDisconnect: (String) -> Unit,
    disconnectingId: String? = null,
    connectingId: String? = null,
    onAdd: () -> Unit,
    onSettingsClick: () -> Unit,
    onStartLocal: () -> Unit,
    localConfig: ServerConfig?,
    onSetStartupCommand: (String) -> Unit,
    onUpdateLocalConfig: (
        cmd: String,
        imagePasteEnabled: Boolean,
        imagePasteDirectory: String?,
        autoCleanup: Boolean,
        maxFiles: Int,
    ) -> Unit = { cmd, _, _, _, _ -> onSetStartupCommand(cmd) },
    modifier: Modifier = Modifier,
) {
    var showStartupDialog by remember { mutableStateOf(false) }
    var pendingDeleteId by remember { mutableStateOf<String?>(null) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Servers") },
                actions = {
                    IconButton(onClick = onSettingsClick) {
                        Icon(Icons.Default.Settings, contentDescription = "Settings")
                    }
                },
            )
        },
        floatingActionButton = {
            FloatingActionButton(onClick = onAdd) {
                Icon(Icons.Default.Add, contentDescription = "Add Server")
            }
        },
        modifier = modifier.fillMaxSize(),
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            contentPadding = PaddingValues(vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            // ── Local Terminal card (always visible) ────────────────
            item(key = "local_terminal") {
                LocalTerminalCard(
                    onClick = onStartLocal,
                    startupCommand = localConfig?.startupCommand,
                    onConfigure = { showStartupDialog = true },
                )
            }

            val sshServers = servers.filter { !it.isLocal }
            if (sshServers.isEmpty()) {
                item {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(32.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            text = "No SSH servers configured.\nTap + to add one.",
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Center,
                        )
                    }
                }
            } else {
                items(sshServers, key = { it.id }) { config ->
                    ServerCard(
                        config = config,
                        isActive = config.id in activeIds,
                        isDisconnecting = config.id == disconnectingId,
                        isConnecting = config.id == connectingId,
                        onClick = { onTap(config.id) },
                        onDisconnect = { onDisconnect(config.id) },
                        onEdit = { onEdit(config.id) },
                        onDelete = { pendingDeleteId = config.id },
                    )
                }
            }
        }

        if (showStartupDialog) {
            LocalTerminalSettingsDialog(
                localConfig = localConfig,
                onDismiss = { showStartupDialog = false },
                onSave = { cmd, enabled, dir, autoCleanup, maxFiles ->
                    onUpdateLocalConfig(cmd, enabled, dir, autoCleanup, maxFiles)
                    showStartupDialog = false
                },
            )
        }

        val pendingDelete = pendingDeleteId?.let { id -> servers.find { it.id == id } }
        if (pendingDelete != null) {
            DeleteServerConfirmationDialog(
                serverLabel = pendingDelete.label,
                onConfirm = {
                    onDelete(pendingDelete.id)
                    pendingDeleteId = null
                },
                onDismiss = { pendingDeleteId = null },
            )
        }
    }
}

@Suppress("LongMethod")
@Composable
private fun LocalTerminalSettingsDialog(
    localConfig: ServerConfig?,
    onDismiss: () -> Unit,
    onSave: (
        cmd: String,
        imagePasteEnabled: Boolean,
        imagePasteDirectory: String?,
        autoCleanup: Boolean,
        maxFiles: Int,
    ) -> Unit,
) {
    var startupInput by remember { mutableStateOf(localConfig?.startupCommand.orEmpty()) }
    val imagePasteState = rememberImagePasteSettingsState(localConfig)

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Local Terminal Settings") },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(
                    "Command to run automatically when the local shell starts:",
                    style = MaterialTheme.typography.bodyMedium,
                )
                OutlinedTextField(
                    value = startupInput,
                    onValueChange = { startupInput = it },
                    label = { Text("Startup command") },
                    placeholder = { Text("e.g. cd /sdcard/Dev && ls") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                )

                HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))

                ImagePasteSettingsSection(
                    state = imagePasteState,
                    directorySupportingText =
                        "Local directory for uploaded files and pasted images. Required for uploads.",
                    activeSupportingText = "Upload media and files or paste clipboard images into terminal",
                )
            }
        },
        confirmButton = {
            TextButton(onClick = {
                onSave(
                    startupInput,
                    imagePasteState.isPasteActive,
                    imagePasteState.trimmedDirectory,
                    imagePasteState.autoCleanup,
                    imagePasteState.maxFiles,
                )
            }) {
                Text("Save")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        },
    )
}

@Composable
private fun DeleteServerConfirmationDialog(
    serverLabel: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Delete server?") },
        text = { Text("Delete \"$serverLabel\"? This cannot be undone.") },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text("Delete", color = MaterialTheme.colorScheme.error)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        },
    )
}

@Composable
private fun LocalTerminalCard(
    onClick: () -> Unit,
    startupCommand: String?,
    onConfigure: () -> Unit,
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp)
            .clickable(onClick = onClick),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.secondaryContainer,
        ),
    ) {
        Row(
            modifier = Modifier
                .padding(start = 16.dp, top = 16.dp, bottom = 16.dp, end = 4.dp)
                .fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                Icons.Default.Terminal,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSecondaryContainer,
            )
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    "Local Terminal",
                    style = MaterialTheme.typography.titleMedium,
                )
                if (!startupCommand.isNullOrBlank()) {
                    Text(
                        startupCommand,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.7f),
                        maxLines = 1,
                    )
                } else {
                    Text(
                        "Shell on this device",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            IconButton(onClick = onConfigure) {
                Icon(
                    Icons.Default.Settings,
                    contentDescription = "Configure startup command",
                    tint = MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.7f),
                )
            }
            Icon(
                Icons.Default.ChevronRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
@Suppress("LongMethod", "LongParameterList")
private fun ServerCard(
    config: ServerConfig,
    isActive: Boolean,
    isDisconnecting: Boolean = false,
    isConnecting: Boolean = false,
    onClick: () -> Unit,
    onDisconnect: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp)
            .clickable(onClick = onClick),
    ) {
        Row(
            modifier = Modifier
                .padding(16.dp)
                .fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (isActive) {
                Icon(Icons.Default.CheckCircle, "Connected", tint = Color(0xFF4CAF50), modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(8.dp))
            }

            Column(Modifier.weight(1f)) {
                Text(config.label, style = MaterialTheme.typography.titleMedium)
                Text(
                    "${config.host}:${config.port} · ${config.username}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (config.herdrEnabled) {
                    Spacer(Modifier.height(4.dp))
                    Surface(
                        color = MaterialTheme.colorScheme.primaryContainer,
                        shape = RoundedCornerShape(4.dp),
                    ) {
                        Text(
                            "Herdr",
                            style = MaterialTheme.typography.labelSmall,
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                        )
                    }
                }
            }
            if (isConnecting) {
                CircularProgressIndicator(
                    modifier = Modifier.size(24.dp),
                    strokeWidth = 2.dp,
                )
                Spacer(Modifier.width(4.dp))
            }
            if (isActive) {
                if (isDisconnecting) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(24.dp),
                        strokeWidth = 2.dp,
                    )
                } else {
                    IconButton(onClick = onDisconnect) {
                        Icon(Icons.Default.Stop, "Disconnect", tint = MaterialTheme.colorScheme.error)
                    }
                }
            }
            if (!isActive) {
                var menuExpanded by remember { mutableStateOf(false) }
                Box {
                    IconButton(onClick = { menuExpanded = true }) {
                        Icon(
                            Icons.Default.MoreVert,
                            contentDescription = "Server options",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    DropdownMenu(
                        expanded = menuExpanded,
                        onDismissRequest = { menuExpanded = false },
                    ) {
                        DropdownMenuItem(
                            text = { Text("Edit") },
                            leadingIcon = { Icon(Icons.Default.Edit, contentDescription = null) },
                            onClick = {
                                menuExpanded = false
                                onEdit()
                            },
                        )
                        DropdownMenuItem(
                            text = { Text("Delete") },
                            leadingIcon = {
                                Icon(
                                    Icons.Default.Delete,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.error,
                                )
                            },
                            onClick = {
                                menuExpanded = false
                                onDelete()
                            },
                        )
                    }
                }
            }
        }
    }
}
