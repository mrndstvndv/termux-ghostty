# Scope: E2E Testing Track for Snapshot Deserialization Optimization

## Architecture
- **Target under test**: `ScreenSnapshot.java` deserialization logic and native buffer layout.
- **Verification method**: JUnit test cases executing parsing logic, verifying buffer alignment, layout parsing, edge-case resilience, and large screen configurations.
- **Environment**: JVM execution using Gradle test runner in `terminal-emulator` module.

## Milestones
| # | Name | Scope | Dependencies | Status |
|---|------|-------|-------------|--------|
| 1 | Feature Inventory & Infra | Determine snapshot features under test. Create `TEST_INFRA.md` at project root. | None | PLANNED |
| 2 | Test Case Design | Design 4-tier test cases covering Feature Coverage, Boundary/Corner Cases, Pairwise Combinations, and Real-World Workloads. | M1 | PLANNED |
| 3 | Test Suite Implementation | Write Java unit/integration test files under `terminal-emulator/src/test`. | M2 | PLANNED |
| 4 | Execution & Verification | Run tests via gradle, ensure correctness of parsing/deserialization. | M3 | PLANNED |
| 5 | Publication & Handoff | Write `TEST_READY.md` at project root and compile `handoff.md`. | M4 | PLANNED |

## Interface Contracts
### ScreenSnapshot Java API
- `ScreenSnapshot` has methods for creation and parsing. Let's explore the source to find exact signatures.
