package com.mrndtvndv.term.ui.workspace

import android.app.Activity
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
import kotlinx.coroutines.flow.filterIsInstance
import java.io.File

class Ref<T>(var value: T? = null)

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun TabbedWorkspace(
    session: TerminalSession,
    sftpViewModel: SftpViewModel?,
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
    val pageCount = if (sftpViewModel != null) 3 else 2
    val pagerState = rememberPagerState(
        initialPage = activePage,
        pageCount = { pageCount }
    )

    // Synchronize pagerState with activePage selected from TabRow
    LaunchedEffect(activePage) {
        if (pagerState.currentPage != activePage && activePage < pageCount) {
            pagerState.scrollToPage(activePage)
        }
    }

    // Synchronize activePage with pagerState (e.g. when page is selected)
    LaunchedEffect(pagerState.currentPage) {
        if (pagerState.currentPage != activePage) {
            onPageSelected(pagerState.currentPage)
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
                Tab(
                    selected = activePage == 0,
                    onClick = { onPageSelected(0) },
                    selectedContentColor = MaterialTheme.colorScheme.primary,
                    unselectedContentColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                    text = {
                        Text(
                            text = "Terminal",
                            color = if (activePage == 0) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                            fontWeight = if (activePage == 0) FontWeight.Bold else FontWeight.Normal
                        )
                    }
                )
                if (sftpViewModel != null) {
                    Tab(
                        selected = activePage == 1,
                        onClick = { onPageSelected(1) },
                        selectedContentColor = MaterialTheme.colorScheme.primary,
                        unselectedContentColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                        text = {
                            Text(
                                text = "SFTP Explorer",
                                color = if (activePage == 1) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                                fontWeight = if (activePage == 1) FontWeight.Bold else FontWeight.Normal
                            )
                        }
                    )
                }
                val browserTabIdx = if (sftpViewModel != null) 2 else 1
                Tab(
                    selected = activePage == browserTabIdx,
                    onClick = { onPageSelected(browserTabIdx) },
                    selectedContentColor = MaterialTheme.colorScheme.primary,
                    unselectedContentColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                    text = {
                        Text(
                            text = "Browser",
                            color = if (activePage == browserTabIdx) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                            fontWeight = if (activePage == browserTabIdx) FontWeight.Bold else FontWeight.Normal
                        )
                    }
                )
            }
        }
    ) { paddingValues ->
        HorizontalPager(
            state = pagerState,
            userScrollEnabled = false, // Disable swipe so terminal selection / scrolling works
            beyondBoundsPageCount = 1,
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .clipToBounds()
        ) { page ->
            when (page) {
                0 -> {
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
                1 -> {
                    if (sftpViewModel != null) {
                        SftpFileBrowser(
                            viewModel = sftpViewModel,
                            onOpenFile = onOpenFile,
                            onOpenFileError = onOpenFileError
                        )
                    } else {
                        InAppBrowser(
                            initialUrl = browserUrl,
                            onUrlChanged = onBrowserUrlChanged,
                            modifier = Modifier.fillMaxSize()
                        )
                    }
                }
                2 -> {
                    if (sftpViewModel != null) {
                        InAppBrowser(
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
    session: TerminalSession,
    sftpViewModel: SftpViewModel?,
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
        val hasRightPanel = true // Always has Browser, and optionally SFTP

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
                    if (sftpViewModel != null) {
                        var rightActivePage by remember { mutableIntStateOf(0) }
                        LaunchedEffect(browserUrl) {
                            if (browserUrl.isNotEmpty() && browserUrl != "https://google.com") {
                                rightActivePage = 1
                            }
                        }
                        
                        TabRow(
                            selectedTabIndex = rightActivePage,
                            containerColor = MaterialTheme.colorScheme.surface,
                            contentColor = MaterialTheme.colorScheme.onSurface
                        ) {
                            Tab(
                                selected = rightActivePage == 0,
                                onClick = { rightActivePage = 0 },
                                text = { Text("SFTP Explorer") }
                            )
                            Tab(
                                selected = rightActivePage == 1,
                                onClick = { rightActivePage = 1 },
                                text = { Text("Browser") }
                            )
                        }
                        
                        Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
                            if (rightActivePage == 0) {
                                SftpFileBrowser(
                                    viewModel = sftpViewModel,
                                    onOpenFile = onOpenFile,
                                    onOpenFileError = onOpenFileError
                                )
                            } else {
                                InAppBrowser(
                                    initialUrl = browserUrl,
                                    onUrlChanged = onBrowserUrlChanged,
                                    modifier = Modifier.fillMaxSize()
                                )
                            }
                        }
                    } else {
                        InAppBrowser(
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
fun TerminalWorkspaceScreen(
    session: TerminalSession,
    sftpViewModel: SftpViewModel?,
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
            session = session,
            sftpViewModel = sftpViewModel,
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
            session = session,
            sftpViewModel = sftpViewModel,
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
