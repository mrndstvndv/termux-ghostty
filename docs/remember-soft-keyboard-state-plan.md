# Plan: remember-soft-keyboard-state

## Goal

Add a new `termux.properties` config:

- `remember-soft-keyboard-state`

When enabled, Termux should remember the last soft-keyboard state and restore it when the terminal activity is resumed/opened.

## Required behavior

Priority on activity resume/open:

1. **Physical keyboard override wins**
   - Existing prefs still take precedence:
     - `soft_keyboard_enabled=false`
     - or `soft_keyboard_enabled_only_if_no_hardware=true` and a hardware keyboard is connected
   - In these cases, soft keyboard stays disabled/hidden.
   - This must **not overwrite** the remembered state.

2. **Remembered state applies if enabled**
   - If `remember-soft-keyboard-state=true` and a remembered runtime state exists:
     - restore `visible` if it was last visible
     - restore `hidden` if it was last hidden

3. **Fallback to current behavior**
   - If no remembered state exists yet:
     - use existing `hide-soft-keyboard-on-startup` logic
     - otherwise keep current default startup/show behavior

## Why this is plausible

Current keyboard behavior is already centralized enough:

- `TermuxActivity.onResume()` calls `TermuxTerminalViewClient.onResume()`
- `TermuxTerminalViewClient.setSoftKeyboardState()` already decides show/hide/disable behavior
- `KeyboardUtils.shouldSoftKeyboardBeDisabled(...)` already handles the physical keyboard override
- `TerminalView.onKeyPreIme()` gives a good hook for back-driven IME dismissal

This is mostly a state-management change, not a large architecture change.

## Design decisions

### 1) Use kebab-case property name

Use:

- `remember-soft-keyboard-state`

Not snake_case, to match existing property naming.

### 2) Store remembered state in shared preferences, not in termux.properties

`termux.properties` should remain user config.
Remembered keyboard state is runtime state.

Use a runtime preference for the last known soft-keyboard state.

### 3) Use tri-state storage

Use:

- `visible`
- `hidden`
- `unknown`

Reason:
- first launch needs “no remembered state yet”
- plain boolean cannot represent that cleanly

## File-by-file plan

### A. Add the new property

Files:

- `termux-shared/src/main/java/com/termux/shared/termux/settings/properties/TermuxPropertyConstants.java`
- `termux-shared/src/main/java/com/termux/shared/termux/settings/properties/TermuxSharedProperties.java`

Changes:

1. Add constant:
   - `KEY_REMEMBER_SOFT_KEYBOARD_STATE = "remember-soft-keyboard-state"`

2. Register it in:
   - `TERMUX_APP_PROPERTIES_LIST`
   - `TERMUX_DEFAULT_FALSE_BOOLEAN_BEHAVIOUR_PROPERTIES_LIST`

3. Add getter in `TermuxSharedProperties`:
   - `shouldRememberSoftKeyboardState()`

4. Update changelog/version block in `TermuxPropertyConstants.java`

### B. Add runtime preference for remembered state

Files:

- `termux-shared/src/main/java/com/termux/shared/termux/settings/preferences/TermuxPreferenceConstants.java`
- `termux-shared/src/main/java/com/termux/shared/termux/settings/preferences/TermuxAppSharedPreferences.java`

Changes:

1. Add pref key, for example:
   - `last_soft_keyboard_state`

2. Define allowed values:
   - `visible`
   - `hidden`
   - `unknown`

3. Add helpers in `TermuxAppSharedPreferences`:
   - getter for last keyboard state
   - setter for last keyboard state
   - optional clear helper

## C. Refactor keyboard state transitions into helpers

File:

- `app/src/main/java/com/termux/app/terminal/TermuxTerminalViewClient.java`

Add small internal helpers so all paths use the same state updates.

Suggested helpers:

- `showSoftKeyboardAndRemember()`
- `hideSoftKeyboardAndRemember()`
- `disableSoftKeyboardByPolicy()`
- `restoreRememberedSoftKeyboardState()`

Rules:

- show -> remember `visible`
- hide -> remember `hidden`
- policy-forced hide because of physical keyboard override -> **do not** change remembered state

This avoids scattered logic and accidental mismatch.

## D. Update resume/open behavior

File:

- `app/src/main/java/com/termux/app/terminal/TermuxTerminalViewClient.java`

Update `setSoftKeyboardState(boolean isStartup, boolean isReloadTermuxProperties)` in this order:

1. Check physical keyboard override first using existing:
   - `KeyboardUtils.shouldSoftKeyboardBeDisabled(...)`

2. If override is active:
   - disable/hide keyboard
   - request terminal focus
   - do not overwrite remembered state
   - stop further auto-show logic

3. If `remember-soft-keyboard-state=true`:
   - read runtime remembered state
   - if `visible`:
     - clear disable flags
     - request focus
     - delayed show
   - if `hidden`:
     - hide keyboard
     - request focus
     - skip auto-show
   - if `unknown`:
     - fall through to existing behavior

4. If no remembered state applies:
   - keep current `hide-soft-keyboard-on-startup` behavior
   - else keep current delayed show behavior

This preserves backward compatibility.

## E. Update all explicit keyboard actions to remember state

File:

- `app/src/main/java/com/termux/app/terminal/TermuxTerminalViewClient.java`

Update these paths to use the new helper methods:

1. `onSingleTapUp()`
   - when it shows the soft keyboard, remember `visible`

2. `onToggleSoftKeyboardRequest()`
   - show/hide mode:
     - showing -> remember `visible`
     - hiding -> remember `hidden`
   - enable/disable mode:
     - enabling + showing -> remember `visible`
     - disabling -> remember `hidden`

3. focus-change listener inside `setSoftKeyboardState()`
   - gaining relevant focus and showing keyboard -> remember `visible`
   - losing relevant focus and hiding keyboard -> remember `hidden`

## F. Track back-button IME dismissal

Problem:
- user can dismiss the soft keyboard with Back
- terminal may still keep focus
- focus listener alone may miss that
- remembered state can become stale

Recommended change:

Files:

- `terminal-view/src/main/java/com/termux/view/TerminalViewClient.java`
- `terminal-view/src/main/java/com/termux/view/TerminalView.java`
- `app/src/main/java/com/termux/app/terminal/TermuxTerminalViewClient.java`

Plan:

1. Add a new callback to `TerminalViewClient`, for example:
   - `onSoftKeyboardDismissed()`

2. In `TerminalView.onKeyPreIme()`:
   - when `KEYCODE_BACK` is received
   - if it is not being remapped to escape
   - and not consumed by text-selection handling
   - notify client of keyboard dismissal before passing through

3. In `TermuxTerminalViewClient`:
   - implement callback and remember `hidden`

This improves correctness across resume/open.

## G. Scope of persistence

Persist remembered state in shared preferences so it survives:

- background -> foreground resume
- activity recreation
- process death / cold reopen

This matches the requested “resumed/opened” behavior better than in-memory state.

## Compatibility / fallback behavior

If `remember-soft-keyboard-state=false`:

- behavior should remain unchanged

If `remember-soft-keyboard-state=true`:

- remembered state wins over startup-default behavior
- but physical keyboard override still wins over remembered state
- if remembered state is `unknown`, old startup behavior remains in effect

## Suggested precedence summary

On resume/open:

1. policy disable due to soft-keyboard prefs / hardware keyboard
2. remembered state, if enabled and known
3. `hide-soft-keyboard-on-startup`
4. existing default auto-show flow

## Test plan

### 1. Baseline compatibility

- `remember-soft-keyboard-state=false`
- verify current behavior is unchanged

### 2. Remember visible state

- enable `remember-soft-keyboard-state=true`
- open keyboard
- background app
- resume app
- keyboard should still be visible

### 3. Remember hidden state

- enable `remember-soft-keyboard-state=true`
- hide keyboard
- background app
- resume app
- keyboard should stay hidden

### 4. Physical keyboard override

- remembered state = visible
- set `soft_keyboard_enabled_only_if_no_hardware=true`
- connect hardware keyboard
- resume app
- soft keyboard must remain hidden
- disconnect hardware keyboard
- reopen app
- remembered visible state should be restorable

### 5. Startup fallback

- `remember-soft-keyboard-state=true`
- remembered state = unknown
- `hide-soft-keyboard-on-startup=true`
- app should start hidden

### 6. Toggle behavior modes

Test both:

- `soft-keyboard-toggle-behaviour=show/hide`
- `soft-keyboard-toggle-behaviour=enable/disable`

Ensure remembered state updates correctly in both modes.

### 7. Back-key dismissal

- show keyboard
- dismiss with Back
- background app
- resume app
- keyboard should remain hidden

### 8. Activity recreation

- rotate screen / recreate activity / reload style
- ensure remembered state stays correct
- ensure no regressions in startup flow

## Risks

### 1. IME visibility detection is imperfect across devices

Android IME behavior varies by keyboard app and ROM.
Do not rely only on `isSoftKeyboardVisible()` for state tracking.
Prefer tracking explicit app actions + `onKeyPreIme()` dismissal hook.

### 2. Regressions from scattered keyboard calls

This is why the plan includes helper methods in `TermuxTerminalViewClient`.
Centralizing state transitions reduces mistakes.

## Final recommendation

Implement this with:

- new property: `remember-soft-keyboard-state`
- runtime pref: `last_soft_keyboard_state = visible|hidden|unknown`
- precedence:
  1. physical keyboard override
  2. remembered state
  3. existing startup logic

This is feasible, low-risk if done carefully, and fits the current architecture well.
