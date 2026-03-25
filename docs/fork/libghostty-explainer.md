# Understanding libghostty, PTYs, and Termux

## What is a PTY?

A **PTY (Pseudo Terminal)** is a virtual terminal that lets programs talk to each other like they're on a real terminal, but in software.

```
┌─────────────┐     ┌─────────────┐     ┌─────────────┐
│   Terminal   │◄───►│    PTY      │◄───►│   Shell     │
│   Emulator   │     │ (virtual    │     │  (bash/zsh) │
│  (your app)  │     │  terminal)  │     │             │
└─────────────┘     └─────────────┘     └─────────────┘
```

### How PTY Works

The PTY has two ends:

| End | Also Called | Who Uses It |
|-----|-------------|-------------|
| **Master** | PTY master | Terminal emulator (termux/ghostling) |
| **Slave** | PTY slave, `/dev/pts/X` | Shell and programs running inside |

### PTY in Practice

```c
// 1. Create a PTY pair
int master_fd = posix_openpt(O_RDWR | O_NOCTTY);
grantpt(master_fd);
unlockpt(master_fd);
char *slave_name = ptsname(master_fd);  // e.g., "/dev/pts/3"

// 2. Fork a child process
pid_t pid = fork();
if (pid == 0) {
    // Child: become the shell
    int slave_fd = open(slave_name, O_RDWR);
    // Redirect stdin/stdout/stderr to slave
    dup2(slave_fd, STDIN_FILENO);
    dup2(slave_fd, STDOUT_FILENO);
    dup2(slave_fd, STDERR_FILENO);
    execlp("/bin/bash", "bash", NULL);
}

// 3. Parent (terminal emulator) uses master_fd
// Read what the shell outputs:
char buf[4096];
int n = read(master_fd, buf, sizeof(buf));

// Write keyboard input to the shell:
write(master_fd, "ls\n", 3);

// Resize the terminal:
struct winsize ws = { .ws_row = 24, .ws_col = 80 };
ioctl(master_fd, TIOCSWINSZ, &ws);
```

### The Problem: Raw Bytes Are Ugly

When you read from a PTY, you get raw bytes like:

```
Hello World\e[31mRed Text\e[0m\e[2J\e[HCursor to top
```

This includes escape sequences for:
- Colors (`\e[31m`)
- Cursor movement (`\e[2J` clears screen)
- And hundreds of other terminal control codes

You need something to **parse this and maintain terminal state**.

---

## What is libghostty?

**libghostty** is the terminal emulation library extracted from [Ghostty](https://ghostty.org), a GPU-accelerated terminal emulator.

### What libghostty Does

```
Raw bytes from PTY
       │
       ▼
┌─────────────────────────────────────────────┐
│                 libghostty                   │
│                                             │
│  1. Parse VT escape sequences               │
│  2. Maintain terminal state                 │
│     - Screen contents (cells with text)     │
│     - Cursor position and style             │
│     - Scrollback buffer                     │
│     - Colors and text attributes            │
│     - Modes (mouse tracking, etc.)          │
│  3. Build a renderable snapshot             │
│  4. Handle VT query responses               │
└─────────────────────────────────────────────┘
       │
       ▼
Render to screen
```

### What libghostty Does NOT Do

- ❌ Does not create/manage PTYs
- ❌ Does not spawn processes
- ❌ Does not render to screen (gives you data to render)
- ❌ Does not handle keyboard input encoding (provides encoder)
- ❌ Does not manage windows

### libghostty Components

| Component | Purpose |
|-----------|---------|
| **Terminal** | Holds terminal state (screen, cursor, modes, colors) |
| **Stream** | Parses VT sequences and dispatches actions |
| **RenderState** | Converts terminal state to renderable data |
| **KeyEncoder** | Encodes keyboard events to VT sequences |
| **Input** | Mouse/keyboard event types and encoding |

---

## libghostty Architecture

### Two APIs

libghostty provides two ways to use it:

#### 1. Zig API (used by termux-app)

```zig
// You create a Handler that responds to all VT actions
const Handler = struct {
    session: *Session,
    
    pub fn vt(self: *Handler, action: Tag, value: Value) void {
        switch (action) {
            .bell => self.session.bell_pending = true,
            .title => self.session.replaceTitle(value.title),
            .device_attributes => self.respondToDA(value),
            // ... handle 100+ different actions
        }
    }
};

// Create a Stream that parses and calls your Handler
var stream = ghostty.Stream(*Handler).init(allocator, &handler);

// Feed it terminal output
stream.nextSlice(pty_output_bytes);
// Your handler.vt() gets called for each VT sequence
```

**Pros:**
- Full control over everything
- One place to handle all VT actions
- Direct access to terminal state

**Cons:**
- Big switch statement
- Must handle everything (or explicitly ignore)

#### 2. C API (used by ghostling)

```c
// Create terminal
GhosttyTerminal terminal = ghostty_terminal_create(...);

// Register optional effect callbacks
ghostty_terminal_set(terminal, GHOSTTY_TERMINAL_OPT_WRITE_PTY, &my_write_pty);
ghostty_terminal_set(terminal, GHOSTTY_TERMINAL_OPT_SIZE, &my_size);

// Feed it terminal output (triggers callbacks)
ghostty_terminal_append(terminal, bytes, len);

// Get renderable data
ghostty_terminal_fill_snapshot(terminal, ...);
```

**Pros:**
- Simpler integration
- Only register callbacks you need
- Clean C API boundary

**Cons:**
- Less control over internal behavior
- Callback-based (some complexity)

### The Effects System (PR #11787)

The recent PR added "effects" - optional callbacks for side effects:

| Effect | When It Fires | What You Do |
|--------|---------------|-------------|
| `write_pty` | Program sends VT query response | Write bytes back to PTY |
| `bell` | Bell character received | Vibrate/play sound |
| `title_changed` | OSC 0/2 sets title | Update window title |
| `size` | CSI 14/16/18 t query | Return terminal dimensions |
| `device_attributes` | DA1/DA2/DA3 query | Return capabilities |
| `xtversion` | XTVERSION query | Return version string |
| `color_scheme` | CSI ? 996 n query | Return light/dark mode |

**Important:** Effects are for the **read-only terminal stream**. If you use the full Zig Stream, you handle all of this directly in your Handler.

---

## How Termux Uses libghostty

### Architecture

```
┌────────────────────────────────────────────────────────────────┐
│                         Termux App                             │
├────────────────────────────────────────────────────────────────┤
│                                                                │
│  ┌──────────────────┐     ┌─────────────────────────────────┐ │
│  │   TerminalSession │     │      GhosttySessionWorker       │ │
│  │   (Java/Kotlin)   │     │         (Worker Thread)         │ │
│  │                    │     │                                 │ │
│  │  - PTY management  │◄──►│  - Reads PTY output             │ │
│  │  - Process spawn   │     │  - Feeds to native lib          │ │
│  │  - Bell handling   │     │  - Builds snapshots             │ │
│  │  - Title handling  │     │  - Drains pending output        │ │
│  └──────────────────┘     └──────────────┬──────────────────┘ │
│                                           │                    │
│                          ┌────────────────▼────────────────┐  │
│                          │     libtermux-ghostty.so        │  │
│                          │          (Zig/JNI)              │  │
│                          │                                  │  │
│                          │  ┌────────────────────────────┐ │  │
│                          │  │     termux_ghostty.zig     │ │  │
│                          │  │                            │ │  │
│                          │  │  Session {                 │ │  │
│                          │  │    terminal: Terminal      │ │  │
│                          │  │    stream: Stream(Handler) │ │  │
│                          │  │    handler: Handler        │ │  │
│                          │  │    render_state: ...       │ │  │
│                          │  │  }                         │ │  │
│                          │  └────────────────────────────┘ │  │
│                          │              │                   │  │
│                          │              ▼                   │  │
│                          │  ┌────────────────────────────┐ │  │
│                          │  │   ghostty (libghostty)     │ │  │
│                          │  │                            │ │  │
│                          │  │  Terminal, Stream, etc.    │ │  │
│                          │  └────────────────────────────┘ │  │
│                          └─────────────────────────────────┘  │
└────────────────────────────────────────────────────────────────┘
```

### Termux Data Flow

#### Reading Terminal Output

```
Shell writes to PTY slave
       │
       ▼
PTY master (fd) has data available
       │
       ▼
TerminalSession.onPtyOutputAvailable()
       │
       ▼
GhosttySessionWorker.onOutputAvailable()
       │
       ▼
mContent.append(buffer, offset, length)  [JNI → Zig]
       │
       ▼
stream.nextSlice(bytes)  [libghostty Stream parses VT]
       │
       ▼
handler.vt(action, value)  [called for each VT sequence]
       │
       ├── .print → terminal.print(cp)
       ├── .bell → bell_pending = true
       ├── .title → pending_title = value
       ├── .cursor_pos → terminal.setCursorPos(row, col)
       └── ... hundreds more
       │
       ▼
Return append_result flags (bell, title_changed, etc.)
       │
       ▼
Process results → post to main thread
       │
       ├── Bell → vibrate/sound
       ├── Title → update session title
       └── Changes → request UI update
```

#### Writing Keyboard Input

```
User presses key on Android keyboard
       │
       ▼
TerminalView.onKeyDown()
       │
       ▼
GhosttyKeyEncoder.encode(keyEvent)  [JNI → Zig]
       │
       ▼
stream.pending_output.append(encoded_bytes)
       │
       ▼
drainPendingOutput() → buffer
       │
       ▼
TerminalSession.write(buffer) → PTY master
       │
       ▼
Shell receives keystroke
```

### Termux JNI Exports

The `termux-ghostty` library exposes these to Java:

```java
// Create/destroy session
long nativeCreate(int columns, int rows, int transcriptRows, int cellWidth, int cellHeight);
void nativeDestroy(long handle);

// Feed terminal output, returns result flags
int nativeAppend(long handle, byte[] data, int offset, int length);

// Get response bytes to send back to PTY
int nativeDrainPendingOutput(long handle, byte[] buffer, int offset, int length);

// Terminal state queries
int nativeGetColumns(long handle);
int nativeGetRows(long handle);
int nativeGetCursorRow(long handle);
int nativeGetCursorCol(long handle);
String nativeConsumeTitle(long handle);

// Snapshot for rendering
int nativeFillSnapshotCurrentViewport(long handle, ByteBuffer buffer, int capacity);
```

### Result Flags (What Changed After Append)

```java
static final int APPEND_RESULT_SCREEN_CHANGED        = 1;
static final int APPEND_RESULT_CURSOR_CHANGED        = 1 << 1;
static final int APPEND_RESULT_TITLE_CHANGED         = 1 << 2;
static final int APPEND_RESULT_BELL                  = 1 << 3;
static final int APPEND_RESULT_CLIPBOARD_COPY        = 1 << 4;
static final int APPEND_RESULT_COLORS_CHANGED        = 1 << 5;
static final int APPEND_RESULT_REPLY_BYTES_AVAILABLE = 1 << 6;
static final int APPEND_RESULT_DESKTOP_NOTIFICATION  = 1 << 7;
static final int APPEND_RESULT_PROGRESS              = 1 << 8;
```

---

## How Ghostling Uses libghostty

### Architecture

```
┌────────────────────────────────────────────────────────────────┐
│                         Ghostling                              │
│                     (C + raylib app)                           │
├────────────────────────────────────────────────────────────────┤
│                                                                │
│  ┌─────────────────────────────────────────────────────────┐  │
│  │                        main.c                           │  │
│  │                                                         │  │
│  │   // Create PTY                                         │  │
│  │   int pty_fd = posix_openpt(...);                       │  │
│  │   spawn_shell(pty_fd);                                  │  │
│  │                                                         │  │
│  │   // Create terminal (C API)                            │  │
│  │   GhosttyTerminal terminal = ghostty_terminal_create()  │  │
│  │                                                         │  │
│  │   // Register effects                                   │  │
│  │   ghostty_terminal_set(terminal, OPT_WRITE_PTY, ...)    │  │
│  │   ghostty_terminal_set(terminal, OPT_SIZE, ...)         │  │
│  │   ghostty_terminal_set(terminal, OPT_DEVICE_ATTR, ...)  │  │
│  │                                                         │  │
│  │   // Main loop                                          │  │
│  │   while (running) {                                     │  │
│  │       // Read PTY → feed to terminal                    │  │
│  │       int n = read(pty_fd, buf, sizeof(buf));           │  │
│  │       ghostty_terminal_append(terminal, buf, n);        │  │
│  │           │                                             │  │
│  │           ├─► effect_write_pty() fires → write(pty_fd)  │  │
│  │           ├─► effect_size() fires → write(pty_fd)       │  │
│  │           └─► effect_device_attr() fires → write(pty_fd)│  │
│  │                                                         │  │
│  │       // Keyboard → encode → write to PTY               │  │
│  │       if (key_event) {                                  │  │
│  │           GhosttyString enc = ghostty_key_encode(key);  │  │
│  │           write(pty_fd, enc.ptr, enc.len);              │  │
│  │       }                                                 │  │
│  │                                                         │  │
│  │       // Render                                         │  │
│  │       ghostty_terminal_fill_snapshot(terminal, ...);    │  │
│  │       render_with_raylib(snapshot);                     │  │
│  │   }                                                     │  │
│  └─────────────────────────────────────────────────────────┘  │
└────────────────────────────────────────────────────────────────┘
```

### Ghostling Effect Callbacks

```c
typedef struct {
    int pty_fd;
    int cell_width;
    int cell_height;
    uint16_t cols;
    uint16_t rows;
} EffectsContext;

// Respond to VT queries by writing back to PTY
static void effect_write_pty(GhosttyTerminal terminal, void *userdata,
                             const uint8_t *data, size_t len)
{
    EffectsContext *ctx = (EffectsContext *)userdata;
    write(ctx->pty_fd, data, len);
}

// Respond to size queries (CSI 14/16/18 t)
static bool effect_size(GhosttyTerminal terminal, void *userdata,
                        GhosttySizeReportSize *out_size)
{
    EffectsContext *ctx = (EffectsContext *)userdata;
    out_size->rows = ctx->rows;
    out_size->columns = ctx->cols;
    out_size->cell_width = ctx->cell_width;
    out_size->cell_height = ctx->cell_height;
    return true;
}

// Respond to device attribute queries
static bool effect_device_attributes(GhosttyTerminal terminal, void *userdata,
                                     GhosttyDeviceAttributes *out_attrs)
{
    out_attrs->primary.conformance_level = GHOSTTY_DA_CONFORMANCE_VT220;
    out_attrs->primary.features[0] = GHOSTTY_DA_FEATURE_COLUMNS_132;
    out_attrs->primary.features[1] = GHOSTTY_DA_FEATURE_ANSI_COLOR;
    out_attrs->primary.num_features = 2;
    return true;
}
```

### Ghostling Main Loop

```c
EffectsContext ctx = { .pty_fd = pty_fd, .cols = 80, .rows = 24, ... };

// Register callbacks
ghostty_terminal_set(terminal, GHOSTTY_TERMINAL_OPT_USERDATA, &ctx);
ghostty_terminal_set(terminal, GHOSTTY_TERMINAL_OPT_WRITE_PTY, &effect_write_pty);
ghostty_terminal_set(terminal, GHOSTTY_TERMINAL_OPT_SIZE, &effect_size);

// Main loop
while (running) {
    // Poll for PTY data and keyboard input
    poll(fds, 2, timeout);
    
    if (pty_has_data) {
        int n = read(pty_fd, buf, sizeof(buf));
        ghostty_terminal_append(terminal, buf, n);
        // ↑ Effects callbacks fire here!
    }
    
    if (keyboard_event) {
        GhosttyKeyEvent key = to_ghostty_key(event);
        GhosttyString encoded = ghostty_key_encoder_encode(encoder, key);
        write(pty_fd, encoded.ptr, encoded.len);
        // ↑ Direct write, no libghostty involved
    }
    
    // Render
    ghostty_terminal_fill_snapshot(terminal, top_row, buffer, capacity);
    render_snapshot_to_raylib(buffer);
}
```

---

## Termux vs Ghostling: Side by Side

| Aspect | Termux | Ghostling |
|--------|--------|-----------|
| **Language** | Zig + Java/Kotlin | C |
| **Rendering** | Android Canvas/SurfaceView | raylib (OpenGL) |
| **PTY Management** | Java `TerminalSession` | C `posix_openpt()` |
| **libghostty API** | Zig `Stream(*Handler)` | C `ghostty_terminal_*` |
| **VT Action Handling** | One big Handler.vt() switch | Effects callbacks |
| **Keyboard Input** | Through Stream's pending_output | Direct PTY write |
| **Thread Model** | Worker thread + main thread | Single threaded with poll() |
| **Bell** | `bell_pending` flag → Java handler | `effect_bell` callback (if added) |

### Both Do The Same Thing

Despite different architectures, both:

1. **Create a PTY** and spawn a shell
2. **Parse terminal output** with libghostty
3. **Maintain terminal state** (screen, cursor, scrollback)
4. **Handle VT queries** (device attributes, size reports, etc.)
5. **Encode keyboard input** to VT sequences
6. **Render** the terminal to screen
7. **Handle bell** by vibrating/playing sound

---

## What Can You Use libghostty For?

### 1. Terminal Emulators

**Like termux and ghostling** - full interactive terminals.

```
PTY ↔ libghostty ↔ Screen
```

### 2. Log Viewers / Output Viewers

Display command output with terminal formatting.

```c
// Just parse and render, no PTY needed
GhosttyTerminal terminal = ghostty_terminal_create(...);
ghostty_terminal_append(terminal, log_output, len);
ghostty_terminal_fill_snapshot(terminal, ...);
render(snapshot);
```

### 3. SSH Clients

Connect to remote servers and display their output.

```
Network ↔ libghostty ↔ Screen
```

### 4. Build System Output

Show colored compiler output, test results, etc.

```
make output → libghostty → formatted display
```

### 5. ANSI Art / Terminal Recordings

Replay `.cast` (asciinema) files or display ANSI art.

```
Recording file → libghostty → playback
```

### 6. Chat/IRC Clients

Display messages with formatting.

```
IRC messages → libghostty → colored chat window
```

### 7. Docker/K8s Log Streaming

Stream container logs with formatting preserved.

```
docker logs -f → libghostty → UI
```

### 8. Custom Terminal UIs

Build TUI applications with custom rendering.

```
Your app → libghostty → GPU-accelerated rendering
```

---

## Why Use libghostty Instead of Writing Your Own?

| Rolling Your Own | Using libghostty |
|------------------|------------------|
| Parse hundreds of VT sequences | Done for you |
| Handle edge cases and quirks | Battle-tested |
| Maintain scrollback buffer | Built-in efficient implementation |
| Support modern features (kitty graphics, etc.) | Already supported |
| Optimize rendering | Optimized snapshots |
| Years of work | Immediate |

### libghostty is Production Quality

- Powers Ghostty terminal (used by thousands)
- Handles edge cases you'd never think of
- Efficient memory management
- Supports modern terminal features:
  - Kitty keyboard protocol
  - Sixel graphics
  - Hyperlinks
  - Progress indicators
  - Desktop notifications
  - And much more

---

## Summary

```
┌─────────────────────────────────────────────────────────────────┐
│                      Terminal Emulator                          │
│                                                                 │
│  ┌─────────┐    ┌───────┐    ┌────────────┐    ┌────────────┐ │
│  │  PTY     │◄──►│Parse  │◄──►│  Terminal  │───►│  Render    │ │
│  │         │    │ VT    │    │   State    │    │            │ │
│  └─────────┘    └───────┘    └────────────┘    └────────────┘ │
│       │                          │                              │
│       │         ┌────────────────┘                              │
│       │         │                                               │
│       ▼         ▼                                               │
│  ┌─────────────────────────────────────────┐                   │
│  │            libghostty                   │                   │
│  │                                         │                   │
│  │  • Stream (parse VT sequences)          │                   │
│  │  • Terminal (maintain state)            │                   │
│  │  • RenderState (build snapshots)        │                   │
│  │  • KeyEncoder (encode keyboard)         │                   │
│  └─────────────────────────────────────────┘                   │
│                                                                 │
│  What YOU provide:                                              │
│  • PTY creation and process management                          │
│  • Reading/writing to PTY                                       │
│  • Rendering (OpenGL, Android Canvas, etc.)                     │
│  • Keyboard/mouse input handling                                │
│  • UI chrome (window, tabs, settings)                           │
└─────────────────────────────────────────────────────────────────┘
```

**libghostty gives you a terminal's brain. You provide the body.**
