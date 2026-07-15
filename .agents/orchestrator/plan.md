# Snapshot Deserialization Optimization Plan

## Objective
Optimize the termux-ghostty snapshot deserialization pipeline to use contiguous field buffering (bulk NIO transfers) on the JVM and native Zig side.

## Steps

### Step 1: Initialize Project Metadata and Contracts
- Create `PROJECT.md` at the project root.
- Define architecture, code layout, interface contract, and milestones.

### Step 2: Spawn Dual Tracks
- Spawn **E2E Testing Track Orchestrator** in `.agents/sub_orch_e2e`.
- Spawn **Implementation Track Orchestrator** in `.agents/sub_orch_impl`.

### Step 3: E2E Testing Track (Parallel)
- Create `TEST_INFRA.md` at project root.
- Design E2E test cases:
  - **Tier 1 (Feature Coverage)**: Happy path serialization/deserialization with various grid sizes.
  - **Tier 2 (Boundary & Corner)**: Boundary columns/rows, alignment pads, maximum buffers, empty/full wraps.
  - **Tier 3 (Cross-Feature Combinations)**: Combinations of dirty rows, mixed styles, and varying UTF-16 character contents.
  - **Tier 4 (Real-World Workloads)**: Simulating realistic screen rendering states.
- Run tests against baseline/modified code.
- Publish `TEST_READY.md` when the test suite is fully complete.

### Step 4: Implementation Track (Parallel)
- **Milestone 1**: Modify native Zig snapshot serialization loop and size estimation in `termux_ghostty.zig`.
  - Optimize `writeSnapshotRow` to write contiguous Starts, Lengths, Widths, Styles, and Chars arrays.
  - Align next write offset to 8 bytes for u64 style array.
  - Align starting offset of each dirty row payload to 8-byte boundary in `fillSnapshotCurrentViewport`.
  - Update `snapshotRowRequiredBytes` and `snapshotRequiredBytes` for padding.
- **Milestone 2**: Modify JVM deserialization in `ScreenSnapshot.java` to use bulk NIO operations.
  - Read contiguous blocks using bulk buffer operations (`IntBuffer.get`, etc.).
  - Handle 8-byte alignment at start of row and before reading styles.
  - Update `RenderFrameCacheTest.java` mock writer to use the new layout.
- **Milestone 3**: Run tests and E2E validation against implemented changes.
- **Milestone 4 (Adversarial Coverage Hardening)**: Use Challenger to find untested code paths/gaps and generate adversarial tests.

### Step 5: Verification & Audit
- Run Forensic Auditor to verify correctness, check for any bypasses, and ensure no SIGBUS/crashes occur on architectures.
- Verify all E2E and JUnit tests pass.

### Step 6: Claim Victory
- Report completion and success to Sentinel (ID: 1af611a1-7cf0-470a-9284-6d527a467900).
