# Context — 2026-07-15T21:12:00+08:00

## Active State
- Role: Implementation Track Sub-Orchestrator
- Objective: Implement termux-ghostty snapshot deserialization optimization and verify using the Project Pattern loop (Explorer -> Worker -> Reviewer -> Challenger -> Forensic Auditor).

## Workspace Context
- Working Directory: `/Volumes/realme/Dev/termux-ghostty/.agents/sub_orch_impl`
- Target Source Files:
  - `terminal-emulator/src/main/zig/src/termux_ghostty.zig` (Native Zig serialization)
  - `terminal-emulator/src/main/java/com/termux/terminal/ScreenSnapshot.java` (JVM Java deserialization)
  - `terminal-emulator/src/test/java/com/termux/terminal/RenderFrameCacheTest.java` (Java Mock rendering test)
