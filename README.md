# Termux-Ghostty

An experimental Android terminal app combining features from [Termux](https://termux.dev) and [Ghostty](https://ghostty.org).

> ⚠️ **NOTICE**: This is an **unofficial** fork and is **not affiliated with ghostty-org or termux-org**. It combines features from both projects under their respective licenses (GPLv3 and MIT).

## Features

- **Ghostty integration** — Uses Ghostty-backed terminal rendering/runtime inside the app.
- **Android session bubbles** — Open terminal sessions as native Android 11+ bubbles without killing the underlying session when the bubble UI is dismissed.
- **Bubble unread dots for OSC notifications** — Bubbled sessions use conversation-style unread state instead of separate system notifications, and opening the session clears the unread indicator.
- **Clickable terminal links** — Optional `termux.properties` support for tap-to-open links in Ghostty sessions via `terminal-onclick-url-open=true`, with `terminal-onclick-url-open-when-mouse-tracking-active=true` to prefer link taps over terminal mouse tracking.
- **Remember soft keyboard state** — Optional `termux.properties` support via `remember-soft-keyboard-state=true` to restore the last soft keyboard visibility state when reopening the app.
- **Material You theming** — Optional `termux.properties` support via `material-you-theme=disabled|light|dark|black|system` to theme the entire app (chrome + terminal ANSI palette) with wallpaper-derived Material 3 colors on Android 12+. `black` uses dynamic accent colors with pure black backgrounds. When enabled, `colors.properties` is ignored. Recreates automatically on wallpaper changes.

## Status

Experimental and fast-moving.
