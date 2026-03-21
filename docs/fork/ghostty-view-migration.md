# How Terminal-View Adapts to the Ghostty Architecture

## The Old World (Upstream)

Upstream had one monolithic class: `TerminalEmulator` (~2600 lines).

```mermaid
graph LR
    subgraph emulator ["TerminalEmulator (1 class)"]
        direction TB
        E1["VT100 Parser"]:::old
        E2["Screen Buffer"]:::old
        E3["Mouse Handler"]:::old
        E4["Color Manager"]:::old
        E5["Mode Bits"]:::old
        E6["mRows, mColumns"]:::old
        E7["mColors"]:::old
    end

    TV["TerminalView"] -->|"mEmulator.mRows<br/>mEmulator.getScreen()<br/>mEmulator.paste()<br/>mEmulator.sendMouseEvent()"| emulator

    classDef old fill:#9a6700,color:#fff,stroke:#7a5200
```

`TerminalView` accessed everything through `mEmulator`:

```java
// All state reads go through the monolith
mEmulator.mRows
mEmulator.mColumns
mEmulator.isMouseTrackingActive()
mEmulator.getScreen().getActiveRows()
mEmulator.getScreen().getActiveTranscriptRows()

// All actions go through the monolith
mEmulator.paste(text)
mEmulator.sendMouseEvent(button, col, row, pressed)
mEmulator.setCursorBlinkState(true)
mEmulator.setCursorBlinkingEnabled(false)

// Rendering reads the whole emulator every frame
mRenderer.render(mEmulator, canvas, topRow, ...);
```

---

## The New World (Fork)

Ghostty replaced the Java VT parser. The emulation is now native.
Java is just the glue layer.

```mermaid
graph LR
    subgraph native ["Native (Ghostty/Zig)"]
        G["libtermux-ghostty.so<br/>VT parsing, screen buffer,<br/>mouse protocol, modes, colors"]
    end

    subgraph java ["Java Layer"]
        direction TB
        TC["TerminalContent<br/>interface — screen queries"]:::new
        TS["TerminalSession<br/>shell + PTY + I/O"]:::new
        GW["GhosttySessionWorker<br/>async event thread"]:::new
        GJ["GhosttyNative<br/>JNI bridge"]:::new
        FD["FrameDelta<br/>changed rows only"]:::new
        RC["RenderFrameCache<br/>UI-thread cache"]:::new
    end

    TV["TerminalView"] -->|"mTermSession.getRows()<br/>mTermSession.isMouseTrackingActive()<br/>mTermSession.sendGhosttyMouseEvent()"| TS
    TS --> GJ --> G
    GW -->|"publishes"| FD
    FD -->|"merged into"| RC
    TV -->|"renders from"| RC
    TS --> TC

    classDef new fill:#1a7f37,color:#fff,stroke:#0d5a1f
```

---

## Migration: What Changed in Terminal-View

### Direct field access → getter methods

| Upstream (field access) | Fork (method calls on session) |
|---|---|
| `mEmulator.mRows` | `mTermSession.getRows()` |
| `mEmulator.mColumns` | `mTermSession.getColumns()` |
| `mEmulator.getScreen().getActiveRows()` | `mTermSession.getActiveRows()` |
| `mEmulator.getScreen().getActiveTranscriptRows()` | `mTermSession.getActiveTranscriptRows()` |
| `mEmulator.getScreen().getSelectedText(...)` | `mTermSession.getTerminalContent().getSelectedText(...)` |

### Actions routed through session

| Upstream | Fork |
|---|---|
| `mEmulator.paste(text)` | `mTermSession.paste(text)` |
| `mEmulator.sendMouseEvent(btn, col, row, pressed)` | `mTermSession.sendGhosttyMouseEvent(GhosttyMouseEvent)` |
| `mEmulator.setCursorBlinkState(true)` | `mTermSession.setCursorBlinkState(true)` |
| `mEmulator.setCursorBlinkingEnabled(false)` | `mTermSession.setCursorBlinkingEnabled(false)` |
| `mEmulator.clearScrollCounter()` | `mTermSession.clearScrollCounter()` |
| `mEmulator.toggleAutoScrollDisabled()` | `mTermSession.toggleAutoScrollDisabled()` |

### New fork-only calls (no upstream equivalent)

These are Ghostty-specific features that didn't exist in upstream:

```java
// Frame delta rendering pipeline
FrameDelta delta = mTermSession.getGhosttyPublishedFrameDelta();
mGhosttyRenderFrameCache.apply(delta);
mGhosttyRenderFrameCache.getSnapshotForRender(cursorBlink, cursorState);

// Viewport scroll tracking
mTermSession.setGhosttyTopRow(mTopRow);

// Backend state
mTermSession.hasActiveTerminalBackend()
mTermSession.isGhosttyCursorBlinkingEnabled()
mTermSession.getGhosttyCursorBlinkState()

// Cell dimensions (was buried inside TerminalEmulator)
mTermSession.getCellWidthPixels()
mTermSession.getCellHeightPixels()
```

---

## The Rendering Pipeline

This is the biggest architectural difference.

### Upstream: Full scan every frame

```mermaid
sequenceDiagram
    participant UI as UI Thread
    participant TE as TerminalEmulator
    participant TR as TerminalRenderer

    UI->>TE: onDraw()
    TE->>TR: render(mEmulator, canvas, ...)
    TR->>TE: getScreen().getActiveRows()
    TR->>TE: iterate ALL visible rows
    TR->>TR: draw every cell
    Note over TR: 30 rows × 80 cols = 2400 cells<br/>every frame, every time
```

### Fork: Incremental updates via frame deltas

```mermaid
sequenceDiagram
    participant GW as Ghostty Worker
    participant FD as FrameDelta
    participant RC as RenderFrameCache
    participant TV as TerminalView

    GW->>FD: publish changed rows only
    FD->>RC: apply(delta)
    Note over RC: merge dirty rows into<br/>cached snapshot
    TV->>RC: getSnapshotForRender()
    RC-->>TV: cached ScreenSnapshot
    TV->>TV: onDraw(cache)
    Note over TV: only changed rows re-rendered<br/>rest comes from cache
```

### Why this matters

| Scenario | Upstream | Fork |
|---|---|---|
| Type one character | Re-render 30 rows × 80 cols | Update 1 row in cache |
| Scroll output | Re-render everything | Merge new rows, shift cache |
| No changes (idle) | Still re-renders full buffer | FrameDelta is null, skip |
| Cursor blink toggle | Re-render full buffer | Swap cursor flag in cache |

---

## Performance Benefits of Decoupling

Beyond the inherent gains of running VT parsing in native code,
the decoupled architecture itself enables:

### 1. Lock-free frame publishing

```mermaid
graph LR
    subgraph worker ["Ghostty Worker Thread"]
        W["Process terminal output<br/>Update screen buffer<br/>Build FrameDelta"]
    end

    subgraph ui ["UI Thread"]
        U["Read RenderFrameCache<br/>Draw to Canvas"]
    end

    W -->|"immutable FrameDelta<br/>no locks needed"| U
```

The old `TerminalEmulator` was one mutable object shared between threads.
The renderer had to either lock or copy the entire screen buffer every frame.
Now `FrameDelta` is an immutable message — the worker publishes it, the UI
consumes it. No synchronization overhead.

### 2. Skip unchanged frames

`RenderFrameCache.apply()` returns `IGNORED_OLDER_OR_DUPLICATE` for stale
frames. If the UI thread is behind (e.g. during fling animation), it
naturally skips intermediate frames instead of queuing render work.

### 3. Partial rebuilds

The `FrameDelta` carries `reasonFlags`:
- `REASON_APPEND` — new output, only bottom rows changed
- `REASON_VIEWPORT_SCROLL` — user scrolled, only viewport offset changed
- `REASON_RESIZE` — terminal resized, full rebuild needed
- `REASON_COLOR_SCHEME` — colors changed, no row data needed

The cache can optimize based on the reason — appending new output doesn't
need to re-check the top of the screen.

### 4. Decoupled read path

`TerminalContent` is a read-only interface. The renderer never mutates
terminal state. This means:
- Multiple consumers could read the same snapshot (e.g. renderer + a11y)
- The worker can continue processing I/O while the UI renders
- No "render lock" blocking terminal input

### 5. No emulator object allocation

Upstream: `new TerminalEmulator(...)` allocated the entire engine.
Fork: Ghostty allocates in native. Java just holds a pointer (`long`)
inside `GhosttyTerminalContent`. Less GC pressure.

---

## Summary

The migration from `mEmulator` to `mTermSession` looks like a simple
rename, but underneath it's an architectural shift:

- **One mutable class** → **read-only interfaces + immutable messages**
- **Full-scan rendering** → **incremental frame deltas**
- **Thread-shared state** → **lock-free message passing**
- **Java VT parser** → **native Ghostty engine**

Terminal-view adapted by talking to `TerminalSession` directly instead of
going through the `TerminalEmulator` intermediary. The session exposes both
the old-style queries (rows, columns, modes) and the new Ghostty-specific
features (frame deltas, cursor blink state, viewport tracking).
