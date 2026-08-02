package com.mrndtvndv.term.ui.workspace

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.systemGestureExclusion
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material3.*
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
import com.mrndtvndv.term.ui.review.ReviewViewModel
import com.mrndtvndv.term.ui.review.GitReviewScreen
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.pager.PagerDefaults
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalViewConfiguration
import androidx.compose.ui.unit.Velocity
import kotlinx.coroutines.launch
import java.io.File

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun TabbedWorkspace(
    session: TerminalSession,
    sftpViewModel: SftpViewModel?,
    reviewViewModel: ReviewViewModel?,
    extraKeysController: ExtraKeysController,
    getActiveTerminalView: () -> TerminalView?,
    extraKeysEnabled: Boolean,
    extraKeysJson: String,
    hideTabs: Boolean = false,
    onViewCreated: (TerminalView) -> Unit,
    onViewReleased: (TerminalView) -> Unit,
    activeTab: WorkspaceTab,
    onTabSelected: (WorkspaceTab) -> Unit,
    onOpenFile: (File) -> Unit,
    onOpenFileError: (String) -> Unit,
    onOpenUrl: (String) -> Unit,
    onRefreshWorkspace: () -> Unit,
    modifier: Modifier = Modifier
) {
    val activeTabs = remember(sftpViewModel, reviewViewModel) {
        buildList {
            add(WorkspaceTab.Terminal)
            if (reviewViewModel != null) {
                add(WorkspaceTab.Review)
            }
            if (sftpViewModel != null) {
                add(WorkspaceTab.Sftp)
            }
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

    // On non-Terminal pages, rightward swipes near the left screen edge would be
    // captured by the system back gesture (predictive back, API 29+) instead of the
    // pager — e.g. SFTP→Git becomes a back press that navigates to the Terminal tab.
    // Hand the edge zone to the pager so those swipes always switch pages.
    val excludeFromSystemGesture = pagerState.currentPage != 0

    var isPagerScrollAllowed by remember { mutableStateOf(true) }
    val viewConfiguration = LocalViewConfiguration.current
    val touchSlop = viewConfiguration.touchSlop

    // pageNestedScrollConnection is @Composable in foundation 1.11+, so it must be
    // called from the composable body, not inside remember {} or the object expression.
    val defaultPagerNestedScrollConnection = PagerDefaults.pageNestedScrollConnection(
        pagerState,
        Orientation.Horizontal
    )
    val pageNestedScrollConnection = remember(pagerState) {
        object : NestedScrollConnection {
            private val defaultConn = defaultPagerNestedScrollConnection

            override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
                if (kotlin.math.abs(available.x) > kotlin.math.abs(available.y) * 1.5f) {
                    return defaultConn.onPreScroll(available, source)
                }
                return Offset.Zero
            }

            override fun onPostScroll(
                consumed: Offset,
                available: Offset,
                source: NestedScrollSource
            ): Offset {
                if (kotlin.math.abs(available.x) > kotlin.math.abs(available.y) * 1.5f) {
                    return defaultConn.onPostScroll(consumed, available, source)
                }
                return Offset.Zero
            }

            override suspend fun onPreFling(available: Velocity): Velocity {
                if (kotlin.math.abs(available.x) > kotlin.math.abs(available.y) * 1.5f) {
                    return defaultConn.onPreFling(available)
                }
                return Velocity.Zero
            }

            override suspend fun onPostFling(consumed: Velocity, available: Velocity): Velocity {
                if (kotlin.math.abs(available.x) > kotlin.math.abs(available.y) * 1.5f) {
                    return defaultConn.onPostFling(consumed, available)
                }
                return Velocity.Zero
            }
        }
    }

    // Sync pagerState when activeTab changes
    LaunchedEffect(activePageIndex) {
        if (pagerState.currentPage != activePageIndex) {
            pagerState.animateScrollToPage(activePageIndex)
        }
    }

    // Sync external activeTab when pager is swiped by the user
    LaunchedEffect(pagerState.settledPage, activeTabs) {
        val tab = activeTabs.getOrNull(pagerState.settledPage) ?: WorkspaceTab.Terminal
        if (tab != activeTab) {
            if (tab == WorkspaceTab.Sftp || tab == WorkspaceTab.Review) {
                onRefreshWorkspace()
            }
            onTabSelected(tab)
        }
    }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = {
            if (activeTabs.size > 1 && !hideTabs) {
                SecondaryTabRow(
                    modifier = Modifier.statusBarsPadding(),
                    selectedTabIndex = pagerState.currentPage,
                    containerColor = MaterialTheme.colorScheme.surface,
                    contentColor = MaterialTheme.colorScheme.onSurface,
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
        }
    ) { paddingValues ->
        HorizontalPager(
            state = pagerState,
            userScrollEnabled = isPagerScrollAllowed,
            pageNestedScrollConnection = pageNestedScrollConnection,
            key = { index -> activeTabs.getOrNull(index)?.let { "${index}_${it.title}" } ?: index.toString() },
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .then(if (activeTabs.size <= 1 || hideTabs) Modifier.statusBarsPadding() else Modifier)
                .navigationBarsPadding()
                .imePadding()
                .clipToBounds()
                .then(if (excludeFromSystemGesture) Modifier.systemGestureExclusion() else Modifier)
                .pointerInput(touchSlop) {
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        var isLocked = false
                        do {
                            val event = awaitPointerEvent(PointerEventPass.Initial)
                            if (!isLocked) {
                                val pointer = event.changes.firstOrNull { it.id == down.id }
                                    ?: event.changes.firstOrNull()
                                if (pointer != null) {
                                    val dx = kotlin.math.abs(pointer.position.x - down.position.x)
                                    val dy = kotlin.math.abs(pointer.position.y - down.position.y)
                                    val dist = kotlin.math.sqrt(dx * dx + dy * dy)
                                    if (dist > touchSlop) {
                                        isLocked = true
                                        if (dy > dx * 0.7f) {
                                            isPagerScrollAllowed = false
                                        }
                                    }
                                }
                            }
                        } while (event.changes.any { it.pressed })
                        isPagerScrollAllowed = true
                    }
                }
        ) { page ->
            if (page < activeTabs.size) {
                when (activeTabs[page]) {
                    WorkspaceTab.Terminal -> {
                        Column(modifier = Modifier.fillMaxSize()) {
                            Box(modifier = Modifier.weight(1f)) {
                                TerminalFocusWrapper(
                                    session = session,
                                    extraKeysController = extraKeysController,
                                    isTerminalActive = activeTab == WorkspaceTab.Terminal,
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
                                isTabActive = activeTab == WorkspaceTab.Sftp,
                                onOpenFile = onOpenFile,
                                onOpenFileError = onOpenFileError
                            )
                        }
                    }
                    WorkspaceTab.Review -> {
                        if (reviewViewModel != null) {
                            GitReviewScreen(
                                viewModel = reviewViewModel,
                                isTabActive = activeTab == WorkspaceTab.Review,
                                modifier = Modifier.fillMaxSize()
                            )
                        }
                    }
                }
            }
        }
    }
}
