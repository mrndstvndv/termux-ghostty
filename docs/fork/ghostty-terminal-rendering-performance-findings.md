# Ghostty terminal frontend and rendering: source-backed performance findings

## Scope and revision

This report studies the Ghostty source pinned by this repository in
`terminal-emulator/src/main/zig/build.zig.zon` (`ec58fbc6a2da89f6d17381d56ef316f29dbf789b`, Ghostty `1.3.2-dev`).
The source links below point at that exact upstream commit, so line movement in
`main` does not silently change the evidence.

The local source is the vendored copy under
`terminal-emulator/src/main/zig/zig-pkg/ghostty-1.3.2-dev-5UdBCygNPQW5SGmaRu3_276Q9ETcqcts2wVjiUet3jbN/src`.
The target implementation here is Android/Compose (`terminal-compose-view/`
and `compose-app/`), not Ghostty's macOS SwiftUI shell. "Observed" means
visible in the cited source; recommendations are explicitly marked
**Recommendation** or **Inference**.

## Executive answer

1. **Keep VT mutation off the Compose frame path.** Ghostty's `Termio.processOutput`
   owns one terminal-state lock while `Stream.nextSlice` parses a byte batch;
   rendering is only woken after state mutation. The stream can be allocation-free
   when configured without an allocator and uses a stack codepoint buffer plus
   `print_slice` runs. This is a strong model for JNI/native parsing, but not a
   reason to parse VT from a composable or `DrawScope`.
2. **Use two-level damage, not a single content version.** Ghostty tracks full
   terminal/screen damage, render-state full/partial damage, and per-row damage.
   A clean frame scans packed row dirty bits cheaply; a partial frame clears and
   rebuilds only affected rows. The C render API explicitly warns that global and
   row dirty flags are independent and both must be cleared. Compose can consume
   a copied frame containing `fullDamage` plus a compact row/rect set, while
   preserving the existing immutable snapshot boundary.
3. **Retain row-shaped render storage.** Ghostty stores backgrounds as a flat
   grid and foreground instances in one list per row, pre-sizing each row for
   approximately `columns * 3` entries and clearing only dirty rows. This maps
   better to `TerminalRowRuns`/retained row renderers than to rebuilding one large
   list or allocating per cell.
4. **Batch GPU work, but do not copy the backend literally.** Desktop Ghostty
   uploads retained cell arrays and issues instanced background/text draws. Android
   Compose's `Canvas`/Skia path does not expose the same Metal/OpenGL buffer and
   instance API; the transferable idea is stable, row-oriented retained data and
   fewer draw operations, not a direct port of `Buffer.syncFromArrayLists`.
5. **Bound both parser work and transport backlog.** The current upstream POSIX
   path uses four preallocated 64 KiB PTY buffers, a 3 ms gather budget, and blocks
   the gatherer when all buffers are in flight. This prevents an unbounded queue
   while keeping the kernel PTY drained. On Android, use a bounded native ring or
   byte queue and coalesced invalidation; do not let a fast producer enqueue an
   unbounded number of Compose snapshots.
6. **Separate update scheduling from draw scheduling.** Ghostty wakes on state
   changes, updates retained frame data, and skips a draw when size/content/
   animation state is unchanged (presenting the previous target instead). Its
   renderer thread uses an 8 ms animation timer, but respects renderer-managed
   vsync and visibility/focus. Compose should invalidate at most once per frame
   for content, and run a separate frame clock only while cursor/effect/shader
   animation requires it.

## 1. VT parser and `ghostty-vt` integration

### 1.1 Stream processing is specialized at compile time

`src/terminal/stream.zig`, `Stream(H)`, stores a handler, parser, UTF-8 decoder,
and optional continuation tracker. The handler type is a comptime parameter:
Ghostty states that this allows unimplemented actions to be compiled away rather
than branching on every action (`Stream` documentation, lines 449–490).
`nextSlice` uses a stack `[4096]u32` codepoint buffer, SIMD UTF-8/control scanning,
and groups printable codepoints into `print_slice` actions (lines 593–705).
Handlers must process both `print_slice` and single `print` actions (lines
453–456).

Source: [`src/terminal/stream.zig#L449-L705`](https://github.com/ghostty-org/ghostty/blob/ec58fbc6a2da89f6d17381d56ef316f29dbf789b/src/terminal/stream.zig#L449-L705)

**Observed:** `Stream.Options.allocator` is optional. Without it, the stream is
"fully allocation free" and drops operations that require heap storage; with it,
OSC parsing and optional continuation tracking may allocate (lines 482–520).
Continuation bytes can be written to a caller-owned writer and are only needed
for unfinished parser/UTF-8 state (lines 538–582).

**Recommendation:** Keep the JNI/native VT stream configured with a long-lived
allocator only when required by feature semantics (clipboard, Kitty/APC, etc.).
For the hot path, feed direct byte slices and avoid turning each printable cell
into a Kotlin object or callback. Preserve continuation state inside the native
session; never assume a PTY read ends at a VT sequence boundary.

### 1.2 Terminal stream handler is the side-effect boundary

`src/terminal/stream_terminal.zig` defines `Stream = stream.Stream(Handler)`.
`Handler` mutates `*Terminal` and defaults to readonly side effects; optional
callbacks cover PTY replies, bell, notifications, title/PWD changes, progress,
clipboard, device attributes, size reports, and XTVERSION. Callback payloads are
borrowed for the duration of the callback (lines 24–35, 53–157).

Source: [`src/terminal/stream_terminal.zig#L24-L169`](https://github.com/ghostty-org/ghostty/blob/ec58fbc6a2da89f6d17381d56ef316f29dbf789b/src/terminal/stream_terminal.zig#L24-L169)

**Observed:** The handler explicitly records `semantic_failure` when an
allocation/semantic operation fails, while the stream continues best-effort
(lines 39–51). That is important for a mobile session: parser progress and
terminal-state correctness must not depend on successfully publishing a UI
frame.

**Recommendation:** Maintain one native side-effect adapter (PTY writes,
titles, clipboard, notifications, progress) and publish only owned/copyable
side effects across JNI. Borrowed native sequence data must not escape the
callback into a later Compose coroutine.

### 1.3 PTY output mutates state, then wakes rendering

`src/termio/Termio.zig` constructs the stream with the session allocator and a
`StreamHandler` (lines 294–315). `processOutput` takes the renderer-state mutex;
`processOutputLocked` schedules a render, rate-limits cursor-blink reset to at
most once per 500 ms, parses a batch with `terminal_stream.nextSlice(buf)`, and
only then wakes the IO mailbox if VT handling produced side-effect messages
(lines 644–703).

Source: [`src/termio/Termio.zig#L294-L315`](https://github.com/ghostty-org/ghostty/blob/ec58fbc6a2da89f6d17381d56ef316f29dbf789b/src/termio/Termio.zig#L294-L315), [`src/termio/Termio.zig#L644-L703`](https://github.com/ghostty-org/ghostty/blob/ec58fbc6a2da89f6d17381d56ef316f29dbf789b/src/termio/Termio.zig#L644-L703)

**Recommendation:** The existing worker/native-owner architecture is the right
seam for Compose. A native `append` should parse and mutate terminal state on
the owner thread, coalesce a wakeup, and publish an immutable frame only when
that worker reaches its chosen budget. Compose should only read the published
frame.

### 1.4 Public C `ghostty-vt` render API is useful but unstable

The vendored `include/ghostty/vt.h` describes `libghostty-vt` as an extracted
terminal/parser/state library but warns that its API is incomplete, unstable,
and expected to change. The C render header describes a stateful render state,
two-phase update, and dirty regions, but requires the consumer to manage dirty
reset itself.

Sources: [`include/ghostty/vt.h#L1-L41`](https://github.com/ghostty-org/ghostty/blob/ec58fbc6a2da89f6d17381d56ef316f29dbf789b/include/ghostty/vt.h#L1-L41), [`include/ghostty/vt/render.h#L22-L71`](https://github.com/ghostty-org/ghostty/blob/ec58fbc6a2da89f6d17381d56ef316f29dbf789b/include/ghostty/vt/render.h#L22-L71)

**Non-transferable:** Do not expose `GhosttyRenderStateRowIterator` pointers
or borrowed row data directly to Kotlin. The C header says row data is valid only
until the render state is updated (render header lines 151–156), which conflicts
with Compose's asynchronous/recomposing consumers. Copy to the existing native
snapshot/frame contract instead.

## 2. Damage and update flow

### 2.1 Terminal damage has explicit full-state causes

`src/terminal/Terminal.zig` defines terminal-level renderer flags for palette,
reverse colors, clear, preedit, and glyph glossary changes. The comments state
that these flags are separate from screen-level dirty state and are cleared by
the renderer (`Dirty`, lines 206–230).

Source: [`src/terminal/Terminal.zig#L206-L230`](https://github.com/ghostty-org/ghostty/blob/ec58fbc6a2da89f6d17381d56ef316f29dbf789b/src/terminal/Terminal.zig#L206-L230)

`src/terminal/page.zig` makes row dirty conservative: dirty means one or more
cells changed visually; false positives are allowed, false negatives are not.
The row is a packed `u64` with flags for wrapping, grapheme/style/hyperlink
presence, semantic prompt, Kitty placeholders, and dirty state (`Row`, lines
1940–2006). A page also has a page-level dirty bit, but false does not mean no
row is dirty (`Page`, lines 179–186).

Sources: [`src/terminal/page.zig#L179-L204`](https://github.com/ghostty-org/ghostty/blob/ec58fbc6a2da89f6d17381d56ef316f29dbf789b/src/terminal/page.zig#L179-L204), [`src/terminal/page.zig#L1940-L2006`](https://github.com/ghostty-org/ghostty/blob/ec58fbc6a2da89f6d17381d56ef316f29dbf789b/src/terminal/page.zig#L1940-L2006)

### 2.2 RenderState consumes damage hierarchically

`src/terminal/render.zig` documents a stateful render snapshot that replaced
viewport cloning because clone time blocked IO (lines 25–43). `beginUpdate`
forces a full rebuild when the active screen changes, any terminal/screen dirty
flag exists, dimensions change, or the viewport pin changes (lines 361–397).
Otherwise it scans page chunks and only rebuilds dirty rows. It uses packed row
masks to scan groups cheaply and clears page/row dirty flags as they are
consumed (lines 494–627, 708–723).

Sources: [`src/terminal/render.zig#L25-L71`](https://github.com/ghostty-org/ghostty/blob/ec58fbc6a2da89f6d17381d56ef316f29dbf789b/src/terminal/render.zig#L25-L71), [`src/terminal/render.zig#L316-L397`](https://github.com/ghostty-org/ghostty/blob/ec58fbc6a2da89f6d17381d56ef316f29dbf789b/src/terminal/render.zig#L316-L397), [`src/terminal/render.zig#L494-L627`](https://github.com/ghostty-org/ghostty/blob/ec58fbc6a2da89f6d17381d56ef316f29dbf789b/src/terminal/render.zig#L494-L627)

The update has two phases: `beginUpdate` needs terminal access, while
`endUpdate` denormalizes pending style runs using only render-state-owned memory
(lines 335–352 and 725–751). This minimizes exclusive-lock duration.

Source: [`src/terminal/render.zig#L335-L352`](https://github.com/ghostty-org/ghostty/blob/ec58fbc6a2da89f6d17381d56ef316f29dbf789b/src/terminal/render.zig#L335-L352), [`src/terminal/render.zig#L725-L751`](https://github.com/ghostty-org/ghostty/blob/ec58fbc6a2da89f6d17381d56ef316f29dbf789b/src/terminal/render.zig#L725-L751)

The C API repeats the same contract: global dirty is `false`, `partial`, or
`full`; row dirty is independent; `update` updates but does not unset dirty;
consumers must clear both layers (`render.h`, lines 54–71 and 96–111).

**Recommendation:** Extend the native-to-Compose frame metadata (without
exposing native pointers) to carry:

```text
fullDamage: Boolean
changedRows: compact row ranges or bitset
cursor/overlay damage: separate transient flags
```

Use `fullDamage` for resize, active-screen switch, palette/font/glossary change,
viewport movement, and other global invalidation. For partial damage, clear and
rebuild only changed row render data. Reset damage only after the copied frame is
complete and published, so a failed UI draw cannot lose native damage.

**Compose caveat:** Compose itself may schedule/re-record a Canvas draw even
when content is unchanged. Damage metadata can avoid rebuilding native/text
models and can skip row work, but cannot guarantee Skia will issue only dirty
GPU regions unless the renderer owns a lower-level surface or tile cache.

### 2.3 Synchronized output is an intentional update gate

`renderer/generic.zig:updateFrame` checks the terminal synchronized-output mode
while holding the state demand lock and returns without rebuilding when it is
active (lines 1176–1195). Ghostty's official documentation explains the user
visible reason: fast rendering can outrun programs that redraw large screen
areas, causing tearing; applications should use synchronized output and update
only cells that need changing.

Sources: [`src/renderer/generic.zig#L1176-L1195`](https://github.com/ghostty-org/ghostty/blob/ec58fbc6a2da89f6d17381d56ef316f29dbf789b/src/renderer/generic.zig#L1176-L1195), [Ghostty synchronized output documentation](https://ghostty.org/docs/help/synchronized-output)

**Recommendation:** Preserve this mode through the native session API. When
synchronized output is active, keep parsing/state mutation and defer frame
publication rather than emitting dozens of intermediate Compose invalidations.
Flush one complete frame when the mode ends. This is directly transferable and
more important than trying to render every parser wakeup.

## 3. Rendering data structures and batching

### 3.1 Terminal pages are contiguous and IO-oriented

`src/terminal/page.zig` allocates page-aligned, zeroed backing memory directly
with `mmap`/`VirtualAlloc`; pages are single contiguous blocks. The comments
explicitly say this avoids allocator overhead on the performance-critical path
and makes pages fast to copy/serialize. Rows and cells are laid out primarily
for terminal IO and low memory use (lines 30–56, 129–167, 230–249).

Source: [`src/terminal/page.zig#L30-L56`](https://github.com/ghostty-org/ghostty/blob/ec58fbc6a2da89f6d17381d56ef316f29dbf789b/src/terminal/page.zig#L30-L56), [`src/terminal/page.zig#L129-L167`](https://github.com/ghostty-org/ghostty/blob/ec58fbc6a2da89f6d17381d56ef316f29dbf789b/src/terminal/page.zig#L129-L167), [`src/terminal/page.zig#L230-L249`](https://github.com/ghostty-org/ghostty/blob/ec58fbc6a2da89f6d17381d56ef316f29dbf789b/src/terminal/page.zig#L230-L249)

**Non-transferable:** Android/Kotlin should not imitate mmap-backed page
ownership or pass page pointers over JNI. It is a native storage optimization,
not a UI model. The transferable property is stable ownership and contiguous
bulk copying at the native boundary.

### 3.2 Retained render state uses cache-friendly row data

`RenderState.row_data` is a `std.MultiArrayList(Row)`: update code owns the
allocators, while readers get cache-friendly column arrays (render lines 91–102).
Each row keeps an arena for cell managed-memory content but retains the
`MultiArrayList` capacity across row clears (lines 203–220). Large retained
frames are periodically deinitialized because the render state intentionally
holds capacity between updates (lines 65–71).

Sources: [`src/terminal/render.zig#L65-L125`](https://github.com/ghostty-org/ghostty/blob/ec58fbc6a2da89f6d17381d56ef316f29dbf789b/src/terminal/render.zig#L65-L125), [`src/terminal/render.zig#L203-L220`](https://github.com/ghostty-org/ghostty/blob/ec58fbc6a2da89f6d17381d56ef316f29dbf789b/src/terminal/render.zig#L203-L220)

**Recommendation:** Keep `TerminalFrame` immutable for consumers, but retain
mutable native/renderer-side row buffers between publications. Reuse row arrays
on unchanged geometry; replace only changed row contents. Periodically trim or
recreate buffers after an unusually large resize/output burst, rather than
retaining the maximum historical frame forever.

### 3.3 GPU cell contents are row-clearable and draw-instanced

`src/renderer/cell.zig:Contents` stores backgrounds in a flat
`row * columns + column` array and foreground glyph/underline/strike/overline
instances in one `ArrayList` per row. `resize` allocates backgrounds once and
creates `rows + 2` foreground lists, initially reserving `columns * 3` per row;
the two extra lists hold cursor cells that must be first/last (lines 33–73,
80–129). `clear(y)` zeroes one background row and clears that row's foreground
list while retaining capacity (lines 208–218).

Source: [`src/renderer/cell.zig#L33-L73`](https://github.com/ghostty-org/ghostty/blob/ec58fbc6a2da89f6d17381d56ef316f29dbf789b/src/renderer/cell.zig#L33-L73), [`src/renderer/cell.zig#L80-L129`](https://github.com/ghostty-org/ghostty/blob/ec58fbc6a2da89f6d17381d56ef316f29dbf789b/src/renderer/cell.zig#L80-L129), [`src/renderer/cell.zig#L208-L218`](https://github.com/ghostty-org/ghostty/blob/ec58fbc6a2da89f6d17381d56ef316f29dbf789b/src/renderer/cell.zig#L208-L218)

`rebuildCells` checks full damage/grid changes, otherwise skips clean rows,
clears only dirty rows, rebuilds them, and clears each render-state row dirty
flag (generic lines 2312–2447). During drawing, the renderer syncs one flat
background buffer and concatenates row lists into a foreground buffer; text is
one instanced draw with `instance_count = fg_count` (generic lines 1575–1580,
1656–1689).

Sources: [`src/renderer/generic.zig#L2312-L2447`](https://github.com/ghostty-org/ghostty/blob/ec58fbc6a2da89f6d17381d56ef316f29dbf789b/src/renderer/generic.zig#L2312-L2447), [`src/renderer/generic.zig#L1575-L1580`](https://github.com/ghostty-org/ghostty/blob/ec58fbc6a2da89f6d17381d56ef316f29dbf789b/src/renderer/generic.zig#L1575-L1580), [`src/renderer/generic.zig#L1656-L1689`](https://github.com/ghostty-org/ghostty/blob/ec58fbc6a2da89f6d17381d56ef316f29dbf789b/src/renderer/generic.zig#L1656-L1689)

**Recommendation:** This is directly applicable to `TerminalRowRenderer` and
`TerminalRowRuns`: make row identity and retained capacity explicit, clear and
rebuild only dirty rows, and combine runs into as few draw submissions as the
Compose/Skia backend allows. If row-level Skia invalidation is unavailable, it
still prevents expensive text shaping/path construction for clean rows.

### 3.4 GPU buffers grow geometrically

The OpenGL `Buffer(T)` wrapper starts with a preallocated buffer. `sync` and
`syncFromArrayLists` upload complete contents but reallocate only when needed;
when growth is needed they allocate twice the required item count. Smaller
updates replace bytes in place (`src/renderer/opengl/buffer.zig`, lines 15–24,
70–123).

Source: [`src/renderer/opengl/buffer.zig#L15-L24`](https://github.com/ghostty-org/ghostty/blob/ec58fbc6a2da89f6d17381d56ef316f29dbf789b/src/renderer/opengl/buffer.zig#L15-L24), [`src/renderer/opengl/buffer.zig#L70-L123`](https://github.com/ghostty-org/ghostty/blob/ec58fbc6a2da89f6d17381d56ef316f29dbf789b/src/renderer/opengl/buffer.zig#L70-L123)

**Recommendation:** Apply geometric growth to native snapshot scratch buffers,
row-run storage, and any Android bitmap/vertex staging buffers. Do not create a
new `ByteBuffer`, `Path`, or list for every frame when the grid is unchanged.
Compose-side allocations still need measurement: a larger retained buffer can
be worse than a modest capped cache on memory-constrained devices.

## 4. Allocation avoidance and lock duration

`updateFrame` creates one temporary arena for frame-local links, preedit,
overlays, and other extracted data, then destroys it after the frame (generic
lines 1143–1165). It periodically deinitializes and recreates retained
`terminal_state` every 100,000 frames (approximately 12 minutes at 120 Hz) to
avoid retaining an unusually large render allocation forever (lines 1149–1160).

Source: [`src/renderer/generic.zig#L1143-L1165`](https://github.com/ghostty-org/ghostty/blob/ec58fbc6a2da89f6d17381d56ef316f29dbf789b/src/renderer/generic.zig#L1143-L1165)

The two-phase render update is the key allocation/lock split: style
normalization is deferred to `endUpdate`, outside terminal access. This is
safer than holding a terminal lock while shaping or constructing UI objects.

**Recommendation:** Keep frame-local Android scratch in a reusable worker-owned
arena or equivalent pooled storage. Build a complete immutable publication
before waking Compose; never hold the native terminal lock while doing JNI
copies, text measurement, or Compose state writes. Preserve a bounded policy for
large transient allocations (periodic trim/recreate), and instrument before
choosing Ghostty's desktop frame-count threshold literally.

## 5. Scheduling, frame pacing, and backpressure

### 5.1 Renderer wakeups are coalesced; drawing is conditional

`renderer/Thread.zig` uses an async wakeup plus a bounded blocking mailbox
(capacity 64). `queueRender` in `Surface.zig` only notifies that async handle;
repeated producers therefore coalesce at the event-loop wakeup rather than
queueing one render task each time (Thread lines 34–50; Surface lines 2485–2490).
The wake callback drains the mailbox, updates frame state immediately, and
invokes rendering (Thread lines 546–588).

Sources: [`src/renderer/Thread.zig#L34-L50`](https://github.com/ghostty-org/ghostty/blob/ec58fbc6a2da89f6d17381d56ef316f29dbf789b/src/renderer/Thread.zig#L34-L50), [`src/Surface.zig#L2485-L2490`](https://github.com/ghostty-org/ghostty/blob/ec58fbc6a2da89f6d17381d56ef316f29dbf789b/src/Surface.zig#L2485-L2490), [`src/renderer/Thread.zig#L546-L588`](https://github.com/ghostty-org/ghostty/blob/ec58fbc6a2da89f6d17381d56ef316f29dbf789b/src/renderer/Thread.zig#L546-L588)

The renderer has a separate 8 ms draw timer (120 FPS), but only enables it for
animations according to config/focus. It skips drawing when invisible and,
unless forced, defers to a renderer with its own vsync (`Thread` lines 21–32,
312–350, 525–544). `generic.drawFrame` redraws only when size changed, cells
were rebuilt, animations exist, or a synchronous draw was requested; otherwise
it presents the last target (`generic` lines 1453–1507).

Sources: [`src/renderer/Thread.zig#L21-L32`](https://github.com/ghostty-org/ghostty/blob/ec58fbc6a2da89f6d17381d56ef316f29dbf789b/src/renderer/Thread.zig#L21-L32), [`src/renderer/Thread.zig#L312-L350`](https://github.com/ghostty-org/ghostty/blob/ec58fbc6a2da89f6d17381d56ef316f29dbf789b/src/renderer/Thread.zig#L312-L350), [`src/renderer/Thread.zig#L525-L544`](https://github.com/ghostty-org/ghostty/blob/ec58fbc6a2da89f6d17381d56ef316f29dbf789b/src/renderer/Thread.zig#L525-L544), [`src/renderer/generic.zig#L1453-L1507`](https://github.com/ghostty-org/ghostty/blob/ec58fbc6a2da89f6d17381d56ef316f29dbf789b/src/renderer/generic.zig#L1453-L1507)

`SwapChain.nextFrame` waits on a semaphore and `releaseFrame` posts it, so the
CPU cannot overwrite per-frame state still used by the GPU (generic lines
253–317).

Source: [`src/renderer/generic.zig#L253-L317`](https://github.com/ghostty-org/ghostty/blob/ec58fbc6a2da89f6d17381d56ef316f29dbf789b/src/renderer/generic.zig#L253-L317)

**Recommendation:** Keep the existing conflated invalidation plus
`withFrameNanos` strategy in Compose. Add an explicit `pendingFrame`/generation
check so a burst of native output produces one latest-frame application per
Android frame, while cursor/effect animation independently requests frames only
when active. A `Channel.CONFLATED` is appropriate for wakeup signaling; it is
not a substitute for a bounded frame/snapshot buffer when ownership or drop
semantics matter.

### 5.2 PTY output has a measured two-stage backpressure pipeline

On POSIX, `termio/Exec.zig:ReadThread` separates gathering bytes from parsing:
`io-gather` reads into a fixed rotating ring while `io-reader` calls
`processOutput` on each complete batch. The source explains that macOS can cap
master PTY reads around 1 KiB; a serial read/parse loop can therefore stall a
producer while parsing. The gather thread drains concurrently and preserves
interactive latency with a first-EAGAIN boundary, saturation threshold, bounded
spin/poll bridge, and sub-frame budget (lines 1268–1303).

The constants are four buffers, 64 KiB each, 1 KiB saturation threshold, up to
16 immediate retries, 1 ms bridge poll, and a 3 ms total gather budget (lines
1304–1356). Ring metadata is mutex-protected, but each buffer belongs to exactly
one stage; when all four are in flight, the gather stage waits on `slot_free`,
which lets the kernel PTY queue apply backpressure (lines 1358–1409,
1550–1562). The parser yields to lock demand at batch boundaries (lines
1492–1518).

Sources: [`src/termio/Exec.zig#L1268-L1303`](https://github.com/ghostty-org/ghostty/blob/ec58fbc6a2da89f6d17381d56ef316f29dbf789b/src/termio/Exec.zig#L1268-L1303), [`src/termio/Exec.zig#L1304-L1409`](https://github.com/ghostty-org/ghostty/blob/ec58fbc6a2da89f6d17381d56ef316f29dbf789b/src/termio/Exec.zig#L1304-L1409), [`src/termio/Exec.zig#L1477-L1518`](https://github.com/ghostty-org/ghostty/blob/ec58fbc6a2da89f6d17381d56ef316f29dbf789b/src/termio/Exec.zig#L1477-L1518), [`src/termio/Exec.zig#L1550-L1562`](https://github.com/ghostty-org/ghostty/blob/ec58fbc6a2da89f6d17381d56ef316f29dbf789b/src/termio/Exec.zig#L1550-L1562)

The gather loop bridges only saturated streams, spins for up to 16 reads, polls
for 1 ms, and stops after 3 ms; small interactive output is delivered at the
first EAGAIN (lines 1569–1666). This is a concrete latency/throughput tradeoff,
not a universal Android constant.

Source: [`src/termio/Exec.zig#L1569-L1666`](https://github.com/ghostty-org/ghostty/blob/ec58fbc6a2da89f6d17381d56ef316f29dbf789b/src/termio/Exec.zig#L1569-L1666)

**Recommendation:** The direct Compose-app analogue is a bounded byte ring plus
single native parser owner, with a maximum parse byte/time slice and explicit
latest-frame drop/coalescing. Do not copy the POSIX thread count or constants
without Android profiling; the invariant to preserve is bounded memory and
producer backpressure while keeping small interactive writes low latency.

## 6. What transfers to Compose, and what does not

| Ghostty pattern | Compose transfer | Boundary / caution |
|---|---|---|
| Native VT stream, `nextSlice`, printable runs | **Direct:** parse byte slices on native owner; batch printable work | Keep all parser/terminal state native; JNI callbacks are side-effect boundaries |
| Terminal/screen/full + render/row damage | **Direct:** add full-vs-row damage to frame metadata | Compose invalidation itself is not GPU damage; it only avoids model/rebuild work |
| RenderState `beginUpdate`/`endUpdate` | **Direct:** lock only to extract/copy, finish derived work unlocked | A copied `TerminalFrame` must be complete before publication |
| Retained row lists and row clear | **Direct:** reuse `TerminalRowRuns`/row renderer capacity | Keep deterministic ownership; avoid exposing mutable rows to UI |
| Flat background + instanced foreground buffers | **Partial:** batch runs, reuse arrays/bitmaps | Skia/Compose Canvas may not provide persistent instance buffers or dirty rect upload |
| Page mmap, packed Zig rows, SIMD mask scans | **No direct port:** native implementation detail | Kotlin data classes/objects would erase the benefit and increase GC pressure |
| xev async wakeup + renderer thread | **Partial:** conflated invalidation + frame clock | Android UI must own Compose state; native worker must never mutate it directly |
| Four 64 KiB PTY ring / 3 ms gather budget | **Principle only:** bounded ring and budget | Measure Android transport/PTY behavior; tune constants per device |
| Swapchain semaphore | **Conceptual:** bounded staging/publish slots | Skia/Compose controls presentation; avoid blocking UI on native GPU completion |
| Synchronized output pause | **Direct:** defer publication while mode active | Ensure timeout/reset behavior and final flush are implemented in the native seam |

## 7. Strongest actionable findings for this repository

1. **Promote damage to a first-class frame contract.** Keep immutable snapshots,
   but add full/partial damage and changed-row ranges. Have native damage reset
   only after snapshot construction succeeds; let the Compose controller skip
   clean row-run rebuilds.
2. **Make row retention the default hot path.** Reuse per-row run storage and
   clear only dirty rows. Reserve enough for the normal row shape, allow rare
   growth for combining/multi-glyph cases, and trim after an outlier burst.
3. **Separate native update from Compose draw.** Parse/mutate/publish on the
   worker. Compose applies at most the newest pending frame on a frame boundary;
   draw only when the frame generation, cursor/effect clock, size, or shader
   state requires it.
4. **Add bounded ingress/backpressure before optimizing Canvas.** A bounded
   PTY/byte ring and parse budget protect ANR risk better than shaving individual
   Canvas calls. Drop/coalesce intermediate frame notifications, never terminal
   bytes.
5. **Honor synchronized output and other full invalidations.** Defer UI frame
   publication during synchronized output, and force full damage for resize,
   active-screen changes, palette/font/glossary changes, and viewport moves.
6. **Use borrowed-vs-owned rules at JNI.** Native parser callback data is
   temporary. Copy only side effects that must outlive the callback and publish
   immutable frame bytes/objects; never hand Kotlin a pointer into mutable
   `RenderState`.
7. **Tune, do not cargo-cult, desktop numbers.** Ghostty's 4×64 KiB, 3 ms, 8 ms,
   and 100,000-frame values are observed desktop choices. Adopt their invariants,
   then benchmark Android device classes with parser, frame-build, GC, and
   dropped-frame counters.

## Primary source index

- Pinned dependency: `terminal-emulator/src/main/zig/build.zig.zon`, Ghostty commit [`ec58fbc6a2da89f6d17381d56ef316f29dbf789b`](https://github.com/ghostty-org/ghostty/commit/ec58fbc6a2da89f6d17381d56ef316f29dbf789b)
- VT overview: [Ghostty Terminal API (VT)](https://ghostty.org/docs/vt)
- Synchronization guidance: [Ghostty Synchronized Output](https://ghostty.org/docs/help/synchronized-output)
- C API umbrella: [`include/ghostty/vt.h`](https://github.com/ghostty-org/ghostty/blob/ec58fbc6a2da89f6d17381d56ef316f29dbf789b/include/ghostty/vt.h)
- C render API: [`include/ghostty/vt/render.h`](https://github.com/ghostty-org/ghostty/blob/ec58fbc6a2da89f6d17381d56ef316f29dbf789b/include/ghostty/vt/render.h)
