# Libghostty Integration Plan

Goal: replace Termux's Java VT parser/emulator with Ghostty's terminal engine, but keep the existing Android app/session model and keep Java `Canvas` rendering for the first usable milestone.

Execution checklist: `LIBGHOSTTY_IMPLEMENTATION_CHECKLIST.md`

This is **not** a WebAssembly plan. The wasm example in `/Volumes/realme/Dev/ectoplasm/ghostty-wasm` is only a reference for how to wrap Ghostty state. The real target here is a **native JNI shared library** for Android.

---

## 1. What should change vs what should stay

### Keep
- `TerminalSession.java` lifecycle and PTY ownership.
- `termux.c` subprocess / PTY creation.
- The current Android `Canvas` renderer path for the first milestone.
- Existing app/UI behaviors: scrollback, selection, URL-on-tap, transcript export, clipboard hooks, bell, title updates.

### Replace
- `TerminalEmulator.java` parsing + state machine.
- Java-owned screen state as the source of truth.
- Java-side VT mode tracking.

### Explicit non-goals for phase 1
- No GPU renderer rewrite.
- No full libghostty app embedding.
- No wasm runtime.
- No large PTY/session architecture rewrite.

---

## 2. Reality check: current Ghostty C API is not enough by itself

This is the biggest thing the original plan was missing.

Ghostty's public `libghostty-vt` C API already gives us:
- terminal create/free/reset/resize
- terminal VT write
- formatter APIs
- key/mouse encoder APIs
- OSC/SGR helper parsers

But the current exported `ghostty_terminal_vt_write()` path uses Ghostty's **readonly** stream handler. That handler updates terminal state, but it intentionally ignores actions that require side effects or replies, such as:
- bell
- window title updates
- clipboard operations
- device status / cursor position reports
- other response-producing sequences

Termux needs those. The current Java `TerminalEmulator` actively does things like:
- ring bell
- update title
- copy/paste clipboard
- notify color changes
- write VT replies back to the PTY

So the integration should **not** be:
1. link `libghostty-vt.so`
2. call `ghostty_terminal_vt_write()`
3. call it done

That will regress interactive behavior.

### Consequence
We need a **custom Zig shim** on top of the Ghostty Zig module, not just the raw public C API.

That shim should:
- own `ghostty.Terminal`
- use Ghostty's full stream/action layer, not the readonly helper
- translate Ghostty actions into Termux-compatible callbacks / pending output buffers
- expose a small, stable, Termux-specific JNI/C ABI

Pin Ghostty to a specific commit. Do not track `main` loosely. `libghostty-vt` is real and usable, but the API is still explicitly unstable.

---

## 3. Current coupling points inside this repo

The second big issue: the app doesn't only depend on `TerminalEmulator`. It depends on `TerminalBuffer` all over the place.

### Current important paths
- `terminal-emulator/src/main/java/com/termux/terminal/TerminalEmulator.java`
- `terminal-emulator/src/main/java/com/termux/terminal/TerminalBuffer.java`
- `terminal-emulator/src/main/java/com/termux/terminal/TerminalRow.java`
- `terminal-view/src/main/java/com/termux/view/TerminalRenderer.java`
- `terminal-view/src/main/java/com/termux/view/TerminalView.java`
- `terminal-view/src/main/java/com/termux/view/textselection/TextSelectionCursorController.java`
- `app/src/main/java/com/termux/app/terminal/TermuxTerminalViewClient.java`
- `termux-shared/src/main/java/com/termux/shared/shell/ShellUtils.java`

### Today these areas directly expect Java screen state for:
- visible rendering
- scroll range / transcript row counts
- `getSelectedText(...)`
- `getWordAtLocation(...)`
- transcript dumping
- alt-screen detection
- top-row scrolling behavior

So replacing only `TerminalEmulator` is not enough. The UI layer must be decoupled from `TerminalBuffer` as a concrete implementation detail.

---

## 4. Recommended architecture

```text
PTY / subprocess
  └─ existing libtermux.so + termux.c
       └─ TerminalSession.java
            ├─ reads PTY output on background thread
            ├─ delivers bytes to main thread
            └─ writes user input back to PTY

Main-thread terminal backend
  ├─ Java backend (current code, temporary compatibility path)
  └─ Ghostty backend
       ├─ libtermux-ghostty.so   <-- new Zig-built JNI/shared lib
       ├─ ghostty.Terminal
       ├─ custom Ghostty stream handler
       ├─ render snapshot builder
       └─ transcript / selection helpers

UI
  ├─ TerminalRenderer.java (adapted to snapshot API)
  ├─ TerminalView.java
  ├─ text selection controller
  └─ transcript / URL / clipboard helpers
```

### Key design rule
For Ghostty mode, native memory is the **single source of truth**.

Do **not** mirror the whole screen + scrollback into Java `TerminalBuffer` on every update. That duplicates state, burns JNI bandwidth, and creates two terminal models that can drift.

Instead:
- render only the viewport via batched snapshots
- ask native code for selection/transcript/word queries
- keep Java state as a thin proxy/cache only where needed

---

## 5. Add an abstraction before swapping engines

Before wiring Ghostty in, add a small interface that captures what the UI actually needs.

Example shape:

```java
interface TerminalContent {
    int getColumns();
    int getRows();
    int getActiveRows();
    int getActiveTranscriptRows();

    boolean isAlternateBufferActive();
    boolean isMouseTrackingActive();
    boolean isReverseVideo();

    int getCursorRow();
    int getCursorCol();
    int getCursorStyle();
    boolean shouldCursorBeVisible();

    String getSelectedText(int x1, int y1, int x2, int y2);
    String getWordAtLocation(int x, int y);
    String getTranscriptText(boolean linesJoined, boolean trim);

    ScreenSnapshot snapshot(int topRow);
}
```

### Why this step matters
It lets us:
- keep existing behavior with a Java-backed adapter first
- port `TerminalView`, `TerminalRenderer`, selection, and transcript code once
- swap the backend later without touching all callers again

### Coordinate contract to preserve
Keep the existing external row model for Java callers:
- visible screen rows: `0 .. rows-1`
- scrollback rows: negative indices down to `-historyRows`

That matches current `TerminalBuffer` callers and minimizes blast radius.

---

## 6. New native library: `libtermux-ghostty.so`

Use a **separate shared library** for the Ghostty bridge.

### Why separate is better than stuffing it into `libtermux.so`
- Keeps PTY code isolated from Ghostty code.
- Lets the current `libtermux.so` stay simple.
- Easier rollback and A/B testing.
- Easier Gradle packaging: existing PTY native code stays ndk-build, Ghostty bridge can be Zig-built.
- Avoids forcing ndk-build to understand Ghostty's Zig build graph.

### Suggested responsibilities
`libtermux.so`
- PTY / subprocess only

`libtermux-ghostty.so`
- Ghostty terminal state
- Ghostty JNI bridge
- snapshot generation
- selection/transcript helpers
- optional key/mouse encoding later

Java can load both.

---

## 7. Build and packaging plan

Ghostty already builds `libghostty-vt` for Android in CI. That is a good signal. It also sets Android-specific linker behavior for **16 KB page size** support, which matters for Android 15+.

### Recommended build strategy
1. Vendor Ghostty as a pinned submodule or subtree, e.g.:
   - `third_party/ghostty/`
2. Add a small Zig project for the Termux shim, e.g.:
   - `terminal-emulator/src/main/zig/`
3. Build one JNI/shared library per ABI via Gradle task invoking Zig.
4. Package the resulting `.so` files into the Android app like normal JNI libs.

### ABI mapping
- `arm64-v8a` -> `aarch64-linux-android`
- `armeabi-v7a` -> `arm-linux-androideabi`
- `x86_64` -> `x86_64-linux-android`
- `x86` -> `i686-linux-android` (verify separately; Ghostty's Android CI currently covers the first three)

### NDK version
This repo currently has mixed signals:
- Gradle properties use NDK 29
- local `flake.nix` currently points at NDK 27
- Ghostty's Android CI uses **r29**

Recommendation: **standardize on NDK r29** for the Ghostty bridge.

### Build integration options

#### Preferred
Gradle task builds the Zig shared lib into intermediates, then package it.

Pros:
- clean separation
- better caching
- easiest to debug

#### Acceptable fallback
Prebuild the Zig libs externally and check/package them as prebuilt JNI artifacts.

Pros:
- simplest app-side integration

Cons:
- worse dev ergonomics
- easier for artifacts to get stale

### Important Android-specific details
- Preserve Ghostty's Android linker config for 16 KB pages.
- Set `ANDROID_NDK_HOME` for Zig builds.
- Reuse Ghostty's Android NDK path helper instead of inventing a parallel one.
- Pin Ghostty commit + Zig version in the build story.

---

## 8. The native shim should use Ghostty directly, not just mimic the wasm example

The wasm wrapper is useful as a shape reference only.

The Android/native version should look more like this:

```zig
const std = @import("std");
const ghostty = @import("ghostty-vt");

const Session = struct {
    alloc: std.mem.Allocator,
    term: ghostty.Terminal,
    stream: ghostty.Stream(TermuxHandler),
    handler: TermuxHandler,
    render_state: ghostty.RenderState = .empty,
};
```

Key point: use a **custom handler** so Ghostty actions can be translated into:
- PTY reply bytes
- title changes
- bell notifications
- clipboard operations
- color-change notifications
- dirty flags for renderer/view updates

---

## 9. Native shim responsibilities in detail

### 9.1 Session lifetime
Native session owns:
- allocator
- `ghostty.Terminal`
- stream/parser state
- pending output buffer
- pending title/clipboard strings
- dirty flags
- cached render state or snapshot scratch buffers

Java owns only a `long nativePtr`.

### 9.2 Append path
When PTY bytes arrive:
1. Java main thread calls native `append(ptr, bytes, len)`.
2. Native feeds bytes into Ghostty stream.
3. Native handler collects:
   - state changes
   - outgoing reply bytes
   - bell/title/clipboard/color events
   - scroll delta / viewport changes if relevant
4. Native returns a compact result struct or bitmask.
5. Java drains pending reply bytes and writes them back through existing `TerminalSession.write(...)`.
6. Java dispatches callbacks (`onBell`, `titleChanged`, etc.) only if flags are set.

### 9.3 Why batch results matter
Do **not** call back into Java for every action.

Prefer:
- one JNI call to append input
- one batched result
- optional follow-up calls only when data actually exists

That keeps JNI overhead controlled.

### 9.4 Resize/reset
Native side should own:
- terminal resize
- scrollback limit derived from transcript rows
- cursor/view state reset
- alt-screen switching behavior

Java still updates PTY size through existing `JNI.setPtyWindowSize(...)`.

### 9.5 State flags to expose
At minimum expose:
- cursor row / col
- cursor visible / blinking / style
- reverse video
- alt buffer active
- mouse tracking active
- cursor-keys application mode
- keypad application mode
- current palette / foreground / background / cursor colors
- active transcript row count
- active row count

These are enough to preserve current Java input and UI behavior.

---

## 10. Rendering plan

This is where the original plan needed more detail.

### Core principle
Only ship the **current viewport** to Java for drawing.

Do not push full scrollback every frame.

### Recommended snapshot shape
A native snapshot should contain:
- `rows`, `cols`
- `historyRows`
- cursor state
- reverse-video flag
- active palette / special colors
- row wrap flags
- per-cell:
  - codepoint or grapheme reference
  - display width (`0/1/2`)
  - style bits/colors mapped to Java-friendly format

A simple version:

```text
SnapshotHeader
palette[259]
rowWrap[rows]
cellCodepoint[rows * cols]
cellWidth[rows * cols]
cellStyle[rows * cols]
```

### Style mapping
For phase 1, map Ghostty cell styles into the existing Termux `TextStyle` encoding where possible:
- bold
- italic
- underline
- blink
- inverse
- invisible
- strikethrough
- dim/faint
- indexed or RGB fg/bg

That lets the current Java paint logic survive with minimal change.

### Renderer refactor target
Refactor `TerminalRenderer` to consume `ScreenSnapshot`, not `TerminalBuffer`.

That is better than trying to fully recreate `TerminalBuffer`/`TerminalRow` in Java every frame.

### Unicode / width handling
Use Ghostty's width decision as the truth.

Do not recompute width with Java `WcWidth` for snapshot-backed rendering. That will drift on emoji / graphemes / ZWJ sequences.

Java can still use font measurement scaling logic for drawing, but the terminal-cell width comes from native state.

---

## 11. Scrollback and viewport ownership

Current Termux keeps scroll position in Java using `mTopRow`.
Ghostty already has a real viewport model.

Recommendation: when Ghostty backend is active, make **native viewport state** the source of truth.

### Meaning
- scroll gesture -> native `scrollViewport(delta)`
- jump to bottom -> native `scrollViewport(bottom)`
- jump to top -> native `scrollViewport(top)`
- Java may keep a cached `mTopRow`-like value for UI math, but it should not become the true scroll owner

### Why
If Java and native both think they own scroll position, they will diverge.

### Compatibility approach
Expose a method that returns Java-compatible top-row semantics:
- `getTopRowFromBottom()` or directly `getExternalTopRow()`

Then `TerminalView` can keep most of its logic with a smaller patch.

---

## 12. Selection / transcript / URL extraction plan

These should move native-side too.

### Selection text
Expose native methods for:
- `getSelectedText(x1, y1, x2, y2)`
- optional flags for trimming / joining wrapped lines

Use Ghostty screen selection utilities internally instead of rebuilding text from a Java mirror.

### Transcript export
For transcript and shell-result capture, use Ghostty formatting utilities / screen dump helpers internally.

Expose APIs equivalent to current needs:
- transcript joined lines
- transcript unjoined lines
- transcript trim/no-trim

This replaces current `ShellUtils` dependency on `TerminalBuffer`.

### Word-at-location
Current app uses this for URL-on-tap.

Expose native `getWordAtLocation(x, y)` with Termux-compatible semantics.
Implementation can be:
- Ghostty `selectWord(...)` with a Termux boundary set, or
- a small custom scan in the shim if exact behavior matching is easier

### Why not mirror to Java?
Because selection and transcript are exactly the kind of features that become expensive and bug-prone if you maintain two screen models.

---

## 13. Input encoding plan

### Phase 1
Keep current Java keyboard path:
- `KeyHandler.getCode(...)`
- existing `TerminalSession.writeCodePoint(...)`

That only requires native exposure of mode bits:
- cursor keys application mode
- keypad application mode

### Mouse
Current Java mouse encoding can also stay initially if native exposes mouse mode state.

### Phase 2
Move to Ghostty's native encoders:
- key encoder
- mouse encoder
- bracketed paste helpers

That should improve correctness for Kitty keyboard protocol and edge cases.

So: **do not block phase 1 on native input encoding**.

---

## 14. Proposed JNI / native API surface

Keep it tight. Example:

### lifecycle
- `nativeCreate(cols, rows, transcriptRows)` -> `long`
- `nativeDestroy(ptr)`
- `nativeReset(ptr)`
- `nativeResize(ptr, cols, rows)`

### input/output
- `nativeAppend(ptr, byte[] data, int len)` -> flags/result
- `nativeDrainPendingOutput(ptr, byte[] out, int maxLen)` -> written

### state
- `nativeGetCursorRow(ptr)`
- `nativeGetCursorCol(ptr)`
- `nativeGetCursorStyle(ptr)`
- `nativeIsCursorVisible(ptr)`
- `nativeIsReverseVideo(ptr)`
- `nativeIsAltBufferActive(ptr)`
- `nativeGetModeBits(ptr)`
- `nativeGetActiveRows(ptr)`
- `nativeGetActiveTranscriptRows(ptr)`
- `nativeCopyPalette(ptr, int[] out259)`

### rendering
- `nativeFillSnapshot(ptr, int topRow, ByteBuffer out)`

### text utilities
- `nativeGetSelectedText(ptr, x1, y1, x2, y2, flags)`
- `nativeGetWordAtLocation(ptr, x, y)`
- `nativeGetTranscriptText(ptr, flags)`
- `nativeGetTitle(ptr)`

### append result flags
Have append return a bitmask like:
- screen changed
- cursor changed
- title changed
- bell
- clipboard copy request
- color changed
- reply bytes pending

That avoids lots of tiny JNI calls.

---

## 15. Migration phases

## Phase 0 — UI decoupling, no Ghostty yet
**Goal:** remove direct `TerminalBuffer` dependency from UI code.

Tasks:
- Introduce `TerminalContent` / `ScreenSnapshot` abstraction.
- Add a Java adapter backed by current `TerminalBuffer`.
- Refactor:
  - `TerminalRenderer`
  - `TerminalView`
  - selection controller
  - transcript helpers
  - URL-on-tap helper

Exit criteria:
- app behavior unchanged
- existing tests still pass
- Java backend works through the new abstraction

---

## Phase 1 — native build + smoke test
**Goal:** build and load `libtermux-ghostty.so` in the Android app.

Tasks:
- Vendor Ghostty at pinned commit.
- Add Zig build for Android ABIs.
- Align build env to NDK r29.
- Add Gradle packaging task.
- Add a tiny JNI smoke API:
  - create
  - write bytes
  - dump plain visible text
  - destroy

Exit criteria:
- app loads the lib on device
- headless JNI test proves Ghostty terminal state works

---

## Phase 2 — native terminal core, no renderer swap yet
**Goal:** prove full interactive VT behavior, not readonly replay behavior.

Tasks:
- Implement custom Ghostty stream handler.
- Support reply bytes back to PTY.
- Surface bell/title/clipboard/color events.
- Expose cursor/mode/state getters.
- Wire a proxy `TerminalEmulator`/backend object.

Exit criteria:
- shell prompt works
- `tput`, cursor reports, title changes, bell, clipboard ops behave correctly
- no UI rendering change required yet for validation

---

## Phase 3 — viewport snapshot renderer
**Goal:** draw Ghostty state using existing Java `Canvas` renderer path.

Tasks:
- Implement native viewport snapshot API.
- Port `TerminalRenderer` to `ScreenSnapshot`.
- Hook `TerminalView` scroll logic to native viewport.
- Preserve cursor blink / reverse video / palette behavior.

Exit criteria:
- normal shell usage works visually
- scrollback works
- alt screen works
- htop / less / vim are usable

---

## Phase 4 — text selection and transcript utilities
**Goal:** remove remaining `TerminalBuffer` dependencies.

Tasks:
- native `getSelectedText`
- native `getWordAtLocation`
- native transcript exports
- selection cursor controller integration
- URL-on-tap integration

Exit criteria:
- text selection works in history + visible viewport
- transcript export matches expectations
- URL-on-tap works again

---

## Phase 5 — native input encoders (optional but recommended)
**Goal:** improve protocol correctness, reduce duplicated mode logic.

Tasks:
- move mouse encoding to Ghostty encoder
- move key encoding where it helps
- keep Android-specific key mapping glue in Java where needed

Exit criteria:
- kitty keyboard / mouse behavior improved
- no regressions in normal typing/navigation

---

## Phase 6 — cleanup / fallback strategy
**Goal:** decide long-term structure.

Options:
- fully replace Java emulator backend
- keep Java backend behind a dev flag for fallback / unsupported ABI

Recommendation:
Keep a fallback until Ghostty backend is stable across all supported ABIs and main workflows.

---

## 16. Testing plan

### Differential / compatibility tests
Add backend-agnostic tests that can run against:
- current Java emulator backend
- Ghostty backend

Compare at least:
- visible text
- cursor position
- alt-screen behavior
- mode bits
- transcript text

### Device / app tests
Run real apps:
- shell prompt / readline
- `less`
- `vim` / `nvim`
- `htop`
- `tmux`
- `ranger` / `mc`

### Unicode / width tests
Must cover:
- combining marks
- emoji
- ZWJ sequences
- East Asian wide chars
- fullwidth + ambiguous width edge cases

### Protocol tests
Specifically test:
- CPR / DSR replies
- title updates
- bell
- OSC 52 clipboard
- cursor style changes
- color changes via OSC 4/10/11/12

### ABI / packaging tests
Build and load on:
- arm64-v8a
- armeabi-v7a
- x86_64
- x86 if still required

---

## 17. Risks and mitigations

| Risk | Mitigation |
| --- | --- |
| Ghostty API churn | Pin exact Ghostty commit. Wrap it behind our own tiny C/JNI ABI. |
| Public C API lacks interactive callbacks | Use custom Zig shim over Ghostty Zig module, not raw `ghostty_terminal_vt_write()` alone. |
| JNI overhead | Batch append results. Use one viewport snapshot call. Prefer `DirectByteBuffer` for large transfers. |
| Two screen models drifting | Do not mirror full scrollback into Java. Native is source of truth. |
| Scroll semantics regress | Move viewport ownership native-side. Preserve Java external row coordinate contract. |
| Unicode width mismatches | Use native width data from Ghostty; do not recompute with Java for layout decisions. |
| Memory leaks / lifetime bugs | Explicit `destroy()`, one owner for native ptr, optional `Cleaner` as backup only. |
| Android build complexity | Separate Ghostty JNI lib from existing PTY lib. Standardize NDK version. |
| x86 support uncertainty | Verify separately; keep Java fallback if necessary. |
| Performance regressions | Measure JNI copies, snapshot size, frame time, GC pressure. Optimize only after phase 3 works. |

---

## 18. Recommended file layout

Suggested new files/directories:

```text
third_party/ghostty/

terminal-emulator/src/main/zig/
  build.zig
  src/termux_ghostty.zig
  src/jni_exports.zig
  include/termux_ghostty.h

terminal-emulator/src/main/java/com/termux/terminal/
  GhosttyNative.java
  TerminalContent.java
  ScreenSnapshot.java
  JavaTerminalContentAdapter.java
  GhosttyTerminalContent.java
```

Possible Java structure choice:
- keep `TerminalEmulator` public shape, but internally delegate to either Java or Ghostty backend
- or introduce `TerminalBackend` and keep `TerminalEmulator` as facade

Either is fine. Main thing: stop exposing `TerminalBuffer` as the UI contract.

---

## 19. First milestone I would actually implement

If I were doing this in order, I would not start with renderer work.

### Milestone A — headless interactive proof
Build a small JNI-backed Ghostty session that can:
- create terminal
- append PTY bytes
- return reply bytes
- report cursor position
- dump plain visible text
- surface title/bell/color flags

That proves we solved the **real** problem: interactive terminal semantics, not just replay rendering.

### Milestone B — viewport snapshot on screen
Once A works, wire the viewport snapshot into `TerminalRenderer` and get a visible shell prompt rendering in-app.

That sequence reduces risk a lot.

---

## 20. Bottom line

The right plan is:
1. decouple the UI from `TerminalBuffer`
2. add a separate Zig-built JNI shared lib
3. build a custom Ghostty shim, not a thin wrapper over readonly `ghostty_terminal_vt_write()`
4. keep native state as source of truth
5. transfer only viewport snapshots to Java
6. move transcript/selection/word queries native-side
7. optionally move input encoding native-side later

That gets you a realistic path to Ghostty-powered Termux on Android without pretending the current C API is already a drop-in replacement for `TerminalEmulator.java`.
