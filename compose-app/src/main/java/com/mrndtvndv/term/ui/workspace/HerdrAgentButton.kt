package com.mrndtvndv.term.ui.workspace

import android.view.View
import android.view.Window
import android.view.WindowManager
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountTree
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.SmartToy
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SmallFloatingActionButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogWindowProvider
import androidx.compose.ui.window.PopupProperties
import com.mrndtvndv.term.R
import com.mrndtvndv.term.server.HerdrWorkspaceResolver.HerdrPaneNode
import com.mrndtvndv.term.server.HerdrWorkspaceResolver.HerdrTabNode
import com.mrndtvndv.term.server.HerdrWorkspaceResolver.HerdrWorkspaceNode
import java.util.Locale
import kotlin.math.roundToInt
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
    onClosePane: (HerdrPaneNode) -> Unit = {},
    fabOpacity: Float = 0.7f,
    modifier: Modifier = Modifier,
) {
    var showAgents by remember { mutableStateOf(false) }
    val expandedWorkspaceIds = remember { mutableStateMapOf<String, Boolean>() }
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val coroutineScope = rememberCoroutineScope()
    var pendingClosePane by remember { mutableStateOf<HerdrPaneNode?>(null) }

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
        elevation = FloatingActionButtonDefaults.elevation(
            defaultElevation = 0.dp,
            pressedElevation = 0.dp,
            focusedElevation = 0.dp,
            hoveredElevation = 0.dp,
        ),
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
                                            TabGroupItem(
                                                tab = tab,
                                                displayPane = displayPane,
                                                additionalPanes = additionalPanes,
                                                onOpen = {
                                                    dismissAgents {
                                                        if (displayPane != null) {
                                                            onFocusPane(displayPane)
                                                        } else {
                                                            onFocusTab(tab)
                                                        }
                                                    }
                                                },
                                                onSelectPane = { pane ->
                                                    dismissAgents { onFocusPane(pane) }
                                                },
                                                onClosePane = { pane -> pendingClosePane = pane },
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

        pendingClosePane?.let { pane ->
            AlertDialog(
                onDismissRequest = { pendingClosePane = null },
                title = { Text("Close pane?") },
                text = {
                    PlaceSheetAboveIme()
                    Text(
                        "This closes ${pane.title.ifBlank { "this pane" }} and terminates whatever is running in it.",
                    )
                },
                confirmButton = {
                    TextButton(
                        onClick = {
                            pendingClosePane = null
                            onClosePane(pane)
                        },
                    ) {
                        Text("Close", color = MaterialTheme.colorScheme.error)
                    }
                },
                dismissButton = {
                    TextButton(onClick = { pendingClosePane = null }) {
                        Text("Cancel")
                    }
                },
            )
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
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .background(MaterialTheme.colorScheme.surfaceContainerLow)
            .padding(horizontal = 16.dp, vertical = WorkspaceRowVerticalPadding),
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
        Spacer(modifier = Modifier.width(16.dp))
        Icon(
            imageVector = if (isExpanded) {
                Icons.Default.ExpandMore
            } else {
                Icons.Default.ChevronRight
            },
            contentDescription = if (isExpanded) "Collapse workspace" else "Expand workspace",
        )
    }
}

/*
 * Brand marks are vendored from Herdr's Apache-2.0 repository:
 * https://github.com/herdrdev/herdr/tree/master/website/assets/agent-icons
 * The Pi mark is sourced from https://pi.dev/logo-on-dark.svg.
 * The omp (Oh My Pi) mark is sourced from:
 * https://github.com/can1357/oh-my-pi/blob/main/assets/icon.svg
 * The Prime Agent mark is sourced from:
 * https://github.com/PrimeIntellect-ai/prime-agent/blob/main/assets/brand/prime-butterfly.svg
 * The Cline mark is sourced from https://uxwing.com/cline-ai-icon/.
 * The Neovim mark is sourced from https://neovim.io/logos/neovim-mark.svg.
 * The fish mark is sourced from Simple Icons (CC0 1.0, https://github.com/simple-icons/simple-icons/blob/develop/LICENSE.md):
 * https://github.com/simple-icons/simple-icons/blob/develop/icons/fishshell.svg
 * The Java/OpenJDK mark is sourced from Simple Icons (CC0 1.0):
 * https://github.com/simple-icons/simple-icons/blob/develop/icons/openjdk.svg
 * The Bun mark is sourced from Simple Icons (CC0 1.0):
 * https://github.com/simple-icons/simple-icons/blob/develop/icons/bun.svg
 * The Git mark is sourced from Simple Icons (CC0 1.0):
 * https://github.com/simple-icons/simple-icons/blob/develop/icons/git.svg
 * The fx mark is sourced from https://fx.sh/favicon.png.
 * The Cargo process icon is an original generic crate mark and does not reproduce
 * the Rust/Cargo trademarks.
 */
@Suppress("CyclomaticComplexMethod")
private fun agentIconResource(agent: String?): Int? = when (agent?.lowercase(Locale.ROOT)) {
    "amp" -> R.drawable.agent_amp
    "agy", "antigravity" -> R.drawable.agent_antigravity
    "claude" -> R.drawable.agent_claude
    "cline" -> R.drawable.agent_cline
    "codex" -> R.drawable.agent_codex
    "copilot", "github-copilot" -> R.drawable.agent_copilot
    "cursor" -> R.drawable.agent_cursor
    "droid" -> R.drawable.agent_droid
    "fx" -> R.drawable.agent_fx
    "git", "lazygit" -> R.drawable.agent_git
    "grok" -> R.drawable.agent_grok
    "hermes" -> R.drawable.agent_hermes
    "kimi" -> R.drawable.agent_kimi
    "kiro" -> R.drawable.agent_kiro
    "mastracode" -> R.drawable.agent_mastracode
    "omp" -> R.drawable.agent_omp
    "opencode" -> R.drawable.agent_opencode
    "pi" -> R.drawable.agent_pi
    "prime", "prime-agent", "primeintellect" -> R.drawable.agent_prime
    "qoder", "qodercli" -> R.drawable.agent_qoder
    else -> null
}

//** True when a non-agent pane is running nvim: title is `NVIM` or `<path> - NVIM`. */
private fun isNvimTitle(title: String, agent: String?): Boolean {
    if (!agent.isNullOrBlank()) return false
    val lower = title.lowercase(Locale.ROOT)
    return lower == "nvim" || lower.startsWith("nvim ") || lower.endsWith("- nvim")
}

/** Width of the long-press pane context menu; used to keep the menu inside the row. */
private val ContextMenuWidth = 168.dp

private val WorkspaceRowVerticalPadding = 4.dp
private val AgentRowVerticalPadding = 8.dp

private fun processIconResource(processName: String?): Int? = when (
    processName?.lowercase(Locale.ROOT)
) {
    "nvim" -> R.drawable.agent_nvim
    "fish" -> R.drawable.agent_fish
    "fx" -> R.drawable.agent_fx
    "java", "javac", "openjdk" -> R.drawable.agent_java
    "bun", "bunx" -> R.drawable.agent_bun
    "cargo", "rustc" -> R.drawable.agent_cargo
    "git", "lazygit", "gitui", "tig" -> R.drawable.agent_git
    else -> null
}

/** Foreground process of an idle pane; nvim title fallback when herdr reports none. */
private fun idleProcessName(agent: String?, processName: String?, title: String): String? {
    if (!agent.isNullOrBlank()) return null
    if (processName != null) return processName
    return if (isNvimTitle(title, agent)) "nvim" else null
}

/** Idle process shown for a tab row: its display pane, or the tab's own title. */
private fun tabIdleProcessName(pane: HerdrPaneNode?, tab: HerdrTabNode): String? =
    idleProcessName(
        agent = pane?.agent ?: tab.agent,
        processName = pane?.processName,
        title = pane?.title ?: tab.title,
    )

private fun agentRowTitle(pane: HerdrPaneNode?, tab: HerdrTabNode): String =
    pane?.agentSessionName ?: tab.agentSessionName ?: pane?.title ?: tab.title

@Composable
private fun AgentIcon(
    agent: String?,
    processName: String?,
    tint: Color,
    modifier: Modifier,
) {
    val iconResource = processIconResource(processName) ?: agentIconResource(agent)
    if (iconResource == null) {
        Icon(
            imageVector = if (agent.isNullOrBlank()) {
                Icons.Default.Terminal
            } else {
                Icons.Default.AccountTree
            },
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

/**
 * One tab's rows: the display pane row plus any additional pane rows.
 * When multiple panes share a tab, rows are grouped in a bordered box and the
 * currently focused pane is highlighted.
 */
@Composable
private fun TabGroupItem(
    tab: HerdrTabNode,
    displayPane: HerdrPaneNode?,
    additionalPanes: List<HerdrPaneNode>,
    onOpen: () -> Unit,
    onSelectPane: (HerdrPaneNode) -> Unit,
    onClosePane: (HerdrPaneNode) -> Unit,
) {
    if (additionalPanes.isEmpty()) {
        AgentTabListItem(
            tab = tab,
            pane = displayPane,
            highlighted = displayPane?.focused == true,
            onClick = onOpen,
            onClosePane = onClosePane,
        )
        additionalPanes.forEach { pane ->
            PaneListItem(
                pane = pane,
                highlighted = pane.focused,
                onClick = { onSelectPane(pane) },
                onClosePane = onClosePane,
            )
        }
        return
    }

    val shape = RoundedCornerShape(12.dp)
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 4.dp)
            .clip(shape)
            .border(
                width = 1.dp,
                color = MaterialTheme.colorScheme.primary.copy(alpha = 0.5f),
                shape = shape,
            ),
    ) {
        AgentTabListItem(
            tab = tab,
            pane = displayPane,
            highlighted = displayPane?.focused == true,
            onClick = onOpen,
            onClosePane = onClosePane,
        )
        additionalPanes.forEach { pane ->
            PaneListItem(
                pane = pane,
                highlighted = pane.focused,
                onClick = { onSelectPane(pane) },
                onClosePane = onClosePane,
            )
        }
    }
}

@Composable
@Suppress("LongMethod")
@OptIn(ExperimentalFoundationApi::class)
private fun AgentTabListItem(
    tab: HerdrTabNode,
    pane: HerdrPaneNode?,
    highlighted: Boolean,
    onClick: () -> Unit,
    onClosePane: (HerdrPaneNode) -> Unit,
) {
    val displayAgent = pane?.agent ?: tab.agent
    val idleProcess = tabIdleProcessName(pane, tab)
    val displayStatus = pane?.agentStatus ?: tab.agentStatus
    val displayTitle = agentRowTitle(pane, tab)
    val status = displayStatus.lowercase(Locale.ROOT).ifBlank { "unknown" }
    val statusColor = when (status) {
        "working" -> MaterialTheme.colorScheme.primary
        "blocked" -> MaterialTheme.colorScheme.error
        "done" -> MaterialTheme.colorScheme.tertiary
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    val containerColor = if (highlighted) {
        MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.6f)
    } else {
        MaterialTheme.colorScheme.surfaceContainerLow
    }
    val headlineColor = when {
        status == "working" -> statusColor
        highlighted -> MaterialTheme.colorScheme.onPrimaryContainer
        else -> MaterialTheme.colorScheme.onSurface
    }
    val supportingColor = if (highlighted) {
        MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.7f)
    } else {
        MaterialTheme.colorScheme.onSurfaceVariant
    }
    val density = LocalDensity.current
    val hapticFeedback = LocalHapticFeedback.current
    var showMenu by remember { mutableStateOf(false) }
    var menuOffset by remember { mutableStateOf(DpOffset.Zero) }
    var rowWidthPx by remember { mutableStateOf(0) }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .onGloballyPositioned { coords -> rowWidthPx = coords.size.width }
            .pointerInput(pane?.paneId) {
                detectTapGestures(
                    onTap = { onClick() },
                    onLongPress = { position ->
                        val menuWidthPx = with(density) { ContextMenuWidth.roundToPx() }
                        val clampedX = (position.x + menuWidthPx)
                            .coerceAtMost(rowWidthPx.toFloat())
                            .minus(menuWidthPx.toFloat())
                            .coerceAtLeast(0f)
                            .roundToInt()
                        menuOffset = DpOffset(
                            x = with(density) { clampedX.toDp() },
                            y = with(density) { position.y.roundToInt().toDp() },
                        )
                        hapticFeedback.performHapticFeedback(HapticFeedbackType.LongPress)
                        showMenu = true
                    },
                )
            },
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(containerColor)
                .padding(horizontal = 16.dp, vertical = AgentRowVerticalPadding),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            AgentIcon(
                agent = displayAgent,
                processName = idleProcess,
                tint = statusColor,
                modifier = Modifier.size(24.dp),
            )
            Spacer(modifier = Modifier.width(16.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = displayTitle.ifBlank { "Terminal" },
                    color = headlineColor,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.bodyLarge,
                )
                displayAgent?.takeIf { it.isNotBlank() }?.let { agentName ->
                    Text(
                        text = agentName,
                        color = supportingColor,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }
            if (displayAgent != null) {
                Spacer(modifier = Modifier.width(16.dp))
                Text(
                    text = status,
                    color = statusColor,
                    style = MaterialTheme.typography.labelMedium,
                )
            }
        }
        DropdownMenu(
            expanded = showMenu,
            onDismissRequest = { showMenu = false },
            modifier = Modifier.width(ContextMenuWidth),
            offset = menuOffset,
            properties = PopupProperties(focusable = false, dismissOnClickOutside = true),
        ) {
            pane?.let { targetPane ->
                DropdownMenuItem(
                    text = { Text("Close pane") },
                    onClick = {
                        showMenu = false
                        onClosePane(targetPane)
                    },
                )
            }
        }
    }
}

@Composable
@Suppress("LongMethod")
@OptIn(ExperimentalFoundationApi::class)
private fun PaneListItem(
    pane: HerdrPaneNode,
    highlighted: Boolean,
    onClick: () -> Unit,
    onClosePane: (HerdrPaneNode) -> Unit,
) {
    val status = pane.agentStatus.lowercase(Locale.ROOT).ifBlank { "unknown" }
    val statusColor = when (status) {
        "working" -> MaterialTheme.colorScheme.primary
        "blocked" -> MaterialTheme.colorScheme.error
        "done" -> MaterialTheme.colorScheme.tertiary
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    val containerColor = if (highlighted) {
        MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.6f)
    } else {
        MaterialTheme.colorScheme.surfaceContainerLow
    }
    val headlineColor = when {
        status == "working" -> statusColor
        highlighted -> MaterialTheme.colorScheme.onPrimaryContainer
        else -> MaterialTheme.colorScheme.onSurface
    }
    val supportingColor = if (highlighted) {
        MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.7f)
    } else {
        MaterialTheme.colorScheme.onSurfaceVariant
    }
    val density = LocalDensity.current
    val hapticFeedback = LocalHapticFeedback.current
    var showMenu by remember { mutableStateOf(false) }
    var menuOffset by remember { mutableStateOf(DpOffset.Zero) }
    var rowWidthPx by remember { mutableStateOf(0) }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .onGloballyPositioned { coords -> rowWidthPx = coords.size.width }
            .pointerInput(pane.paneId) {
                detectTapGestures(
                    onTap = { onClick() },
                    onLongPress = { position ->
                        val menuWidthPx = with(density) { ContextMenuWidth.roundToPx() }
                        val clampedX = (position.x + menuWidthPx)
                            .coerceAtMost(rowWidthPx.toFloat())
                            .minus(menuWidthPx.toFloat())
                            .coerceAtLeast(0f)
                            .roundToInt()
                        menuOffset = DpOffset(
                            x = with(density) { clampedX.toDp() },
                            y = with(density) { position.y.roundToInt().toDp() },
                        )
                        hapticFeedback.performHapticFeedback(HapticFeedbackType.LongPress)
                        showMenu = true
                    },
                )
            },
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(containerColor)
                .padding(horizontal = 16.dp, vertical = AgentRowVerticalPadding),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            AgentIcon(
                agent = pane.agent,
                processName = idleProcessName(pane.agent, pane.processName, pane.title),
                tint = statusColor,
                modifier = Modifier.size(24.dp),
            )
            Spacer(modifier = Modifier.width(16.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = pane.agentSessionName ?: pane.title,
                    color = headlineColor,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.bodyLarge,
                )
                pane.agent?.takeIf { it.isNotBlank() }?.let { agentName ->
                    Text(
                        text = agentName,
                        color = supportingColor,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }
            if (pane.agent != null) {
                Spacer(modifier = Modifier.width(16.dp))
                Text(
                    text = status,
                    color = statusColor,
                    style = MaterialTheme.typography.labelMedium,
                )
            }
        }
        DropdownMenu(
            expanded = showMenu,
            onDismissRequest = { showMenu = false },
            modifier = Modifier.width(ContextMenuWidth),
            offset = menuOffset,
            properties = PopupProperties(focusable = false, dismissOnClickOutside = true),
        ) {
            DropdownMenuItem(
                text = { Text("Close pane") },
                onClick = {
                    showMenu = false
                    onClosePane(pane)
                },
            )
        }
    }
}
