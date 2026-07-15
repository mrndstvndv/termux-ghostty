# Context Details

## Target Codebase
- Project: `termux-ghostty`
- Module: `terminal-emulator`

## Key Source Files
- Native side: `terminal-emulator/src/main/zig/src/termux_ghostty.zig` (snapshot serialization & capacity calculations)
- JVM side: `terminal-emulator/src/main/java/com/termux/terminal/ScreenSnapshot.java` (snapshot deserialization)
- JVM test side: `terminal-emulator/src/test/java/com/termux/terminal/RenderFrameCacheTest.java` (mock binary data writing)

## Requirements
- Contiguous Native Serialization: Serialize layout fields (starts, lengths, widths, styles) contiguously instead of interleaving per-cell.
- Hardware 8-byte alignment: Dynamic padding before styles array and row starts.
- Dynamic Sizing: Update `snapshotRequiredBytes` and `snapshotRowRequiredBytes` to accurately predict sizes with alignment paddings.
- JVM Bulk NIO Operations: Read contiguous cell layout blocks using bulk get operations (e.g. `IntBuffer.get`, etc.), matching new binary structure.
- Safety: Run safety checks (`validateNativeRow`) successfully.
