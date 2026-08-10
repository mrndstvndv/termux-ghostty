# Ghostling’s `libghostty-vt` consumption: primary-source findings

## Scope and revision

This is a source-code investigation of `ghostty-org/ghostling`, not a build of the
application.  The Ghostling checkout examined is commit
[`f9034e43a50a2f3a8101e35497f486090c1ddd6e`](https://github.com/ghostty-org/ghostling/tree/f9034e43a50a2f3a8101e35497f486090c1ddd6e).
Its CMake file pins Ghostty to
[`ae52f97dcac558735cfa916ea3965f247e5c6e9e`](https://github.com/ghostty-org/ghostty/tree/ae52f97dcac558735cfa916ea3965f247e5c6e9e),
so Ghostty source references below are to that exact dependency revision.

## Executive answer

* **Build/link:** Ghostling uses CMake `FetchContent` to fetch the pinned Ghostty
  checkout.  That checkout's CMake wrapper runs Zig to emit `libghostty-vt`,
  creates an imported **shared** target named `ghostty-vt`, and exposes generated
  headers.  Ghostling links `raylib ghostty-vt`; it does not select the static
  target.
* **Boundary:** The application is one C11 translation unit including the
  generated umbrella header `<ghostty/vt.h>`.  It uses the public C ABI (opaque
  handles, enums/structs, pointer+length buffers, and function-pointer
  callbacks); it does not bind to Zig internals or use a second FFI layer.
* **Threading:** Ghostling is explicitly single-threaded.  Its UI loop drains a
  non-blocking PTY, calls `ghostty_terminal_vt_write`, handles input, updates a
  render-state snapshot, and renders in sequence.  `forkpty` creates a separate
  shell **process**, not a libghostty worker thread.  Effects callbacks run
  synchronously during `ghostty_terminal_vt_write`; no application lock is used.
* **Lifecycle:** A process-global PNG decoder is installed before the terminal is
  created; terminal, encoders/events, render state, and iterators are then
  created and reused.  Resizing mutates both libghostty and the PTY.  Cleanup
  closes/reaps the child and frees borrowed-handle owners in reverse dependency
  order, with the terminal freed last.
* **Versioning:** There is no Ghostling release/version field in the examined
  project.  Compatibility is controlled by the exact Ghostty Git commit and Zig
  toolchain pin.  The pinned Ghostty build describes libghostty-vt as
  `0.1.0-dev`, while its CMake wrapper uses project/output version `0.1.0`; the
  public header warns that this C API is unstable and breaking changes are
  expected.

## 1. Build and link path

### Ghostling’s CMake entry point

[`CMakeLists.txt#L33-L42`](https://github.com/ghostty-org/ghostling/blob/f9034e43a50a2f3a8101e35497f486090c1ddd6e/CMakeLists.txt#L33-L42)
uses `FetchContent_Declare(ghostty)` with the full commit hash
`ae52f97dcac558735cfa916ea3965f247e5c6e9e`, followed by
`FetchContent_MakeAvailable(ghostty)`.  This means the dependency is built from
source as part of the Ghostling CMake build; it is not a system-installed
libghostty-vt and there is no separately downloaded binary.

Ghostling resolves `zig` itself with `find_program(... NO_CACHE)` and force-sets
the `ZIG_EXECUTABLE` cache entry before adding Ghostty
([`CMakeLists.txt#L9-L14`](https://github.com/ghostty-org/ghostling/blob/f9034e43a50a2f3a8101e35497f486090c1ddd6e/CMakeLists.txt#L9-L14)).
This avoids a stale Zig store path when the Nix flake/dev shell changes.

The Ghostling comment says the wrapper delegates to “`zig build lib-vt`”
([`CMakeLists.txt#L33-L37`](https://github.com/ghostty-org/ghostling/blob/f9034e43a50a2f3a8101e35497f486090c1ddd6e/CMakeLists.txt#L33-L37));
the pinned Ghostty wrapper shows the actual command: `zig build -Demit-lib-vt`
([`ghostty/CMakeLists.txt#L137-L148`](https://github.com/ghostty-org/ghostty/blob/ae52f97dcac558735cfa916ea3965f247e5c6e9e/CMakeLists.txt#L137-L148)).
That command emits both shared and static artifacts and installs the generated
headers under the fetched checkout's `zig-out` directory.

Ghostling builds a C11 executable and links the imported shared target:

```cmake
add_executable(ghostling main.c ...)
target_compile_features(ghostling PRIVATE c_std_11)
target_link_libraries(ghostling raylib ghostty-vt)
```

See [`CMakeLists.txt#L60-L65`](https://github.com/ghostty-org/ghostling/blob/f9034e43a50a2f3a8101e35497f486090c1ddd6e/CMakeLists.txt#L60-L65).
There is no `ghostty-vt-static` link and no `GHOSTTY_STATIC` consumer definition
in Ghostling.  The dependency wrapper defines `ghostty-vt` as an imported
`SHARED` library, points its include directory at `zig-out/include`, and makes
it depend on the Zig build target
([`ghostty/CMakeLists.txt#L155-L176`](https://github.com/ghostty-org/ghostty/blob/ae52f97dcac558735cfa916ea3965f247e5c6e9e/CMakeLists.txt#L155-L176)).
The wrapper separately defines `ghostty-vt-static` as an imported static target
([`ghostty/CMakeLists.txt#L178-L202`](https://github.com/ghostty-org/ghostty/blob/ae52f97dcac558735cfa916ea3965f247e5c6e9e/CMakeLists.txt#L178-L202)),
but Ghostling does not use it.

The wrapper maps CMake Release-like build types to Zig `ReleaseFast`
([`ghostty/CMakeLists.txt#L76-L90`](https://github.com/ghostty-org/ghostty/blob/ae52f97dcac558735cfa916ea3965f247e5c6e9e/CMakeLists.txt#L76-L90)).
Thus Ghostling's documented `-DCMAKE_BUILD_TYPE=Release` also optimizes the
libghostty-vt build; a default/debug build retains Ghostty's debug checks.  The
Ghostling README explicitly warns that debug builds are very slow
([`README.md#L118-L128`](https://github.com/ghostty-org/ghostling/blob/f9034e43a50a2f3a8101e35497f486090c1ddd6e/README.md#L118-L128)).

## 2. C/FFI/API boundary

### What crosses the boundary

`main.c` includes `raylib.h` and the public umbrella header
`<ghostty/vt.h>` ([`main.c#L18-L24`](https://github.com/ghostty-org/ghostling/blob/f9034e43a50a2f3a8101e35497f486090c1ddd6e/main.c#L18-L24)).
The umbrella header includes the terminal, render, input, system, Kitty graphics,
and allocator headers inside `extern "C"`
([`ghostty/vt.h#L112-L147`](https://github.com/ghostty-org/ghostty/blob/ae52f97dcac558735cfa916ea3965f247e5c6e9e/include/ghostty/vt.h#L112-L147)).
The C API's symbols are exported by the Zig library only when the C-library
build is selected; `src/lib_vt.zig` explicitly re-exports the `ghostty_*` C
symbols ([`src/lib_vt.zig#L135-L160`](https://github.com/ghostty-org/ghostty/blob/ae52f97dcac558735cfa916ea3965f247e5c6e9e/src/lib_vt.zig#L135-L160)).
There is no hand-written Zig shim, JNI layer, generated language binding, or
C++ adapter in Ghostling.

The C ABI is intentionally opaque.  `GhosttyTerminal`, render-state handles,
Kitty graphics handles, and related objects are pointer handles rather than
structs whose internals Ghostling can access
([`types.h#L88-L158`](https://github.com/ghostty-org/ghostty/blob/ae52f97dcac558735cfa916ea3965f247e5c6e9e/include/ghostty/vt/types.h#L88-L158)).
The header also defines platform export/import visibility through `GHOSTTY_API`
([`types.h#L14-L34`](https://github.com/ghostty-org/ghostty/blob/ae52f97dcac558735cfa916ea3965f247e5c6e9e/include/ghostty/vt/types.h#L14-L34)).
Sized structs are initialized with `GHOSTTY_INIT_SIZED`; this is the API's
forward-compatible ABI pattern ([`types.h#L230-L249`](https://github.com/ghostty-org/ghostty/blob/ae52f97dcac558735cfa916ea3965f247e5c6e9e/include/ghostty/vt/types.h#L230-L249)).
Ghostling uses that pattern for render colors/style (`main.c#L809-L812`,
[`#L898-L902`](https://github.com/ghostty-org/ghostling/blob/f9034e43a50a2f3a8101e35497f486090c1ddd6e/main.c#L898-L902)).

### Terminal input/output flow

1. `pty_spawn` uses `forkpty`, puts the parent master descriptor in
   non-blocking mode, and starts the user's shell in the child
   ([`main.c#L30-L95`](https://github.com/ghostty-org/ghostling/blob/f9034e43a50a2f3a8101e35497f486090c1ddd6e/main.c#L30-L95)).
2. Each frame, `pty_read` drains bytes and passes each chunk directly to
   `ghostty_terminal_vt_write(terminal, buf, len)`
   ([`main.c#L119-L156`](https://github.com/ghostty-org/ghostling/blob/f9034e43a50a2f3a8101e35497f486090c1ddd6e/main.c#L119-L156)).
   The public API documents this as the raw VT parser/state update entry point
   ([`terminal.h#L998-L1020`](https://github.com/ghostty-org/ghostty/blob/ae52f97dcac558735cfa916ea3965f247e5c6e9/include/ghostty/vt/terminal.h#L998-L1020)).
3. Keyboard events are normalized into a reusable `GhosttyKeyEvent`; the
   reusable `GhosttyKeyEncoder` is synchronized from terminal modes and emits
   bytes into a stack buffer, which Ghostling writes to the PTY
   ([`main.c#L444-L563`](https://github.com/ghostty-org/ghostling/blob/f9034e43a50a2f3a8101e35497f486090c1ddd6e/main.c#L444-L563)).
4. Mouse events follow the same pattern with `GhosttyMouseEvent` and
   `GhosttyMouseEncoder`; tracking mode is read from terminal state, and
   encoded bytes go to the PTY ([`main.c#L304-L442`](https://github.com/ghostty-org/ghostling/blob/f9034e43a50a2f3a8101e35497f486090c1ddd6e/main.c#L304-L442)).
5. The terminal is copied into a reusable `GhosttyRenderState` with
   `ghostty_render_state_update`; Ghostling then walks row/cell data and draws
   text, colors, cursor, scrollbar, and Kitty images with Raylib
   ([`main.c#L784-L818`](https://github.com/ghostty-org/ghostling/blob/f9034e43a50a2f3a8101e35497f486090c1ddd6e/main.c#L784-L818),
   [`main.c#L1528-L1551`](https://github.com/ghostty-org/ghostling/blob/f9034e43a50a2f3a8101e35497f486090c1ddd6e/main.c#L1528-L1551)).
   This is why libghostty-vt supplies terminal/render state but no window or
   drawing implementation; Ghostling's README says so explicitly
   ([`README.md#L24-L38`](https://github.com/ghostty-org/ghostling/blob/f9034e43a50a2f3a8101e35497f486090c1ddd6e/README.md#L24-L38)).

### Library-to-application effects

Ghostling stores an `EffectsContext` containing the PTY fd, cell metrics, and
current grid dimensions, then supplies its address as terminal userdata.  It
registers six effects with `ghostty_terminal_set`: PTY writes, size reports,
device attributes, XTVERSION, title changes, and color-scheme queries
([`main.c#L1085-L1189`](https://github.com/ghostty-org/ghostling/blob/f9034e43a50a2f3a8101e35497f486090c1ddd6e/main.c#L1085-L1189),
[`main.c#L1301-L1327`](https://github.com/ghostty-org/ghostling/blob/f9034e43a50a2f3a8101e35497f486090c1ddd6e/main.c#L1301-L1327)).

The important boundary semantics come from the upstream header: effects are
invoked synchronously inside `ghostty_terminal_vt_write`; callbacks must not
re-enter `ghostty_terminal_vt_write` on that terminal and should not block or do
expensive work ([`terminal.h#L43-L70`](https://github.com/ghostty-org/ghostty/blob/ae52f97dcac558735cfa916ea3965f247e5c6e9/include/ghostty/vt/terminal.h#L43-L70)).
Ghostling's write callback therefore calls its non-blocking `pty_write` directly
([`main.c#L98-L117`](https://github.com/ghostty-org/ghostling/blob/f9034e43a50a2f3a8101e35497f486090c1ddd6e/main.c#L98-L117),
[`main.c#L1100-L1110`](https://github.com/ghostty-org/ghostling/blob/f9034e43a50a2f3a8101e35497f486090c1ddd6e/main.c#L1100-L1110));
the title callback reads a borrowed title and immediately copies it into a
NUL-terminated Raylib title buffer ([`main.c#L1161-L1177`](https://github.com/ghostty-org/ghostling/blob/f9034e43a50a2f3a8101e35497f486090c1ddd6e/main.c#L1161-L1177)).

### Process-global system hook and Kitty graphics

Before creating a terminal, Ghostling installs a PNG decoder with
`ghostty_sys_set(GHOSTTY_SYS_OPT_DECODE_PNG, decode_png)`
([`main.c#L1269-L1273`](https://github.com/ghostty-org/ghostling/blob/f9034e43a50a2f3a8101e35497f486090c1ddd6e/main.c#L1269-L1273)).
The decoder converts Raylib/stb pixels to RGBA and allocates the output with the
allocator passed by libghostty-vt ([`main.c#L1051-L1083`](https://github.com/ghostty-org/ghostling/blob/f9034e43a50a2f3a8101e35497f486090c1ddd6e/main.c#L1051-L1083));
that ownership rule is documented by `sys.h`
([`sys.h#L16-L29`](https://github.com/ghostty-org/ghostty/blob/ae52f97dcac558735cfa916ea3965f247e5c6e9/include/ghostty/vt/sys.h#L16-L29),
[`sys.h#L107-L125`](https://github.com/ghostty-org/ghostty/blob/ae52f97dcac558735cfa916ea3965f247e5c6e9/include/ghostty/vt/sys.h#L107-L125)).
The system interface is process-global and must be set at startup, so it is not
per-terminal state.

Kitty graphics are enabled by setting a 64 MiB image-storage limit and enabling
file, temporary-file, and shared-memory media
([`main.c#L1329-L1343`](https://github.com/ghostty-org/ghostling/blob/f9034e43a50a2f3a8101e35497f486090c1ddd6e/main.c#L1329-L1343)).
Ghostling obtains image/placement handles from the terminal and reads them only
while rendering.  Those handles are borrowed and invalidated by any mutating
terminal call; the upstream lifetime/thread-safety section documents this
explicitly ([`kitty_graphics.h#L31-L43`](https://github.com/ghostty-org/ghostty/blob/ae52f97dcac558735cfa916ea3965f247e5c6e9/include/ghostty/vt/kitty_graphics.h#L31-L43),
[`kitty_graphics.h#L87-L93`](https://github.com/ghostty-org/ghostty/blob/ae52f97dcac558735cfa916ea3965f247e5c6e9/include/ghostty/vt/kitty_graphics.h#L87-L93));
Ghostling does not retain them across a subsequent terminal mutation.

## 3. Threading and event-loop lifecycle

Ghostling's README states that the example is **single-threaded**, even though it
says libghostty-vt supports threading
([`README.md#L3-L11`](https://github.com/ghostty-org/ghostling/blob/f9034e43a50a2f3a8101e35497f486090c1ddd6e/README.md#L3-L11)).
The C source contains no pthread/thread creation.  The only concurrency-like
boundary is the shell process made by `forkpty`; the parent owns the PTY master
and polls it from the Raylib/UI loop.

The loop is deliberately serialized ([`main.c#L1430-L1531`](https://github.com/ghostty-org/ghostling/blob/f9034e43a50a2f3a810c1ddd6e/main.c#L1430-L1531)):

1. Resize the libghostty terminal and update the PTY `winsize`/effect context.
2. Detect focus transitions and encode focus events when DECSET 1004 is active.
3. Drain PTY output into `ghostty_terminal_vt_write`.
4. Reap the child non-blockingly after PTY EOF/error.
5. Encode keyboard and mouse input and write it to the PTY.
6. Call `ghostty_render_state_update` and render the snapshot.

Resize updates both sides: `ghostty_terminal_resize` reflows/updates the
terminal, while `ioctl(TIOCSWINSZ)` tells the child its new dimensions and
causes the usual `SIGWINCH` behavior ([`main.c#L1431-L1461`](https://github.com/ghostty-org/ghostling/blob/f9034e43a50a2f3a8101e35497f486090c1ddd6e/main.c#L1431-L1461)).
The parent stops reading/input after PTY EOF/error, keeps the final state
visible, and repeatedly attempts `waitpid(WNOHANG)` until the child is reaped
([`main.c#L1488-L1526`](https://github.com/ghostty-org/ghostling/blob/f9034e43a50a2f3a8101e35497f486090c1ddd6e/main.c#L1488-L1526)).

The upstream render API can support a separate renderer thread, but only if the
embedder holds a lock during the terminal-to-render-state update
([`render.h#L22-L41`](https://github.com/ghostty-org/ghostty/blob/ae52f97dcac558735cfa916ea3965f247e5c6e9/include/ghostty/vt/render.h#L22-L41)).
Ghostling does not implement that arrangement: it updates and reads render state
on the same serialized loop.  Therefore its callbacks can also call Raylib
(`SetWindowTitle`) without a cross-thread handoff.  Moving PTY parsing or effects
to a worker would require an application-owned synchronization/UI dispatch
strategy; no such strategy exists in this demo.

## 4. Allocation, handle ownership, and shutdown

All long-lived handles are initialized to `NULL` and created once after the
terminal (`GhosttyTerminal`, key/mouse encoder and event, render state, row
iterator/cells, Kitty placement iterator)
([`main.c#L1254-L1267`](https://github.com/ghostty-org/ghostling/blob/f9034e43a50a2f3a8101e35497f486090c1ddd6e/main.c#L1254-L1267),
[`main.c#L1275-L1409`](https://github.com/ghostty-org/ghostling/blob/f9034e43a50a2f3a8101e35497f486090c1ddd6e/main.c#L1275-L1409)).
Passing `NULL` as the allocator selects libghostty-vt's default allocator.  The
`EffectsContext` is a stack object in `main`, but remains valid because the
terminal is used only within that function and is freed before `main` returns.

At shutdown Ghostling:

1. unloads the font and closes the window;
2. closes the PTY and, if needed, sends `SIGHUP` and blocks in `waitpid` to avoid
   a zombie;
3. frees the independently owned Kitty placement iterator, mouse/key events
   and encoders, row cells/iterator, render state; and
4. frees the terminal last.

See [`main.c#L1582-L1603`](https://github.com/ghostty-org/ghostling/blob/f9034e43a50a2f3a8101e35497f486090c1ddd6e/main.c#L1582-L1603).
This ordering is consistent with the API's rule that terminal-derived Kitty
handles and render data are borrowed and become invalid after mutation; only
objects explicitly created with `*_new` are freed by Ghostling.

## 5. Versioning and upgrade surface

### Dependency/toolchain pins

* Ghostling pins Ghostty by commit, not by a moving branch or release tag:
  [`CMakeLists.txt#L38-L42`](https://github.com/ghostty-org/ghostling/blob/f9034e43a50a2f3a8101e35497f486090c1ddd6e/CMakeLists.txt#L38-L42).
* The README/agent instructions require Zig `0.15.x`
  ([`README.md#L101-L110`](https://github.com/ghostty-org/ghostling/blob/f9034e43a50a2f3a8101e35497f486090c1ddd6e/README.md#L101-L110),
  [`AGENTS.md#L3-L10`](https://github.com/ghostty-org/ghostling/blob/f9034e43a50a2f3a8101e35497f486090c1ddd6e/AGENTS.md#L3-L10)).
  The flake selects Zig `0.15.2` on both Darwin and other systems
  ([`flake.nix#L19-L33`](https://github.com/ghostty-org/ghostling/blob/f9034e43a50a2f3a8101e35497f486090c1ddd6e/flake.nix#L19-L33)),
  and the pinned Ghostty source declares minimum Zig `0.15.2`
  ([`build.zig.zon#L1-L7`](https://github.com/ghostty-org/ghostty/blob/ae52f97dcac558735cfa916ea3965f247e5c6e9e/build.zig.zon#L1-L7)).
* Ghostling's local upgrade instructions are explicit: change the CMake pin,
  clean the build directory to remove stale fetched/generated artifacts, then
  rebuild to detect API changes ([`AGENTS.md#L18-L28`](https://github.com/ghostty-org/ghostling/blob/f9034e43a50a2f3a8101e35497f486090c1ddd6e/AGENTS.md#L18-L28)).

### Library version/API stability

The pinned Ghostty build passes `lib_version = "0.1.0-dev"` to its build
configuration ([`build.zig#L6-L17`](https://github.com/ghostty-org/ghostty/blob/ae52f97dcac558735cfa916ea3965f247e5c6e9/build.zig#L6-L17)).
The CMake wrapper itself declares project version `0.1.0` and names the emitted
shared library/soname with `0.1.0` components
([`ghostty/CMakeLists.txt#L69-L70`](https://github.com/ghostty-org/ghostty/blob/ae52f97dcac558735cfa916ea3965f247e5c6e9/CMakeLists.txt#L69-L70),
[`ghostty/CMakeLists.txt#L102-L131`](https://github.com/ghostty-org/ghostty/blob/ae52f97dcac558735cfa916ea3965f247e5c6e9/CMakeLists.txt#L102-L131)).
These are dependency-side library versions; Ghostling has no own project version
in its `project(ghostling C)` declaration.

The public API itself warns that it is incomplete/unstable and that breaking
changes are expected ([`vt.h#L4-L12`](https://github.com/ghostty-org/ghostty/blob/ae52f97dcac558735cfa916ea3965f247e5c6e9/include/ghostty/vt.h#L4-L12),
[`vt.h#L17-L27`](https://github.com/ghostty-org/ghostty/blob/ae52f97dcac558735cfa916ea3965f247e5c6e9/include/ghostty/vt.h#L17-L27);
see also [`src/lib_vt.zig#L1-L9`](https://github.com/ghostty-org/ghostty/blob/ae52f97dcac558735cfa916ea3965f247e5c6e9/src/lib_vt.zig#L1-L9)).
Consequently, the Git pin—not a stable C-API compatibility promise—is the
practical version lock for this demo.

The C API can report build version fields (`VERSION_STRING`, major/minor/patch,
and build metadata) via `ghostty_build_info`
([`build_info.h#L46-L142`](https://github.com/ghostty-org/ghostty/blob/ae52f97dcac558735cfa916ea3965f247e5c6e9/include/ghostty/vt/build_info.h#L46-L142)),
but Ghostling's `log_build_info` only queries SIMD and optimization mode
([`main.c#L1020-L1045`](https://github.com/ghostty-org/ghostling/blob/f9034e43a50a2f3a8101e35497f486090c1ddd6e/main.c#L1020-L1045)).
Its XTVERSION effect reports the application string `ghostling`, not the
libghostty-vt version ([`main.c#L1153-L1159`](https://github.com/ghostty-org/ghostling/blob/f9034e43a50a2f3a8101e35497f486090c1ddd6e/main.c#L1153-L1159)).

## Bottom line for an embedder

Ghostling demonstrates the thinnest supported C integration: build Ghostty's
C-ABI shared library in-tree, feed PTY bytes to one opaque terminal handle, use
library-owned key/mouse encoders for input, register synchronous effects for
PTY/UI side effects, and consume a render-state snapshot from a custom renderer.
The app supplies PTY/process management, windowing, drawing, image decoding, and
thread/lifecycle serialization.  It should be treated as a pinned-source demo,
not as evidence that the unstable libghostty-vt C API can be upgraded by soname
alone.
