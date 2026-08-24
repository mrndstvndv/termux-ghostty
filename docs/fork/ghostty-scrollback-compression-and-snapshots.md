# Ghostty scrollback compression and VT snapshots

## Upstream baseline

The wrapper now tracks Ghostty commit `a36dc245b0a8111b86b8cecdd0abf1c8d8c4dff9` (2026-08-24). The previous pin, `ec58fbc6a2da89f6d17381d56ef316f29dbf789b` (2026-08-05), contained the initial compression and snapshot implementations but did not export the Zig snapshot package from `src/lib_vt.zig`. Upstream added that public Zig export in `f4f9991d2 chore(vt): expose snapshot api`.

Relevant upstream implementation milestones:

- `ebc3ffd22` through `461562ca4`: compressed page representation, `PageList` integration, activity debounce, and idle renderer scheduling.
- `172f15da3`: libghostty-vt compression API.
- `25e624569`: renderer scheduling that is not starved by unrelated frame wakes.
- `d44baa914` through `e37865bed`: binary snapshot format and incremental decoder.
- `d7bb4b863`: C snapshot API.
- `f4f9991d2`: public Zig snapshot package.

## Scrollback compression

Ghostty compresses complete, cold primary-screen history pages. The compressed representation retains the page's virtual address range, stores an LZ4 block, and returns physical backing pages to the OS. Linux and 64-bit Android use `madvise(MADV_DONTNEED)`. Access through scrolling, search, inspection, or snapshot encoding is logically transparent; a page is restored when an operation needs its native backing.

Compression is deliberately caller-driven:

- `Terminal.compressionActivity()` / `ghostty_terminal_compression_activity()` returns an opaque token.
- A token change restarts an idle delay.
- `Terminal.compress(.incremental)` / `ghostty_terminal_compress(...INCREMENTAL...)` performs bounded work.
- `pending` schedules another short step; `complete` waits for another activity-token change; `unsupported` disables scheduling.
- `full` exists for synchronous maintenance but may stall on large histories.

Ghostty's desktop renderer owns only the timer. `src/renderer/Thread.zig` waits 250 ms after relevant activity, then performs incremental steps 1 ms apart. It uses the terminal lock with `tryLock`, so rendering or parsing is never blocked waiting for compression. Inspector/frame wakes do not reset the timer unless the compression activity token changed.

### Fork integration

`GhosttySessionWorker` is already the sole mutable terminal owner, so it also owns compression scheduling. After worker operations it compares the native activity token, waits 250 ms after a change, and invokes 1 ms incremental continuation steps. Compression never publishes a frame because it changes storage representation only. Viewport rendering that restores a compressed page changes the token and naturally starts a later idle recompression pass.

The path is:

```text
Ghostty PageList activity
  -> native compression token
  -> GhosttySessionWorker idle message
  -> incremental native compression
  -> no frame publication
```

## Terminal state snapshots

Ghostty's terminal snapshot is distinct from this fork's `ScreenSnapshot` render transport. It is a complete binary VT-state representation intended for migration or persistence, not a UI frame.

The format is an ordered, CRC-protected record stream:

```text
ENVELOPE
TERMINAL
SCREEN + active PAGE records
CONTINUATION
READY
HISTORY + older PAGE records
FINISH
```

READY authenticates enough state to render and resume the VT parser. HISTORY follows newest-to-oldest, allowing an incremental decoder to expose a usable terminal before all scrollback arrives. FINISH validates the complete stream. Snapshot version 1 remains explicitly unstable and currently omits some state such as Kitty graphics payloads.

Public interfaces:

- Zig: `ghostty.snapshot.encode`, `decode`, `decodeExact`, and `Decoder.ready` / `Decoder.next`.
- C: `ghostty_snapshot_encode*`, `ghostty_snapshot_decoder_new*`, `ready`, `next`, `decode`, and decoder progress/data queries.
- Stream continuation: `Stream.writeContinuation` and continuation tracking preserve an unfinished VT sequence or partial UTF-8 code point at the snapshot cut.

Ghostty's desktop renderer does not currently use terminal snapshots. The functionality is exposed through libghostty-vt and examples/benchmarks for embedders. Incremental READY/history restoration is useful for remote migration; bounded in-process byte-array restoration uses the transactional one-shot decoder.

### Fork integration

The native session enables continuation tracking and exposes complete capture/transactional restore through the C, JNI, Java session, and Compose-session adapter seams.

```text
TerminalSessionBackend / TerminalSession
  -> CompletableFuture request
  -> GhosttySessionWorker
  -> GhosttyTerminalContent / JNI
  -> ghostty.snapshot encode or decodeExact
```

Capture parses output already queued at the request boundary before encoding. Restore decodes into temporary state first; malformed or truncated bytes leave the live terminal untouched. A successful restore replaces terminal and stream state only on the worker, replays the authenticated continuation, reapplies current host geometry, resets viewport state, and publishes a full immutable frame. Snapshot futures complete on the main thread.

Snapshots are currently materialized as `byte[]`, so this wrapper uses one-shot restoration rather than Ghostty's incremental READY/history decoder. A future streaming migration transport should keep the decoder on the worker and publish a full frame at READY plus monotonic history-growth frames after each applied page.
