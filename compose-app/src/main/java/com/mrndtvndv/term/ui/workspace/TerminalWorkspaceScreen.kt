package com.mrndtvndv.term.ui.workspace

import android.app.Activity
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
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
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.launch

import com.termux.view.TerminalView

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun TabbedWorkspace(
    session: TerminalSession,
    sftpViewModel: SftpViewModel,
    extraKeysController: ExtraKeysController,
    activeTerminalView: TerminalView?,
    extraKeysEnabled: Boolean,
    extraKeysJson: String,
    onViewCreated: (TerminalView) -> Unit,
    onViewReleased: () -> Unit,
    modifier: Modifier = Modifier
) {
    val pagerState = rememberPagerState(pageCount = { 2 })
    val coroutineScope = rememberCoroutineScope()
    val tabs = listOf("Terminal", "SFTP Explorer")

    Column(modifier = modifier.fillMaxSize()) {
        TabRow(selectedTabIndex = pagerState.currentPage) {
            tabs.forEachIndexed { index, title ->
                Tab(
                    selected = pagerState.currentPage == index,
                    onClick = {
                        coroutineScope.launch { pagerState.animateScrollToPage(index) }
                    },
                    text = { Text(title) }
                )
            }
        }

        HorizontalPager(
            state = pagerState,
            userScrollEnabled = false,
            beyondBoundsPageCount = 1,
            modifier = Modifier.weight(1f)
        ) { page ->
            when (page) {
                0 -> Column(modifier = Modifier.fillMaxSize()) {
                    Box(modifier = Modifier.weight(1f)) {
                        TerminalFocusWrapper(
                            session = session,
                            extraKeysController = extraKeysController,
                            isTerminalActive = pagerState.currentPage == 0,
                            onViewCreated = onViewCreated,
                            onViewReleased = onViewReleased
                        )
                    }
                    if (extraKeysEnabled) {
                        ExtraKeysToolbar(
                            extraKeysController = extraKeysController,
                            activeTerminalView = activeTerminalView,
                            session = session,
                            extraKeysJson = extraKeysJson
                        )
                    }
                }
                1 -> SftpFileBrowser(viewModel = sftpViewModel)
            }
        }
    }
}

@Composable
fun SplitWorkspace(
    session: TerminalSession,
    sftpViewModel: SftpViewModel,
    foldingFeature: FoldingFeature?,
    extraKeysController: ExtraKeysController,
    activeTerminalView: TerminalView?,
    extraKeysEnabled: Boolean,
    extraKeysJson: String,
    onViewCreated: (TerminalView) -> Unit,
    onViewReleased: () -> Unit,
    modifier: Modifier = Modifier
) {
    BoxWithConstraints(modifier = modifier.fillMaxSize()) {
        val totalWidth = maxWidth

        val terminalWidth = if (foldingFeature != null && foldingFeature.isSeparating) {
            val foldDp = foldingFeature.bounds.left.dp
            if (foldDp > 0.dp && foldDp < totalWidth) foldDp else totalWidth * 0.6f
        } else {
            totalWidth * 0.6f
        }
        val sftpWidth = totalWidth - terminalWidth

        Row(modifier = Modifier.fillMaxSize()) {
            Column(modifier = Modifier.width(terminalWidth)) {
                Box(modifier = Modifier.weight(1f)) {
                    TerminalFocusWrapper(
                        session = session,
                        extraKeysController = extraKeysController,
                        isTerminalActive = true,
                        onViewCreated = onViewCreated,
                        onViewReleased = onViewReleased
                    )
                }
                if (extraKeysEnabled) {
                    ExtraKeysToolbar(
                        extraKeysController = extraKeysController,
                        activeTerminalView = activeTerminalView,
                        session = session,
                        extraKeysJson = extraKeysJson
                    )
                }
            }
            VerticalDivider(modifier = Modifier.fillMaxHeight(), thickness = 1.dp, color = MaterialTheme.colorScheme.outline)
            Box(modifier = Modifier.width(sftpWidth)) {
                SftpFileBrowser(viewModel = sftpViewModel)
            }
        }
    }
}

@Composable
fun TerminalWorkspaceScreen(
    session: TerminalSession,
    sftpViewModel: SftpViewModel,
    extraKeysEnabled: Boolean,
    extraKeysJson: String,
    onViewCreated: (TerminalView) -> Unit,
    onViewReleased: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val configuration = LocalConfiguration.current
    val isWideScreen = configuration.screenWidthDp >= 600

    val extraKeysController = remember { ExtraKeysController() }
    var activeTerminalView by remember { mutableStateOf<TerminalView?>(null) }

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
        activeTerminalView = view
        onViewCreated(view)
    }

    val handleViewReleased: () -> Unit = {
        activeTerminalView = null
        onViewReleased()
    }

    if (isWideScreen) {
        SplitWorkspace(
            session = session,
            sftpViewModel = sftpViewModel,
            foldingFeature = foldingFeature,
            extraKeysController = extraKeysController,
            activeTerminalView = activeTerminalView,
            extraKeysEnabled = extraKeysEnabled,
            extraKeysJson = extraKeysJson,
            onViewCreated = handleViewCreated,
            onViewReleased = handleViewReleased,
            modifier = modifier
        )
    } else {
        TabbedWorkspace(
            session = session,
            sftpViewModel = sftpViewModel,
            extraKeysController = extraKeysController,
            activeTerminalView = activeTerminalView,
            extraKeysEnabled = extraKeysEnabled,
            extraKeysJson = extraKeysJson,
            onViewCreated = handleViewCreated,
            onViewReleased = handleViewReleased,
            modifier = modifier
        )
    }
}
