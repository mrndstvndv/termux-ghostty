package com.mrndtvndv.term.ui.workspace

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountTree
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.SmartToy
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SmallFloatingActionButton
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.mrndtvndv.term.R
import com.mrndtvndv.term.server.HerdrWorkspaceResolver
import java.util.Locale
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Suppress("LongMethod")
@Composable
fun HerdrAgentButton(
    agents: List<HerdrWorkspaceResolver.HerdrAgentInfo>,
    isLoading: Boolean,
    error: String?,
    onRefresh: () -> Unit,
    onFocusAgent: (HerdrWorkspaceResolver.HerdrAgentInfo) -> Unit,
    fabOpacity: Float = 0.7f,
    modifier: Modifier = Modifier,
) {
    var showAgents by remember { mutableStateOf(false) }
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
        ) {
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
                    IconButton(onClick = { dismissAgents() }) {
                        Icon(
                            imageVector = Icons.Default.Close,
                            contentDescription = "Close agents",
                        )
                    }
                }

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
                    agents.isEmpty() -> {
                        Text(
                            text = "No recognized agents",
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(16.dp),
                        )
                    }
                    else -> {
                        LazyColumn(modifier = Modifier.fillMaxWidth()) {
                            items(agents, key = { it.paneId }) { agent ->
                                AgentListItem(
                                    agent = agent,
                                    title = agentWorkspaceTitle(agent, agents),
                                    onClick = {
                                        dismissAgents { onFocusAgent(agent) }
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

private fun agentWorkspaceTitle(
    agent: HerdrWorkspaceResolver.HerdrAgentInfo,
    agents: List<HerdrWorkspaceResolver.HerdrAgentInfo>,
): String {
    agent.workspaceLabel?.trim()?.takeIf { it.isNotEmpty() }?.let { return it }

    val cwd = agent.cwd?.trim()?.trimEnd('/')?.takeIf { it.isNotEmpty() }
    val directory = cwd?.substringAfterLast('/')?.takeIf { it.isNotEmpty() }
    if (directory != null) {
        val duplicateDirectory = agents.any { other ->
            other.workspaceId != agent.workspaceId &&
                other.cwd?.trim()?.trimEnd('/')?.substringAfterLast('/') == directory
        }
        return if (duplicateDirectory) cwd else directory
    }

    return agent.workspaceId
}

/*
 * Brand marks are vendored from Herdr's Apache-2.0 repository:
 * https://github.com/herdrdev/herdr/tree/master/website/assets/agent-icons
 * The Pi mark is sourced from https://pi.dev/logo-on-dark.svg.
 * The Prime Agent mark is sourced from:
 * https://github.com/PrimeIntellect-ai/prime-agent/blob/main/assets/brand/prime-butterfly.svg
 */
private fun agentIconResource(agent: String?): Int? = when (agent?.lowercase(Locale.ROOT)) {
    "amp" -> R.drawable.agent_amp
    "agy", "antigravity" -> R.drawable.agent_antigravity
    "claude" -> R.drawable.agent_claude
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
private fun AgentListItem(
    agent: HerdrWorkspaceResolver.HerdrAgentInfo,
    title: String,
    onClick: () -> Unit,
) {
    val status = agent.agentStatus.lowercase(Locale.ROOT)
    val supporting = listOfNotNull(
        agent.cwd,
        agent.paneId,
    ).joinToString(" · ")
    val statusColor = when (status) {
        "working" -> MaterialTheme.colorScheme.primary
        "blocked" -> MaterialTheme.colorScheme.error
        "done" -> MaterialTheme.colorScheme.tertiary
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }

    ListItem(
        modifier = Modifier.clickable(onClick = onClick),
        colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
        leadingContent = {
            AgentIcon(
                agent = agent.agent,
                tint = statusColor,
                modifier = Modifier.size(24.dp),
            )
        },
        headlineContent = {
            Text(
                text = title,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        },
        supportingContent = {
            Text(
                text = supporting,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        },
        trailingContent = {
            Text(
                text = status,
                color = statusColor,
                style = MaterialTheme.typography.labelMedium,
            )
        },
    )
}
