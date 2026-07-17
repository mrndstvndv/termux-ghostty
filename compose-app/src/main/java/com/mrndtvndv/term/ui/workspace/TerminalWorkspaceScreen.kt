package com.mrndtvndv.term.ui.workspace

import android.app.Activity
import android.view.ViewGroup
import android.webkit.WebView
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material3.*
import androidx.compose.material3.TabRowDefaults.tabIndicatorOffset
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.window.layout.FoldingFeature
import androidx.window.layout.WindowInfoTracker
import com.termux.terminal.TerminalSession
import com.mrndtvndv.term.ui.sftp.SftpFileBrowser
import com.mrndtvndv.term.ui.sftp.SftpViewModel
import com.mrndtvndv.term.ui.keyboard.ExtraKeysToolbar
import com.mrndtvndv.term.ui.keyboard.ExtraKeysController
import com.termux.view.TerminalView
import com.mrndtvndv.term.ui.browser.InAppBrowser
import com.mrndtvndv.term.ui.review.ReviewViewModel
import com.mrndtvndv.term.ui.review.GitReviewScreen
import kotlinx.coroutines.flow.filterIsInstance
import java.io.File

sealed interface WorkspaceTab {
    object Terminal : WorkspaceTab
    object Sftp : WorkspaceTab
    object Review : WorkspaceTab
    object Browser : WorkspaceTab
}

class Ref<T>(var value: T? = null)

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun TabbedWorkspace(
    webView: WebView,
    session: TerminalSession,
    sftpViewModel: SftpViewModel?,
    reviewViewModel: ReviewViewModel?,
    extraKeysController: ExtraKeysController,
    getActiveTerminalView: () -> TerminalView?,
    extraKeysEnabled: Boolean,
    extraKeysJson: String,
    onViewCreated: (TerminalView) -> Unit,
    onViewReleased: (TerminalView) -> Unit,
    activePage: Int,
    onPageSelected: (Int) -> Unit,
    onOpenFile: (File) -> Unit,
    onOpenFileError: (String) -> Unit,
    browserUrl: String,
    onBrowserUrlChanged: (String) -> Unit,
    onOpenUrl: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    val activeTabs = remember(sftpViewModel, reviewViewModel) {
        buildList {
            add(WorkspaceTab.Terminal)
            if (sftpViewModel != null) {
                add(WorkspaceTab.Sftp)
            }
            if (reviewViewModel != null) {
                add(WorkspaceTab.Review)
            }
            add(WorkspaceTab.Browser)
        }
    }

    val pagerState = rememberPagerState(
        initialPage = activePage,
        pageCount = { activeTabs.size }
    )

    var lastSelectedTabType by remember { mutableStateOf<WorkspaceTab>(WorkspaceTab.Terminal) }

    // Sync lastSelectedTabType when activePage or activeTabs change
    LaunchedEffect(activePage, activeTabs) {
        if (activePage in activeTabs.indices) {
            lastSelectedTabType = activeTabs[activePage]
        }
    }

    // Align activePage when the activeTabs list changes to keep the same tab type focused
    LaunchedEffect(activeTabs) {
        val targetIndex = activeTabs.indexOf(lastSelectedTabType)
        if (targetIndex != -1 && targetIndex != activePage) {
            onPageSelected(targetIndex)
        }
    }

    // Synchronize pagerState with activePage selected from TabRow
    LaunchedEffect(activePage) {
        if (pagerState.currentPage != activePage && activePage < activeTabs.size) {
            pagerState.scrollToPage(activePage)
        }
    }

    // Synchronize activePage with pagerState (e.g. when page is selected)
    LaunchedEffect(pagerState.currentPage) {
        if (pagerState.currentPage != activePage && pagerState.currentPage < activeTabs.size) {
            onPageSelected(pagerState.currentPage)
        }
    }

    // Synchronize activePage to Browser tab when a URL is loaded (skip first composition)
    var isFirstCompose by remember { mutableStateOf(true) }
    LaunchedEffect(browserUrl) {
        if (isFirstCompose) {
            isFirstCompose = false
            return@LaunchedEffect
        }
        if (browserUrl.isNotEmpty()) {
            val browserIndex = activeTabs.indexOf(WorkspaceTab.Browser)
            if (browserIndex != -1 && pagerState.currentPage != browserIndex) {
                onPageSelected(browserIndex)
            }
        }
    }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        topBar = {
            TabRow(
                selectedTabIndex = activePage,
                containerColor = MaterialTheme.colorScheme.surface,
                contentColor = MaterialTheme.colorScheme.onSurface,
                indicator = { tabPositions ->
                    if (activePage < tabPositions.size) {
                        TabRowDefaults.SecondaryIndicator(
                            modifier = Modifier.tabIndicatorOffset(tabPositions[activePage]),
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                }
            ) {
                activeTabs.forEachIndexed { index, tab ->
                    val isSelected = activePage == index
                    Tab(
                        selected = isSelected,
                        onClick = { onPageSelected(index) },
                        selectedContentColor = MaterialTheme.colorScheme.primary,
                        unselectedContentColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                        text = {
                            val title = when (tab) {
                                WorkspaceTab.Terminal -> "Terminal"
                                WorkspaceTab.Sftp -> "SFTP Explorer"
                                WorkspaceTab.Review -> "Review"
                                WorkspaceTab.Browser -> "Browser"
                            }
                            Text(
                                text = title,
                                color = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal
                            )
                        }
                    )
                }
            }
        }
    ) { paddingValues ->
        HorizontalPager(
            state = pagerState,
            userScrollEnabled = false, // Disable swipe so terminal selection / scrolling works
            beyondBoundsPageCount = 2,
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .clipToBounds()
        ) { page ->
            if (page < activeTabs.size) {
                when (activeTabs[page]) {
                    WorkspaceTab.Terminal -> {
                        Column(modifier = Modifier.fillMaxSize()) {
                            Box(modifier = Modifier.weight(1f)) {
                                TerminalFocusWrapper(
                                    session = session,
                                    extraKeysController = extraKeysController,
                                    isTerminalActive = true,
                                    onViewCreated = onViewCreated,
                                    onViewReleased = onViewReleased,
                                    onOpenUrl = onOpenUrl
                                )
                            }
                            if (extraKeysEnabled) {
                                ExtraKeysToolbar(
                                    extraKeysController = extraKeysController,
                                    getActiveTerminalView = getActiveTerminalView,
                                    session = session,
                                    extraKeysJson = extraKeysJson
                                )
                            }
                        }
                    }
                    WorkspaceTab.Sftp -> {
                        if (sftpViewModel != null) {
                            SftpFileBrowser(
                                viewModel = sftpViewModel,
                                onOpenFile = onOpenFile,
                                onOpenFileError = onOpenFileError
                            )
                        }
                    }
                    WorkspaceTab.Review -> {
                        if (reviewViewModel != null) {
                            GitReviewScreen(
                                viewModel = reviewViewModel,
                                modifier = Modifier.fillMaxSize()
                            )
                        }
                    }
                    WorkspaceTab.Browser -> {
                        InAppBrowser(
                            webView = webView,
                            initialUrl = browserUrl,
                            onUrlChanged = onBrowserUrlChanged,
                            modifier = Modifier.fillMaxSize()
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun SplitWorkspace(
    webView: WebView,
    session: TerminalSession,
    sftpViewModel: SftpViewModel?,
    reviewViewModel: ReviewViewModel?,
    foldingFeature: FoldingFeature?,
    extraKeysController: ExtraKeysController,
    getActiveTerminalView: () -> TerminalView?,
    extraKeysEnabled: Boolean,
    extraKeysJson: String,
    onViewCreated: (TerminalView) -> Unit,
    onViewReleased: (TerminalView) -> Unit,
    onOpenFile: (File) -> Unit,
    onOpenFileError: (String) -> Unit,
    browserUrl: String,
    onBrowserUrlChanged: (String) -> Unit,
    onOpenUrl: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    BoxWithConstraints(modifier = modifier.fillMaxSize()) {
        val totalWidth = maxWidth
        val hasRightPanel = true // Always has Browser, and optionally SFTP/Review

        val terminalWidth = if (hasRightPanel) {
            if (foldingFeature != null && foldingFeature.isSeparating) {
                val foldDp = foldingFeature.bounds.left.dp
                if (foldDp > 0.dp && foldDp < totalWidth) foldDp else totalWidth * 0.6f
            } else {
                totalWidth * 0.6f
            }
        } else {
            totalWidth
        }
        val rightPanelWidth = totalWidth - terminalWidth

        Row(modifier = Modifier.fillMaxSize()) {
            Column(modifier = Modifier.width(terminalWidth)) {
                Box(modifier = Modifier.weight(1f)) {
                    TerminalFocusWrapper(
                        session = session,
                        extraKeysController = extraKeysController,
                        isTerminalActive = true,
                        onViewCreated = onViewCreated,
                        onViewReleased = onViewReleased,
                        onOpenUrl = onOpenUrl
                    )
                }
                if (extraKeysEnabled) {
                    ExtraKeysToolbar(
                        extraKeysController = extraKeysController,
                        getActiveTerminalView = getActiveTerminalView,
                        session = session,
                        extraKeysJson = extraKeysJson
                    )
                }
            }
            if (hasRightPanel) {
                VerticalDivider(modifier = Modifier.fillMaxHeight(), thickness = 1.dp, color = MaterialTheme.colorScheme.outline)
                Column(modifier = Modifier.width(rightPanelWidth).fillMaxHeight()) {
                    val rightTabs = remember(sftpViewModel, reviewViewModel) {
                        buildList {
                            if (sftpViewModel != null) {
                                add("SFTP Explorer")
                            }
                            if (reviewViewModel != null) {
                                add("Review")
                            }
                            add("Browser")
                        }
                    }

                    var rightActivePage by remember { mutableIntStateOf(0) }
                    var isFirstCompose by remember { mutableStateOf(true) }

                    LaunchedEffect(rightTabs) {
                        if (rightActivePage >= rightTabs.size) {
                            rightActivePage = 0
                        }
                    }

                    LaunchedEffect(browserUrl) {
                        if (isFirstCompose) {
                            isFirstCompose = false
                            return@LaunchedEffect
                        }
                        if (browserUrl.isNotEmpty() && browserUrl != "https://google.com") {
                            val idx = rightTabs.indexOf("Browser")
                            if (idx != -1) {
                                rightActivePage = idx
                            }
                        }
                    }

                    if (rightTabs.size > 1) {
                        TabRow(
                            selectedTabIndex = rightActivePage,
                            containerColor = MaterialTheme.colorScheme.surface,
                            contentColor = MaterialTheme.colorScheme.onSurface
                        ) {
                            rightTabs.forEachIndexed { index, title ->
                                Tab(
                                    selected = rightActivePage == index,
                                    onClick = { rightActivePage = index },
                                    text = { Text(title) }
                                )
                            }
                        }
                    }

                    Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
                        val activeTab = rightTabs.getOrNull(rightActivePage)
                        when (activeTab) {
                            "SFTP Explorer" -> {
                                if (sftpViewModel != null) {
                                    SftpFileBrowser(
                                        viewModel = sftpViewModel,
                                        onOpenFile = onOpenFile,
                                        onOpenFileError = onOpenFileError
                                    )
                                }
                            }
                            "Review" -> {
                                if (reviewViewModel != null) {
                                    GitReviewScreen(
                                        viewModel = reviewViewModel,
                                        modifier = Modifier.fillMaxSize()
                                    )
                                }
                            }
                            "Browser" -> {
                                InAppBrowser(
                                    webView = webView,
                                    initialUrl = browserUrl,
                                    onUrlChanged = onBrowserUrlChanged,
                                    modifier = Modifier.fillMaxSize()
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
fun TerminalWorkspaceScreen(
    session: TerminalSession,
    sftpViewModel: SftpViewModel?,
    reviewViewModel: ReviewViewModel?,
    extraKeysEnabled: Boolean,
    extraKeysJson: String,
    onViewCreated: (TerminalView) -> Unit,
    onViewReleased: (TerminalView) -> Unit,
    activePage: Int,
    onPageSelected: (Int) -> Unit,
    onOpenFile: (File) -> Unit,
    onOpenFileError: (String) -> Unit,
    browserUrl: String,
    onBrowserUrlChanged: (String) -> Unit,
    onOpenUrl: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val browserWebView = remember(context) {
        WebView(context).apply {
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.useWideViewPort = true
            settings.loadWithOverviewMode = true
            settings.supportZoom()
            settings.builtInZoomControls = true
            settings.displayZoomControls = false
        }
    }
    val configuration = LocalConfiguration.current
    val isWideScreen = configuration.screenWidthDp >= 600

    val extraKeysController = remember { ExtraKeysController() }
    val activeTerminalViewRef = remember { Ref<TerminalView>() }
    val getActiveTerminalView = remember { { activeTerminalViewRef.value } }

    var foldingFeature by remember { mutableStateOf<FoldingFeature?>(null) }
    LaunchedEffect(context) {
        val activity = context as? Activity ?: return@LaunchedEffect
        WindowInfoTracker.getOrCreate(context)
            .windowLayoutInfo(activity)
            .collect { layoutInfo ->
                foldingFeature = layoutInfo.displayFeatures
                    .filterIsInstance<FoldingFeature>()
                    .firstOrNull()
            }
    }

    val handleViewCreated: (TerminalView) -> Unit = { view ->
        activeTerminalViewRef.value = view
        onViewCreated(view)
    }

    val handleViewReleased: (TerminalView) -> Unit = { view ->
        if (activeTerminalViewRef.value === view) {
            activeTerminalViewRef.value = null
        }
        onViewReleased(view)
    }

    if (isWideScreen) {
        SplitWorkspace(
            webView = browserWebView,
            session = session,
            sftpViewModel = sftpViewModel,
            reviewViewModel = reviewViewModel,
            foldingFeature = foldingFeature,
            extraKeysController = extraKeysController,
            getActiveTerminalView = getActiveTerminalView,
            extraKeysEnabled = extraKeysEnabled,
            extraKeysJson = extraKeysJson,
            onViewCreated = handleViewCreated,
            onViewReleased = handleViewReleased,
            onOpenFile = onOpenFile,
            onOpenFileError = onOpenFileError,
            browserUrl = browserUrl,
            onBrowserUrlChanged = onBrowserUrlChanged,
            onOpenUrl = onOpenUrl,
            modifier = modifier
        )
    } else {
        TabbedWorkspace(
            webView = browserWebView,
            session = session,
            sftpViewModel = sftpViewModel,
            reviewViewModel = reviewViewModel,
            extraKeysController = extraKeysController,
            getActiveTerminalView = getActiveTerminalView,
            extraKeysEnabled = extraKeysEnabled,
            extraKeysJson = extraKeysJson,
            onViewCreated = handleViewCreated,
            onViewReleased = handleViewReleased,
            activePage = activePage,
            onPageSelected = onPageSelected,
            onOpenFile = onOpenFile,
            onOpenFileError = onOpenFileError,
            browserUrl = browserUrl,
            onBrowserUrlChanged = onBrowserUrlChanged,
            onOpenUrl = onOpenUrl,
            modifier = modifier
        )
    }
}
