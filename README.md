# Termux-Ghostty

An experimental Android terminal app combining features from [Termux](https://termux.dev) and [Ghostty](https://ghostty.org).

> ⚠️ **NOTICE**: This is an **unofficial** fork and is **not affiliated with ghostty-org or termux-org**. It combines features from both projects under their respective licenses (GPLv3 and MIT).

## Features

- **Ghostty integration** — Uses Ghostty-backed terminal rendering/runtime inside the app.
- **Android session bubbles** — Open terminal sessions as native Android 11+ bubbles without killing the underlying session when the bubble UI is dismissed.
- **Bubble unread dots for OSC notifications** — Bubbled sessions use conversation-style unread state instead of separate system notifications, and opening the session clears the unread indicator.
- **Remember soft keyboard state** — Optional `termux.properties` support to restore the last soft keyboard visibility state when reopening the app.

## Status

Experimental and fast-moving.
