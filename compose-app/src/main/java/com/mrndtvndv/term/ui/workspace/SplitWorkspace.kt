package com.mrndtvndv.term.ui.workspace

import android.webkit.WebView
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.window.layout.FoldingFeature
import com.termux.terminal.TerminalSession
import com.mrndtvndv.term.ui.sftp.SftpFileBrowser
import com.mrndtvndv.term.ui.sftp.SftpViewModel
import com.mrndtvndv.term.ui.keyboard.ExtraKeysToolbar
import com.mrndtvndv.term.ui.keyboard.ExtraKeysController
import com.termux.view.TerminalView
import com.mrndtvndv.term.ui.browser.InAppBrowser
import com.mrndtvndv.term.ui.review.ReviewViewModel
import com.mrndtvndv.term.ui.review.GitReviewScreen
import java.io.File

@Composable
fun SplitWorkspace(
    getWebView: () -> WebView,
    session: TerminalSession,
    isLocal: Boolean = false,
    sftpViewModel: SftpViewModel?,
    reviewViewModel: ReviewViewModel?,
    foldingFeature: FoldingFeature?,
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
    BoxWithConstraints(modifier = modifier.fillMaxSize()) {
        val totalWidth = maxWidth
        val hasRightPanel = true // Always has Browser, and optionally SFTP/Review

        val rightTabs = remember(sftpViewModel, reviewViewModel, isLocal) {
            buildList {
                if (reviewViewModel != null) {
                    add(WorkspaceTab.Review)
                }
                if (sftpViewModel != null) {
                    add(WorkspaceTab.Sftp)
                }
                if (!isLocal) {
                    add(WorkspaceTab.Browser)
                }
            }
        }

        val rightActiveTab = remember(activeTab, rightTabs) {
            if (rightTabs.contains(activeTab)) {
                activeTab
            } else {
                rightTabs.firstOrNull { it == WorkspaceTab.Browser } ?: rightTabs.first()
            }
        }
        val rightActiveIndex = rightTabs.indexOf(rightActiveTab).takeIf { it >= 0 } ?: 0

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
                        onOpenUrl = { url ->
                            onOpenUrl(url)
                            if (rightTabs.contains(WorkspaceTab.Browser)) {
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
            if (hasRightPanel) {
                VerticalDivider(modifier = Modifier.fillMaxHeight(), thickness = 1.dp, color = MaterialTheme.colorScheme.outline)
                Column(modifier = Modifier.width(rightPanelWidth).fillMaxHeight()) {
                    if (rightTabs.size > 1) {
                        SecondaryTabRow(
                            selectedTabIndex = rightActiveIndex,
                            containerColor = MaterialTheme.colorScheme.surface,
                            contentColor = MaterialTheme.colorScheme.onSurface
                        ) {
                            rightTabs.forEachIndexed { index, tab ->
                                Tab(
                                    selected = rightActiveIndex == index,
                                    onClick = {
                                        if (tab == WorkspaceTab.Sftp || tab == WorkspaceTab.Review) {
                                            onRefreshWorkspace()
                                        }
                                        onTabSelected(tab)
                                    },
                                    text = { Text(tab.title) }
                                )
                            }
                        }
                    }

                    Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
                        when (rightActiveTab) {
                            WorkspaceTab.Sftp -> {
                                if (sftpViewModel != null) {
                                    SftpFileBrowser(
                                        viewModel = sftpViewModel,
                                        isTabActive = rightActiveTab == WorkspaceTab.Sftp,
                                        onOpenFile = onOpenFile,
                                        onOpenFileError = onOpenFileError
                                    )
                                }
                            }
                            WorkspaceTab.Review -> {
                                if (reviewViewModel != null) {
                                    GitReviewScreen(
                                        viewModel = reviewViewModel,
                                        isTabActive = rightActiveTab == WorkspaceTab.Review,
                                        modifier = Modifier.fillMaxSize()
                                    )
                                }
                            }
                            WorkspaceTab.Browser -> {
                                InAppBrowser(
                                    getWebView = getWebView,
                                    initialUrl = browserUrl,
                                    onUrlChanged = onBrowserUrlChanged,
                                    isTabActive = rightActiveTab == WorkspaceTab.Browser,
                                    modifier = Modifier.fillMaxSize()
                                )
                            }
                            WorkspaceTab.Terminal -> {
                                // Right pane does not support terminal
                            }
                        }
                    }
                }
            }
        }
    }
}
