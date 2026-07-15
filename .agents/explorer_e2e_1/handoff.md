# Snapshot Serialization and Deserialization Pipeline E2E Exploration Report

## 1. Observation
This investigation focused on `ScreenSnapshot.java`, `RenderFrameCache.java`, `FrameDelta.java`, `ViewportLinkSnapshot.java`, and `termux_ghostty.zig` under `/Volumes/realme/Dev/termux-ghostty`.

### Key Code Paths Observed:
1. **Interleaved Row Serialization (Native Side):**
   In `terminal-emulator/src/main/zig/src/termux_ghostty.zig` (lines 615-627), rows are serialized by interleaving the properties of each cell (starts, lengths, widths, dummy padding, style):
   ```zig
   try writer.writeU32(std.math.cast(u32, self.scratch_utf16.items.len) orelse return error.InvalidSnapshotRow);
   try writer.writeU32(@intFromBool(row_rows[row_index].wrap));
   for (0..self.render_state.cols) |column| {
       try writer.writeI32(self.scratch_cell_starts.items[column]);
       try writer.writeU16(self.scratch_cell_lengths.items[column]);
       try writer.writeU8(self.scratch_cell_widths.items[column]);
       try writer.writeU8(0);
       try writer.writeU64(self.scratch_cell_styles.items[column]);
   }
   for (self.scratch_utf16.items) |unit| {
       try writer.writeU16(unit);
   }
   ```

2. **Interleaved Row Deserialization (JVM Side):**
   In `terminal-emulator/src/main/java/com/termux/terminal/ScreenSnapshot.java` (lines 450-488), the direct buffer is parsed using individual element accesses in a column-wise loop:
   ```java
   int payloadRowCount = mFullRebuild ? rows : dirtyRowCount;
   for (int payloadIndex = 0; payloadIndex < payloadRowCount; payloadIndex++) {
       int rowIndex = mFullRebuild ? payloadIndex : mDirtyRows[payloadIndex];
       int charsUsed = buffer.getInt();
       boolean lineWrap = buffer.getInt() != 0;
       ...
       RowSnapshot row = mRowsData[rowIndex];
       row.beginNative(charsUsed, columns, lineWrap);
       for (int column = 0; column < columns; column++) {
           int textStart = buffer.getInt();
           short textLength = buffer.getShort();
           byte displayWidth = buffer.get();
           buffer.get();
           long style = buffer.getLong();
           ...
           row.mCellTextStart[column] = textStart;
           row.mCellTextLength[column] = textLength;
           row.mCellDisplayWidth[column] = displayWidth;
           row.mStyle[column] = style;
       }

       for (int charIndex = 0; charIndex < charsUsed; charIndex++) {
           row.mText[charIndex] = buffer.getChar();
       }
       row.finishNative();
   }
   ```

3. **Render Frame Cache Apply Logic:**
   In `terminal-emulator/src/main/java/com/termux/terminal/RenderFrameCache.java` (lines 60-84), delta snapshots are validated and row content is copied:
   ```java
   int topRowDelta = transportSnapshot.getTopRow() - mSnapshot.getTopRow();
   boolean requiresFullRebuild = shouldFullRebuild(transportSnapshot, frameDelta, topRowDelta);
   if (requiresFullRebuild && !frameDelta.isFullRebuild()) {
       logDroppedPartialRequiringFullRebuild(frameDelta, topRowDelta);
       return ApplyResult.REJECTED_PARTIAL_REQUIRING_FULL_REBUILD;
   }

   if (requiresFullRebuild) {
       mSnapshot.copyFrom(transportSnapshot);
   } else {
       if (topRowDelta != 0) {
           shiftRows(topRowDelta, transportSnapshot.getRows());
       }
       mSnapshot.copyFrameStateFrom(transportSnapshot);
       for (int index = 0; index < frameDelta.getDirtyRowCount(); index++) {
           mSnapshot.copyRowFrom(transportSnapshot, frameDelta.getDirtyRow(index));
       }
   }
   ```

4. **Existing JNI/JVM Test Code:**
   In `terminal-emulator/src/test/java/com/termux/terminal/RenderFrameCacheTest.java` (lines 129-139), a test helper mimics native serialization to build mock binary buffers:
   ```java
   private static void writeBlankRow(ByteBuffer buffer, int columns) {
       buffer.putInt(0);
       buffer.putInt(0);
       for (int column = 0; column < columns; column++) {
           buffer.putInt(0);
           buffer.putShort((short) 0);
           buffer.put((byte) 1);
           buffer.put((byte) 0);
           buffer.putLong(0L);
       }
   }
   ```

---

## 2. Logic Chain
The current architecture interleaves cell metadata and styling elements per cell. This format makes it impossible to perform bulk memory copying (bulk NIO transfer) on the JVM side. To optimize the JNI boundary performance by avoiding per-cell loops and element gets, we must transition to the packed, contiguous block structures specified in `PROJECT.md`.

### Correspondence to `PROJECT.md` Binary Layout Contract:
To implement the contiguous format, row payloads must follow this structure:
1. **UTF-16 length (`charsUsed`):** `u32` (4 bytes) at offset `0`
2. **Line wrap flag:** `u32` (4 bytes) at offset `4`
3. **Contiguous Cell Starts Array:** `columns * i32` (`columns * 4` bytes) at offset `8`
4. **Contiguous Cell Lengths Array:** `columns * u16` (`columns * 2` bytes) at offset `8 + columns * 4`
5. **Contiguous Cell Widths Array:** `columns * u8` (`columns * 1` bytes) at offset `8 + columns * 6`
6. **8-Byte Alignment Padding (`P` bytes):** `(8 - ((columns * 7) & 7)) & 7` dummy bytes to align the styles array offset to a multiple of 8 bytes.
7. **Contiguous Cell Styles Array:** `columns * u64` (`columns * 8` bytes) starting at `8 + columns * 7 + P`
8. **UTF-16 Characters Array:** `charsUsed * u16` (`charsUsed * 2` bytes) starting at `8 + columns * 15 + P`
9. **Row End Alignment Padding (`Q` bytes):** `(8 - ((charsUsed * 2) & 7)) & 7` dummy bytes to ensure the total row payload size is a multiple of 8 bytes, so any subsequent row starts on an 8-byte boundary.

Also, a global prefix alignment padding `G` must be written after the headers, metadata, and dirty rows index array to ensure the first row payload begins at an 8-byte aligned offset:
- Prefix size `H` = 28 bytes header + optional palette (1036 bytes) + optional cursor (20 bytes) + optional mode bits (4 bytes) + partial dirty rows index list (`dirtyRowCount * 4` bytes).
- Prefix padding `G = (8 - (H & 7)) & 7` bytes.

---

## 3. Caveats
- Direct memory manipulation via direct `ByteBuffer` views (e.g. `asIntBuffer()`, `asShortBuffer()`, etc.) modifies only the position of the created views. The parent `ByteBuffer`'s position must be explicitly advanced by the caller.
- JNI direct buffers use native byte ordering, which must be set via `.order(ByteOrder.nativeOrder())` on all buffer views.

---

## 4. Conclusion & Feature Inventory (13 Features)

### Feature Inventory:
1. **Feature 1: Magic Header Validation** (verifying `TGX2` (`0x54475832`) header to protect against corrupt data).
2. **Feature 2: JNI Direct Buffer Allocation** (allocating and managing native-ordered JNI direct byte buffers).
3. **Feature 3: Dimensions & Viewport Context** (serializing/deserializing top-row offsets, rows, columns, and flags).
4. **Feature 4: Differential Incremental Updates** (tracking and applying only dirty rows instead of full redraws).
5. **Feature 5: Dynamic Palette Color Sync** (transmitting the 259-color terminal theme palette).
6. **Feature 6: Cursor State and Style Sync** (transmitting cursor y/x coordinates, visible, blink, and style properties).
7. **Feature 7: Mode Bits Configuration Sync** (transmitting terminal mode bitmask, e.g. bracketed paste).
8. **Feature 8: UTF-16 Codepoints Translation** (encoding surrogate pairs and mapping Unicode to UTF-16 character arrays).
9. **Feature 9: Contiguous Packing of cell starts, lengths, and widths** (writing/reading contiguous blocks in bulk).
10. **Feature 10: Contiguous Packing of 64-bit cell styles** (writing/reading contiguous u64 array in bulk).
11. **Feature 11: 8-Byte Alignment Enforcement** (calculating and inserting padding for styles array and row payloads).
12. **Feature 12: Row-level Content Hash Integrity** (FNV-1a hash checks and generation tracking).
13. **Feature 13: Viewport Links Extraction** (detecting and serializing hyperlink segments (`ViewportLinkSnapshot` / `TGL1`)).

---

## 5. Proposing Test Cases (Tiers 1-4)

### Tier 1: Unit & Component Tests
- **T1.1: Math Padding Verification:** Verify alignment logic formulas `P` and `Q` across standard and unusual column/char counts (e.g. columns = 80, 81, 82, 83; chars = 0, 1, 2, 3).
- **T1.2: Style Bitmask Encoding:** Verify `encodeTermuxStyle` properly encodes 64-bit styling values including underlines, inverse, and bold/italic flags.
- **T1.3: Unicode surrogate parsing:** Verify UTF-16 length estimation and surrogate pair insertion (codepoints > 0xFFFF).
- **T1.4: Header magic check:** Verify parser throws `IllegalStateException` on wrong magic numbers.

### Tier 2: Integration & Pipeline Tests
- **T2.1: Bulk NIO Extraction:** Verify Java bulk reads (`asIntBuffer().get(...)`, `asShortBuffer().get(...)`, etc.) yield identical cell data to standard manual parsing loops.
- **T2.2: Cache Differential Application:** Verify `RenderFrameCache` correctly applies `FrameDelta` partial updates (reusing unchanged rows and overwriting dirty ones).
- **T2.3: Viewport Scrolling Shift:** Verify `RenderFrameCache` shifts row records correctly on vertical scrolling delta.
- **T2.4: Sequence Gap Reject:** Verify cache rejects partial frame deltas when there is a missing sequence index.

### Tier 3: Edge & Boundary Tests
- **T3.1: Terminal Resizing Full Rebuild:** Verify cache rejects partial updates after a column resize and forces a full rebuild request.
- **T3.2: Buffer Limits and Overflows:** Verify native/Java side throws exception when payload sizes exceed direct buffer bounds.
- **T3.3: Empty Snapshot Handling:** Verify zero rows or zero columns states are correctly handled without crashing.
- **T3.4: Hyperlink Interning Limits:** Verify `ViewportLinkSnapshot` deduplicates identical URLs and throws if the string table overflows.

### Tier 4: E2E & High-Throughput Performance Tests
- **T4.1: High Frequency Rendering:** Simulate rendering a sustained 120 FPS stream of full screen outputs and measure JNI bridge memory allocations.
- **T4.2: E2E Visual Verification:** Write character data into Ghostty native backend, serialize it, deserialize on JVM, and compare cell-by-cell equality against a reference rendering matrix.
- **T4.3: Thread Concurrency Safety:** Test concurrent worker thread writes and UI thread reads on the frame cache to verify thread isolation.

---

## 6. Verification Method

### Testing Execution:
To verify project test integrity, run:
```bash
./gradlew :terminal-emulator:test
```
This runs the full test suite in `terminal-emulator` including `RenderFrameCacheTest`.

### Files to Inspect:
- `terminal-emulator/src/main/java/com/termux/terminal/ScreenSnapshot.java`
- `terminal-emulator/src/main/zig/src/termux_ghostty.zig`
- `terminal-emulator/src/test/java/com/termux/terminal/RenderFrameCacheTest.java`
