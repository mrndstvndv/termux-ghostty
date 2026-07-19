# Research: SFTP & Review Tab Blank Screen Bug

## Symptom

The SFTP Explorer and Review tabs display a blank screen. The UI is still interactive — tapping where buttons/items would be triggers the correct action — but nothing is visible. The Terminal and Browser tabs work fine.

## Investigation Summary

### Architecture

Both tabs share the same infrastructure:

1. **Dynamic tab addition**: ViewModels (`SftpViewModel`, `ReviewViewModel`) are created *asynchronously* after SSH connection, ~1–2 seconds after the `TerminalWorkspace` screen first renders. During this gap, `activeTabs = [Terminal, Browser]` (2 pages). After VMs arrive, `activeTabs = [Terminal, Sftp, Review, Browser]` (4 pages).

2. **Tab switching via HorizontalPager**: `TabbedWorkspace.kt` uses Compose Foundation's `HorizontalPager` with `userScrollEnabled = false`, `beyondViewportPageCount = 2`, and programmatic `animateScrollToPage()`.

3. **Page content**: `SftpFileBrowser` and `GitReviewScreen` are pure Compose screens. Both use `collectAsState()` to observe ViewModel state and render via `when(state)` branches (Loading → indicator, Success → list, Error → message).

### Code Paths Analyzed

| File | Path |
|---|---|
| `TabbedWorkspace.kt` | Pager setup, tab switching |
| `SplitWorkspace.kt` | Wide-screen layout (same issue there too) |
| `SftpFileBrowser.kt` | SFTP content rendering |
| `GitReviewScreen.kt` | Review content rendering |
| `MainViewModel.kt` | ViewModel creation timing |
| `TerminalCanvas.kt` | Terminal Canvas rendering (page 0) |
| `Theme.kt` / `Color.kt` | Material theme colors |
| `MainContent.kt` | Navigation 3 entry + Surface wrapper |
| `TerminalThemeSync.kt` | Terminal color syncing (side-effect only) |

### What Was Ruled Out

| Cause | Reason |
|---|---|
| **Theme / color mismatch** | `onSurface` and `background` have correct contrast. Tab labels render fine. |
| **Alpha=0 somewhere** | No `alpha(0f)` or `graphicsLayer { alpha = 0f }` found in the relevant composable tree. |
| **Custom font corruption** | Would affect all text, not just SFTP/Review. Tab labels are visible. |
| **ViewModel null** | Tabs only appear when VMs are non-null (guard in `activeTabs`). |
| **Data loading error** | Loading state shows `CircularProgressIndicator`. Error state shows red text. Neither is visible. |
| **`clipToBounds()` clipping** | Content is correctly sized and positioned within the pager bounds. |
| **Nested Scaffold** | `GitReviewScreen` uses a Scaffold for diff view, but `SftpFileBrowser` doesn't, and both are blank. |
| **`beyondViewportPageCount` overflow** | Fixed in Compose Foundation (coercing to `pageCount`). The BOM `2026.06.01` includes this fix (see [commit c4ff0b8](https://github.com/alhia/androidx/commit/c4ff0b8ad2ad439dbdd5b281fa254eea553fbb63)). |
| **`TerminalCanvas` drawing interference** | Terminal drawing is on page 0, clipped by pager bounds. Should not affect other pages. |

### Most Likely Root Causes

**1. Competing `animateScrollToPage()` calls causing stale rendering**

In `TabbedWorkspace.kt`, when a tab is tapped:

```kotlin
// onClick handler launches animation
onTabSelected(tab)   // → updates activeTab state
coroutineScope.launch {
    pagerState.animateScrollToPage(index)
}

// LaunchedEffect also fires when activePageIndex changes
LaunchedEffect(activePageIndex) {
    if (pagerState.currentPage != activePageIndex && !pagerState.isScrollInProgress) {
        pagerState.animateScrollToPage(activePageIndex)
    }
}
```

Both the `onClick` handler and the `LaunchedEffect` call `animateScrollToPage`. The `LaunchedEffect`'s `isScrollInProgress` guard prevents a second animation, but the first animation from `onClick` runs in a `coroutineScope.launch`. Meanwhile, `activePageIndex` changes trigger `pagerState` recomputation. This **race** can cause the pager to not properly settle on the target page, leaving a stale or blank visual state. Known issue from the Accompanist days ([Issue #1334](https://github.com/google/accompanist/issues/1334)).

**2. Dynamic `pageCount` change with `beyondViewportPageCount`**

When `activeTabs` changes from 2→4 items mid-session, the pager's `pageCount` lambda now returns 4. Pages at indices 1 and 2 are *new* — they were composed as `Browser` before (at index 1). Without a custom `key` lambda, the pager identifies pages by index. The content function `pageContent = { when(activeTabs[it]) { ... } }` correctly returns SFTP for index 1, but the pager's internal `LazyLayout` *may reuse the composition slot for key '1'* without recomposing if it thinks the content hasn't changed. This would show the old `Browser` content on the SFTP tab — except the WebView inside the old Browser composition is detached from the view hierarchy, resulting in a blank slot.

This matches the "interactive but invisible" symptom precisely: the old composition's layout and touch handlers remain, but the View rendering is gone.

**3. `weight(1f)` modifier inside `HorizontalPager` page**

Both `SftpFileBrowser` and `GitReviewScreen` use `.weight(1f)` on their content boxes inside a `Column`. The `weight` modifier works by dividing remaining space in a `Column`/`Row`. Inside a `HorizontalPager` page, the measurement constraints from the pager may interact poorly with `weight`, potentially causing the content to measure with zero height in some edge cases.

## Suggested Fixes

### Fix 1: Remove the competing `LaunchedEffect` animation

Replace the `LaunchedEffect(activePageIndex)` scroll sync with a simpler approach — let the `onClick` handler be the sole source of scroll commands:

```kotlin
// Remove LaunchedEffect(activePageIndex) that also calls animateScrollToPage
// Instead, just sync the initial page:
LaunchedEffect(Unit) {
    if (pagerState.currentPage != activePageIndex) {
        pagerState.scrollToPage(activePageIndex)
    }
}
```

### Fix 2: Add a `key` parameter to `HorizontalPager`

Provide a stable identity for each page so the pager correctly recomposes when the tab list changes:

```kotlin
key = { index -> activeTabs.getOrNull(index) }
```

This ensures that when `activeTabs` changes from `[Terminal, Browser]` to `[Terminal, Sftp, Review, Browser]`, the pager treats index 1's new content (SFTP) as a new item rather than reusing the stale Browser composition.

### Fix 3: Set a background color on the page content

Both `SftpFileBrowser` and `GitReviewScreen` rely on the parent `Surface` background. Adding an explicit background to the page content ensures the visual layer is always present:

```kotlin
SftpFileBrowser(
    modifier = Modifier
        .fillMaxSize()
        .background(MaterialTheme.colorScheme.surface),
    ...
)
```

### Fix 4: Replace `animateScrollToPage` with `scrollToPage`

`scrollToPage` (non-animated) bypasses animation-related race conditions. If the race in Fix 1 is the culprit, this provides an immediate workaround. Testing with `scrollToPage` first can confirm whether the animation is the issue.

### Fix 5: Remove `beyondViewportPageCount = 2`

Setting `beyondViewportPageCount = 0` (the default) eliminates pre-composition. Pages are composed on-demand when scrolled into view. This avoids any pre-composition rendering issues and is the simplest change to test.

## Recommended Testing Order

1. Set `beyondViewportPageCount = 0` (easiest, rules out pre-composition issues)
2. Replace `animateScrollToPage` with `scrollToPage` (rules out animation race)
3. Add `key = { activeTabs.getOrNull(it) }` to pager (fixes stale composition reuse)
4. Add explicit background color to SFTP/Review pages (fixes missing visual layer)

## Sources

- [Compose Foundation HorizontalPager API reference](https://developer.android.com/reference/kotlin/androidx/compose/foundation/pager/HorizontalPager.composable)
- [Accompanist Issue #1334: animateScrollToPage doesn't fully scroll](https://github.com/google/accompanist/issues/1334)
- [Accompanist Issue #1306: Pager shows wrong page during swipe](https://github.com/google/accompanist/issues/1306)
- [Compose Foundation commit c4ff0b8: Prevent overflow with beyondViewportPageCount](https://github.com/alhia/androidx/commit/c4ff0b8ad2ad439dbdd5b281fa254eea553fbb63)
- [StackOverflow: animateScrollToPage not working with pages that request focus](https://stackoverflow.com/questions/76281249/compose-pager-animatescrolltopage-not-working-with-pages-that-request-focus)
