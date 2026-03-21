# Termux Implementation Guide: Kitty & Mouse Protocols

## Quick Answers to Your Questions

### Q1: Do both protocols need terminal view integration?

**Mouse Protocol:**
- ✅ **Mostly NO** - It's purely VT stuff
- View only needs to capture events and send them
- Terminal encodes the response
- View receives encoded output like normal

**Kitty Imaging:**
- ❌ **YES, definitely** - Requires view integration for rendering
- VT layer handles protocol parsing/storage
- View must decode images and draw them on canvas

---

### Q2: Is it purely libghostty VT stuff?

**Mouse Protocol:**
- 100% VT-level (protocol encoding, terminal modes)
- Platform provides event capture (already in termux)

**Kitty Imaging:**
- 90% VT-level (protocol parsing, storage, placement)
- 10% requires platform/view (image decoding, rendering)

---

### Q3: Is it possible to add support in termux?

**Mouse Protocol: YES** ✅
- Work: ~4-6 hours
- Complexity: Low
- Just add SGR/UTF-8 encoding to existing code

**Kitty Imaging: YES, but harder** ⚠️
- Work: ~20-30 hours  
- Complexity: High
- Need to add APC parsing, image storage, canvas rendering

---

## Implementation Roadmap

### Phase 1: Mouse Protocol (Recommended First)

**Effort: ~200-300 lines of Java**

#### 1.1 Extend DECSET bits in TerminalEmulator.java

Current state (only has basic support):
```java
// Line 34-39
public static final int MOUSE_LEFT_BUTTON = 0;
public static final int MOUSE_MIDDLE_BUTTON = 1;
public static final int MOUSE_RIGHT_BUTTON = 2;
public static final int MOUSE_LEFT_BUTTON_MOVED = 32;
public static final int MOUSE_WHEELUP_BUTTON = 64;
public static final int MOUSE_WHEELDOWN_BUTTON = 65;

// Line 119-125
private static final int DECSET_BIT_MOUSE_TRACKING_PRESS_RELEASE = 1 << 6;   // 1000
private static final int DECSET_BIT_MOUSE_TRACKING_BUTTON_EVENT = 1 << 7;    // 1001
private static final int DECSET_BIT_MOUSE_PROTOCOL_CLICK = 1 << 8;
private static final int DECSET_BIT_MOUSE_PROTOCOL_SGR = 1 << 9;             // 1006
```

**Add missing bits:**
```java
// New modes to add
private static final int DECSET_BIT_MOUSE_PROTOCOL_UTF8 = 1 << 10;   // 1005
private static final int DECSET_BIT_MOUSE_PROTOCOL_URXVT = 1 << 11;  // 1015
private static final int DECSET_BIT_MOUSE_PROTOCOL_PIXELS = 1 << 12; // 1016

// Track button state for motion events
private int mMouseButtonsPressed = 0; // Bitmask
```

#### 1.2 Update CSI handler for mouse modes

Find where CSI sets these bits and add:
```java
case 1005: // UTF-8 mouse format
    return DECSET_BIT_MOUSE_PROTOCOL_UTF8;

case 1006: // SGR mouse format
    return DECSET_BIT_MOUSE_PROTOCOL_SGR;

case 1015: // URXVT mouse format
    return DECSET_BIT_MOUSE_PROTOCOL_URXVT;

case 1016: // SGR-Pixels mouse format
    return DECSET_BIT_MOUSE_PROTOCOL_PIXELS;
```

#### 1.3 Update sendMouseEvent() with encoding

**Current code (line 365):**
```java
public void sendMouseEvent(int mouseButton, int column, int row, boolean pressed) {
    if (mouseButton == MOUSE_LEFT_BUTTON_MOVED && 
        !isDecsetInternalBitSet(DECSET_BIT_MOUSE_TRACKING_BUTTON_EVENT)) {
        return;
    } else if (isDecsetInternalBitSet(DECSET_BIT_MOUSE_PROTOCOL_SGR)) {
        // Existing SGR handling
    }
}
```

**Enhance with full encoding:**
```java
public void sendMouseEvent(int mouseButton, int column, int row, boolean pressed) {
    // Check if mouse tracking is enabled
    if (!isMouseTrackingActive()) {
        return;
    }
    
    // Check if this event type should be reported
    if (!shouldReportMouseEvent(mouseButton, pressed)) {
        return;
    }
    
    // Update button state
    if (pressed) {
        mMouseButtonsPressed |= (1 << getButtonBit(mouseButton));
    } else {
        mMouseButtonsPressed &= ~(1 << getButtonBit(mouseButton));
    }
    
    // Get button code for encoding
    int buttonCode = encodeMouseButton(mouseButton, pressed, mMouseButtonsPressed);
    
    // Determine protocol format and encode
    String encoded = null;
    if (isDecsetInternalBitSet(DECSET_BIT_MOUSE_PROTOCOL_SGR)) {
        encoded = encodeSGR(buttonCode, column, row, pressed);
    } else if (isDecsetInternalBitSet(DECSET_BIT_MOUSE_PROTOCOL_UTF8)) {
        encoded = encodeUTF8(buttonCode, column, row);
    } else if (isDecsetInternalBitSet(DECSET_BIT_MOUSE_PROTOCOL_URXVT)) {
        encoded = encodeURXVT(buttonCode, column, row);
    } else if (isDecsetInternalBitSet(DECSET_BIT_MOUSE_PROTOCOL_PIXELS)) {
        encoded = encodeSGRPixels(buttonCode, column, row, pressed);
    } else {
        // X10 format (default, legacy)
        encoded = encodeX10(buttonCode, column, row);
    }
    
    if (encoded != null) {
        mSession.write(encoded);
    }
}

private int encodeMouseButton(int button, boolean pressed, int buttonsPressed) {
    int code;
    
    switch (button) {
        case MOUSE_LEFT_BUTTON:
            code = 0;
            break;
        case MOUSE_MIDDLE_BUTTON:
            code = 1;
            break;
        case MOUSE_RIGHT_BUTTON:
            code = 2;
            break;
        case MOUSE_LEFT_BUTTON_MOVED:
            code = 3; // No button, but motion indicated
            break;
        case MOUSE_WHEELUP_BUTTON:
            code = 64;
            break;
        case MOUSE_WHEELDOWN_BUTTON:
            code = 65;
            break;
        default:
            return -1;
    }
    
    // Add motion bit if moving
    if (button == MOUSE_LEFT_BUTTON_MOVED) {
        code += 32;
    }
    
    return code;
}

private String encodeSGR(int buttonCode, int column, int row, boolean pressed) {
    char action = pressed ? 'M' : 'm';
    return String.format("\u001B[<%d;%d;%d%c", buttonCode, column + 1, row + 1, action);
}

private String encodeX10(int buttonCode, int column, int row) {
    if (column > 222 || row > 222) {
        return null; // X10 can't encode large coordinates
    }
    
    byte button = (byte)(32 + buttonCode);
    byte x = (byte)(32 + column + 1);
    byte y = (byte)(32 + row + 1);
    
    return "\u001B[M" + (char)button + (char)x + (char)y;
}

private String encodeUTF8(int buttonCode, int column, int row) {
    StringBuilder sb = new StringBuilder("\u001B[M");
    sb.append((char)(32 + buttonCode));
    
    // UTF-8 encode column
    int cp = column + 33;
    sb.appendCodePoint(cp);
    
    // UTF-8 encode row
    cp = row + 33;
    sb.appendCodePoint(cp);
    
    return sb.toString();
}

private String encodeURXVT(int buttonCode, int column, int row) {
    return String.format("\u001B[%d;%d;%dM", 
        32 + buttonCode, column + 1, row + 1);
}

private String encodeSGRPixels(int buttonCode, int column, int row, boolean pressed) {
    char action = pressed ? 'M' : 'm';
    // In SGR-Pixels, column/row are already in pixels
    return String.format("\u001B[<%d;%d;%d%c", buttonCode, column, row, action);
}

private boolean shouldReportMouseEvent(int mouseButton, boolean pressed) {
    // Mode checks: 1000=press/release, 1001=button event, 1002=motion, 1003=any
    if (isDecsetInternalBitSet(DECSET_BIT_MOUSE_TRACKING_PRESS_RELEASE)) {
        // X10 mode - only press of left/middle/right
        return pressed && (mouseButton == MOUSE_LEFT_BUTTON || 
                          mouseButton == MOUSE_MIDDLE_BUTTON || 
                          mouseButton == MOUSE_RIGHT_BUTTON);
    }
    
    if (isDecsetInternalBitSet(DECSET_BIT_MOUSE_TRACKING_BUTTON_EVENT)) {
        // Button event mode - all events
        return true;
    }
    
    // Normal mode or others - don't report motion
    if (mouseButton == MOUSE_LEFT_BUTTON_MOVED) {
        return false;
    }
    
    return true;
}

private int getButtonBit(int button) {
    switch (button) {
        case MOUSE_LEFT_BUTTON: return 0;
        case MOUSE_MIDDLE_BUTTON: return 1;
        case MOUSE_RIGHT_BUTTON: return 2;
        case MOUSE_WHEELUP_BUTTON: return 3;
        case MOUSE_WHEELDOWN_BUTTON: return 4;
        default: return -1;
    }
}
```

#### 1.4 Update TerminalView to send motion events

**Current code (line 214-219):**
```java
if (mTermSession.isMouseTrackingActive() && e.isFromSource(InputDevice.SOURCE_MOUSE)) {
    sendMouseEventCode(e, TerminalEmulator.MOUSE_LEFT_BUTTON_MOVED, true);
}
```

Enhance to include button state:
```java
if (mTermSession.isMouseTrackingActive()) {
    // For mouse pointer or multi-button tracking
    if (e.isFromSource(InputDevice.SOURCE_MOUSE) || 
        mTermSession.mTerminalEmulator.isDecsetInternalBitSet(
            TerminalEmulator.DECSET_BIT_MOUSE_TRACKING_BUTTON_EVENT)) {
        
        // Send motion event
        sendMouseEventCode(e, TerminalEmulator.MOUSE_LEFT_BUTTON_MOVED, true);
    }
}
```

**Estimated implementation time: 4-6 hours**

---

### Phase 2: Kitty Image Protocol (More Complex)

**Effort: ~500-1000 lines of Java + library integration**

#### 2.1 Add APC parsing to TerminalEmulator.java

Create a new helper class for APC handling:

```java
// New class: APCHandler.java
public class APCHandler {
    private static final char APC_IDENTIFIER_KITTY_GRAPHICS = 'G';
    
    private StringBuilder mApcBuffer;
    private char mApcIdentifier;
    private boolean mInAPC;
    
    public APCHandler() {
        reset();
    }
    
    public void reset() {
        mApcBuffer = new StringBuilder();
        mApcIdentifier = 0;
        mInAPC = false;
    }
    
    public void startAPC() {
        mInAPC = true;
        mApcBuffer.setLength(0);
        mApcIdentifier = 0;
    }
    
    public void feedByte(byte b) {
        if (!mInAPC) return;
        
        if (mApcIdentifier == 0) {
            // First byte identifies the APC type
            mApcIdentifier = (char) b;
            return;
        }
        
        mApcBuffer.append((char) b);
    }
    
    public APCCommand endAPC() {
        if (!mInAPC) return null;
        
        mInAPC = false;
        
        switch (mApcIdentifier) {
            case APC_IDENTIFIER_KITTY_GRAPHICS:
                return parseKittyGraphics(mApcBuffer.toString());
            default:
                return null;
        }
    }
    
    private APCCommand parseKittyGraphics(String data) {
        // Parse format: key1=val1,key2=val2;binary_data
        int semicolonIndex = data.indexOf(';');
        String controlPart = semicolonIndex >= 0 ? 
            data.substring(0, semicolonIndex) : data;
        String dataPart = semicolonIndex >= 0 ? 
            data.substring(semicolonIndex + 1) : "";
        
        Map<String, String> params = new HashMap<>();
        for (String pair : controlPart.split(",")) {
            int eqIndex = pair.indexOf('=');
            if (eqIndex > 0) {
                String key = pair.substring(0, eqIndex);
                String value = pair.substring(eqIndex + 1);
                params.put(key, value);
            }
        }
        
        return new KittyGraphicsCommand(params, dataPart);
    }
}

// New class: KittyGraphicsCommand.java
public class KittyGraphicsCommand extends APCCommand {
    public static final String ACTION_DISPLAY = "a";
    public static final String ACTION_TRANSMIT = "t";
    public static final String ACTION_TRANSMIT_AND_DISPLAY = "T";
    public static final String ACTION_DELETE = "d";
    
    Map<String, String> params;
    String binaryData;
    int imageId;
    int width, height;
    String format;
    String action;
    
    public KittyGraphicsCommand(Map<String, String> params, String data) {
        this.params = params;
        this.binaryData = data;
        
        // Parse parameters
        this.imageId = parseInt(params.get("i"), 0);
        this.width = parseInt(params.get("s"), 0);
        this.height = parseInt(params.get("v"), 0);
        this.format = params.get("f"); // 24=PNG, 100=RGB, etc
        this.action = params.get("a"); // t=transmit, T=transmit+display, d=delete
    }
    
    private static int parseInt(String value, int defaultVal) {
        if (value == null) return defaultVal;
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException e) {
            return defaultVal;
        }
    }
}
```

#### 2.2 Update TerminalEmulator to call APC handler

In the main input loop:
```java
// In append() method or wherever escape sequences are handled
private APCHandler mAPCHandler = new APCHandler();

// When ESC _ is encountered
private void handleAPCStart() {
    mAPCHandler.startAPC();
}

// When a byte is part of APC data
private void handleAPCByte(byte b) {
    mAPCHandler.feedByte(b);
}

// When ESC \ is encountered
private void handleAPCEnd() {
    APCCommand cmd = mAPCHandler.endAPC();
    if (cmd instanceof KittyGraphicsCommand) {
        handleKittyGraphics((KittyGraphicsCommand) cmd);
    }
}

private void handleKittyGraphics(KittyGraphicsCommand cmd) {
    // Delegate to image storage
    switch (cmd.action) {
        case KittyGraphicsCommand.ACTION_TRANSMIT:
        case KittyGraphicsCommand.ACTION_TRANSMIT_AND_DISPLAY:
            storeKittyImage(cmd);
            if (KittyGraphicsCommand.ACTION_TRANSMIT_AND_DISPLAY.equals(cmd.action)) {
                displayKittyImage(cmd.imageId);
            }
            break;
        case KittyGraphicsCommand.ACTION_DISPLAY:
            displayKittyImage(cmd.imageId);
            break;
        case KittyGraphicsCommand.ACTION_DELETE:
            deleteKittyImage(cmd.imageId);
            break;
    }
}
```

#### 2.3 Create image storage in TerminalScreen

```java
// New class: KittyImageStorage.java
public class KittyImageStorage {
    private static final int MAX_IMAGES = 256;
    
    private SparseArray<KittyImageData> mImages = new SparseArray<>();
    private SparseArray<KittyImagePlacement> mPlacements = new SparseArray<>();
    
    public void storeImage(int id, byte[] pngData, int width, int height) {
        KittyImageData data = new KittyImageData(id, pngData, width, height);
        mImages.put(id, data);
    }
    
    public void placeImage(int imageId, int column, int row) {
        KittyImagePlacement placement = new KittyImagePlacement(imageId, column, row);
        mPlacements.put(imageId, placement);
    }
    
    public void deleteImage(int imageId) {
        mImages.remove(imageId);
        mPlacements.remove(imageId);
    }
    
    public KittyImageData getImage(int id) {
        return mImages.get(id);
    }
    
    public Collection<KittyImagePlacement> getPlacements() {
        List<KittyImagePlacement> list = new ArrayList<>();
        for (int i = 0; i < mPlacements.size(); i++) {
            list.add(mPlacements.valueAt(i));
        }
        return list;
    }
}

public class KittyImageData {
    public int id;
    public Bitmap bitmap;
    
    public KittyImageData(int id, byte[] pngData, int width, int height) {
        this.id = id;
        // Decode PNG
        this.bitmap = BitmapFactory.decodeByteArray(pngData, 0, pngData.length);
    }
}

public class KittyImagePlacement {
    public int imageId;
    public int column;
    public int row;
    
    public KittyImagePlacement(int imageId, int col, int row) {
        this.imageId = imageId;
        this.column = col;
        this.row = row;
    }
}
```

#### 2.4 Update TerminalScreen

```java
public class TerminalScreen {
    private KittyImageStorage mKittyImages = new KittyImageStorage();
    
    public KittyImageStorage getKittyImages() {
        return mKittyImages;
    }
}
```

#### 2.5 Modify TerminalRenderer to draw images

In `TerminalRenderer.java`:

```java
public final void render(ScreenSnapshot screenSnapshot, Canvas canvas, ...) {
    // ... existing text rendering code ...
    
    // Draw Kitty images
    drawKittyImages(canvas, screenSnapshot);
}

private void drawKittyImages(Canvas canvas, ScreenSnapshot screenSnapshot) {
    if (mTerminal.mScreen.getKittyImages() == null) {
        return;
    }
    
    for (KittyImagePlacement placement : 
         mTerminal.mScreen.getKittyImages().getPlacements()) {
        
        KittyImageData imageData = 
            mTerminal.mScreen.getKittyImages().getImage(placement.imageId);
        
        if (imageData == null || imageData.bitmap == null) {
            continue;
        }
        
        // Convert terminal cell coordinates to pixel coordinates
        float pixelX = placement.column * mCharacterWidth;
        float pixelY = (placement.row - mTopRow) * mCharacterHeight;
        
        // Draw the image
        canvas.drawBitmap(imageData.bitmap, pixelX, pixelY, null);
    }
}
```

**Estimated implementation time: 20-30 hours**

---

## Testing Recommendations

### Mouse Protocol Testing

```bash
# In terminal, test mouse reporting
printf '\033[?1000h'  # Enable X10 mode
printf '\033[?1006h'  # Enable SGR mode

# Test with mouse-enabled app (vim, less, top, etc)
vim

# You should see terminal respond to mouse clicks
```

### Kitty Image Testing

```bash
# Simple test script
printf '\033_Gf=24,s=10,v=10;BINARY_PNG_DATA\033\\'

# View with icat (kitty image viewer)
icat image.png
```

---

## Dependencies

### For Mouse Protocol
- None! Only uses existing Java APIs

### For Kitty Images
- Image decoding: `android.graphics.BitmapFactory` (built-in)
- Or use: Glide, Picasso, or ImageView libraries (optional, for advanced features)

---

## Testing Checklist

### Mouse Protocol
- [ ] X10 mode (basic click reporting)
- [ ] SGR mode (extended buttons/modifiers)
- [ ] UTF-8 mode (wide coordinate support)
- [ ] URXVT mode
- [ ] Motion reporting with button
- [ ] Scroll wheel events
- [ ] Modifier keys (shift/ctrl/alt)

### Kitty Images
- [ ] PNG decoding
- [ ] Image placement on terminal
- [ ] Image layering (below/above text)
- [ ] Image deletion/update
- [ ] Multiple concurrent images
- [ ] Clipping at terminal edges

---

## Performance Tips

### Mouse Protocol
- **Cache encoding buffers** - Don't allocate new StringBuilder per event
- **Use switch instead of string comparison** for format detection
- **Profile before optimizing** - Mouse events aren't performance-critical

### Kitty Images  
- **Decode asynchronously** - Don't block UI thread for PNG decoding
- **Cache decoded bitmaps** - Reuse same bitmap if image data unchanged
- **Limit concurrent images** - Cap at 256 to prevent memory explosion
- **Use weak references** - Let GC collect old images

---

## Links

- Ghostty source: https://github.com/mitchellh/ghostty
- Kitty graphics spec: https://sw.kovidgoyal.net/kitty/graphics-protocol/
- ANSI mouse protocols: https://www.xfree86.org/current/ctlseqs.html
