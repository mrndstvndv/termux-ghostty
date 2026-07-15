# Context: termux-ghostty Snapshot Deserialization E2E Testing

## Objective
This sub-orchestrator manages the E2E Testing Track for the snapshot deserialization optimization in termux-ghostty. The goal is to define features, design a 4-tier test suite, write JUnit/E2E test files in `terminal-emulator/src/test/`, run them, verify they pass, and publish `TEST_READY.md`.

## Constraints
- Do not modify production sources (`ScreenSnapshot.java`, `termux_ghostty.zig`).
- Only modify test files.
- Ensure proper code layout and interface contracts are respected.
- Align with E2E Testing Track requirements from instructions.
