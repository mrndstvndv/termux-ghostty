## 2026-07-15T13:12:37Z
You are the Explorer for Milestone 1: Native Serialization & Sizing Optimization.
Your task is to:
1. Analyze `terminal-emulator/src/main/zig/src/termux_ghostty.zig`.
2. Examine the current snapshot serialization loop (`fillSnapshotCurrentViewport`, `writeSnapshotRow`, `snapshotRowRequiredBytes`, `snapshotRequiredBytes`, etc.).
3. Identify how the cell layout is serialized and where styling, widths, lengths, starts, and character data are written.
4. Formulate an implementation strategy to serialize these fields as contiguous arrays per row, with:
   - Starts: `columns * i32`
   - Lengths: `columns * u16`
   - Widths: `columns * u8`
   - 8-byte padding to align the Style array
   - Styles: `columns * u64`
   - Chars: `charsUsed * u16`
   - 8-byte alignment for the start of the next row payload.
5. Provide precise line ranges and details. Write your findings to `analysis.md` in your working directory `/Volumes/realme/Dev/termux-ghostty/.agents/explorer_m1` and send a handoff message when done.
