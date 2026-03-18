# Plan: deprecate and remove the legacy Java terminal backend

Status: draft
Owner: app/runtime cleanup after Ghostty rollout

## Goal

Make the app/runtime **Ghostty-only**.

That means:
- no runtime fallback from Ghostty to `TerminalEmulator`
- no dual-backend branching in `TerminalSession` and `TerminalView`
- no app code reaching into Java-emulator-specific internals
- eventually no shipped legacy Java terminal backend source, unless we intentionally keep it as a deprecated library/testing surface

## Non-goals

Not in scope for this work:
- replacing Ghostty with another backend
- reimplementing Ghostty behavior in Java
- preserving two fully-supported terminal backends long-term
- porting every old Java emulator unit test 1:1 before deleting runtime fallback

## Why remove it

The current dual-backend model adds permanent complexity:
- `TerminalSession` owns two backend shapes
- `TerminalView` still carries emulator-era state and fallback adapters
- app code still has Java-emulator-specific assumptions
- every feature touching input, colors, rendering, resize, selection, clipboard, or lifecycle risks split logic
- test coverage is misleading because a lot of it still exercises `TerminalEmulator`, not the shipped Ghostty path

If Ghostty is the intended backend, keeping the Java fallback around long-term increases maintenance cost and slows feature work.

## Current state

Today the legacy Java backend still exists in 3 different roles:

### 1. Runtime fallback
`TerminalSession` still contains a dual path:
- `GhosttyTerminalContent` + `GhosttySessionWorker`
- or `TerminalEmulator` fallback

Current hotspots:
- `terminal-emulator/src/main/java/com/termux/terminal/TerminalSession.java`
- `terminal-view/src/main/java/com/termux/view/TerminalView.java`

### 2. Adapter compatibility layer
`JavaTerminalContentAdapter` exists only to present `TerminalEmulator` as `TerminalContent`.

File:
- `terminal-emulator/src/main/java/com/termux/terminal/JavaTerminalContentAdapter.java`

### 3. App-level assumptions
Some app code still expects an emulator object to exist.

Examples:
- `app/src/main/java/com/termux/app/terminal/TermuxTerminalSessionActivityClient.java`
  - `session.getEmulator().mColors.reset()`
- `app/src/main/java/com/termux/app/terminal/TermuxTerminalViewClient.java`
  - method name and lifecycle semantics still centered on `onEmulatorSet()`
- `terminal-view/src/main/java/com/termux/view/TerminalView.java`
  - `mEmulator`
  - fallback `JavaTerminalContentAdapter`
  - some constants still referenced from `TerminalEmulator`

### 4. Published library / test surface
`terminal-emulator` and `terminal-view` are published Android libraries.

That means deleting `TerminalEmulator` is not just an internal cleanup. It may be a breaking API change for downstream consumers.

## Recommended policy decisions

These should be decided up front.

### 1. App runtime should become Ghostty-only first
Recommended.

Reason:
- biggest complexity win
- lowest ambiguity
- does not require immediate deletion of every Java backend source file

### 2. Runtime removal and source removal should be separate phases
Recommended.

Reason:
- runtime cleanup is internal and urgent
- public API removal may need a deprecation window
- tests/constants may need extraction before hard deletion

### 3. Do not preserve silent fallback
Recommended.

If Ghostty native init fails or the native lib is unavailable, the app should fail loudly and predictably.
Not silently switch to the old backend.

### 4. Do not port upstream VT semantics tests into Java glue tests
Recommended.

Use:
- upstream Ghostty tests for terminal semantics
- local tests for our integration surface: JNI, worker ownership, snapshot transport, Android input, rendering, lifecycle

## Desired end state

### App/runtime end state
- `TerminalSession` always creates and uses Ghostty
- `TerminalView` renders from `TerminalContent` / Ghostty snapshots only
- no `mEmulator` field in `TerminalView`
- no `getEmulator()` dependency in app code
- no `JavaTerminalContentAdapter`
- no runtime branch for Java backend

### Optional library end state
If we choose full removal:
- `TerminalEmulator.java` removed
- Java-emulator-only tests removed or replaced
- shared constants extracted into neutral types before deletion

If we choose staged deprecation:
- `TerminalEmulator` stays temporarily but marked deprecated
- not used anywhere in app/runtime
- removed in a later breaking release

## Phase 0: pre-removal validation

Do not start runtime deletion until Ghostty passes a focused validation matrix.

Required checks:
- session startup/shutdown
- resize storms
- large output flood
- alternate screen apps (`vim`, `less`, `htop`, `nvim`, `tmux`)
- selection + copy
- clipboard paste
- mouse reporting
- scrollback
- cursor blink
- title updates
- color changes
- font changes
- soft keyboard / IME flows

This phase is done when there is no release-blocking reason to keep Java fallback.

## Phase 1: deprecate runtime fallback

Goal:
- stop treating Java backend as a valid runtime path
- keep code compiling
- keep deletion diff smaller later

### A. Remove fallback selection logic from `TerminalSession`
File:
- `terminal-emulator/src/main/java/com/termux/terminal/TerminalSession.java`

Changes:
- remove `FORCE_GHOSTTY_BACKEND`
- remove `shouldUseGhosttyBackend()` backend choice logic
- stop constructing `TerminalEmulator` in `initializeTerminalBackend(...)`
- stop swallowing Ghostty init failure and falling back
- replace fallback with explicit failure path

Recommended failure behavior:
- log hard error
- expose visible session/message failure to the UI
- do not silently create a Java backend

### B. Decide missing-lib policy
If `GhosttyNative.isLibraryLoaded()` is false:
- fail fast
- make the failure obvious
- no legacy fallback

Do not hide native packaging/configuration regressions behind Java fallback.

### C. Mark legacy runtime APIs as deprecated-in-practice
Examples:
- `TerminalSession.getEmulator()`
- comments/docs that still imply Java emulator is normal runtime behavior

At this phase they may still exist, but runtime should no longer depend on them.

## Phase 2: remove emulator assumptions from the app/view layer

Goal:
- make UI and app lifecycle backend-agnostic or Ghostty-specific where appropriate

### A. Replace `onEmulatorSet()` with backend-neutral lifecycle
Files:
- `app/src/main/java/com/termux/app/terminal/TermuxTerminalViewClient.java`
- any interfaces or callers that expose emulator-specific naming

Change:
- rename to something like `onTerminalReady()` or `onTerminalAttached()`

Reason:
- current name encodes obsolete architecture
- current behavior is really “terminal became usable”, not “Java emulator exists”

### B. Remove direct emulator access from color/font refresh
File:
- `app/src/main/java/com/termux/app/terminal/TermuxTerminalSessionActivityClient.java`

Current problem:
- `session.getEmulator().mColors.reset()` reaches into Java-emulator internals

Replace with backend-neutral session API, e.g. one of:
- `session.onColorSchemeChanged()`
- `session.invalidateColors()`
- `session.reloadRendererColors()`

The exact API can be chosen during implementation, but it must not expose emulator internals.

### C. Remove `mEmulator` from `TerminalView`
File:
- `terminal-view/src/main/java/com/termux/view/TerminalView.java`

Changes:
- remove `mEmulator`
- remove fallback `JavaTerminalContentAdapter`
- simplify `getTerminalContent()` to return session content only
- update cursor-blinker/lifecycle comments that still reference emulator setup timing

Important:
- if `TerminalView` still needs constants currently defined on `TerminalEmulator`, extract them first into a neutral constants class

Suggested extractions:
- Unicode replacement char constant
- legacy touch/mouse compatibility constants if they still matter outside the old emulator

## Phase 3: remove Java-backend runtime branches

Goal:
- delete dead branching now that runtime is Ghostty-only

### A. Simplify `TerminalSession`
File:
- `terminal-emulator/src/main/java/com/termux/terminal/TerminalSession.java`

Remove:
- `mEmulator`
- Java fallback creation path
- dual-branch getters for cursor/mode/title/background/etc.
- Java-specific mouse/paste/reset paths
- `appendToActiveBackend(...)`

Keep or replace:
- any session-level API still useful to the app, but backed only by Ghostty

### B. Delete `JavaTerminalContentAdapter`
File:
- `terminal-emulator/src/main/java/com/termux/terminal/JavaTerminalContentAdapter.java`

Once `TerminalView` and `TerminalSession` stop using it, it should go.

### C. Clean comments/docs/javadocs
Examples:
- `TerminalOutput.java` javadocs still mention `TerminalEmulator`
- comments that describe Java emulator as normal runtime path
- Ghostty docs that still describe fallback as part of rollout, if that is no longer true

## Phase 4: handle public API deprecation explicitly

This phase matters because `terminal-emulator` and `terminal-view` are published libraries.

### Option A. Conservative
Keep `TerminalEmulator` for one deprecation cycle.

Steps:
- mark `TerminalEmulator` deprecated
- document that app/runtime no longer uses it
- document planned removal version
- stop adding features/fixes except critical breakages

### Option B. Hard break
Remove `TerminalEmulator` immediately in the same release series.

Only do this if:
- there are no external consumers that matter
- breaking the published library API is acceptable

### Recommendation
Use Option A unless there is strong evidence no one depends on the library API.

## Phase 5: full legacy source removal

Only do this after Phase 4 decision is settled.

### A. Extract shared constants before deletion
Likely needed because some code still uses `TerminalEmulator` for constants.

Create a neutral class for constants still needed outside the old backend, for example:
- `TerminalConstants`
- `TerminalInputConstants`

Move only what is still shared.
Do not drag old emulator behavior along with the constants.

### B. Remove `TerminalEmulator`
File:
- `terminal-emulator/src/main/java/com/termux/terminal/TerminalEmulator.java`

Delete only after:
- no runtime references remain
- no app references remain
- constants are extracted
- deprecation policy is satisfied

### C. Rework or remove Java-emulator unit tests
Current large test surface under:
- `terminal-emulator/src/test/java/com/termux/terminal/*`

These tests currently mostly validate the old Java emulator.

Do not blindly delete all coverage.
Split them into 3 buckets:

1. **Keep as generic utility tests**
   - tests for classes still in use regardless of backend
   - examples: buffer helpers, width logic, frame cache helpers

2. **Replace with Ghostty integration tests**
   - tests that should validate our shipped backend contract
   - examples: session resize, snapshot invariants, pending-output handling, JNI glue, worker ownership

3. **Drop because upstream Ghostty already covers the semantics**
   - parser/VT behavior tests that only mattered because we owned the emulator implementation

## Test strategy for the transition

### Local tests should cover
- `TerminalSession` Ghostty-only initialization/failure behavior
- `GhosttySessionWorker` ownership and message flow
- JNI wrappers and native pending-output flow
- snapshot transport and render-cache invariants
- Android view input mapping
- app lifecycle hooks that previously depended on `mEmulator`

### Upstream Ghostty should cover
- parser semantics
- DEC/CSI/OSC behavior
- terminal mouse encoding rules
- core terminal state transitions

## Risks

### 1. Hidden app dependencies on `getEmulator()`
Mitigation:
- grep whole repo before removing APIs
- replace with session-level backend-neutral methods first

### 2. Breaking published library consumers
Mitigation:
- split runtime removal from public API removal
- use a deprecation window if needed

### 3. Losing regression coverage when deleting Java tests
Mitigation:
- classify tests first
- add Ghostty integration tests before deleting coverage

### 4. Native load failures become user-visible
Mitigation:
- make packaging/ABI failures explicit early
- add CI checks that ensure native lib is built and loadable for supported ABIs

### 5. Color/font/lifecycle regressions
Mitigation:
- replace emulator-specific hooks with explicit session APIs
- manually retest settings reload flows

## Acceptance criteria

This plan is complete when all are true:
- app/runtime no longer creates `TerminalEmulator`
- app/runtime no longer falls back from Ghostty to Java backend
- `TerminalView` no longer stores `mEmulator`
- `JavaTerminalContentAdapter` is deleted
- app code no longer calls `session.getEmulator()`
- backend readiness callbacks are renamed away from emulator terminology
- color/font reload works without emulator internals
- Ghostty remains the only shipped runtime backend

If full source removal is also in scope, then additionally:
- `TerminalEmulator.java` is removed or formally deprecated per policy
- shared constants are extracted to neutral types
- obsolete Java-emulator tests are replaced or intentionally dropped with rationale

## Recommended execution order

1. Finish Ghostty validation and rollout confidence work.
2. Remove silent runtime fallback.
3. Remove emulator assumptions from app/view lifecycle.
4. Delete runtime dual-branch code.
5. Decide library deprecation policy.
6. Extract shared constants.
7. Remove `TerminalEmulator` source and obsolete tests.

## Short version

Do this in 2 passes, not 1:

### Pass 1: runtime cleanup
- make the app Ghostty-only
- remove fallback logic
- remove emulator assumptions from UI/app code
- keep legacy source temporarily if needed for compatibility

### Pass 2: source/API cleanup
- deprecate or remove `TerminalEmulator`
- delete `JavaTerminalContentAdapter`
- trim old tests
- extract shared constants

That gets rid of the legacy backend without turning the migration into one huge risky diff.
