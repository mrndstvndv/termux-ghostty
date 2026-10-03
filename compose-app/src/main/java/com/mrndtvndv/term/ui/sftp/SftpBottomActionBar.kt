package com.mrndtvndv.term.ui.sftp

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.FileUpload
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import com.mrndtvndv.term.ui.components.FloatingPill
import com.mrndtvndv.term.ui.components.PillActions
import com.mrndtvndv.term.ui.components.PillDivider
import com.mrndtvndv.term.ui.components.PillIconButton

/** SFTP pill: search and upload actions that morph into an inline search field. */
@Composable
fun SftpBottomActionBar(
    isSearchActive: Boolean,
    searchQuery: String,
    onSearchClick: () -> Unit,
    onCloseSearch: () -> Unit,
    onSearchQueryChange: (String) -> Unit,
    onUploadClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    BackHandler(enabled = isSearchActive) {
        onCloseSearch()
    }

    FloatingPill(state = isSearchActive, modifier = modifier, label = "SftpBottomActionBar") { active ->
        if (active) {
            SearchEditField(
                query = searchQuery,
                onQueryChange = onSearchQueryChange,
                onClose = onCloseSearch
            )
        } else {
            PillActions {
                PillIconButton(Icons.Default.Search, "Search files", onSearchClick)
                PillDivider()
                PillIconButton(Icons.Default.FileUpload, "Upload file", onUploadClick)
            }
        }
    }
}

@Composable
private fun SearchEditField(
    query: String,
    onQueryChange: (String) -> Unit,
    onClose: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .widthIn(max = 560.dp)
            .height(56.dp)
            .padding(horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        IconButton(
            onClick = onClose,
            modifier = Modifier.size(44.dp)
        ) {
            Icon(
                imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                contentDescription = "Close search",
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        SearchTextInput(
            query = query,
            onQueryChange = onQueryChange,
            modifier = Modifier.weight(1f)
        )

        if (query.isNotEmpty()) {
            IconButton(
                onClick = { onQueryChange("") },
                modifier = Modifier.size(44.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.Close,
                    contentDescription = "Clear search",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun SearchTextInput(
    query: String,
    onQueryChange: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    val focusRequester = remember { FocusRequester() }
    val keyboardController = LocalSoftwareKeyboardController.current

    LaunchedEffect(Unit) {
        focusRequester.requestFocus()
        keyboardController?.show()
    }

    BasicTextField(
        value = query,
        onValueChange = onQueryChange,
        modifier = modifier
            .focusRequester(focusRequester)
            .padding(horizontal = 8.dp),
        textStyle = MaterialTheme.typography.bodyLarge.copy(
            color = MaterialTheme.colorScheme.onSurface
        ),
        cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
        singleLine = true,
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
        keyboardActions = KeyboardActions(onSearch = { keyboardController?.hide() }),
        decorationBox = { innerTextField ->
            Box(
                contentAlignment = Alignment.CenterStart,
                modifier = Modifier.fillMaxWidth()
            ) {
                if (query.isEmpty()) {
                    Text(
                        text = "Search files…",
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                    )
                }
                innerTextField()
            }
        }
    )
}
