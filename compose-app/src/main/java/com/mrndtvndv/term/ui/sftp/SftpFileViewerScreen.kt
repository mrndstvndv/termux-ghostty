package com.mrndtvndv.term.ui.sftp
 
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.horizontalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

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

private fun highlightCode(code: String, isDark: Boolean): AnnotatedString {
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
    
    val builder = AnnotatedString.Builder(code)
    for (match in nonOverlapping) {
        val style = if (isDark) {
            when (match.type) {
                TokenType.COMMENT -> SpanStyle(color = Color(0xFF808080), fontStyle = FontStyle.Italic)
                TokenType.STRING -> SpanStyle(color = Color(0xFF6A8759))
                TokenType.KEYWORD -> SpanStyle(color = Color(0xFFCC7832), fontWeight = FontWeight.Bold)
                TokenType.NUMBER -> SpanStyle(color = Color(0xFF6897BB))
                TokenType.ANNOTATION -> SpanStyle(color = Color(0xFFBBB529))
                TokenType.TYPE -> SpanStyle(color = Color(0xFF287BDE))
            }
        } else {
            when (match.type) {
                TokenType.COMMENT -> SpanStyle(color = Color(0xFF8C8C8C), fontStyle = FontStyle.Italic)
                TokenType.STRING -> SpanStyle(color = Color(0xFF067D17))
                TokenType.KEYWORD -> SpanStyle(color = Color(0xFF0033B0), fontWeight = FontWeight.Bold)
                TokenType.NUMBER -> SpanStyle(color = Color(0xFF1750EB))
                TokenType.ANNOTATION -> SpanStyle(color = Color(0xFF9E880D))
                TokenType.TYPE -> SpanStyle(color = Color(0xFF007F7F))
            }
        }
        builder.addStyle(style, match.range.first, match.range.last + 1)
    }
    return builder.toAnnotatedString()
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SftpFileViewerScreen(file: File, onClose: () -> Unit) {
    var content by remember { mutableStateOf<AnnotatedString?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    val isDark = MaterialTheme.colorScheme.background.luminance() < 0.5

    LaunchedEffect(file, isDark) {
        try {
            val rawText = file.readText()
            val highlighted = withContext(Dispatchers.Default) {
                highlightCode(rawText, isDark)
            }
            content = highlighted
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
                    Text(
                        text = content!!,
                        fontFamily = FontFamily.Monospace,
                        modifier = Modifier
                            .fillMaxSize()
                            .verticalScroll(rememberScrollState())
                            .horizontalScroll(rememberScrollState())
                            .padding(16.dp)
                    )
                }
                else -> {
                    CircularProgressIndicator(modifier = Modifier.padding(16.dp))
                }
            }
        }
    }
}
