---
name: terminal-architecture
description: Required architecture and concurrency reference for this repository's Ghostty terminal. Use before reviewing or changing anything that interacts with terminal-emulator, terminal-compose-view, TerminalSessionBackend, terminal input/IME/gestures, frame publication, rendering, viewport state, or terminal JNI/native code.
---

# Terminal Architecture

Read [`../../../docs/fork/terminal-architecture.md`](../../../docs/fork/terminal-architecture.md) completely before inspecting or editing terminal code.

## Workflow

1. Classify the change: output transport, native mutation, frame publication, Compose frame application, rendering, input, viewport, resize, or lifecycle.
2. Trace the full path: producer -> queue -> mutable owner -> publication -> consumer.
3. Write down the owner thread and whether each object is mutable transport or immutable publication.
4. Check the invariants below before coding.
5. Add a regression test at the real seam.
6. Run the module tests and required linters.

## Non-negotiable invariants

- `GhosttySessionWorker` serializes mutable native terminal state.
- Compose and render code consume complete immutable `TerminalFrame`s only.
- Never render a partial `FrameDelta` transport snapshot directly.
- Frame sequences are monotonic; missing partial frames require a full refresh.
- Terminal bytes are never dropped. Wakeups and frame builds may be coalesced.
- Mode-dependent input must be routed on the worker after parsing PTY bytes already queued before the input.
- Never use rendered-frame flags or volatile `TerminalSession` mode caches to authoritatively route input.
- Pointer commands carry event-time geometry; do not substitute cached resize geometry.
- Frame reason flags are bitsets; use bitwise membership tests.
- Do not call session, JNI, or backend mutation while drawing.
- Do not introduce unbounded queues, mutable published frames, direct native pointers in Kotlin, or shared “pending command” fields that destroy Handler ordering.

## Known traps

- `TerminalSessionCommandAdapter` key/cursor encoding, `ExtraKeysToolbar`, and `TerminalSession.paste()` still contain cached-mode technical debt. Deepen worker-owned semantic input instead of adding cache refreshes.
- Full selected-text extraction is a cold synchronous native read; do not copy that pattern into hot paths.
- SSH pending operations are unbounded, and synchronized-output publication gating is not implemented. Preserve these as explicit debt.
- `terminal-view` is the legacy View path. Its behavior is not the Compose architecture.
- Grid-equivalent resizes are intentionally coalesced, so current canvas pixels can differ from the last native resize pixels.
- Row cache reuse must include row identity/content, overlay state, link state, palette/reverse-video state, cell-layout kind, and column geometry.
- IME indices are UTF-16 offsets; terminal movement is Unicode code points. Clamp code-point movement before calling `offsetByCodePoints`.

## Required reading by change

| Change | Read |
|---|---|
| Backend interface or command | `TerminalBackend.kt`, `TerminalCommand.kt`, `TerminalSessionBackend.kt` |
| Input, IME, gesture | `TerminalInputTranslator.kt`, `ImeEditCommandProcessor.kt`, `TerminalGestures.kt`, `TerminalSessionCommandAdapter.kt`, worker input handlers |
| Native/PTY/modes | `TerminalSession.java`, `GhosttySessionWorker.java`, `GhosttyTerminalContent.java`, `termux_ghostty.zig` |
| Frames/caching | `FrameDelta.java`, `RenderFrameCache.java`, `TerminalSessionFrameAdapter.kt`, `TerminalFrame.kt` |
| Rendering | `TerminalController.kt`, `TerminalRenderNodeRenderer.kt`, `TerminalRowRenderer.kt`, `TerminalRowRuns.kt` |
| Resize/pointer | `TerminalMetrics.kt`, `TerminalPointerEvent.kt`, `TerminalController.resizeIfNeeded()` |

## Verification

```bash
./gradlew :terminal-emulator:testDebugUnitTest \
  :terminal-compose-view:testDebugUnitTest \
  :compose-app:testUniversalDebugUnitTest
./gradlew ktlintFormat detekt
```

Do not install, launch, or run device/emulator validation unless the user explicitly requests it.
