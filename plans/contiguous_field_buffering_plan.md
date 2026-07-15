# Implementation Plan: Contiguous Field Buffering (Bulk NIO Transfers)

This plan details the implementation steps to transition `termux-ghostty`'s rendering snapshot deserialization from field-by-field virtual reads to bulk contiguous memory copies.

---

## 1. Goal
Reduce the JVM virtual method invocation count during row metadata parsing from **O(rows * columns * 5)** to **O(rows * 4)** by serializing cell layout fields as contiguous primitive blocks and reading them via Java's bulk `Buffer.get()` APIs.

---

## 2. Proposed Changes

### Part 1: Native Serialization (`termux_ghostty.zig`)
In [termux_ghostty.zig](file:///Volumes/realme/Dev/termux-ghostty/terminal-emulator/src/main/zig/src/termux_ghostty.zig#L536), modify `writeSnapshotRow` to serialize arrays contiguously instead of cell-by-cell.

#### New Binary Layout per Row:
1.  **UTF-16 length:** `u32` (4 bytes)
2.  **Line wrap flag:** `u32` (4 bytes)
3.  **Contiguous Cell Starts Array:** `columns * i32` (`columns * 4` bytes)
4.  **Contiguous Cell Lengths Array:** `columns * u16` (`columns * 2` bytes)
5.  **Contiguous Cell Widths Array:** `columns * u8` (`columns * 1` bytes)
6.  **8-Byte Alignment Padding:** `0 to 7` dummy bytes to align the styles array offset to a multiple of 8 bytes.
7.  **Contiguous Cell Styles Array:** `columns * u64` (`columns * 8` bytes)
8.  **UTF-16 Characters Array:** `charsUsed * u16` (`charsUsed * 2` bytes)

#### Native Implementation Sketch (Zig):
```zig
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

// 5. Align next write offset to 8 bytes for u64 style array
const current_offset = writer.offset();
const aligned_offset = (current_offset + 7) & ~@as(usize, 7);
const padding = aligned_offset - current_offset;
var p: usize = 0;
while (p < padding) : (p += 1) {
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
```

---

### Part 2: JVM Deserialization (`ScreenSnapshot.java`)
In [ScreenSnapshot.java](file:///Volumes/realme/Dev/termux-ghostty/terminal-emulator/src/main/java/com/termux/terminal/ScreenSnapshot.java#L460-L489), rewrite the row parsing logic to use bulk transfers.

#### Deserialization Implementation Sketch (Java):
```java
// Perform contiguous bulk reads
// 1. Cell Starts (4-byte aligned)
buffer.asIntBuffer().get(row.mCellTextStart, 0, columns);
buffer.position(buffer.position() + columns * 4);

// 2. Cell Lengths (2-byte aligned)
buffer.asShortBuffer().get(row.mCellTextLength, 0, columns);
buffer.position(buffer.position() + columns * 2);

// 3. Cell Display Widths (1-byte aligned)
buffer.get(row.mCellDisplayWidth, 0, columns);

// 4. Align position to 8-byte boundary for Styles
int pos = buffer.position();
int alignedPos = (pos + 7) & ~7;
buffer.position(alignedPos);

// 5. Styles (8-byte aligned)
buffer.asLongBuffer().get(row.mStyle, 0, columns);
buffer.position(buffer.position() + columns * 8);

// 6. Characters (2-byte aligned)
buffer.asCharBuffer().get(row.mText, 0, charsUsed);
buffer.position(buffer.position() + charsUsed * 2);
```

---

## 3. Important Implementation Risks & Auditing Directives
1.  **Buffer Alignment:** Ensure that when slicing the `IntBuffer`, `ShortBuffer`, or `LongBuffer` via Java's `as*Buffer()` APIs, the absolute address of the direct buffer's pointer is correctly aligned. A mismatch can trigger an `UnalignedAccess` error on some CPU architectures or JVMs.
2.  **Dirty Row Handling:** Since the changes modify the payload layout of serialized rows, they must apply identically to both full screen updates and incremental `FrameDelta` row updates.
3.  **Buffer Bounds Checks:** The total size required per row is now variable (depending on character count and 8-byte padding). Ensure `snapshotRequiredBytes` in Zig dynamically estimates the exact required capacity including padding bytes.
