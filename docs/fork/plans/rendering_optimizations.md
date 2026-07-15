# Performance Optimization Plan: Termux-Ghostty Rendering Pipeline

While the current `termux-ghostty` rendering pipeline is highly optimized compared to traditional snapshot systems, the deserialization bridge between Zig and Java on the UI thread still incurs significant CPU overhead.

This document details three proposed optimizations to transition from individual virtual-method reads to bulk-memory operations, reducing UI-thread latency and garbage collection pressure.

---

## Optimization 1: Contiguous Field Buffering (Bulk NIO Transfers)

### The Problem
Currently, inside [ScreenSnapshot.java](file:///Volumes/realme/Dev/termux-ghostty/terminal-emulator/src/main/java/com/termux/terminal/ScreenSnapshot.java#L460-L483), row metadata is deserialized column-by-column in a nested loop:

```java
for (int column = 0; column < columns; column++) {
    int textStart = buffer.getInt();         // Virtual call
    short textLength = buffer.getShort();     // Virtual call
    byte displayWidth = buffer.get();         // Virtual call
    buffer.get();                            // Padding virtual call
    long style = buffer.getLong();           // Virtual call
    
    row.mCellTextStart[column] = textStart;
    row.mCellTextLength[column] = textLength;
    row.mCellDisplayWidth[column] = displayWidth;
    row.mStyle[column] = style;
}
```

For an 80-column terminal, this generates **400 virtual method calls** per row. Across a 40-row screen, this equals **16,000 method calls** per frame on the UI thread. Each call performs bounds checks, alignment adjustments, and endianness swaps in Java.

### The Solution
Reorganize the binary serialization format in [termux_ghostty.zig](file:///Volumes/realme/Dev/termux-ghostty/terminal-emulator/src/main/zig/src/termux_ghostty.zig#L617-L623) to group fields contiguously. Instead of interleaving them per-cell, serialize them as arrays:

1.  Write all `textStart` values for the row as a contiguous block of `columns * 4` bytes.
2.  Write all `textLength` values as `columns * 2` bytes.
3.  Write all `displayWidth` values as `columns * 1` bytes.
4.  Write all `style` values as `columns * 8` bytes.

On the Java side, perform **bulk array reads**:

```java
// Grouped bulk transfers - 4 method calls per row
buffer.asIntBuffer().get(row.mCellTextStart, 0, columns);
buffer.asShortBuffer().get(row.mCellTextLength, 0, columns);
buffer.get(row.mCellDisplayWidth, 0, columns);
buffer.asLongBuffer().get(row.mStyle, 0, columns);
```

### Impact
*   Reduces virtual JNI/NIO helper method calls from **16,000 to just 160 per frame** (a **100x reduction**).
*   Enables the JVM to perform native block memory copies (`memcpy` equivalents) under the hood.

---

## Optimization 2: Bulk Character Copying

### The Problem
Characters are currently read one-by-one inside a loop:

```java
for (int charIndex = 0; charIndex < charsUsed; charIndex++) {
    row.mText[charIndex] = buffer.getChar(); // Virtual call
}
```

This generates up to 100 additional virtual calls per row.

### The Solution
Read the entire character sequence in a single bulk operation:

```java
buffer.asCharBuffer().get(row.mText, 0, charsUsed);
```

### Impact
*   Replaces individual byte boundary checks with a single optimized block copy per row.

---

## Optimization 3: Native-Side FNV-1a Hash Calculation

### The Problem
When a row is updated, [ScreenSnapshot.java](file:///Volumes/realme/Dev/termux-ghostty/terminal-emulator/src/main/java/com/termux/terminal/ScreenSnapshot.java#L717-L739) calculates a custom FNV-1a content hash in Java to determine if the line needs to be re-rendered:

```java
private void updateContentHash() {
    long hash = 0xcbf29ce484222325L;
    // Loops over all characters and cell styles...
    for (int i = 0; i < mCharsUsed; i++) {
        hash = mixHash(hash, mText[i]);
    }
    for (int i = 0; i < mColumns; i++) {
        hash = mixHash(hash, mStyle[i]);
    }
    // ...
    mContentHash = hash;
}
```

This nested character/style loop runs on the UI thread for every dirty row, duplicating traversal work.

### The Solution
Calculate the FNV-1a hash inside the native Zig serialization code in [termux_ghostty.zig](file:///Volumes/realme/Dev/termux-ghostty/terminal-emulator/src/main/zig/src/termux_ghostty.zig#L570-L593) where the loop is already running in high-performance compiled machine code. Write the hash as a `u64` into the row's header, and read it in Java:

```java
row.mContentHash = buffer.getLong();
```

### Impact
*   Completely eliminates the CPU-intensive content hashing loops on the JVM thread.
*   Offloads hashing to Zig (compiled with `-O3` equivalent optimizations), where it can be fully vectorized by the compiler.
