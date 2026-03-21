# Ghostty vs. Termux: Protocol Implementation Comparison

## Architecture Overview

### Ghostty Stack
```
Platform Event (OS)
    ↓
Surface.zig (platform abstraction)
    ↓
input/mouse_encode.zig (encoding)
    ↓
Terminal.zig (state)
    ↓
Screen.zig (grid state)
    ↓
renderer/image.zig (GPU rendering)
    ↓
GPU texture (platform graphics API)
```

### Termux Stack
```
Platform Event (Android OS)
    ↓
TerminalView.java (touch/event capture)
    ↓
TerminalEmulator.java (state)
    ↓
TerminalScreen.java (grid state)
    ↓
TerminalRenderer.java (Canvas rendering)
    ↓
Canvas bitmap (software rendering)
```

---

## 1. Mouse Protocol Comparison

### Ghostty Implementation

**Encoding location:** `src/input/mouse_encode.zig`
**Lines of code:** ~400
**Architecture:** Input → Encoding → Terminal output

```zig
// Options structure (line 10-35)
pub const Options = struct {
    event: terminal.MouseEvent = .none,
    format: terminal.MouseFormat = .x10,
    size: renderer_size.Size,
    any_button_pressed: bool = false,
    last_cell: ?*?point.Coordinate = null,
};

// Encode function (line 77-180)
pub fn encode(
    writer: *std.Io.Writer,
    event: Event,
    opts: Options,
) std.Io.Writer.Error!void {
    // Filtering logic
    if (!shouldReport(event, opts)) return;
    
    // Cell conversion (deduplication)
    const cell = posToCell(event.pos, opts.size);
    if (event.action == .motion and opts.format != .sgr_pixels) {
        if (opts.last_cell) |last| {
            if (last.*) |last_cell| {
                if (last_cell.eql(cell)) return; // Dedup
            }
        }
    }
    
    // Format-specific encoding
    switch (opts.format) {
        .x10 => { /* X10 encoding */ },
        .utf8 => { /* UTF-8 encoding */ },
        .sgr => { /* SGR encoding */ },
        .urxvt => { /* URXVT encoding */ },
        .sgr_pixels => { /* SGR-Pixels encoding */ },
    }
}
```

**Key features:**
- Stack-allocated encoding buffer (no allocations per event)
- Motion deduplication via cell tracking
- Supports 5 different protocols
- Modulo arithmetic for coordinate encoding
- ~10-20 test cases

### Termux Implementation (Current)

**Encoding location:** `TerminalEmulator.java` (line 365+)
**Lines of code:** ~50
**Architecture:** Platform → sendMouseEvent() → Terminal

```java
// Current implementation (very basic)
public void sendMouseEvent(int mouseButton, int column, int row, boolean pressed) {
    if (mouseButton == MOUSE_LEFT_BUTTON_MOVED && 
        !isDecsetInternalBitSet(DECSET_BIT_MOUSE_TRACKING_BUTTON_EVENT)) {
        return;
    } else if (isDecsetInternalBitSet(DECSET_BIT_MOUSE_PROTOCOL_SGR)) {
        String response = String.format("\u001B[<%d;%d;%d%c",
            mouseButton, column + 1, row + 1, pressed ? 'M' : 'm');
        mSession.write(response);
    }
}

// Event capture (in TerminalView.java, line 740+)
void sendMouseEventCode(MotionEvent e, int button, boolean pressed) {
    x = (int) (e.getX() / mTerminalRenderer.mCharacterWidth);
    y = (int) ((e.getY() - ...) / mTerminalRenderer.mCharacterHeight);
    mTermSession.sendMouseEvent(button, x, y, pressed);
}
```

**Current limitations:**
- Only X10 (legacy) and SGR (basic) modes
- No UTF-8, URXVT, SGR-Pixels support
- No button state tracking
- No motion deduplication
- String allocation per event
- No modifier key handling

### Comparison Table

| Feature | Ghostty | Termux |
|---------|---------|--------|
| X10 format | ✅ Yes | ⚠️ Partial |
| UTF-8 format | ✅ Yes | ❌ No |
| SGR format | ✅ Yes | ⚠️ Partial |
| URXVT format | ✅ Yes | ❌ No |
| SGR-Pixels format | ✅ Yes | ❌ No |
| Motion events | ✅ Yes | ✅ Yes |
| Button state | ✅ Yes | ❌ No |
| Modifier tracking | ✅ Yes | ❌ No |
| Motion deduplication | ✅ Yes | ❌ No |
| No alloc per event | ✅ Yes | ❌ No |
| Comprehensive tests | ✅ 14+ | ❌ 0 |

### What Termux Needs to Add

1. **Separate encoding functions** for each protocol
2. **Button state tracking** (bitmask)
3. **Modifier key capture** (shift/alt/ctrl)
4. **Motion deduplication** (track last cell)
5. **Tests** for each protocol

**Effort:** 200-300 lines of Java, ~4-6 hours

---

## 2. Kitty Imaging Protocol Comparison

### Ghostty Implementation

**APC parsing location:** `src/terminal/apc.zig`
**Command parsing:** `src/terminal/kitty/graphics_command.zig`
**Execution:** `src/terminal/kitty/graphics_exec.zig`
**Storage:** `src/terminal/kitty/graphics_storage.zig`
**Rendering:** `src/renderer/image.zig`
**Total lines:** ~2000+

#### Step 1: APC Identification (apc.zig, ~130 lines)

```zig
pub const Handler = struct {
    state: State = .{ .inactive = {} },

    pub fn start(self: *Handler) void {
        self.state = .{ .identify = {} };
    }

    pub fn feed(self: *Handler, alloc: Allocator, byte: u8) void {
        switch (self.state) {
            .identify => {
                switch (byte) {
                    'G' => self.state = .{ 
                        .kitty = kitty_gfx.CommandParser.init(alloc) 
                    },
                    else => self.state = .{ .ignore = {} },
                }
            },
            .kitty => |*p| {
                p.feed(byte) catch |err| {
                    self.state = .{ .ignore = {} };
                };
            },
        }
    }

    pub fn end(self: *Handler) ?Command {
        defer {
            self.state = .{ .inactive = {} };
        }
        return switch (self.state) {
            .kitty => |*p| .{ .kitty = try p.complete(alloc) },
            else => null,
        };
    }
};
```

#### Step 2: Command Parsing (graphics_command.zig, ~1200 lines)

State machine with 4 states:
- `control_key` - reading parameter names
- `control_value` - reading parameter values  
- `data` - collecting binary payload

```zig
pub const Parser = struct {
    arena: ArenaAllocator,
    kv: std.AutoHashMap(u8, u32),    // Key-value parameters
    data: std.ArrayList(u8),           // Binary payload
    state: State,
    
    pub fn feed(self: *Parser, c: u8) !void {
        switch (self.state) {
            .control_key => switch (c) {
                '=' => { /* transition */ },
                ';' => { /* transition */ },
                else => { /* accumulate */ },
            },
            .control_value => switch (c) {
                ',' => { /* finish kv */ },
                ';' => { /* start data */ },
                else => { /* accumulate */ },
            },
            .data => try self.data.append(...),
        }
    }
};
```

#### Step 3: Command Execution (graphics_exec.zig, ~500 lines)

Executes against terminal state:
- `transmit` - decode & store image
- `display` - place on screen
- `delete` - remove image
- `query` - return info

```zig
pub fn execute(
    alloc: Allocator,
    terminal: *Terminal,
    cmd: *const Command,
) ?Response {
    return switch (cmd.control) {
        .query => query(alloc, cmd),
        .display => display(alloc, terminal, cmd),
        .delete => delete(alloc, terminal, cmd),
        .transmit, .transmit_and_display => transmit(alloc, terminal, cmd),
        // ... animation not implemented ...
    };
}
```

#### Step 4: Image Storage (graphics_storage.zig, ~1200 lines)

Complex storage with:
- Image ID → Image data mapping
- Placement tracking
- Reference counting
- Dirty state tracking

```zig
pub const ImageStorage = struct {
    images: ImageIDMap,
    placements: PlacementMap,
    dirty: bool = true,
    enabled_: bool = true,
    
    pub fn imageById(self: *const ImageStorage, id: u32) ?*const Image {
        // Lookup with validation
    }
    
    pub fn storeImage(self: *ImageStorage, image: Image) !void {
        // Store with memory limits
    }
    
    pub fn placeImage(self: *ImageStorage, placement: Placement) !void {
        // Track placement with z-order
    }
};
```

#### Step 5: Image Rendering (image.zig, ~900 lines)

GPU texture management:
- Upload images to GPU
- Compute placement coordinates
- Layer images (below/above text)
- Batch rendering

```zig
pub const State = struct {
    images: ImageMap,
    kitty_placements: std.ArrayListUnmanaged(Placement),
    kitty_bg_end: u32,     // Below background
    kitty_text_end: u32,   // Below text
    kitty_virtual: bool,
    
    pub fn upload(
        self: *State,
        alloc: Allocator,
        api: *GraphicsAPI,
    ) bool {
        // Upload pending images to GPU
    }
    
    pub fn draw(
        self: *State,
        api: *GraphicsAPI,
        layer: DrawPlacements,
    ) void {
        // Draw specific layer
    }
};
```

### Termux Implementation (Current)

**Image support:** ❌ None

**Required additions:**
1. APC parsing (150 lines)
2. Image storage (200 lines)
3. Renderer integration (150 lines)
4. Image decoding (already available via BitmapFactory)

Total: ~500 lines of Java (vs. 2000+ in Ghostty)

### Ghostty Complexity (Why?)

Ghostty's larger implementation is due to:

1. **Memory efficiency** - Arena allocators, bitmap allocators
2. **GPU optimization** - Texture batching, dirty tracking
3. **Advanced features** - Unicode virtual placement, animation (not done)
4. **Error recovery** - Robust parsing with error messages
5. **Performance** - Per-frame dirty state, deduplication
6. **Language** - Zig explicit control vs. Java GC

### Termux Can Be Simpler

1. **Canvas rendering** - Direct bitmap drawing (no GPU)
2. **No animation** - Skip complex state machines
3. **Simpler storage** - HashMap instead of custom allocator
4. **No virtual placement** - Just position + size for now
5. **Java GC** - Automatic memory management

---

## 3. Terminal View Integration

### Ghostty: Full Graphics Stack

**Surface.zig** (platform abstraction):
```zig
pub const Renderer = struct {
    pub const API = struct {
        // GPU texture management
        pub fn createTexture(...) Texture { }
        pub fn uploadTexture(...) void { }
        pub fn drawTexture(...) void { }
    };
};
```

**renderer/generic.zig** (frame building):
```zig
pub fn buildFrame(...) void {
    // Per frame:
    // 1. Check if images need update
    if (self.images.kittyRequiresUpdate(state.terminal)) {
        self.images.kittyUpdate(...);
    }
    
    // 2. Upload any pending images
    self.images.upload(alloc, api);
    
    // 3. Draw text layer
    drawTextLayer(...);
    
    // 4. Draw images in correct order
    self.images.draw(api, .kitty_below_bg);
    self.images.draw(api, .kitty_below_text);
    self.images.draw(api, .kitty_above_text);
}
```

### Termux: Simple Canvas Integration

**TerminalRenderer.java:**
```java
public final void render(ScreenSnapshot screenSnapshot, Canvas canvas, ...) {
    // 1. Draw background
    canvas.drawColor(backgroundColor);
    
    // 2. Draw text
    renderCachedRow(canvas, ...);
    
    // 3. Draw images
    drawKittyImages(canvas);
}

private void drawKittyImages(Canvas canvas) {
    for (KittyImagePlacement placement : mTerminal.getKittyImages()) {
        Bitmap bitmap = mTerminal.getImage(placement.imageId).bitmap;
        float x = placement.column * mCharacterWidth;
        float y = placement.row * mCharacterHeight;
        canvas.drawBitmap(bitmap, x, y, null);
    }
}
```

**Key difference:**
- Ghostty: Per-frame GPU management (efficient, complex)
- Termux: Direct canvas drawing (simple, less efficient)

---

## 4. State Management Comparison

### Ghostty: Terminal.zig

```zig
pub const Terminal = struct {
    screens: ScreenSet,  // Multiple screens
    
    pub const ScreenSet = struct {
        active: Screen,      // Current screen
        inactive: Screen,    // Alt screen
    };
};

pub const Screen = struct {
    // ... many fields ...
    kitty_images: ImageStorage,  // Image state here
};
```

Mouse state:
```zig
pub const MouseEvent = enum {
    none, x10, normal, button, any
};

pub const MouseFormat = enum {
    x10, utf8, sgr, urxvt, sgr_pixels
};

pub struct Screen {
    flags: struct {
        mouse_event: MouseEvent,
        mouse_format: MouseFormat,
        mouse_shift_capture: ?bool,
    }
};
```

### Termux: TerminalEmulator.java

```java
public class TerminalEmulator {
    private TerminalScreen mScreen;
    
    // Mouse state bits
    private static final int DECSET_BIT_MOUSE_TRACKING_PRESS_RELEASE = 1 << 6;
    private static final int DECSET_BIT_MOUSE_TRACKING_BUTTON_EVENT = 1 << 7;
    private static final int DECSET_BIT_MOUSE_PROTOCOL_SGR = 1 << 9;
    
    private int mDecset_internal; // Bitmask of all modes
    
    public void setDecsetinternalBit(int bit, boolean set) {
        if (set) mDecset_internal |= bit;
        else mDecset_internal &= ~bit;
    }
}
```

**Observation:**
Termux uses bitmasks (bit-packed), Ghostty uses enums (cleaner but more storage).

---

## 5. Summary: What to Port

### Easy (Mouse Protocol)

| Component | Ghostty LOC | Termux Equiv | Complexity |
|-----------|-------------|--------------|------------|
| Encoding | 400 | 300 | Low - Direct port |
| State tracking | 50 | 50 | Low - Already exists |
| Event capture | N/A | 100 | Already done |
| Tests | 500 | 0 | Medium - Write tests |

**Total: 200-300 lines of new Java**

### Hard (Kitty Images)

| Component | Ghostty LOC | Termux Equiv | Complexity |
|-----------|-------------|--------------|------------|
| APC parsing | 130 | 150 | Low - Straightforward |
| Command parser | 1200 | 100 | Low - Simpler termux |
| Image storage | 1200 | 200 | Medium - Implement |
| Rendering | 900 | 150 | High - Canvas integration |
| Decoding | N/A | 0 | Free - BitmapFactory |

**Total: 500-800 lines of new Java**

---

## 6. Performance Characteristics

### Mouse Events

**Ghostty:**
- Stack allocation: ~200 bytes per event
- No string building (binary encoding)
- CPU: < 0.1ms per event
- GC pressure: None

**Termux (if ported):**
- Heap allocation: ~100 bytes per event (String)
- String building: Small overhead
- CPU: 0.1-0.5ms per event
- GC pressure: Low (strings eligible for quick GC)

**Verdict:** Termux version will be ~2-5x slower but acceptable (still <1ms)

### Kitty Images

**Ghostty:**
- GPU texture creation: 1-5ms per image (parallel)
- Per-frame re-upload: Only if dirty
- Rendering cost: < 1ms (batched)
- Memory: 4-8 bytes/pixel (GPU VRAM)

**Termux (if ported):**
- PNG decode: 10-50ms per image (blocking)
- Canvas drawing: 5-20ms per image
- Memory: 4 bytes/pixel (RAM) + GC pressure
- No GPU = software rendering

**Verdict:** Ghostty ~10-50x faster, but termux version still interactive (<100ms)

---

## Conclusion

### Port Priority

1. **Start with mouse** - Lower complexity, immediate value
2. **Then images** - Build on mouse foundation

### Key Learnings from Ghostty

1. **Separation of concerns** - libghostty does VT, platform does rendering
2. **No allocations in hot path** - Mouse encoding is allocation-free
3. **Dirty tracking** - Only re-render when state changes
4. **Batching** - Group image draws into single GPU call

### For Termux

1. **Mouse protocol**: Direct port of Ghostty logic (200-300 lines)
2. **Kitty images**: Simplified version for Canvas (500-800 lines)
3. **Testing**: Write comprehensive test suite (~1000 lines)

**Total estimated effort: 20-40 hours of focused development**
