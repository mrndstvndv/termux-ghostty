## 2026-07-15T13:13:57Z
You are the Worker for Milestone 1: Native Serialization & Sizing Optimization.
Your task is to implement the native serialization & sizing changes in `terminal-emulator/src/main/zig/src/termux_ghostty.zig` according to the strategy formulated by the Explorer in `/Volumes/realme/Dev/termux-ghostty/.agents/explorer_m1/analysis.md`.

Specifically, you need to:
1. Modify `terminal-emulator/src/main/zig/src/termux_ghostty.zig` to change the row snapshot layout from interleaved cells to contiguous arrays per row.
2. Remove or adjust cell size constants that are no longer accurate.
3. Update `snapshotRowRequiredBytes` to compute the correct required bytes based on:
   - Header: 8 bytes
   - Starts: `cols * 4` bytes
   - Lengths: `cols * 2` bytes
   - Widths: `cols * 1` bytes
   - Styles padding: alignment offset to a multiple of 8 bytes
   - Styles: `cols * 8` bytes
   - Chars: `chars * 2` bytes
4. Update `snapshotRequiredBytes` to align the starting offset of each serialized row to an 8-byte boundary.
5. Update `writeSnapshotRow` to serialize:
   - `charsUsed` (u32)
   - `wrap` (u32)
   - Starts array (contiguous `cols * i32`)
   - Lengths array (contiguous `cols * u16`)
   - Widths array (contiguous `cols * u8`)
   - Style padding (aligning the writer offset to 8 bytes)
   - Styles array (contiguous `cols * u64`)
   - Chars array (contiguous `charsUsed * u16`)
6. Update `fillSnapshotCurrentViewport` to write padding bytes to align each row's start offset to an 8-byte boundary.
7. Perform compile check / build to verify these changes are correct on the Zig side. (Note: Since Java side is not yet modified, full tests might fail, but compiles on Zig side and individual unit tests should be verified).

MANDATORY INTEGRITY WARNING:
DO NOT CHEAT. All implementations must be genuine. DO NOT hardcode test results, create dummy/facade implementations, or circumvent the intended task. A Forensic Auditor will independently verify your work. Integrity violations WILL be detected and your work WILL be rejected.

Please write your implementation report detailing the changes, compilation command, and output to `handoff.md` in your working directory `/Volumes/realme/Dev/termux-ghostty/.agents/worker_m1/` and send a message when done.
