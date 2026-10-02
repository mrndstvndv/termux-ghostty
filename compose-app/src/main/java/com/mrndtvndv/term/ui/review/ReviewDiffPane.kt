@file:OptIn(ExperimentalMaterial3Api::class)

package com.mrndtvndv.term.ui.review

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.CompareArrows
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.FormatListNumbered
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.UnfoldMore
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconToggleButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.PlainTooltip
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TooltipAnchorPosition
import androidx.compose.material3.TooltipBox
import androidx.compose.material3.TooltipDefaults
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberTooltipState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

@Suppress("LongParameterList")
@Composable
internal fun DiffPane(
    title: String,
    subtitle: String,
    state: ReviewScreenState,
    search: DiffSearch,
    showFullFileToggle: Boolean,
    onToggleFullFileMode: () -> Unit,
    onToggleLineNumbers: () -> Unit,
    onToggleWordDiff: () -> Unit,
    onCloseSearch: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    val scrollBehavior = TopAppBarDefaults.enterAlwaysScrollBehavior(
        canScroll = { !search.isVisible }
    )
    LaunchedEffect(search.isVisible) {
        if (search.isVisible) {
            scrollBehavior.state.heightOffset = 0f
        }
    }
    Scaffold(
        modifier = modifier
            .fillMaxSize()
            .nestedScroll(scrollBehavior.nestedScrollConnection),
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = {
            TopAppBar(
                scrollBehavior = scrollBehavior,
                windowInsets = WindowInsets(0, 0, 0, 0),
                title = { DiffPaneTitle(title = title, subtitle = subtitle, search = search) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Back"
                        )
                    }
                },
                actions = {
                    DiffPaneActions(
                        state = state,
                        search = search,
                        showFullFileToggle = showFullFileToggle,
                        onToggleFullFileMode = onToggleFullFileMode,
                        onToggleLineNumbers = onToggleLineNumbers,
                        onToggleWordDiff = onToggleWordDiff,
                        onCloseSearch = onCloseSearch
                    )
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface
                )
            )
        }
    ) { padding ->
        Box(modifier = Modifier.fillMaxSize().padding(padding)) {
            DiffViewer(
                hasSelection = state.selectedFile != null || state.selectedCommit != null,
                diffContent = state.diffContent,
                showLineNumbers = state.showLineNumbers,
                isWordDiffEnabled = state.isWordDiffEnabled,
                search = search
            )
        }
    }
}

@Composable
private fun DiffPaneTitle(title: String, subtitle: String, search: DiffSearch) {
    if (search.isVisible) {
        DiffSearchField(
            query = search.query,
            onQueryChange = search::updateQuery,
            onNext = { search.moveMatch(1) }
        )
    } else {
        Column {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
            )
        }
    }
}

@Suppress("LongParameterList")
@Composable
private fun DiffPaneActions(
    state: ReviewScreenState,
    search: DiffSearch,
    showFullFileToggle: Boolean,
    onToggleFullFileMode: () -> Unit,
    onToggleLineNumbers: () -> Unit,
    onToggleWordDiff: () -> Unit,
    onCloseSearch: () -> Unit
) {
    if (search.isVisible) {
        DiffSearchActions(
            query = search.query,
            currentMatchIndex = search.matchIndex,
            matchCount = search.matchCount,
            onPrevious = { search.moveMatch(-1) },
            onNext = { search.moveMatch(1) },
            onClose = onCloseSearch
        )
        return
    }
    Row(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.padding(end = 8.dp)
    ) {
        IconButton(onClick = search::open) {
            Icon(
                imageVector = Icons.Default.Search,
                contentDescription = "Search diff"
            )
        }
        DiffToggle(
            tooltip = if (state.isWordDiffEnabled) "Word-level diff on" else "Word-level diff off",
            checked = state.isWordDiffEnabled,
            icon = Icons.AutoMirrored.Filled.CompareArrows,
            contentDescription = if (state.isWordDiffEnabled) {
                "Disable word-level diff"
            } else {
                "Enable word-level diff"
            },
            onToggle = onToggleWordDiff
        )
        DiffToggle(
            tooltip = "Line Numbers",
            checked = state.showLineNumbers,
            icon = Icons.Default.FormatListNumbered,
            contentDescription = "Toggle line numbers",
            onToggle = onToggleLineNumbers
        )
        if (showFullFileToggle) {
            DiffToggle(
                tooltip = if (state.isFullFileMode) "Full File" else "Diff Only",
                checked = state.isFullFileMode,
                icon = if (state.isFullFileMode) Icons.Default.Visibility else Icons.Default.UnfoldMore,
                contentDescription = "Toggle full file mode",
                onToggle = onToggleFullFileMode
            )
        }
    }
}

@Composable
private fun DiffToggle(
    tooltip: String,
    checked: Boolean,
    icon: ImageVector,
    contentDescription: String,
    onToggle: () -> Unit
) {
    TooltipBox(
        positionProvider = TooltipDefaults.rememberTooltipPositionProvider(
            positioning = TooltipAnchorPosition.Above
        ),
        tooltip = { PlainTooltip { Text(tooltip) } },
        state = rememberTooltipState()
    ) {
        IconToggleButton(
            checked = checked,
            onCheckedChange = { onToggle() }
        ) {
            Icon(
                imageVector = icon,
                contentDescription = contentDescription,
                modifier = Modifier.size(16.dp)
            )
        }
    }
}

@Composable
private fun DiffSearchField(
    query: String,
    onQueryChange: (String) -> Unit,
    onNext: () -> Unit
) {
    val focusRequester = remember { FocusRequester() }
    val keyboardController = LocalSoftwareKeyboardController.current

    LaunchedEffect(Unit) {
        focusRequester.requestFocus()
        keyboardController?.show()
    }

    OutlinedTextField(
        value = query,
        onValueChange = onQueryChange,
        modifier = Modifier
            .fillMaxWidth()
            .focusRequester(focusRequester),
        placeholder = { Text("Search diff") },
        leadingIcon = {
            Icon(
                imageVector = Icons.Default.Search,
                contentDescription = "Search diff"
            )
        },
        trailingIcon = {
            if (query.isNotEmpty()) {
                IconButton(onClick = { onQueryChange("") }) {
                    Icon(
                        imageVector = Icons.Default.Clear,
                        contentDescription = "Clear search"
                    )
                }
            }
        },
        singleLine = true,
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
        keyboardActions = KeyboardActions(onSearch = { onNext() })
    )
}

@Composable
private fun DiffSearchActions(
    query: String,
    currentMatchIndex: Int,
    matchCount: Int,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
    onClose: () -> Unit
) {
    val matchLabel = when {
        query.isEmpty() -> null
        matchCount == 0 -> "No matches"
        else -> "${currentMatchIndex + 1}/$matchCount"
    }

    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.padding(end = 8.dp)
    ) {
        matchLabel?.let { label ->
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                modifier = Modifier.padding(horizontal = 4.dp)
            )
        }
        IconButton(
            onClick = onPrevious,
            enabled = matchCount > 0
        ) {
            Icon(
                imageVector = Icons.Default.KeyboardArrowUp,
                contentDescription = "Previous match"
            )
        }
        IconButton(
            onClick = onNext,
            enabled = matchCount > 0
        ) {
            Icon(
                imageVector = Icons.Default.KeyboardArrowDown,
                contentDescription = "Next match"
            )
        }
        IconButton(onClick = onClose) {
            Icon(
                imageVector = Icons.Default.Close,
                contentDescription = "Close search"
            )
        }
    }
}
