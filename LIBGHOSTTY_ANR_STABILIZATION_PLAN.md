# Libghostty ANR Stabilization Plan

Status: native `fillSnapshot()` crash is fixed. The Ghostty backend is now functionally usable for normal shell output and `termux-change-repo`, but it still ANRs under bursty redraw scenarios such as SSH + tmux resize, large paste, and large output floods.

This doc is the next-step execution plan focused on removing UI-thread stalls.

Related docs:
- `LIBGHOSTTY_INTEGRATION_PLAN.md`
- `LIBGHOSTTY_IMPLEMENTATION_CHECKLIST.md`

---

## 1. Problem statement

Current blocker:
- `com.termux` still ANRs with Ghostty enabled when output arrives in large bursts or when terminal size changes trigger large redraws.

Observed symptoms:
- ANR reason is input dispatch timeout, e.g. `MotionEvent` or `FocusEvent`.
- During ANR, `com.termux` main thread shows ~100% user CPU.
- Repros are strongest when:
  - resizing an SSH session inside tmux
  - pasting large chunks of text
  - receiving large screen updates from TUI / remote shells

Current evidence says this is no longer a native crash bug. It is an ownership / scheduling bug: too much terminal work still runs on the UI thread.

---

## 2. What is still running on the main thread

Today the Ghostty path still does all of this on the main thread:

1. PTY output is drained in `TerminalSession.MainThreadHandler.handleMessage(...)`
2. `appendToActiveBackend(...)` calls `GhosttyTerminalContent.append(...)`
3. `GhosttyTerminalContent.append(...)` crosses JNI into native Ghostty parsing/state updates
4. `notifyScreenUpdate()` triggers a view redraw
5. `TerminalView.onDraw()` calls `TerminalRenderer.render(...)`
6. `TerminalRenderer.render(...)` calls `terminalContent.fillSnapshot(...)`
7. `GhosttyTerminalContent.fillSnapshot(...)` calls native snapshot generation
8. `ScreenSnapshot.markNativeSnapshot(...)` parses snapshot data on the Java side

Even after batching and log reduction, this still means:
- append work runs on the UI thread
- snapshot generation runs on the UI thread
- snapshot parsing runs on the UI thread
- resize storms can force repeated append + snapshot cycles before input/focus events are serviced

This is the core reason ANRs still happen.

---

## 3. Root cause hypothesis

The Ghostty backend still treats the UI thread as the terminal engine thread.

That was acceptable for initial bring-up, but it is not acceptable for bursty workloads.

Under a resize/output storm, the current pipeline looks like this:

1. PTY reader thread pushes bytes into `mProcessToTerminalIOQueue`
2. main thread wakes up and drains queue
3. main thread appends bytes into native Ghostty
4. main thread triggers redraw
5. main thread rebuilds snapshot during `onDraw`
6. more bytes arrive before input/focus/render queue catches up
7. UI thread stays busy long enough to miss input dispatch deadline

Conclusion:
- the next step is not another small micro-optimization
- the next step is to remove Ghostty state mutation and snapshot building from the UI thread

---

## 4. Goals

Must achieve:
- no ANR during SSH + tmux resize storms
- no ANR during large paste or output floods
- UI thread should never block on Ghostty append/snapshot work for long bursts
- preserve current Termux PTY/session lifecycle
- preserve current Java `Canvas` renderer for now
- preserve title, bell, clipboard, reply bytes, selection, transcript behavior

Nice-to-have:
- lower resize latency
- fewer redundant snapshots during rapid updates
- lower GC pressure and fewer per-frame allocations

---

## 5. Non-goals

Not part of this step:
- GPU renderer rewrite
- replacing Java `Canvas` rendering
- removing Java backend fallback
- rewriting PTY ownership/lifecycle
- moving all keyboard/mouse encoding native-side

---

## 6. Strategy: move Ghostty ownership off the UI thread

### Decision
Introduce a dedicated Ghostty backend worker thread and make it the single owner of:
- `GhosttyTerminalContent`
- native Ghostty append operations
- native Ghostty resize/reset operations
- snapshot generation
- Ghostty side-effect polling (reply bytes/title/clipboard/bell/colors)

The main thread should only:
- update Android views
- write user input back to the PTY
- render the latest published snapshot
- consume already-marshaled UI-safe events from the worker

This is the smallest architecture shift that directly targets the ANR.

---

## 7. Target architecture

```text
PTY reader thread
  -> ByteQueue / pending bytes
  -> Ghostty worker thread
       -> native append
       -> native resize/reset
       -> native drain pending output
       -> snapshot build into staging snapshot
       -> publish immutable/latest snapshot
       -> marshal events to main thread

main thread
  -> draw latest published snapshot only
  -> process input/focus/touch normally
  -> invalidate when a newer published snapshot exists
  -> dispatch title/bell/clipboard/colors callbacks already prepared by worker
```

### Key rule
The UI thread must not be the owner of native Ghostty terminal state.

That means:
- no `nativeAppend(...)` from the UI thread
- no `nativeResize(...)` from the UI thread
- no `nativeFillSnapshot(...)` from `onDraw()`

---

## 8. Snapshot publication model

### Recommended model: double-buffered `ScreenSnapshot`

Each Ghostty-backed `TerminalSession` gets:
- one staging snapshot owned by worker thread
- one published snapshot visible to UI thread
- a sequence number/version for published frames

Flow:
1. worker appends/resizes native state
2. worker decides a new frame is needed
3. worker fills staging `ScreenSnapshot`
4. worker swaps staging/published references under lock
5. worker posts one main-thread invalidate/update notification
6. UI thread renders the latest published snapshot without touching native Ghostty state

### Why this is the best next step
- preserves existing Java renderer shape
- avoids JNI/native work in `onDraw()`
- avoids mutating the same snapshot object while it is being rendered
- makes stale-frame behavior acceptable: UI can render the last complete snapshot instead of blocking

### Important thread-safety rule
A published snapshot must be immutable from the worker's point of view until it is swapped out again.

That means:
- do not share a single mutable `ScreenSnapshot` between worker and UI
- do not let `TerminalRenderer` call back into native to populate the snapshot live

---

## 9. Resize model

Resize storms are a major repro, so resize needs its own rules.

### New resize pipeline
1. UI thread computes new columns/rows in `TerminalView.updateSize()`
2. UI thread still updates PTY window size immediately via `JNI.setPtyWindowSize(...)`
3. UI thread enqueues a resize request to Ghostty worker
4. Ghostty worker coalesces repeated resizes and only applies the latest pending size
5. Ghostty worker rebuilds snapshot once for the latest size
6. UI thread renders the latest published post-resize snapshot

### Coalescing rule
If multiple resize requests arrive before worker processes them, older ones should be dropped.

Rationale:
- tmux/SSH can emit redraw storms while size is still changing
- rendering every intermediate size is wasted work
- only the final size matters for responsiveness

### Optional debounce
If needed, add a tiny debounce window on worker side for rapid repeated resize requests:
- target: 8-16 ms max
- goal: collapse resize bursts without making resize feel laggy

---

## 10. Backpressure model

Even on the worker thread, we should avoid useless work.

### Rules
- at most one pending UI invalidate for Ghostty output
- at most one pending snapshot build request
- output bursts should mark the state dirty, not enqueue N redraws
- if worker is already building a snapshot, later updates should collapse into one follow-up build
- worker should prefer newest state over faithfully rendering every intermediate state

### Why
Terminal correctness depends on final state, not on rendering every intermediate frame.

Skipping intermediate frames is fine.
Skipping input/focus processing is not.

---

## 11. Concrete code changes

## 11.1 `terminal-emulator/src/main/java/com/termux/terminal/TerminalSession.java`

### Required changes
- add a dedicated Ghostty worker for Ghostty-backed sessions
- stop calling `mGhosttyTerminalContent.append(...)` from `MainThreadHandler`
- stop calling `mGhosttyTerminalContent.resize(...)` directly from UI thread
- move Ghostty reply/title/clipboard/bell/color handling into worker-owned append cycle
- publish already-prepared UI events back to main thread
- keep Java backend behavior unchanged

### Likely additions
- new inner helper or separate class, e.g. `GhosttySessionWorker`
- worker message types:
  - `APPEND_AVAILABLE`
  - `RESIZE`
  - `RESET`
  - `BUILD_SNAPSHOT`
  - `SHUTDOWN`
- fields for:
  - pending/latest resize
  - published snapshot sequence
  - dirty flags
  - one pending UI notification boolean

---

## 11.2 `terminal-emulator/src/main/java/com/termux/terminal/GhosttyTerminalContent.java`

### Required changes
- keep native wrapper small and thread-safe by convention: Ghostty worker is sole caller
- add a method shape that makes snapshot ownership explicit, e.g.:
  - `buildSnapshot(int topRow, ScreenSnapshot target)`

### Optional cleanup
Rename `fillSnapshot(...)` usage in the Ghostty path so future code does not accidentally call it from `onDraw()`.

---

## 11.3 `terminal-emulator/src/main/java/com/termux/terminal/ScreenSnapshot.java`

### Required changes
- support safe publication between worker and UI threads
- no hidden mutable shared state between rendering and building

### Possible additions
- snapshot sequence/version
- metadata for last built top row / rows / columns
- helper for cheap swap/publication

### Constraint
Do not reintroduce per-frame allocations. The worker should reuse the same staging buffers.

---

## 11.4 `terminal-view/src/main/java/com/termux/view/TerminalRenderer.java`

### Required changes
Split rendering from snapshot production.

Current bad shape:
- `render(...)` calls `terminalContent.fillSnapshot(...)`

Target shape:
- `renderPreparedSnapshot(...)` only draws an already-built snapshot
- no Ghostty JNI/native calls from `onDraw()`

Java backend can keep the old synchronous path temporarily if needed.

---

## 11.5 `terminal-view/src/main/java/com/termux/view/TerminalView.java`

### Required changes
- `onDraw()` should render the latest published snapshot for Ghostty sessions
- `updateSize()` should enqueue resize to worker, not synchronously resize Ghostty on UI thread
- `onScreenUpdated()` should just invalidate/scroll based on already-published state

### Important rule
`TerminalView` should never wait for Ghostty to build a frame.
If no new frame exists yet, it should draw the last published frame.

---

## 11.6 Native Zig layer

Files:
- `terminal-emulator/src/main/zig/src/termux_ghostty.zig`
- `terminal-emulator/src/main/zig/src/jni_exports.zig`

### Required changes
No major architecture rewrite is required in Zig for the first pass.
The main change is caller ownership: native APIs are still used, but only from the worker thread.

### Optional native follow-up if Java worker is not enough
If worker-thread ownership still leaves too much overhead, add a native-side cached snapshot API:
- build snapshot natively on update
- expose `copyLatestSnapshot(...)` or similar
- avoid rebuilding snapshot on every Java request

This is phase 2 optimization, not the first move.

---

## 12. Instrumentation plan

Add temporary threshold-based instrumentation, not spam logging.

### Log only when slow
Suggested thresholds:
- append batch > 8 ms
- resize > 8 ms
- snapshot build > 16 ms
- UI draw > 16 ms
- pending PTY queue above 64 KiB
- more than one dropped/coalesced resize in a burst

### Metrics to capture
- bytes appended per batch
- append duration
- snapshot build duration
- snapshot publish interval
- number of dropped intermediate frames
- number of coalesced resize requests
- queue depth / buffered bytes
- top row and visible rows at publish time

### Avoid
- per-row logs
- per-append logs on normal path
- logs from `onDraw()` for every frame

---

## 13. Step-by-step execution plan

## Phase A — prove the architecture change in code shape

Goal:
- make rendering consume published snapshots instead of building snapshots in `onDraw()`

Tasks:
- add a Ghostty-only render path that takes a prebuilt snapshot
- keep Java backend on current synchronous path
- introduce published snapshot field on `TerminalSession` or helper class

Exit condition:
- Ghostty onDraw path does not call native fill directly anymore

---

## Phase B — move append off the UI thread

Goal:
- PTY output parsing/state mutation no longer happens on main thread

Tasks:
- create `GhosttySessionWorker`
- make PTY reader thread signal worker instead of main thread for Ghostty sessions
- worker drains process-to-terminal queue
- worker performs `append(...)`
- worker handles reply bytes/title/clipboard/bell/colors
- worker posts only compact UI events back to main

Exit condition:
- `MainThreadHandler` no longer performs Ghostty append work

---

## Phase C — publish snapshots from worker

Goal:
- worker builds snapshots and publishes latest completed frame

Tasks:
- add staging + published `ScreenSnapshot`
- worker builds staging snapshot after append/resize when dirty
- worker atomically swaps published snapshot
- UI thread invalidates and draws published snapshot only

Exit condition:
- Ghostty `onDraw()` path does not call `nativeFillSnapshot(...)`

---

## Phase D — coalesce resize and redraw storms

Goal:
- resize storms from tmux/SSH no longer cause cascaded work

Tasks:
- latest-only resize request storage
- optional tiny debounce for repeated resize bursts
- one invalidate per published snapshot
- collapse redundant build requests while worker already dirty/building

Exit condition:
- repeated size changes settle into one latest render path

---

## Phase E — threshold instrumentation + tuning

Goal:
- confirm ANR is gone and identify remaining hotspots

Tasks:
- add threshold logs around append/build/publish/draw
- test large paste, large output, ssh+tmux resize, alternate screen
- measure max append time and snapshot build time under burst loads

Exit condition:
- no ANR repro in known cases
- slow-path logs are understandable and sparse

---

## 14. Acceptance criteria

All must be true:
- no ANR during large paste
- no ANR during ssh + tmux resize storm
- no ANR during focus changes while output is streaming
- Ghostty output still appears live and correct
- cursor, scrollback, transcript rows remain correct
- title, bell, clipboard, colors, PTY reply bytes still work
- Java backend remains untouched as fallback

Stretch criteria:
- resize feels subjectively close to Java backend
- no visible frame hitch over ~250 ms during large redraw bursts

---

## 15. Risks and mitigations

| Risk | Mitigation |
| --- | --- |
| Native handle accessed from multiple threads | Single-owner Ghostty worker. UI never calls append/resize/fill directly. |
| Snapshot race between worker and UI | Double-buffer snapshots and swap under synchronization. |
| UI renders stale frame briefly | Acceptable. Prefer stale frame over blocking UI. |
| Event ordering regressions | Publish sequence numbers and process UI callbacks in the same worker batch order. |
| Resize ordering issues with PTY vs Ghostty state | UI updates PTY immediately, worker coalesces latest Ghostty resize and rebuilds once. |
| More code complexity in `TerminalSession` | Keep Java backend path unchanged; isolate Ghostty logic in helper class. |
| Snapshot parsing on worker still costly | Reuse buffers; if needed, move to native cached snapshot copy as follow-up. |

---

## 16. Immediate task checklist

- [x] Add a dedicated Ghostty worker class/helper and make it the sole owner of `GhosttyTerminalContent`
- [x] Stop calling Ghostty `append(...)` from `TerminalSession.MainThreadHandler`
- [x] Stop calling Ghostty `fillSnapshot(...)` from `TerminalRenderer.render(...)`
- [x] Add double-buffered published/staging `ScreenSnapshot` support
- [x] Make `TerminalView.onDraw()` render latest published Ghostty snapshot only
- [x] Add latest-only resize coalescing for Ghostty sessions
- [x] Add one-pending-invalidate / one-pending-build backpressure rules
- [x] Add threshold-based performance logs for append/snapshot/draw
- [x] Re-test: large paste, ssh+tmux resize, focus changes, alternate screen apps

---

## 17. Recommendation

Do not spend more time trying to shave milliseconds from the current UI-thread Ghostty path.

The current remaining bug is architectural:
- Ghostty append work is still on the UI thread
- Ghostty snapshot work is still on the UI thread

The next step should be a worker-owned Ghostty backend with published snapshots.
That is the most direct path to eliminating the ANR class now being observed.
