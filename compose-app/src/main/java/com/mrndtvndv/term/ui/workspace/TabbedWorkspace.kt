package com.mrndtvndv.term.ui.workspace

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
import androidx.compose.ui.unit.dp
import com.termux.terminal.TerminalSession
import com.mrndtvndv.term.ui.sftp.SftpFileBrowser
import com.mrndtvndv.term.ui.sftp.SftpViewModel
import com.mrndtvndv.term.ui.keyboard.ExtraKeysToolbar
import com.mrndtvndv.term.ui.keyboard.ExtraKeysController
import com.termux.view.TerminalView
import com.mrndtvndv.term.ui.browser.InAppBrowser
import com.mrndtvndv.term.ui.review.ReviewViewModel
import com.mrndtvndv.term.ui.review.GitReviewScreen
import kotlinx.coroutines.launch
import java.io.File

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun TabbedWorkspace(
    getWebView: () -> WebView,
    session: TerminalSession,
    sftpViewModel: SftpViewModel?,
    reviewViewModel: ReviewViewModel?,
    extraKeysController: ExtraKeysController,
    getActiveTerminalView: () -> TerminalView?,
    extraKeysEnabled: Boolean,
    extraKeysJson: String,
    onViewCreated: (TerminalView) -> Unit,
    onViewReleased: (TerminalView) -> Unit,
    activeTab: WorkspaceTab,
    onTabSelected: (WorkspaceTab) -> Unit,
    onOpenFile: (File) -> Unit,
    onOpenFileError: (String) -> Unit,
    browserUrl: String,
    onBrowserUrlChanged: (String) -> Unit,
    onOpenUrl: (String) -> Unit,
    onRefreshWorkspace: () -> Unit,
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

    val activePageIndex = remember(activeTabs, activeTab) {
        activeTabs.indexOf(activeTab).takeIf { it >= 0 } ?: 0
    }

    val pagerState = rememberPagerState(
        initialPage = activePageIndex,
        pageCount = { activeTabs.size }
    )
    val coroutineScope = rememberCoroutineScope()

    // Sync pagerState when external activeTab changes (e.g. back button pressed in MainActivity)
    LaunchedEffect(activePageIndex) {
        if (pagerState.currentPage != activePageIndex && !pagerState.isScrollInProgress) {
            pagerState.animateScrollToPage(activePageIndex)
        }
    }

    // Sync external activeTab when pager is swiped by the user
    LaunchedEffect(pagerState.settledPage, activeTabs) {
        val tab = activeTabs.getOrNull(pagerState.settledPage) ?: WorkspaceTab.Terminal
        if (tab != activeTab) {
            onTabSelected(tab)
        }
    }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        topBar = {
            TabRow(
                selectedTabIndex = pagerState.currentPage,
                containerColor = MaterialTheme.colorScheme.surface,
                contentColor = MaterialTheme.colorScheme.onSurface,
                indicator = { tabPositions ->
                    if (pagerState.currentPage < tabPositions.size) {
                        TabRowDefaults.SecondaryIndicator(
                            modifier = Modifier.tabIndicatorOffset(tabPositions[pagerState.currentPage]),
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                }
            ) {
                activeTabs.forEachIndexed { index, tab ->
                    val isSelected = pagerState.currentPage == index
                    Tab(
                        selected = isSelected,
                        onClick = {
                            if (tab == WorkspaceTab.Sftp || tab == WorkspaceTab.Review) {
                                onRefreshWorkspace()
                            }
                            onTabSelected(tab)
                            coroutineScope.launch {
                                pagerState.animateScrollToPage(index)
                            }
                        },
                        selectedContentColor = MaterialTheme.colorScheme.primary,
                        unselectedContentColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                        text = {
                            Text(
                                text = tab.title,
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
            beyondViewportPageCount = 2,
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
                                    onOpenUrl = { url ->
                                        onOpenUrl(url)
                                        if (activeTabs.contains(WorkspaceTab.Browser)) {
                                            onTabSelected(WorkspaceTab.Browser)
                                        }
                                    }
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
                            getWebView = getWebView,
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
