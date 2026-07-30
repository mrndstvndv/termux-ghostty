package com.mrndtvndv.term.ui.review

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.gestures.rememberTransformableState
import androidx.compose.foundation.gestures.transformable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
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
import androidx.compose.ui.text.withStyle

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
    val isFullFileMode by viewModel.isFullFileMode.collectAsState()
    val errorMessage by viewModel.errorMessage.collectAsState()
    val isCommitInProgress by viewModel.isCommitInProgress.collectAsState()

    val configuration = LocalConfiguration.current
    val isWideScreen = configuration.screenWidthDp >= 600

    var discardConfirmFile by remember { mutableStateOf<GitFileStatus?>(null) }
    var showCommitDialog by remember { mutableStateOf(false) }
    var commitMessage by remember { mutableStateOf("") }

    val stagedFileCount = (uiState as? ReviewUiState.Success)?.stagedFiles?.size ?: 0

    if (showCommitDialog) {
        AlertDialog(
            onDismissRequest = { if (!isCommitInProgress) showCommitDialog = false },
            title = { Text("Commit Changes") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("Commit $stagedFileCount staged file${if (stagedFileCount == 1) "" else "s"}.")
                    OutlinedTextField(
                        value = commitMessage,
                        onValueChange = { commitMessage = it },
                        label = { Text("Commit message") },
                        placeholder = { Text("Describe your changes") },
                        minLines = 2,
                        maxLines = 5,
                        enabled = !isCommitInProgress,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        viewModel.commit(commitMessage)
                        showCommitDialog = false
                        commitMessage = ""
                    },
                    enabled = commitMessage.isNotBlank() && !isCommitInProgress
                ) {
                    if (isCommitInProgress) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(18.dp),
                            strokeWidth = 2.dp
                        )
                    } else {
                        Text("Commit")
                    }
                }
            },
            dismissButton = {
                TextButton(
                    onClick = { showCommitDialog = false },
                    enabled = !isCommitInProgress
                ) {
                    Text("Cancel")
                }
            }
        )
    }

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
                    onCommit = { showCommitDialog = true },
                    isCommitInProgress = isCommitInProgress,
                    onRefresh = { viewModel.refresh() }
                )
            }

            VerticalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.3f))

            // Right Panel - Diff Viewer (60% width)
            Box(modifier = Modifier.weight(0.6f).fillMaxHeight()) {
                DiffViewer(
                    selectedFile = selectedFile,
                    diffText = selectedFileDiff,
                    isLoading = isDiffLoading,
                    isFullFileMode = isFullFileMode,
                    onToggleFullFileMode = { viewModel.toggleFullFileMode() },
                    showHeader = true
                )
            }
        }
    } else {
        // Mobile layout: switch between list and diff view
        if (selectedFile != null) {
            Scaffold(
                topBar = {
                    TopAppBar(
                        title = {
                            Column {
                                Text(
                                    text = selectedFile?.path ?: "",
                                    style = MaterialTheme.typography.titleMedium,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Text(
                                    text = if (selectedFile?.isStaged == true) {
                                        "Staged Changes"
                                    } else {
                                        "Unstaged Changes"
                                    },
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                                )
                            }
                        },
                        navigationIcon = {
                            IconButton(onClick = { viewModel.deselectFile() }) {
                                Icon(
                                    imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                                    contentDescription = "Back"
                                )
                            }
                        },
                        actions = {
                            FilterChip(
                                selected = isFullFileMode,
                                onClick = { viewModel.toggleFullFileMode() },
                                label = { Text(if (isFullFileMode) "Full File" else "Diff Only") },
                                leadingIcon = {
                                    Icon(
                                        imageVector = if (isFullFileMode) {
                                            Icons.Default.Visibility
                                        } else {
                                            Icons.Default.UnfoldMore
                                        },
                                        contentDescription = "Toggle full file mode",
                                        modifier = Modifier.size(16.dp)
                                    )
                                },
                                modifier = Modifier.padding(end = 8.dp)
                            )
                        },
                        colors = TopAppBarDefaults.topAppBarColors(
                            containerColor = MaterialTheme.colorScheme.surface
                        )
                    )
                }
            ) { padding ->
                Box(modifier = modifier.fillMaxSize().padding(padding)) {
                    DiffViewer(
                        selectedFile = selectedFile,
                        diffText = selectedFileDiff,
                        isLoading = isDiffLoading,
                        isFullFileMode = isFullFileMode,
                        onToggleFullFileMode = { viewModel.toggleFullFileMode() },
                        showHeader = false
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
                onCommit = { showCommitDialog = true },
                isCommitInProgress = isCommitInProgress,
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

private enum class TokenType {
    COMMENT, STRING, KEYWORD, NUMBER, ANNOTATION, TYPE
}

private data class TokenRule(val type: TokenType, val regex: Regex)

private data class Match(val type: TokenType, val range: IntRange)

private val syntaxRules = listOf(
    // Comments (single line and multi line)
    TokenRule(TokenType.COMMENT, Regex("//.*|/\\*[\\s\\S]*?\\*/|#.*")),
    // Strings
    TokenRule(TokenType.STRING, Regex("\"[^\"\\\\]*(?:\\\\.[^\"\\\\]*)*\"|'[^'\\\\]*(?:\\\\.[^'\\\\]*)*'|`[^`\\\\]*(?:\\\\.[^`\\\\]*)*`")),
    // Keywords
    TokenRule(TokenType.KEYWORD, Regex("\\b(val|var|fun|class|interface|object|import|package|return|if|else|for|while|do|when|is|as|in|out|try|catch|finally|throw|this|super|new|private|protected|public|internal|lateinit|init|companion|const|null|true|false|void|int|double|float|long|short|byte|char|boolean|string|def|elif|from|lambda|pass|global|nonlocal|async|await|let|const|var|function|export|default|extends|implements|struct|enum|fn|mut|impl|use|pub|sizeof|typeof)\\b")),
    // Numbers (decimal and hex)
    TokenRule(TokenType.NUMBER, Regex("\\b(0x[0-9a-fA-F]+|\\d+(\\.\\d+)?)\\b")),
    // Annotations
    TokenRule(TokenType.ANNOTATION, Regex("@[A-Za-z0-9_]+")),
    // Types (Capitalized words)
    TokenRule(TokenType.TYPE, Regex("\\b[A-Z][A-Za-z0-9_]*\\b"))
)

private fun highlightCode(code: String, isDark: Boolean): androidx.compose.ui.text.AnnotatedString {
    val matches = mutableListOf<Match>()
    for (rule in syntaxRules) {
        rule.regex.findAll(code).forEach { result ->
            matches.add(Match(rule.type, result.range))
        }
    }
    
    // Sort matches: first by start index ascending, then by length descending
    matches.sortWith(compareBy<Match> { it.range.first }.thenByDescending { it.range.last - it.range.first })
    
    val nonOverlapping = mutableListOf<Match>()
    var lastEnd = -1
    for (match in matches) {
        if (match.range.first > lastEnd) {
            nonOverlapping.add(match)
            lastEnd = match.range.last
        }
    }
    
    val builder = androidx.compose.ui.text.AnnotatedString.Builder(code)
    for (match in nonOverlapping) {
        val style = if (isDark) {
            when (match.type) {
                TokenType.COMMENT -> androidx.compose.ui.text.SpanStyle(color = Color(0xFF808080), fontStyle = androidx.compose.ui.text.font.FontStyle.Italic)
                TokenType.STRING -> androidx.compose.ui.text.SpanStyle(color = Color(0xFF6A8759))
                TokenType.KEYWORD -> androidx.compose.ui.text.SpanStyle(color = Color(0xFFCC7832), fontWeight = FontWeight.Bold)
                TokenType.NUMBER -> androidx.compose.ui.text.SpanStyle(color = Color(0xFF6897BB))
                TokenType.ANNOTATION -> androidx.compose.ui.text.SpanStyle(color = Color(0xFFBBB529))
                TokenType.TYPE -> androidx.compose.ui.text.SpanStyle(color = Color(0xFF287BDE))
            }
        } else {
            when (match.type) {
                TokenType.COMMENT -> androidx.compose.ui.text.SpanStyle(color = Color(0xFF8C8C8C), fontStyle = androidx.compose.ui.text.font.FontStyle.Italic)
                TokenType.STRING -> androidx.compose.ui.text.SpanStyle(color = Color(0xFF067D17))
                TokenType.KEYWORD -> androidx.compose.ui.text.SpanStyle(color = Color(0xFF0033B0), fontWeight = FontWeight.Bold)
                TokenType.NUMBER -> androidx.compose.ui.text.SpanStyle(color = Color(0xFF1750EB))
                TokenType.ANNOTATION -> androidx.compose.ui.text.SpanStyle(color = Color(0xFF9E880D))
                TokenType.TYPE -> androidx.compose.ui.text.SpanStyle(color = Color(0xFF007F7F))
            }
        }
        builder.addStyle(style, match.range.first, match.range.last + 1)
    }
    return builder.toAnnotatedString()
}

@Suppress("LongParameterList", "LongMethod")
@Composable
fun FileChangesList(
    uiState: ReviewUiState,
    selectedFile: GitFileStatus?,
    onFileSelected: (GitFileStatus) -> Unit,
    onStage: (GitFileStatus) -> Unit,
    onUnstage: (GitFileStatus) -> Unit,
    onDiscard: (GitFileStatus) -> Unit,
    onCommit: () -> Unit,
    isCommitInProgress: Boolean,
    onRefresh: () -> Unit,
    modifier: Modifier = Modifier
) {
    val stagedFiles = (uiState as? ReviewUiState.Success)?.stagedFiles.orEmpty()

    Scaffold(
        modifier = modifier.fillMaxSize(),
        floatingActionButton = {
            if (stagedFiles.isNotEmpty()) {
                FloatingActionButton(
                    onClick = { if (!isCommitInProgress) onCommit() },
                ) {
                    if (isCommitInProgress) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(24.dp),
                            strokeWidth = 2.dp
                        )
                    } else {
                        Icon(
                            imageVector = Icons.Default.Commit,
                            contentDescription = "Commit staged changes"
                        )
                    }
                }
            }
        }
    ) { contentPadding ->
        PullToRefreshBox(
            isRefreshing = uiState is ReviewUiState.Loading,
            onRefresh = onRefresh,
            modifier = Modifier.fillMaxSize().padding(contentPadding)
        ) {
            when (uiState) {
                is ReviewUiState.Loading -> {
                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator()
                    }
                }
                is ReviewUiState.Error -> {
                    Box(
                        modifier = Modifier.fillMaxSize().padding(16.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(text = uiState.message, color = MaterialTheme.colorScheme.error)
                    }
                }
                is ReviewUiState.Success -> {
                    LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(top = 8.dp, bottom = 88.dp)
                    ) {
                        if (uiState.stagedFiles.isEmpty() && uiState.unstagedFiles.isEmpty()) {
                            item {
                                Box(
                                    modifier = Modifier.fillParentMaxSize().padding(32.dp),
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
                            }
                        } else {
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
fun DiffHeader(
    selectedFile: GitFileStatus,
    isFullFileMode: Boolean,
    onToggleFullFileMode: () -> Unit
) {
    Surface(
        tonalElevation = 2.dp,
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = selectedFile.path,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = if (selectedFile.isStaged) "Staged Changes" else "Unstaged Changes",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                )
            }
            Spacer(modifier = Modifier.width(12.dp))
            FilterChip(
                selected = isFullFileMode,
                onClick = onToggleFullFileMode,
                label = { Text(if (isFullFileMode) "Full File" else "Diff Only") },
                leadingIcon = {
                    Icon(
                        imageVector = if (isFullFileMode) {
                            Icons.Default.Visibility
                        } else {
                            Icons.Default.UnfoldMore
                        },
                        contentDescription = "Toggle full file mode",
                        modifier = Modifier.size(16.dp)
                    )
                }
            )
        }
    }
    HorizontalDivider(thickness = 0.5.dp, color = MaterialTheme.colorScheme.outline.copy(alpha = 0.2f))
}

@Composable
fun DiffViewer(
    selectedFile: GitFileStatus?,
    diffText: String?,
    isLoading: Boolean,
    isFullFileMode: Boolean = false,
    onToggleFullFileMode: () -> Unit = {},
    showHeader: Boolean = false
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
        if (showHeader) {
            DiffHeader(
                selectedFile = selectedFile,
                isFullFileMode = isFullFileMode,
                onToggleFullFileMode = onToggleFullFileMode
            )
        }

        Box(modifier = Modifier.fillMaxSize().weight(1f)) {
            when {
                isLoading -> {
                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator()
                    }
                }
                diffText == null -> {
                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text("No diff details loaded.")
                    }
                }
                diffText.trim().isEmpty() -> {
                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text("No changes detected in file.")
                    }
                }
                else -> {
                    DiffContent(diffText = diffText)
                }
            }
        }
    }
}

private fun parseDiffLines(diffText: String): List<ParsedDiffLine> {
    var currentOldLine = 0
    var currentNewLine = 0
    return diffText.split("\n").map { line ->
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
            line.startsWith("diff --git") || line.startsWith("index ") ||
                line.startsWith("--- ") || line.startsWith("+++ ") ||
                line.startsWith("\\ ") -> {
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

@Composable
fun DiffContent(
    diffText: String,
    modifier: Modifier = Modifier
) {
    var fontScale by remember { mutableFloatStateOf(1f) }
    val transformState = rememberTransformableState { zoomChange, _, _ ->
        fontScale = (fontScale * zoomChange).coerceIn(0.6f, 3.0f)
    }

    val scrollState = rememberScrollState()
    val horizScrollState = rememberScrollState()
    val isDark = MaterialTheme.colorScheme.background.luminance() < 0.5f
    val fallbackColor = MaterialTheme.colorScheme.onSurface

    val parsedLines = remember(diffText) { parseDiffLines(diffText) }

    val maxLineNum = remember(parsedLines) {
        parsedLines.maxOfOrNull {
            maxOf(it.oldLineNum.toIntOrNull() ?: 0, it.newLineNum.toIntOrNull() ?: 0)
        } ?: 0
    }
    val digitCount = maxLineNum.toString().length.coerceAtLeast(1)
    val dims = remember(digitCount, fontScale) {
        val numW = ((digitCount * 7 + 4) * fontScale).dp
        ScaledDiffDimensions(
            numWidth = numW,
            columnWidth = numW * 2 + (8 * fontScale).dp,
            lineHeight = (20 * fontScale).dp,
            codeFontSize = (12 * fontScale).sp,
            lineNumFontSize = (10 * fontScale).sp
        )
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .transformable(state = transformState)
    ) {
        DiffRowsLayout(
            parsedLines = parsedLines,
            dims = dims,
            scrollState = scrollState,
            horizScrollState = horizScrollState,
            isDark = isDark,
            fallbackColor = fallbackColor
        )

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

@Composable
private fun DiffRowsLayout(
    parsedLines: List<ParsedDiffLine>,
    dims: ScaledDiffDimensions,
    scrollState: androidx.compose.foundation.ScrollState,
    horizScrollState: androidx.compose.foundation.ScrollState,
    isDark: Boolean,
    fallbackColor: Color
) {
    Row(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(scrollState)
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.1f))
    ) {
        LineNumberColumn(
            parsedLines = parsedLines,
            numWidth = dims.numWidth,
            columnWidth = dims.columnWidth,
            lineHeight = dims.lineHeight,
            fontSize = dims.lineNumFontSize,
            isDark = isDark,
            fallbackColor = fallbackColor
        )

        Box(
            modifier = Modifier
                .width(1.dp)
                .height((parsedLines.size * dims.lineHeight.value).dp)
                .background(MaterialTheme.colorScheme.outline.copy(alpha = 0.2f))
        )

        CodeLinesColumn(
            parsedLines = parsedLines,
            horizScrollState = horizScrollState,
            lineHeight = dims.lineHeight,
            fontSize = dims.codeFontSize,
            isDark = isDark,
            fallbackColor = fallbackColor
        )
    }
}

private data class ScaledDiffDimensions(
    val numWidth: androidx.compose.ui.unit.Dp,
    val columnWidth: androidx.compose.ui.unit.Dp,
    val lineHeight: androidx.compose.ui.unit.Dp,
    val codeFontSize: androidx.compose.ui.unit.TextUnit,
    val lineNumFontSize: androidx.compose.ui.unit.TextUnit
)

@Composable
private fun LineNumberColumn(
    parsedLines: List<ParsedDiffLine>,
    numWidth: androidx.compose.ui.unit.Dp,
    columnWidth: androidx.compose.ui.unit.Dp,
    lineHeight: androidx.compose.ui.unit.Dp,
    fontSize: androidx.compose.ui.unit.TextUnit,
    isDark: Boolean,
    fallbackColor: Color
) {
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
                    .height(lineHeight)
                    .background(bgColor)
                    .padding(horizontal = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.End
            ) {
                Text(
                    text = parsedLine.oldLineNum,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f),
                    fontSize = fontSize,
                    fontFamily = FontFamily.Monospace,
                    modifier = Modifier.width(numWidth),
                    textAlign = androidx.compose.ui.text.style.TextAlign.End
                )
                Spacer(modifier = Modifier.width(4.dp))
                Text(
                    text = parsedLine.newLineNum,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f),
                    fontSize = fontSize,
                    fontFamily = FontFamily.Monospace,
                    modifier = Modifier.width(numWidth),
                    textAlign = androidx.compose.ui.text.style.TextAlign.End
                )
            }
        }
    }
}

@Composable
private fun RowScope.CodeLinesColumn(
    parsedLines: List<ParsedDiffLine>,
    horizScrollState: androidx.compose.foundation.ScrollState,
    lineHeight: androidx.compose.ui.unit.Dp,
    fontSize: androidx.compose.ui.unit.TextUnit,
    isDark: Boolean,
    fallbackColor: Color
) {
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
                    .height(lineHeight)
                    .background(bgColor)
                    .padding(horizontal = 8.dp),
                contentAlignment = Alignment.CenterStart
            ) {
                val annotatedText = remember(parsedLine.text, parsedLine.type, isDark, textColor) {
                    val type = parsedLine.type
                    if (type == DiffLineType.ADDITION || type == DiffLineType.DELETION ||
                        type == DiffLineType.CONTEXT
                    ) {
                        val prefix = parsedLine.text.take(1)
                        val remainingText = parsedLine.text.drop(1)

                        androidx.compose.ui.text.buildAnnotatedString {
                            withStyle(androidx.compose.ui.text.SpanStyle(color = textColor)) {
                                append(prefix)
                            }
                            append(highlightCode(remainingText, isDark))
                        }
                    } else {
                        androidx.compose.ui.text.buildAnnotatedString {
                            withStyle(androidx.compose.ui.text.SpanStyle(color = textColor)) {
                                append(parsedLine.text)
                            }
                        }
                    }
                }

                Text(
                    text = annotatedText,
                    fontSize = fontSize,
                    fontFamily = FontFamily.Monospace,
                    softWrap = false
                )
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
