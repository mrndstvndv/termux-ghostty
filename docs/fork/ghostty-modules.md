# Termux Module Architecture

## Dependency Graph

```mermaid
graph BT
    A["<b>terminal-emulator</b><br/>com.termux.terminal<br/>Standalone ✓"]:::standalone

    B["<b>terminal-view</b><br/>com.termux.view<br/>Standalone ✓"]:::standalone

    C["<b>termux-shared</b><br/>com.termux.shared<br/>NOT standalone"]:::internal

    D["<b>app</b><br/>com.termux.app<br/>The Termux app"]:::app

    B -->|"api (transitive)"| A
    C -->|"implementation"| B
    D -->|"implementation"| C

    E["androidx.annotation"]:::ext
    F["appcompat + material<br/>guava + markwon<br/>hiddenapibypass"]:::ext

    E --> A
    E --> B
    F --> C

    classDef standalone fill:#1a7f37,color:#fff,stroke:#0d5a1f
    classDef internal fill:#9a6700,color:#fff,stroke:#7a5200
    classDef app fill:#6e40c9,color:#fff,stroke:#553098
    classDef ext fill:#3d4450,color:#fff,stroke:#2d333b
```

## What's Inside Each Module

```mermaid
graph LR
    subgraph emulator ["terminal-emulator"]
        direction TB
        E1["GhosttyNative<br/>libtermux-ghostty.so"]:::e
        E2["TerminalSession<br/>Shell + PTY mgmt"]:::e
        E3["TerminalContent<br/>Screen buffer API"]:::e
        E4["ScreenSnapshot<br/>FrameDelta"]:::e
        E5["KeyHandler<br/>Keyboard encoding"]:::e
        E6["TerminalColors<br/>TextStyle"]:::e
        E1 --- E2
        E2 --- E3
        E3 --- E4
        E5 --- E2
        E6 --- E3
    end

    subgraph view ["terminal-view"]
        direction TB
        V1["TerminalView<br/>Android View"]:::v
        V2["TerminalRenderer<br/>Canvas text drawing"]:::v
        V3["TextSelection<br/>Controller"]:::v
        V4["LinkLayout<br/>Gesture handling"]:::v
        V1 --- V2
        V1 --- V3
        V1 --- V4
    end

    subgraph shared ["termux-shared"]
        direction TB
        S1["TermuxSession<br/>Session manager"]:::s
        S2["ExtraKeysView<br/>Extra keys bar"]:::s
        S3["TermuxProperties<br/>Settings"]:::s
        S4["ShellUtils<br/>ExecutionCommand"]:::s
        S5["CrashHandler<br/>Logger"]:::s
        S1 --- S2
        S3 --- S1
        S4 --- S1
        S5 --- S1
    end

    view -->|"api"| emulator
    shared -->|"impl"| view

    classDef e fill:#1a7f37,color:#fff,stroke:#0d5a1f
    classDef v fill:#0969da,color:#fff,stroke:#0550ae
    classDef s fill:#9a6700,color:#fff,stroke:#7a5200
```

## Third-Party Usability

```mermaid
graph LR
    subgraph usable ["Can embed independently ✓"]
        direction TB
        U1["<b>terminal-emulator</b><br/>Just the engine<br/>No UI deps<br/>Pure data + I/O"]:::good
        U2["<b>terminal-view</b><br/>Terminal widget<br/>Pulls in emulator<br/>Canvas rendering"]:::good
    end

    subgraph blocked ["Do not use ✗"]
        direction TB
        B1["<b>termux-shared</b><br/>Termux-specific infra<br/>~93/100 files are<br/>Termux glue code"]:::bad
    end

    classDef good fill:#1a7f37,color:#fff,stroke:#0d5a1f
    classDef bad fill:#cf222e,color:#fff,stroke:#a40e26
```

---

## `terminal-emulator` — The Engine

**Package:** `com.termux.terminal`
**Coord:** `com.termux:terminal-emulator`
**Dependencies:** only `androidx.annotation`

### Contains
- VT100/xterm parsing (Ghostty native, `libtermux-ghostty.so`)
- `TerminalSession` — shell subprocess + PTY management
- `TerminalContent` — screen buffer abstraction
- `ScreenSnapshot`, `FrameDelta` — rendering data for consumers
- `KeyHandler` — keyboard encoding
- `TerminalColors`, `TextStyle` — color/style model
- `JNI` — native subprocess creation
- `GhosttySessionWorker` — async I/O thread for Ghostty
- `TerminalLinkSource` — URL/link detection

### Zero UI dependencies
No Canvas, Paint, Bitmap, View, or Android graphics imports.
Only `android.graphics.Color` for brightness calculation.

### Third-party usable?
**Yes.** Pure data + I/O. Any app can embed a terminal engine.

---

## `terminal-view` — The UI Layer

**Package:** `com.termux.view`
**Coord:** `com.termux:terminal-view`
**Dependencies:** `terminal-emulator` (transitive via `api`), `androidx.annotation`

### Contains
- `TerminalView` — Android View (~2000 lines)
- `TerminalRenderer` — Canvas-based text rendering
- Touch/mouse/keyboard/IME handling
- Text selection controller
- Cursor blinking (`TerminalCursorBlinkerRunnable`)
- Link detection layout (`TerminalViewLinkLayout`)
- Gesture and scale recognition
- AutoFill support

### Third-party usable?
**Yes.** Pulls in `terminal-emulator` transitively. This is the "give me a terminal widget" library.

```groovy
implementation 'com.termux:terminal-view:0.118.0'
// Includes terminal-emulator automatically
```

---

## `termux-shared` — Termux-Specific Infrastructure

**Package:** `com.termux.shared`
**Coord:** `com.termux:termux-shared`
**Dependencies:** `terminal-view` + appcompat, material, guava, markwon, hiddenapibypass

### Contains
- `TermuxConstants`, `TermuxBootstrap` — app paths, bootstrap config
- `TermuxSession` — higher-level session manager
- `ExtraKeysView`, `ExtraKeysInfo` — the extra keys bar
- `TermuxSharedProperties` — settings from `termux.properties`
- `ShellUtils`, `ExecutionCommand` — shell utilities
- `CrashHandler`, `Logger`, `NotificationUtils`
- App preferences for every Termux companion app (API, Boot, Float, Widget...)
- `TermuxTerminalSessionClientBase` — base impl of `TerminalSessionClient`
- `TermuxTerminalViewClientBase` — base impl of `TerminalViewClient`

### Coupling to terminal libraries
Only **7 of ~100 files** import `com.termux.terminal` or `com.termux.view`.
The rest is Termux-specific Android infrastructure.

### Third-party usable?
**No.** References Termux file paths, bootstrap types, companion app package names.
It's internal glue code for the Termux ecosystem.

---

## What a Third-Party App Needs

### Minimal: just the engine
```groovy
implementation 'com.termux:terminal-emulator:0.118.0'
```
Implement `TerminalSessionClient`. Manage the `TerminalView` yourself (or skip UI entirely for headless use).

### With UI: the terminal widget
```groovy
implementation 'com.termux:terminal-view:0.118.0'
```
Implement `TerminalSessionClient` + `TerminalViewClient`. Drop `TerminalView` into your layout.

### Don't use
```groovy
// This is Termux internals:
implementation 'com.termux:termux-shared:0.118.0'
```

---

## Key Interfaces for Consumers

### `TerminalSessionClient` (terminal-emulator)
Callbacks from session → app:
- `onTextChanged`, `onTitleChanged`, `onSessionFinished`
- `onCopyTextToClipboard`, `onPasteTextFromClipboard`
- `onBell`, `onColorsChanged`
- `setTerminalShellPid`
- Logging methods

### `TerminalViewClient` (terminal-view)
Callbacks from view → app:
- `onSingleTapUp`, `onLongPress`
- `onKeyDown`, `onKeyUp`, `onCodePoint`
- `onScale` (pinch zoom)
- `copyModeChanged`
- Key modifier reads (`readControlKey`, `readAltKey`, etc.)

### `TerminalContent` (terminal-emulator)
Screen state queries:
- `getColumns`, `getRows`, `getActiveRows`
- `getCursorRow`, `getCursorCol`, `getCursorStyle`
- `getSelectedText`, `getTranscriptText`
- `isAlternateBufferActive`, `isMouseTrackingActive`
- `fillSnapshot` (for rendering)
