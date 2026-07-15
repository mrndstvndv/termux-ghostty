# Handoff Report: E2E Exploration of Snapshot Serialization/Deserialization

This report details the exploration of snapshot serialization/deserialization features, analyzes the old interleaved format vs. the proposed contiguous binary layout format, provides the Feature Inventory, and proposes a 4-Tier test suite structure for the optimized pipeline.

---

## 1. Observation

We directly investigated and verified the following sources:
1. **JVM Deserialization**: `terminal-emulator/src/main/java/com/termux/terminal/ScreenSnapshot.java`
   - In lines 450-489, the deserializer iterates column-by-column, parsing interleaved cell structures:
     ```java
     int payloadRowCount = mFullRebuild ? rows : dirtyRowCount;
     for (int payloadIndex = 0; payloadIndex < payloadRowCount; payloadIndex++) {
         int rowIndex = mFullRebuild ? payloadIndex : mDirtyRows[payloadIndex];
         int charsUsed = buffer.getInt();
         boolean lineWrap = buffer.getInt() != 0;
         ...
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
2. **Native Zig Serialization**: `terminal-emulator/src/main/zig/src/termux_ghostty.zig`
   - In lines 615-627, row payloads are serialized cell-by-cell in matching interleaved structure:
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
3. **Mocks in Test Directory**: `terminal-emulator/src/test/java/com/termux/terminal/RenderFrameCacheTest.java`
   - In lines 129-139, the mock snapshot generator constructs the binary layout by hand matching the interleaved format:
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
4. **Interface Contracts**: `PROJECT.md`
   - Section *Interface Contracts (Binary Layout per Row)* specifies the new layout:
     - UTF-16 length: `u32` (4 bytes)
     - Line wrap flag: `u32` (4 bytes)
     - Contiguous Cell Starts Array: `columns * i32`
     - Contiguous Cell Lengths Array: `columns * u16`
     - Contiguous Cell Widths Array: `columns * u8`
     - 8-Byte Alignment Padding: `0 to 7` dummy bytes to align the styles array offset to a multiple of 8 bytes.
     - Contiguous Cell Styles Array: `columns * u64`
     - UTF-16 Characters Array: `charsUsed * u16`
5. **Optimization & Alignment Plans**:
   - `plans/contiguous_field_buffering_plan_audited.md` specifies the exact alignment calculations:
     - **Inner Alignment Padding** (to align styles offset to a multiple of 8):
       - Offset before padding: `8 + columns * 7`
       - Padding bytes = `(8 - ((8 + columns * 7) % 8)) % 8` which simplifies to `columns % 8` bytes.
     - **Outer Alignment Padding** (to align row starts to a multiple of 8):
       - Position before starting the next row is rounded up to the nearest multiple of 8: `(current_offset + 7) & ~7`.
   - `docs/fork/plans/rendering_optimizations.md` proposed three optimizations:
     - Optimization 1: Contiguous Field Buffering (bulk NIO transfers like `buffer.asIntBuffer().get(...)` instead of O(rows * cols * 5) virtual method calls).
     - Optimization 2: Bulk Character Copying (`buffer.asCharBuffer().get(...)`).
     - Optimization 3: Native-Side FNV-1a Hash Calculation (offload hash loop to Zig, write as `u64` in row header, read in Java).

---

## 2. Logic Chain

1. **Current Pipeline Performance Drawbacks**: The current implementation of `ScreenSnapshot.java` deserializes each row by sequentially making `getInt()`, `getShort()`, `get()`, and `getLong()` JNI/NIO methods per cell (16,000 method calls for an 80x40 viewport).
2. **Contiguous Buffer Optimization**: Grouping matching attributes (starts, lengths, widths, styles) into contiguous blocks allows the JVM to execute bulk array reads (e.g. `buffer.asIntBuffer().get(row.mCellTextStart, 0, columns)`). Under the hood, this translates to native `memcpy` operations, reducing JVM method calls to O(rows * 4) and bypassing per-cell bounds/endianness validation.
3. **8-Byte Memory Alignment Necessity**: On ARM and x86 architectures, reading 64-bit `long` styles via bulk NIO (or Unsafe) from unaligned memory offsets can cause CPU execution slowdowns or trigger `UnalignedAccess` exceptions.
4. **Alignment Rules**:
   - Every row start offset must be aligned to a multiple of 8 bytes from the start of the buffer.
   - The cell styles array offset within each row payload must also be aligned to a multiple of 8 bytes. Because the row start is a multiple of 8, this simplifies to ensuring the relative offset of the styles array within the row payload is a multiple of 8.
   - The size of the row payload before the style array is `8 (headers) + columns * 7`. Thus, we need `(8 - (columns * 7) % 8) % 8` dummy padding bytes (which is exactly `columns % 8` bytes) before writing the styles array.
5. **Hash Offloading**: Offloading the FNV-1a calculation to Zig allows the high-performance native compiler to optimize and vectorize the hashing loop, eliminating the expensive Java-side loop over all cells.

---

## 3. Caveats

- **Hash Calculation in Audited Plan**: `plans/contiguous_field_buffering_plan_audited.md` does not write the `ContentHash` in the Zig serialization code sketch or read it in the Java deserializer sketch, instead maintaining the JVM-side `updateContentHash()` or relying on `finishNative()`. However, `docs/fork/plans/rendering_optimizations.md` lists optimization 3 (Native-Side FNV-1a Hash Calculation) as writing the hash in the row's header. If the hash is included in the row header, the row header becomes 16 bytes (length: `u32` (4), wrap: `u32` (4), hash: `u64` (8)), and the offset of the Starts array is 16. Since 16 is a multiple of 8, the padding calculation remains `columns % 8` bytes. The implementation must reconcile whether to implement Optimization 3.
- **RenderFrameCacheTest Mock Updating**: Tests in `RenderFrameCacheTest` manually generate binary buffer frames in the old interleaved style. Modifying `ScreenSnapshot.java` to parse contiguous layouts will break these tests unless `RenderFrameCacheTest`'s `writeBlankRow()` helper is updated to write the new contiguous format.

---

## 4. Conclusion

The transition to contiguous field buffering requires coordinated updates in `termux_ghostty.zig` (native sizing, layout writing, and padding logic), `ScreenSnapshot.java` (bulk NIO transfers, offset alignment, and validation), and `RenderFrameCacheTest.java` (mock formatting).

Below is the proposed **Feature Inventory** (12 features) and **Test Suite Matrix** (Tiers 1-4) to implement and validate the pipeline.

### Feature Inventory (12 Features)

| ID | Feature | Description | Source File / Lines |
|---|---|---|---|
| **F-01** | **Direct JNI Buffer Allocation & Bounds Control** | Capacity allocation (`DEFAULT_CAPACITY_BYTES`), resize detection, and bounds overflow prevention (`IllegalStateException`). | `ScreenSnapshot.java:9-10, 48-58, 88-99` |
| **F-02** | **Viewport & Mode Metadata Sync** | Serialization of cursor properties (`cursor_col`, `cursor_row`, style, visibility), mode bits (mouse tracking, bracketed paste), and top row index. | `termux_ghostty.zig:16-28, 437-478, 1851-1870` |
| **F-03** | **Dynamic Palette Mapping** | Encoding and sync of 259 colors from Zig RGB to Java ARGB, triggered dynamically via metadata flags. | `termux_ghostty.zig:459-462, 2329-2348` |
| **F-04** | **Rebuild Flow Control (Full vs Partial)** | Flow logic to either serialize all rows (Full Rebuild) or write a list of index headers and row payloads (Partial Update). | `termux_ghostty.zig:336-364, 1871-1880` |
| **F-05** | **Outer Row Alignment (8-Byte Boundary)** | Dynamically padding the buffer writer offset to a multiple of 8 bytes before starting each row payload. | `plans/contiguous_field_buffering_plan_audited.md:92-99` |
| **F-06** | **Row Header Length & Wrap Encoding** | Writing and reading of UTF-16 character length (`charsUsed`) and line wrap flag (`lineWrap`). | `termux_ghostty.zig:615-616` |
| **F-07** | **Contiguous Cell Starts Array** | Block serialization and bulk reading of start index offsets (`columns * i32`). | `plans/contiguous_field_buffering_plan_audited.md:163-165` |
| **F-08** | **Contiguous Cell Lengths Array** | Block serialization and bulk reading of cell character lengths (`columns * u16`). | `plans/contiguous_field_buffering_plan_audited.md:167-169` |
| **F-09** | **Contiguous Cell Widths Array** | Block serialization and bulk reading of cell display widths (`columns * u8`). | `plans/contiguous_field_buffering_plan_audited.md:171-172` |
| **F-10** | **Style Alignment Padding (Inner Padding)** | Writing and skipping of `columns % 8` alignment dummy bytes to place styles at 8-byte aligned offsets. | `plans/contiguous_field_buffering_plan_audited.md:112-119, 174-175` |
| **F-11** | **Contiguous Cell Styles Array** | Block serialization and bulk reading of style attribute bitmasks (`columns * u64`) containing color indexes / 24-bit color + effect flags. | `plans/contiguous_field_buffering_plan_audited.md:177-179` |
| **F-12** | **Characters Array (UTF-16 Copy)** | Block serialization and bulk reading of char array code units (`charsUsed * u16`). | `plans/contiguous_field_buffering_plan_audited.md:181-183` |

---

### Proposed Test Suite (Tiers 1-4)

#### Tier 1: Unit & Component Helper Tests
*Focus: Validation of standalone functions, math calculations, and flag bitwise mapping.*
- **Test Case 1.1: Inner and Outer Alignment Calculations**
  - Verify that padding bytes calculated for `columns` match `columns % 8` (inner padding) across various grid widths (e.g. 1, 8, 80, 81, 120 columns).
  - Verify that the row-start alignment function correctly rounds up buffer offset positions to multiples of 8.
- **Test Case 1.2: Style Bitwise Encoding & Colors**
  - Verify that `encodeTermuxStyle` in Zig and `TextStyle.encode` / `decode` in Java map colors (both indexed and 24-bit TrueColor) and text effect attributes (bold, italic, blink, underline, dim, strikethrough, etc.) to the exact same 64-bit bits.

#### Tier 2: Contiguous Serialization Roundtrip Tests
*Focus: Byte-perfect validation of serialization/deserialization loops.*
- **Test Case 2.1: Single Row Roundtrip (Contiguous Layout)**
  - Serialize a mock row with varied cell structures (empty, default cells, style cells) using the new layout into a direct ByteBuffer.
  - Deserialize the row using `ScreenSnapshot`'s new contiguous layout parser.
  - Assert that all cells in the resulting `RowSnapshot` contain the identical starts, lengths, widths, styles, and text characters.
- **Test Case 2.2: Character Arrays & Multi-byte/Wide Codepoints**
  - Perform roundtrips on rows containing wide characters (display width 2), spacers (display width 0), and multi-unit surrogate pairs (e.g. emoji).
  - Verify character boundaries, length tracking, and alignment padding do not corrupt subsequent row data.
- **Test Case 2.3: Multiple Row Payloads Alignment**
  - Serialize a frame with 3 or more dirty rows of different sizes.
  - Assert that every row starts at a buffer index that is a multiple of 8.
  - Verify that the deserializer successfully skips padding bytes and parses all rows correctly.

#### Tier 3: Viewport, Resizing & Scroll Tests
*Focus: System-level state changes and integration behaviors.*
- **Test Case 3.1: Rebuilt Viewport Dimensions (Resizing)**
  - Simulate a resize operation (e.g., column count shifts from 80 to 120).
  - Verify that a `fullRebuild` snapshot correctly allocates the new sizes.
  - Verify that `RenderFrameCache` rejects mismatched dimensions in partial snapshots and triggers a full refresh as expected.
- **Test Case 3.2: Partial Update Sync & Skipping**
  - Create a snapshot containing 10 rows where only rows 2 and 5 are marked dirty.
  - Verify that only rows 2 and 5 are serialized.
  - Verify that the deserializer parses only rows 2 and 5, leaving the cached state of other rows intact.
- **Test Case 3.3: Metadata Flag Processing**
  - Test transitions in palette colors, cursor positions, mode bits, and viewport top row changes.
  - Ensure corresponding metadata update flags are set, written, and parsed correctly.

#### Tier 4: Adversarial & Performance Tests
*Focus: Stress-testing boundaries, invalid buffers, security assertions, and performance profiling.*
- **Test Case 4.1: Buffer Sizing and Overflow Hardening**
  - Trigger serialization with a direct buffer that is smaller than the estimated `snapshotRequiredBytes`.
  - Assert that Zig serialization fails gracefully with `error.NoSpaceLeft` or returning needed capacity, and JVM handles it without crashing the native runtime.
- **Test Case 4.2: Payload Fuzzing & Malformed Bounds Protection**
  - Mutate serialized bytes manually (e.g., change `charsUsed` to negative, point cell starts outside `charsUsed`, set display widths to > 2).
  - Assert that `validateNativeRow` on the JVM side catches these bounds violations and throws an `IllegalStateException` to prevent memory corruption or buffer overflows.
- **Test Case 4.3: Bulk NIO Performance Benchmark**
  - Run a microbenchmark comparing the old loop-based interleaved parser with the new bulk contiguous view parser.
  - Assert a significant reduction in total execution time and zero garbage collector pressure.

---

## 5. Verification Method

1. **Verify Source Locations**:
   - Direct JNI reads can be inspected in `ScreenSnapshot.java` under the target method `parseNativeSnapshot()`.
   - Native cell array writes are located in `termux_ghostty.zig` inside the `writeSnapshotRow` function.
2. **Execute Tests**:
   - Run the unit tests via Gradle:
     ```zsh
     ./gradlew :terminal-emulator:testDebugUnitTest
     ```
   - Observe that the existing baseline test suite completes successfully.
