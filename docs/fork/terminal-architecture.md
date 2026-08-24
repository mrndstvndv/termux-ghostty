# Terminal architecture and invariants

This is the canonical architecture reference for the Compose Ghostty terminal path. It supersedes the older View-oriented descriptions in `docs/ghostty-backend-walkthrough.md` for `compose-app`.

## Scope

The active Compose path spans four Gradle modules:

```text
compose-app
  depends on terminal-compose-view + terminal-compose-session + terminal-emulator

terminal-compose-session
  depends on terminal-compose-view + terminal-emulator

terminal-compose-view
  depends internally on terminal-emulator for width/style primitives

terminal-emulator
  owns PTY lifecycle, Ghostty JNI, native VT state, frame transport, and worker scheduling
```

The legacy `terminal-view` module has been removed. The XML hosts in `app` and the
Compose workspace use `TerminalComposeView`; session construction remains in the
app/session-adapter layer. Do not infer current behavior from historical
`TerminalView` descriptions.

## Deep modules and seams

### `terminal-compose-view`: backend-neutral UI module

External seam:

- `TerminalCanvas`
- `TerminalBackend`
- `TerminalCommand`
- `TerminalFrame`
- `TerminalSize`, pointer geometry, selection, configuration, and diagnostics types

Responsibilities:

- Compose lifecycle and focus
- gesture recognition
- platform IME adaptation
- selection and links
- terminal grid measurement
- retained row-layer rendering
- conflated UI invalidation and animation frame scheduling

It must not know `TerminalSession`, `GhosttySessionWorker`, JNI handles, PTY queues, or native snapshot buffers.

### `terminal-compose-session`: session adapter

Main seam:

- `TerminalSessionBackend` adapts `TerminalSession` to `TerminalBackend`.
- `TerminalSessionCommandAdapter` translates neutral commands into session input.
- `TerminalSessionFrameStore` copies worker transport deltas into immutable Compose frames.

Responsibilities:

- main-thread backend lifecycle
- applying contiguous `FrameDelta` publications
- requesting full recovery after sequence gaps
- adapting session-specific input and viewport commands
- reporting recoverable backend failures

This layer is an adapter, not a second terminal emulator. It must not duplicate VT mode policy that belongs to the native-state owner.

### `terminal-emulator`: terminal engine and owner

Main runtime module:

- `TerminalSession` owns process/custom-IO lifecycle and public session operations.
- `GhosttySessionWorker` is the serialized owner of mutable Ghostty state.
- `GhosttyTerminalContent` is the synchronized Java/JNI adapter.
- `GhosttyNative` and `jni_exports.zig` form the JNI seam.
- `termux_ghostty.zig` owns Ghostty `Terminal`, parser, render state, viewport, protocol replies, and snapshot serialization.
- `FrameDelta`, `ScreenSnapshot`, `ViewportLinkSnapshot`, and `RenderFrameCache` form the frame transport.
- Ghostty binary state snapshots and scrollback compression remain worker-owned native maintenance operations; they are separate from frame transport snapshots.

## Runtime actors and ownership

```text
PTY reader / custom IO producer
    -> bounded ByteQueue (process output)
    -> Ghostty worker Looper
    -> GhosttyTerminalContent
    -> JNI / native Ghostty Session

Ghostty worker
    -> mutable staging snapshots A/B
    -> atomic FrameDelta publication
    -> main-thread session callback

main thread
    -> TerminalSessionBackend.refresh()
    -> RenderFrameCache + TerminalSessionFrameStore
    -> immutable TerminalFrame
    -> TerminalController
    -> retained row renderer
```

### Thread rules

1. **Native terminal mutation is worker-confined.** Append, resize, reset, viewport mutation, mouse encoding, focus encoding, scroll routing, snapshot fill, and protocol-output drain run on `GhosttySessionWorker`.
2. **Initialization is the exception.** `TerminalSession.initializeTerminalBackend()` creates native content and reads initial metadata before starting the worker.
3. **Compose state is main-thread confined.** `TerminalBackend`, `TerminalController`, frame-store application, selection, and render resources live on the main thread.
4. **PTY transport threads do transport only.** They enqueue/dequeue bytes; they do not inspect terminal modes or mutate UI state.
5. **Cold synchronous native reads are technical debt.** Full selected-text extraction currently calls synchronized `GhosttyTerminalContent` from the main thread. Do not add more direct UI/native reads.

## Output and frame pipeline

### PTY output

1. Local/custom output enters `mProcessToTerminalIOQueue`.
2. `onOutputAvailable()` schedules one coalesced append message.
3. The worker parses bounded byte/time slices with `appendToNative()`.
4. Native append mutates terminal state and returns side-effect flags.
5. The worker refreshes Java metadata caches and drains replies/title/clipboard/notification/progress effects.
6. Dirty state schedules a snapshot at the 16 ms publication cadence.

Terminal bytes are never dropped. Frame builds and UI wakeups may be coalesced.

Native SSH output uses worker `MSG_APPEND_DIRECT`; it still reaches the same native owner and publication path.

### Frame publication

1. `GhosttySessionWorker` fills a mutable staging `ScreenSnapshot` and `ViewportLinkSnapshot`.
2. `FramePublicationGate` prevents staging-buffer reuse while the main thread is copying the publication.
3. `FrameDelta` carries a monotonic sequence, reason bitset, partial/full transport snapshot, and full viewport-link snapshot.
4. `TerminalSessionBackend.refresh()` applies only contiguous deltas through `RenderFrameCache`.
5. A missing or incompatible partial delta requests a native full refresh; callers never guess missing rows.
6. `TerminalSessionFrameStore` publishes a complete immutable `TerminalFrame`.

Transport snapshots are not UI frames. Partial snapshots may omit unchanged rows and metadata. Never render `FrameDelta.transportSnapshot` directly.

### Retained rendering

- `TerminalFrameContentCache` preserves immutable row identity across unchanged rows.
- `TerminalRenderNodeRenderer` retains one Compose graphics layer per visible row.
- Row layers rotate only when absolute-row identity proves scroll reuse is valid.
- A replacement `TerminalRow`, changed link hash, selection, cursor, palette, reverse-video state, or geometry invalidates the affected row.
- `RowRunCache` reuse requires matching content, overlays, cell-layout kind, and column geometry.
- Animated shader rendering uses the bitmap path; non-animated rendering uses retained row layers.

A published `TerminalFrame`, its rows, palette, cell layout, and link layout must remain immutable for the lifetime of every consumer holding it.

## Input pipeline

### Compose input

```text
platform key / IME / gesture
    -> TerminalInputTranslator or gesture recognizer
    -> TerminalCommand
    -> TerminalController.submit()
    -> TerminalSessionBackend
    -> TerminalSessionCommandAdapter
    -> TerminalSession / Ghostty worker
```

The IME state machine tracks an invisible platform buffer and emits semantic text/delete/cursor operations. IME positions are UTF-16 offsets; terminal cursor movement is in Unicode code points.

### Mode-dependent input ordering

Terminal modes are established by PTY output. A volatile Java cache only means “last parsed state visible to this thread”; it does not mean queued PTY bytes have been parsed.

Required ordering for any input whose bytes depend on native modes:

1. enqueue a semantic input event to the Ghostty worker;
2. parse PTY bytes that were already queued when that event arrived;
3. read live native mode state on the worker;
4. choose/encode the input route;
5. emit bytes or mutate viewport on that same worker turn.

Scroll, mouse/tap, and focus follow this rule. Never route them from a rendered frame or `TerminalSession` mode cache.

Pointer commands must carry event-time pixel/cell geometry. Resize coalescing may intentionally leave the last PTY resize pixel dimensions older than the current canvas.

### Viewport scrolling

- A vertical gesture emits incremental row deltas.
- The worker decides between mouse wheel input, alternate-screen arrow keys, and ordinary-shell scrollback.
- Worker-local `mCurrentTopRow` is the mutation source.
- A published `REASON_VIEWPORT_SCROLL` frame synchronizes `TerminalSessionBackend.topRow` from `FrameDelta.topRow`.
- Reason flags are a bitset. Test with `flags and REASON != 0`, never equality.
- Viewport commands retain Handler queue order; do not coalesce them through a shared mutable “pending top row”.

## Resize semantics

`TerminalController` derives terminal columns from raw measured cell width and visual placement from rounded cell width. Grid-equivalent pixel changes are coalesced to avoid reflow/SIGWINCH churn.

Consequences:

- `TerminalSize` passed to the backend is the last applied PTY/native geometry.
- pointer and wheel input must use geometry captured from current `TerminalMetrics`, not that cached resize;
- font/typeface changes invalidate measurement and retained render resources;
- resize, active-screen change, large viewport jump, and incompatible partial damage require a full frame rebuild.

## Backpressure and scheduling

- Local process output uses a bounded 64 KiB `ByteQueue` and worker parse slices.
- Scrollback compression waits 250 ms after compression-relevant activity, then runs bounded native steps 1 ms apart on the worker. It never publishes a frame because logical terminal content is unchanged.
- Snapshot builds and main-thread callbacks are coalesced; terminal bytes are not.
- Compose invalidation uses a conflated channel because it is a wakeup signal, not frame storage.
- The immutable frame store holds the latest complete state.
- `TerminalSessionIOBridge` preserves SSH write/resize ordering on one executor, but its pending-operation queue is currently unbounded. Treat this as known debt; never add another unbounded terminal queue.

## Cached state: allowed and forbidden uses

Worker-published volatile caches in `TerminalSession` are valid for:

- display metadata attached to an already-applied frame;
- diagnostics and non-authoritative UI hints;
- avoiding JNI reads where slight publication lag is harmless.

They are forbidden as the authoritative source for:

- choosing scrollback versus terminal input;
- deciding whether to drop mouse/tap/focus events;
- encoding mode-sensitive keys, keypad keys, Kitty keyboard sequences, or bracketed paste;
- ordering an input event against unparsed PTY output.

## Binary terminal state snapshots

Ghostty's binary terminal snapshot is not a `ScreenSnapshot`. Capture and restore requests enter `GhosttySessionWorker`, which first orders them against queued PTY output and then calls native snapshot codecs. Capture includes VT stream continuation state. Restore validates into temporary native state, preserves current host geometry, and replaces the live terminal only after complete validation. A successful restore resets the viewport and publishes a full immutable frame.

The current Java/Compose seam materializes a complete snapshot as `byte[]`. Streaming READY/history restoration would require a worker-owned decoder and monotonic full-frame publications as restored history grows.

## Known architectural debt

1. `TerminalSessionCommandAdapter` still encodes ordinary key/cursor commands from cached cursor/keypad modes on the main thread.
2. `ExtraKeysToolbar` encodes special keys/macros from cached cursor/keypad/Kitty modes and writes directly to the session.
3. `TerminalSession.paste()` reads cached bracketed-paste mode outside the worker.
4. full selected-text extraction synchronously enters native content from the main thread.
5. `TerminalSessionIOBridge.pendingOperations` is unbounded when an SSH channel stalls.
6. Synchronized-output mode is not exposed to the worker, so publication is not yet deferred across application-controlled atomic redraws.
7. The XML host still exposes Java activity callbacks for lifecycle and context-menu policy; these are app integration seams, not terminal rendering or session ownership.

When touching one of these paths, deepen the worker-owned semantic-input interface instead of refreshing caches or adding another fallback.

## Change checklist

Before changing `terminal-emulator` or `terminal-compose-view`:

1. Identify the mutable owner and thread for every state touched.
2. Trace the full path: producer -> queue -> owner -> publication -> consumer.
3. State whether the data is mutable transport or immutable publication.
4. For mode-sensitive input, define its ordering against queued PTY output.
5. Preserve monotonic frame sequences and full-refresh recovery.
6. Preserve event-time pointer geometry.
7. Keep renderer decisions frame-only; never call session/native code while drawing.
8. Add a regression test at the seam that can reproduce stale state, sequence gaps, geometry changes, or Unicode boundaries.
9. Run:

```bash
./gradlew :terminal-emulator:testDebugUnitTest \
  :terminal-compose-view:testDebugUnitTest \
  :compose-app:testUniversalDebugUnitTest
./gradlew ktlintFormat detekt
```

Do not install builds or run devices/emulators unless the user explicitly requests it.

## Primary files

Read in this order for cross-module changes:

1. `terminal-compose-view/src/main/java/com/termux/terminal/compose/TerminalBackend.kt`
2. `terminal-compose-view/src/main/java/com/termux/terminal/compose/TerminalCommand.kt`
3. `terminal-compose-view/src/main/java/com/termux/terminal/compose/TerminalFrame.kt`
4. `terminal-compose-session/src/main/java/com/termux/terminal/compose/session/TerminalSessionBackend.kt`
5. `terminal-compose-session/src/main/java/com/termux/terminal/compose/session/TerminalSessionCommandAdapter.kt`
6. `terminal-compose-session/src/main/java/com/termux/terminal/compose/session/TerminalSessionFrameAdapter.kt`
7. `terminal-emulator/src/main/java/com/termux/terminal/TerminalSession.java`
8. `terminal-emulator/src/main/java/com/termux/terminal/GhosttySessionWorker.java`
9. `terminal-emulator/src/main/java/com/termux/terminal/GhosttyTerminalContent.java`
10. `terminal-emulator/src/main/zig/src/termux_ghostty.zig`
