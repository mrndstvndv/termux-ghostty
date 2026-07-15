# Implementation Plan: Extra Keys Support in Jetpack Compose

This plan outlines the re-implementation of the "Extra Keys" toolbar in the `compose-app` module, replicating the behavior of the legacy `app` (XML/Java) version, and adding a settings UI with live preview on the connection dashboard screen.

## 1. Research & Analysis of Legacy Behavior

The legacy Termux extra keys are implemented in `ExtraKeysView.java` and `TerminalExtraKeys.java` with the following key behaviors:

1.  **Key Matrix Parse**:
    *   Extra keys configurations are parsed from a JSON array of arrays string (e.g. `[['ESC', 'TAB', ...]]`).
    *   Each key configuration can be a simple string (e.g., `'ESC'`), a complex object with custom labels and popup configurations (e.g., `{key: '-', popup: '|'}`), or macros (e.g., `{macro: 'ALT j', display: 'A-j'}`).
2.  **Special Modifiers (`CTRL`, `ALT`, `SHIFT`, `FN`)**:
    *   Clicking toggles the modifier's active state.
    *   Long-pressing locks the modifier (sticky lock).
    *   When the terminal client receives a regular keystroke (soft keyboard or physical keyboard), it queries the state of the modifiers via `readSpecialButton(..., autoSetInActive = true)`.
    *   If active and *not* locked, the modifier is consumed and automatically resets to inactive after the keystroke.
3.  **Repetitive Keys**:
    *   Certain keys (`UP`, `DOWN`, `LEFT`, `RIGHT`, `BKSP`, `DEL`, `PGUP`, `PGDN`) auto-repeat when long pressed.
4.  **Swipe-up Popups**:
    *   If a button has a popup defined (e.g., `{key: '-', popup: '|'}`), swiping up (dragging `y < 0`) displays a tooltip popup.
    *   Releasing the drag while the popup is active triggers the popup key instead.
5.  **View Integration**:
    *   Virtual key presses are simulated using `TerminalView.onKeyDown(keyCode, keyEvent)` with corresponding metadata states (e.g. `KeyEvent.META_CTRL_ON`), or sent as text via `TerminalView.inputCodePoint(...)`.

---

## 2. Plan for Jetpack Compose Implementation

To bridge the gap and replicate these behaviors, we will implement the following:

### A. Core State Management: `ExtraKeysController`
We will introduce a state holder `ExtraKeysController` that:
*   Tracks the `ModifierState` (`INACTIVE`, `ACTIVE`, `LOCKED`) for `CTRL`, `ALT`, `SHIFT`, and `FN`.
*   Exposes `readControl()`, `readAlt()`, `readShift()`, and `readFn()` which consume/reset the state if in `ACTIVE` state.
*   Is shared with the `TerminalView`'s `TerminalViewClient` base via Compose `AndroidView` integration.

### B. Terminal View Client Integration
Update `TerminalWorkspaceContainer.kt` to:
*   Pass the `ExtraKeysController` into `TerminalWorkspaceContainer`.
*   Override `readControlKey()`, `readAltKey()`, `readShiftKey()`, and `readFnKey()` in the `TermuxTerminalViewClientBase` anonymous object to delegate to the controller.

### C. Gesture & Key Execution in Compose: `ExtraKeysToolbar`
Re-implement the toolbar using a Compose component:
*   Parse the extra keys configuration JSON string using the existing `ExtraKeysInfo` from the `:termux-shared` module.
*   Build a dynamic row/grid layout supporting the parsed matrix.
*   Implement touch processing using `Modifier.pointerInput` to detect:
    *   **Normal Click / Press**: Toggles modifiers, or sends keys to the current `TerminalView`.
    *   **Long Press**: Locks modifiers, or triggers a coroutine repeating a repetitive key action.
    *   **Swipe Up (Drag)**: Triggers the popup key.

### D. Settings & Live Preview UI in `DashboardScreen`
*   Add configuration controls to the dashboard (main connection screen):
    *   Toggle switch to enable/disable Extra Keys.
    *   Dropdown to select presets ("Default Double Row", "Single Row", "Arrows Only", "Custom").
    *   Text field for raw JSON layout editing (only when "Custom" is selected), with JSON validation.
*   Add a **Live Preview** of the Extra Keys toolbar directly on the dashboard screen so the user can interact with and test their layout configuration instantly.
*   Persist configuration preferences using `SharedPreferences` (`extra_keys_enabled`, `extra_keys_preset`, `extra_keys_custom_json`).

---

## 3. Step-by-Step Implementation Tasks

### Phase 1: Controller & View Client Integration
- [ ] Create `ExtraKeysController.kt` to manage sticky/lock modifier states.
- [ ] Modify `TerminalWorkspaceContainer.kt` to accept `ExtraKeysController` and wire it up to `TermuxTerminalViewClientBase`.
- [ ] Modify `TerminalWorkspaceScreen.kt` to instantiate and hold the `ExtraKeysController`.

### Phase 2: Toolbar Component & Interaction
- [ ] Update `ExtraKeysToolbar.kt` to take `ExtraKeysController`, parsed `ExtraKeysInfo`, and a reference to the active `TerminalView` (or a helper key-dispatch function).
- [ ] Implement robust Compose gesture detection for drag/swipe-up and long-press auto-repeat.
- [ ] Replicate key dispatching (using `onKeyDown` and `inputCodePoint`) matching `TerminalExtraKeys.java`.

### Phase 3: Dashboard Configuration UI & Preview
- [ ] Update `DashboardScreen.kt` to support extra keys settings.
- [ ] Add layout selector and custom JSON input box.
- [ ] Render the Live Preview of the `ExtraKeysToolbar` inside the dashboard.
- [ ] Modify `MainActivity.kt` to load the preferences on start, save them, and pass them to both screens.
