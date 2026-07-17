package com.mrndtvndv.term.ui.review

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GitReviewScreen(
    viewModel: ReviewViewModel,
    modifier: Modifier = Modifier
) {
    val uiState by viewModel.uiState.collectAsState()
    val selectedFile by viewModel.selectedFile.collectAsState()
    val selectedFileDiff by viewModel.selectedFileDiff.collectAsState()
    val isDiffLoading by viewModel.isDiffLoading.collectAsState()
    val errorMessage by viewModel.errorMessage.collectAsState()

    val configuration = LocalConfiguration.current
    val isWideScreen = configuration.screenWidthDp >= 600

    var discardConfirmFile by remember { mutableStateOf<GitFileStatus?>(null) }

    // Error Alert Dialog
    errorMessage?.let { error ->
        AlertDialog(
            onDismissRequest = { viewModel.clearErrorMessage() },
            title = { Text("Git Error") },
            text = { Text(error) },
            confirmButton = {
                TextButton(onClick = { viewModel.clearErrorMessage() }) {
                    Text("OK")
                }
            }
        )
    }

    // Discard Confirmation Dialog
    discardConfirmFile?.let { file ->
        AlertDialog(
            onDismissRequest = { discardConfirmFile = null },
            title = { Text("Discard Changes?") },
            text = {
                Text(
                    if (file.status == "??") {
                        "Are you sure you want to permanently delete untracked file '${file.path}'?"
                    } else {
                        "Are you sure you want to discard all local changes to '${file.path}'? This cannot be undone."
                    }
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        viewModel.discardFileChanges(file)
                        discardConfirmFile = null
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                ) {
                    Text("Discard")
                }
            },
            dismissButton = {
                TextButton(onClick = { discardConfirmFile = null }) {
                    Text("Cancel")
                }
            }
        )
    }

    if (isWideScreen) {
        Row(modifier = modifier.fillMaxSize()) {
            // Left Panel - File List (40% width)
            Box(modifier = Modifier.weight(0.4f).fillMaxHeight()) {
                FileChangesList(
                    uiState = uiState,
                    selectedFile = selectedFile,
                    onFileSelected = { viewModel.selectFile(it) },
                    onStage = { viewModel.stageFile(it) },
                    onUnstage = { viewModel.unstageFile(it) },
                    onDiscard = { discardConfirmFile = it },
                    onRefresh = { viewModel.refresh() }
                )
            }

            VerticalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.3f))

            // Right Panel - Diff Viewer (60% width)
            Box(modifier = Modifier.weight(0.6f).fillMaxHeight()) {
                DiffViewer(
                    selectedFile = selectedFile,
                    diffText = selectedFileDiff,
                    isLoading = isDiffLoading
                )
            }
        }
    } else {
        // Mobile layout: switch between list and diff view
        if (selectedFile != null) {
            Scaffold(
                topBar = {
                    SmallTopAppBar(
                        title = {
                            Column {
                                Text(
                                    text = selectedFile?.path ?: "",
                                    style = MaterialTheme.typography.titleMedium,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Text(
                                    text = if (selectedFile?.isStaged == true) "Staged Changes" else "Unstaged Changes",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                                )
                            }
                        },
                        navigationIcon = {
                            IconButton(onClick = { viewModel.refresh() }) {
                                Icon(
                                    imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                                    contentDescription = "Back"
                                )
                            }
                        },
                        colors = TopAppBarDefaults.smallTopAppBarColors(
                            containerColor = MaterialTheme.colorScheme.surface
                        )
                    )
                }
            ) { padding ->
                Box(modifier = modifier.fillMaxSize().padding(padding)) {
                    DiffViewer(
                        selectedFile = selectedFile,
                        diffText = selectedFileDiff,
                        isLoading = isDiffLoading
                    )
                }
            }
        } else {
            FileChangesList(
                uiState = uiState,
                selectedFile = null,
                onFileSelected = { viewModel.selectFile(it) },
                onStage = { viewModel.stageFile(it) },
                onUnstage = { viewModel.unstageFile(it) },
                onDiscard = { discardConfirmFile = it },
                onRefresh = { viewModel.refresh() },
                modifier = modifier
            )
        }
    }
}

@Composable
fun StatusBadge(status: String) {
    val isDark = MaterialTheme.colorScheme.background.luminance() < 0.5f
    val (bg, fg, label) = when (status) {
        "A" -> Triple(
            if (isDark) Color(0x2256D364) else Color(0xFFE6FFEC),
            if (isDark) Color(0xFF56D364) else Color(0xFF22863A),
            "A"
        )
        "M" -> Triple(
            if (isDark) Color(0x22E3B341) else Color(0xFFFFF8E1),
            if (isDark) Color(0xFFE3B341) else Color(0xFFB78103),
            "M"
        )
        "D" -> Triple(
            if (isDark) Color(0x22F85149) else Color(0xFFFFEEEE),
            if (isDark) Color(0xFFF85149) else Color(0xFFCB2431),
            "D"
        )
        "??" -> Triple(
            if (isDark) Color(0x228B949E) else Color(0xFFF6F8FA),
            if (isDark) Color(0xFF8B949E) else Color(0xFF57606A),
            "U"
        )
        else -> Triple(
            MaterialTheme.colorScheme.surfaceVariant,
            MaterialTheme.colorScheme.onSurfaceVariant,
            status
        )
    }

    Surface(
        color = bg,
        shape = RoundedCornerShape(4.dp),
        modifier = Modifier.size(24.dp)
    ) {
        Box(contentAlignment = Alignment.Center) {
            Text(
                text = label,
                color = fg,
                style = MaterialTheme.typography.bodySmall.copy(
                    fontWeight = FontWeight.Bold,
                    fontFamily = FontFamily.Monospace
                )
            )
        }
    }
}

@Composable
fun FileChangesList(
    uiState: ReviewUiState,
    selectedFile: GitFileStatus?,
    onFileSelected: (GitFileStatus) -> Unit,
    onStage: (GitFileStatus) -> Unit,
    onUnstage: (GitFileStatus) -> Unit,
    onDiscard: (GitFileStatus) -> Unit,
    onRefresh: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(modifier = modifier.fillMaxSize()) {
        Surface(
            tonalElevation = 8.dp,
            modifier = Modifier.fillMaxWidth()
        ) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Git Changes",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.weight(1f)
                )
                IconButton(onClick = onRefresh) {
                    Icon(imageVector = Icons.Default.Refresh, contentDescription = "Refresh")
                }
            }
        }

        when (uiState) {
            is ReviewUiState.Loading -> {
                Box(modifier = Modifier.fillMaxSize().weight(1f), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
            }
            is ReviewUiState.Error -> {
                Box(modifier = Modifier.fillMaxSize().weight(1f).padding(16.dp), contentAlignment = Alignment.Center) {
                    Text(text = uiState.message, color = MaterialTheme.colorScheme.error)
                }
            }
            is ReviewUiState.Success -> {
                if (uiState.stagedFiles.isEmpty() && uiState.unstagedFiles.isEmpty()) {
                    Box(
                        modifier = Modifier.fillMaxSize().weight(1f).padding(32.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.CheckCircle,
                                contentDescription = "Clean",
                                tint = Color(0xFF2EA043),
                                modifier = Modifier.size(48.dp)
                            )
                            Text(
                                "Working Tree Clean",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                "No staged or unstaged changes found.",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                            )
                        }
                    }
                } else {
                    LazyColumn(
                        modifier = Modifier.fillMaxSize().weight(1f),
                        contentPadding = PaddingValues(vertical = 8.dp)
                    ) {
                        if (uiState.stagedFiles.isNotEmpty()) {
                            item {
                                SectionHeader(title = "Staged Changes (${uiState.stagedFiles.size})")
                            }
                            items(uiState.stagedFiles) { file ->
                                FileItem(
                                    file = file,
                                    isSelected = selectedFile == file,
                                    onClick = { onFileSelected(file) },
                                    onAction = { onUnstage(file) },
                                    onDiscard = { onDiscard(file) }
                                )
                            }
                        }

                        if (uiState.unstagedFiles.isNotEmpty()) {
                            item {
                                SectionHeader(title = "Unstaged Changes (${uiState.unstagedFiles.size})")
                            }
                            items(uiState.unstagedFiles) { file ->
                                FileItem(
                                    file = file,
                                    isSelected = selectedFile == file,
                                    onClick = { onFileSelected(file) },
                                    onAction = { onStage(file) },
                                    onDiscard = { onDiscard(file) }
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun SectionHeader(title: String) {
    Text(
        text = title,
        style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold),
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)
    )
}

@Composable
fun FileItem(
    file: GitFileStatus,
    isSelected: Boolean,
    onClick: () -> Unit,
    onAction: () -> Unit,
    onDiscard: () -> Unit
) {
    val bg = if (isSelected) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.4f) else Color.Transparent
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(bg)
            .clickable { onClick() }
            .padding(vertical = 4.dp, horizontal = 16.dp)
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth()
        ) {
            StatusBadge(status = file.status)
            Spacer(modifier = Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = file.path.substringAfterLast('/'),
                    style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.SemiBold),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                if (file.path.contains('/')) {
                    Text(
                        text = file.path.substringBeforeLast('/'),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
            IconButton(onClick = onAction) {
                Icon(
                    imageVector = if (file.isStaged) Icons.Default.RemoveCircleOutline else Icons.Default.AddCircleOutline,
                    contentDescription = if (file.isStaged) "Unstage" else "Stage",
                    tint = if (file.isStaged) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary
                )
            }
            IconButton(onClick = onDiscard) {
                Icon(
                    imageVector = Icons.Default.Restore,
                    contentDescription = "Discard Changes",
                    tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                )
            }
        }
        HorizontalDivider(thickness = 0.5.dp, color = MaterialTheme.colorScheme.outline.copy(alpha = 0.15f))
    }
}

@Composable
fun DiffViewer(
    selectedFile: GitFileStatus?,
    diffText: String?,
    isLoading: Boolean
) {
    if (selectedFile == null) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text(
                "Select a file to view its diff",
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
            )
        }
        return
    }

    Column(modifier = Modifier.fillMaxSize()) {
        Box(modifier = Modifier.fillMaxSize().weight(1f)) {
            if (isLoading) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
            } else if (diffText == null) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text("No diff details loaded.")
                }
            } else if (diffText.trim().isEmpty()) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text("No changes detected in file.")
                }
            } else {
                val scrollState = rememberScrollState()
                val horizScrollState = rememberScrollState()
                val isDark = MaterialTheme.colorScheme.background.luminance() < 0.5f
                val fallbackColor = MaterialTheme.colorScheme.onSurface

                val parsedLines = remember(diffText) {
                    var currentOldLine = 0
                    var currentNewLine = 0
                    diffText.split("\n").map { line ->
                        when {
                            line.startsWith("@@ ") -> {
                                val match = Regex("""@@ -(\d+)(?:,\d+)? \+(\d+)(?:,\d+)? @@""").find(line)
                                if (match != null) {
                                    currentOldLine = match.groupValues[1].toInt()
                                    currentNewLine = match.groupValues[2].toInt()
                                }
                                ParsedDiffLine(line, "", "", DiffLineType.HUNK_HEADER)
                            }
                            line.startsWith("+") && !line.startsWith("+++") -> {
                                val lineNum = currentNewLine.toString()
                                currentNewLine++
                                ParsedDiffLine(line, "", lineNum, DiffLineType.ADDITION)
                            }
                            line.startsWith("-") && !line.startsWith("---") -> {
                                val lineNum = currentOldLine.toString()
                                currentOldLine++
                                ParsedDiffLine(line, lineNum, "", DiffLineType.DELETION)
                            }
                            line.startsWith(" ") -> {
                                val oldNum = currentOldLine.toString()
                                val newNum = currentNewLine.toString()
                                currentOldLine++
                                currentNewLine++
                                ParsedDiffLine(line, oldNum, newNum, DiffLineType.CONTEXT)
                            }
                            line.startsWith("diff --git") || line.startsWith("index ") || line.startsWith("--- ") || line.startsWith("+++ ") -> {
                                ParsedDiffLine(line, "", "", DiffLineType.METADATA)
                            }
                            else -> {
                                if (currentOldLine > 0) {
                                    val oldNum = currentOldLine.toString()
                                    val newNum = currentNewLine.toString()
                                    currentOldLine++
                                    currentNewLine++
                                    ParsedDiffLine(line, oldNum, newNum, DiffLineType.CONTEXT)
                                } else {
                                    ParsedDiffLine(line, "", "", DiffLineType.METADATA)
                                }
                            }
                        }
                    }
                }

                val maxLineNum = remember(parsedLines) {
                    parsedLines.maxOfOrNull { 
                        maxOf(it.oldLineNum.toIntOrNull() ?: 0, it.newLineNum.toIntOrNull() ?: 0) 
                    } ?: 0
                }
                val digitCount = remember(maxLineNum) {
                    maxLineNum.toString().length.coerceAtLeast(1)
                }
                val numWidth = remember(digitCount) { (digitCount * 7 + 4).dp }
                val columnWidth = remember(numWidth) { numWidth * 2 + 8.dp }

                Row(
                    modifier = Modifier
                        .fillMaxSize()
                        .verticalScroll(scrollState)
                        .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.1f))
                ) {
                    // 1. Line Numbers Column (Fixed on the Left)
                    Column(
                        modifier = Modifier
                            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f))
                            .width(columnWidth)
                    ) {
                        parsedLines.forEach { parsedLine ->
                            val (bgColor, _) = getColors(parsedLine.type, isDark, fallbackColor)
                            
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(20.dp)
                                    .background(bgColor)
                                    .padding(horizontal = 4.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.End
                            ) {
                                Text(
                                    text = parsedLine.oldLineNum,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f),
                                    fontSize = 10.sp,
                                    fontFamily = FontFamily.Monospace,
                                    modifier = Modifier.width(numWidth),
                                    textAlign = androidx.compose.ui.text.style.TextAlign.End
                                )
                                Spacer(modifier = Modifier.width(4.dp))
                                Text(
                                    text = parsedLine.newLineNum,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f),
                                    fontSize = 10.sp,
                                    fontFamily = FontFamily.Monospace,
                                    modifier = Modifier.width(numWidth),
                                    textAlign = androidx.compose.ui.text.style.TextAlign.End
                                )
                            }
                        }
                    }

                    // Vertical Divider
                    Box(
                        modifier = Modifier
                            .width(1.dp)
                            .height((parsedLines.size * 20).dp)
                            .background(MaterialTheme.colorScheme.outline.copy(alpha = 0.2f))
                    )

                    // 2. Code Lines Column (Horizontally Scrollable)
                    Column(
                        modifier = Modifier
                            .weight(1f)
                            .horizontalScroll(horizScrollState)
                    ) {
                        parsedLines.forEach { parsedLine ->
                            val (bgColor, textColor) = getColors(parsedLine.type, isDark, fallbackColor)
                            
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(20.dp)
                                    .background(bgColor)
                                    .padding(horizontal = 8.dp),
                                contentAlignment = Alignment.CenterStart
                            ) {
                                Text(
                                    text = parsedLine.text,
                                    color = textColor,
                                    fontSize = 12.sp,
                                    fontFamily = FontFamily.Monospace,
                                    softWrap = false
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

data class ParsedDiffLine(
    val text: String,
    val oldLineNum: String,
    val newLineNum: String,
    val type: DiffLineType
)

enum class DiffLineType {
    METADATA,
    HUNK_HEADER,
    CONTEXT,
    ADDITION,
    DELETION
}

private fun getColors(type: DiffLineType, isDark: Boolean, fallbackColor: Color): Pair<Color, Color> {
    return when (type) {
        DiffLineType.ADDITION -> {
            if (isDark) {
                Color(0x222EA043) to Color(0xFF56D364)
            } else {
                Color(0xFFE6FFEC) to Color(0xFF22863A)
            }
        }
        DiffLineType.DELETION -> {
            if (isDark) {
                Color(0x22F85149) to Color(0xFFF85149)
            } else {
                Color(0xFFFFEEEE) to Color(0xFFCB2431)
            }
        }
        DiffLineType.HUNK_HEADER -> {
            if (isDark) {
                Color(0x15388BFD) to Color(0xFF79C0FF)
            } else {
                Color(0xFFF1F8FF) to Color(0xFF032F62)
            }
        }
        DiffLineType.METADATA -> {
            Color.Transparent to fallbackColor.copy(alpha = 0.5f)
        }
        DiffLineType.CONTEXT -> {
            Color.Transparent to fallbackColor.copy(alpha = 0.85f)
        }
    }
}
