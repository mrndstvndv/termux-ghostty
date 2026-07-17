package com.mrndtvndv.term.ui.sftp

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.hossain.highlight.ui.SyntaxHighlightedCode
import dev.hossain.highlight.ui.HighlightThemeProvider
import java.io.File

private fun getLanguageFromExtension(ext: String): String {
    return when (ext.lowercase()) {
        "kt", "kts" -> "kotlin"
        "java" -> "java"
        "py" -> "python"
        "js" -> "javascript"
        "ts" -> "typescript"
        "rs" -> "rust"
        "c", "cpp", "h", "hpp" -> "cpp"
        "sh", "bash" -> "bash"
        "html" -> "xml"
        "xml" -> "xml"
        "json" -> "json"
        "md" -> "markdown"
        "yml", "yaml" -> "yaml"
        "gradle" -> "gradle"
        "sql" -> "sql"
        "css" -> "css"
        else -> ext.lowercase()
    }
}

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
                    HighlightThemeProvider {
                        SyntaxHighlightedCode(
                            code = content!!,
                            language = getLanguageFromExtension(file.extension),
                            showLineNumbers = true,
                            modifier = Modifier.fillMaxSize()
                        )
                    }
                }
                else -> {
                    CircularProgressIndicator(modifier = Modifier.padding(16.dp))
                }
            }
        }
    }
}
