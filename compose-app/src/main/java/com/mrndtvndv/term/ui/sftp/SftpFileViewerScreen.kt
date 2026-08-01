@file:Suppress("MaxLineLength")

package com.mrndtvndv.term.ui.sftp

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.rememberTransformableState
import androidx.compose.foundation.gestures.transformable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mrndtvndv.term.ui.highlightCode
import com.mrndtvndv.term.ui.theme.codeFontFamily
import java.io.File

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SftpFileViewerScreen(file: File, onClose: () -> Unit) {
    var content by remember { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(file) {
        try {
            content = file.readText()
        } catch (e: Exception) {
            error = e.localizedMessage ?: "Failed to read file"
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(file.name) },
                navigationIcon = {
                    IconButton(onClick = onClose) {
                        Icon(Icons.Default.Close, contentDescription = "Close")
                    }
                }
            )
        }
    ) { padding ->
        Surface(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            when {
                error != null -> {
                    Text(text = error!!, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(16.dp))
                }
                content != null -> {
                    CodeViewer(code = content!!)
                }
                else -> {
                    CircularProgressIndicator(modifier = Modifier.padding(16.dp))
                }
            }
        }
    }
}

@Suppress("LongMethod")
@Composable
private fun CodeViewer(code: String) {
    var fontScale by remember { mutableFloatStateOf(1f) }
    val transformState = rememberTransformableState { zoomChange, _, _ ->
        fontScale = (fontScale * zoomChange).coerceIn(0.6f, 3.0f)
    }

    val scrollState = rememberScrollState()
    val horizScrollState = rememberScrollState()
    val isDark = MaterialTheme.colorScheme.background.luminance() < 0.5f

    val lines = remember(code) {
        if (code.endsWith("\n")) code.lines().dropLast(1) else code.lines()
    }
    val digitCount = lines.size.toString().length.coerceAtLeast(1)
    val dims = remember(digitCount, fontScale) {
        ViewerDimensions(
            numWidth = ((digitCount * 7 + 4) * fontScale).dp,
            lineHeight = (20 * fontScale).dp,
            codeFontSize = (12 * fontScale).sp,
            lineNumFontSize = (10 * fontScale).sp
        )
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .transformable(state = transformState)
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(scrollState)
                .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.1f))
        ) {
            Row(modifier = Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier.width(dims.numWidth),
                    horizontalAlignment = Alignment.End
                ) {
                    lines.forEachIndexed { index, _ ->
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(dims.lineHeight)
                                .padding(end = 8.dp),
                            contentAlignment = Alignment.CenterEnd
                        ) {
                            Text(
                                text = (index + 1).toString(),
                                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f),
                                fontSize = dims.lineNumFontSize,
                                fontFamily = codeFontFamily()
                            )
                        }
                    }
                }

                Box(
                    modifier = Modifier
                        .width(1.dp)
                        .height((lines.size * dims.lineHeight.value).dp)
                        .background(MaterialTheme.colorScheme.outline.copy(alpha = 0.2f))
                )

                SelectionContainer {
                    Column(
                        modifier = Modifier
                            .weight(1f)
                            .horizontalScroll(horizScrollState)
                    ) {
                        lines.forEach { line ->
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(dims.lineHeight)
                                    .padding(horizontal = 8.dp),
                                contentAlignment = Alignment.CenterStart
                            ) {
                                Text(
                                    text = highlightCode(line, isDark),
                                    fontSize = dims.codeFontSize,
                                    fontFamily = codeFontFamily(),
                                    softWrap = false,
                                    maxLines = 1
                                )
                            }
                        }
                    }
                }
            }
        }

        if (fontScale != 1f) {
            Surface(
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.9f),
                shape = RoundedCornerShape(16.dp),
                shadowElevation = 4.dp,
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(16.dp)
                    .clickable { fontScale = 1f }
            ) {
                Text(
                    text = "${(fontScale * 100).toInt()}% (Tap to reset)",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
                )
            }
        }
    }
}

private data class ViewerDimensions(
    val numWidth: Dp,
    val lineHeight: Dp,
    val codeFontSize: TextUnit,
    val lineNumFontSize: TextUnit
)
