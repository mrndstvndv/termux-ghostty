# Scope: Snapshot Deserialization Pipeline Optimization - Implementation Track

## Architecture
- **Native (Zig) Side**: `Session` handles viewport state. Serialization runs in `fillSnapshotCurrentViewport` and `writeSnapshotRow` in `termux_ghostty.zig`. Writes row and viewport payloads to a direct JNI `ByteBuffer`.
- **JVM (Java) Side**: `ScreenSnapshot` parses the native-allocated direct `ByteBuffer` in `parseNativeSnapshot()` in `ScreenSnapshot.java`.
- **Communications**: Fast IPC via shared JNI direct byte buffer. Contiguous array optimizations avoid per-cell JNI/JVM method call overhead.

## Milestones
| # | Name | Scope | Dependencies | Status |
|---|------|-------|-------------|--------|
| 1 | Native Serialization & Sizing | Optimize native snapshot serialization loop & size estimation in `termux_ghostty.zig` (contiguous layouts, padding, 8-byte alignment). | None | IN_PROGRESS |
| 2 | JVM Deserialization & Mock Update | Update JVM parsing in `ScreenSnapshot.java` (bulk NIO) and the mock writer in `RenderFrameCacheTest.java`. | M1 | PLANNED |
| 3 | E2E Integration & Verification | Integrate changes and verify they pass E2E tests (polling for `TEST_READY.md`). | M1, M2 | PLANNED |
| 4 | Adversarial Coverage Hardening | White-box coverage analysis and adversarial testing using Challenger, Worker, and Reviewer. | M1, M2, M3 | PLANNED |

## Interface Contracts
### Zig Native ↔ Java JVM (Binary Layout per Row)
1. **UTF-16 length:** `u32` (4 bytes)
2. **Line wrap flag:** `u32` (4 bytes)
3. **Contiguous Cell Starts Array:** `columns * i32` (`columns * 4` bytes)
4. **Contiguous Cell Lengths Array:** `columns * u16` (`columns * 2` bytes)
5. **Contiguous Cell Widths Array:** `columns * u8` (`columns * 1` bytes)
6. **8-Byte Alignment Padding:** `0 to 7` dummy bytes to align the styles array offset to a multiple of 8 bytes.
7. **Contiguous Cell Styles Array:** `columns * u64` (`columns * 8` bytes)
8. **UTF-16 Characters Array:** `charsUsed * u16` (`charsUsed * 2` bytes)

All dirty row payloads must start at an 8-byte aligned offset within the global snapshot buffer.

## Code Layout
- `terminal-emulator/src/main/zig/src/termux_ghostty.zig` (Native source)
- `terminal-emulator/src/main/java/com/termux/terminal/ScreenSnapshot.java` (JVM source)
- `terminal-emulator/src/test/java/com/termux/terminal/RenderFrameCacheTest.java` (Test source)
