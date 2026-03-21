# Quick Reference: Your Questions Answered

## Question 1: How is the kitty imaging protocol integrated in ghostty?

### Short Answer
The Kitty imaging protocol is a **two-layer system**:
1. **VT Layer (libghostty)** - Protocol parsing, command execution, image storage
2. **View Layer (Platform)** - GPU rendering, texture upload, drawing

### The Flow
```
User runs: icat image.png
    ↓
icat sends APC sequence: \x1b_Gf=24,s=width,v=height;PNG_DATA\x1b\\
    ↓
Ghostty Parser.zig identifies APC escape sequence
    ↓
stream.zig routes to apc.zig
    ↓
apc.zig detects first byte 'G' for Kitty graphics
    ↓
kitty/graphics_command.zig parses key=value parameters
    ↓
kitty/graphics_exec.zig executes transmit/display/delete commands
    ↓
Image stored in Terminal.Screen.kitty_images (ImageStorage)
    ↓
renderer/image.zig GPU texture management
    ↓
Frame rendering: drawKittyImages() on GPU
```

### Code Path
```
src/terminal/apc.zig (130 lines) - APC identification
    ↓
src/terminal/kitty/graphics_command.zig (1200 lines) - Command parsing
    ↓
src/terminal/kitty/graphics_exec.zig (500 lines) - Execution
    ↓
src/terminal/kitty/graphics_storage.zig (1200 lines) - Storage
    ↓
src/renderer/image.zig (900 lines) - GPU rendering
```

---

## Question 2: How is the mouse click/cursor move protocol implemented?

### Short Answer
Mouse protocol is **input → encoding → output**:
1. **Capture** - Platform (Surface.zig) captures mouse event
2. **Encode** - input/mouse_encode.zig encodes according to terminal mode
3. **Send** - Encoded bytes sent to terminal input

### The Flow
```
User clicks/moves mouse
    ↓
Surface.zig.mouseButton() captures event with position, button, action
    ↓
input/mouse_encode.Options constructed from terminal state
    ↓
input/mouse_encode.encode() converts to protocol format
    ↓
Terminal input receives encoded sequence (e.g., \x1B[<0;15;10M for SGR)
    ↓
Application receives as stdin and responds
```

### Code Path
```
src/Surface.zig:3607 (mouse event capture)
    ↓
src/input/mouse_encode.zig (full protocol encoding)
    - shouldReport() - filter by terminal mode (x10/normal/button/any)
    - buttonCode() - encode button + modifiers
    - encode() - format-specific encoding
```

### Supported Formats
1. **X10** (1000) - Legacy, 3-byte format, only left/middle/right
2. **UTF-8** (1005) - Unicode-based, supports wide coordinates
3. **SGR** (1006) - Modern, supports 11 buttons + modifiers
4. **URXVT** (1015) - rxvt extension
5. **SGR-Pixels** (1016) - Pixel-level precision (not grid-based)

---

## Question 3: Do both need terminal view integration? Or purely libghostty?

### Mouse Protocol

**Terminal View Integration: MINIMAL**

✅ **What the view provides:**
- Capture mouse events from OS
- Convert pixel coordinates to grid cells
- Send events to terminal

❌ **What the view DOESN'T do:**
- Encode the protocol (libghostty does)
- Track terminal modes (libghostty does)
- Send responses (libghostty does)

**Flow:**
```
View: capture event → to terminal
Terminal: apply mode logic + encode → back to view → to app stdin
```

Mice protocol is **100% independent of rendering**. You could have a terminal with no graphics at all and still use mouse.

### Kitty Imaging

**Terminal View Integration: CRITICAL**

✅ **What the view must provide:**
- Decode PNG/GIF/etc. (if not done by VT layer)
- Render images to pixels
- Draw on canvas/screen
- Handle clipping at edges

❌ **What the view doesn't do:**
- Parse the protocol (libghostty does)
- Execute commands (libghostty does)
- Store image data (libghostty does)

**Flow:**
```
View: (not involved in reception)
Terminal: parse + store image
View: render stored images on canvas
```

Images **require view integration** because you need to display pixels.

---

## Question 4: Is it possible to add support in termux?

### Mouse Protocol: YES ✅ (Easy)

**Current status in termux:**
- Event capture: ✅ Done (TerminalView.java line 740+)
- Encoding: ⚠️ Partial (only X10 + basic SGR)
- Terminal modes: ⚠️ Partial (DECSET bits exist, not all used)

**What's missing:**
- UTF-8, URXVT, SGR-Pixels encoding
- Button state tracking
- Motion deduplication
- Modifier key integration
- Tests

**Effort:** 4-6 hours, ~200-300 lines of Java

**Approach:**
```java
// In TerminalEmulator.sendMouseEvent():
switch (mMouseFormat) {
    case SGR: sendSGRMouse(...); break;
    case UTF8: sendUTF8Mouse(...); break;
    case URXVT: sendURXVTMouse(...); break;
    // ... etc
}
```

### Kitty Imaging: YES ✅ (Hard)

**Current status in termux:**
- Event capture: N/A
- Protocol parsing: ❌ None
- Image storage: ❌ None
- Rendering: ❌ None

**What needs building:**
1. APC parsing in TerminalEmulator.java (~150 lines)
2. Image storage HashMap (~200 lines)
3. Canvas rendering in TerminalRenderer.java (~150 lines)
4. Image decoding (use BitmapFactory, built-in)

**Effort:** 20-30 hours, ~500-800 lines of Java

**Approach:**
```java
// In TerminalEmulator.append():
if (seenAPC) {
    handleAPCCommand(apcBuffer);
}

// New methods:
void handleAPCCommand(String data) {
    if (data.startsWith("G")) {
        KittyGraphicsCommand cmd = parseKittyCommand(data);
        processKittyImage(cmd);
    }
}

// In TerminalRenderer.render():
drawKittyImages(canvas);
```

---

## Implementation Roadmap

### Phase 1: Mouse (Do This First)

**Why first?**
- Simpler logic
- Immediate user benefit
- Foundation for Phase 2

**Steps:**
1. Add encoding functions for UTF-8, URXVT, SGR-Pixels
2. Add button state tracking (int bitmask)
3. Add motion deduplication
4. Write tests

**Time:** 4-6 hours

### Phase 2: Kitty Images

**Why after mouse?**
- More complex
- Builds on terminal architecture knowledge
- Benefits from Phase 1 completion

**Steps:**
1. Add APC parsing to TerminalEmulator
2. Create KittyImageStorage class
3. Add image rendering to TerminalRenderer
4. Write tests

**Time:** 20-30 hours

---

## Ghostty Code You Should Read

### For Mouse Protocol
1. **`src/input/mouse_encode.zig`** (400 lines)
   - All 5 protocol implementations
   - Motion deduplication
   - Format selection logic
   - Comprehensive test suite

2. **`src/input/mouse.zig`** (100 lines)
   - Button/action/momentum definitions
   - Helps understand event types

3. **`src/Surface.zig`** (line 3600+)
   - Event capture to encoding pipeline
   - Renderer size usage

### For Kitty Imaging
1. **`src/terminal/apc.zig`** (130 lines) - **Start here**
   - APC identification
   - Handler state machine
   - Entry point

2. **`src/terminal/kitty/graphics_command.zig`** (1200 lines)
   - Complex but educational
   - Shows how to parse binary escape sequences
   - Use arena allocators

3. **`src/terminal/kitty/graphics_storage.zig`** (1200 lines)
   - Image storage patterns
   - Reference counting (optional for termux)
   - Dirty state tracking

4. **`src/renderer/image.zig`** (900 lines)
   - GPU rendering patterns
   - Placement calculation
   - Layering logic

### Don't Need to Read (Ghostty-specific)
- GPU texture management (Surface.zig Graphics API)
- Arena allocators (use Java GC instead)
- Advanced animation support (not implemented yet anyway)

---

## Terminal Emulator Architecture Comparison

### What Ghostty Does Better
1. **Separation of concerns** - VT and rendering are truly separate
2. **Memory efficiency** - Zero-allocation hot paths
3. **GPU acceleration** - Fast image rendering
4. **Comprehensive testing** - 500+ test cases

### Where Termux Needs Work
1. **Protocol parsing** - Limited (only CSI, missing APC)
2. **Image support** - Zero
3. **Advanced mouse modes** - Partial

### How Termux Can Catch Up (Quickly)
1. **Mouse:** Port Ghostty's encoding (same algorithms)
2. **Images:** Simplified version for Canvas (no GPU needed)

---

## Key Technical Insights

### Mouse Protocol

The protocol encoding is **protocol-specific**, not terminal-specific:

```
Terminal Mode: "any" (report everything)
Mouse Event: press, left button, shift key, position (15, 10)
    ↓
Format: SGR
    ↓
Encoding: \x1B[<0;15;10M  (button=0, x=15, y=10)
    ↓
Format: X10
    ↓
Encoding: \x1B[M !(64) !(48) !(42)  (button+32, x+33, y+33)
```

Same event, different formats = **different bytes sent**.

### Kitty Imaging

The protocol is **stateful and persistent**:

1. **Transmit** - Sends image data
   ```
   \x1b_Gf=24,s=100,v=50;BINARY_PNG_DATA\x1b\\
   ```
   Image stored in terminal memory

2. **Display** - Places stored image
   ```
   \x1b_Gd=1,i=1,x=5,y=10\x1b\\
   ```
   Shows image at cell (5, 10)

3. **Delete** - Removes from memory
   ```
   \x1b_Gd=2,i=1\x1b\\
   ```
   Delete image ID 1

**This is different from ANSI art** - Images are first-class objects, not character placeholders.

---

## Decision Tree: What Should You Do?

```
Do you want: Mouse protocol improvements?
  Yes → Start with mouse (4-6 hours)
  No → Skip to images

Do you want: Kitty image support?
  Yes → Build on mouse foundation (20-30 hours)
  No → Done

Do you want: Full compatibility?
  Yes → Implement both
  No → Implement whichever has priority
```

### Recommended Path
1. ✅ Port mouse encoding (low risk, high value)
2. ✅ Add Kitty image support (higher effort, impressive feature)
3. ⚠️ Optimize if performance issues arise

---

## Links to Key Files

### Ghostty (Reference Implementation)
```
APC Parsing
  └─ src/terminal/apc.zig

Mouse Protocol
  └─ src/input/mouse_encode.zig (all formats)
  └─ src/input/mouse.zig (types)
  └─ src/Surface.zig:3607 (integration)

Kitty Imaging
  └─ src/terminal/kitty/graphics.zig (entry point)
  ├─ src/terminal/kitty/graphics_command.zig (parsing)
  ├─ src/terminal/kitty/graphics_exec.zig (execution)
  ├─ src/terminal/kitty/graphics_storage.zig (storage)
  └─ src/renderer/image.zig (rendering)
```

### Termux (Where to Modify)
```
Mouse Protocol
  └─ terminal-emulator/src/main/java/com/termux/terminal/TerminalEmulator.java
     (sendMouseEvent() method)

Kitty Imaging  
  └─ terminal-emulator/src/main/java/com/termux/terminal/TerminalEmulator.java
     (append() method)
  └─ terminal-view/src/main/java/com/termux/view/TerminalRenderer.java
     (render() method)
  └─ terminal-emulator/src/main/java/com/termux/terminal/TerminalScreen.java
     (add image storage)
```

---

## Final Summary

| Aspect | Mouse | Kitty Images |
|--------|-------|------|
| **VT Logic** | 100% done in ghostty | 100% done in ghostty |
| **View Integration** | Minimal (capture + send) | Critical (rendering) |
| **Termux Status** | 60% done (missing encoders) | 0% (need parsing + rendering) |
| **Effort to Complete** | 4-6 hours | 20-30 hours |
| **Complexity** | Low | High |
| **User Impact** | High (many apps use mouse) | High (image viewing) |
| **Recommended Start** | Yes, do first | Yes, do second |

Both are **achievable in termux** and **worth doing**. Start with mouse for quick wins, then tackle images.
