package com.mrndtvndv.term.ui.review

import android.annotation.SuppressLint
import android.graphics.Color
import android.view.MotionEvent
import android.view.VelocityTracker
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.webkit.JavascriptInterface
import android.webkit.RenderProcessGoneDetail
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.rememberNestedScrollInteropConnection
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.NestedScrollingChild3
import androidx.core.view.NestedScrollingChildHelper
import androidx.core.view.ViewCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.webkit.WebViewAssetLoader
import com.mrndtvndv.term.ui.theme.LocalCustomFontFamily
import java.io.File
import java.io.FileInputStream
import java.io.IOException
import java.util.Locale
import org.json.JSONObject

private const val AssetUrl = "https://appassets.androidplatform.net/assets/diff-viewer/index.html"
private const val CustomFontPath = "/custom-font/font.ttf"

internal data class DiffDisplaySettings(
    val isDarkTheme: Boolean,
    val showLineNumbers: Boolean,
    val isWordDiffEnabled: Boolean,
    val useCustomFont: Boolean = false,
    val fontVersion: Long = 0L
)

internal class DiffSearchController {
    internal var findNextAction: ((Boolean) -> Unit)? = null

    fun findNext(forward: Boolean) {
        findNextAction?.invoke(forward)
    }
}

internal data class DiffSearchState(
    val query: String = "",
    val controller: DiffSearchController? = null,
    val onMatchCountChange: (Int) -> Unit = {},
    val onMatchIndexChange: (Int) -> Unit = {}
)

@Suppress("UnusedParameter")
private class DiffBridge(
    private val onRenderFinished: () -> Unit = {}
) {
    /**
     * Horizontal scroll capability of the code container under the active
     * touch, reported asynchronously by JS. Packed into one int so a reader
     * never sees a torn left/right pair. [SCROLL_STATE_UNKNOWN] means JS has
     * not yet reported for the current gesture; callers must not treat that
     * as "cannot scroll".
     */
    @Volatile var horizontalScrollState: Int = SCROLL_STATE_UNKNOWN

    fun resetHorizontalScrollState() {
        horizontalScrollState = SCROLL_STATE_UNKNOWN
    }

    @JavascriptInterface
    fun onRenderComplete(fileCount: Int, hunkCount: Int) {
        onRenderFinished()
    }

    @JavascriptInterface
    fun onError(message: String) {
        // Logged via JS console
    }

    @JavascriptInterface
    fun onHorizontalScrollState(canLeft: Boolean, canRight: Boolean) {
        horizontalScrollState =
            (if (canLeft) SCROLL_STATE_CAN_LEFT else 0) or
            (if (canRight) SCROLL_STATE_CAN_RIGHT else 0)
    }

    companion object {
        const val SCROLL_STATE_UNKNOWN = -1
        const val SCROLL_STATE_CAN_LEFT = 1
        const val SCROLL_STATE_CAN_RIGHT = 2
    }
}

/** Max time to wait for JS to report scroll bounds before assuming none. */
private const val ScrollStateWaitMs = 120L

/**
 * Android WebView host for rendering code diffs using `@pierre/diffs`.
 * Uses [WebViewAssetLoader] to load bundled assets securely offline without CORS issues.
 */
@Composable
internal fun PierreDiffView(
    rawDiff: String,
    settings: DiffDisplaySettings,
    search: DiffSearchState = DiffSearchState(),
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val surfaceColor = MaterialTheme.colorScheme.surface
    val onSurfaceColor = MaterialTheme.colorScheme.onSurface

    val (bgColorHex, fgColorHex) = remember(surfaceColor, onSurfaceColor) {
        val bgHex = String.format(Locale.ROOT, "#%06X", 0xFFFFFF and surfaceColor.toArgb())
        val fgHex = String.format(Locale.ROOT, "#%06X", 0xFFFFFF and onSurfaceColor.toArgb())
        bgHex to fgHex
    }

    var webViewRef by remember { mutableStateOf<WebView?>(null) }
    var isPageLoaded by remember { mutableStateOf(false) }
    // Bumped when the renderer process dies (observed live as a WebView sandbox kill
    // during keyboard-driven resize storms). The old view can never paint again, so
    // key a fresh AndroidView: disposal destroys the dead view and the reload effects
    // below re-render the current diff into the new one via isPageLoaded.
    var rendererGeneration by remember { mutableStateOf(0) }

    val bridge = remember(webViewRef, search.query) {
        DiffBridge {
            if (search.query.isNotBlank()) {
                webViewRef?.post {
                    webViewRef?.findAllAsync(search.query)
                }
            }
        }
    }
    val assetLoader = remember {
        WebViewAssetLoader.Builder()
            .addPathHandler("/assets/", WebViewAssetLoader.AssetsPathHandler(context))
            .build()
    }

    SetupSearchListeners(webViewRef, search)
    val resolvedSettings = rememberResolvedSettings(context, settings)

    ManageWebViewLifecycle(webViewRef)
    SyncDiffState(webViewRef, isPageLoaded, rawDiff, resolvedSettings)
    SyncThemeAndStyle(webViewRef, isPageLoaded, resolvedSettings, bgColorHex, fgColorHex)
    SyncSearch(webViewRef, isPageLoaded, search)

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(surfaceColor)
            .nestedScroll(rememberNestedScrollInteropConnection())
    ) {
        key(rendererGeneration) {
            AndroidView(
                modifier = Modifier.fillMaxSize(),
                factory = { ctx ->
                    createConfiguredWebView(
                        ctx,
                        bridge,
                        assetLoader,
                        onLoaded = { isPageLoaded = true },
                        onRenderProcessGone = {
                            webViewRef = null
                            isPageLoaded = false
                            rendererGeneration++
                        }
                    ).also { webViewRef = it }
                }
            )
        }
    }
}

@Composable
private fun SetupSearchListeners(
    webViewRef: WebView?,
    search: DiffSearchState
) {
    DisposableEffect(webViewRef, search.controller) {
        search.controller?.findNextAction = { forward ->
            webViewRef?.findNext(forward)
        }
        onDispose {
            search.controller?.findNextAction = null
        }
    }

    DisposableEffect(webViewRef, search.onMatchCountChange, search.onMatchIndexChange) {
        webViewRef?.setFindListener { activeMatchOrdinal, numberOfMatches, isDoneCounting ->
            if (isDoneCounting) {
                search.onMatchCountChange(numberOfMatches)
                search.onMatchIndexChange(if (numberOfMatches > 0) activeMatchOrdinal else 0)
            }
        }
        onDispose {
            webViewRef?.setFindListener(null)
        }
    }
}

@Composable
private fun rememberResolvedSettings(
    context: android.content.Context,
    settings: DiffDisplaySettings
): DiffDisplaySettings {
    val localCustomFont = LocalCustomFontFamily.current
    return remember(settings, localCustomFont) {
        val useCustom = settings.useCustomFont || (localCustomFont != null)
        val version = if (settings.fontVersion != 0L) {
            settings.fontVersion
        } else if (useCustom) {
            val fontFile = File(context.filesDir, "font.ttf")
            if (fontFile.exists()) fontFile.lastModified() else 0L
        } else {
            0L
        }
        settings.copy(useCustomFont = useCustom, fontVersion = version)
    }
}

@Composable
private fun ManageWebViewLifecycle(webViewRef: WebView?) {
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner, webViewRef) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_PAUSE -> webViewRef?.onPause()
                Lifecycle.Event.ON_RESUME -> webViewRef?.onResume()
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            webViewRef?.let { wv ->
                // Detach before destroy: WebView.destroy() requires removal from the
                // view system first. Destroying while attached lets the framework
                // detach the view outside Compose scheduling, racing the pager's
                // placement pass ("LayoutNode should be attached to an owner").
                // The later AndroidView unmount re-removes (no-op) safely.
                (wv.parent as? ViewGroup)?.removeView(wv)
                wv.stopLoading()
                wv.clearMatches()
                wv.destroy()
            }
        }
    }
}

@Composable
private fun SyncDiffState(
    webView: WebView?,
    isPageLoaded: Boolean,
    rawDiff: String,
    settings: DiffDisplaySettings
) {
    LaunchedEffect(rawDiff, isPageLoaded) {
        if (webView == null || !isPageLoaded) return@LaunchedEffect
        val options = JSONObject().apply {
            put("isDark", settings.isDarkTheme)
            put("diffStyle", "unified")
            put("showLineNumbers", settings.showLineNumbers)
            put("isWordDiffEnabled", settings.isWordDiffEnabled)
            put("useCustomFont", settings.useCustomFont)
            if (settings.useCustomFont) {
                put("fontUrl", "/custom-font/font.ttf?v=${settings.fontVersion}")
            }
        }
        val optionsJson = options.toString()
        val escapedPatch = JSONObject.quote(rawDiff)
        val script = "window.diffViewer?.renderPatch($escapedPatch, '$optionsJson');"
        webView.evaluateJavascript(script, null)
    }
}

@Composable
private fun SyncThemeAndStyle(
    webView: WebView?,
    isPageLoaded: Boolean,
    settings: DiffDisplaySettings,
    bgColorHex: String,
    fgColorHex: String
) {
    LaunchedEffect(settings.isDarkTheme, bgColorHex, fgColorHex, isPageLoaded) {
        if (webView == null || !isPageLoaded) return@LaunchedEffect
        val script = "window.diffViewer?.updateTheme(${settings.isDarkTheme}, '$bgColorHex', '$fgColorHex');"
        webView.evaluateJavascript(script, null)
    }

    LaunchedEffect(settings.showLineNumbers, isPageLoaded) {
        if (webView == null || !isPageLoaded) return@LaunchedEffect
        webView.evaluateJavascript("window.diffViewer?.setLineNumbers(${settings.showLineNumbers});", null)
    }

    LaunchedEffect(settings.isWordDiffEnabled, isPageLoaded) {
        if (webView == null || !isPageLoaded) return@LaunchedEffect
        webView.evaluateJavascript("window.diffViewer?.setWordDiff(${settings.isWordDiffEnabled});", null)
    }

    LaunchedEffect(settings.useCustomFont, settings.fontVersion, isPageLoaded) {
        if (webView == null || !isPageLoaded) return@LaunchedEffect
        val fontUrl = if (settings.useCustomFont) "/custom-font/font.ttf?v=${settings.fontVersion}" else ""
        webView.evaluateJavascript(
            "window.diffViewer?.setFontFamily(${settings.useCustomFont}, '$fontUrl');",
            null
        )
    }
}

@Composable
private fun SyncSearch(
    webView: WebView?,
    isPageLoaded: Boolean,
    search: DiffSearchState
) {
    LaunchedEffect(search.query, isPageLoaded) {
        if (webView == null || !isPageLoaded) return@LaunchedEffect
        if (search.query.isBlank()) {
            webView.clearMatches()
            search.onMatchCountChange(0)
            search.onMatchIndexChange(0)
        } else {
            webView.findAllAsync(search.query)
        }
    }
}

@SuppressLint("ViewConstructor")
@Suppress("TooManyFunctions")
private class ScrollableDiffWebView(
    context: android.content.Context,
    private val diffBridge: DiffBridge? = null
) : WebView(context), NestedScrollingChild3 {
    private val childHelper = NestedScrollingChildHelper(this).apply {
        isNestedScrollingEnabled = true
    }
    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop
    private var startX = 0
    private var startY = 0
    private var lastX = 0
    private var lastY = 0
    private var isDraggingX = false
    private var isDraggingY = false
    private var dragDirX = 0
    private var dirAnchorX = 0
    private var downTime = 0L
    private val scrollConsumed = IntArray(2)
    private val scrollOffset = IntArray(2)
    private var nestedOffsetX = 0
    private var nestedOffsetY = 0
    private var velocityTracker: VelocityTracker? = null

    override fun setNestedScrollingEnabled(enabled: Boolean) {
        childHelper.isNestedScrollingEnabled = enabled
    }

    override fun isNestedScrollingEnabled(): Boolean = childHelper.isNestedScrollingEnabled

    override fun startNestedScroll(axes: Int, type: Int): Boolean =
        childHelper.startNestedScroll(axes, type)

    override fun startNestedScroll(axes: Int): Boolean =
        childHelper.startNestedScroll(axes)

    override fun stopNestedScroll(type: Int) {
        childHelper.stopNestedScroll(type)
    }

    override fun stopNestedScroll() {
        childHelper.stopNestedScroll()
    }

    override fun hasNestedScrollingParent(type: Int): Boolean =
        childHelper.hasNestedScrollingParent(type)

    override fun hasNestedScrollingParent(): Boolean =
        childHelper.hasNestedScrollingParent()

    override fun dispatchNestedScroll(
        dxConsumed: Int,
        dyConsumed: Int,
        dxUnconsumed: Int,
        dyUnconsumed: Int,
        offsetInWindow: IntArray?,
        type: Int,
        consumed: IntArray
    ) {
        childHelper.dispatchNestedScroll(
            dxConsumed, dyConsumed, dxUnconsumed, dyUnconsumed, offsetInWindow, type, consumed
        )
    }

    override fun dispatchNestedScroll(
        dxConsumed: Int,
        dyConsumed: Int,
        dxUnconsumed: Int,
        dyUnconsumed: Int,
        offsetInWindow: IntArray?,
        type: Int
    ): Boolean = childHelper.dispatchNestedScroll(
        dxConsumed, dyConsumed, dxUnconsumed, dyUnconsumed, offsetInWindow, type
    )

    override fun dispatchNestedScroll(
        dxConsumed: Int,
        dyConsumed: Int,
        dxUnconsumed: Int,
        dyUnconsumed: Int,
        offsetInWindow: IntArray?
    ): Boolean = childHelper.dispatchNestedScroll(
        dxConsumed, dyConsumed, dxUnconsumed, dyUnconsumed, offsetInWindow
    )

    override fun dispatchNestedPreScroll(
        dx: Int,
        dy: Int,
        consumed: IntArray?,
        offsetInWindow: IntArray?,
        type: Int
    ): Boolean = childHelper.dispatchNestedPreScroll(dx, dy, consumed, offsetInWindow, type)

    override fun dispatchNestedPreScroll(
        dx: Int,
        dy: Int,
        consumed: IntArray?,
        offsetInWindow: IntArray?
    ): Boolean = childHelper.dispatchNestedPreScroll(dx, dy, consumed, offsetInWindow)

    override fun dispatchNestedFling(
        velocityX: Float,
        velocityY: Float,
        consumed: Boolean
    ): Boolean = childHelper.dispatchNestedFling(velocityX, velocityY, consumed)

    override fun dispatchNestedPreFling(
        velocityX: Float,
        velocityY: Float
    ): Boolean = childHelper.dispatchNestedPreFling(velocityX, velocityY)

    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        // Never lock interception on DOWN/MOVE here: onTouchEvent decides per
        // gesture direction once touch slop is exceeded. Locking here would
        // prevent the parent HorizontalPager from ever stealing horizontal
        // swipes for tab switches. Always release on gesture end so the next
        // gesture starts interceptable.
        if (event.actionMasked == MotionEvent.ACTION_UP ||
            event.actionMasked == MotionEvent.ACTION_CANCEL
        ) {
            parent?.requestDisallowInterceptTouchEvent(false)
        }
        return super.dispatchTouchEvent(event)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        val tracker = velocityTracker ?: VelocityTracker.obtain().also { velocityTracker = it }
        tracker.addMovement(event)

        val motionEvent = MotionEvent.obtain(event)
        if (event.actionMasked == MotionEvent.ACTION_DOWN) {
            nestedOffsetX = 0
            nestedOffsetY = 0
        }
        motionEvent.offsetLocation(nestedOffsetX.toFloat(), nestedOffsetY.toFloat())

        val result = when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> handleTouchDown(event, motionEvent)
            MotionEvent.ACTION_MOVE -> handleTouchMove(event, motionEvent)
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL ->
                handleTouchUp(motionEvent, tracker)
            else -> super.onTouchEvent(motionEvent)
        }
        motionEvent.recycle()
        return result
    }

    private fun handleTouchDown(event: MotionEvent, motionEvent: MotionEvent): Boolean {
        startX = event.rawX.toInt()
        startY = event.rawY.toInt()
        lastX = startX
        lastY = startY
        isDraggingX = false
        isDraggingY = false
        dragDirX = 0
        downTime = event.eventTime
        // Drop the previous gesture's bounds; JS reports fresh ones on touchstart.
        diffBridge?.resetHorizontalScrollState()
        startNestedScroll(
            ViewCompat.SCROLL_AXIS_VERTICAL or ViewCompat.SCROLL_AXIS_HORIZONTAL,
            ViewCompat.TYPE_TOUCH
        )
        // Do NOT disallow intercept on down: the parent pager must stay
        // eligible to steal the gesture until direction + scrollability
        // are known.
        return super.onTouchEvent(motionEvent)
    }

    private fun handleTouchMove(event: MotionEvent, motionEvent: MotionEvent): Boolean {
        val rawX = event.rawX.toInt()
        val rawY = event.rawY.toInt()
        lockDragDirection(rawX, rawY)
        return when {
            isDraggingY -> handleVerticalMove(rawX, rawY, motionEvent)
            isDraggingX -> handleHorizontalMove(rawX, rawY, motionEvent)
            else -> {
                lastX = rawX
                lastY = rawY
                super.onTouchEvent(motionEvent)
            }
        }
    }

    private fun lockDragDirection(rawX: Int, rawY: Int) {
        if (isDraggingX || isDraggingY) return
        val totalDx = rawX - startX
        val totalDy = rawY - startY
        if (kotlin.math.abs(totalDy) > touchSlop &&
            kotlin.math.abs(totalDy) > kotlin.math.abs(totalDx)
        ) {
            isDraggingY = true
        } else if (kotlin.math.abs(totalDx) > touchSlop &&
            kotlin.math.abs(totalDx) > kotlin.math.abs(totalDy)
        ) {
            isDraggingX = true
        }
    }

    private fun handleVerticalMove(rawX: Int, rawY: Int, motionEvent: MotionEvent): Boolean {
        parent?.requestDisallowInterceptTouchEvent(true)
        var dy = lastY - rawY
        if (dispatchNestedPreScroll(0, dy, scrollConsumed, scrollOffset, ViewCompat.TYPE_TOUCH)) {
            dy -= scrollConsumed[1]
            motionEvent.offsetLocation(0f, -scrollConsumed[1].toFloat())
            nestedOffsetY += scrollOffset[1]
        }
        lastX = rawX - scrollOffset[0]
        lastY = rawY - scrollOffset[1]
        val result = super.onTouchEvent(motionEvent)
        dispatchNestedScroll(
            0, scrollConsumed[1], 0, dy, scrollOffset, ViewCompat.TYPE_TOUCH
        )
        return result
    }

    /**
     * Tracks the finger's horizontal travel direction with hysteresis: a
     * single jittery opposite-direction event must not flip it, otherwise the
     * wrong edge is consulted and the pager steals a gesture that should be
     * scrolling code. Returns +1 (finger moving left, content scrolls right)
     * or -1 (finger moving right, content scrolls left).
     */
    private fun updateDragDirX(rawX: Int): Int {
        if (dragDirX == 0) {
            dragDirX = if (rawX < startX) 1 else -1
            dirAnchorX = rawX
        } else if (dragDirX > 0) {
            if (rawX < dirAnchorX) {
                dirAnchorX = rawX
            } else if (rawX - dirAnchorX > touchSlop) {
                dragDirX = -1
                dirAnchorX = rawX
            }
        } else {
            if (rawX > dirAnchorX) {
                dirAnchorX = rawX
            } else if (dirAnchorX - rawX > touchSlop) {
                dragDirX = 1
                dirAnchorX = rawX
            }
        }
        return dragDirX
    }

    private fun canScrollCodeInDirection(dirX: Int, eventTime: Long): Boolean {
        val state = diffBridge?.horizontalScrollState ?: return false
        if (state == DiffBridge.SCROLL_STATE_UNKNOWN) {
            // JS has not reported bounds for this gesture yet. Hold the
            // gesture rather than letting the pager steal it on stale data.
            return eventTime - downTime < ScrollStateWaitMs
        }
        val flag = if (dirX < 0) DiffBridge.SCROLL_STATE_CAN_LEFT else DiffBridge.SCROLL_STATE_CAN_RIGHT
        return state and flag != 0
    }

    private fun handleHorizontalMove(rawX: Int, rawY: Int, motionEvent: MotionEvent): Boolean {
        val canScrollInDirection = canScrollCodeInDirection(updateDragDirX(rawX), motionEvent.eventTime)
        parent?.requestDisallowInterceptTouchEvent(canScrollInDirection)
        if (canScrollInDirection) {
            // The web content owns this gesture; keep the parent out of the
            // nested-scroll chain so the pager's pre-scroll cannot eat deltas.
            lastX = rawX
            lastY = rawY
            return super.onTouchEvent(motionEvent)
        }
        var dx = lastX - rawX
        if (dispatchNestedPreScroll(dx, 0, scrollConsumed, scrollOffset, ViewCompat.TYPE_TOUCH)) {
            dx -= scrollConsumed[0]
            motionEvent.offsetLocation(-scrollConsumed[0].toFloat(), 0f)
            nestedOffsetX += scrollOffset[0]
        }
        lastX = rawX - scrollOffset[0]
        lastY = rawY - scrollOffset[1]
        val result = super.onTouchEvent(motionEvent)
        // At the scroll edge (or no overflow): hand the remainder to the
        // parent pager for a tab swipe. Flows via
        // rememberNestedScrollInteropConnection into
        // WorkspacePager's page nested-scroll connection.
        dispatchNestedScroll(
            scrollConsumed[0], 0, dx, 0, scrollOffset, ViewCompat.TYPE_TOUCH
        )
        return result
    }

    private fun handleTouchUp(
        motionEvent: MotionEvent,
        tracker: VelocityTracker
    ): Boolean {
        parent?.requestDisallowInterceptTouchEvent(false)
        tracker.computeCurrentVelocity(1000)
        if (isDraggingX && tracker.xVelocity != 0f) {
            dispatchNestedPreFling(-tracker.xVelocity, 0f)
            dispatchNestedFling(-tracker.xVelocity, 0f, false)
        } else if (isDraggingY && tracker.yVelocity != 0f) {
            dispatchNestedPreFling(0f, -tracker.yVelocity)
        }
        isDraggingX = false
        isDraggingY = false
        stopNestedScroll(ViewCompat.TYPE_TOUCH)
        velocityTracker?.recycle()
        velocityTracker = null
        return super.onTouchEvent(motionEvent)
    }
}

private fun handleCustomFontRequest(
    context: android.content.Context,
    path: String?
): WebResourceResponse? {
    if (path != CustomFontPath) return null
    val fontFile = File(context.filesDir, "font.ttf")
    if (!fontFile.isFile || fontFile.length() <= 0L) return null
    return try {
        WebResourceResponse("font/ttf", null, FileInputStream(fontFile)).apply {
            responseHeaders = mapOf(
                "Access-Control-Allow-Origin" to "*",
                "Cache-Control" to "no-cache"
            )
        }
    } catch (e: IOException) {
        android.util.Log.w("PierreDiffView", "Failed to open custom font", e)
        null
    }
}

@SuppressLint("SetJavaScriptEnabled")
private fun createConfiguredWebView(
    context: android.content.Context,
    bridge: DiffBridge,
    assetLoader: WebViewAssetLoader,
    onLoaded: () -> Unit,
    onRenderProcessGone: () -> Unit
): WebView {
    return ScrollableDiffWebView(context, bridge).apply {
        layoutParams = ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT
        )
        setBackgroundColor(Color.TRANSPARENT)
        isVerticalScrollBarEnabled = true
        isHorizontalScrollBarEnabled = true

        settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            allowFileAccess = false
            allowContentAccess = false
            cacheMode = WebSettings.LOAD_NO_CACHE
            setSupportZoom(true)
            builtInZoomControls = true
            displayZoomControls = false
            useWideViewPort = true
            loadWithOverviewMode = true
        }

        addJavascriptInterface(bridge, "AndroidDiffBridge")

        webViewClient = object : WebViewClient() {
            override fun shouldInterceptRequest(
                view: WebView,
                request: WebResourceRequest
            ): WebResourceResponse? {
                return handleCustomFontRequest(context, request.url.path)
                    ?: assetLoader.shouldInterceptRequest(request.url)
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                onLoaded()
            }

            override fun onRenderProcessGone(
                view: WebView?,
                detail: RenderProcessGoneDetail?
            ): Boolean {
                android.util.Log.w(
                    "PierreDiffView",
                    "Renderer gone (crashed=${detail?.didCrash()}); recreating WebView"
                )
                // Hide synchronously: the framework destroyed the view outside
                // Compose scheduling, and a GONE view is skipped by placement, so no
                // traversal can walk the detached holder before recreation lands.
                view?.visibility = View.GONE
                onRenderProcessGone()
                return true
            }
        }

        loadUrl(AssetUrl)
    }
}
