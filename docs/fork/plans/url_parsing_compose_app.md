# Implementation Plan: URL Parsing and Clickable Link Support in Jetpack Compose App

This plan outlines adding URL parsing and clickable link support to the `compose-app` module, replicating the behavior of the legacy `app` (XML/Java) version.

## 1. Research & Analysis

The legacy `app` implementation utilizes `TerminalView`'s built-in URL parsing capabilities by configuring the `TerminalViewClient` and utilizing a shared helper utility `ShareUtils` to open URLs.

### Requirements for Link Detection and Click Handling:
1. **Enable URL Layout Building**:
   `TerminalView` checks if the client wants URLs to be parsed via `TerminalViewClient.shouldOpenTerminalTranscriptURLOnClick()`. If `true`, it builds and renders underlines for the visible URLs in the terminal viewport.
2. **Detect Tapped URLs**:
   When a click/tap event occurs, `TerminalView` queries the client via `getTerminalTranscriptUrlOnTap(MotionEvent)`.
3. **Handle Tap Actions**:
   In `onSingleTapUp(MotionEvent)`, the client checks if the tap was on a URL. If a URL is found, it opens it (using `ShareUtils.openUrl()`).

Currently, in `compose-app` ([TerminalWorkspaceContainer.kt](file:///Users/steven/Library/Application%20Support/worktree-tui/Volumes-realme-Dev-termux-ghostty/url-parsing/compose-app/src/main/java/com/mrndtvndv/term/ui/workspace/TerminalWorkspaceContainer.kt)):
* The client (`TermuxTerminalViewClientBase` anonymous class subclass) does not override `shouldOpenTerminalTranscriptURLOnClick()`, so it defaults to `false`.
* The client does not override `getTerminalTranscriptUrlOnTap(MotionEvent)`, so it defaults to returning `null`.
* The client's `onSingleTapUp(MotionEvent)` only handles showing the soft keyboard and requesting focus.

---

## 2. Proposed Changes

We will modify [TerminalWorkspaceContainer.kt](file:///Users/steven/Library/Application%20Support/worktree-tui/Volumes-realme-Dev-termux-ghostty/url-parsing/compose-app/src/main/java/com/mrndtvndv/term/ui/workspace/TerminalWorkspaceContainer.kt) to:

1. Import `com.termux.shared.interact.ShareUtils`.
2. Override `shouldOpenTerminalTranscriptURLOnClick()` to return `true`.
3. Override `getTerminalTranscriptUrlOnTap(MotionEvent)` to return the URL from the visible link layout under the touch position:
   ```kotlin
   override fun getTerminalTranscriptUrlOnTap(e: MotionEvent): String? {
       return this@apply.getVisibleLinkHit(e)?.url
   }
   ```
4. Update `onSingleTapUp(MotionEvent)` to check for a tapped URL and open it if present:
   ```kotlin
   override fun onSingleTapUp(e: MotionEvent) {
       val url = getTerminalTranscriptUrlOnTap(e)
       if (url != null) {
           ShareUtils.openUrl(context, url)
           return
       }
       this@apply.requestFocus()
       KeyboardUtils.showSoftKeyboard(context, this@apply)
   }
   ```

---

## 3. Implementation Tasks

- [ ] **Task 1**: Update imports in `TerminalWorkspaceContainer.kt`.
- [ ] **Task 2**: Modify the anonymous `TermuxTerminalViewClientBase` instance inside `TerminalWorkspaceContainer.kt` to:
  - Override `shouldOpenTerminalTranscriptURLOnClick` returning `true`.
  - Override `getTerminalTranscriptUrlOnTap` to locate the tapped URL.
  - Implement URL click intercepting in `onSingleTapUp`.
- [ ] **Task 3**: Compile/test the changes inside the Nix development shell (`nix develop -c ./gradlew :compose-app:compileDebugKotlin`) to ensure correct implementation.
