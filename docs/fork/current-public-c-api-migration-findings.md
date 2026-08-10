# Current public `libghostty-vt` C API migration findings

## Scope

This report compares the project’s current direct Zig integration with the
current upstream public C API. It intentionally excludes the Rust port.

The upstream comparison target is Ghostty `main` at commit
`ec58fbc6a2da89f6d17381d56ef316f29dbf789b`, inspected on 2026-08-06. This is
not a claim that every change is present in a stable Ghostty release.

## What the project uses today

The project does **not** currently use the standalone public `libghostty-vt`
C library. It imports Ghostty’s internal Zig `ghostty-vt` module directly:

- `terminal-emulator/src/main/zig/build.zig:22-28` imports the dependency module
  named `ghostty-vt`.
- `terminal-emulator/src/main/zig/build.zig.zon:7-9` pins Ghostty at
  `73534c4680a809398b396c94ac7f12fcccb7963d`.
- `terminal-emulator/src/main/zig/src/termux_ghostty.zig:3` imports the module,
  and `:150-152` owns `ghostty.Terminal` and `ghostty.Stream` directly.
- The project builds its own `libtermux-ghostty.so`; it does not load a separate
  `libghostty-vt.so`.

A migration would therefore move from direct internal Zig APIs to the opaque
public C ABI generated from the same terminal implementation.

## New public capabilities in current upstream `main`

### Progress and desktop notifications

The previous public-API gap is now addressed upstream:

- `include/ghostty/vt/terminal.h:84-99` lists
  `GHOSTTY_TERMINAL_OPT_DESKTOP_NOTIFICATION` and
  `GHOSTTY_TERMINAL_OPT_PROGRESS_REPORT`.
- `include/ghostty/vt/terminal.h:453-545` defines the borrowed notification
  and progress callback payloads.
- `src/terminal/c/terminal.zig:130-167` and `:202-214` define the matching
  native structs and callbacks.
- `src/terminal/stream_terminal.zig:331-344` dispatches these effects.

These callbacks can preserve the project’s existing behavior from
`termux_ghostty.zig:1129-1132` and `:1343-1351`. The callback data is borrowed
and valid only during the synchronous callback, so the adapter must copy it
into its worker-owned queue/state.

### Complete snapshots and parser continuation

Current upstream adds `include/ghostty/vt/snapshot.h`, which provides:

- complete terminal snapshot encoding;
- restore and incremental decoding;
- active-screen and scrollback pages;
- authenticated record/checkpoint handling; and
- preservation of unfinished VT/UTF-8 parser state.

The terminal API also adds replay-safe continuation export functions in
`include/ghostty/vt/terminal.h:1548-1605` and the continuation option/data
fields. This could be useful for lifecycle persistence and fragmented-stream
recovery.

This is **not a drop-in replacement** for the project’s existing Java frame
format. The current serializer in `termux_ghostty.zig:1857-2000` is a UI
snapshot containing render metadata and viewport links. The existing format
can remain while the C render APIs are used to populate it, or the new terminal
snapshot format can be evaluated separately. The snapshot header explicitly
marks format version 1 as work in progress without a binary-compatibility
guarantee.

### Scrollback limits and diagnostics

New terminal options/data include:

- maximum scrollback bytes;
- maximum scrollback lines;
- maximum VT continuation bytes; and
- a sticky VT-processing-error diagnostic.

These are documented in `include/ghostty/vt/terminal.h:969-1020` and
`:1375-1422`. They provide better memory-limit and parser-diagnostic controls
than the older line-count-only construction option.

### Title reporting

CSI 21t title reporting is now an explicit option,
`GHOSTTY_TERMINAL_OPT_TITLE_REPORT`, documented in
`include/ghostty/vt/terminal.h` and implemented by the current C terminal
layer.

The project currently responds to CSI 21t in
`termux_ghostty.zig:1277-1304`. A migration must explicitly enable the public
option, because the public API defaults this behavior off for security reasons
(title contents can be fed back into the pty).

### Kitty/APC and other parser behavior

The current public stream implementation handles APC Kitty actions and DCS
processing in `src/terminal/stream_terminal.zig:325-360`. The project’s current
handler deliberately ignores APC/DCS actions at
`termux_ghostty.zig:1124-1125`, so this is a potential capability gain rather
than a feature loss. The project would still need to configure Kitty graphics
and connect the public image-data API to a renderer if it wants to use it.

The public API also has newer Kitty temporary-file directory controls and
pending-image payload semantics in `include/ghostty/vt/terminal.h:840-902` and
`include/ghostty/vt/kitty_graphics.h:390-435`.

## What would be preserved

With the current public C API and an adapter, the following project behaviors
can be retained:

- PTY replies, XTVERSION, device attributes, device status, and size reports;
- title, bell, PWD, clipboard, color-scheme, progress, and notification effects;
- terminal modes, alternate screen, cursor movement, colors, hyperlinks,
  semantic prompt state, selection, and scrollback;
- render rows/cells, dirty tracking, palette data, and viewport state;
- JNI worker ownership, bounded processing, frame coalescing, and the existing
  Java snapshot protocol.

The public C effect table is at `include/ghostty/vt/terminal.h:84-99`. The
public render API supports dirty rows/cells and selected ranges, while grid
references support hyperlink URI lookup. These APIs replace direct access to
Zig internals but can supply the data needed by the existing serializer.

The project’s custom device attributes and XTVERSION values remain adapter
callbacks. The public API does not choose Termux’s exact identity for us; it
allows the adapter to provide it.

## Remaining parity work or losses

### 1. No direct Kitty keyboard stack setter

The project initializes Kitty keyboard flags on both primary and alternate
screens at `termux_ghostty.zig:1497-1511`. The public API exposes current flags
through terminal data, but does not expose a direct setter for this stack.

Possible mitigations are an initialization VT sequence, an explicit policy when
switching screens, or a small upstream/public-API addition. Sending a sequence
must be tested because the project currently initializes both screen states
explicitly.

### 2. No aggregate append-result bitmask

The project returns result bits for screen, cursor, title, bell, clipboard,
colors, replies, notifications, and progress. This is computed at
`termux_ghostty.zig:1731-1775`.

`ghostty_terminal_vt_write()` still returns `void`. The adapter must recreate
these bits from synchronous callbacks, render-state dirtiness, terminal data
comparisons, and pending-output bookkeeping. This is feasible but not a
single-call public API equivalent.

### 3. No raw action interception

The public API does not expose the internal `StreamAction` switch. The adapter
cannot directly override or observe every parser action. This is acceptable
for current behavior because the public stream applies normal terminal state,
but future Ghostty-specific host behavior would require a new public effect or
an internal shim.

### 4. Prompt-redraw default differs

Ghostty’s internal terminal default is
`Terminal.zig:94`, where `shell_redraws_prompt` defaults true. The public C
wrapper explicitly sets it false in `src/terminal/c/terminal.zig:600`.

This must be treated as a deliberate compatibility decision. It can affect OSC
133 prompt redraw behavior during resize.

### 5. Borrowed-data and opaque-handle boundaries

The public callbacks and render iterators expose borrowed data with strict
lifetimes. The worker must copy callback strings, clipboard data, notification
payloads, and row/cell data before releasing the C-side access window. Direct
Zig pointers and internal page pins cannot be retained across calls.

## Build and stability constraints

- Current upstream `build.zig.zon` requires Zig `0.16.0`; this project currently
  provisions Zig `0.15.2`.
- The public C API is still documented as unstable in `src/lib_vt.zig`.
- The snapshot format is explicitly work in progress.
- The Android build must still produce one bundled
  `libtermux-ghostty.so` for all currently supported ABIs.
- The worker-thread ownership and JNI lifecycle ordering should remain unchanged.

## Verdict

Migrating to the current upstream public C API should not require dropping a
major user-visible terminal feature. Progress and desktop notifications are
now first-class public effects, and current upstream adds snapshots,
continuation handling, memory limits, diagnostics, and stronger Kitty/APC
support.

The migration still needs explicit parity work for Kitty keyboard
initialization, append-result flags, CSI 21t opt-in, prompt-redraw defaults,
custom callback values, and the existing Java frame serializer. These are
adapter-boundary differences rather than evidence that the public terminal
parser is missing required functionality.

The lowest-risk approach is to preserve the existing worker/JNI/frame
architecture, target a pinned Ghostty revision containing the new C API, and
implement a small C-facing adapter that translates public callbacks and render
state into the existing session contract. Do not treat upstream `main` or the
new snapshot format as stable until the exact revision and Android ABI builds
are validated.
