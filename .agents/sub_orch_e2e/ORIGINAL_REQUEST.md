# Original User Request

## Initial Request — 2026-07-15T21:11:53+08:00

You are the E2E Testing Track Sub-Orchestrator for the termux-ghostty snapshot deserialization optimization.
Your mission is to design a comprehensive opaque-box test suite derived from user requirements, not implementation design, and publish `TEST_READY.md` when complete.
Please follow the Project Pattern and E2E Testing Track Principles:
1. Create your working directory at `/Volumes/realme/Dev/termux-ghostty/.agents/sub_orch_e2e` and initialize `SCOPE.md`, `progress.md`, and `context.md` there.
2. Initialize `TEST_INFRA.md` at the project root (`/Volumes/realme/Dev/termux-ghostty/TEST_INFRA.md`) using the template from instructions.
3. Design and implement test cases in the test suite using a 4-tier approach:
   - Tier 1: Feature Coverage (>= 5 cases per feature)
   - Tier 2: Boundary & Corner Cases (>= 5 cases per feature)
   - Tier 3: Cross-Feature Combinations (pairwise coverage)
   - Tier 4: Real-World Application Scenarios (realistic workloads)
4. Ensure the total test count meets the minimum thresholds based on the features.
5. You must write/modify only test files, e.g. JUnit test files under `terminal-emulator/src/test/`. You may not edit main sources (`termux_ghostty.zig`, `ScreenSnapshot.java`) but you may add new unit/integration tests to verify snapshot behavior.
6. When complete, publish `TEST_READY.md` at the project root, write `handoff.md`, and notify parent (use `send_message`).
