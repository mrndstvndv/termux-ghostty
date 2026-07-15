# Milestone 1: Native Serialization & Sizing Optimization Analysis

## Executive Summary
This analysis details the current snapshot serialization mechanism in `terminal-emulator/src/main/zig/src/termux_ghostty.zig` and proposes a memory-aligned, bulk-NIO-friendly contiguous field buffering layout for Milestone 1. By transforming interleaved cell fields into contiguous arrays and enforcing 8-byte alignments, the Java-side JVM deserialization can leverage high-performance `ByteBuffer` bulk reads (e.g. `asIntBuffer().get()`), eliminating thousands of individual virtual method calls and JNI boundary traversals during screen rendering.

---

## 1. Current Implementation Analysis

### File Path
`terminal-emulator/src/main/zig/src/termux_ghostty.zig`

### Header Constants (Lines 52–53)
*   `snapshot_row_header_bytes: usize = 2 * @sizeOf(u32)` (8 bytes, for `charsUsed` and `wrap`).
*   `snapshot_cell_bytes: usize = @sizeOf(i32) + @sizeOf(u16) + 2 + @sizeOf(u64)` (16 bytes, representing interleaved fields: `start` (4), `length` (2), `width` (1), `padding` (1), `style` (8)).

### Frame Capacity Calculation: `snapshotRequiredBytes` (Lines 480–503)
Calculates the aggregate byte size needed for the entire snapshot buffer, including metadata and row payloads:
```zig
fn snapshotRequiredBytes(self: *Session, metadata: *const SnapshotMetadata) !usize {
    const rows: usize = self.render_state.rows;

    var total: usize = snapshot_header_bytes;
    if ((metadata.flags & snapshot_metadata_palette) != 0) {
        total += termux_palette_len * @sizeOf(u32);
    }
    if ((metadata.flags & snapshot_metadata_render) != 0) {
        total += snapshot_render_metadata_bytes;
    }
    if ((metadata.flags & snapshot_metadata_mode_bits) != 0) {
        total += snapshot_mode_bits_bytes;
    }
    total += @as(usize, self.partialDirtyRowCount()) * @sizeOf(u32);
    for (0..rows) |row_index| {
        if (!self.shouldSerializeRow(row_index)) {
            continue;
        }

        total += try self.snapshotRowRequiredBytes(row_index);
    }

    return total;
}
```
*   **Limitation**: Rows are appended back-to-back without boundary alignment padding. This means starting offsets of rows can fall on arbitrary non-8-byte (or even non-2-byte/4-byte) boundaries, which makes bulk reads of 32-bit (`i32`) or 64-bit (`u64`) integers in Java unstable or slow on some hardware architectures due to unaligned memory accesses.

### Row Capacity Calculation: `snapshotRowRequiredBytes` (Lines 505–534)
Calculates the byte size required by an individual row:
```zig
fn snapshotRowRequiredBytes(self: *const Session, row_index: usize) !usize {
    const cols: usize = self.render_state.cols;
    const row_data = self.render_state.row_data.slice();
    const row_cells = row_data.items(.cells);
    const cells = row_cells[row_index].slice();
    const raw_cells = cells.items(.raw);
    const grapheme_cells = cells.items(.grapheme);

    var total: usize = snapshot_row_header_bytes + (cols * snapshot_cell_bytes);
    for (0..cols) |column| {
        const raw_cell = raw_cells[column];
        if (raw_cell.wide == .spacer_tail or raw_cell.wide == .spacer_head) {
            continue;
        }
        if (!raw_cell.hasText()) {
            continue;
        }

        total += utf16LengthForCodepoint(raw_cell.codepoint()) * @sizeOf(u16);
        if (!raw_cell.hasGrapheme()) {
            continue;
        }

        for (grapheme_cells[column]) |cp| {
            total += utf16LengthForCodepoint(cp) * @sizeOf(u16);
        }
    }

    return total;
}
```
*   **Limitation**: Assumes a fixed interleaved cost per cell (`cols * 16` bytes) plus UTF-16 character lengths. No alignment padding is calculated for internal arrays or character arrays.

### Cell Serialization: `writeSnapshotRow` (Lines 536–627)
Fills the output byte buffer for a single row. The cell layout fields are currently written in an interleaved fashion inside a column loop:
```zig
try writer.writeU32(std.math.cast(u32, self.scratch_utf16.items.len) orelse return error.InvalidSnapshotRow);
try writer.writeU32(@intFromBool(row_rows[row_index].wrap));
for (0..self.render_state.cols) |column| {
    try writer.writeI32(self.scratch_cell_starts.items[column]);
    try writer.writeU16(self.scratch_cell_lengths.items[column]);
    try writer.writeU8(self.scratch_cell_widths.items[column]);
    try writer.writeU8(0); // 1-byte padding
    try writer.writeU64(self.scratch_cell_styles.items[column]);
}
for (self.scratch_utf16.items) |unit| {
    try writer.writeU16(unit);
}
```
*   **Limitation**: Interleaving prevents Java from performing bulk reads for entire rows. The JVM is forced to perform individual loop iterations and read element-by-element (`getInt()`, `getShort()`, `get()`, `getLong()`), causing severe performance overhead.

### Outer Loop Serialization: `fillSnapshotCurrentViewport` (Lines 1895–1901)
Serializes all dirty rows:
```zig
for (0..handle.render_state.rows) |row_index| {
    if (!handle.shouldSerializeRow(row_index)) {
        continue;
    }

    handle.writeSnapshotRow(&writer, row_index) catch return -1;
}
```
*   **Limitation**: Row payloads are serialized one after another without aligning their start boundaries.

---

## 2. Proposed Contiguous Layout Strategy

### Proposed Row Payload Structure
To allow bulk operations, the fields must be serialized as contiguous arrays, grouped by field type rather than cell. The layout must guarantee 8-byte alignments for both:
1.  The `Style` array within each row payload (since `u64` values must be 8-byte aligned on some architectures).
2.  The start of the next row payload.

```
+-----------------------------------------------------------------------+
| Field                           | Type             | Size             |
+-----------------------------------------------------------------------+
| Chars Used                      | u32              | 4 bytes          |
| Wrap Flag                       | u32              | 4 bytes          |
| Cell Starts Array               | columns * i32    | cols * 4 bytes   |
| Cell Lengths Array              | columns * u16    | cols * 2 bytes   |
| Cell Widths Array               | columns * u8     | cols * 1 bytes   |
| Inner Padding (Styles Align)    | u8[]             | 0 to 7 bytes     |
| Cell Styles Array               | columns * u64    | cols * 8 bytes   |
| UTF-16 Character Array          | charsUsed * u16  | chars * 2 bytes  |
+-----------------------------------------------------------------------+
```

### Alignment Computations

1.  **Inner Padding (Style Array Alignment)**:
    *   The payload up to the end of the `Widths` array occupies:
        `base_size = 8 + cols * 4 + cols * 2 + cols * 1` = `8 + cols * 7` bytes.
    *   Since the row starts at an 8-byte aligned offset, the offset of the `Widths` array relative to the row start is `8 + cols * 7`.
    *   To align the `Styles` array (`u64`) to an 8-byte boundary, we must add `inner_padding` bytes such that the total size before styles is a multiple of 8.
    *   **Formula**: `inner_padding = (8 - ((8 + cols * 7) % 8)) % 8` which simplifies to `(8 - ((cols * 7) % 8)) % 8`.

2.  **Row Alignment Padding (Start of next row payload)**:
    *   Each row payload must start at an 8-byte aligned boundary.
    *   During size calculation, the total size is updated by rounding up to the nearest multiple of 8 before adding the size of the next row:
        `total = (total + 7) & ~@as(usize, 7)`

---

## 3. Implementation Plan Details (Zig Changes)

### 1. Constant Definitions Updates
Remove `snapshot_cell_bytes` as the cell fields are no longer contiguous per cell. Adjust `snapshot_row_header_bytes` if necessary (it remains `2 * @sizeOf(u32) = 8`).

### 2. Size Calculation Changes
#### Update `snapshotRowRequiredBytes` (Lines 505–534):
```zig
fn snapshotRowRequiredBytes(self: *const Session, row_index: usize) !usize {
    const cols: usize = self.render_state.cols;
    const row_data = self.render_state.row_data.slice();
    const row_cells = row_data.items(.cells);
    const cells = row_cells[row_index].slice();
    const raw_cells = cells.items(.raw);
    const grapheme_cells = cells.items(.grapheme);

    // 1. Calculate characters count in UTF-16 code units
    var chars: usize = 0;
    for (0..cols) |column| {
        const raw_cell = raw_cells[column];
        if (raw_cell.wide == .spacer_tail or raw_cell.wide == .spacer_head) {
            continue;
        }
        if (!raw_cell.hasText()) {
            continue;
        }

        chars += utf16LengthForCodepoint(raw_cell.codepoint());
        if (!raw_cell.hasGrapheme()) {
            continue;
        }

        for (grapheme_cells[column]) |cp| {
            chars += utf16LengthForCodepoint(cp);
        }
    }

    // 2. Base size = Header (8) + Starts (cols * 4) + Lengths (cols * 2) + Widths (cols * 1)
    const base_size = 8 + cols * 7;

    // 3. Alignment padding to make Styles (u64) 8-byte aligned
    const styles_padding = (8 - (base_size % 8)) % 8;

    // 4. Total = base_size + styles_padding + Styles (cols * 8) + Chars (chars * 2)
    return base_size + styles_padding + (cols * 8) + (chars * 2);
}
```

#### Update `snapshotRequiredBytes` (Lines 480–503):
```zig
fn snapshotRequiredBytes(self: *Session, metadata: *const SnapshotMetadata) !usize {
    const rows: usize = self.render_state.rows;

    var total: usize = snapshot_header_bytes;
    if ((metadata.flags & snapshot_metadata_palette) != 0) {
        total += termux_palette_len * @sizeOf(u32);
    }
    if ((metadata.flags & snapshot_metadata_render) != 0) {
        total += snapshot_render_metadata_bytes;
    }
    if ((metadata.flags & snapshot_metadata_mode_bits) != 0) {
        total += snapshot_mode_bits_bytes;
    }
    total += @as(usize, self.partialDirtyRowCount()) * @sizeOf(u32);
    for (0..rows) |row_index| {
        if (!self.shouldSerializeRow(row_index)) {
            continue;
        }

        // Align the starting offset of each row payload to an 8-byte boundary
        total = (total + 7) & ~@as(usize, 7);
        total += try self.snapshotRowRequiredBytes(row_index);
    }

    return total;
}
```

### 3. Serialization Changes
#### Update `writeSnapshotRow` (Lines 536–627):
```zig
fn writeSnapshotRow(self: *Session, writer: *BufferWriter, row_index: usize) !void {
    // ... Clear scratch arrays and populate cell data (identical to current implementation) ...

    // 1. Write length & wrap headers (8 bytes total)
    try writer.writeU32(std.math.cast(u32, self.scratch_utf16.items.len) orelse return error.InvalidSnapshotRow);
    try writer.writeU32(@intFromBool(row_rows[row_index].wrap));

    // 2. Write contiguous Cell Starts Array (cols * i32)
    for (0..self.render_state.cols) |column| {
        try writer.writeI32(self.scratch_cell_starts.items[column]);
    }

    // 3. Write contiguous Cell Lengths Array (cols * u16)
    for (0..self.render_state.cols) |column| {
        try writer.writeU16(self.scratch_cell_lengths.items[column]);
    }

    // 4. Write contiguous Cell Widths Array (cols * u8)
    for (0..self.render_state.cols) |column| {
        try writer.writeU8(self.scratch_cell_widths.items[column]);
    }

    // 5. Align next write offset to 8 bytes for u64 Style array
    while (writer.offset % 8 != 0) {
        try writer.writeU8(0);
    }

    // 6. Write contiguous Cell Styles Array (cols * u64)
    for (0..self.render_state.cols) |column| {
        try writer.writeU64(self.scratch_cell_styles.items[column]);
    }

    // 7. Write UTF-16 characters array (charsUsed * u16)
    for (self.scratch_utf16.items) |unit| {
        try writer.writeU16(unit);
    }
}
```

#### Update `fillSnapshotCurrentViewport` (Lines 1895–1901):
```zig
    for (0..handle.render_state.rows) |row_index| {
        if (!handle.shouldSerializeRow(row_index)) {
            continue;
        }

        // Align the starting offset of each row payload to an 8-byte boundary
        while (writer.offset % 8 != 0) {
            try writer.writeU8(0);
        }

        handle.writeSnapshotRow(&writer, row_index) catch return -1;
    }
```

---

## 4. Verification and Validation Method

### Verification Strategy
Since the changes alter the binary protocol between Zig and Java, both sides must be modified in lockstep to avoid deserialization crashes.

1.  **Compilation Check**:
    Ensure the Zig side compiles without syntax errors:
    *   Command: `./gradlew test` (or clean build target)
2.  **Snapshot Verification**:
    The Java implementation (`ScreenSnapshot.java`) must be updated to align with the contiguous layout:
    *   Read starts, lengths, widths, styles, and character arrays using bulk NIO buffers.
    *   Check for buffer positions matching the expected offsets.
3.  **Local Unit Tests**:
    Verify that screenshotting unit tests and terminal emulator rendering tests pass.
