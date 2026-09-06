package com.mrndtvndv.term.ui.workspace

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.isImeVisible
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.systemGestureExclusion
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.mrndtvndv.term.ui.keyboard.SoftKeyboardState
import com.mrndtvndv.term.ui.keyboard.SoftKeyboardVisibilityTracker
import com.termux.terminal.TerminalSession
import com.mrndtvndv.term.ui.sftp.SftpFileBrowser
import com.mrndtvndv.term.ui.sftp.SftpViewModel
import com.mrndtvndv.term.ui.keyboard.ExtraKeysToolbar
import com.mrndtvndv.term.ui.keyboard.ExtraKeysController
import com.termux.terminal.compose.TerminalBackend
import com.mrndtvndv.term.server.TerminalProgress
import com.mrndtvndv.term.server.HerdrWorkspaceResolver
import com.mrndtvndv.term.ui.review.ReviewViewModel
import com.mrndtvndv.term.ui.review.GitReviewScreen
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.pager.PagerDefaults
import androidx.compose.foundation.pager.PagerSnapDistance
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.unit.Velocity
import kotlinx.coroutines.launch
import java.io.File

@OptIn(ExperimentalFoundationApi::class, ExperimentalLayoutApi::class)
@Suppress("LongParameterList", "LongMethod", "CyclomaticComplexMethod")
@Composable
fun TabbedWorkspace(
    session: TerminalSession,
    terminalProgress: TerminalProgress?,
    onUploadImage: () -> Unit,
    sftpViewModel: SftpViewModel?,
    reviewViewModel: ReviewViewModel?,
    extraKeysController: ExtraKeysController,
    extraKeysEnabled: Boolean,
    extraKeysJson: String,
    hideTabs: Boolean = false,
    herdrEnabled: Boolean = false,
    herdrWorkspaces: List<HerdrWorkspaceResolver.HerdrWorkspaceNode> = emptyList(),
    herdrWorkspacesLoading: Boolean = false,
    herdrWorkspacesError: String? = null,
    onLoadHerdrAgents: () -> Unit = {},
    onFocusHerdrTab: (HerdrWorkspaceResolver.HerdrTabNode) -> Unit = {},
    onFocusHerdrPane: (HerdrWorkspaceResolver.HerdrPaneNode) -> Unit = {},
    onCloseHerdrPane: (HerdrWorkspaceResolver.HerdrPaneNode) -> Unit = {},
    herdrAgentFabOpacity: Float = 0.7f,
    rememberSoftKeyboardState: Boolean = false,
    lastSoftKeyboardState: SoftKeyboardState = SoftKeyboardState.UNKNOWN,
    onKeyboardVisibilityChanged: (Boolean) -> Unit = {},
    showKeyboardFab: Boolean = false,
    hideKeyboardFabWhileTyping: Boolean = true,
    onBackendCreated: (TerminalSession, TerminalBackend) -> Unit,
    onBackendReleased: (TerminalSession, TerminalBackend) -> Unit,
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
    val flingBehavior = PagerDefaults.flingBehavior(
        state = pagerState,
        pagerSnapDistance = PagerSnapDistance.atMost(1),
    )
    val coroutineScope = rememberCoroutineScope()
    val isLifecycleResumed by rememberLifecycleResumed()
    val imeVisible = WindowInsets.isImeVisible
    val keyboardVisibilityTracker = remember(session) { SoftKeyboardVisibilityTracker() }
    val currentActiveTab = rememberUpdatedState(activeTab)
    val currentLifecycleResumed = rememberUpdatedState(isLifecycleResumed)
    val currentRememberKeyboardState = rememberUpdatedState(rememberSoftKeyboardState)
    val currentOnKeyboardVisibilityChanged = rememberUpdatedState(onKeyboardVisibilityChanged)
    val reportKeyboardVisibility: (Boolean) -> Unit = remember(session) {
        { isVisible: Boolean ->
            val visibilityToPersist = keyboardVisibilityTracker.observe(
                isVisible = isVisible,
                isTerminalActive = currentActiveTab.value == WorkspaceTab.Terminal,
                isLifecycleResumed = currentLifecycleResumed.value
            )
            if (visibilityToPersist != null && currentRememberKeyboardState.value) {
                currentOnKeyboardVisibilityChanged.value(visibilityToPersist)
            }
        }
    }
    val currentLastSoftKeyboardState = rememberUpdatedState(lastSoftKeyboardState)
    LaunchedEffect(session, activeTab, rememberSoftKeyboardState, isLifecycleResumed) {
        if (!rememberSoftKeyboardState) return@LaunchedEffect
        if (activeTab != WorkspaceTab.Terminal || !isLifecycleResumed) return@LaunchedEffect
        val stateToRestore = currentLastSoftKeyboardState.value
        if (stateToRestore == SoftKeyboardState.UNKNOWN) return@LaunchedEffect
        withFrameNanos { }
        if (stateToRestore == SoftKeyboardState.VISIBLE) {
            extraKeysController.requestShowKeyboard()
        } else {
            extraKeysController.requestHideKeyboard()
        }
    }

    // On non-Terminal pages, rightward swipes near the left screen edge would be
    // captured by the system back gesture (predictive back, API 29+) instead of the
    // pager — e.g. SFTP→Git becomes a back press that navigates to the Terminal tab.
    // Hand the edge zone to the pager so those swipes always switch pages.
    val excludeFromSystemGesture = pagerState.currentPage != 0

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
        if (pagerState.currentPage != activePageIndex && !pagerState.isScrollInProgress) {
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
            flingBehavior = flingBehavior,
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
        ) { page ->
            if (page < activeTabs.size) {
                when (activeTabs[page]) {
                    WorkspaceTab.Terminal -> {
                        Column(modifier = Modifier.fillMaxSize()) {
                            TerminalProgressStrip(progress = terminalProgress)
                            Box(modifier = Modifier.weight(1f)) {
                                TerminalFocusWrapper(
                                    session = session,
                                    extraKeysController = extraKeysController,
                                    onUploadImage = onUploadImage,
                                    isTerminalActive = activeTab == WorkspaceTab.Terminal,
                                    onBackendCreated = onBackendCreated,
                                    onBackendReleased = onBackendReleased,
                                    onOpenUrl = onOpenUrl,
                                    onKeyboardVisibilityChanged = reportKeyboardVisibility
                                )
                                if (showKeyboardFab || herdrEnabled) {
                                    Column(
                                        modifier = Modifier
                                            .align(Alignment.BottomEnd)
                                            .padding(end = 12.dp, bottom = 8.dp),
                                        verticalArrangement = Arrangement.spacedBy(8.dp),
                                        horizontalAlignment = Alignment.End
                                    ) {
                                        if (
                                            isKeyboardFabVisible(
                                                showKeyboardFab,
                                                hideKeyboardFabWhileTyping,
                                                imeVisible
                                            )
                                        ) {
                                            KeyboardToggleFab(
                                                isKeyboardVisible = imeVisible,
                                                fabOpacity = herdrAgentFabOpacity,
                                                onToggle = { visible ->
                                                    extraKeysController.toggleKeyboard(visible)
                                                }
                                            )
                                        }
                                        if (herdrEnabled) {
                                            HerdrAgentButton(
                                                workspaces = herdrWorkspaces,
                                                isLoading = herdrWorkspacesLoading,
                                                error = herdrWorkspacesError,
                                                onRefresh = onLoadHerdrAgents,
                                                onFocusTab = onFocusHerdrTab,
                                                onFocusPane = onFocusHerdrPane,
                                                onClosePane = onCloseHerdrPane,
                                                fabOpacity = herdrAgentFabOpacity
                                            )
                                        }
                                    }
                                }
                            }
                            if (extraKeysEnabled) {
                                ExtraKeysToolbar(
                                    extraKeysController = extraKeysController,
                                    session = session,
                                    extraKeysJson = extraKeysJson,
                                    onToggleKeyboard = {
                                        extraKeysController.toggleKeyboard(imeVisible)
                                    },
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

@Composable
private fun rememberLifecycleResumed(): State<Boolean> {
    val lifecycleOwner = LocalLifecycleOwner.current
    val lifecycle = lifecycleOwner.lifecycle
    val isResumed = remember(lifecycleOwner) {
        mutableStateOf(lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED))
    }
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            isResumed.value = when (event) {
                Lifecycle.Event.ON_RESUME -> true
                Lifecycle.Event.ON_PAUSE,
                Lifecycle.Event.ON_STOP,
                Lifecycle.Event.ON_DESTROY -> false
                else -> lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)
            }
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }
    return isResumed
}
