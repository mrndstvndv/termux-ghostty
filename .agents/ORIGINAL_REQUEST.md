# Original User Request

## 2026-07-15T13:10:12Z

Optimize the termux-ghostty snapshot deserialization pipeline to use contiguous field buffering (bulk NIO transfers) on the JVM and native Zig side.

Working directory: /Volumes/realme/Dev/termux-ghostty
Integrity mode: development

## Requirements

### R1. Contiguous Native Serialization
Optimize the native Zig snapshot serialization loop in `termux_ghostty.zig` to write cell layout fields (starts, lengths, widths, styles) contiguously in the direct ByteBuffer instead of interleaving them per-cell. Ensure that the styles array and starting offsets of each row are dynamically padded to guarantee hardware-level 8-byte alignment.

### R2. Dynamic Capacity Calculation
Update the Zig snapshot size estimation functions (`snapshotRequiredBytes` and `snapshotRowRequiredBytes`) to correctly account for row-start alignment padding and inner style array alignment padding.

### R3. Bulk JVM Deserialization
Modify the Java `ScreenSnapshot.java` deserialization loop to read the contiguous cell layout blocks using bulk Java NIO Buffer operations (e.g., `IntBuffer.get`, `ShortBuffer.get`, `LongBuffer.get`, and `CharBuffer.get`), eliminating cell-by-cell loops. Align the buffer position to an 8-byte boundary at the start of each row and before reading styles.

---

## Reference Material: Audited Implementation Details

Below is the audited implementation design that must be followed.

### Binary Layout per Row:
1.  **UTF-16 length:** `u32` (4 bytes)
2.  **Line wrap flag:** `u32` (4 bytes)
3.  **Contiguous Cell Starts Array:** `columns * i32` (`columns * 4` bytes)
4.  **Contiguous Cell Lengths Array:** `columns * u16` (`columns * 2` bytes)
5.  **Contiguous Cell Widths Array:** `columns * u8` (`columns * 1` bytes)
6.  **8-Byte Alignment Padding:** `0 to 7` dummy bytes to align the styles array offset to a multiple of 8 bytes.
7.  **Contiguous Cell Styles Array:** `columns * u64` (`columns * 8` bytes)
8.  **UTF-16 Characters Array:** `charsUsed * u16` (`charsUsed * 2` bytes)

---

### Native Alignment & Row Serialization Details (`termux_ghostty.zig`):

#### A. Row Serialization:
Modify `writeSnapshotRow` in `termux_ghostty.zig`:
```zig
fn writeSnapshotRow(self: *Session, writer: *BufferWriter, row_index: usize) !void {
    // ... clear scratch arrays and fetch row cells ...

    // 1. Write length & wrap headers
    try writer.writeU32(std.math.cast(u32, self.scratch_utf16.items.len) orelse return error.InvalidSnapshotRow);
    try writer.writeU32(@intFromBool(row_rows[row_index].wrap));

    // 2. Write contiguous Starts
    for (0..self.render_state.cols) |column| {
        try writer.writeI32(self.scratch_cell_starts.items[column]);
    }

    // 3. Write contiguous Lengths
    for (0..self.render_state.cols) |column| {
        try writer.writeU16(self.scratch_cell_lengths.items[column]);
    }

    // 4. Write contiguous Widths
    for (0..self.render_state.cols) |column| {
        try writer.writeU8(self.scratch_cell_widths.items[column]);
    }

    // 5. Align next write offset to 8 bytes for u64 style array (using writer.offset direct field)
    while (writer.offset % 8 != 0) {
        try writer.writeU8(0);
    }

    // 6. Write contiguous Styles
    for (0..self.render_state.cols) |column| {
        try writer.writeU64(self.scratch_cell_styles.items[column]);
    }

    // 7. Write UTF-16 characters
    for (self.scratch_utf16.items) |unit| {
        try writer.writeU16(unit);
    }
}
```

#### B. Outer Serialization Loop:
In `fillSnapshotCurrentViewport`, align the writer position to 8 bytes **before** serializing each dirty row:
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

### Native Capacity Estimation Details (`termux_ghostty.zig`):

#### A. Row Capacity (`snapshotRowRequiredBytes`):
```zig
fn snapshotRowRequiredBytes(self: *const Session, row_index: usize) !usize {
    // ... compute utf16 character count ('chars') ...
    const base_size = 8 + cols * 7;
    const styles_padding = (8 - (base_size % 8)) % 8;
    const row_size = base_size + styles_padding + cols * 8 + chars * 2;
    return row_size;
}
```

#### B. Frame Capacity (`snapshotRequiredBytes`):
```zig
fn snapshotRequiredBytes(self: *Session, metadata: *const SnapshotMetadata) !usize {
    // ... sum metadata fields ...
    for (0..rows) |row_index| {
        if (!self.shouldSerializeRow(row_index)) {
            continue;
        }
        total = (total + 7) & ~@as(usize, 7);
        total += try self.snapshotRowRequiredBytes(row_index);
    }
    return total;
}
```

---

### JVM Deserialization Details (`ScreenSnapshot.java`):

Rewrite the parsing loop in `parseNativeSnapshot()`:
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

    // 3. Bulk read cell starts
    buffer.asIntBuffer().get(row.mCellTextStart, 0, columns);
    buffer.position(buffer.position() + columns * 4);

    // 4. Bulk read cell lengths
    buffer.asShortBuffer().get(row.mCellTextLength, 0, columns);
    buffer.position(buffer.position() + columns * 2);

    // 5. Bulk read cell display widths
    buffer.get(row.mCellDisplayWidth, 0, columns);

    // 6. Align buffer position to 8-byte boundary for styles array
    buffer.position((buffer.position() + 7) & ~7);

    // 7. Bulk read cell styles
    buffer.asLongBuffer().get(row.mStyle, 0, columns);
    buffer.position(buffer.position() + columns * 8);

    // 8. Bulk read characters
    buffer.asCharBuffer().get(row.mText, 0, charsUsed);
    buffer.position(buffer.position() + charsUsed * 2);

    row.finishNative();

    // 9. Run safety bounds & layout validations
    validateNativeRow(rowIndex, charsUsed, columns, row.mCellTextStart, row.mCellTextLength, row.mCellDisplayWidth);
}
```

---

## Acceptance Criteria

### Performance & Memory
- [ ] JVM virtual method invocation count during row metadata deserialization is reduced by over 90% per frame.
- [ ] No unaligned access violations or `SIGBUS` crashes on ARM or x86 architectures.

### Functional Correctness
- [ ] The terminal emulator starts, runs, and renders text and scrollback correctly without regressions.
- [ ] All safety bounds and cell layout validation checks (`validateNativeRow`) run successfully on the deserialized data.
- [ ] The existing JUnit test suite (`./gradlew :terminal-emulator:test`) compiles and passes successfully without any failures.
