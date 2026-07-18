package com.mrndtvndv.term.ui.browser

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.view.ViewGroup
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView

fun urlsMatch(url1: String?, url2: String?): Boolean {
    if (url1 == url2) return true
    if (url1 == null || url2 == null) return false
    return url1.trim().removeSuffix("/") == url2.trim().removeSuffix("/")
}

@OptIn(ExperimentalMaterial3Api::class)
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun InAppBrowser(
    getWebView: () -> WebView,
    initialUrl: String,
    onUrlChanged: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    val webView = remember { getWebView() }
    var currentUrl by remember(initialUrl) { mutableStateOf(initialUrl) }
    var inputUrl by remember { mutableStateOf(initialUrl) }
    var isLoading by remember { mutableStateOf(false) }
    var progress by remember { mutableIntStateOf(0) }
    var canGoBack by remember { mutableStateOf(false) }
    var canGoForward by remember { mutableStateOf(false) }

    val focusManager = LocalFocusManager.current

    // Sync input field when currentUrl changes
    LaunchedEffect(currentUrl) {
        inputUrl = currentUrl
    }

    // Handle initialUrl changes from external sources (e.g. terminal click)
    LaunchedEffect(initialUrl, webView) {
        if (initialUrl.isNotEmpty() && !urlsMatch(webView.url, initialUrl)) {
            webView.loadUrl(initialUrl)
        }
    }

    LaunchedEffect(webView) {
        if (webView.url == null && currentUrl.isNotEmpty()) {
            webView.loadUrl(currentUrl)
        }
    }

    val currentOnUrlChanged by rememberUpdatedState(onUrlChanged)
    val currentInitialUrl by rememberUpdatedState(initialUrl)

    DisposableEffect(webView) {
        val client = object : WebViewClient() {
            override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                isLoading = true
                progress = 0
                url?.let {
                    if (!urlsMatch(currentUrl, it)) {
                        currentUrl = it
                    }
                    if (!urlsMatch(currentInitialUrl, it)) {
                        currentOnUrlChanged(it)
                    }
                }
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                isLoading = false
                canGoBack = view?.canGoBack() ?: false
                canGoForward = view?.canGoForward() ?: false
                url?.let {
                    if (!urlsMatch(currentUrl, it)) {
                        currentUrl = it
                    }
                    if (!urlsMatch(currentInitialUrl, it)) {
                        currentOnUrlChanged(it)
                    }
                }
            }

            override fun shouldOverrideUrlLoading(
                view: WebView?,
                request: WebResourceRequest?
            ): Boolean {
                return false
            }
        }

        val chromeClient = object : WebChromeClient() {
            override fun onProgressChanged(view: WebView?, newProgress: Int) {
                progress = newProgress
                if (newProgress == 100) {
                    isLoading = false
                }
            }
        }

        webView.webViewClient = client
        webView.webChromeClient = chromeClient

        canGoBack = webView.canGoBack()
        canGoForward = webView.canGoForward()
        webView.url?.let {
            if (!urlsMatch(currentUrl, it)) {
                currentUrl = it
            }
        }

        onDispose {
            webView.webViewClient = WebViewClient()
            webView.webChromeClient = null
        }
    }

    // Intercept back button if webview can navigate back
    BackHandler(enabled = canGoBack) {
        webView.goBack()
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
    ) {
        // WebView
        AndroidView(
            factory = { _ ->
                webView.apply {
                    (parent as? ViewGroup)?.removeView(this)
                }
            },
            update = {
                // Handled in LaunchedEffect
            },
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
        )

        // Progress Bar
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(3.dp)
        ) {
            if (isLoading) {
                LinearProgressIndicator(
                    progress = { progress / 100f },
                    modifier = Modifier.fillMaxWidth(),
                    color = MaterialTheme.colorScheme.primary,
                    trackColor = MaterialTheme.colorScheme.surfaceVariant
                )
            }
        }

        // Control Bar
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            IconButton(
                onClick = { webView.goBack() },
                enabled = canGoBack
            ) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = "Back",
                    tint = if (canGoBack) MaterialTheme.colorScheme.onBackground else MaterialTheme.colorScheme.onBackground.copy(alpha = 0.38f)
                )
            }

            IconButton(
                onClick = { webView.goForward() },
                enabled = canGoForward
            ) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.ArrowForward,
                    contentDescription = "Forward",
                    tint = if (canGoForward) MaterialTheme.colorScheme.onBackground else MaterialTheme.colorScheme.onBackground.copy(alpha = 0.38f)
                )
            }

            IconButton(
                onClick = {
                    if (isLoading) {
                        webView.stopLoading()
                    } else {
                        webView.reload()
                    }
                }
            ) {
                Icon(
                    imageVector = if (isLoading) Icons.Default.Close else Icons.Default.Refresh,
                    contentDescription = if (isLoading) "Stop" else "Refresh"
                )
            }

            IconButton(
                onClick = {
                    webView.loadUrl("about:blank")
                }
            ) {
                Icon(
                    imageVector = Icons.Default.Home,
                    contentDescription = "Home"
                )
            }

            // Address bar
            OutlinedTextField(
                value = inputUrl,
                onValueChange = { inputUrl = it },
                modifier = Modifier
                    .weight(1f)
                    .height(52.dp),
                shape = RoundedCornerShape(26.dp),
                singleLine = true,
                textStyle = MaterialTheme.typography.bodyMedium,
                colors = OutlinedTextFieldDefaults.colors(
                    focusedContainerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f),
                    unfocusedContainerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f),
                    focusedBorderColor = MaterialTheme.colorScheme.primary,
                    unfocusedBorderColor = MaterialTheme.colorScheme.outline.copy(alpha = 0.5f)
                ),
                keyboardOptions = KeyboardOptions(
                    imeAction = ImeAction.Search
                ),
                keyboardActions = KeyboardActions(
                    onSearch = {
                        focusManager.clearFocus()
                        var target = inputUrl.trim()
                        if (target.isNotEmpty()) {
                            if (!target.contains(".") || target.contains(" ")) {
                                // Search Google
                                target = "https://www.google.com/search?q=" + java.net.URLEncoder.encode(target, "UTF-8")
                            } else if (!target.startsWith("http://") && !target.startsWith("https://")) {
                                target = "https://$target"
                            }
                            webView.loadUrl(target)
                        }
                    }
                ),
                placeholder = {
                    Text("Search or enter URL", style = MaterialTheme.typography.bodyMedium)
                }
            )
        }
    }
}
