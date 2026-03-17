# Ghostty Resume / Session Resync Plan

Scope:
- fix stale terminal content after app background -> foreground
- fix the same class of bug behind earlier "switching sessions does not update the screen" issues
- keep the fix model-correct, not lifecycle-hacky

---

## Problem

Current behavior:
- PTY output continues while the activity is not visible
- `TermuxTerminalSessionActivityClient` drops UI updates while hidden
- when the app returns, `TerminalView.onScreenUpdated()` runs
- but Ghostty UI state is reconstructed from `RenderFrameCache`, which expects a valid frame history
- if the view missed Ghostty frame deltas while hidden, the cache can be stale
- applying only the newest partial delta is not enough to reconstruct the full visible screen

Most visible cases:
- SSH
- full-screen TUIs
- programs that repaint aggressively while app is backgrounded
- switching back to a session after it changed off-screen

This is the same bug family as the older session-switch issue:
- UI-side visible state was treated as if it were authoritative
- but the real authority is the terminal backend state
- when UI history is incomplete, the only correct recovery is a fresh backend snapshot

---

## Why the "correct" fix is sequence-gap detection + backend resync

We have several possible fixes:
- always request a full snapshot on resume
- keep applying background frames while hidden
- fake redraws via resize / invalidation / `Ctrl-L`-style behavior
- detect when UI missed Ghostty frames, then request a full snapshot rebuild

The most correct fix is:
1. detect when the UI cache missed one or more Ghostty frame publications
2. reject partial frame application in that state
3. request a fresh full snapshot from the backend
4. rebuild UI cache from backend truth

Why this is correct:
- it preserves the architecture: backend state is source of truth, UI cache is disposable
- it fixes both resume and session-switch paths with the same rule
- it avoids unnecessary full rebuilds during normal operation
- it does not depend on activity lifecycle timing
- it works even if we later add more paths that can miss frames

---

## Design rule

For Ghostty rendering:
- `GhosttyTerminalContent` / worker state is authoritative
- `FrameDelta` is only valid if the UI cache has continuous history or the frame is a full rebuild
- `RenderFrameCache` must never pretend it can recover from a missed partial frame
- if continuity is broken, request a full snapshot and rebuild from there

---

## Root cause in current code

Relevant behavior now:
- `GhosttySessionWorker` publishes `FrameDelta` with increasing `frameSequence`
- `TerminalView.applyLatestGhosttyFrameDelta()` applies the latest delta to `RenderFrameCache`
- `RenderFrameCache.apply()` rejects partial frames only before initial cache initialization
- once initialized, it does **not** enforce sequence continuity
- while activity is hidden, `onFrameAvailable()` is dropped
- on return, UI may try to apply a newer partial delta on top of an old cache
- result: stale or incorrect visible content

So the missing invariant is:
- partial deltas require contiguous frame sequence

---

## Proposed implementation

### Phase 1: make cache continuity explicit

Add sequence-gap handling to `RenderFrameCache`.

Rules:
- full rebuild frames may be applied regardless of previous sequence
- partial frames may only be applied if `frameSequence == lastApplied + 1`
- if a partial frame arrives after a gap, reject it as unrecoverable from cache state alone
- expose enough signal so caller can request backend resync

Expected result:
- stale cache can no longer silently survive after missed frames

### Phase 2: resync from `TerminalView`

Update `TerminalView.applyLatestGhosttyFrameDelta()`.

Rules:
- if cache apply succeeds, continue normally
- if apply fails because cache is uninitialized and frame is partial, request full snapshot
- if apply fails because of a sequence gap, request full snapshot
- avoid repeated spammy requests if a full snapshot is already pending or the same bad frame is seen repeatedly

Expected result:
- the view self-heals from missed frame history

### Phase 3: foreground belt-and-suspenders

On activity foreground/session attach paths, explicitly request a full snapshot when needed.

Candidate places:
- `TermuxTerminalSessionActivityClient.onStart()`
- `TerminalView.attachSession()`
- maybe both, but avoid duplicate work if one is enough

Use this as a safety net, not the primary correctness mechanism.

Reason:
- even with correct gap detection, foreground is a natural resync point
- this also makes recovery feel faster and more deterministic to users

### Phase 4: apply same rule to session switching

Confirm that session switching follows the same invariant:
- attaching a session to a view with stale or unrelated cache must not reuse incompatible partial history
- if the cache cannot prove continuity for that session, rebuild from a full snapshot

This likely means:
- keep `mGhosttyRenderFrameCache.reset()` on session attach
- ensure attach path requests or quickly obtains a full snapshot
- never rely on an already-cached frame stream from another attachment lifetime

---

## Concrete code targets

### `terminal-emulator/src/main/java/com/termux/terminal/RenderFrameCache.java`
- [x] Add explicit continuity validation for partial frames.
- [x] Distinguish failure reasons:
  - [x] uninitialized cache + partial frame
  - [x] sequence gap on partial frame
  - [x] older or duplicate frame
- [x] Keep full rebuild frames always acceptable.
- [x] Keep cache reset cheap and explicit.

Implementation notes:
- current `mAppliedFrameSequence` already exists
- use it as the continuity source of truth
- do not silently accept `frameSequence > lastApplied + 1` for partial frames

### `terminal-view/src/main/java/com/termux/view/TerminalView.java`
- [x] Update `applyLatestGhosttyFrameDelta()` to respond to continuity failures.
- [x] Request `mTermSession.requestGhosttyFullSnapshotRefresh()` on unrecoverable partial frames.
- [x] Add a small guard so repeated failed applications do not trigger endless duplicate refresh requests.
- [x] Clear that guard once a valid full rebuild lands.

Implementation notes:
- a bool or last-requested-frame marker is enough
- this should stay view-local; the backend already knows how to build a full snapshot

### `app/src/main/java/com/termux/app/terminal/TermuxTerminalSessionActivityClient.java`
- [ ] Decide whether `onStart()` should proactively request a full snapshot for current Ghostty session.
- [ ] If yes, keep it conditional and cheap.
- [ ] Do not rely on this path as the only fix.

### `terminal-emulator/src/main/java/com/termux/terminal/GhosttySessionWorker.java`
- [ ] Verify full snapshot refresh requests are already coalesced enough.
- [ ] If needed, add lightweight dedupe for repeated refresh requests while one is pending.
- [ ] Do not change frame publication semantics unless necessary.

---

## Implementation checklist

### A. Lock the invariant down
- [x] Write down the continuity invariant in code comments near `RenderFrameCache`.
- [x] Make it clear that worker-published transport snapshots are not directly renderable source of truth.
- [x] Make it clear that partial deltas require contiguous sequence history.

### B. `RenderFrameCache` behavior
- [x] Extend `apply(...)` so partial deltas with a sequence gap are rejected.
- [x] Preserve existing older/duplicate frame rejection.
- [x] Preserve full rebuild acceptance.
- [x] Make failure reason observable by caller.

### C. `TerminalView` recovery
- [x] Teach `applyLatestGhosttyFrameDelta()` to request a full snapshot on continuity failure.
- [x] Deduplicate repeated refresh requests.
- [x] Reset dedupe state after successful full rebuild.
- [x] Keep fallback behavior minimal and local.

### D. Foreground/session attach safety net
- [ ] Evaluate whether `onStart()` should request a full snapshot for current Ghostty session.
- [ ] Evaluate whether `attachSession()` should always request a full snapshot after cache reset.
- [ ] Pick the smallest combination that gives deterministic recovery.

### E. Manual test matrix
- [ ] Reproduce with local shell output while app is backgrounded.
- [ ] Reproduce with SSH session output while app is backgrounded.
- [ ] Reproduce with a full-screen TUI on SSH.
- [ ] Reproduce with alternate-screen app returning to shell.
- [ ] Switch sessions after off-screen output changed inactive session.
- [ ] Switch to a session immediately after app foreground.
- [ ] Confirm no stale frame remains after recovery.
- [ ] Confirm no visible flicker loop / repeated full refresh spam.

### F. Regression checks
- [ ] Normal typing path still uses incremental deltas.
- [ ] Scrolling still works.
- [ ] Viewport-scroll-only deltas still preserve user scroll position.
- [ ] Session switching still updates immediately.
- [ ] Resume path does not require manual input to refresh.
- [ ] No excessive full rebuilds during steady-state usage.

---

## Acceptance criteria

The fix is done when all are true:
- [ ] Background -> foreground always shows current PTY state without manual interaction.
- [ ] SSH sessions recover correctly after off-screen output.
- [ ] Full-screen TUIs recover correctly after off-screen output.
- [ ] Session switching never shows stale content from missed frame history.
- [ ] Partial Ghostty deltas are never applied across sequence gaps.
- [ ] Full snapshot recovery is automatic and bounded.
- [ ] Normal foreground rendering still stays incremental and performant.

---

## Non-goals

- not a PTY buffering change
- not an Android lifecycle-only workaround
- not a fake redraw via resize or synthetic terminal input
- not keeping hidden views fully caught up at all times

---

## Suggested execution order

1. `RenderFrameCache` continuity enforcement
2. `TerminalView` full-snapshot recovery path
3. foreground/session-attach safety net if still needed
4. manual regression pass on SSH + TUI + session switching

---

## Notes during implementation

- If the old session-switch bug was fixed with an attach-time full refresh, keep that behavior if it helps, but treat it as a safety net.
- The actual correctness boundary should live at frame continuity validation, not at activity lifecycle callbacks.
- Prefer small types and explicit states over booleans with unclear meaning.
