# Audited Plan: Contiguous Field Buffering (Bulk NIO Transfers)

This is the **audited** version of the implementation plan, incorporating direct JVM alignment constraints, Zig syntax fixes, and dynamic capacity checks identified by the Plan Auditor.

---

## 1. Goal
Optimize `termux-ghostty`'s rendering snapshot deserialization to achieve a **100x+ reduction** in JVM virtual method calls on the UI thread, bypassing individual byte-boundary checks in favor of native bulk memory copying (`memcpy`).

---

## 2. Updated Binary Layout

To guarantee hardware-level 8-byte alignment on ARM/x86 architectures, both **row starting offsets** and **styles array offsets** within each row must be dynamically aligned.

```
Buffer Start
 ├── Snapshot Headers (variable size)
 ├── Outer Padding (variable: 0 to 7 bytes) -> aligned to 8-byte boundary
 │
 ├── Row 0 Start
 │    ├── UTF-16 characters count: u32 (4 bytes)
 │    ├── Line wrap flag: u32 (4 bytes)
 │    ├── Cell Starts Array: columns * i32 (columns * 4 bytes)
 │    ├── Cell Lengths Array: columns * u16 (columns * 2 bytes)
 │    ├── Cell Display Widths Array: columns * u8 (columns * 1 bytes)
 │    ├── Inner Padding (variable: 0 to 7 bytes) -> aligns next offset to 8-byte boundary
 │    ├── Cell Styles Array: columns * u64 (columns * 8 bytes)
 │    └── UTF-16 Characters Array: charsUsed * u16 (charsUsed * 2 bytes)
 │
 ├── Outer Padding (variable: 0 to 7 bytes) -> aligns next offset to 8-byte boundary
 ├── Row 1 Start
 ...
```

---

## 3. Native Serialization Code (`termux_ghostty.zig`)

### A. Alignment Helpers & Row Serialization
Modify `writeSnapshotRow` in [termux_ghostty.zig](file:///Volumes/realme/Dev/termux-ghostty/terminal-emulator/src/main/zig/src/termux_ghostty.zig#L536) to perform aligned writes using the `writer.offset` field directly (rather than as a function call):

```zig
fn writeSnapshotRow(self: *Session, writer: *BufferWriter, row_index: usize) !void {
    // ... clear scratch arrays and fetch row cells ...

    // 1. Write length & wrap headers
    try writer.writeU32(std.math.cast(u32, self.scratch_utf16.items.len) orelse return error.InvalidSnapshotRow);
    try writer.writeU32(@intFromBool(row_rows[row_index].wrap));

    // 2. Write contiguous Starts (4-byte aligned since row starts are 8-byte aligned)
    for (0..self.render_state.cols) |column| {
        try writer.writeI32(self.scratch_cell_starts.items[column]);
    }

    // 3. Write contiguous Lengths (2-byte aligned)
    for (0..self.render_state.cols) |column| {
        try writer.writeU16(self.scratch_cell_lengths.items[column]);
    }

    // 4. Write contiguous Widths (1-byte aligned)
    for (0..self.render_state.cols) |column| {
        try writer.writeU8(self.scratch_cell_widths.items[column]);
    }

    // 5. Align next write offset to 8 bytes for u64 style array
    while (writer.offset % 8 != 0) {
        try writer.writeU8(0);
    }

    // 6. Write contiguous Styles (8-byte aligned)
    for (0..self.render_state.cols) |column| {
        try writer.writeU64(self.scratch_cell_styles.items[column]);
    }

    // 7. Write UTF-16 characters
    for (self.scratch_utf16.items) |unit| {
        try writer.writeU16(unit);
    }
}
```

### B. Outer Serialization Loop
In `fillSnapshotCurrentViewport`, align the writer position **before** serializing each dirty row:

```zig
for (0..handle.render_state.rows) |row_index| {
    if (!handle.shouldSerializeRow(row_index)) {
        continue;
    }

    // Align start of each row payload to 8-byte boundary
    while (writer.offset % 8 != 0) {
        try writer.writeU8(0);
    }

    handle.writeSnapshotRow(&writer, row_index) catch return -1;
}
```

---

## 4. Native Capacity Estimation Code (`termux_ghostty.zig`)

Modify size calculations to accurately account for dynamic row-alignment padding.

### A. Row Capacity (`snapshotRowRequiredBytes`)
```zig
fn snapshotRowRequiredBytes(self: *const Session, row_index: usize) !usize {
    // ... compute utf16 character count ('chars') ...
    
    // Row Header (8) + Cell Starts (cols * 4) + Cell Lengths (cols * 2) + Cell Widths (cols * 1)
    const base_size = 8 + cols * 7;
    // Padding to align styles to an 8-byte boundary
    const styles_padding = (8 - (base_size % 8)) % 8;
    
    // Total size = base + padding + Styles (cols * 8) + Characters (chars * 2)
    const row_size = base_size + styles_padding + cols * 8 + chars * 2;
    return row_size;
}
```

### B. Frame Capacity (`snapshotRequiredBytes`)
```zig
fn snapshotRequiredBytes(self: *Session, metadata: *const SnapshotMetadata) !usize {
    // ... sum metadata fields and dirty row indices array ...
    
    for (0..rows) |row_index| {
        if (!self.shouldSerializeRow(row_index)) {
            continue;
        }

        // Pad outer offset to 8-byte boundary for the start of the row
        total = (total + 7) & ~@as(usize, 7);
        total += try self.snapshotRowRequiredBytes(row_index);
    }

    return total;
}
```

---

## 5. JVM Deserialization Code (`ScreenSnapshot.java`)

Rewrite the parsing block in `parseNativeSnapshot()` to use bulk views, align the buffer position before each row, and preserve row validation checks.

```java
int payloadRowCount = mFullRebuild ? rows : dirtyRowCount;
for (int payloadIndex = 0; payloadIndex < payloadRowCount; payloadIndex++) {
    int rowIndex = mFullRebuild ? payloadIndex : mDirtyRows[payloadIndex];

    // 1. Align buffer position to 8-byte boundary for the start of the row
    buffer.position((buffer.position() + 7) & ~7);

    // 2. Read headers
    int charsUsed = buffer.getInt();
    boolean lineWrap = buffer.getInt() != 0;

    RowSnapshot row = mRowsData[rowIndex];
    row.beginNative(charsUsed, columns, lineWrap);

    // 3. Bulk read cell starts (4-byte aligned)
    buffer.asIntBuffer().get(row.mCellTextStart, 0, columns);
    buffer.position(buffer.position() + columns * 4);

    // 4. Bulk read cell lengths (2-byte aligned)
    buffer.asShortBuffer().get(row.mCellTextLength, 0, columns);
    buffer.position(buffer.position() + columns * 2);

    // 5. Bulk read cell display widths (1-byte aligned)
    buffer.get(row.mCellDisplayWidth, 0, columns);

    // 6. Align buffer position to 8-byte boundary for styles array
    buffer.position((buffer.position() + 7) & ~7);

    // 7. Bulk read cell styles (8-byte aligned)
    buffer.asLongBuffer().get(row.mStyle, 0, columns);
    buffer.position(buffer.position() + columns * 8);

    // 8. Bulk read characters (2-byte aligned)
    buffer.asCharBuffer().get(row.mText, 0, charsUsed);
    buffer.position(buffer.position() + charsUsed * 2);

    row.finishNative();

    // 9. Run safety bounds & layout validations
    validateNativeRow(rowIndex, charsUsed, columns, row.mCellTextStart, row.mCellTextLength, row.mCellDisplayWidth);
}
```
