# Detailed Plan: Phase 2 — Compose UI & UX Design

This document outlines the detailed action items for **Phase 2**, focusing on the user interface, page routing, and reactive data flow using Jetpack Compose and Material 3.

---

## 1. UI Screen Inventory

The new app will contain four core screens:
1. **Connection Manager Dashboard (DashboardScreen):** Displays host lists and allows configuration.
2. **Terminal Workspace Screen (TerminalWorkspaceScreen):** The split-pane UI containing the embedded [TerminalView](file:///Volumes/realme/Dev/termux-ghostty/terminal-view/src/main/java/com/termux/view/TerminalView.java) and toolbars.
3. **SFTP File Browser Screen (SftpBrowserScreen):** Explorer UI for directory navigation and file actions.
4. **Settings Screen (SettingsScreen):** Standard terminal styles and connection parameters configurations.

---

## 2. Adaptive Workspace Layout & State Restoration

To provide a seamless user experience, the SSH session workspace adapts to the device form factor (using Window Size Classes and Jetpack WindowManager) and preserves all view state during layout transitions, tab swaps, and screen rotations.

### Navigation & Parameter Passing
To open a session, screens navigate using type-safe routing. We pass a unique identifier `hostId` (e.g., UUID or DB primary key) instead of serializing sensitive host settings. The destination's ViewModel retrieves this ID and asynchronously queries the connection profile:

```kotlin
// Navigation Graph setup
composable("workspace/{hostId}") { backStackEntry ->
    val hostId = backStackEntry.arguments?.getString("hostId") ?: ""
    TerminalWorkspaceScreen(hostId = hostId)
}
```

In the screen's ViewModel, we inject a `SavedStateHandle` to retrieve `hostId` automatically, resisting process death:
```kotlin
class WorkspaceViewModel(
    private val savedStateHandle: SavedStateHandle,
    private val hostRepository: HostRepository,
    private val sessionManager: SshSessionManager
) : ViewModel() {
    val hostId: String = checkNotNull(savedStateHandle["hostId"])
    
    // Connection and session flows managed reactively...
}
```

### Tab Layout Implementation with State Preservation (Phone Portrait / Compact)
Using a conditional `when (selectedTab)` statement will dispose of [TerminalView](file:///Volumes/realme/Dev/termux-ghostty/terminal-view/src/main/java/com/termux/view/TerminalView.java) when switching tabs, resulting in layout re-inflation, loss of terminal scroll/backlog state, and keyboard dismissal.
To prevent this, use `HorizontalPager` with `beyondViewportPageCount = 1` and disable swipe gestures to avoid interfering with terminal scroll/selection interactions:

```kotlin
@Composable
fun TabbedWorkspace(
    session: TerminalSession,
    sftpViewModel: SftpViewModel
) {
    val pagerState = rememberPagerState(pageCount = { 2 })
    val coroutineScope = rememberCoroutineScope()
    val tabs = listOf("Terminal", "SFTP File Explorer")
    
    Column(modifier = Modifier.fillMaxSize()) {
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
            userScrollEnabled = false, // Disable swipe so terminal text selection / scrolling works
            beyondViewportPageCount = 1 // Keeps the TerminalView Composable alive in the hierarchy
        ) { page ->
            when (page) {
                0 -> TerminalWorkspaceContainer(session)
                1 -> SftpFileBrowser(sftpViewModel)
            }
        }
    }
}
```

### Split-Pane & Foldable Layout Implementation (Tablet/Landscape/Expanded)
For wider form factors, we render a split-pane layout side-by-side. 
1. **Window Size Classes**: Detect width using Material 3 `WindowWidthSizeClass` (Compact/Medium → Tabbed; Expanded → Split-Pane).
2. **Hinge & Fold Alignment**: Use Jetpack WindowManager's `FoldingFeature` to identify the physical fold seam of foldables. If a fold is actively separating (e.g., `foldingFeature.isSeparating == true`), adjust the widths of the pane boundaries to avoid placing interactive elements or text directly under the physical crease.
3. **Dynamic Terminal Resizing**: Any change in size (entering split-screen, rotating, folding) changes the available layout area. We must observe container layout constraints and invoke `TerminalSession.updateSize(cols, rows)` and `TerminalView.onScreenSizeChanged()` to recompute the terminal rows and columns grid dynamically, ensuring text reflow occurs properly without cropping.

```kotlin
@Composable
fun SplitWorkspace(
    session: TerminalSession,
    sftpViewModel: SftpViewModel,
    foldingFeature: FoldingFeature?
) {
    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val totalWidth = maxWidth
        
        // Calculate split ratio based on folding feature, if present
        val terminalWidth = if (foldingFeature != null && foldingFeature.isSeparating) {
            val foldDp = foldingFeature.bounds.left.dp // Assume vertical fold
            foldDp
        } else {
            totalWidth * 0.6f
        }
        val sftpWidth = totalWidth - terminalWidth

        Row(modifier = Modifier.fillMaxSize()) {
            Box(modifier = Modifier.width(terminalWidth)) {
                TerminalWorkspaceContainer(session)
            }
            VerticalDivider(modifier = Modifier.fillMaxHeight(), thickness = 1.dp)
            Box(modifier = Modifier.width(sftpWidth)) {
                SftpFileBrowser(sftpViewModel)
            }
        }
    }
}
```

### State Restoration on Configuration Change (Screen Rotation)
To keep the shell session alive across activity destruction (such as rotation or split-screen toggles):
1. **Background Service**: Maintain connection states and [TerminalSession](file:///Volumes/realme/Dev/termux-ghostty/terminal-emulator/src/main/java/com/termux/terminal/TerminalSession.java) instances inside a bound foreground `Service` (e.g., `SshSessionService` or similar implementation to [TermuxService](file:///Volumes/realme/Dev/termux-ghostty/app/src/main/java/com/termux/app/TermuxService.java)).
2. **AndroidView Interop & Re-binding**: Use `rememberSaveable` to store key active indices (e.g., `selectedTab`). In the `AndroidView` wrapper for [TerminalView](file:///Volumes/realme/Dev/termux-ghostty/terminal-view/src/main/java/com/termux/view/TerminalView.java), attach/detach sessions gracefully during the view lifecycle to prevent window context leaks:

```kotlin
@Composable
fun TerminalWorkspaceContainer(session: TerminalSession) {
    AndroidView(
        factory = { context ->
            TerminalView(context, null).apply {
                // Attach session when view is created
                attachSession(session)
            }
        },
        update = { view ->
            // Re-sync session parameters if instance updates
            if (view.currentSession != session) {
                view.attachSession(session)
            }
        },
        onRelease = { view ->
            // Detach session to prevent Activity context leaks
            view.detachSession()
        },
        modifier = Modifier.fillMaxSize()
    )
}
```

---

## 3. SFTP Browser & State Management

Manage the SFTP directory state reactively using Kotlin's `StateFlow` inside the `SftpViewModel`. State must be preserved during process recreation by leveraging `SavedStateHandle` to cache the `currentPath`.

1. **State Definition:**
   ```kotlin
   sealed interface SftpUiState {
       object Loading : SftpUiState
       data class Success(val currentPath: String, val files: List<SftpFile>) : SftpUiState
       data class Error(val message: String) : SftpUiState
   }
   ```
2. **ViewModel Navigation with Path Preservation:**
   ```kotlin
   class SftpViewModel(
       private val client: SftpClient,
       private val savedStateHandle: SavedStateHandle
   ) : ViewModel() {
       private val _uiState = MutableStateFlow<SftpUiState>(SftpUiState.Loading)
       val uiState = _uiState.asStateFlow()
       
       // Backed by SavedStateHandle to restore folder path on recreation
       var currentPath: String
           get() = savedStateHandle["current_path"] ?: "/"
           set(value) { savedStateHandle["current_path"] = value }

       init {
           navigateTo(currentPath)
       }

       fun navigateTo(path: String) {
           currentPath = path
           viewModelScope.launch {
               _uiState.value = SftpUiState.Loading
               try {
                   val list = client.listFiles(path)
                   _uiState.value = SftpUiState.Success(path, list)
               } catch (e: Exception) {
                   _uiState.value = SftpUiState.Error(e.localizedMessage ?: "Failed to load directory")
               }
           }
       }
   }
   ```
3. **UI List Rendering:**
   * Render directories using `LazyColumn` and modern Material 3 `ListItem` with folder/file icon indicators.
   * Provide a sliding action menu (e.g., download, delete, rename).

---

## 4. Focus Management, Keyboards, & Input Interception

A terminal view requires absolute focus to capture and transmit keyboard events. We implement structured focus request mechanisms in Jetpack Compose:

### Focus Request Mechanics
Use `FocusRequester` to manage focus transitions. Focus should be requested on the [TerminalView](file:///Volumes/realme/Dev/termux-ghostty/terminal-view/src/main/java/com/termux/view/TerminalView.java) when the terminal becomes visible:

```kotlin
@Composable
fun TerminalFocusWrapper(
    session: TerminalSession,
    isTerminalActive: Boolean
) {
    val focusRequester = remember { FocusRequester() }
    
    LaunchedEffect(isTerminalActive) {
        if (isTerminalActive) {
            focusRequester.requestFocus()
        }
    }
    
    Box(
        modifier = Modifier
            .fillMaxSize()
            .focusRequester(focusRequester)
            .focusable()
            .onPreviewKeyEvent { keyEvent ->
                // Intercept hardware key events (e.g., Tab, Arrow Keys, Esc) 
                // to prevent Jetpack Compose's FocusManager from consuming them
                // and shifting focus to other UI buttons.
                val handled = session.handleKeyEvent(keyEvent)
                handled
            }
    ) {
        TerminalWorkspaceContainer(session)
    }
}
```

### Keyboard Toolbar
Re-implement the keyboard utility toolbar (Ctrl, Alt, ESC, and arrow buttons) using Jetpack Compose:
* Use a horizontal `LazyRow` pinned above the software keyboard (using `WindowInsets.isImeVisible` / `ime` padding to adjust positioning).
* **Focus Retention**: Clicking buttons on the toolbar must not steal focus away from the [TerminalView](file:///Volumes/realme/Dev/termux-ghostty/terminal-view/src/main/java/com/termux/view/TerminalView.java). Buttons on the toolbar should use non-focusable click behaviors, or explicitly request focus back to the terminal `FocusRequester` immediately after click processing.

---

## 5. Phase 2 Verification Checklist
* [ ] App opens to host configuration dashboard.
* [ ] Connection transitions smoothly into the split-tab session workspace.
* [ ] Tapping "SFTP" fetches directory contents and lists them without lag.
* [ ] Swapping tabs preserves terminal text rendering and scroll state using `HorizontalPager`.
* [ ] App handles configuration changes (screen rotations, split-pane resize) gracefully without dropping the underlying shell connection (bound to `SshSessionService`).
* [ ] Split-Pane automatically layout-adjusts dynamically for wide screens and respects foldable hinge bounds using WindowManager's `FoldingFeature`.
* [ ] Focus is maintained on `TerminalView` upon navigation, tab swap, and when interacting with the extra keys toolbar.
* [ ] Hardware key inputs (Tab, Escape, Arrows) are correctly handled by the terminal emulator rather than the Compose navigation framework.
