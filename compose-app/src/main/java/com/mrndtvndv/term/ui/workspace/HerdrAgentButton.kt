package com.mrndtvndv.term.ui.workspace

import android.view.View
import android.view.Window
import android.view.WindowManager
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountTree
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.SmartToy
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SmallFloatingActionButton
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogWindowProvider
import com.mrndtvndv.term.R
import com.mrndtvndv.term.server.HerdrWorkspaceResolver.HerdrPaneNode
import com.mrndtvndv.term.server.HerdrWorkspaceResolver.HerdrTabNode
import com.mrndtvndv.term.server.HerdrWorkspaceResolver.HerdrWorkspaceNode
import java.util.Locale
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Suppress("LongMethod", "LongParameterList")
@Composable
fun HerdrAgentButton(
    workspaces: List<HerdrWorkspaceNode>,
    isLoading: Boolean,
    error: String?,
    onRefresh: () -> Unit,
    onFocusTab: (HerdrTabNode) -> Unit,
    onFocusPane: (HerdrPaneNode) -> Unit,
    fabOpacity: Float = 0.7f,
    modifier: Modifier = Modifier,
) {
    var showAgents by remember { mutableStateOf(false) }
    val expandedWorkspaceIds = remember { mutableStateMapOf<String, Boolean>() }
    val sheetState = rememberModalBottomSheetState()
    val coroutineScope = rememberCoroutineScope()

    fun dismissAgents(afterDismiss: () -> Unit = {}) {
        coroutineScope.launch {
            sheetState.hide()
            showAgents = false
            afterDismiss()
        }
    }

    SmallFloatingActionButton(
        onClick = {
            showAgents = true
            onRefresh()
        },
        modifier = modifier.alpha(fabOpacity.coerceIn(0f, 1f)),
        containerColor = MaterialTheme.colorScheme.primaryContainer,
        contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
    ) {
        Icon(
            imageVector = Icons.Default.SmartToy,
            contentDescription = "Show agents",
        )
    }

    if (showAgents) {
        ModalBottomSheet(
            onDismissRequest = { showAgents = false },
            sheetState = sheetState,
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        ) {
            PlaceSheetAboveIme()
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 16.dp),
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = "Agents",
                        style = MaterialTheme.typography.titleLarge,
                        modifier = Modifier.weight(1f),
                    )
                }

                Spacer(modifier = Modifier.height(8.dp))

                when {
                    isLoading -> {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(32.dp),
                            contentAlignment = Alignment.Center,
                        ) {
                            CircularProgressIndicator()
                        }
                    }
                    error != null -> {
                        Text(
                            text = error,
                            color = MaterialTheme.colorScheme.error,
                            modifier = Modifier.padding(16.dp),
                        )
                    }
                    workspaces.isEmpty() -> {
                        Text(
                            text = "No herdr workspaces or agents",
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(16.dp),
                        )
                    }
                    else -> {
                        LazyColumn(modifier = Modifier.fillMaxWidth()) {
                            workspaces.forEach { workspace ->
                                val isExpanded = expandedWorkspaceIds[workspace.workspaceId] ?: true
                                item(key = "workspace:${workspace.workspaceId}") {
                                    WorkspaceListItem(
                                        workspace = workspace,
                                        isExpanded = isExpanded,
                                        onClick = {
                                            expandedWorkspaceIds[workspace.workspaceId] = !isExpanded
                                        },
                                    )
                                }
                                if (isExpanded) {
                                    workspace.tabs.forEach { tab ->
                                        val hasAgentPane = tab.panes.any { pane -> pane.agent != null }
                                        val displayPane = tab.panes.firstOrNull { pane ->
                                            pane.agent != null
                                        } ?: tab.panes.firstOrNull()
                                        val additionalPanes = tab.panes.filter { pane ->
                                            pane.paneId != displayPane?.paneId &&
                                                (!hasAgentPane || pane.agent != null)
                                        }
                                        item(key = "tab:${workspace.workspaceId}:${tab.tabId}") {
                                            AgentTabListItem(
                                                tab = tab,
                                                pane = displayPane,
                                                onClick = {
                                                    dismissAgents {
                                                        if (displayPane != null) {
                                                            onFocusPane(displayPane)
                                                        } else {
                                                            onFocusTab(tab)
                                                        }
                                                    }
                                                },
                                            )
                                        }
                                        additionalPanes.forEach { pane ->
                                            item(
                                                key = "pane:${workspace.workspaceId}:${tab.tabId}:${pane.paneId}",
                                            ) {
                                                PaneListItem(
                                                    pane = pane,
                                                    onClick = {
                                                        dismissAgents { onFocusPane(pane) }
                                                    },
                                                )
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

/**
 * Material3 hosts ModalBottomSheet in a platform Dialog, even in a Compose-only UI.
 * We need the dialog Window here because IME z-order is controlled by WindowManager, not by
 * Compose modifiers or ModalBottomSheetProperties.
 */
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
    // LocalView is the inner AndroidComposeView; DialogWindowProvider is on the dialog root.
    var current: View? = this
    while (current != null) {
        if (current is DialogWindowProvider) return current.window
        current = current.parent as? View
    }
    return null
}

@Composable
private fun WorkspaceListItem(
    workspace: HerdrWorkspaceNode,
    isExpanded: Boolean,
    onClick: () -> Unit,
) {
    val tabCount = workspace.tabs.size
    ListItem(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        colors = ListItemDefaults.colors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        ),
        headlineContent = {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = workspace.label,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    text = if (tabCount == 1) "1 tab" else "$tabCount tabs",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.labelMedium,
                    modifier = Modifier.padding(start = 8.dp),
                )
            }
        },
        trailingContent = {
            Icon(
                imageVector = if (isExpanded) {
                    Icons.Default.ExpandMore
                } else {
                    Icons.Default.ChevronRight
                },
                contentDescription = if (isExpanded) "Collapse workspace" else "Expand workspace",
            )
        },
    )
}

/*
 * Brand marks are vendored from Herdr's Apache-2.0 repository:
 * https://github.com/herdrdev/herdr/tree/master/website/assets/agent-icons
 * The Pi mark is sourced from https://pi.dev/logo-on-dark.svg.
 * The Prime Agent mark is sourced from:
 * https://github.com/PrimeIntellect-ai/prime-agent/blob/main/assets/brand/prime-butterfly.svg
 * The Cline mark is sourced from https://uxwing.com/cline-ai-icon/.
 */
private fun agentIconResource(agent: String?): Int? = when (agent?.lowercase(Locale.ROOT)) {
    "amp" -> R.drawable.agent_amp
    "agy", "antigravity" -> R.drawable.agent_antigravity
    "claude" -> R.drawable.agent_claude
    "cline" -> R.drawable.agent_cline
    "codex" -> R.drawable.agent_codex
    "copilot", "github-copilot" -> R.drawable.agent_copilot
    "cursor" -> R.drawable.agent_cursor
    "droid" -> R.drawable.agent_droid
    "grok" -> R.drawable.agent_grok
    "hermes" -> R.drawable.agent_hermes
    "kimi" -> R.drawable.agent_kimi
    "kiro" -> R.drawable.agent_kiro
    "mastracode" -> R.drawable.agent_mastracode
    "opencode" -> R.drawable.agent_opencode
    "pi" -> R.drawable.agent_pi
    "prime", "prime-agent", "primeintellect" -> R.drawable.agent_prime
    "qoder", "qodercli" -> R.drawable.agent_qoder
    else -> null
}

@Composable
private fun AgentIcon(
    agent: String?,
    tint: Color,
    modifier: Modifier,
) {
    val iconResource = agentIconResource(agent)
    if (iconResource == null) {
        Icon(
            imageVector = Icons.Default.AccountTree,
            contentDescription = null,
            tint = tint,
            modifier = modifier,
        )
    } else {
        Icon(
            painter = painterResource(iconResource),
            contentDescription = null,
            tint = tint,
            modifier = modifier,
        )
    }
}

@Composable
@Suppress("LongMethod")
private fun AgentTabListItem(
    tab: HerdrTabNode,
    pane: HerdrPaneNode?,
    onClick: () -> Unit,
) {
    val displayAgent = pane?.agent ?: tab.agent
    val displayStatus = pane?.agentStatus ?: tab.agentStatus
    val displayFocused = pane?.focused ?: tab.focused
    val displayTitle = pane?.title ?: tab.title
    val status = displayStatus.lowercase(Locale.ROOT).ifBlank { "unknown" }
    val statusColor = when (status) {
        "working" -> MaterialTheme.colorScheme.primary
        "blocked" -> MaterialTheme.colorScheme.error
        "done" -> MaterialTheme.colorScheme.tertiary
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    val containerColor = if (displayFocused) {
        MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.6f)
    } else {
        MaterialTheme.colorScheme.surfaceContainerLow
    }

    ListItem(
        modifier = Modifier.clickable(onClick = onClick),
        colors = ListItemDefaults.colors(
            containerColor = containerColor,
            headlineColor = when {
                status == "working" -> statusColor
                displayFocused -> MaterialTheme.colorScheme.onPrimaryContainer
                else -> MaterialTheme.colorScheme.onSurface
            },
            supportingColor = if (displayFocused) {
                MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.7f)
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            },
        ),
        leadingContent = {
            AgentIcon(
                agent = displayAgent,
                tint = statusColor,
                modifier = Modifier.size(24.dp),
            )
        },
        headlineContent = {
            Text(
                text = displayTitle.ifBlank { "Terminal" },
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        },
        supportingContent = {
            displayAgent?.takeIf { it.isNotBlank() }?.let { agentName ->
                Text(
                    text = agentName,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        },
        trailingContent = {
            if (displayAgent != null) {
                Text(
                    text = status,
                    color = statusColor,
                    style = MaterialTheme.typography.labelMedium,
                )
            }
        },
    )
}

@Composable
@Suppress("LongMethod")
private fun PaneListItem(
    pane: HerdrPaneNode,
    onClick: () -> Unit,
) {
    val status = pane.agentStatus.lowercase(Locale.ROOT).ifBlank { "unknown" }
    val statusColor = when (status) {
        "working" -> MaterialTheme.colorScheme.primary
        "blocked" -> MaterialTheme.colorScheme.error
        "done" -> MaterialTheme.colorScheme.tertiary
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    val containerColor = if (pane.focused) {
        MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.6f)
    } else {
        MaterialTheme.colorScheme.surfaceContainerLow
    }

    ListItem(
        modifier = Modifier.clickable(onClick = onClick),
        colors = ListItemDefaults.colors(
            containerColor = containerColor,
            headlineColor = when {
                status == "working" -> statusColor
                pane.focused -> MaterialTheme.colorScheme.onPrimaryContainer
                else -> MaterialTheme.colorScheme.onSurface
            },
            supportingColor = if (pane.focused) {
                MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.7f)
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            },
        ),
        leadingContent = {
            AgentIcon(
                agent = pane.agent,
                tint = statusColor,
                modifier = Modifier.size(24.dp),
            )
        },
        headlineContent = {
            Text(
                text = pane.title,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        },
        supportingContent = {
            pane.agent?.takeIf { it.isNotBlank() }?.let { agentName ->
                Text(
                    text = agentName,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        },
        trailingContent = {
            if (pane.agent != null) {
                Text(
                    text = status,
                    color = statusColor,
                    style = MaterialTheme.typography.labelMedium,
                )
            }
        },
    )
}
