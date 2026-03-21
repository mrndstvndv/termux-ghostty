# Libghostty Implementation Checklist

This is the short execution doc for the plan in `LIBGHOSTTY_INTEGRATION_PLAN.md`.

Current ANR/performance stabilization follow-up: `LIBGHOSTTY_ANR_STABILIZATION_PLAN.md`.

Scope of this checklist:
- make the next edits obvious
- keep the migration incremental
- point at the bootstrap files added for the JNI/Zig bridge

---

## 1. Files added as bootstrap skeletons

### Java
- `terminal-emulator/src/main/java/com/termux/terminal/TerminalContent.java`
- `terminal-emulator/src/main/java/com/termux/terminal/ScreenSnapshot.java`
- `terminal-emulator/src/main/java/com/termux/terminal/GhosttyNative.java`
- `terminal-emulator/src/main/java/com/termux/terminal/GhosttyTerminalContent.java`

### Zig
- `terminal-emulator/src/main/zig/build.zig`
- `terminal-emulator/src/main/zig/include/termux_ghostty.h`
- `terminal-emulator/src/main/zig/src/termux_ghostty.zig`
- `terminal-emulator/src/main/zig/src/jni_exports.zig`

### Status of the bootstrap files
These are **shape files**, not the full integration.

What they do now:
- define the Java-side backend contract
- define the snapshot container
- define the native method surface
- define a small native session ABI
- define JNI exports for the Java methods

What they do **not** do yet:
- no Ghostty dependency is wired in yet
- no real JNI byte-array / string marshalling yet
- no PTY reply handling yet
- no real native screen snapshot implementation yet

What is now done:
- Gradle builds and packages `libtermux-ghostty.so` for all supported Android ABIs
- renderer-facing code consumes `TerminalContent` + `ScreenSnapshot`
- Java-backed snapshot rendering works through `JavaTerminalContentAdapter`

---

## 2. Commit order

## Commit 1 — abstraction only
- [x] Introduce `TerminalContent` into renderer-facing code.
- [x] Add a Java-backed adapter around the existing `TerminalEmulator` / `TerminalBuffer` path.
- [x] Change `TerminalRenderer` to render from `ScreenSnapshot` rather than `TerminalBuffer`.
- [x] Keep app behavior identical.

Exit condition:
- current Java emulator still works
- no Ghostty code path required yet

## Commit 2 — scroll / selection / transcript decoupling
- [x] Route `TerminalView` scroll math through `TerminalContent` APIs.
- [x] Stop direct `TerminalBuffer` reads in selection code.
- [x] Move transcript helpers away from `TerminalBuffer` as a public dependency.
- [x] Keep existing Java adapter behavior stable.

Exit condition:
- no UI code depends on `TerminalBuffer` directly

## Commit 3 — vendor Ghostty + build the native lib
- [x] Add pinned Ghostty source under `third_party/ghostty/`.
- [x] Standardize build env on NDK r29.
- [x] Add Gradle task that invokes `zig build` for each Android ABI.
- [x] Package `libtermux-ghostty.so` into the app.
- [x] Verify library load from Java.

Exit condition:
- device build contains the new shared lib
- `System.loadLibrary("termux-ghostty")` succeeds

## Commit 4 — replace the stub native session with a real Ghostty-backed session
- [x] Replace the placeholder `Session` in `termux_ghostty.zig` with:
  - `ghostty.Terminal`
  - custom stream handler
  - pending output buffer
  - pending title / clipboard state
  - render scratch state
- [x] Keep the exported ABI stable while internals change.
- [x] Implement append processing using Ghostty's full action stream, not readonly-only behavior.

Exit condition:
- PTY bytes change native terminal state
- handler records interactive side effects

## Commit 5 — PTY reply plumbing
- [x] Implement append result flags.
- [x] Drain pending output bytes from native.
- [x] Feed those bytes back into existing `TerminalSession.write(...)`.
- [x] Surface pending title / clipboard / bell / color-change events.

Exit condition:
- CPR / DSR / title / bell / OSC 52 behavior works again

## Commit 6 — first visible rendering path
- [x] Implement native viewport snapshot fill.
- [x] Teach `GhosttyTerminalContent.fillSnapshot(...)` to use it.
- [x] Render the snapshot through the existing Java `Canvas` path.
- [x] Preserve cursor, palette, reverse video, and alt-screen behavior.

Exit condition:
- visible shell prompt renders in-app using Ghostty backend

## Commit 7 — transcript / selection / word lookup
- [ ] Implement native transcript export.
- [ ] Implement native selected text extraction.
- [ ] Implement native word lookup.
- [ ] Hook URL-on-tap and text selection back up.

Exit condition:
- selection and transcript features work without `TerminalBuffer`

## Commit 8 — optional native input encoding
- [ ] Decide whether to move key encoding native-side.
- [ ] Decide whether to move mouse encoding native-side.
- [ ] Keep Java fallback until parity is proven.

---

## 3. Exact next edits

### 3.1 UI abstraction pass
- [x] Add `JavaTerminalContentAdapter`.
- [x] Change `TerminalRenderer.render(...)` to accept `TerminalContent` + `ScreenSnapshot`.
- [x] Replace direct `TerminalBuffer` line walking with snapshot walking.
- [x] Replace direct history row queries with `getActiveTranscriptRows()`.

### 3.2 Native build pass
- [x] Wire `terminal-emulator/src/main/zig/build.zig` into Gradle.
- [x] Add ABI-specific output directories under `terminal-emulator/build/generated/zigJniLibs/main/jni/<abi>/` and `terminal-emulator/build/intermediates/zigInstall/<abi>/`.
- [x] Copy resulting `.so` files into packaging inputs.

### 3.3 Ghostty session pass
- [ ] Vendor Ghostty.
- [ ] Import `ghostty-vt` from the shim build.
- [ ] Replace placeholder `Session` fields in `termux_ghostty.zig`.
- [ ] Keep `jni_exports.zig` unchanged if possible.

### 3.4 Event plumbing pass
- [ ] Add append result flag handling in Java.
- [ ] Drain pending PTY replies after append.
- [ ] Consume pending title / clipboard strings.
- [ ] Trigger `TerminalOutput` callbacks from Java after append.

---

## 4. Stable ABI target for the first real bridge

Keep this native surface stable while internals evolve:

### lifecycle
- [ ] `nativeCreate(columns, rows, transcriptRows)`
- [ ] `nativeDestroy(handle)`
- [ ] `nativeReset(handle)`
- [ ] `nativeResize(handle, columns, rows)`

### input/output
- [ ] `nativeAppend(handle, data, offset, length)`
- [ ] `nativeDrainPendingOutput(handle, buffer, offset, length)`

### state
- [ ] `nativeGetColumns(handle)`
- [ ] `nativeGetRows(handle)`
- [ ] `nativeGetActiveRows(handle)`
- [ ] `nativeGetActiveTranscriptRows(handle)`
- [ ] `nativeGetModeBits(handle)`
- [ ] `nativeGetCursorRow(handle)`
- [ ] `nativeGetCursorCol(handle)`
- [ ] `nativeGetCursorStyle(handle)`
- [ ] `nativeIsCursorVisible(handle)`
- [ ] `nativeIsReverseVideo(handle)`
- [ ] `nativeIsAlternateBufferActive(handle)`

### text helpers
- [ ] `nativeGetSelectedText(handle, x1, y1, x2, y2, flags)`
- [ ] `nativeGetWordAtLocation(handle, x, y)`
- [ ] `nativeGetTranscriptText(handle, flags)`
- [ ] `nativeConsumeTitle(handle)`
- [ ] `nativeConsumeClipboardText(handle)`

### rendering
- [ ] `nativeFillSnapshot(handle, topRow, buffer, capacity)`

---

## 5. Guardrails

- [ ] Do not make Java `TerminalBuffer` the source of truth again.
- [ ] Do not rely on raw `ghostty_terminal_vt_write()` readonly semantics for PTY sessions.
- [ ] Do not do per-cell JNI calls.
- [ ] Do not move the renderer until the abstraction layer lands.
- [ ] Do not switch input encoding native-side until viewport rendering is stable.
- [ ] Do not track Ghostty `main` loosely; pin a commit.

---

## 6. Definition of done for the first usable Ghostty milestone

All must be true:
- [ ] app launches with Ghostty backend enabled behind a flag
- [ ] shell prompt renders correctly
- [ ] resize works
- [ ] scrollback works
- [ ] alt screen works
- [ ] title updates work
- [ ] bell works
- [ ] PTY replies work
- [ ] text selection works
- [ ] transcript export works
- [ ] `less`, `vim`, `htop` are usable
- [ ] Java backend still exists as fallback
