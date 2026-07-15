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
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.launch

import com.termux.view.TerminalView

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun TabbedWorkspace(
    session: TerminalSession,
    sftpViewModel: SftpViewModel,
    ctrlActive: Boolean,
    altActive: Boolean,
    onExtraKeyClick: (String) -> Unit,
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
                            isTerminalActive = pagerState.currentPage == 0,
                            onViewCreated = onViewCreated,
                            onViewReleased = onViewReleased
                        )
                    }
                    ExtraKeysToolbar(
                        onKeyClick = onExtraKeyClick,
                        ctrlActive = ctrlActive,
                        altActive = altActive
                    )
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
    ctrlActive: Boolean,
    altActive: Boolean,
    onExtraKeyClick: (String) -> Unit,
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
                        isTerminalActive = true,
                        onViewCreated = onViewCreated,
                        onViewReleased = onViewReleased
                    )
                }
                ExtraKeysToolbar(
                    onKeyClick = onExtraKeyClick,
                    ctrlActive = ctrlActive,
                    altActive = altActive
                )
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
    onViewCreated: (TerminalView) -> Unit,
    onViewReleased: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val configuration = LocalConfiguration.current
    val isWideScreen = configuration.screenWidthDp >= 600

    var ctrlActive by remember { mutableStateOf(false) }
    var altActive by remember { mutableStateOf(false) }

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

    val onExtraKeyClick: (String) -> Unit = { key ->
        when (key) {
            "CTRL" -> ctrlActive = !ctrlActive
            "ALT" -> altActive = !altActive
            "ESC" -> session.write("\u001b")
            "TAB" -> session.write("\t")
            "◀" -> session.write("\u001b[D")
            "▲" -> session.write("\u001b[A")
            "▼" -> session.write("\u001b[B")
            "▶" -> session.write("\u001b[C")
            else -> session.write(key)
        }
    }

    if (isWideScreen) {
        SplitWorkspace(
            session = session,
            sftpViewModel = sftpViewModel,
            foldingFeature = foldingFeature,
            ctrlActive = ctrlActive,
            altActive = altActive,
            onExtraKeyClick = onExtraKeyClick,
            onViewCreated = onViewCreated,
            onViewReleased = onViewReleased,
            modifier = modifier
        )
    } else {
        TabbedWorkspace(
            session = session,
            sftpViewModel = sftpViewModel,
            ctrlActive = ctrlActive,
            altActive = altActive,
            onExtraKeyClick = onExtraKeyClick,
            onViewCreated = onViewCreated,
            onViewReleased = onViewReleased,
            modifier = modifier
        )
    }
}
