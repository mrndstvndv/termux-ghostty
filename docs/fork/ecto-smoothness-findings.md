# Ecto-v2 vs compose-app terminal rendering — smoothness findings

**Author:** Hermes (session 2026-08-07)
**Audience:** reviewing agent (pick up where this leaves off)
**Scope:** Why ecto-v2 renders more smoothly than termux-ghostty's compose-app, what has already been ported, what remains learnable, and what is *not* a quick win.

Companion repos:
- `termux-ghostty` — `/Volumes/realme/Dev/termux-ghostty` (branch `dev`)
- `ecto-v2` — `/Volumes/realme/Dev/ecto-v2` (read-only reference, **never modified**)

---

## 1. TL;DR

ecto-v2 publishes every frame as **one atomic draw pass into a single Compose Canvas** — zero GraphicsLayers, zero per-row RenderNodes. HWUI sees one display list and swaps the whole surface per frame, so there is structurally nothing to tear, ghost, or blink. compose-app draws **N per-row GraphicsLayers composited under a parent layer**, so every changed row is an independent RenderNode re-record + GPU upload, and the composite of many layers is where the remaining flicker/tearing comes from.

Scheduling is **not** the gap: both are invalidation-driven and vsync-aligned.

Two big fixes were already ported (grid-coalesced resize + row-layer retention); two more port ideas remain, one of which (single-layer draw) is the architectural change that would actually close the gap. The "strip the hidden view's renderer" idea that looked like a cheap win is **not** cheap — it is entangled with the session migration adapter.


### 1.1 2026-08-09 addendum: input must never acquire the native-content monitor

The 5,000-line physical-device workload exposed an independent reliability footgun: **extra-key input acquired `GhosttyTerminalContent` directly on the main thread**. `getKittyKeyboardFlags()` and `setCursorBlinkState(true)` contend with `GhosttyWorker` while it runs `nativeAppend()`, causing Android's five-second input-dispatch ANR. The session now owns volatile, worker-published input/cursor state; the worker is the sole normal owner of `GhosttyTerminalContent`. This is required for responsiveness regardless of whether the renderer remains retained-row or moves to a single layer.
---

## 2. Why ecto is smoother — root causes

### 2.1 Single atomic draw vs N composited row layers (the dominant cause)

**ecto-v2** (`app/src/main/java/com/example/ectov2/ui/terminal/TerminalSurface.kt`):
- One Canvas draw per frame: `drawColor` background, per-cell `drawRect` cell backgrounds, flat `drawText` runs, then cursor. No `RenderNode`, no `graphicsLayer`, no `drawLayer` anywhere in the terminal code (verified by repo-wide search).
- Result: HWUI gets a single RenderNode whose display list is atomically replaced each frame. There is no intermediate state that can be partially visible → no tearing/ghosting/blanking, ever, by construction.

**termux-ghostty compose-app** (`terminal-compose-view/src/main/java/com/termux/terminal/compose/internal/TerminalRenderNodeRenderer.kt`):
- `parentLayer` (one GraphicsLayer) + one `TerminalRowState` per visible row (`createRowLayer`, ~line 161).
- Only changed rows are re-recorded (`recordRowLayer`, ~line 227) into their own RenderNode layers; then the parent display list is re-recorded to re-composite all row layers (`updateParentDisplayList`, ~line 271).
- Each changed row = separate RenderNode re-record **+ GPU texture upload + composite**. Under a tmux redraw or resize, that's N partial uploads per frame — every upload/composite boundary is a potential torn or one-frame-stale row.
- The previously-fixed release/recreate churn (below) was the *worst* instance of this class; the per-row layer composition model itself is the remaining cause.

### 2.1.1 Resolved correctness footgun: child re-record requires parent re-record

The retained-row architecture has a non-obvious invariant: whenever any child row `GraphicsLayer` is recorded, the `parentLayer` display list must also be recorded. A coalesced burst can advance `topRow` by at least one viewport; that deliberately bypasses row rotation, yet records every dirty child. Previously it did not mark the parent display list dirty, allowing HWUI to keep presenting the old parent list while accessibility exposed the new terminal text.

`TerminalRenderNodeRenderer.recordDirtyRows()` now records this invariant directly: a boolean tracks whether at least one row was recorded and invalidates the parent list exactly once afterward. This retains sparse redraws and prevents the observed black/stale terminal canvas.

### 2.2 Immutable, structurally-shared frames

**ecto** (`TerminalFrame.kt`, `TerminalViewModel.kt`):
- Zig core (`ecto_terminal.zig`) emits FULL or ROWS deltas; `TerminalFrameStore` reuses unchanged `TerminalRow` instances across updates (untouched rows keep object identity).
- One `MutableStateFlow<TerminalUiState>` publish (`publish()`, ~line 399) drives the whole canvas to redraw. All work is serialized under `operationMutex` with `latestViewport`-wins for resize (`resize()`, ~line 244).
- Design philosophy: full redraws are cheap and tear-free, so no per-row delta at the UI level.

**compose-app**: `updateRowsIfNeeded` re-records only changed rows — "smarter" in theory, but each of those re-records is precisely the independent layer upload that causes the artifacts.

### 2.3 No second renderer

**ecto**: input (keyboard/tap/scroll) goes through a thin transparent `AndroidView(TerminalInputView)` overlaid on the canvas (`TerminalSurface.kt:137-146`). Rendering is the canvas, period.

**compose-app**: `ComposeInputTerminalView` (`compose-app/.../ui/workspace/ComposeInputTerminalView.kt`) is a hidden, never-attached, never-drawn `TerminalView` subclass that still hosts the full `TerminalRenderer` + its frame-cache machinery. It exists to drive session sizing and the frame cache. It is a second renderer that never renders (see §4.1 for why stripping it is not trivial).

### 2.4 Text rendering differences (minor, cosmetic)

- ecto: one global `textScaleX` applied to the paint; flat `drawText` runs. Crisp, simple.
- compose-app: `drawTextRun` with per-run `runScale()` squeeze/stretch (`TerminalRowRenderer.kt` `drawTextRun`/`runScale`, ~lines 341–402) — more per-run math, and box-drawing glyphs go through font fallback instead of vector paths.
- ecto sets `isSubpixelText = true` on the text paint (`TerminalSurface.kt` ~line 63); compose-app's `TerminalRowRenderer` sets only `isAntiAlias = true` (`TerminalRowRenderer.kt:53`).

### 2.5 Input/native-state seam (resolved ANR footgun)

`TerminalSession` is the correct deep module between UI input and Ghostty:

- UI consumers read its volatile published mode/cursor fields and enqueue changes to `GhosttySessionWorker`.
- `GhosttySessionWorker` alone reads or mutates `GhosttyTerminalContent` after initialization.
- The worker publishes Kitty keyboard flags alongside its existing mode bits; cursor blink mutations are worker messages rather than direct content calls.

This preserves exact terminal protocol behavior while preventing an input event from waiting behind a large native append. Do not add direct `GhosttyTerminalContent` calls to UI-facing `TerminalSession` methods.

### 2.6 Scheduling parity (NOT a difference)

Both are invalidation-driven + vsync-aligned:
- compose-app library `TerminalCanvas.kt`: `awaitInvalidation()` → `withFrameNanos` loop, no continuous redraw unless a shader animates (~lines 379–399).
- ecto: frame published to state → recomposition/draw on next vsync.

---

## 3. Already ported (do not redo)

All on `dev`, committed (see `git log`):

1. **Grid-coalesced resize** — `TerminalController.kt` `resizeIfNeeded` (~line 132): backend resizes dedup on the terminal grid (cols×rows) using the exact `TerminalView.updateSize` formula (`terminal-view/.../TerminalView.java` ~lines 1627–1629). A drag-resize that doesn't cross a cell boundary no longer churns reflow → SIGWINCH → full tmux redraw. Verified: 6000-sample parity with view-eligible resizes, 0 mismatches; 420 jittered frames → 69 forwarded grids; monotonic 420 frames → 52 backend resizes (~8× reduction).
2. **Row-layer retention** — `TerminalRenderNodeRenderer.kt` `ensureLayout()` (~line 101): on width-only change, retain and reuse the row-layer pool; full release/recreate only on line-height/visible-rows/parent-height change. Kills the one-frame layer blanking during drag-resize.
3. **Debounce as a setting, default 0 ms** — soft-keyboard resize debounce is a settings control (`SettingsScreen.kt` row "Keyboard Resize Debounce (ms)", ±5 stepper, clamp 0–100, "resize immediately (no debounce)" at 0), persisted under `keyboard_resize_debounce_ms`, wired through `MainContent.kt` → `TerminalCanvas.kt` → `TerminalSessionBackend.setResizeDebounceMillis` → `ComposeInputTerminalView.resizeDebounceMillis` (trailing debounce). Default 0 = resize immediately. On-device: user confirmed much smoother.

Verification policy used: ktlint + detekt + Kotlin compile + ad-hoc throwaway Python scripts (under `/private/var/folders/.../T/`, deleted after) run by agent; canonical JUnit suite + on-device checks run by user.

---

## 4. Remaining learnable items (ranked)

### 4.1 ~~Strip the hidden view's dead renderer~~ — NOT a cheap win (revised)

**Do not attempt as a quick fix.** Investigation result:

- `ComposeInputTerminalView` is never attached or drawn (`setWillNotDraw(true)`, comment at `ComposeInputTerminalView.kt:41` — "TerminalCanvas owns layout"), so the renderer never actually composites. Its real work is *being the session host*.
- `TerminalSessionBackend.kt` depends on the view for everything: `view.attachSession(session)` (line 53), frame cache built from `TerminalSessionFrameAdapter(session, view)` (line 45), `setFontSize` reads `view.mRenderer?.mTextSize` (line 57), `resize()` → `view.layout(...)` → view `onSizeChanged` → debounced `updateSize()` → ghostty session grid resize.
- The file itself declares: "TEMPORARY migration adapter (plan stage 5) — DO NOT extend... REMOVAL TICKET (plan stage 8): delete this class and TerminalSessionBackend once no path references TerminalView/TerminalRenderer..."

So stripping the renderer == ripping out the migration adapter == plan stage 8. That is a deliberate, sizable migration (session-native backend), not a contained perf win. The only contained sub-item: verify `updateSize()`'s renderer-side work (bitmap/screen alloc per resize) and neutralize it **if** it can be done without touching session sizing — needs care; the renderer may be what the session's screen reports into.

### 4.2 Collapse to a single-layer atomic draw — the real jump (recommended next)

Replace the parent + N row GraphicsLayers with **one GraphicsLayer per frame** whose display list draws all rows directly (like ecto's flat pass), relying on:
- existing content-version invalidation (`contentVersion`, `lastProcessedContentVersion`) so unchanged frames skip re-record;
- `drawText` being cheap with cached glyph textures;
- atomicity: one display list, one swap, zero composite boundaries.

Trade-offs to weigh:
- Lose per-row redraw caching → whole surface re-recorded per changed frame (exactly what ecto does; it is smooth).
- The shader path (`updateParentDisplayList` + `renderEffect` on parentLayer) must be preserved — apply the effect to the single layer instead.
- The animated-shader bitmap path (`AnimatedTerminalBitmapRenderer`) is already single-layer-ish and should be left alone.
- Regression risk: medium-high; the row-run cache in `TerminalRowRenderer` stays (it caches *runs*, not layers) so text-building cost is preserved.
- Suggested approach: keep `TerminalRowRenderer` untouched; replace only the layer bookkeeping in `TerminalRenderNodeRenderer`. Then measure: row layer count per frame should go from N+1 to 1; on-device drag-resize should show no row-ghosting at all.

### 4.3 Single global `textScaleX` instead of per-run `runScale()`

`TerminalRowRenderer.drawTextRun` does `canvas.save()` + `canvas.scale(scale, 1f)` per squeezed/stretched run (~lines 356–364, 393–395). ecto instead applies one `textScaleX` to the paint. Simplify to a single paint-level scale; verify glyphs still land on the grid (this is the pixel-fit trick that keeps CJK/wide glyphs inside their cells). Cosmetic/CPU micro-win; careful with `runScale` semantics in tests.

### 4.4 `isSubpixelText = true` (trivially safe)

compose-app `TerminalRowRenderer.kt:52-54` sets `isAntiAlias = true` but not `isSubpixelText`. ecto sets both. One-line parity change; subpixel antialiasing on RGB displays is crisper. Watch: only valid on opaque/opaque-near backgrounds; if the terminal surface is ever translucent (shader path), subpixel text can fring — but the shader path uses a separate bitmap renderer, so the direct path is safe.

### 4.5 Box-drawing glyphs as vector paths

ecto draws box-drawing characters via `BoxDrawingGlyphs.kt` (vector paths) instead of font fallback — crisper tmux/htop borders and no font-fallback ambiguity/variance. compose-app currently lets them go through font fallback. Port the glyph tables and special-case box-drawing cells in `TerminalRowRenderer`. Medium effort; self-contained; good visual parity win.

---

## 5. Verification evidence (from this work)

Latest physical-device workload with the HOME extra-key probe (Xiaomi M2102J20SG, Android 16):
- `seq 1 5000` completed visibly through `5000`, returned to `:/ $`, and displayed no ANR after the one-second HOME tap; screenshot: `compose-app/build/reports/terminal-profile/terminal-burst-profile.png`.
- 162 frames; 14 janky (8.64%); P95 40 ms; P99 77 ms. Raw `gfxinfo` is Base64-encoded in `terminal-burst-profile.json`.
- Canonical runner: `ANDROID_SERIAL=<serial> ./scripts/profile-local-terminal-rendering.sh`.

Grid parity / coalescing (ad-hoc script, all passed):
- 6000-sample parity with `TerminalView.updateSize` eligibility: **0 mismatches** (coalescer can never skip a resize the view would do).
- 420 jittered frames → 69 forwarded grids; 0 consecutive duplicates.
- Monotonic 420-frame sequence → 52 backend resizes (~8× reduction).
- Intra-cell drift → 0 reflows. Fallback dedup preserved.

Debounce (ad-hoc script, all passed):
- Default = 0 ms when pref absent; clamp to [0,100]; ±5 stepper reaches both extremes; setter coerces ≥ 0.
- delay=0 cancels prior pending fires → newest size applies immediately, nothing stranded.

Build gates (current source state):
- `:compose-app:compileUniversalDebugKotlin :compose-app:detekt` — BUILD SUCCESSFUL (note: compose-app uses the **Universal** variant; bare `compileDebugKotlin` is wrong for it).
- Full gate: `GRADLE_USER_HOME=/Volumes/realme/.gradle direnv exec . ./gradlew --offline :compose-app:compileUniversalDebugKotlin :terminal-compose-view:compileDebugKotlin :compose-app:detekt :terminal-compose-view:detekt` — 45 tasks, BUILD SUCCESSFUL.
- ktlintFormat applied before detekt on both modules; `@Suppress("LongParameterList")` used on `rememberTerminalBackend` (matches codebase convention).
- Canonical JUnit (`:terminal-compose-view:testDebugUnitTest`) and on-device checks are the user's per AGENTS.md; the debounce=0 default was on-device confirmed by the user.

Staging discipline: only 7 intended Kotlin files staged/committed; `.android-sdk*`, `.envrc`, `docs/fork/` (including this file) left untracked. This doc is NOT to be committed per AGENTS.md ("Keep plan documents untracked or locally edited only").

---

## 6. File reference map

ecto-v2 (reference only):
- `app/src/main/java/com/example/ectov2/ui/terminal/TerminalSurface.kt` — single-pass canvas draw; input overlay; `isSubpixelText`.
- `app/src/main/java/com/example/ectov2/ui/terminal/TerminalFrame.kt` — frame store, structural row sharing.
- `app/src/main/java/com/example/ectov2/ui/terminal/TerminalViewModel.kt` — mutex + latestViewport-wins, `renderAndPublish`, StateFlow publish (~line 399).
- `app/src/main/java/com/example/ectov2/ui/terminal/BoxDrawingGlyphs.kt` — vector box-drawing glyphs.
- core: `ecto_terminal.zig` — FULL/ROWS delta emission, dirty rows.

termux-ghostty compose-app:
- `compose-app/.../ui/workspace/TerminalCanvas.kt` — canvas wiring, debounce constants/state, `@Suppress("LongParameterList")`.
- `compose-app/.../ui/workspace/TerminalSessionBackend.kt` — session adapter; resize → hidden view layout; `setResizeDebounceMillis`.
- `compose-app/.../ui/workspace/ComposeInputTerminalView.kt` — hidden TerminalView host; trailing debounce; migration-adapter comments.
- `compose-app/.../ui/MainContent.kt`, `.../ui/settings/SettingsScreen.kt` — debounce setting wiring/UI.
- `terminal-compose-view/.../internal/TerminalController.kt` — grid-coalesced `resizeIfNeeded` (~line 132), draw loop.
- `terminal-compose-view/.../internal/TerminalRenderNodeRenderer.kt` — layer-retaining `ensureLayout()` (~line 101), per-row layer model, parent display list, shader path.
- `terminal-compose-view/.../internal/TerminalRowRenderer.kt` — run building/caching, `drawTextRun` + `runScale`, no `isSubpixelText`.
- `terminal-compose-view/.../TerminalCanvas.kt` — vsync-aligned frame loop (~lines 379–399).
- `terminal-view/.../TerminalView.java` — updateSize dedup (~1629), formulas (1627–1628); the sizing truth source.

---

## 7. Open questions for the reviewing agent

1. Is a single-layer atomic draw (4.2) worth the regression risk now, or should 4.4/4.5 land first as safe visual parity? (User was offered the choice; no answer received — session ended with "write your findings".)
2. Can the renderer-side allocation inside the hidden view's `updateSize()` be neutralized without touching session sizing (4.1)? Requires reading `TerminalView.updateSize`'s renderer calls and confirming the session doesn't report into the renderer's screen.
3. Should `runScale()` (4.3) be replaced wholesale, or only for box-drawing/CJK cells?
4. ecto's input overlay pattern (`AndroidView(TerminalInputView)`) vs compose-app's focus/hit-testing on the canvas — not a perf issue, but a UX parity item to evaluate during the stage-8 migration.
