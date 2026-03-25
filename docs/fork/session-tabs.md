# Session Tabs View Mode

A new view mode that shows session tabs horizontally above the terminal instead of using the drawer sidebar.

## Configuration

Add to `~/.termux/termux.properties`:

```properties
use-session-tabs=true
```

Default: `false` (drawer mode)

## Features

- **Horizontal tab strip** - Sessions shown as scrollable tabs at the top of the terminal
- **Quick switching** - Tap a tab to switch to that session
- **New session** - Tap the `+` button to create a new session
- **Failsafe mode** - Long-press the `+` button to create a failsafe session
- **Session context menu** - Long-press a tab to rename or bubble the session
- **Visual indicators**:
  - Current session tab is highlighted (bold + background)
  - Finished sessions show strikethrough text
  - Failed sessions (non-zero exit) show red text

## Usage

1. Enable in `termux.properties`
2. Restart Termux
3. Tabs appear at the top of the terminal view
4. Tap `+` to add sessions, tap tabs to switch

The drawer is still accessible via swipe from the left edge for:
- Settings
- Keyboard toggle
- Failsafe new session (via drawer button)
