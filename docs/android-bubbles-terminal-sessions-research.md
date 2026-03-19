# Android Bubbles research for Termux terminal sessions

## Goal

Bubble terminal sessions so they can be minimized into a system bubble and reopened fast.

## Short answer

Yes, **feasible**.

But the clean path is **not** to reuse `TermuxActivity` directly.

Best design:
- keep sessions/processes in `TermuxService` as today
- add a **dedicated bubble activity** for a single session
- expose a **per-session bubble notification**
- identify each bubbled session by `TerminalSession.mHandle`
- on Android 11+, back bubbles with **dynamic conversation shortcuts**

## What Android bubbles require

From Android docs:
- bubbles are notification-driven UI
- expanded bubble content comes from an **activity**
- that activity must be `android:resizeableActivity="true"`
- and `android:allowEmbedded="true"`
- if multiple bubbles of same type exist, Android 10 and lower need `documentLaunchMode="always"`
- on Android 11+ a robust implementation should reference a **sharing/conversation shortcut**
- user or OEM can disable bubbles globally or per-app

Important docs:
- https://developer.android.com/develop/ui/views/notifications/bubbles
- https://developer.android.com/guide/topics/ui/conversations
- https://developer.android.com/guide/topics/manifest/activity-element

## Current app state relevant to bubbles

### Good news

The core architecture already fits bubble use pretty well.

`TermuxService` owns terminal sessions and outlives the UI:
- foreground notification built in `app/src/main/java/com/termux/app/TermuxService.java`
- sessions live in `mShellManager.mTermuxSessions`
- sessions can already be reattached by activity clients
- sessions can be looked up by handle via `getTerminalSessionForHandle()`

Relevant files:
- `app/src/main/java/com/termux/app/TermuxService.java`
- `app/src/main/java/com/termux/app/TermuxActivity.java`
- `app/src/main/java/com/termux/app/terminal/TermuxTerminalSessionActivityClient.java`

This matters because bubble activities are disposable. Android can destroy the expanded bubble UI while the terminal process keeps running. Termux already separates session lifetime from activity lifetime, which is exactly what we want.

### Current blockers

#### 1. `TermuxActivity` is a bad bubble host

Manifest today:
- `TermuxActivity` uses `launchMode="singleTask"`
- no `allowEmbedded`
- no bubble-specific document behavior

File:
- `app/src/main/AndroidManifest.xml`

That clashes with bubble expectations.

Why:
- bubble activities should allow embedded launch
- multiple bubbled sessions want multiple activity instances
- `documentLaunchMode="always"` requires `launchMode="standard"`
- changing the main activity launch model would be risky for normal app navigation

#### 2. notifications are app-level, not session-level

Current foreground notification shows counts like `N sessions, M tasks`.
It opens the main activity.

File:
- `app/src/main/java/com/termux/app/TermuxService.java`

For bubbles, the better mental model is:
- **one session = one bubble candidate**

#### 3. no bubble metadata today

Current notification helpers build plain `Notification.Builder` objects.
No `BubbleMetadata`, no shortcut id, no `Person`, no conversation channel setup.

Files:
- `termux-shared/src/main/java/com/termux/shared/notification/NotificationUtils.java`
- `app/src/main/java/com/termux/app/TermuxService.java`

#### 4. current targetSdk is old

From `gradle.properties`:
- `minSdkVersion=21`
- `targetSdkVersion=28`
- `compileSdkVersion=36`

This has 2 implications:
- a prototype can still be built with API guards
- but a future-proof bubble design should already follow Android 11+ conversation rules

## Feasibility assessment

## Can it work technically?

Yes.

Because:
- session state already lives in a service, not the activity
- sessions already have a stable lookup key: `mHandle`
- the app already knows how to rebind UI to existing sessions
- the service already owns the notification lifecycle

That combination makes bubbles much more realistic here than in apps where activity == session.

## What will probably not work well

Using the existing `TermuxActivity` as the bubble activity.

That would mix:
- normal app UX
- drawer/session list UX
- singleTask launch behavior
- bubble embedded/multi-instance requirements

Too much risk.

## Recommended implementation shape

### Option A — recommended: dedicated bubble session activity

Create a new activity, something like:
- `BubbleSessionActivity`

Behavior:
- bind to `TermuxService`
- read a `sessionHandle` extra from the launching intent
- call `getTerminalSessionForHandle(sessionHandle)`
- attach that session to a `TerminalView`
- keep UI minimal: terminal only, maybe a tiny top bar for close/open-full-app

Manifest traits for that activity:
- `android:resizeableActivity="true"`
- `android:allowEmbedded="true"`
- `android:launchMode="standard"`
- `android:documentLaunchMode="always"` for Android 10 compatibility

Why this is best:
- isolates bubble behavior
- avoids destabilizing main app launch semantics
- allows multiple session bubbles

### Option B — lower effort prototype: bubble only the current session

Simpler MVP:
- add action: `Bubble current session`
- create one bubble notification for current session
- launch dedicated bubble activity with current `mHandle`

Pros:
- smallest feature slice
- directly matches your stated goal

Cons:
- not full multi-session bubble management yet

## Session identity model

Use `TerminalSession.mHandle` as the bubble/conversation id.

Why:
- already used for restoring current session
- already resolvable through `TermuxService.getTerminalSessionForHandle()`
- stable enough for the life of a session

So:
- notification id can be derived per session
- dynamic shortcut id can equal the session handle
- bubble intent should include the session handle

## Notification model

Recommended:
- keep existing service notification as-is for service lifetime
- add **separate notifications for bubbled sessions**

Each bubbled session notification should include:
- bubble metadata
- per-session pending intent to `BubbleSessionActivity`
- session label/title
- session icon
- shortcut id on Android 11+

Avoid trying to convert the single app-wide foreground notification into the bubble itself.
That notification represents the service, not a single session.

## Android 11+ conversation/shortcut guidance

If you want this to survive future targetSdk bumps, do it the modern way now.

Per bubbled session:
- publish a long-lived dynamic shortcut
- set a `Person`
- attach shortcut id to the notification
- attach bubble metadata to the notification

Even though terminal sessions are not literal chats, Android’s bubble system is now tied closely to conversation surfaces. A "session as conversation" mapping is the practical way to integrate.

Suggested mapping:
- shortcut id: session handle
- short label: session name or `[n] shell`
- person name: same label
- icon: Termux icon or a session icon

## Lifecycle fit

Bubble UI destruction is **not** a problem here.

Android docs say the expanded bubble activity can be destroyed when collapsed/dismissed.
That is okay because:
- the shell keeps running in `TermuxService`
- reopening the bubble can rebind and reattach to the same session

This is one of the strongest arguments that bubbles fit Termux well.

## UX recommendation

Do **not** auto-bubble every new session.

Better UX:
- explicit action: `Bubble this session`
- maybe also `Unbubble this session`
- optional setting: `Remember bubbled sessions until they exit`

Reason:
- bubble stacks get messy fast
- users may have many sessions
- bubbles should feel like pinning a quick-access session, not mirroring the whole sidebar

## Suggested MVP

1. Add `BubbleSessionActivity`
2. Add a context menu or overflow action on current session: `Bubble session`
3. Build one per-session bubble notification
4. Pass `sessionHandle` in bubble intent
5. Rebind activity to existing session
6. Cancel bubble notification when session exits

This would prove the concept without changing core navigation.

## Major risks / caveats

### 1. OEM behavior

Bubble support varies by vendor skin.
Some devices hide it, limit it, or disable it by default.

### 2. user settings can block it

Even if implementation is correct:
- app notifications may be off
- bubbles may be disabled globally
- bubbles may be disabled per app/channel

Need fallback to normal notification/open-app flow.

### 3. terminal UI in bubble is smaller

A full Termux UI with drawer/toolbar is not a great fit in bubble size.
That is another reason to use a dedicated minimal activity.

### 4. keyboard/input edge cases

Soft keyboard + terminal + embedded bubble window may need testing.
Current keyboard handling in `TermuxTerminalViewClient` is tuned for the full activity.
A bubble activity may need a slimmer dedicated view client.

### 5. targetSdk future changes

Current targetSdk 28 makes some parts less strict today.
If the app later moves to 30+, shortcuts/conversation compliance becomes much more important.

## Rough integration plan

### Code areas likely touched

- `app/src/main/AndroidManifest.xml`
- `app/src/main/java/com/termux/app/TermuxService.java`
- `termux-shared/src/main/java/com/termux/shared/notification/NotificationUtils.java`
- new bubble activity + maybe new client classes
- session list/context menu code for bubble/unbubble action

### New pieces likely needed

- `BubbleSessionActivity`
- `BubbleSessionActivityClient` or reuse a slimmed session client
- `SessionBubbleController` helper owned by service
- shortcut publisher helper
- per-session bubble notification ids

## Alternative already in the ecosystem: Termux:Float

There is already an official plugin app: **Termux:Float**.

What it does:
- floating terminal window
- minimize/show behavior
- purpose-built for overlay-style terminal access

Evidence in ecosystem/repo:
- this app already exposes `Termux:Float` settings when installed
- constants for the plugin exist in `TermuxConstants`
- external repo: `termux/termux-float`

Why this matters:
- your end goal is very close to what `Termux:Float` already solves
- if you want a floating terminal **today**, that path is lower risk

Tradeoff:
- `Termux:Float` uses overlay-style behavior and needs overlay permission
- Android bubbles are more system-native and permission-light, but more constrained

## Recommendation

If the goal is **native Android minimize/reopen behavior inside main Termux**, I would do this:

1. implement a **dedicated bubble session activity**
2. support **explicit bubbling of selected sessions**
3. keep the existing service notification separate
4. use **dynamic shortcuts per session** so the design survives a future targetSdk increase

If the goal is simply **get floating quick-access terminals ASAP**, `Termux:Float` is the faster path.

## Final verdict

**Yes, integrateable.**

Best answer is:
- **technically viable**
- **architecture already helps a lot**
- **do not retrofit bubbles onto `TermuxActivity`**
- **build a dedicated per-session bubble activity instead**

That gives you the feature you described: minimize terminal sessions into bubbles, then reopen them quickly, while the underlying shells keep running in `TermuxService`.

## Implementation checklist

- [x] Replace per-session UUID conversation ids with stable bubble slot ids.
- [x] Keep slot ids stable for the lifetime of the live session.
- [x] Reuse slot ids for future sessions after exit.
- [x] Keep dismissed bubble shortcuts alive until session exit so Android can surface them in Recent bubbles.
- [x] Report shortcut usage on explicit bubble actions.
- [x] Clear legacy bubble shortcuts on service startup so old conversation spam is pruned.
- [ ] Add a recent bubbles dialog to restore closed shells or relaunch a new session into the same slot.
