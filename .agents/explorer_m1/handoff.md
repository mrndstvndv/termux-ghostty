# Handoff Report: Native Serialization & Sizing Optimization Explorer (Milestone 1)

## 1. Observation
I analyzed the snapshot serialization and sizing functions in `terminal-emulator/src/main/zig/src/termux_ghostty.zig`. Below are the exact file paths, line ranges, and observed code patterns:

*   **Cell Size Constants** (Lines 52-53):
    ```zig
    const snapshot_row_header_bytes: usize = 2 * @sizeOf(u32);
    const snapshot_cell_bytes: usize = @sizeOf(i32) + @sizeOf(u16) + 2 + @sizeOf(u64);
    ```
*   **Frame Capacity Calculation** (Lines 480–503):
    Calculates size in `snapshotRequiredBytes` without any alignment padding between consecutive row payloads.
*   **Row Capacity Calculation** (Lines 505–534):
    In `snapshotRowRequiredBytes`, capacity is calculated assuming interleaved cell structures:
    ```zig
    var total: usize = snapshot_row_header_bytes + (cols * snapshot_cell_bytes);
    ```
*   **Row Serialization** (Lines 536–627):
    In `writeSnapshotRow`, cell layout fields are interleaved per column within a single loop:
    ```zig
    for (0..self.render_state.cols) |column| {
        try writer.writeI32(self.scratch_cell_starts.items[column]);
        try writer.writeU16(self.scratch_cell_lengths.items[column]);
        try writer.writeU8(self.scratch_cell_widths.items[column]);
        try writer.writeU8(0);
        try writer.writeU64(self.scratch_cell_styles.items[column]);
    }
    ```
*   **Java Deserialization** (`terminal-emulator/src/main/java/com/termux/terminal/ScreenSnapshot.java`, Lines 460–483):
    Reads each cell's fields interleaved inside a loop:
    ```java
    for (int column = 0; column < columns; column++) {
        int textStart = buffer.getInt();
        short textLength = buffer.getShort();
        byte displayWidth = buffer.get();
        buffer.get();
        long style = buffer.getLong();
        ...
    }
    ```

---

## 2. Logic Chain
1.  **Interleaved Layout Overhead**: The current layout serializes all fields (`start`, `length`, `width`, `style`) together for one cell before moving to the next cell. This forces the Java deserialization side to perform individual element-by-element buffer lookups (`getInt`, `getShort`, `get`, `getLong`) inside a nested column loop. This results in heavy virtual method call overhead and poor cache locality.
2.  **Contiguous Arrays Solution**: Grouping each field type into its own contiguous array per row allows Java to use NIO bulk copy operations (e.g. `buffer.asIntBuffer().get(startsArray, 0, columns)`).
3.  **Style Alignment Requirements**: On the JVM side, reading a `long` array (`u64` in Zig) in bulk via `asLongBuffer()` requires the buffer position to be aligned to an 8-byte boundary. 
4.  **Style Padding Computation**:
    *   The row header occupies 8 bytes (two `u32` fields: `charsUsed` and `wrap`).
    *   The Starts array occupies `columns * 4` bytes.
    *   The Lengths array occupies `columns * 2` bytes.
    *   The Widths array occupies `columns * 1` bytes.
    *   This gives a base offset of `8 + columns * 7` bytes relative to the start of the row.
    *   To make the `Styles` array 8-byte aligned, we must pad the offset to the next multiple of 8: `inner_padding = (8 - ((8 + columns * 7) % 8)) % 8`.
5.  **Next Row Alignment**: To ensure each row payload starts on an 8-byte aligned boundary, both the size calculation in `snapshotRequiredBytes` and the writing loop in `fillSnapshotCurrentViewport` must round the current offset up to the nearest multiple of 8 before processing the next row: `total = (total + 7) & ~@as(usize, 7)`.

---

## 3. Caveats
*   **Read-only scope**: This is a read-only investigation. No modifications have been made to the repository source code.
*   **Coordinated Deployment Required**: The change changes the binary protocol format. If only the Zig side is updated without the Java side (or vice versa), it will cause BufferUnderflow / BufferOverflow exceptions or corrupt screen rendering.

---

## 4. Conclusion
We have identified the exact line ranges and proposed changes needed for Milestone 1. The implementation strategy involves:
1.  Removing `snapshot_cell_bytes` in `termux_ghostty.zig`.
2.  Updating size estimation in `snapshotRowRequiredBytes` and `snapshotRequiredBytes` to account for inner style padding (`(8 - ((8 + cols * 7) % 8)) % 8`) and row alignment.
3.  Updating the writing loop in `writeSnapshotRow` and row padding in `fillSnapshotCurrentViewport`.
4.  Updating `ScreenSnapshot.java` to perform bulk NIO operations with corresponding alignment skips.

A detailed description of the proposed code changes has been written to `analysis.md`.

---

## 5. Verification Method
1.  **Project Compile/Test Check**: Run `./gradlew test` to ensure that the project is in a compiling and healthy state prior to the changes.
2.  **Implementation Verification**:
    *   Inspect `analysis.md` for exact code snippets.
    *   Once implemented by the Implementer, verify that unit tests pass and snapshot decoding functions correctly without throwing `BufferUnderflowException` or rendering visual artifacts.
