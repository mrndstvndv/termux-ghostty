package com.mrndtvndv.term.ui.workspace

import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PagerDefaults
import androidx.compose.foundation.pager.PagerSnapDistance
import androidx.compose.foundation.pager.PagerState
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.systemGestureExclusion
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SecondaryTabRow
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Velocity
import com.mrndtvndv.term.CrashBreadcrumbs
import kotlin.math.abs
import kotlinx.coroutines.launch

/**
 * Pages [tabs] horizontally. [currentTab] is the source of truth; the pager is a view of it
 * and reports user-driven page changes through [onSelectTab].
 */
@Composable
fun WorkspacePager(
    tabs: List<WorkspaceTab>,
    currentTab: WorkspaceTab,
    onSelectTab: (WorkspaceTab) -> Unit,
    hideTabs: Boolean,
    modifier: Modifier = Modifier,
    content: @Composable (tab: WorkspaceTab, isActive: Boolean) -> Unit,
) {
    val pagerState = rememberPagerState(
        initialPage = tabs.indexOf(currentTab).coerceAtLeast(0),
        pageCount = { tabs.size }
    )
    val flingBehavior = PagerDefaults.flingBehavior(
        state = pagerState,
        pagerSnapDistance = PagerSnapDistance.atMost(1),
    )
    val coroutineScope = rememberCoroutineScope()

    // On non-Terminal pages, rightward swipes near the left screen edge would be
    // captured by the system back gesture (predictive back, API 29+) instead of the
    // pager — e.g. SFTP→Git becomes a back press that navigates to the Terminal tab.
    // Hand the edge zone to the pager so those swipes always switch pages.
    // NOTE: keyed on settledPage, not currentPage. currentPage flips mid-swipe while
    // the pager is still placing pages; toggling systemGestureExclusion then replaces
    // the pager's LayoutNode mid-placement ("LayoutNode should be attached to an
    // owner" during dispatchDraw). settledPage only flips after the scroll settles,
    // when the pager is idle.
    val excludeFromSystemGesture = pagerState.settledPage != 0

    val pageNestedScrollConnection = rememberHorizontalDominantConnection(pagerState)

    PagerSyncEffects(pagerState, tabs, currentTab, onSelectTab)

    Scaffold(
        modifier = modifier.fillMaxSize(),
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = {
            if (tabs.size > 1 && !hideTabs) {
                WorkspaceTabRow(
                    tabs = tabs,
                    selectedIndex = pagerState.currentPage,
                    onTabClick = { index ->
                        onSelectTab(tabs[index])
                        // Skip no-op animations: re-animating to the current page
                        // replaces pager nodes mid-layout for no visible effect.
                        if (pagerState.currentPage != index) {
                            coroutineScope.launch {
                                pagerState.animateScrollToPage(index)
                            }
                        }
                    },
                )
            }
        }
    ) { paddingValues ->
        HorizontalPager(
            state = pagerState,
            flingBehavior = flingBehavior,
            pageNestedScrollConnection = pageNestedScrollConnection,
            // Stable identity per tab, not per index, so moved pages survive
            // list-instance changes without teardown (e.g. SFTP keeps its node
            // when Git is absent). Keys must be Bundle-storable: Lazy layouts
            // route page keys through SaveableStateProvider, which crashes on
            // arbitrary objects (e.g. the WorkspaceTab enum constants themselves).
            key = { index -> tabs.getOrNull(index)?.name ?: "page:$index" },
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .then(if (tabs.size <= 1 || hideTabs) Modifier.statusBarsPadding() else Modifier)
                .imePadding()
                .clipToBounds()
                .then(if (excludeFromSystemGesture) Modifier.systemGestureExclusion() else Modifier)
        ) { page ->
            tabs.getOrNull(page)?.let { tab ->
                val isActive = tab == currentTab &&
                    pagerState.settledPage == page &&
                    !pagerState.isScrollInProgress
                content(tab, isActive)
            }
        }
    }
}

@Composable
private fun rememberHorizontalDominantConnection(pagerState: PagerState): NestedScrollConnection {
    // pageNestedScrollConnection is @Composable in foundation 1.11+, so it must be
    // called from the composable body, not inside remember {} or the object expression.
    val defaultPagerNestedScrollConnection = PagerDefaults.pageNestedScrollConnection(
        pagerState,
        Orientation.Horizontal
    )
    // Include the default connection as a remember key: it is a @Composable return
    // value that can change across recompositions. Capturing only pagerState would
    // pin a stale delegate that forwards flings to detached scroll state mid-layout.
    return remember(pagerState, defaultPagerNestedScrollConnection) {
        HorizontalDominantScrollConnection(defaultPagerNestedScrollConnection)
    }
}

@Composable
private fun PagerSyncEffects(
    pagerState: PagerState,
    tabs: List<WorkspaceTab>,
    currentTab: WorkspaceTab,
    onSelectTab: (WorkspaceTab) -> Unit,
) {
    // External changes (in-app notifications, back press, restore) snap immediately
    // to avoid mid-transition focus and layout races aborting the scroll. Tab taps
    // already animate the pager themselves, so they are skipped via targetPage and
    // the two paths never run competing animations that replace pages mid-layout.
    LaunchedEffect(currentTab, tabs) {
        val index = tabs.indexOf(currentTab)
        if (index >= 0 && pagerState.targetPage != index) {
            pagerState.scrollToPage(index)
        }
    }

    // The tab list can shrink while the pager sits on a now-removed page (e.g. the
    // session backing SFTP/Review goes away). Snap back to a valid page immediately;
    // leaving currentPage out of bounds breaks the tab row and the pager's layout.
    LaunchedEffect(tabs.size) {
        if (pagerState.currentPage >= tabs.size) {
            pagerState.scrollToPage(0)
        }
    }

    // Propagates a user swipe to currentTab.
    // Also records a breadcrumb for crash reports (see CrashBreadcrumbs).
    // Keyed on settledPage (the idle page) rather than currentPage: side effects
    // belong to settled state per PagerState semantics. currentPage is still read
    // for the breadcrumb, but a body read does not restart the effect.
    LaunchedEffect(pagerState.settledPage, tabs) {
        CrashBreadcrumbs.setWorkspace(
            tab = currentTab.title,
            page = pagerState.settledPage,
            currentPage = pagerState.currentPage,
            tabCount = tabs.size,
        )
        val tab = tabs.getOrNull(pagerState.settledPage)
        if (tab != null && tab != currentTab) {
            onSelectTab(tab)
        }
    }
}

@Composable
private fun WorkspaceTabRow(
    tabs: List<WorkspaceTab>,
    selectedIndex: Int,
    onTabClick: (Int) -> Unit,
) {
    SecondaryTabRow(
        modifier = Modifier.statusBarsPadding(),
        // Live indicator driven by currentPage (the documented pattern);
        // side effects stay gated on settledPage in PagerSyncEffects.
        selectedTabIndex = selectedIndex.coerceIn(tabs.indices),
        containerColor = MaterialTheme.colorScheme.surface,
        contentColor = MaterialTheme.colorScheme.onSurface,
    ) {
        tabs.forEachIndexed { index, tab ->
            val isSelected = selectedIndex == index
            val labelColor = if (isSelected) {
                MaterialTheme.colorScheme.primary
            } else {
                MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
            }
            Tab(
                selected = isSelected,
                onClick = { onTabClick(index) },
                selectedContentColor = MaterialTheme.colorScheme.primary,
                unselectedContentColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                text = {
                    Text(
                        text = tab.title,
                        color = labelColor,
                        fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal
                    )
                }
            )
        }
    }
}

/** Lets the pager react only to clearly horizontal gestures so vertical scrolling inside pages is untouched. */
private class HorizontalDominantScrollConnection(
    private val delegate: NestedScrollConnection,
) : NestedScrollConnection {
    private fun isHorizontal(x: Float, y: Float) = abs(x) > abs(y) * HORIZONTAL_DOMINANCE

    override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset =
        if (isHorizontal(available.x, available.y)) delegate.onPreScroll(available, source) else Offset.Zero

    override fun onPostScroll(consumed: Offset, available: Offset, source: NestedScrollSource): Offset =
        if (isHorizontal(available.x, available.y)) {
            delegate.onPostScroll(consumed, available, source)
        } else {
            Offset.Zero
        }

    override suspend fun onPreFling(available: Velocity): Velocity =
        if (isHorizontal(available.x, available.y)) delegate.onPreFling(available) else Velocity.Zero

    override suspend fun onPostFling(consumed: Velocity, available: Velocity): Velocity =
        if (isHorizontal(available.x, available.y)) delegate.onPostFling(consumed, available) else Velocity.Zero

    private companion object {
        const val HORIZONTAL_DOMINANCE = 1.5f
    }
}
