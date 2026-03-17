# Ghostty Backend Worker Architecture and Native-Aligned Migration Plan

This document describes the current Ghostty worker-based backend in Termux, what it fixed, where it still falls short, and the migration plan to move the Android architecture closer to upstream Ghostty's persistent render-state model.

## 1. Problem Statement
Previously, the Ghostty backend performed terminal state mutation (appending PTY output) and screen snapshot generation directly on the Android UI thread. During bursty scenarios—such as rapid resizing in `tmux` over SSH or large output floods—the UI thread would become saturated with JNI calls and native parsing work, leading to input dispatch timeouts and ANRs.

## 2. Threading Model
The architecture now utilizes three distinct threads to manage the terminal lifecycle:

| Thread | Responsibility |
| --- | --- |
| **PTY Reader** | Reads raw bytes from the shell/process and pushes them into a `ByteQueue`. |
| **Ghostty Worker** | The **sole owner** of the native Ghostty terminal state. Drains the queue, appends to native, handles resizes/resets, and builds snapshots. |
| **UI Thread** | Manages Android View lifecycle. Applies published `FrameDelta` objects to a persistent `RenderFrameCache` and renders that cache only. Handles user input by writing directly to the PTY. |

### Key Rule
The UI thread **never** performs native Ghostty terminal state mutation. It only consumes immutable or thread-safe cached data, and it never renders a worker-published Ghostty `ScreenSnapshot` directly.

## 3. GhosttySessionWorker
The `GhosttySessionWorker` is a dedicated thread introduced to decouple terminal logic from the UI.

### Responsibilities:
- **Asynchronous Appending**: Processes PTY output without blocking the UI.
- **Resize Coalescing**: Resizes are enqueued. If multiple resize requests arrive before the worker processes them, only the latest one is applied.
- **Side-Effect Marshalling**: Captures native events (bell, title change, clipboard, PTY replies) and posts them back to the main thread.
- **Frame Publication**: Generates `FrameDelta` publications whose attached `ScreenSnapshot` payload is transport/debug data for `RenderFrameCache`, not a directly rendered frame.

## 4. FrameDelta Publication & Double-Buffering
To ensure the UI thread always has a frame to draw without waiting for native work, a double-buffered transport/publication system was implemented.

1.  **Staging Transport Snapshot**: The worker builds the next partial or full payload into a staging `ScreenSnapshot`.
2.  **Atomic Publication**: Once the payload is ready, the worker publishes a `FrameDelta` that points at that transport snapshot and carries the frame sequence, dirty rows, and reason flags.
3.  **UI Cache Apply**: The main thread consumes the latest `FrameDelta` and applies it to the persistent `RenderFrameCache`.
4.  **Zero-Wait Draw**: `TerminalView.onDraw()` renders the cache only. It never renders the worker's transport snapshot directly.

## 5. Performance Optimizations

### 5.1 Snapshot Debouncing
To prevent CPU saturation during massive output (e.g., `cat` of a large file), the worker debounces snapshot builds. It ensures that snapshots are built at most once every **16ms (~60fps)**, collapsing multiple appends into a single render frame.

### 5.2 Lock Contention Reduction
The `GhosttyTerminalContent.fillSnapshot` method was refactored to minimize the time spent holding the native handle lock.
- **Synchronized Phase**: Only the raw native memory copy and metadata retrieval are synchronized.
- **Parallel Phase**: The heavy parsing of the native snapshot into Java-side row objects happens **outside** the synchronized block, allowing the UI thread to perform other JNI-safe operations (like selection or accessibility queries) concurrently.

### 5.3 Metadata Caching
`TerminalSession` now maintains `volatile` cached copies of terminal state (Columns, Rows, Active Rows, Mode Bits, Cursor Info). 
- These are updated by the worker thread after every state mutation.
- The UI thread reads these cached values for scroll-range calculations and accessibility, completely avoiding JNI calls in the majority of UI operations.

### 5.4 Buffer Recycling
`ScreenSnapshot` now caches its duplicated `ByteBuffer`. This eliminates per-frame allocations during the parsing phase, significantly reducing Garbage Collection (GC) pressure and frame hitching.

## 6. Thread Safety
- **Native Handle Synchronization**: All direct access to the native Ghostty handle in `GhosttyTerminalContent` is `synchronized`.
- **Atomic State**: `AtomicInteger` (for scroll counters) and `AtomicReference` (for snapshots) are used to ensure safe communication between the worker and UI threads.
- **Immutable Snapshots**: Once a snapshot is published, it is treated as immutable by the worker until it is swapped out of the "published" slot.

## 7. Current Status
- **Zero hot-path JNI in `onDraw()`**: Rendering is now a pure Java/Canvas operation using pre-parsed data.
- **ANR elimination**: High-frequency output and resize storms no longer block the UI thread badly enough to trigger the previous input-dispatch timeouts.
- **Remaining issue: tmux scroll latency**: Repaint-heavy workloads still lag because the pipeline is still fundamentally **full-frame snapshot based**.

Current hot path under scroll/output load:
1. Native terminal state is mutated on the worker.
2. Native `render_state` is updated for the viewport.
3. The full visible frame is serialized into a snapshot buffer.
4. Java parses that full buffer into row objects.
5. `TerminalRenderer` walks the full visible frame again on every draw.

That architecture is good enough for ANR prevention, but it still throws away one of upstream Ghostty's main advantages: **persistent render state plus dirty-row rebuilds instead of full-frame flattening**.

## 8. What Upstream Ghostty Does Differently
The upstream Ghostty renderer is built around **persistent render state**, **dirty tracking**, and **renderer-owned caches**, not whole-frame snapshot publication.

Relevant upstream files:
- `src/terminal/render.zig`: persistent `RenderState`, viewport pin tracking, row/page dirty handling.
- `src/renderer/generic.zig`: rebuilds only dirty rows into renderer-side cached cell buffers.
- `src/renderer/cell.zig`: row-wise cached cell storage designed for partial rebuild.
- `src/termio/Termio.zig` and `src/renderer/Thread.zig`: mailbox/thread split between terminal IO and rendering wakeups.

Principles to carry over into Termux:
- Keep a **persistent native render model** alive across frames.
- Treat scrolling/output as **incremental updates**, not full viewport reserialization.
- Separate **frame invalidation** from generic terminal-content notifications.
- Keep a **renderer-owned cache** that survives across frames.
- Reserve full rebuilds for **global changes** only: resize, palette changes, font changes, alternate-screen switch, full clear, etc.

## 9. Target Architecture for Termux

### 9.1 Threads and Ownership
| Thread | Responsibility |
| --- | --- |
| **PTY Reader** | Reads process output and enqueues mailbox messages. |
| **Ghostty TermIO Worker** | Sole owner of native terminal mutation: append, resize, reset, viewport scroll, mode changes, side effects. |
| **Frame Publisher** | Maintains a persistent native render state and emits frame deltas. Phase 1 may keep this on the same worker thread; the boundary should still be explicit. |
| **UI Thread** | Applies deltas to a persistent Java render cache and draws. No terminal mutation. No full-frame parsing in the hot path. |

### 9.2 Data Flow
1. PTY bytes, resize requests, and viewport-scroll requests become **mailbox messages**.
2. The Ghostty worker applies them to native terminal state.
3. Native updates a **persistent render state** rather than building a one-off full snapshot.
4. Native publishes a **`FrameDelta`** describing only what changed.
5. The UI thread applies that delta to a persistent **`RenderFrameCache`**.
6. `TerminalView.onDraw()` renders the cache immediately.
7. Accessibility, selection, transcript export, and other expensive queries are handled on a **separate path** from the frame pump.

### 9.3 Core Structures to Introduce
- **`GhosttyMailboxMessage`**: explicit worker commands for append, resize, reset, viewport scroll, and shutdown.
- **`NativeRenderFrame`**: persistent native-side render cache derived from Ghostty's `RenderState`.
- **`FrameDelta`**: transport object containing metadata changes and dirty rows only.
- **`RenderFrameCache`**: Java-side persistent cache of visible rows/runs/styles used directly by Canvas rendering.
- **`FrameInvalidateCallback`**: redraw-only callback, separate from `onTextChanged()`.

### 9.4 FrameDelta Shape
A delta should carry enough data to update the visible frame without reparsing the whole viewport:
- Global frame flags: full rebuild vs partial update.
- Dimensions and viewport metadata: rows, columns, top row, active rows, transcript rows.
- Terminal metadata if changed: cursor state, mode bits, reverse video, palette generation.
- Dirty row count.
- For each dirty row:
  - viewport row index
  - row identity/generation token
  - wrap flag
  - text/style/cell-layout payload for that row only
- Optional reason flags for debugging: append, scroll, resize, palette, clear, selection, etc.

### 9.5 Notification Split
Before Phase 1, `notifyScreenUpdate()` was overloaded: it meant both "terminal content changed" and "please redraw". That was convenient, but it kept render scheduling tied to higher-level UI work.

Target split:
- **`notifyFrameAvailable()`**: redraw only.
- **`notifyTerminalMetadataChanged()`**: title/colors/bell/clipboard-like session updates.
- **`notifyAccessibilityContentInvalidated()`**: lazy content-description or accessibility refresh when needed.

That keeps the render loop close to upstream Ghostty's renderer wakeup model.

### 9.6 Core Render Invariants
- The Ghostty worker publishes `FrameDelta` objects in monotonically increasing sequence order after mutating native terminal/render state.
- The `ScreenSnapshot` attached to a `FrameDelta` is transport/debug data only. Partial publications may omit unchanged rows and metadata, so it is **not** directly renderable.
- The UI thread is the sole owner of `RenderFrameCache` and applies deltas in sequence.
- A full rebuild initializes or reinitializes the cache. Partial deltas are only valid after that cache exists.
- `TerminalView.onDraw()` renders the cache only. It does not fill Ghostty snapshots, touch native state, or render the worker transport snapshot.
- Cursor blink visibility is UI-local transient state applied to the cached frame immediately before draw; it does not change worker ownership of terminal state.

### 9.7 Ownership and Threading Invariants
- **PTY Reader** owns raw byte ingestion only. It never mutates native terminal/render state directly.
- **Ghostty Worker** is the sole owner of native Ghostty terminal state and the persistent native render state. All append, resize, reset, viewport-scroll, and side-effect extraction work happens there.
- **UI Thread** owns `RenderFrameCache`, view scroll/selection state, cursor blink timers, and Canvas drawing.
- Once published, a `FrameDelta` and its transport snapshot are treated as immutable publication artifacts until superseded.
- The worker never mutates `RenderFrameCache`, and the UI never mutates native Ghostty state during draw.
- Any higher-level queries such as accessibility, transcript export, or selection text are separate from the frame pump and must not reintroduce a snapshot-render hot path.

## 10. Migration Plan

### Phase 0: Instrument before restructuring
Add timing and counters so each stage can be measured separately.
- Native render-state update time
- Delta serialization time
- Java delta application time
- Canvas draw time
- Dirty row count per frame
- Full rebuild vs partial update rate

### Phase 1: Decouple redraws from text-changed callbacks
First remove the architectural coupling between frame publication and generic session notifications.
- Redraws should not require `onTextChanged()`.
- Accessibility/transcript work must not ride the same hot path as frame invalidation.

### Phase 2: Persistent native render-state API
Move from one-off snapshot production to a native render object that survives across frames and keeps dirty information alive.
- Scroll events become mailbox commands.
- Native viewport moves should update render state incrementally.
- Full rebuild only when global state requires it.

### Phase 3: Delta transport instead of full snapshots
Replace `ScreenSnapshot` publication on the hot path with `FrameDelta` publication.
- Keep `ScreenSnapshot` temporarily as the transport/debug payload beneath `FrameDelta`, not as a direct render path.
- Publish only dirty rows for append/scroll workloads.

### Phase 4: Java-side persistent render cache
Introduce a cache that mirrors the visible viewport and updates only dirty rows.
- Canvas drawing reads that cache directly.
- No full-frame parse on every published frame.
- Row-level reuse becomes possible for tmux and transcript scrolling.

### Phase 5: Revisit renderer backend after delta path lands
If tmux/scroll performance is still not good enough after the delta/cache migration, then revisit the renderer backend itself.
- Option A: keep Canvas, but with row/run caches and cheaper invalidation.
- Option B: move to `SurfaceView` / GL / Vulkan / native Ghostty-style GPU rendering.

The key rule is: **do not jump to a renderer rewrite before eliminating the avoidable full-frame serialization/parsing work**.

Current scaffold status: native snapshot publications now carry viewport/full-rebuild/dirty-row metadata, serialize only dirty-row payloads for partial frames, and only resend palette/render/mode metadata when required; the worker publishes `FrameDelta` objects around a transport-only `ScreenSnapshot` payload, the UI applies those deltas into a persistent `RenderFrameCache` with viewport row shifting for scroll updates, and `TerminalView`/`TerminalRenderer` now draw only from that persistent cache while reusing cached row/run state keyed off the frame cache.

## 11. Implementation Checklist

### Phase 0 — Instrumentation
- [x] Add native timing logs for render-state update, frame serialization, and scroll handling.
- [x] Add Java timing logs for delta application and `onDraw()`.
- [x] Add counters for dirty-row count, full rebuild count, and dropped/coalesced frame count.
- [x] Create repeatable benchmarks for tmux scroll, large output flood, and SSH resize storms. See `docs/ghostty-performance-benchmarks.md`.

### Phase 1 — Notification split
- [x] Introduce a redraw-only callback path separate from `TerminalSessionClient.onTextChanged()`.
- [x] Stop using `notifyScreenUpdate()` as the generic render wakeup for Ghostty frames.
- [x] Move accessibility/content-description refresh to a lazy or throttled path.
- [x] Keep title, bell, clipboard, and color changes on separate main-thread callbacks.

### Phase 2 — Native render-state ownership
- [x] Introduce explicit mailbox commands for append, resize, reset, viewport scroll, and shutdown.
- [x] Keep a persistent native render object alive across frames.
- [x] Preserve native dirty-row/page information across updates.
- [x] Make viewport scroll a first-class native operation instead of a side effect of snapshot rebuild.

### Phase 3 — FrameDelta transport
- [x] Define the `FrameDelta` binary/API format.
- [x] Support both full rebuild and partial update frames.
- [x] Serialize only dirty rows for scroll/output-driven updates.
- [x] Include cursor/mode/palette metadata only when changed or required.
- [x] Keep `ScreenSnapshot` temporarily as the transport/debug payload under `FrameDelta`, not as a direct render path.

### Phase 4 — Java render cache
- [x] Add a persistent `RenderFrameCache` for visible rows.
- [x] Apply deltas row-by-row instead of reparsing the full viewport.
- [x] Reuse cached row/runs/style data across frames.
- [x] Keep `TerminalRenderer` drawing purely from cached rows.
- [x] Verify tmux scroll no longer requires full visible-frame rebuild on every step.

### Phase 5 — Cleanup and validation
- [x] Remove old snapshot-only hot-path render code once delta parity is proven.
- [x] Make `RenderFrameCache` the only Ghostty UI render source and treat partial `ScreenSnapshot` payloads as transport/debug data only.
- [ ] Re-test ANR resistance under output floods after the refactor.
- [ ] Re-test tmux mouse scroll, fling scroll, and transcript scrolling.
- [ ] Re-test resize storms and alternate-screen applications.
- [x] Document final ownership and threading invariants in this file.

### Phase 6 — Optional renderer follow-up
- [ ] Decide whether Canvas is sufficient once delta/caching lands.
- [ ] If not, draft a `SurfaceView`/GPU-backed renderer plan.
- [ ] Reuse the same native mailbox + render-state model regardless of renderer backend.

## 12. Success Criteria
- tmux scroll feels immediate under sustained repaint.
- Scroll latency scales with **dirty rows**, not with **entire viewport size**.
- Large output floods remain ANR-safe.
- `onDraw()` stays free of JNI and full-frame parsing.
- Full rebuilds happen only for real global invalidations.
