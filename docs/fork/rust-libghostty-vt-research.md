# Rust options for `libghostty-vt`: primary-source research

## Scope

This report checks crates.io and the corresponding source repositories for Rust
consumers of Ghostty's public `libghostty-vt` C API. The most relevant source
checkout is `Uzaaft/libghostty-rs` at commit
`72ac98f292879bf9f788fcbb11238c562a1eebe6`, whose crates.io release is `0.2.1`.

There is no official Ghostty-org Rust binding in the Ghostty repository. The
Rust crates below are community projects built on the public C API or, in some
cases, independent Rust ports.

## Best direct match: `libghostty-vt` and `libghostty-vt-sys`

### `libghostty-vt-sys` 0.2.1

Source: `https://github.com/Uzaaft/libghostty-rs/tree/72ac98f292879bf9f788fcbb11238c562a1eebe6/crates/libghostty-vt-sys`

* `libghostty-vt-sys` contains generated bindgen bindings to
  `include/ghostty/vt.h` (`crates/libghostty-vt-sys/Cargo.toml` and
  `src/bindings.rs`).
* Its build script pins Ghostty to
  `ab0b9da9e88fcb4b0533a1854e84628f663930af` and runs Zig with
  `-Demit-lib-vt=true` (`crates/libghostty-vt-sys/build.rs`).
* It links the static `libghostty-vt.a` by default; `link-dynamic` is optional.
  It supports source overrides (`GHOSTTY_SOURCE_DIR`) and optional pkg-config.
* The checked-in target mapping explicitly handles `aarch64-linux-android` and
  `x86_64-linux-android`, but not `arm-linux-androideabi` or
  `x86-linux-android`. This is a direct issue for this project, which currently
  builds four ABIs.

### `libghostty-vt` 0.2.1

This is the safe wrapper over `libghostty-vt-sys`. Its public API includes:

* `Terminal` with resize/reset/scrollback and VT writes;
* synchronous effect callbacks for PTY replies, bell, title, PWD, size,
  device attributes, color scheme, and clipboard;
* `RenderState`, row/cell iterators, selection, key/mouse encoders, paste/focus
  encoding, build information, and optional Kitty graphics.

The wrapper puts unsafe pointer/lifetime work behind Rust types and ownership
rules, but it is not an independent terminal implementation. Its crate-level
documentation explicitly says:

* the API is still unstable and breaking changes are expected; and
* all libghostty-vt types are `!Send` and `!Sync` because the underlying C API
  does not guarantee thread safety.

That second point is actually compatible with the current architecture: the
crate recommends creating the terminal on a dedicated emulation thread and
communicating with it through channels. It must not be shared concurrently
between Java/UI threads.

The crate itself still contains an unsafe FFI implementation internally. Rust
reduces wrapper-side memory errors; it does not make the Ghostty C/Zig library,
JNI boundary, or callback contracts memory-safe automatically.

### Rust Ghostling port

`libghostty-rs/example/ghostling_rs` is a real Rust port of Ghostling using
`libghostty-vt`, Macroquad, and a PTY. It demonstrates the same public C API
features as the C demo, including effects, render snapshots, input encoders,
selection, and Kitty graphics. It remains a desktop, single-threaded example;
it is not an Android/JNI integration.

## Other crates found

* **`ratatui-ghostty` 0.2.0:** a Ratatui desktop/TUI adapter depending on
  `libghostty-vt` 0.1.1. Useful for API examples, not an Android bridge.
* **`vtcode-ghostty-vt-sys` 0.123.4:** a small runtime dynamic-loader wrapper
  that documents Linux/macOS support. It exposes a simple text snapshot API and
  is not a general Android binding.
* **`vtcode-ghostty-core` 0.128.4:** a pure-Rust VT emulator *inspired by*
  Ghostty. It is not the Ghostty implementation and should not be assumed to
  have Ghostty protocol/scrollback/Kitty parity.
* **`ghosttea-vt` / `ghosttea-vt-sys` 0.9.2:** a custom product wrapper around
  locked Ghostty artifacts. Its published release targets are Apple Silicon
  macOS and Windows, not Android.

## Suitability for this project

### What Rust would improve

* Ownership and destruction of native session objects can be represented by
  `Drop`, preventing many wrapper-side leaks and double frees.
* Snapshot buffers, pending effect queues, selection state, and protocol
  marshalling can use bounds-checked collections and explicit lifetimes.
* A small Rust JNI layer can keep unsafe code concentrated in a few audited
  functions while exposing a safe internal session API.
* Rust's type system can preserve the single-owner rule, provided the
  `!Send`/`!Sync` terminal remains on one worker thread.

### What Rust would not automatically fix

* Bugs or leaks inside Ghostty's Zig/C implementation.
* Invalid JNI pointers, Java lifetime mistakes, direct-buffer misuse, or
  callbacks that violate synchronous/reentrancy rules.
* Missing features in the public C API. The current safe crate exposes the
  public callbacks, but not the custom action-level progress/notification
  behavior implemented by this project's direct Zig handler.
* Android build/toolchain complexity. The crate requires a recent Rust toolchain
  and Zig 0.16.x, while this repository currently provisions Zig 0.15.2. Its
  build script also needs extension or an ABI policy for 32-bit Android.

## Recommended migration shape

Do not rewrite the whole app or renderer first. The lowest-risk Rust trial is:

1. Keep the existing Java `GhosttySessionWorker`, Java snapshot/frame protocol,
   and `GhosttyNative` method names.
2. Replace the implementation behind those JNI methods with a Rust `Session`
   that owns `libghostty_vt::Terminal` and render/input objects.
3. Keep all calls serialized on the existing worker thread. Do not put the
   `Terminal` in a global mutex or pass it between threads.
4. Initially preserve the existing custom snapshot format and implement only
   the feature subset needed for shell output, resize, replies, title, bell,
   clipboard, selection, and input.
5. Add progress/desktop notifications and Kitty behavior only after verifying
   whether the public C API exposes the required hooks; otherwise retain a
   narrowly scoped custom shim.
6. Decide explicitly whether to drop 32-bit ABIs or extend/test the crate's
   Android target support before changing the Gradle packaging matrix.
7. Pin both the Rust crate revision and Ghostty source revision, or vendor/fork
   the crate for reproducible production builds.

**Verdict:** `libghostty-vt` 0.2.1 is a credible Rust starting point and is
much more promising than writing raw Rust bindings from scratch. It could
reduce memory-management bugs in this project's adapter, but it is not a
ready-made Android solution. A Rust JNI/core migration is worthwhile as an
incremental experiment, while a full rewrite should wait until ABI support and
feature parity are proven.
