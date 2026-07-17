package com.mrndtvndv.term.ui.workspace

import android.app.Activity
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.material3.TabRowDefaults.tabIndicatorOffset
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
import com.termux.view.TerminalView
import kotlinx.coroutines.flow.filterIsInstance

class Ref<T>(var value: T? = null)

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
    modifier: Modifier = Modifier
) {
    Column(modifier = modifier.fillMaxSize()) {
        if (sftpViewModel != null) {
            TabRow(
                selectedTabIndex = activePage,
                containerColor = MaterialTheme.colorScheme.surfaceVariant,
                contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
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
                    unselectedContentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                    text = { Text("Terminal") }
                )
                Tab(
                    selected = activePage == 1,
                    onClick = { onPageSelected(1) },
                    selectedContentColor = MaterialTheme.colorScheme.primary,
                    unselectedContentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                    text = { Text("SFTP Explorer") }
                )
            }
        }

        Box(modifier = Modifier.weight(1f)) {
            if (activePage == 0) {
                Column(modifier = Modifier.fillMaxSize()) {
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
                            getActiveTerminalView = getActiveTerminalView,
                            session = session,
                            extraKeysJson = extraKeysJson
                        )
                    }
                }
            } else if (activePage == 1 && sftpViewModel != null) {
                SftpFileBrowser(viewModel = sftpViewModel!!)
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
    sftpVisible: Boolean,
    modifier: Modifier = Modifier
) {
    BoxWithConstraints(modifier = modifier.fillMaxSize()) {
        val totalWidth = maxWidth
        val isSftpOpen = sftpViewModel != null && sftpVisible

        val terminalWidth = if (isSftpOpen) {
            if (foldingFeature != null && foldingFeature.isSeparating) {
                val foldDp = foldingFeature.bounds.left.dp
                if (foldDp > 0.dp && foldDp < totalWidth) foldDp else totalWidth * 0.6f
            } else {
                totalWidth * 0.6f
            }
        } else {
            totalWidth
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
                        getActiveTerminalView = getActiveTerminalView,
                        session = session,
                        extraKeysJson = extraKeysJson
                    )
                }
            }
            if (isSftpOpen) {
                VerticalDivider(modifier = Modifier.fillMaxHeight(), thickness = 1.dp, color = MaterialTheme.colorScheme.outline)
                Box(modifier = Modifier.width(sftpWidth)) {
                    SftpFileBrowser(viewModel = sftpViewModel!!)
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
    sftpVisible: Boolean,
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
            sftpVisible = sftpVisible,
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
            modifier = modifier
        )
    }
}
