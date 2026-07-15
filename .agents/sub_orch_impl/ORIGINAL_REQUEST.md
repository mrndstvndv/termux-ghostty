# Original User Request

## Initial Request — 2026-07-15T21:12:01+08:00

You are the Implementation Track Sub-Orchestrator for the termux-ghostty snapshot deserialization optimization.
Your mission is to implement the code changes and verify they work and pass all E2E tests.
Please follow the Project Pattern and Implementation Track Principles:
1. Create your working directory at `/Volumes/realme/Dev/termux-ghostty/.agents/sub_orch_impl` and initialize `SCOPE.md`, `progress.md`, and `context.md` there.
2. Decompose the implementation into milestones. We suggest:
   - Milestone 1: Native Serialization & Sizing Optimization in `termux_ghostty.zig`.
   - Milestone 2: JVM Deserialization Optimization in `ScreenSnapshot.java` (using bulk JVM NIO operations) and updating mock writer in `RenderFrameCacheTest.java`.
   - Milestone 3: Integrate and verify against E2E test suite (polling for `TEST_READY.md` from the E2E Testing Track).
   - Milestone 4: Adversarial Coverage Hardening (Tier 5) with Challenger.
3. For each milestone, you may spawn a Worker to write code and run builds/tests.
   IMPORTANT MANDATORY INTEGRITY WARNING to include in any Worker prompt:
   "DO NOT CHEAT. All implementations must be genuine. DO NOT hardcode test results, create dummy/facade implementations, or circumvent the intended task. A Forensic Auditor will independently verify your work. Integrity violations WILL be detected and your work WILL be rejected."
4. Verify each milestone using the Explorer -> Worker -> Reviewer -> Challenger -> Forensic Auditor flow as described in instructions.
5. In Phase 2 of the final milestone, spawn Challenger first to perform white-box adversarial testing, then Worker to fix gaps, then Reviewer.
6. When complete, write `handoff.md`, and notify parent (use `send_message`).
