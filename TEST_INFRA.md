# Test Infrastructure: termux-ghostty Screen Snapshot Boundary

This document outlines the test philosophy, feature inventory, and architecture of the test suite designed to validate the Screen Snapshot serialization and deserialization pipeline of `termux-ghostty`.

---

## Test Philosophy

Our testing strategy follows the **Minimal Gap Principle** and the **Four-Tier Testing Model**. The goal is to ensure a bug-free, memory-safe, and high-performance serialization/deserialization boundary between the native Zig runtime and the JVM Java environment.

1. **Verify State Reconstruction**: Every test ensures that state serialized in native-style memory can be perfectly reconstructed in Java, matching values, byte ordering, and alignment.
2. **Bounds & Alignment Hardening**: Deserializers must fail gracefully and throw safe exceptions rather than crash with memory segmentation faults, buffer overflows, or unaligned memory access exceptions on architectures like ARM64.
3. **Dual Layout Compatibility**: Tests must validate serialization/deserialization routines in both the **interleaved layout** (production baseline) and the proposed **contiguous layout** to ensure seamless forward/backward compatibility.
4. **Behavior-Based Coverage**: Test cases test distinct behaviors and outcomes, avoiding dummy/facade checks.

---

## Feature Inventory (6 Core Features under Test)

The test suite validates 6 core features of the snapshot deserialization boundary:

### 1. Magic Header Validation (F1)
*   **Description**: Validates that incoming byte buffers carry the correct protocol signature (`TGX2` / `0x54475832`).
*   **Behavior**: Rejects corrupted, offset, or completely mismatched buffers with an `IllegalStateException` before attempting to read variable-length data.

### 2. Viewport Dimensions & Metadata Sync (F2)
*   **Description**: Syncs frame layout metadata (`topRow`, `rows`, `columns`, `flags`, `dirtyRowCount`, `metadataFlags`).
*   **Behavior**: Correctly updates snapshot layout properties and maps full-rebuild vs. incremental-partial redraw flags.

### 3. Cursor State Synchronization (F3)
*   **Description**: Syncs cursor location (`cursorCol`, `cursorRow`), cursor visibility, cursor style, and reverse video.
*   **Behavior**: Reflects the cursor's visual and styling properties accurately in the JVM frame snapshot.

### 4. Mode Bits Configuration (F4)
*   **Description**: Propagates global terminal mode bitmasks (e.g., mouse reporting, bracketed paste).
*   **Behavior**: Decodes bitwise options and stores them for query by the terminal UI components.

### 5. Contiguous Buffer Serialization Layout (F5)
*   **Description**: Tests serialization and deserialization layout modes (Interleaved vs. Contiguous).
*   **Behavior**: Serializes cell starts, lengths, widths, styles, and character arrays according to layout rules, inserting correct alignment padding bytes where necessary.

### 6. Out-of-Bounds & Malformed Input Hardening (F6)
*   **Description**: Guards the JNI/JVM boundary against malformed, fuzzed, or maliciously crafted payloads.
*   **Behavior**: Catches negative lengths, cell ranges pointing outside `charsUsed`, invalid display widths, and truncated buffers, throwing safe exceptions.

---

## Test Suite Architecture

The test suite is implemented in `ScreenSnapshotE2ETest.java` and follows a 4-tier organization to cover 71 test cases:

*   **Tier 1: Feature Coverage (30 Tests)**: Exercises the happy-path behavior of the 6 features under varying basic dimensions and valid setups (5 tests per feature).
*   **Tier 2: Boundary & Corner Cases (30 Tests)**: Exercises edge cases like zero rows/columns, maximum buffer sizes, wrapping boundaries, extreme cursor coordinates, and minimal text (5 tests per feature).
*   **Tier 3: Cross-Feature Combinations (6 Tests)**: Exercises combinations of feature interactions, such as scrolling with metadata updates, dirty rows with TrueColor styles, and mixed surrogate Unicode characters.
*   **Tier 4: Real-World Application Scenarios (5 Tests)**: Simulates actual app sequences like full terminal initialization, dense text rendering, terminal screen resizing, and incremental text updates.

---

## Verification Commands

To run the full unit and integration test suite:
```bash
./gradlew :terminal-emulator:test
```
or to run only the screen snapshot tests:
```bash
./gradlew :terminal-emulator:test --tests "com.termux.terminal.ScreenSnapshotE2ETest"
```
