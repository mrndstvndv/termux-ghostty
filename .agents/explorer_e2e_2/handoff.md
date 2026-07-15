# Snapshot Serialization Pipeline Exploration & E2E Testing Plan

This report contains the findings of the exploration of the snapshot serialization/deserialization pipeline between the native (Zig) and JVM (Java) layers, details the binary layout mappings, and proposes a complete Feature Inventory and E2E unit testing plan (Tiers 1-4).

---

## 1. Observation

Based on a direct inspection of the codebase in `/Volumes/realme/Dev/termux-ghostty`, the snapshot mechanism uses JNI direct byte buffers to serialize terminal frames in Zig and deserialize them in Java.

### Exact File Paths & Code Segments Identified
1. **Java Deserialization Source**: `/Volumes/realme/Dev/termux-ghostty/terminal-emulator/src/main/java/com/termux/terminal/ScreenSnapshot.java`
   - **Line 15**: Defines native snapshot magic: `private static final int NATIVE_SNAPSHOT_MAGIC = 0x54475832;`
   - **Lines 386-396**: Reads the global header:
     ```java
     int magic = buffer.getInt();
     if (magic != NATIVE_SNAPSHOT_MAGIC) {
         throw new IllegalStateException("Unexpected native snapshot magic: 0x" + Integer.toHexString(magic));
     }
     int topRow = buffer.getInt();
     int rows = buffer.getInt();
     int columns = buffer.getInt();
     int flags = buffer.getInt();
     int dirtyRowCount = buffer.getInt();
     int metadataFlags = buffer.getInt();
     ```
   - **Lines 449-489**: Current interleaved cell parsing loop:
     ```java
     int payloadRowCount = mFullRebuild ? rows : dirtyRowCount;
     for (int payloadIndex = 0; payloadIndex < payloadRowCount; payloadIndex++) {
         int rowIndex = mFullRebuild ? payloadIndex : mDirtyRows[payloadIndex];
         int charsUsed = buffer.getInt();
         boolean lineWrap = buffer.getInt() != 0;
         if (charsUsed < 0) {
             throw new IllegalStateException("charsUsed must be >= 0");
         }

         RowSnapshot row = mRowsData[rowIndex];
         row.beginNative(charsUsed, columns, lineWrap);
         for (int column = 0; column < columns; column++) {
             int textStart = buffer.getInt();
             short textLength = buffer.getShort();
             byte displayWidth = buffer.get();
             buffer.get();
             long style = buffer.getLong();
             ...
         }
         for (int charIndex = 0; charIndex < charsUsed; charIndex++) {
             row.mText[charIndex] = buffer.getChar();
         }
         row.finishNative();
     }
     ```

2. **Zig Native Serialization Source**: `/Volumes/realme/Dev/termux-ghostty/terminal-emulator/src/main/zig/src/termux_ghostty.zig`
   - **Lines 44-53**: Snapshot Constants:
     ```zig
     const snapshot_magic: u32 = 0x54475832;
     const snapshot_flag_full_rebuild: u32 = 1 << 0;
     const snapshot_metadata_palette: u32 = 1 << 0;
     const snapshot_metadata_render: u32 = 1 << 1;
     const snapshot_metadata_mode_bits: u32 = 1 << 2;
     const snapshot_header_bytes: usize = @sizeOf(u32) + @sizeOf(i32) + (5 * @sizeOf(u32));
     const snapshot_render_metadata_bytes: usize = (3 * @sizeOf(i32)) + (2 * @sizeOf(u32));
     const snapshot_mode_bits_bytes: usize = @sizeOf(u32);
     const snapshot_row_header_bytes: usize = 2 * @sizeOf(u32);
     const snapshot_cell_bytes: usize = @sizeOf(i32) + @sizeOf(u16) + 2 + @sizeOf(u64);
     ```
   - **Lines 1851-1869**: Serializes global header and metadata block pointers:
     ```zig
     var writer = BufferWriter.init(out);
     writer.writeU32(snapshot_magic) catch return -1;
     writer.writeI32(top_row) catch return -1;
     writer.writeU32(handle.render_state.rows) catch return -1;
     writer.writeU32(handle.render_state.cols) catch return -1;
     writer.writeU32(snapshot_flags) catch return -1;
     writer.writeU32(partial_dirty_rows) catch return -1;
     writer.writeU32(snapshot_metadata.flags) catch return -1;
     ```
   - **Lines 615-626**: Writes individual cell values in interleaved format:
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

3. **Frame Cache Testing Source**: `/Volumes/realme/Dev/termux-ghostty/terminal-emulator/src/test/java/com/termux/terminal/RenderFrameCacheTest.java`
   - **Lines 105-139**: Manually populates the interleaved byte buffer structure to simulate native snapshots:
     ```java
     ByteBuffer buffer = snapshot.getBuffer().order(ByteOrder.nativeOrder());
     buffer.clear();
     buffer.putInt(SNAPSHOT_MAGIC);
     buffer.putInt(topRow);
     buffer.putInt(rows);
     buffer.putInt(columns);
     ...
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

4. **Viewport Link Deserialization Source**: `/Volumes/realme/Dev/termux-ghostty/terminal-emulator/src/main/java/com/termux/terminal/ViewportLinkSnapshot.java`
   - **Line 11**: Defines viewport link magic: `private static final int NATIVE_SNAPSHOT_MAGIC = 0x54474c31;`
   - **Lines 138-218**: Parses native-serialized OSC8 hyperlinks:
     ```java
     int magic = buffer.getInt();
     int topRow = buffer.getInt();
     int rows = buffer.getInt();
     int columns = buffer.getInt();
     int segmentCount = buffer.getInt();
     int stringTableBytes = buffer.getInt();
     ...
     for (int index = 0; index < segmentCount; index++) {
         int row = buffer.getInt();
         int startColumn = buffer.getInt();
         int endColumnExclusive = buffer.getInt();
         int stringOffset = buffer.getInt();
         int stringLength = buffer.getInt();
         ...
     }
     byte[] stringTable = new byte[stringTableBytes];
     buffer.get(stringTable);
     ```

---

## 2. Logic Chain

The current system implementation is based on an **interleaved cell layout** format. This format incurs high JVM/JNI crossing and bounds-checking overhead because `ByteBuffer` methods (`getInt()`, `getShort()`, `get()`, `getLong()`) are called cell-by-cell in a loop on the Java side.

According to `PROJECT.md`, the pipeline optimization targets a **contiguous layout format** to allow zero-copy bulk reads using Java NIO (`asIntBuffer()`, `asShortBuffer()`, `asLongBuffer()`, `get(byte[])`):
1. **Transition path**: We must shift the native Zig serializer (`termux_ghostty.zig`) to group all Starts, Lengths, Widths, and Styles contiguously per row instead of interleaving them per column.
2. **Alignment & Padding Requirements**: To prevent unaligned memory access crashes on architectures like ARM64, the proposed styles array starts at an 8-byte aligned boundary.
   - For a given column count, the offset to the starts, lengths, and widths takes `columns * 7` bytes.
   - Since the header is 8 bytes, the offset is `8 + (columns * 7)` bytes.
   - The required padding to align the Styles array to an 8-byte boundary is calculated as `padding = (8 - (columns * 7) % 8) % 8`.
   - Additionally, the next row payload must start at an 8-byte aligned address. This requires aligning the write pointer after serializing each row payload.
3. **NIO Optimization**: In `ScreenSnapshot.java`, loops can be replaced by bulk array transfer calls (`IntBuffer.get()`, `ShortBuffer.get()`, `LongBuffer.get()`, `ByteBuffer.get()`) which are optimized by the JVM into native memory transfers.

---

## 3. Caveats

- **Network Constraints**: Operating in `CODE_ONLY` mode; no external networking was done.
- **Interoperability**: The legacy Java-only fallback pipeline (`beginJavaSnapshot`, `setRow`) does not use direct buffers. Care must be taken that optimizations only target the `parseNativeSnapshot()` path.
- **Hardware Endianness**: Direct byte buffer allocations must explicitly use `.order(ByteOrder.nativeOrder())` on both sides to prevent endianness mismatches during bulk conversions.

---

## 4. Conclusion

The E2E testing track (Milestone 1) requires defining a comprehensive feature inventory and test plan to validate the transition from interleaved serialization to contiguous bulk NIO deserialization. Implementing this structure will allow for complete validation of correct parsing, alignment padding, partial rebuilds, resizing, and adversarial robustness.

---

## 5. Verification Method

Existing JUnit tests can be executed via Gradle:
```zsh
./gradlew :terminal-emulator:test
```
To verify the implementation of Milestones 2 and 3 in the future, we will inspect:
1. `ScreenSnapshot.java`: ensuring no per-column loop reads occur, and bulk JNI transfers are utilized.
2. `termux_ghostty.zig`: verifying alignment padding logic using `alignTo(8)` at the end of each row payload.

---

## 6. Proposing E2E Feature Inventory & Tiers 1-4 Test Plan

### Feature Inventory (18 Features)

| ID | Feature Name | Description |
|----|--------------|-------------|
| F01 | Magic Header & Version Guard | Writing and validating the `0x54475832` signature to verify protocol compatibility. |
| F02 | Global Viewport Sync | Synchronizing `topRow`, `rows`, `columns`, and layout state flags between native/JVM. |
| F03 | Color Palette Serialization | Encoding the 259 indexed ARGB terminal colors to JVM cached palette array. |
| F04 | Render Cursor State Sync | Syncing cursor column, row, styling, and visibility flags. |
| F05 | Video Mode Bits Integration | Synchronizing application terminal modes (mouse, bracketed paste, etc.). |
| F06 | Line Wrapping Flag Sync | Propagating the wrap state of individual terminal rows. |
| F07 | Contiguous Starts Array | Native grouping and bulk JVM reading of cell character start positions (`i32[]`). |
| F08 | Contiguous Lengths Array | Native grouping and bulk JVM reading of cell character lengths (`u16[]`/`short[]`). |
| F09 | Contiguous Widths Array | Native grouping and bulk JVM reading of cell display widths (`u8[]`/`byte[]`). |
| F10 | 8-Byte Alignment Padding | Injecting dummy padding bytes (0 to 7) to align the Styles array within row payloads. |
| F11 | Contiguous Styles Array | Native grouping and bulk JVM reading of cell styling flags (`u64[]`/`long[]`). |
| F12 | UTF-16 Text Bulk Extraction | Zero-copy retrieval of characters using bulk character buffers. |
| F13 | Incremental Dirty Row Writing | Serializing only modified row indices to minimize JNI data transfers. |
| F14 | Differential Cache Application | Application of incremental row updates to the frame cache (`RenderFrameCache`). |
| F15 | Viewport Scrolling/Shifting | Correct shifting of cached rows without complete buffer re-deserialization. |
| F16 | Resize/Full Rebuild Transition | Fallback to full refresh on window resize or size changes. |
| F17 | Viewport Link Header & Magic | Validation of OSC8 viewport link snapshot signature `0x54474c31`. |
| F18 | Viewport Link String Table Intern | Deduplication and serialization of OSC8 hyperlink URLs using a string table. |

---

### E2E Test Suite Specification (Tiers 1-4)

```
E2E Test Suite Structure
├── Tier 1: Core Codec & Binary Layout Unit Tests
├── Tier 2: Global State, Metadata & Flow Control Tests
├── Tier 3: Incremental Updates & Cache Integration Tests
└── Tier 4: Robustness, Security & Adversarial Input Tests
```

#### Tier 1: Core Codec & Binary Layout Unit Tests

* **Test T1.1: Single-Row Basic ASCII Serialization**
  - **Intent**: Validate serialization and bulk deserialization of a standard ASCII line under the new contiguous layout.
  - **Method**: Set up a row with "Hello, World!" and styling. Serialize to buffer using contiguous format. Read using Java NIO and assert row content and cell styles match.

* **Test T1.2: Empty Row Layout Verification**
  - **Intent**: Validate behavior when a row contains zero characters.
  - **Method**: Serialize a row where `charsUsed = 0`. Verify that start indices are zero, lengths are zero, and styles match defaults.

* **Test T1.3: Alignment Padding Multi-Column Verification**
  - **Intent**: Ensure alignment calculations inject correct padding bytes.
  - **Method**: Test columns sizes `80` (requires 0 bytes padding), `83` (requires 3 bytes padding), and `87` (requires 7 bytes padding). Verify that the styles array starts at an 8-byte aligned index relative to the row payload start.

* **Test T1.4: Surrogate Pairs & Combining Characters (UTF-16)**
  - **Intent**: Confirm that surrogate pairs (display width 2, char count 2) and combining characters (display width 0, char count 1) are serialized correctly.
  - **Method**: Populate cell with surrogate pair (e.g., Emoji) and combining diacritic. Verify `mCellTextLength` equals 2 and 3, and `displayWidth` is correctly mapped.

* **Test T1.5: Style Encoding Verification**
  - **Intent**: Verify that text attributes (bold, inverse, truecolor RGB foreground/background) map perfectly.
  - **Method**: Set cell style with `bold | italic` and truecolor RGB. Verify matching bit flag values in `mStyle`.

* **Test T1.6: Viewport Link String Interning**
  - **Intent**: Test that multiple cells sharing the same hyperlink URI deduplicate in the OSC8 string table.
  - **Method**: Create viewport link segments with repeating URIs. Assert the serialized string table contains only unique strings and offsets map correctly.

---

#### Tier 2: Global State, Metadata & Flow Control Tests

* **Test T2.1: Full Rebuild Snapshot Parse**
  - **Intent**: Verify full screen refresh parsing from scratch.
  - **Method**: Populate complete screen snapshot with global metadata and all rows. Verify `isFullRebuild() == true` on Java side, and all rows data correctly match the test input.

* **Test T2.2: Color Palette Metadata Parsing**
  - **Intent**: Validate palette synchronization block triggers and updates `mPalette`.
  - **Method**: Enable palette update flag, serialize 259 colors. Verify Java detects palette update and copies all 259 colors correctly.

* **Test T2.3: Cursor Metadata Sync**
  - **Intent**: Validate cursor visibility and styles rendering metadata.
  - **Method**: Set cursor styles, visibility, and coordinates. Verify `isCursorVisible()` and `getCursorStyle()` reflect the correct state.

* **Test T2.4: Mode Bits Sync**
  - **Intent**: Verify terminal modes like bracketed paste, mouse tracking flags are propagated.
  - **Method**: Set mode flags in Zig and verify `getModeBits()` values in Java.

* **Test T2.5: Resize Dimension Change Handling**
  - **Intent**: Validate resize validation rejects mismatched partial snapshots.
  - **Method**: Initialize cache with `63` columns. Apply a partial frame delta with `64` columns. Assert that the cache rejects the delta with `REJECTED_PARTIAL_REQUIRING_FULL_REBUILD`.

---

#### Tier 3: Incremental Updates & Cache Integration Tests

* **Test T3.1: Partial Frame Updates**
  - **Intent**: Test that only marked dirty rows are parsed and updated in the Cache snapshot.
  - **Method**: Update row index `1` in a multi-row layout. Verify that the snapshot payload size only contains the row header and row `1` data. Apply delta to `RenderFrameCache` and verify only row `1` is updated, while row `0` remains unmodified.

* **Test T3.2: Viewport Scroll Row Shifting**
  - **Intent**: Test row shifting mechanics inside `RenderFrameCache` during scrolling.
  - **Method**: Scroll screen down by `1` row. Apply a partial delta with shifted top row coordinate. Verify that cached row `0` now contains the previous contents of row `1`.

* **Test T3.3: Sequence Gap Recovery**
  - **Intent**: Verify sequence gaps trigger cache reinitialization.
  - **Method**: Apply frame `1`, then attempt to apply frame `3` (partial update). Verify cache rejects frame `3` with `REJECTED_SEQUENCE_GAP`.

---

#### Tier 4: Robustness, Security & Adversarial Input Tests

* **Test T4.1: Magic Bytes Mismatch Handling**
  - **Intent**: Ensure corrupted headers fail safely.
  - **Method**: Corrupt the first 4 bytes of direct byte buffer. Assert that calling `markNativeSnapshot()` throws `IllegalStateException`.

* **Test T4.2: Buffer Underflow Protection**
  - **Intent**: Prevent memory out-of-bounds reading when reading truncated payloads.
  - **Method**: Set `requiredBytes` to 500, but only write 200 bytes into the buffer. Call `markNativeSnapshot()` and verify it throws a safe exception (e.g., `BufferUnderflowException`).

* **Test T4.3: Out of Bounds Dirty Row Index**
  - **Intent**: Guard against indexing issues.
  - **Method**: Set `rows = 5`, but set a dirty row index to `10`. Verify validation throws `IllegalStateException`.

* **Test T4.4: Corrupted Cell Text Start Range**
  - **Intent**: Protect against malicious cell start offsets that point outside `charsUsed`.
  - **Method**: Set `charsUsed = 5`, but set a column's `textStart` to `10`. Verify validation detects out-of-bounds and throws.

* **Test T4.5: Invalid Display Width Reject**
  - **Intent**: Verify display width bounds checking.
  - **Method**: Set column width to `3` (maximum allowed is 2). Verify validation catches this and throws.

* **Test T4.6: Viewport Link Segment Table Overflow Protection**
  - **Intent**: Prevent segment URL parsing issues from corrupted offsets.
  - **Method**: Set segment `stringOffset` larger than `stringTableBytes`. Verify `ViewportLinkSnapshot` parsing throws `IllegalStateException`.
