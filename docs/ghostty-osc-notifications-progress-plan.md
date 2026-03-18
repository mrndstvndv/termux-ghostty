# Plan: Ghostty OSC notifications + progress integration

Status: draft
Owner: Ghostty backend integration

## Goal

Add Termux-side support for Ghostty VT actions that are already emitted by upstream `ghostty-vt`:

- desktop notifications: `.show_desktop_notification`
  - source protocols: `OSC 9`, `OSC 777;notify;...`
- progress updates: `.progress_report`
  - source protocol: `OSC 9;4;state;progress`

Important: this repo already embeds `ghostty-vt` as a Zig module and uses a custom `ghostty.Stream(*Handler)` integration. We do **not** need to switch to or patch the public libghostty C terminal API for this work.

## Non-goals

Not in scope for this plan:

- adding upstream support for kitty `OSC 99`
- changing Ghostty parser behavior upstream
- exact visual parity with Ghostty desktop UI, Windows taskbar progress, or macOS dock progress
- drawing inline progress UI inside terminal cells in v1
- implementing advanced notification filtering/callback semantics from kitty's richer protocol set

## Current state

### Upstream / parser side

Upstream Ghostty already normalizes these protocols into stream actions:

| Protocol | Upstream action |
| --- | --- |
| `OSC 9` | `.show_desktop_notification` |
| `OSC 777;notify;title;body` | `.show_desktop_notification` |
| `OSC 9;4;state;progress` | `.progress_report` |

### Termux integration side

Current integration points:

- `terminal-emulator/src/main/zig/src/termux_ghostty.zig`
  - owns `ghostty.Terminal`
  - owns `ghostty.Stream(*Handler)`
  - custom `Handler.vt(...)` is the real integration point
- `terminal-emulator/src/main/zig/src/jni_exports.zig`
  - JNI bridge to Java
- `terminal-emulator/src/main/java/com/termux/terminal/GhosttyNative.java`
  - Java native declarations + result bits
- `terminal-emulator/src/main/java/com/termux/terminal/GhosttyTerminalContent.java`
  - Java wrapper around native handle
- `terminal-emulator/src/main/java/com/termux/terminal/GhosttySessionWorker.java`
  - single-threaded owner of native state mutation at runtime

What happens today:

- `.bell` -> surfaced
- title changes -> surfaced
- clipboard copy -> surfaced
- color changes -> surfaced
- reply bytes (CPR/DSR/etc.) -> surfaced
- `.show_desktop_notification` -> ignored
- `.progress_report` -> ignored

## Constraints

- `GhosttySessionWorker` stays the sole owner of native session mutation.
- Append path must stay cheap. No UI work on native append thread path.
- Apps running in the terminal are untrusted from the app's point of view. Remote shells over SSH can emit these protocols too.
- Notifications need user control and rate limiting.
- Progress is session UI state, not terminal row/cell content.
- v1 should avoid coupling notifications/progress to `ScreenSnapshot` row transport unless a renderer need appears.

## Recommended product policy

These decisions should be made up front.

### 1. Settings policy

Recommended:

- final user-facing control should live in `termux.properties`
- both protocol notifications and progress mirroring should be treated as **opt-in** features
- do **not** hardcode always-on Android notifications as the long-term default

For the immediate implementation/testing phase:

- land the plumbing first
- use temporary experimental behavior so UX can be evaluated
- defer `termux.properties` parsing and final defaults until after testing

Reason:
- remote hosts can spam notifications or progress updates
- Android notification permission/state differs by OS version
- the best defaults are still unknown and should be validated with real use

### 2. Foreground vs background behavior

Recommended v1/testing behavior:

- focused/visible session:
  - no Android system notification for protocol notifications by default during testing
  - keep progress local to the session/UI state
- unfocused/background session:
  - desktop-notification protocols may raise Android notifications when the experiment is enabled
  - progress may be mirrored into a single ongoing Android notification when the experiment is enabled
- optional later: in-app banner/toast for focused sessions

### 3. Progress UX

Recommended v1/testing behavior:

- always store progress as per-session native/session state
- do **not** block on a full visual design for inline terminal overlays
- focused session: keep progress inline/session-local
- background session: mirror progress into **one ongoing Android notification per session**, updated in place rather than posting new notifications repeatedly
- clear that ongoing notification on progress remove/reset/timeout/session close, and likely when the session returns to the foreground

Reason:
- Termux does not have a 1:1 equivalent of Ghostty's split-top progress bar
- `OSC 9;4` is stateful progress, not a one-shot alert channel
- updating one stable notification is much safer than emitting many notifications from frequent progress refreshes

### 4. Stale progress timeout

Recommended:

- clear stale progress after 15s without refresh, matching Ghostty's documented behavior closely enough

Reason:
- many apps forget to send the final clear update
- avoids permanently stale UI state
- avoids leaving stale ongoing Android notifications around

### 5. Temporary experimentation rule

Until `termux.properties` toggles land:

- treat notification/progress behavior as experimental
- keep the implementation structured so settings can be added later without redesigning the transport path
- do not treat testing-time behavior as the final product default

## Desired end state

- native session captures notification requests and progress updates
- `termux_ghostty_session_append()` exposes new result bits for both
- worker consumes those results on the worker thread and posts minimal main-thread updates
- `TerminalSession` exposes current Ghostty progress state
- app layer can decide whether to show Android notifications for protocol notifications
- app layer can mirror background progress into one ongoing Android notification per session
- app layer can decide where else to surface per-session progress inline/in chrome
- final user controls live in `termux.properties`
- short-term testing can happen before `termux.properties` toggles are added
- behavior is tested with real OSC sequences, not only mocked callbacks

## Proposed design

## 1. Native side effect transport

### 1.1 Notification model

Treat desktop notifications as **ephemeral events**.

Native session stores:

- `pending_notification_title`
- `pending_notification_body`

Behavior:

- newest notification wins if multiple arrive before Java consumes them
- data is consumed once by worker
- notification data is not serialized into snapshots

### 1.2 Progress model

Treat progress as **persistent per-session state**.

Native session stores:

- `progress_state`
- `progress_value`
- `progress_generation`
- optional `progress_dirty` helper flag

Behavior:

- latest progress replaces previous progress
- `remove` clears state
- reset/destroy clears state
- stale timeout handled by worker/app layer, not by the parser itself

### 1.3 Result bits

Add append-result bits for:

- `APPEND_RESULT_DESKTOP_NOTIFICATION`
- `APPEND_RESULT_PROGRESS`

Reason:
- matches existing title/clipboard/bell/colors pattern
- lets worker react without polling

## 2. Native API shape

Recommended API additions in the Termux native shim, not upstream Ghostty:

### Notification

- `termux_ghostty_session_consume_notification_title(...)`
- `termux_ghostty_session_consume_notification_body(...)`

Alternative if we want fewer JNI methods:
- one combined consume method returning a single encoded payload

Recommendation:
- keep title/body as separate consume methods
- consumption is safe because worker is single-threaded owner of append + consume order

### Progress

- `termux_ghostty_session_get_progress_state(...)`
- `termux_ghostty_session_get_progress_value(...)`
- `termux_ghostty_session_get_progress_generation(...)`
- `termux_ghostty_session_clear_progress(...)`

Reason:
- worker needs read access after append
- worker needs clear access for stale timeout/reset behavior
- generation helps avoid duplicate UI work

## 3. Java/session model

### Notification path

Recommended path:

1. append returns `APPEND_RESULT_DESKTOP_NOTIFICATION`
2. worker consumes title/body from native side
3. worker posts main-thread callback through `TerminalSession`
4. app/client decides whether to show Android notification, in-app banner, or ignore

Recommendation:
- add a dedicated callback on `TerminalSessionClient`
- do not overload bell/title callbacks for this

Suggested callback shape:

- `onTerminalProtocolNotification(TerminalSession session, String title, String body)`

### Progress path

Recommended path:

1. append returns `APPEND_RESULT_PROGRESS`
2. worker reads native progress state/value/generation
3. worker updates cached `TerminalSession` progress fields
4. worker schedules stale-timeout clear if state is active
5. worker posts a main-thread callback or uses an existing frame/session invalidation path
6. app layer decides whether to mirror the current state into a single ongoing background Android notification for that session

Recommendation:
- add explicit session getters for progress state/value
- add explicit client callback for progress change if app chrome or background-notification logic needs reactive updates
- keep the ongoing Android progress notification keyed by stable session identity so it updates in place instead of spamming new notifications

Suggested session fields:

- `mGhosttyProgressState`
- `mGhosttyProgressValue`
- `mGhosttyProgressGeneration`

Suggested callback:

- `onTerminalProgressChanged(TerminalSession session)`

If interface churn needs to stay small, fallback option:
- reuse `onFrameAvailable()` for UI invalidation
- keep progress readable via `TerminalSession` getters

## File-by-file plan

## Phase 0: policy + API decisions

Files:

- `docs/ghostty-osc-notifications-progress-plan.md`
- app/client code after policy is chosen

Tasks:

- [ ] confirm that final user controls live in `termux.properties`
- [ ] confirm that notifications/progress are opt-in in the long term
- [ ] decide temporary experimentation behavior before settings land
- [ ] decide foreground vs background notification behavior
- [ ] decide v1 focused-session progress UI surface
- [ ] decide whether to add explicit `TerminalSessionClient` callbacks or only getters + invalidation
- [ ] decide stale progress timeout exact value and whether it is configurable

## Phase 1: native plumbing in Zig

Files:

- `terminal-emulator/src/main/zig/src/termux_ghostty.zig`
- `terminal-emulator/src/main/zig/include/termux_ghostty.h`
- `terminal-emulator/src/main/zig/src/jni_exports.zig`

Tasks:

- [ ] add native session fields for pending notification title/body
- [ ] add native session fields for progress state/value/generation
- [ ] add helpers:
  - [ ] `replacePendingNotification(...)`
  - [ ] `setProgress(...)`
  - [ ] `clearProgress(...)`
- [ ] handle `.show_desktop_notification` in `Handler.vt(...)`
- [ ] handle `.progress_report` in `Handler.vt(...)`
- [ ] add append result bits for notification/progress
- [ ] set those bits in `termux_ghostty_session_append(...)`
- [ ] add consume/getter/clear exports for notification + progress
- [ ] update `termux_ghostty.h` to match
- [ ] ensure reset/destroy paths clear progress + pending notification data

Recommended implementation note:
- keep desktop notifications and progress out of snapshot row transport for now

## Phase 2: Java bridge updates

Files:

- `terminal-emulator/src/main/java/com/termux/terminal/GhosttyNative.java`
- `terminal-emulator/src/main/java/com/termux/terminal/GhosttyTerminalContent.java`

Tasks:

- [ ] add new append result constants
- [ ] add progress state constants or enum mapping
- [ ] add native methods for notification consume
- [ ] add native methods for progress get/clear
- [ ] add wrapper methods on `GhosttyTerminalContent`

## Phase 3: worker integration

Files:

- `terminal-emulator/src/main/java/com/termux/terminal/GhosttySessionWorker.java`

Tasks:

- [ ] extend `processAppendResult(...)` for notification/progress
- [ ] consume pending notification title/body on worker thread
- [ ] post main-thread notification callback/event
- [ ] read progress state/value/generation from native side
- [ ] update cached session progress fields
- [ ] schedule/cancel stale progress timeout message
- [ ] clear native progress on timeout
- [ ] trigger UI/session invalidation when progress changes

Recommended worker change:
- add a dedicated message like `MSG_PROGRESS_TIMEOUT`

## Phase 4: session/client surface

Files:

- `terminal-emulator/src/main/java/com/termux/terminal/TerminalSession.java`
- `terminal-emulator/src/main/java/com/termux/terminal/TerminalSessionClient.java`
- `app/src/main/java/com/termux/app/terminal/TermuxTerminalSessionClientDispatcher.java`
- app client implementations that need to react

Tasks:

- [ ] add `TerminalSession` fields + getters for progress
- [ ] add `TerminalSession` method for protocol notification dispatch
- [ ] add `TerminalSession` method for progress-change dispatch if using explicit callback
- [ ] extend `TerminalSessionClient` if using explicit callbacks
- [ ] update dispatcher and app-side clients

## Phase 5: app/UI integration

Files: app layer, exact files depend on product decision

Likely touchpoints:

- `app/src/main/java/com/termux/app/terminal/...`
- bubble/session-list UI code
- maybe notification helper/service code

Tasks:

- [ ] if enabled, show Android notification for background/unfocused protocol notifications
- [ ] include session identity in notification content where useful
- [ ] handle Android notification permission/state cleanly
- [ ] decide where session progress is visible in UI when the session is focused
- [ ] when the session is backgrounded and progress is active, show/update one ongoing Android notification for that session
- [ ] dismiss/update that ongoing progress notification on remove/reset/timeout/session close/foreground return

Recommended v1/testing behavior:

- protocol notifications: optional background Android notification path
- progress: session-state always, plus one ongoing background Android notification per session during experimentation
- inline/chrome progress UI can stay minimal until defaults are validated

## Phase 6: `termux.properties` settings + rollout guardrails

Files: app settings / config files

This phase is intentionally deferred until testing settles the desired defaults.

Tasks:

- [ ] add `termux.properties` toggle for terminal protocol notifications
- [ ] add `termux.properties` toggle for progress mirroring/background progress notifications
- [ ] add `termux.properties` toggle for any focused-session progress UI if we decide to expose one
- [ ] optionally keep debug logging around new paths while feature settles
- [ ] document that kitty `OSC 99` is not supported yet

## Test plan

## Zig/native tests

Files:

- `terminal-emulator/src/main/zig/src/termux_ghostty.zig`

Add tests for:

- [ ] `OSC 9` sets notification append bit and stores body/title correctly
- [ ] `OSC 777;notify;title;body` sets notification append bit
- [ ] `OSC 9;4;1;50` stores progress state/value
- [ ] `OSC 9;4;3` stores indeterminate progress
- [ ] `OSC 9;4;0` clears progress
- [ ] reset clears progress + pending notification state
- [ ] consuming notification data clears it

## Java tests

Targets:

- `GhosttySessionWorker`
- `TerminalSession`
- dispatcher/client callback flow

Add tests for:

- [ ] worker handles new append-result flags without regressing title/clipboard/bell/reply behavior
- [ ] stale progress timeout clears state
- [ ] session getters reflect latest progress state/value
- [ ] protocol notification callback fires once per consumed event
- [ ] background progress notification logic updates one stable notification instead of creating many
- [ ] background progress notification clears on remove/reset/timeout/session close/foreground return

## Manual validation

Use real shell commands:

```sh
# OSC 9 notification
printf '\e]9;Build finished\a'

# OSC 777 notification
printf '\e]777;notify;Build;Finished\e\\'

# progress 50%
printf '\e]9;4;1;50\a'

# indeterminate
printf '\e]9;4;3\a'

# clear
printf '\e]9;4;0\a'
```

Manual checks:

- [ ] focused session notification behavior
- [ ] background session notification behavior
- [ ] focused session progress behavior
- [ ] background session ongoing progress notification update behavior
- [ ] progress update + clear behavior
- [ ] stale timeout behavior
- [ ] session close/reset while progress active
- [ ] foreground/background transitions while progress is active
- [ ] resize/rotation while progress active
- [ ] SSH/remote shell spam behavior during experimentation

## Risks

- notification spam from remote hosts if enabled too broadly
- Android notification permission/OS behavior differences
- no obvious current UI surface for progress parity with desktop terminals
- callback/interface churn in `TerminalSessionClient`
- stale progress if timeout policy is skipped

## Recommended execution order

1. land native plumbing only
2. land Java bridge + worker consumption
3. expose progress/session state
4. add app callbacks/UI policy for notifications
5. add background ongoing progress-notification behavior for experimentation
6. add stale-timeout handling
7. add tests + manual validation
8. add `termux.properties` settings + rollout guardrails after defaults are validated

## Future follow-up

If upstream Ghostty later adds kitty `OSC 99` support, this plan should still hold:

- upstream parser would emit a desktop-notification-like action
- Termux-side transport/callback policy would already exist
- only protocol-specific metadata mapping would need review
