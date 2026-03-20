# Plan: Ghostty-only URL detection + clickable links in Android `TerminalView`

Status: draft
Audience: coding agent implementing the feature in this repo
Scope: Ghostty runtime only. Do not add or preserve legacy Java-backend support for this feature. This plan follows the direction in `docs/legacy-java-backend-removal-plan.md`.

## 1. Goal

Add visible link affordances to the Android terminal view for the Ghostty path:

- underline visible links in the terminal viewport
- open a tapped link
- support literal URLs printed in terminal text
- support OSC 8 hyperlinks where visible text differs from the destination URI
- keep Ghostty terminal state in Ghostty
- keep the UI path fast enough for normal frame cadence

## 2. Non-goals

Out of scope for this work:

- legacy Java backend support
- widening legacy backend interfaces just to keep this feature compiling there
- transcript-wide indexing or URL search
- desktop Ghostty hover modifiers / ctrl-click / cmd-click behavior
- file path detection or opening bare paths on Android
- enabling Oniguruma in `ghostty-vt` just for Android link detection
- redesigning selection, copy mode, or mouse tracking UX
- changing the existing `Select URL` transcript dialog in this work

## 3. Verified facts

### 3.1 Current tap-open behavior is incomplete

Current app behavior:

- `TermuxTerminalViewClient.onSingleTapUp()` gets the tapped word with `TerminalContent.getWordAtLocation(...)`
- then runs `TermuxUrlUtils.extractUrls(...)`
- then opens the first URL if any

That gives basic tap-open behavior, but only for:

- visible text that looks like a URL
- URLs that survive word-based extraction

It does **not** give us:

- visible underlines
- multi-row wrapped URL detection for rendering
- precise hit regions shared by render + tap
- OSC 8 support when link text is not the URI

Relevant files:

- `app/src/main/java/com/termux/app/terminal/TermuxTerminalViewClient.java`
- `termux-shared/src/main/java/com/termux/shared/termux/data/TermuxUrlUtils.java`

### 3.2 Ghostty already parses OSC 8 hyperlinks in our native layer

Our Zig bridge already forwards OSC 8 start/end actions into Ghostty screen state:

- `.start_hyperlink => ... startHyperlink(...)`
- `.end_hyperlink => ... endHyperlink()`

Relevant file:

- `terminal-emulator/src/main/zig/src/termux_ghostty.zig`

Implication:

- Ghostty already knows which cells belong to OSC 8 hyperlinks
- we only need to expose that information to the Android UI path

### 3.3 No usable link API is exposed to Android today

Current Ghostty JNI surface exposes:

- snapshot fill
- selected text
- word at location
- transcript text
- cursor/mode/state queries

It does **not** expose:

- visible hyperlink ranges
- URI-at-cell
- visible link segments
- regex URL matches

Relevant files:

- `terminal-emulator/src/main/java/com/termux/terminal/GhosttyNative.java`
- `terminal-emulator/src/main/java/com/termux/terminal/GhosttyTerminalContent.java`

### 3.4 Upstream Ghostty regex link matching is not available in our `ghostty-vt` import

This repo imports the Zig `ghostty-vt` module directly.
That module is built here without Oniguruma regex support.

Implication:

- Ghostty desktop link matching logic from `Surface.zig` / `StringMap` is **not** available as a drop-in API in our current build
- we should not block the Android feature on exposing upstream regex search first

Relevant upstream facts already verified locally:

- `src/build/GhosttyZig.zig` forces `vt_options.oniguruma = false`
- desktop Ghostty link matching lives above the pure VT core

### 3.5 `RenderFrameCache` is the UI source of truth for visible screen text

The UI already renders from `RenderFrameCache`, not directly from worker transport snapshots.
Transport `ScreenSnapshot` inside `FrameDelta` may be partial.

Implication:

- visible literal URL detection must read from the **UI-owned visible frame cache**
- do not build URL overlays from partial transport snapshots

Relevant files:

- `terminal-emulator/src/main/java/com/termux/terminal/RenderFrameCache.java`
- `terminal-view/src/main/java/com/termux/view/TerminalView.java`
- `terminal-emulator/src/main/java/com/termux/terminal/FrameDelta.java`

### 3.6 Ghostty native state is worker-owned at runtime

`GhosttySessionWorker` is documented as the sole owner of Ghostty native state during normal runtime.

Implication:

- do not add a per-frame UI-thread JNI crawl of live native hyperlink state
- native-visible OSC 8 extraction should happen on the worker and be published with the frame

Relevant file:

- `terminal-emulator/src/main/java/com/termux/terminal/GhosttySessionWorker.java`

## 4. Product behavior

### 4.1 What gets underlined

V1 should underline:

- literal URLs detected in the visible Ghostty viewport text
- OSC 8 hyperlinks visible in the Ghostty viewport

V1 should **not** underline:

- bare file paths
- paths emitted by compiler errors
- Android-local paths like `/sdcard/...`
- anything outside the visible viewport

### 4.2 What opens on tap

If a tapped cell belongs to:

- an OSC 8 link -> open the OSC 8 URI
- a literal URL match -> open the matched URL text

### 4.3 Toggle behavior

Use the existing property `shouldOpenTerminalTranscriptURLOnClick()` as the V1 master toggle.

When disabled:

- do not build visible link overlay state
- do not underline links
- do not intercept tap-to-open

Reason:

- no new user-facing setting needed for V1
- current property already means “URL tap opening is enabled”
- visible underline should track actual clickability in V1

### 4.4 Interaction rules

Preserve existing terminal behavior:

- never intercept while text selection is active
- never intercept touch taps while terminal mouse reporting is active
- do not add legacy Java-backend fallback behavior

### 4.5 Precedence rules

If two link sources overlap:

1. OSC 8 wins
2. literal URL regex fills only cells not already claimed by OSC 8

If terminal styling already underlines a cell:

- do not draw a second synthetic underline on top of it

## 5. Recommended architecture

## 5.1 Ownership split

Use a split model:

- **worker/native side** publishes full visible OSC 8 link segments for the current viewport
- **UI side** derives literal URL segments from the visible `RenderFrameCache` snapshot
- **`TerminalView`** owns the merged visible link layout used by both rendering and tap hit-testing
- **app layer** only decides policy and opening action

This is the cleanest fit for current architecture because it:

- respects Ghostty worker ownership
- avoids per-cell JNI
- avoids teaching legacy Java backend types about new link APIs
- lets Android stay URL-only for literal text links

## 5.2 Do not widen `TerminalContent`

Do **not** add new link methods to:

- `TerminalContent`
- `JavaTerminalContentAdapter`
- `TerminalBuffer`
- `TerminalEmulator`

Reason:

- this feature is intentionally Ghostty-only
- `docs/legacy-java-backend-removal-plan.md` already says the app should move away from dual-backend surface area
- new shared interface methods would only create temporary migration debt

Preferred location for new APIs:

- Ghostty-specific worker/native publication types
- `TerminalView`-owned merged cache

## 5.3 High-level flow

```text
PTY output -> GhosttySessionWorker -> native Ghostty state
                                  -> ScreenSnapshot publish
                                  -> OSC8 viewport link publish

TerminalView.applyScreenUpdate()
  -> apply FrameDelta to RenderFrameCache
  -> rebuild merged visible link layout from:
       (a) full visible RenderFrameCache snapshot
       (b) worker-published OSC8 viewport link snapshot
  -> invalidate()

TerminalView.onDraw()
  -> renderer draws text
  -> renderer draws synthetic underline overlay for merged link layout

TermuxTerminalViewClient.onSingleTapUp()
  -> ask TerminalView for link hit at tapped cell
  -> open returned URI
```

## 6. Data model

### 6.1 Worker-published OSC 8 transport type

Add a new parsed transport object in `terminal-emulator`.

Recommended file:

- `terminal-emulator/src/main/java/com/termux/terminal/ViewportLinkSnapshot.java`

Recommended shape:

- `long frameSequence`
- `int topRow`
- `int rows`
- `int columns`
- `LinkSegment[] segments`

Recommended segment fields:

- `int row` — row index relative to viewport `0..rows-1`
- `int startColumn`
- `int endColumnExclusive`
- `String url`
- `int source` — for now only `SOURCE_OSC8`, but keep enum room for future

Important policy:

- this snapshot is always a **full visible viewport link state**, not a delta
- keep it cheap by publishing only visible segments, not transcript state

### 6.2 `FrameDelta` extension

Extend `FrameDelta` so one publication contains both:

- transport `ScreenSnapshot`
- full `ViewportLinkSnapshot`

Relevant file:

- `terminal-emulator/src/main/java/com/termux/terminal/FrameDelta.java`

Reason:

- the worker is already the publication boundary
- link state should advance with the same frame sequence as the visible screen state

### 6.3 `TerminalView` merged cache

Add a `TerminalView`-owned merged layout used for draw + hit testing.

Recommended file:

- `terminal-view/src/main/java/com/termux/view/TerminalViewLinkLayout.java`

Recommended contents:

- `long frameSequence`
- `int topRow`
- `int rows`
- `int columns`
- per-row sorted segment lists
- `@Nullable LinkHit findAt(int externalRow, int column)`

Recommended `LinkHit` fields:

- `String url`
- `int source` (`OSC8` or `VISIBLE_URL`)

The merged layout should store row-relative drawing segments because that is all the renderer needs.
A multi-row logical link can be represented as multiple row segments sharing the same URL.

## 7. Native / worker plan for OSC 8 links

## 7.1 New JNI surface

Add new Ghostty-native bulk extraction for visible OSC 8 links.

Relevant files:

- `terminal-emulator/src/main/java/com/termux/terminal/GhosttyNative.java`
- `terminal-emulator/src/main/java/com/termux/terminal/GhosttyTerminalContent.java`
- `terminal-emulator/src/main/zig/src/jni_exports.zig`
- `terminal-emulator/src/main/zig/src/termux_ghostty.zig`

Recommended Java signature:

```java
static native int nativeFillViewportLinks(long nativeHandle, ByteBuffer buffer, int capacity);
```

Notes:

- use current Ghostty viewport state already set by the worker
- keep this API bulk-only
- do not add per-cell JNI getters for the hot path

### 7.2 Native serialization format

Use a compact direct-buffer format similar in spirit to `ScreenSnapshot`.

Recommended payload:

- magic/version
- topRow
- rows
- columns
- segmentCount
- stringTableBytes
- repeated segment records:
  - row
  - startColumn
  - endColumnExclusive
  - stringOffset
  - stringLength
- UTF-8 string table

Deduplicate identical URIs in the string table.

### 7.3 Native extraction behavior

During worker snapshot build:

- inspect the active visible viewport in Ghostty render/page state
- find OSC 8 cells and their URI
- emit **row segments** for contiguous cells that belong to the same URI on the same visible row

Do not try to publish transcript-wide hyperlink state.
Do not publish partial link deltas.

### 7.4 Publication point

Extend `GhosttySessionWorker.buildAndPublishSnapshot()` to build both:

- visible `ScreenSnapshot`
- visible `ViewportLinkSnapshot`

Recommended worker changes:

- keep double-buffered `ViewportLinkSnapshot` staging objects, like screen snapshots
- assign the same `frameSequence` to both before publishing `FrameDelta`

## 8. UI-side literal URL detection plan

## 8.1 Detector should be viewport-only and UI-only

Literal URL detection should happen in Java on the UI thread from the **already cached visible frame**.

Reason:

- no extra native ownership complexity
- no need for upstream Ghostty regex support
- visible viewport is small enough to process cheaply

### 8.2 Add a URL matcher with match positions

The current `TermuxUrlUtils.extractUrls(...)` returns strings only.
For rendering we need match ranges.

Recommended new utility in `terminal-view`:

- `terminal-view/src/main/java/com/termux/view/TerminalUrlMatcher.java`

Recommended API:

```java
List<UrlMatch> findMatches(CharSequence text)
```

Where each match has:

- `int start`
- `int endExclusive`
- `String url`

Do not make `TerminalView` depend on app-layer classes for this.

### 8.3 URL scope for V1

Use a URL-only matcher, not a path matcher.

Reason:

- the user asked for URL detection, not local path opening
- current Android app behavior already thinks in URLs
- file-path opening semantics on Android are different and should be separate work

### 8.4 Mapping visible text to cell coordinates

Build literal URL matches from **logical wrapped lines** in the visible viewport.

Algorithm per visible logical line group:

1. Group consecutive visible rows that belong to one wrapped logical line
   - continue while previous row has `isLineWrap() == true`
2. Build a `StringBuilder` for the group
3. Build a UTF-16 char-index -> cell-coordinate map while appending text
4. Run `TerminalUrlMatcher.findMatches(...)`
5. Convert each match back into one or more row segments
6. Drop any cells already claimed by OSC 8 segments

Use `ScreenSnapshot.RowSnapshot` native cell layout fields for exact mapping:

- `getCellTextStart(...)`
- `getCellTextLength(...)`
- `getCellDisplayWidth(...)`

That avoids guessing around:

- wide characters
- surrogate pairs
- grapheme clusters
- spacer tail cells

### 8.5 Viewport edge limitation

V1 can accept this limitation:

- if a wrapped URL begins above the visible viewport or ends below it, the visible fragment may not be detected until more of the URL is visible

Reason:

- the overlay is explicitly viewport-only
- avoiding extra transcript/context fetches keeps the first implementation smaller

Document this in the plan and tests instead of hiding it.

## 9. Merging link sources in `TerminalView`

Relevant file:

- `terminal-view/src/main/java/com/termux/view/TerminalView.java`

## 9.1 Refresh point

After applying the latest Ghostty frame delta to `RenderFrameCache`, rebuild the merged visible link layout before `invalidate()`.

Recommended sequence inside `applyScreenUpdate(...)`:

1. apply Ghostty frame delta to `RenderFrameCache`
2. get full visible render snapshot from `RenderFrameCache`
3. merge worker OSC 8 link snapshot + UI literal URL matches
4. store `mVisibleLinkLayout`
5. call `invalidate()`

### 9.2 Rebuild conditions

Rebuild merged links when any of these change:

- `frameSequence`
- `topRow`
- visible row count
- visible column count
- URL tap-open property value

Do **not** rebuild only because cursor blink toggled.

### 9.3 Hit testing API

Add a small `TerminalView` helper used by the app layer.

Recommended API:

```java
@Nullable
public TerminalViewLinkLayout.LinkHit getVisibleLinkHit(MotionEvent event)
```

Behavior:

- convert touch coordinates to terminal row/column
- map external row to viewport-relative row using current `topRow`
- return the matching cached link hit or `null`

## 10. Renderer plan

Relevant file:

- `terminal-view/src/main/java/com/termux/view/TerminalRenderer.java`

## 10.1 Do not mutate terminal text styles for synthetic links

Do **not** rewrite `ScreenSnapshot` cell styles just to force underline.

Reason:

- synthetic UI affordance is not terminal state
- mutating styles would contaminate render caches and selection semantics
- native text styles should remain source-of-truth for real terminal formatting only

## 10.2 Draw underline as a separate overlay pass

Recommended approach:

1. render terminal text exactly as today
2. draw synthetic underline segments afterward using merged link layout

This avoids touching existing text run caches.

### 10.3 Underline drawing rules

For each row segment:

- draw one or more line fragments under the covered columns
- resolve underline color from the effective foreground color of the covered cell style
- skip cells already underlined by terminal style
- respect reverse-video effective foreground resolution

Implementation note:

- extract a small foreground-color resolver from existing `drawTextRun(...)` logic instead of duplicating color rules inline

### 10.4 Renderer API extension

Recommended renderer signature change:

```java
render(..., @Nullable TerminalViewLinkLayout linkLayout)
```

Pass the merged layout from `TerminalView.onDraw()`.

## 11. Tap integration plan

Relevant files:

- `app/src/main/java/com/termux/app/terminal/TermuxTerminalViewClient.java`
- `terminal-view/src/main/java/com/termux/view/TerminalView.java`

## 11.1 Replace Ghostty tap-open path with cached hit testing

For the Ghostty path, stop using:

- `getWordAtLocation(...)`
- `extractUrls(...)` on the tapped word

Use:

- `TerminalView.getVisibleLinkHit(event)`

Then open `hit.url`.

### 11.2 Preserve interaction guards

Before opening a link, keep existing guards:

- bail if there is no active terminal backend
- bail if text selection is active
- bail for touch taps while terminal mouse tracking is active
- respect `shouldOpenTerminalTranscriptURLOnClick()`

## 12. Interaction with legacy backend removal

This feature should align with `docs/legacy-java-backend-removal-plan.md`.

Recommended sequencing:

1. finish enough runtime cleanup that app/view code can treat Ghostty as the real terminal path
2. land the Ghostty-only link overlay feature
3. then remove leftover word-based Ghostty tap-open logic

If this feature lands **before** full backend removal is complete:

- keep new APIs Ghostty-specific
- do not add temporary Java-backend implementations
- do not widen `TerminalContent`
- do not add new work in `TerminalBuffer` / `TerminalEmulator`

## 13. Test plan

## 13.1 Native Zig tests

Add tests around viewport link extraction in:

- `terminal-emulator/src/main/zig/src/termux_ghostty.zig`

Required cases:

- single OSC 8 link on one row
- one OSC 8 link wrapped across multiple visible rows
- adjacent OSC 8 links on the same row are kept separate
- repeated same URI on different rows
- no links in viewport -> zero segments
- scrollback / shifted top row still reports correct visible row-relative segments

## 13.2 Java unit tests for visible URL mapping

Add tests for the Java detector / merged layout builder.

Required cases:

- single-row URL
- wrapped URL across visible rows
- punctuation trimming
- surrogate pair / wide-character neighbors do not shift match mapping
- OSC 8 segment blocks regex ownership on overlapping cells
- viewport-edge partial wrapped URL limitation is explicitly documented/tested

## 13.3 Renderer tests

Add focused renderer tests or snapshot-ish tests for:

- underline drawn for synthetic links
- no double underline for already-underlined terminal text
- underline color respects effective foreground / reverse video

## 13.4 App-level behavior tests

Add tests around tap hit resolution:

- tap inside link opens URL
- tap outside link does not open URL
- selection mode blocks opening
- mouse tracking blocks touch tap opening

## 14. Implementation phases

### Phase 0: prerequisite alignment

- confirm feature will target Ghostty runtime only
- avoid any new shared interface obligations for legacy backend

### Phase 1: worker/native OSC 8 publication

- add `ViewportLinkSnapshot`
- add native bulk serialization
- publish link snapshot with `FrameDelta`
- add native tests

### Phase 2: UI literal URL detector

- add `TerminalUrlMatcher` with positional matches
- add wrapped-line text-to-cell mapping from `ScreenSnapshot`
- emit row segments for visible literal URLs
- add Java unit tests

### Phase 3: merged cache in `TerminalView`

- add `TerminalViewLinkLayout`
- rebuild it after applying Ghostty frame updates
- add hit-test helper

### Phase 4: renderer overlay

- pass merged layout into renderer
- draw synthetic underline overlay
- add renderer tests

### Phase 5: tap integration cleanup

- switch Ghostty tap-open to cached hit testing
- keep property + interaction guards
- stop relying on tapped-word URL extraction for Ghostty path

## 15. Open questions / decisions to lock before coding

### 15.1 Underline color

Recommended:

- use effective foreground color of the linked text

Not recommended for V1:

- theme accent color
- hardcoded blue

### 15.2 Toggle scope

Recommended V1:

- existing tap-open property controls both underline + clickability

Future optional refinement:

- split “show link affordances” from “open on tap” if users ask for it

### 15.3 Hardware mouse behavior

Not in V1.

Possible future work:

- pointer cursor change over links
- modifier-based click/open behavior closer to desktop Ghostty

## 16. Summary

Recommended implementation:

- publish visible OSC 8 link segments from the Ghostty worker
- detect visible literal URLs in Java from the UI-owned render snapshot
- merge both into a `TerminalView` cache
- draw synthetic underline overlay in `TerminalRenderer`
- open links from cached hit testing on tap
- do all of this **without** adding new legacy Java-backend obligations

That gives us:

- visible underlines
- reliable tap hit regions
- OSC 8 support
- no per-cell JNI crawl
- architecture aligned with planned Ghostty-only runtime
