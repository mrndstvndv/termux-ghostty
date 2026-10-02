@file:OptIn(ExperimentalMaterial3ExpressiveApi::class)

package com.mrndtvndv.term.ui.review

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalContext
import com.mrndtvndv.term.ui.theme.LocalCustomFontFamily
import java.io.File

/**
 * True only for git's binary markers (`Binary files ... differ`, `GIT binary patch`)
 * emitted as their own diff lines. A plain substring check false-positives on
 * text diffs whose added lines merely mention those phrases (e.g. the
 * diff-viewer's own source containing `/^Binary files .+ differ/`).
 */
internal fun isBinaryDiff(rawDiff: String): Boolean =
    rawDiff.lineSequence().any { line ->
        (line.startsWith("Binary files ") && line.contains(" differ")) ||
            line.startsWith("GIT binary patch")
    }

@Stable
internal class DiffSearch {
    val controller = DiffSearchController()
    var isVisible by mutableStateOf(false)
        private set
    var query by mutableStateOf("")
        private set
    var matchIndex by mutableIntStateOf(0)
    var matchCount by mutableIntStateOf(0)

    fun open() {
        isVisible = true
    }

    fun reset() {
        isVisible = false
        query = ""
        matchIndex = 0
        matchCount = 0
    }

    fun updateQuery(value: String) {
        query = value
        matchIndex = 0
    }

    fun moveMatch(offset: Int) {
        if (matchCount == 0) return
        if (controller.findNextAction != null) {
            controller.findNext(offset > 0)
        } else {
            matchIndex = (matchIndex + offset + matchCount) % matchCount
        }
    }
}

@Composable
internal fun DiffViewer(
    hasSelection: Boolean,
    diffContent: DiffContentState?,
    showLineNumbers: Boolean,
    isWordDiffEnabled: Boolean,
    search: DiffSearch
) {
    if (!hasSelection) {
        EmptyDiffMessage(
            message = "Select a file or commit to view details",
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
        )
        return
    }

    when (diffContent) {
        null -> EmptyDiffMessage("No diff details loaded.")
        is DiffContentState.Loading -> {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                LoadingIndicator()
            }
        }
        is DiffContentState.Error -> EmptyDiffMessage(diffContent.message)
        is DiffContentState.Ready -> ReadyDiff(diffContent, showLineNumbers, isWordDiffEnabled, search)
    }
}

@Composable
private fun ReadyDiff(
    content: DiffContentState.Ready,
    showLineNumbers: Boolean,
    isWordDiffEnabled: Boolean,
    search: DiffSearch
) {
    val rawDiff = content.rawDiff
    when {
        isBinaryDiff(rawDiff) -> EmptyDiffMessage("Binary file changed (diff not available).")
        rawDiff.isNotBlank() -> {
            val isDark = MaterialTheme.colorScheme.surface.luminance() < 0.5f
            val useCustomFont = LocalCustomFontFamily.current != null
            val context = LocalContext.current
            val fontVersion = remember(useCustomFont) {
                val file = File(context.filesDir, "font.ttf")
                if (useCustomFont && file.exists()) file.lastModified() else 0L
            }
            PierreDiffView(
                rawDiff = rawDiff,
                settings = DiffDisplaySettings(
                    isDarkTheme = isDark,
                    showLineNumbers = showLineNumbers,
                    isWordDiffEnabled = isWordDiffEnabled,
                    useCustomFont = useCustomFont,
                    fontVersion = fontVersion
                ),
                search = DiffSearchState(
                    query = if (search.isVisible) search.query else "",
                    controller = search.controller,
                    onMatchCountChange = { search.matchCount = it },
                    onMatchIndexChange = { search.matchIndex = it }
                )
            )
        }
        else -> EmptyDiffMessage("No changes detected in file.")
    }
}

@Composable
private fun EmptyDiffMessage(message: String, color: Color = Color.Unspecified) {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text(message, color = color)
    }
}
