# BRIEFING — 2026-07-15T21:10:45+08:00

## Mission
Optimize the snapshot deserialization pipeline in termux-ghostty on JVM and native Zig sides.

## 🔒 My Identity
- Archetype: teamwork_preview_orchestrator
- Roles: orchestrator, user_liaison, human_reporter, successor
- Working directory: /Volumes/realme/Dev/termux-ghostty/.agents/orchestrator
- Original parent: Sentinel
- Original parent conversation ID: 1af611a1-7cf0-470a-9284-6d527a467900

## 🔒 My Workflow
- **Pattern**: Project
- **Scope document**: /Volumes/realme/Dev/termux-ghostty/PROJECT.md
1. **Decompose**: We will decompose the task into 3 key implementation milestones: R1: Native Serialization, R2: Capacity Calculations, and R3: JVM Deserialization, plus an E2E testing track.
2. **Dispatch & Execute**:
   - **Delegate (sub-orchestrator)**: We will spawn sub-orchestrators/workers for each milestone.
3. **On failure** (in this order):
   - Retry: nudge stuck agent or re-send task
   - Replace: spawn fresh agent with partial progress
   - Skip: proceed without (only if non-critical)
   - Redistribute: split stuck agent's remaining work
   - Redesign: re-partition decomposition
   - Escalate: report to parent (sub-orchestrators only, last resort)
4. **Succession**: Self-succeed at 16 spawns. Write handoff.md, spawn successor.
- **Work items**:
  1. Initialize project files and planning [pending]
  2. Setup E2E Test Suite [pending]
  3. Implement R1 & R2 (Native serialization & sizing in Zig) [pending]
  4. Implement R3 (JVM Deserialization in ScreenSnapshot.java) [pending]
  5. End-to-end integration and validation [pending]
- **Current phase**: 1
- **Current focus**: Initialize project files and planning

## 🔒 Key Constraints
- Never reuse a subagent after it has delivered its handoff — always spawn fresh
- Hard veto on integrity audit failure

## Current Parent
- Conversation ID: 1af611a1-7cf0-470a-9284-6d527a467900
- Updated: not yet

## Key Decisions Made
- Use Project Orchestrator pattern. Decompose into E2E testing track and implementation track.

## Team Roster
| Agent | Type | Work Item | Status | Conv ID |
|-------|------|-----------|--------|---------|
| sub_orch_e2e | E2E Testing Track Orchestrator | Design & implement E2E test suite | in-progress | 3cc81a07-0d8b-4e48-a3c4-7a41e804e8f2 |
| sub_orch_impl | Implementation Track Orchestrator | Coordinate code changes and validation | in-progress | 5aec9002-c3dc-4054-823c-debb396c9de5 |

## Succession Status
- Succession required: no
- Spawn count: 2 / 16
- Pending subagents: 3cc81a07-0d8b-4e48-a3c4-7a41e804e8f2, 5aec9002-c3dc-4054-823c-debb396c9de5
- Predecessor: none
- Successor: not yet spawned

## Active Timers
- Heartbeat cron: task-11
- Safety timer: none
- On succession: kill all timers before spawning successor
- On context truncation: run `manage_task(Action="list")` — re-create if missing

## Artifact Index
- /Volumes/realme/Dev/termux-ghostty/.agents/orchestrator/plan.md — Orchestrator plan
- /Volumes/realme/Dev/termux-ghostty/.agents/orchestrator/progress.md — Liveness heartbeat and progress check-pointing
- /Volumes/realme/Dev/termux-ghostty/.agents/orchestrator/context.md — Context details
