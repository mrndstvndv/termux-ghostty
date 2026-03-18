# Ghostty Mouse Protocol Implementation Spec for Termux

Status: implementation spec
Audience: coding agent implementing the feature in this repo
Goal: implement correct terminal mouse reporting for the Ghostty backend only, using Ghostty’s upstream mouse encoder. No Java-side protocol reimplementation. No kitty graphics work in this spec.

---

## 1. Scope

This spec covers **mouse protocol support only**.

Target:
- **Ghostty backend only**

Out of scope:
- kitty graphics protocol
- any Ghostty image rendering
- Java emulator mouse protocol upgrades
- touch UX redesign

The Java emulator remains unchanged fallback behavior.

---

## 2. Verified facts

### 2.1 Upstream Ghostty mouse encoding API is already available here
This repo pins Ghostty dependency commit:
- `0f2eaed68cd2feb5a48e733fe7b39a73d341e5f2`

File:
- `terminal-emulator/src/main/zig/build.zig.zon`

That commit is Ghostty PR:
- `#11553` — `libghostty: add mouse encoding Zig + C API`

Local Ghostty source check:
- local clone HEAD is exactly `0f2eaed68cd2feb5a48e733fe7b39a73d341e5f2`

Implication:
- the upstream Ghostty mouse encoder API already exists in the dependency this repo builds against
- use it

### 2.2 Current Ghostty mouse path in this repo is incomplete
Current file:
- `terminal-emulator/src/main/java/com/termux/terminal/TerminalSession.java`

Current behavior in `sendMouseEvent(...)` for Ghostty backend:
- manual SGR formatting if `MODE_MOUSE_PROTOCOL_SGR`
- otherwise manual X10-style bytes

This is insufficient because Ghostty supports more than that:
- tracking modes: `x10`, `normal`, `button`, `any`
- formats: `x10`, `utf8`, `sgr`, `urxvt`, `sgr_pixels`
- motion filtering
- motion dedup
- out-of-viewport motion rules
- modifiers
- horizontal wheel
- extra buttons

### 2.3 Ghostty worker-thread ownership must be preserved
File:
- `terminal-emulator/src/main/java/com/termux/terminal/GhosttySessionWorker.java`

The worker is documented as sole owner of native Ghostty state.

Implication:
- UI thread must not directly call native Ghostty mouse encoding against live terminal state
- mouse events must be forwarded to the worker, then encoded there

### 2.4 Current Android touch behavior already synthesizes limited terminal mouse input
File:
- `terminal-view/src/main/java/com/termux/view/TerminalView.java`

Current behavior when mouse tracking is active:
- touch tap synthesizes left press + left release
- touch drag/scroll synthesizes wheel up/down events
- touch does not synthesize full mouse motion drag behavior

This compatibility behavior must be preserved.

### 2.5 Current Android hardware mouse behavior is partially intercepted by UI shortcuts
File:
- `terminal-view/src/main/java/com/termux/view/TerminalView.java`

Current behavior from `SOURCE_MOUSE`:
- right click can open context menu
- middle click can paste clipboard

This is wrong while terminal mouse tracking is active.

---

## 3. Required upstream source of truth

Implementation must follow Ghostty upstream behavior from:
- `~/.pi/gh/ghostty/src/input/mouse_encode.zig`
- `~/.pi/gh/ghostty/src/input/mouse.zig`
- `~/.pi/gh/ghostty/src/Surface.zig`
- `~/.pi/gh/ghostty/src/lib_vt.zig`

Primary requirement:
- use Ghostty’s real encoder
- do not translate the protocol rules into a separate Java implementation

Preferred native call path:
- Zig API via `ghostty.input.encodeMouse`

Do not use the C API unless there is a concrete Zig-integration blocker.

---

## 4. Final behavior

## 4.1 Supported on Ghostty backend
When Ghostty backend is active, Termux must support terminal mouse reporting via Ghostty for:
- X10
- UTF-8
- SGR
- URXVT
- SGR-Pixels

And tracking modes:
- none
- x10
- normal
- button
- any

This support must come from Ghostty’s native encoder behavior.

## 4.2 Supported input sources
### Hardware mouse / trackpad as mouse source
Examples:
- Bluetooth mouse
- USB OTG mouse
- Android pointer device reporting `SOURCE_MOUSE`

Must support:
- left
- right
- middle
- back
- forward
- vertical wheel
- horizontal wheel if Android reports it
- motion
- modifiers: shift, alt, ctrl

### Touch input
Touch remains compatibility-only behavior.
Preserve current semantics exactly:
- tap -> synthesize left press + left release
- drag/scroll while tracking -> synthesize wheel events
- no synthetic right/middle/back/forward
- no synthetic full mouse-motion drag stream

## 4.3 Behavior while mouse tracking is inactive
When terminal mouse tracking is not active:
- preserve current Termux behavior
- context menu behavior stays
- middle-click paste stays
- normal viewport scrolling stays

## 4.4 Java emulator backend
Do not change Java emulator mouse behavior in this work.

---

## 5. Architecture

Required event path:

1. `TerminalView` captures Android input
2. `TerminalView` converts it to a raw normalized mouse DTO
3. `TerminalSession` forwards that DTO to `GhosttySessionWorker`
4. `GhosttySessionWorker` invokes native mouse queue/encode API
5. native Zig code maps DTO -> Ghostty mouse event/options
6. native Zig code calls Ghostty mouse encoder
7. encoded bytes are appended to native pending output
8. worker drains pending output and writes to PTY using existing output path

Do not write mouse bytes directly from UI-thread Java code when Ghostty backend is active.

---

## 6. Data model

Create:
- `terminal-emulator/src/main/java/com/termux/terminal/GhosttyMouseEvent.java`

This DTO is raw input transport only.
It is not a protocol encoder state object.

## 6.1 Fields
Required fields:
- `int action`
- `int button`
- `int modifiers`
- `float surfaceX`
- `float surfaceY`
- `int screenWidthPx`
- `int screenHeightPx`
- `int cellWidthPx`
- `int cellHeightPx`
- `int paddingTopPx`
- `int paddingRightPx`
- `int paddingBottomPx`
- `int paddingLeftPx`

## 6.2 Action values
```text
PRESS = 0
RELEASE = 1
MOTION = 2
```

## 6.3 Button values
```text
NONE = 0
LEFT = 1
MIDDLE = 2
RIGHT = 3
WHEEL_UP = 4
WHEEL_DOWN = 5
WHEEL_LEFT = 6
WHEEL_RIGHT = 7
BACK = 8
FORWARD = 9
```

These are Java transport constants only.
Native code must map them to Ghostty button enums.

## 6.4 Modifier bits
```text
SHIFT = 1 << 0
ALT   = 1 << 1
CTRL  = 1 << 2
```

---

## 7. TerminalView requirements

Files:
- `terminal-view/src/main/java/com/termux/view/TerminalView.java`

## 7.1 Geometry source
Use renderer/view geometry already used by Termux rendering.

Required values:
- `screenWidthPx = getWidth()`
- `screenHeightPx = getHeight()`
- `cellWidthPx = round(mRenderer.getFontWidth())`
- `cellHeightPx = mRenderer.getFontLineSpacing()`
- `paddingTopPx = mRenderer.mFontLineSpacingAndAscent`
- `paddingLeftPx = 0`
- `paddingRightPx = 0`
- `paddingBottomPx = 0`

Do not invent extra padding semantics.

## 7.2 Modifier mapping
Use Android meta state:
- `META_SHIFT_ON` -> SHIFT
- `META_ALT_ON` -> ALT
- `META_CTRL_ON` -> CTRL

## 7.3 Hardware mouse button mapping
Use Android button state mapping:
- `BUTTON_PRIMARY` -> LEFT
- `BUTTON_SECONDARY` -> RIGHT
- `BUTTON_TERTIARY` -> MIDDLE
- `BUTTON_BACK` -> BACK
- `BUTTON_FORWARD` -> FORWARD

## 7.4 Wheel mapping
Use Android axis mapping:
- positive `AXIS_VSCROLL` -> WHEEL_UP
- negative `AXIS_VSCROLL` -> WHEEL_DOWN
- positive `AXIS_HSCROLL` -> WHEEL_RIGHT
- negative `AXIS_HSCROLL` -> WHEEL_LEFT

If horizontal wheel is not reported by device/Android, no synthetic fallback.

## 7.5 Motion mapping
For `SOURCE_MOUSE` move events:
- action = MOTION
- button = currently pressed supported button if deterministically known
- else button = NONE

Required precedence if multiple supported buttons are pressed:
1. LEFT
2. RIGHT
3. MIDDLE
4. BACK
5. FORWARD
6. NONE

Reason:
- terminal protocols encode at most one button identity for an event
- deterministic collapse is required

## 7.6 Behavior changes while Ghostty mouse tracking is active
When all are true:
- Ghostty backend active
- terminal mouse tracking active
- event source is `SOURCE_MOUSE`

Then:
- do not open context menu on right click
- do not paste clipboard on middle click
- forward those buttons to Ghostty instead

When terminal mouse tracking is inactive:
- preserve current context menu/paste behavior

## 7.7 Touch behavior preservation
When all are true:
- Ghostty backend active
- terminal mouse tracking active
- event source is not `SOURCE_MOUSE`

Preserve current behavior:
- tap synthesizes left press + left release
- drag/scroll synthesizes wheel up/down button events through the existing scroll path
- do not synthesize touch motion drag events

The only change is routing these synthesized events through the new Ghostty worker/native path.

---

## 8. TerminalSession requirements

File:
- `terminal-emulator/src/main/java/com/termux/terminal/TerminalSession.java`

## 8.1 Remove manual Ghostty mouse formatting
Current Ghostty path in `sendMouseEvent(...)` manually formats bytes.
That must stop.

When Ghostty backend is active:
- `TerminalSession` must forward normalized raw mouse events to `GhosttySessionWorker`
- `TerminalSession` must not directly format X10 or SGR bytes

When Ghostty backend is not active:
- keep existing behavior unchanged

## 8.2 New API
Add a richer Ghostty-only path, e.g.:
- `sendGhosttyMouseEvent(GhosttyMouseEvent event)`

The existing `sendMouseEvent(int mouseButton, int column, int row, boolean pressed)` may remain only as compatibility for non-Ghostty behavior and existing callers/tests.
Do not use it as the primary Ghostty backend path.

---

## 9. GhosttySessionWorker requirements

File:
- `terminal-emulator/src/main/java/com/termux/terminal/GhosttySessionWorker.java`

## 9.1 New message type
Add:
- `MSG_MOUSE_EVENT`

## 9.2 New worker API
Add:
- `void sendMouseEvent(GhosttyMouseEvent event)`

## 9.3 Handling rules
Worker handling for mouse event must:
1. call native queue/encode API
2. if native appended output bytes, drain pending output immediately
3. write drained bytes to PTY via existing session write path
4. not mark screen dirty just because a mouse event was emitted

Reason:
- mouse reporting emits input to the app
- it does not itself mutate terminal display state

## 9.4 Ownership rule
All native Ghostty mouse encoding calls must occur on the worker thread.
No exceptions.

---

## 10. Native Zig/JNI requirements

Files:
- `terminal-emulator/src/main/zig/src/termux_ghostty.zig`
- `terminal-emulator/src/main/zig/src/jni_exports.zig`
- `terminal-emulator/src/main/java/com/termux/terminal/GhosttyNative.java`
- `terminal-emulator/src/main/java/com/termux/terminal/GhosttyTerminalContent.java`
- `terminal-emulator/src/main/zig/include/termux_ghostty.h`

## 10.1 New native API
Add a JNI/native entry point, conceptually:
- `nativeQueueMouseEvent(...)`

Recommended Java signature:
```java
static native int nativeQueueMouseEvent(
    long nativeHandle,
    int action,
    int button,
    int modifiers,
    float surfaceX,
    float surfaceY,
    int screenWidthPx,
    int screenHeightPx,
    int cellWidthPx,
    int cellHeightPx,
    int paddingTopPx,
    int paddingRightPx,
    int paddingBottomPx,
    int paddingLeftPx
);
```

Return:
- number of bytes appended to native pending output
- `0` if event emits nothing
- negative on hard error

## 10.2 Native session state
Add to native `Session` transient mouse state:
- pressed-button bitset for supported buttons
- last-reported cell x/y
- last-cell-valid flag

This state is required for:
- `any_button_pressed`
- motion dedup
- correct out-of-viewport motion behavior

## 10.3 Native mapping rules
Map Java DTO values to Ghostty types:
- action -> Ghostty mouse action
- button -> Ghostty mouse button or null-equivalent
- modifiers -> Ghostty key mods
- `surfaceX/surfaceY` -> Ghostty mouse position in surface-space pixels

## 10.4 Encoder options
Construct Ghostty encoder options from:
- `terminal.flags.mouse_event`
- `terminal.flags.mouse_format`
- provided geometry
- transient pressed-button state
- transient last-cell dedup state

Use Ghostty’s real encoder:
- preferred: `ghostty.input.encodeMouse`

Do not implement protocol encoding logic manually in Termux Zig.

## 10.5 Pressed-button state update rules
Must be explicit:
- on press: update pressed-button state before encode, except wheel buttons
- on release: clear released button before encode
- on motion: button state unchanged
- wheel buttons must not become persistent pressed state

## 10.6 Dedup state update rule
Last-cell dedup state must update only if the encode actually succeeds and emits output, matching Ghostty semantics.

## 10.7 Pending output rule
Encoded mouse bytes must be appended to existing `pending_output` and drained through the existing worker path.
Do not create a second output channel.

---

## 11. Pixel-size correctness

Files:
- `terminal-emulator/src/main/zig/src/termux_ghostty.zig`
- `terminal-emulator/src/main/java/com/termux/terminal/TerminalSession.java`
- `terminal-emulator/src/main/java/com/termux/terminal/GhosttyTerminalContent.java`
- `terminal-emulator/src/main/java/com/termux/terminal/GhosttyNative.java`

Current native create/resize only gets rows/columns.
That is insufficient for SGR pixel mode and proper coordinate mapping.

Required change:
- pass `cellWidthPixels` and `cellHeightPixels` into native create and resize
- update Ghostty terminal:
  - `width_px = cols * cellWidthPixels`
  - `height_px = rows * cellHeightPixels`

This is required for correctness.

---

## 12. Non-goals / forbidden shortcuts

Do not do any of the following:
- do not keep manual SGR/X10 byte formatting for Ghostty backend
- do not port Ghostty mouse encoding logic into Java
- do not call Ghostty mouse encoding from UI thread
- do not redesign touch into full desktop mouse semantics
- do not change Java emulator mouse behavior as part of this work
- do not leave right-click context menu interception active while Ghostty mouse tracking is on
- do not leave middle-click paste interception active while Ghostty mouse tracking is on
- do not synthesize unsupported touch buttons/motion behavior

---

## 13. Acceptance criteria

This work is done only when all are true on the Ghostty backend:
- bytes sent for mouse reporting come from Ghostty’s upstream encoder path
- `1000`, `1002`, `1003`, `1005`, `1006`, `1015`, `1016` behave according to Ghostty
- right click reaches terminal apps while tracking is active
- middle click reaches terminal apps while tracking is active
- wheel up/down works
- horizontal wheel works when Android reports it
- touch tap still acts like left click while tracking is active
- touch drag/scroll still acts like wheel while tracking is active
- no display regressions occur
- Java emulator behavior remains unchanged

---

## 14. Required tests

## 14.1 Java/UI tests
Add tests for:
- `TerminalView` mapping Android hardware mouse events to `GhosttyMouseEvent`
- `TerminalView` preserving touch synthesis behavior
- context menu disabled during Ghostty mouse tracking
- middle-click paste disabled during Ghostty mouse tracking
- wheel and horizontal wheel mapping

Recommended files:
- `terminal-view/.../TerminalViewGhosttyMouseTest.java`
- `terminal-emulator/.../GhosttyMouseEventTest.java`

## 14.2 Zig/native tests
Add unit tests for:
- Java DTO -> Ghostty event mapping
- pressed-button state transitions
- wheel non-persistence
- motion dedup state updates
- native wrapper around `ghostty.input.encodeMouse`

## 14.3 Manual verification
Must manually verify on Android with Ghostty backend:
1. hardware mouse left click in `vim`/`nvim`
2. hardware mouse right click in mouse-reporting TUI
3. hardware mouse middle click in mouse-reporting TUI
4. wheel scroll in mouse-reporting TUI
5. horizontal wheel if device supports it
6. touch tap while mouse tracking active
7. touch drag/scroll while mouse tracking active

---

## 15. Implementation order

Implement in this order.

### Phase 1
1. add pixel-size propagation into native create/resize
2. add `GhosttyMouseEvent` DTO
3. add worker queue/message path
4. add native queue/encode JNI path
5. remove manual Ghostty-side Java formatting

### Phase 2
1. update `TerminalView` hardware mouse handling
2. preserve touch synthesis through the new path
3. land tests

### Phase 3
1. manual Android verification with real hardware mouse
2. manual Android verification with touch input

---

## 16. Short summary for the implementing agent

Use Ghostty PR `#11553` functionality already pinned in this repo.

Do this:
- capture raw Android mouse/touch-derived events in `TerminalView`
- send them to `GhosttySessionWorker`
- let native Ghostty encode them
- drain native pending output to PTY

Do not do this:
- do not manually format SGR/X10 bytes in Java for Ghostty backend
- do not touch kitty graphics
- do not change Java emulator behavior
