package com.mrndtvndv.term.ui.sftp

import android.annotation.SuppressLint
import android.graphics.Color
import android.webkit.JavascriptInterface
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.UriHandler
import androidx.compose.ui.platform.rememberNestedScrollInteropConnection
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.webkit.WebViewAssetLoader
import com.mikepenz.markdown.compose.components.markdownComponents
import com.mikepenz.markdown.compose.elements.MarkdownCodeFence
import com.mikepenz.markdown.compose.elements.MarkdownHighlightedCode
import com.mikepenz.markdown.m3.Markdown
import com.mikepenz.markdown.m3.markdownTypography
import com.mikepenz.markdown.model.MarkdownTypography
import com.mikepenz.markdown.model.markdownPadding
import com.mrndtvndv.term.ui.theme.codeFontFamily
import dev.snipme.highlights.Highlights
import dev.snipme.highlights.model.SyntaxThemes
import org.json.JSONObject

private const val AssetHostUrl = "https://appassets.androidplatform.net/"
private const val MermaidLanguage = "mermaid"
private const val MermaidInitialHeight = 160

@Composable
internal fun SftpMarkdownViewer(markdown: String) {
    val isDark = MaterialTheme.colorScheme.background.luminance() < 0.5f
    val systemUriHandler = LocalUriHandler.current
    val webLinksOnly = remember(systemUriHandler) {
        object : UriHandler {
            override fun openUri(uri: String) {
                if (uri.startsWith("http://") || uri.startsWith("https://")) systemUriHandler.openUri(uri)
            }
        }
    }
    val highlights = remember(isDark) { Highlights.Builder().theme(SyntaxThemes.default(darkMode = isDark)) }
    val components = remember(highlights, isDark) {
        markdownComponents(
            codeFence = { model ->
                MarkdownCodeFence(model.content, model.node, model.typography.code) { code, language, style ->
                    if (language == MermaidLanguage) {
                        MermaidDiagram(code = code, isDark = isDark)
                    } else {
                        MarkdownHighlightedCode(
                            code = code,
                            language = language,
                            style = style,
                            highlightsBuilder = highlights,
                        )
                    }
                }
            },
            table = { SftpMarkdownTable(it.content, it.node, it.typography.table) },
        )
    }

    CompositionLocalProvider(LocalUriHandler provides webLinksOnly) {
        SelectionContainer {
            Markdown(
                content = markdown,
                components = components,
                typography = documentTypography(),
                padding = markdownPadding(
                    block = 8.dp,
                    listItemTop = 2.dp,
                    listItemBottom = 2.dp,
                    codeBlock = PaddingValues(12.dp),
                ),
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .navigationBarsPadding()
                    .padding(16.dp),
            )
        }
    }
}

@Composable
private fun documentTypography(): MarkdownTypography {
    val body = MaterialTheme.typography.bodyMedium.copy(fontSize = 15.sp, lineHeight = 24.sp)
    val code = body.copy(fontFamily = codeFontFamily(), fontSize = 13.sp, lineHeight = 20.sp)
    fun heading(size: Int) = body.copy(fontSize = size.sp, lineHeight = (size * 1.3f).sp, fontWeight = FontWeight.Bold)
    return markdownTypography(
        h1 = heading(26),
        h2 = heading(22),
        h3 = heading(19),
        h4 = heading(17),
        h5 = heading(16),
        h6 = heading(15),
        text = body,
        paragraph = body,
        ordered = body,
        bullet = body,
        list = body,
        table = body,
        quote = body.copy(fontStyle = FontStyle.Italic),
        code = code,
        inlineCode = code,
    )
}

private class MermaidBridge(private val report: (Int) -> Unit) {
    @JavascriptInterface
    fun onHeight(cssPixels: Int) = report(cssPixels)
}

@SuppressLint("SetJavaScriptEnabled", "JavascriptInterface")
@Composable
private fun MermaidDiagram(code: String, isDark: Boolean) {
    val context = LocalContext.current
    var heightDp by remember { mutableIntStateOf(MermaidInitialHeight) }
    val html = remember(code, isDark) { mermaidHtml(code, isDark) }
    val assetLoader = remember {
        WebViewAssetLoader.Builder()
            .addPathHandler("/assets/", WebViewAssetLoader.AssetsPathHandler(context))
            .build()
    }

    Box(modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
        AndroidView(
            modifier = Modifier
                .fillMaxWidth()
                .height(heightDp.dp)
                .nestedScroll(rememberNestedScrollInteropConnection()),
            factory = { ctx ->
                WebView(ctx).apply {
                    setBackgroundColor(Color.TRANSPARENT)
                    overScrollMode = WebView.OVER_SCROLL_NEVER
                    settings.javaScriptEnabled = true
                    settings.allowFileAccess = false
                    settings.blockNetworkLoads = true
                    addJavascriptInterface(
                        MermaidBridge { height -> post { heightDp = height } },
                        "Android",
                    )
                    webViewClient = object : WebViewClient() {
                        override fun shouldInterceptRequest(
                            view: WebView,
                            request: WebResourceRequest,
                        ): WebResourceResponse? = assetLoader.shouldInterceptRequest(request.url)

                        override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest) = true
                    }
                }
            },
            update = { webView ->
                if (webView.tag != html) {
                    webView.tag = html
                    webView.loadDataWithBaseURL(AssetHostUrl, html, "text/html", "utf-8", null)
                }
            },
            onRelease = { it.destroy() },
        )
    }
}

private fun mermaidHtml(code: String, isDark: Boolean): String {
    val theme = if (isDark) "dark" else "default"
    return """
        <!doctype html>
        <html>
        <head>
        <meta name="viewport" content="width=device-width, initial-scale=1">
        <style>
          html, body { margin: 0; overflow: hidden; background: transparent; }
          #diagram { text-align: center; font-family: sans-serif; color: ${if (isDark) "#ddd" else "#222"}; }
          #diagram svg { max-width: 100%; height: auto; }
        </style>
        <script src="/assets/mermaid/mermaid.min.js"></script>
        </head>
        <body>
        <div id="diagram"></div>
        <script>
          const diagram = document.getElementById("diagram");
          const report = () => Android.onHeight(Math.ceil(diagram.getBoundingClientRect().height));
          new ResizeObserver(report).observe(diagram);
          mermaid.initialize({ startOnLoad: false, securityLevel: "strict", theme: "$theme" });
          mermaid.render("graph", ${JSONObject.quote(code)})
            .then(result => { diagram.innerHTML = result.svg; })
            .catch(error => { diagram.textContent = String(error.message || error); });
        </script>
        </body>
        </html>
    """.trimIndent()
}
