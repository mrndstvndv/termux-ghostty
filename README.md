# Termux-Ghostty

An experimental Android terminal application combining features from [Termux](https://termux.dev) and [Ghostty](https://ghostty.org).

> ⚠️ **NOTICE**: This is an **UNOFFICIAL** fork and is **NOT affiliated with ghostty-org or termux-org**. It combines features from both projects under their respective licenses (GPLv3 and MIT).

## Notable additions in this fork

- Ghostty-backed terminal rendering/runtime integration
- Native Android **terminal session bubbles**
- `termux.properties` support for remembering the soft keyboard visibility state

## Android session bubbles

This fork supports native Android bubbles for terminal sessions on **Android 11+**.

Current behavior:
- **one bubble = one terminal session**
- multiple session bubbles can exist at the same time
- sessions continue to live in `TermuxService`
- dismissing a bubble removes the bubble UI, but **does not kill the underlying shell session**
- renamed sessions update their bubble label
- bubble icons are differentiated per session
- the bubble UI is terminal-first, with configurable extra keys and pinch-to-zoom support

Current entry points:
- terminal context menu → `Bubble session`
- session list long-press menu → `Bubble session`

Notes:
- bubbles are notification-driven, so OEM behavior and system bubble settings still apply
- on some devices, bubbling may still depend on the app/channel bubble settings being enabled

## `termux.properties`

Termux reads configuration from:
- `~/.termux/termux.properties`
- `~/.config/termux/termux.properties`

### `remember-soft-keyboard-state`

```properties
remember-soft-keyboard-state=true
```

Default:
- `false`

Behavior:
- when `true`, the main Termux activity remembers whether the soft keyboard was last **visible** or **hidden**
- that remembered state is restored when the activity is reopened/resumed
- when `false`, keyboard behavior follows the normal startup/toggle flow instead
- this works well together with the existing soft keyboard configuration options, including `hide-soft-keyboard-on-startup`

Use this if you want Termux to preserve your last keyboard visibility choice across activity reopen/resume instead of always falling back to startup behavior.

## Status

This fork is experimental and fast-moving. Expect behavior and UX around newer features to keep evolving.
