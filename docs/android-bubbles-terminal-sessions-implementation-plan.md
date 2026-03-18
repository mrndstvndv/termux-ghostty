# Android bubbles for Termux terminal sessions — implementation plan

## Goal

Use native Android bubbles to let users minimize terminal sessions and reopen them fast.

Core decision:
- **one bubble = one terminal session**
- bubble opens **that exact session**
- multiple bubbled sessions allowed
- use a **dedicated bubble activity**, not `TermuxActivity`

## Product decisions locked in

- [x] Dedicated bubble activity
- [x] One bubble per session
- [x] Multiple bubbled sessions allowed
- [x] Manual bubbling, not auto-bubble by default
- [x] Bubble opens exact session
- [x] Bubble disappears when session exits
- [x] Session rename updates bubble title
- [x] Keep existing app/service notification separate
- [x] Use per-session bubble notifications
- [x] Suppress duplicate notification when bubble is active
- [x] Auto-expand bubble when user explicitly requests it
- [x] MVP targets **Android 11+** first

## Why this shape

Reasons:
- `TermuxService` already owns session lifetime
- activities can come and go without killing the shell
- `TerminalSession.mHandle` already gives us a stable per-session id
- changing `TermuxActivity` launch semantics would be risky
- bubble UI wants a small dedicated surface, not the full drawer/session-list app

## MVP scope

MVP means:
- user can bubble a chosen session
- system shows native Android bubble
- bubble opens a minimal terminal UI for that exact session
- multiple session bubbles can coexist
- bubble disappears when session exits
- rename updates title/shortcut/notification label
- `Open in full Termux` jumps to that exact session

Out of MVP:
- session switcher inside bubble
- restore bubbled sessions after process death/reboot
- advanced bubble settings UI
- Android 10 compatibility polish
- live subtitle updates from cwd/title changes unless easy

---

# Architecture

## Session identity

Use `TerminalSession.mHandle` as the canonical id for:
- bubble intent extra
- notification id mapping
- dynamic shortcut id
- internal bubble tracking

## New main pieces

### 1. `BubbleSessionActivity`

Purpose:
- minimal activity used as bubble expanded content
- binds to `TermuxService`
- resolves `sessionHandle`
- attaches exact `TerminalSession` to a `TerminalView`

Expected behavior:
- no drawer
- no session list
- terminal-first UI
- prefer no persistent top bar chrome
- if any bubble chrome remains, keep it minimal and do not prioritize `Open in full Termux`
- bubble should eventually expose configurable extra keys / shortcut controls, reusing the same `termux.properties`-driven behavior as the main terminal UI

### 2. `SessionBubbleController`

Purpose:
- service-owned helper for all bubble lifecycle logic

Responsibilities:
- create/update/remove per-session bubble notifications
- map `sessionHandle -> notificationId`
- publish/update/remove dynamic shortcuts
- know whether a session is bubbled
- handle explicit bubble/unbubble requests

### 3. Bubble intent contract

Need a dedicated extra like:
- `EXTRA_SESSION_HANDLE`
- maybe `EXTRA_AUTO_EXPAND_BUBBLE`
- maybe `EXTRA_FROM_BUBBLE`

### 4. Bubble-specific UI client

Likely simplest:
- create a small session client/view client pair for bubble activity
- reuse shared logic where possible
- avoid coupling to `TermuxActivity`

---

# Manifest / platform plan

## New activity

Add `BubbleSessionActivity` to `app/src/main/AndroidManifest.xml` with:
- `android:resizeableActivity="true"`
- `android:allowEmbedded="true"`
- `android:launchMode="standard"`
- `android:documentLaunchMode="always"`
- appropriate theme, likely no action bar

Why:
- bubble host activity must be embeddable and resizable
- multi-session bubbles need multiple activity instances
- we do not want `singleTask` behavior from `TermuxActivity`

## API level strategy

MVP target:
- Android **11+** behavior first
- guard bubble code behind SDK checks
- fallback gracefully on unsupported devices

Fallback on unsupported devices:
- do not crash
- maybe show toast: bubbles unsupported / disabled
- optionally open session normally instead

---

# Notification / shortcut design

## Notification model

Keep:
- existing app-wide foreground service notification

Add:
- **per-session bubble notification** for each bubbled session

One session bubble notification should contain:
- small icon
- session label
- bubble metadata
- shortcut id
- content intent to exact session
- suppression enabled when bubble is active

## Shortcut model

For Android 11+:
- publish one dynamic long-lived shortcut per bubbled session
- shortcut id = session handle
- short label = session name or fallback label
- icon = app/session icon
- intent opens exact session in bubble/full app path

Minimal conversation mapping is fine even though sessions are not chat threads.
This is the cleanest way to fit Android’s modern bubble plumbing.

## Bubble icon differentiation

Current limitation:
- generic app icon is not good enough once multiple session bubbles exist

Next-step requirement:
- bubble / shortcut icon should be visually distinct per session
- user should be able to tell bubbled sessions apart without opening them

Acceptable strategies:
- named session: monogram icon from the session name
- unnamed session: numeric badge or index-based icon like `[1]`, `[2]`, etc.
- if easy, color variation can help too, but identity matters more than decoration

Implementation note:
- keep shortcut icon and collapsed bubble icon aligned so system surfaces stay consistent
- session rename should eventually refresh the differentiated icon too if icon depends on the name

## Notification content text

MVP label strategy:
- primary label: renamed session name if present
- fallback: `[index] shell`
- optional subtitle: terminal title if cheap to get

If live title updates are expensive/noisy, skip for MVP.
Rename updates are required.

---

# UX plan

## User entry points

MVP entry points:
- session list item menu / long-press action: `Bubble session`
- terminal context/overflow action: `Bubble current session`

If one is much easier initially, ship one first, but target both in MVP if feasible.

## Bubble activity UI

Minimal chrome only.

Current UX direction after device testing:
- prefer removing the top bar entirely
- if any temporary chrome remains, `Open in full Termux` is the first candidate to remove
- do not expose `Close session` from the bubble UI; bubble should act as a view client for an already-owned service session, not as session lifecycle chrome
- prioritize bringing configurable extra keys / shortcut controls into the bubble instead
- keep exact-session full-app handoff support in code, even if it is not exposed as a visible bubble button
- when the bubble is collapsed or dismissed, the bubble activity should be disposable and must unregister/unbind cleanly so it does not retain extra memory; the underlying terminal session should keep running in `TermuxService`

No session switching in bubble.
No drawer.
No extra panels.

## Full app handoff

When `Open in full Termux` is tapped:
- launch `TermuxActivity`
- force it to attach to the exact session handle
- do not open last/current arbitrary session

This needs a new intent path in `TermuxActivity`.

---

# Required code changes

## 1. Add session-targeted launch path to full app

### Goal
Allow `TermuxActivity` to open directly to a requested session handle.

### Changes
- add intent extra for session handle in constants
- update `TermuxActivity.onServiceConnected()` logic:
  - if session handle extra exists, resolve session by handle
  - attach exact session
  - fallback to current stored/last session if missing

### Checklist
- [ ] Add `EXTRA_SESSION_HANDLE` constant
- [ ] Teach `TermuxActivity.newInstance(...)` to optionally accept a session handle
- [ ] Update `onServiceConnected()` to prioritize requested handle
- [ ] Keep old launch behavior unchanged when extra absent

## 2. Add `BubbleSessionActivity`

### Goal
Dedicated bubble UI for one exact session.

### Changes
- new activity class
- simple layout with terminal + mini action bar
- bind to `TermuxService`
- resolve `sessionHandle` from intent
- attach resolved session to `TerminalView`

### Checklist
- [ ] Create `BubbleSessionActivity`
- [ ] Create bubble activity layout
- [ ] Bind to `TermuxService`
- [ ] Resolve session via `getTerminalSessionForHandle()`
- [ ] Attach terminal session to a local `TerminalView`
- [ ] Handle missing/ended session gracefully and finish activity
- [ ] Revisit bubble chrome after on-device testing; prefer removing the top bar entirely if possible
- [ ] If any bubble controls remain, keep them minimal (`Unbubble` / `Close session` only if still needed)
- [ ] Keep exact-session full-app handoff support available in code, even if no visible button exists
- [ ] Reuse or embed configurable extra keys in bubble UI, driven by `termux.properties`
- [ ] Add manifest entry with bubble-safe flags

## 3. Add bubble state/controller in service

### Goal
Centralize bubble lifecycle in the service, where session lifetime already lives.

### Changes
- create `SessionBubbleController`
- instantiate from `TermuxService`
- expose public service methods like:
  - bubble session
  - unbubble session
  - check if session bubbled
  - update session bubble label
  - remove bubble on session exit

### Checklist
- [ ] Add `SessionBubbleController`
- [ ] Initialize it from `TermuxService`
- [ ] Add service API for bubble/unbubble by `TerminalSession` or handle
- [ ] Track `sessionHandle -> notificationId`
- [ ] Track bubbled handles in memory
- [ ] Remove bubble state when session exits

## 4. Build per-session bubble notifications

### Goal
Post native bubble notifications per session.

### Changes
- create bubble-capable channel handling if needed
- build `Notification.BubbleMetadata`
- add `PendingIntent` to `BubbleSessionActivity`
- set auto-expand on explicit bubble request
- suppress duplicate notification when bubble is active

### Checklist
- [ ] Add bubble notification builder path
- [ ] Add per-session notification ids
- [ ] Create/update notification channel config if needed
- [ ] Add `BubbleMetadata`
- [ ] Add content/bubble pending intent with session handle
- [ ] Set shortcut id on notification
- [ ] Set suppress-notification for bubbled case
- [ ] Post notification through `NotificationManager`
- [ ] Cancel notification when unbubbled or session exits

## 5. Publish dynamic shortcuts per bubbled session

### Goal
Use proper Android 11+ bubble/conversation plumbing.

### Changes
- publish shortcut when session is bubbled
- update shortcut label on rename
- remove shortcut when bubble/session is removed

### Checklist
- [ ] Add shortcut publisher helper
- [ ] Publish long-lived dynamic shortcut for bubbled session
- [ ] Use session handle as shortcut id
- [ ] Update shortcut on rename
- [ ] Remove shortcut on session exit/unbubble

## 6. Add UI actions to request bubbling

### Goal
Give user explicit controls.

### Changes
- add `Bubble session` action to session list menu and/or terminal menu
- call service bubble API for selected/current session

### Checklist
- [ ] Add `Bubble session` string resources
- [ ] Add action in session list UI
- [ ] Add action in terminal context/overflow menu
- [ ] Hide/disable action if already bubbled
- [ ] Show `Unbubble session` if practical, otherwise leave to bubble UI for MVP

## 7. Keep labels in sync on rename

### Goal
Renaming a session should update bubble label.

### Changes
- hook into rename flow in `TermuxTerminalSessionActivityClient`
- if session is bubbled, ask service/controller to refresh label + shortcut + notification

### Checklist
- [ ] Hook rename path
- [ ] Detect if renamed session is bubbled
- [ ] Update bubble notification label
- [ ] Update shortcut short label

## 8. Remove bubble when session exits

### Goal
No stale bubble after process/session ends.

### Changes
- hook `TermuxService.onTermuxSessionExited()`
- remove notification + shortcut + bubbled state

### Checklist
- [ ] Unbubble from `onTermuxSessionExited()`
- [ ] Cancel per-session notification
- [ ] Remove dynamic shortcut
- [ ] Clear tracked state

---

# MVP task checklist

## Foundation
- [ ] Add bubble constants: session handle extra, bubble actions
- [ ] Add exact-session launch support to `TermuxActivity`
- [ ] Add minimal bubble-safe manifest entry

## Bubble UI
- [ ] Create `BubbleSessionActivity`
- [ ] Create bubble layout
- [ ] Bind activity to service
- [ ] Attach requested `TerminalSession`
- [ ] Add top bar actions: full app / unbubble / close

## Bubble backend
- [ ] Add `SessionBubbleController`
- [ ] Add per-session notification id management
- [ ] Add bubble notification builder
- [ ] Add dynamic shortcut publishing
- [ ] Add bubble/unbubble service methods

## User actions
- [ ] Add `Bubble session` action to at least one obvious UI path
- [ ] Trigger auto-expand on explicit bubble request

## Sync / cleanup
- [ ] Update bubble title on rename
- [ ] Remove bubble on session exit
- [ ] Gracefully handle invalid/missing session handle

## Manual testing
- [ ] Bubble one session
- [ ] Bubble multiple sessions
- [ ] Open exact session from each bubble
- [ ] Rename bubbled session and verify label update
- [ ] Kill bubbled session and verify bubble removal
- [ ] Tap `Open in full Termux` and verify exact session opens
- [ ] Verify existing service notification still behaves normally
- [ ] Verify app does not crash when bubbles disabled/unsupported

---

# Suggested file touch list

Likely touched existing files:
- `app/src/main/AndroidManifest.xml`
- `app/src/main/java/com/termux/app/TermuxActivity.java`
- `app/src/main/java/com/termux/app/TermuxService.java`
- `app/src/main/java/com/termux/app/terminal/TermuxTerminalSessionActivityClient.java`
- `termux-shared/src/main/java/com/termux/shared/termux/TermuxConstants.java`
- `termux-shared/src/main/java/com/termux/shared/notification/NotificationUtils.java`
- `app/src/main/res/values/strings.xml`

New files likely:
- `app/src/main/java/com/termux/app/BubbleSessionActivity.java`
- `app/src/main/java/com/termux/app/bubbles/SessionBubbleController.java`
- `app/src/main/java/com/termux/app/bubbles/SessionShortcutHelper.java`
- maybe bubble-specific client/view helper classes
- bubble activity layout xml

---

# Risks

## OEM / settings variability

Even correct code may fail to visibly bubble when:
- user disabled bubbles globally
- user disabled app bubbles
- OEM skin changes behavior

Need robust fallback.

## Keyboard / focus quirks

Terminal input inside embedded bubble window may need special handling.
This is probably the biggest UI risk.

## Notification churn

Too many live updates could make notification handling noisy.
For MVP, keep updates minimal.

## Old targetSdk / future migration

Current targetSdk is older.
Still okay for guarded implementation, but future upgrades may tighten behavior.
Using shortcuts now reduces future pain.

---

# Non-goals for MVP

Not doing these yet:
- [ ] bubble-all-sessions mode
- [ ] auto-bubble new sessions by setting
- [ ] bubble session switcher
- [ ] restore bubble state after reboot/process death
- [ ] Android 10 special handling polish
- [ ] advanced per-session notification settings UI
- [ ] transcript previews or rich message-style content

---

# UX / view follow-up notes from device testing

- [ ] Bring configurable extra keys into bubble UI using the same `termux.properties` mechanism as the main terminal UI.
- [ ] Revisit whether bubble needs any visible top bar actions at all. Current preference: terminal-first surface with minimal or no chrome.
- [ ] Keep exact-session `TermuxActivity` handoff support even if `Open in full Termux` is removed from the visible bubble UI.
- [ ] Investigate bubble terminal viewport padding / clipping. Some characters are partially out of view in the expanded bubble.
- [ ] Review bubble insets and margins so terminal content is fully visible.

# Next steps after MVP

Once MVP works, finalize the feature properly.

## v1 polish checklist
- [ ] Add `Unbubble session` action in both full app and bubble UI consistently
- [ ] Add bubble status indicator in session list
- [ ] Add settings toggle for showing bubble actions in UI
- [ ] Add better session labels/subtitles from title/cwd
- [ ] Add differentiated bubble/shortcut icons per session instead of a generic app icon
- [ ] Add user-visible error messaging when bubbles disabled
- [ ] Add tests around exact-session intent routing
- [ ] Add tests for bubble cleanup on session exit

## v1.1 / advanced
- [ ] Optional persistent restoration of bubbled sessions while session still exists
- [ ] Optional `auto-bubble on explicit creation` flows
- [ ] Android 10 best-effort support if worth it
- [ ] More refined top bar / compact controls in bubble
- [ ] Better bubble suppression logic when same session already visible in full app
- [ ] Potential `LocusId` integration for better system matching
- [ ] Investigate using the system-managed bubble overflow (`+` / recent bubbles) for dismissed session bubbles instead of hard-unbubbling them immediately.
  - Current behavior likely fights overflow because bubble dismiss triggers our `deleteIntent`, which fully unbubbles the session.
  - Desired behavior for a future pass: explicit `Unbubble session` or session exit fully removes it; casual bubble dismiss should allow SystemUI to keep it in overflow so the user can restore it later.
  - Need to verify how session title / notification updates interact with overflowed bubbles so we do not accidentally re-promote them.

## Final quality bar

Feature is “done” when:
- exact session routing is reliable
- multiple session bubbles coexist cleanly
- bubble removal is always correct
- rename sync works reliably
- keyboard/input is stable in bubble window
- no regression to normal Termux activity/session behavior

---

# Recommended implementation order

1. exact-session launch support in `TermuxActivity`
2. `BubbleSessionActivity` skeleton with service bind + session attach
3. basic `SessionBubbleController`
4. one-session bubble notification posting
5. explicit `Bubble session` UI action
6. multi-session tracking
7. rename sync + cleanup on exit
8. polish bubble controls and failure handling

This order gives a working vertical slice early and reduces risk.