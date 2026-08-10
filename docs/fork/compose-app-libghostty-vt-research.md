# Compose app’s `libghostty-vt` consumption: primary-source findings

## Scope and revision

This report follows the actual integration path at repository revision
`3c0fa173f2b378d0475856d740394058246da056`. `compose-app` does not call
`libghostty-vt` directly: it depends on `terminal-emulator`, and that module owns
the Zig/JNI integration. The Ghostty source dependency is pinned separately at
`terminal-emulator/src/main/zig/build.zig.zon`.

## Executive answer

* **Build/link:** Gradle invokes a custom `zig build` for four Android ABIs and
two variants. The Zig module imports Ghostty’s `ghostty-vt` module directly and
links those internals into one packaged `libtermux-ghostty.so`; the app does not
package a separate `libghostty-vt.so`.
* **Boundary:** Java calls a Termux-specific JNI surface. JNI exports turn Java
arrays/strings/direct buffers into calls to a custom native session ABI. The
native session is an opaque pointer represented as a `long`, but its internals
are implemented with Ghostty’s **Zig** types, not the public C headers.
* **Ghostty API use:** `termux_ghostty.zig` constructs `ghostty.Terminal`,
`ghostty.Stream(*Handler)`, and `ghostty.RenderState`. Its `Handler` manually
maps Ghostty stream actions and reaches into terminal screens, modes, pages,
colors, selection, and input types. This gives the app full side-effect and
feature control, but couples it to Ghostty source-level internals.
* **Threading:** `GhosttySessionWorker` is the sole owner of native terminal
state. PTY/SSH bytes are queued, parsed in bounded worker slices, and converted
to double-buffered snapshots/frame deltas consumed by the UI thread.
* **Stability:** This is the safer Android runtime architecture, while
Ghostling’s public C boundary is the safer source/API boundary. Neither API is
promised stable by Ghostty; both integrations must pin and validate a commit.

## 1. Build integration

### Compose app dependency path

`compose-app/build.gradle#L138-L140` depends on `terminal-compose-view`,
`terminal-view`, and `terminal-emulator`. There is no Ghostty import or native
build in `compose-app` itself. `terminal-emulator` is therefore the correct
comparison target for Ghostling.

### Gradle-to-Zig build

`terminal-emulator/build.gradle#L75-L93` defines four Android targets and two
Ghostty variants. The `Exec` tasks at `#L139-L172` run, per ABI:

```text
zig build -Dtarget=<android-target>
          -Doptimize=<Debug|ReleaseSmall>
          -Dghostty-log=<true|false>
          --prefix <variant install directory>
```

The resulting `libtermux-ghostty.so` is copied into generated JNI directories
and exposed through the debug/release `jniLibs` source sets
(`#L44-L50`). The normal Android `Android.mk` still builds the separate legacy
`libtermux` module; it is not the Ghostty library.

`terminal-emulator/src/main/zig/build.zig#L12-L28` creates the JNI root module
and lazily imports Ghostty’s `ghostty-vt` module. It passes the Android target,
optimization mode, and `.simd = false` to Ghostty. The dynamic library target
at `#L70-L90` is named `termux-ghostty`, links libc, adds Android `log` and NDK
paths, installs `termux_ghostty.h`, and emits one JNI library per ABI.

The dependency is pinned in `build.zig.zon#L4-L10` to Ghostty commit
`73534c4680a809398b396c94ac7f12fcccb7963d`, with minimum Zig `0.15.2`. This is
a different revision from Ghostling’s `ae52f97dcac558735cfa916ea3965f247e5c6e9e`,
so API comparisons must account for source-version differences.

### Packaging consequence

Because the Ghostty module is compiled into `libtermux-ghostty.so`, Java loads
one library with `System.loadLibrary("termux-ghostty")`
(`GhosttyNative.java#L34-L47`). There is no separate dynamic-library search
path, SONAME, or second Ghostty runtime to package. This is convenient on
Android, although it makes the custom Zig build part of every ABI build.

## 2. JNI/FFI/API boundary

### Java surface and native handles

`GhosttyNative.java#L56-L146` declares lifecycle, append, resize, mouse, output,
snapshot, transcript, and side-effect methods. `nativeCreate` returns a `long`
handle; `GhosttyTerminalContent.java#L21-L46` owns that handle and destroys it
exactly once on close. `jni_exports.zig#L22-L52` maps Java method names to native
session creation/destruction, and the later exports marshal primitive arrays,
critical byte-array pointers, strings, and direct `ByteBuffer` addresses.

The installed `termux_ghostty.h` is a small Termux-specific C ABI
(`terminal-emulator/src/main/zig/include/termux_ghostty.h#L12-L60`). It exposes
opaque `termux_ghostty_session*` handles and result/mode flags, but the Java
path currently calls the exported JNI functions directly rather than compiling
against the header.

### Direct Ghostty Zig coupling

`termux_ghostty.zig#L1-L4` imports `ghostty-vt` as a Zig module. `Session`
(`#L148-L205`) stores:

* `ghostty.Terminal`;
* `ghostty.Stream(*Handler)`;
* `ghostty.RenderState`;
* Ghostty-derived pending effect buffers; and
* custom snapshot/link scratch buffers and performance state.

Creation at `#L1448-L1555` initializes the terminal and stream directly,
including Ghostty screen keyboard state and pixel dimensions. The `Handler` at
`#L1014-L1144` manually consumes the stream’s action union: it updates terminal
state, records bell/title/clipboard/notification/progress effects, generates
query replies, changes modes and colors, and handles Kitty keyboard state.
The rest of the file directly accesses Ghostty internals for pages, rows,
selection, hyperlinks, colors, cursor state, and input encoding.

This is materially different from Ghostling’s C integration. Ghostling sees
opaque handles and public structs/enums/callbacks from `<ghostty/vt.h>`; this
project sees compile-time Zig types and internal fields. The custom surface is
narrower for Java callers, but the implementation surface is wider and more
sensitive to Ghostty source changes.

### Snapshot transport

The native layer does not expose a per-cell JNI API. It updates a Ghostty render
state and serializes a Termux-specific binary snapshot in
`termux_ghostty.zig#L1845-L1968`. `GhosttyTerminalContent.java#L392-L430` fills
a direct `ByteBuffer`, validates required capacity, and parses/marks the
snapshot. Viewport links use a parallel buffer (`#L432-L469`). This avoids JNI
calls per cell and keeps the UI model independent from native memory, but adds a
second serialization contract that the app must maintain.

## 3. Threading, scheduling, and lifecycle

`TerminalSession.java#L23-L35` documents the ownership rule: terminal mutation
happens on the Ghostty worker and UI callbacks return to the main thread.
`GhosttySessionWorker.java#L15-L47` makes the worker responsible for append,
resize/reset, snapshots, PTY replies, and side effects.

The input reader writes to a bounded `ByteQueue`
(`TerminalSession.java#L56-L64`, `#L318-L335`) and wakes the worker. The worker
processes at most 64 KiB or 8 ms per append slice
(`GhosttySessionWorker.java#L41-L45`, `#L254-L283`), coalesces snapshot requests,
and schedules 16 ms/33 ms frame builds (`#L485-L502`). Native state mutation and
snapshot building therefore stay off the UI thread.

`buildAndPublishSnapshot()` (`GhosttySessionWorker.java#L504-L552`) uses two
staging snapshots and two viewport-link buffers, publishes them through atomic
references, and posts only a UI wakeup. `TerminalView` applies the latest frame
delta and requests a full refresh when a sequence cannot be applied
(`terminal-view/src/main/java/com/termux/view/TerminalView.java#L650-L684`).

Cleanup closes the queues, sends a front-of-queue shutdown, joins the worker,
and only then destroys the native handle
(`TerminalSession.java#L617-L649`; `GhosttySessionWorker.java#L198-L211`). This
ordering is substantially stronger than a single-thread demo when Android
views, PTY readers, SSH callbacks, and session teardown overlap.

## 4. Comparison with Ghostling

| Dimension | This project | Ghostling |
|---|---|---|
| Public boundary | Termux JNI + custom ABI; internals are Zig types | C11 + public opaque C API |
| Ghostty artifact | One bundled `libtermux-ghostty.so` per ABI | CMake-imported shared `ghostty-vt` target |
| Terminal execution | Worker-owned, bounded/coalesced Android pipeline | One synchronous Raylib/UI loop |
| Side effects | Custom action handler: replies, title, clipboard, notifications, progress, bell, colors; APC/DCS actions are currently ignored | Public effect callbacks; minimal demo registers PTY/reports/title/device/color behavior |
| Rendering | Ghostty render state -> custom binary snapshots -> Java Canvas | C render-state handles -> direct Raylib drawing |
| Lifecycle | Explicit Java/native/session teardown and queue ownership | Process-local handles and reverse-order cleanup |
| Upgrade exposure | High source coupling, but Android packaging is self-contained | Lower source coupling, but public API is explicitly unstable and shared-library packaging is separate |

Ghostling’s `ghostty_terminal_vt_write()` is not inherently side-effect-complete
without configured effects: the public header says default processing ignores
queries and other side-effect sequences. Its callbacks are synchronous and must
not re-enter or block the write call. The C API can cover many common effects,
but the examined Ghostling integration does not demonstrate this project’s
progress/desktop-notification feature set. A C migration would require an
explicit feature-parity audit rather than a mechanical replacement.

## 5. Stability assessment and recommendation

### What Ghostling does better

1. **Smaller upgrade surface.** Opaque C handles, public callbacks, and
   `GHOSTTY_INIT_SIZED` reduce direct dependence on Ghostty’s internal structs.
2. **Simpler review/debugging.** The PTY -> `vt_write` -> effects -> render
   sequence is visible in one C file.
3. **Less custom FFI.** No JNI handle marshalling or app-defined binary frame
   protocol is needed for a desktop renderer.

### What this project does better

1. **Android runtime safety.** One owner thread, bounded work slices, coalescing,
   double-buffer publication, and teardown ordering address ANR/lifecycle risks
   that Ghostling intentionally leaves to its single-thread loop.
2. **Feature coverage.** The custom stream handler preserves Termux-specific
   replies and side effects beyond the minimal Ghostling example.
3. **Android packaging.** Bundling into one ABI-specific JNI library avoids a
   second shared-library dependency and loader-path problem.
4. **UI decoupling.** The snapshot/frame contract prevents native terminal state
   or borrowed render handles from crossing onto the UI thread.

### Recommendation

Do **not** replace the current architecture wholesale with Ghostling’s loop or
shared-library arrangement. For Android, the worker/lifecycle design is the
more stable runtime foundation.

Adopt Ghostling’s ideas selectively:

1. Keep `GhosttySessionWorker`, the Termux JNI surface, one native owner, and the
   existing snapshot publication contract.
2. Treat `termux_ghostty.zig` as the only Ghostty-internals adapter and keep the
   Java ABI independent of Ghostty types.
3. During a future pinned upgrade, prototype a public-C-API adapter for the
   terminal/effects/render pieces **only after** checking feature parity
   (especially progress, desktop notifications, clipboard, replies, scrollback,
   and Kitty behavior).
4. If the public C API covers the required behavior, prefer its opaque-handle
   boundary but statically link it into the existing Android JNI library rather
   than introducing a separately loaded `libghostty-vt.so`.
5. Keep commit/toolchain pins and add protocol/ownership regression coverage;
   neither Ghostling nor Ghostty’s own header promises a stable API.

**Verdict:** Ghostling is a better *API-boundary reference* and may reduce
Ghostty-upgrade churn, but it is not a more stable migration target as a whole.
The strongest path is a hybrid: Ghostling’s public-boundary discipline inside
this project’s worker-thread and Android packaging architecture.
