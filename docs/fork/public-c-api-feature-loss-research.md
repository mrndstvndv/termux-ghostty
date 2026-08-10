# Public C API feature-loss assessment

## Scope and conclusion

The public C terminal API is still useful for the normal terminal-parser path: bytes written through the API are parsed by the terminal implementation, and the parser can produce the ordinary terminal effects internally. The loss happens at the boundary between those internal effects and the application adapter. In other words, this is not a claim that the parser cannot recognize a sequence; it is a claim that the public C surface does not expose every effect, state transition, or interception point needed by the app.

The relevant boundary is visible in the project adapter at `terminal-emulator/src/main/zig/src/termux_ghostty.zig`, and in the pinned C terminal implementation/API at `/tmp/ghostty-ours/src/terminal/c/terminal.zig`, `/tmp/ghostty-ours/src/terminal/stream_terminal.zig`, and `/tmp/ghostty-ours/include/ghostty/vt/terminal.h`.

## Effects that are lost at the public boundary

| Feature | What the public C surface does not expose | Consequence | Mitigation options |
| --- | --- | --- | --- |
| Progress OSC 9;4 | No app-facing progress callback/state/generation stream equivalent to the internal progress effect | The adapter cannot reliably update progress UI, clear it, or distinguish newer state from stale state | Add a progress callback (including state and generation) to the C API; expose a pollable effect queue; or implement project-specific parsing outside the C terminal |
| Desktop notifications (OSC 9/777) | No notification callback/queue carrying the notification payload | Notifications are parsed, if recognized internally, but cannot be delivered to the host notification subsystem | Add a notification callback or queued notification effect; alternatively parse these OSC forms in the adapter (with the associated duplication and ordering risk) |
| Color changes | No color-change callback | Dynamic palette changes cannot be forwarded to app/UI state that needs to observe them | Add a palette/color-change callback or provide a versioned palette snapshot API |
| Append-result bitmask | `vt_write` returns `void`, so there is no append-result bitmask for the caller | The caller cannot learn from the write call which effects or output classes were appended without another API | Return a result object/bitmask from a new write entry point; or expose a drainable effect/output queue and sequence number |
| Kitty keyboard mode | No direct setter for the Kitty keyboard flag | The host cannot set the desired keyboard protocol state directly when restoring or synchronizing session state | Add a direct setter/getter in the public API; otherwise send the appropriate escape sequence through the PTY and observe whatever state is exposed |
| Raw action interception | No callback/interception hook for raw parser actions before they are consumed | The adapter cannot implement host-specific handling for actions that the terminal library consumes or ignores | Add an action callback with an explicit ownership/consume contract; or keep a separate adapter parser for only the required actions |

These are feature losses, not necessarily parser losses. The implementation can maintain internal state or execute an internal effect while the public API gives the adapter no stable way to observe or act on it.

## Effects that remain available

The public C path is sufficient for the normal terminal behavior needed by the host. In particular, the adapter can retain access to:

- PTY replies;
- bell;
- title;
- clipboard operations;
- `XTVERSION`;
- device attributes;
- `ENQ` handling;
- size reports; and
- color-scheme queries.

These should be treated as supported parser effects/queries rather than reimplemented in the app adapter. The callback option table is documented in `/tmp/ghostty-ours/include/ghostty/vt/terminal.h:87-96`; the C implementation installs the corresponding trampolines in `/tmp/ghostty-ours/src/terminal/c/terminal.zig:354-368`. CSI 21t title reporting is handled internally and emitted through the write-PTY path (`/tmp/ghostty-ours/src/terminal/stream_terminal.zig:440-482`).

## Evidence map

- The project handles notification and progress actions at `termux_ghostty.zig:1129-1134`; progress state transitions are in `:1343-1351`.
- The project marks reverse-color changes at `termux_ghostty.zig:1201-1207`, and computes its result flags after each write at `:1731-1775`.
- The project seeds Kitty keyboard flags on both screens at `termux_ghostty.zig:1497-1511`.
- The public C effect storage contains only the callback fields listed at `/tmp/ghostty-ours/src/terminal/c/terminal.zig:65-76`; its write entry point returns `void` at `:378-385`.
- The C stream ignores progress and desktop-notification actions at `/tmp/ghostty-ours/src/terminal/stream_terminal.zig:308-313`.
- The public callback option table is at `/tmp/ghostty-ours/include/ghostty/vt/terminal.h:87-96`; Kitty graphics options/data are documented at `:764-824` and `:1122-1170`.

## Work that remains adapter-specific

Using the public C API does not remove application policy and persistence work. The following remain adapter responsibilities:

- app-specific snapshot serialization;
- link extraction;
- worker scheduling and coalescing; and
- mouse policy.

Those concerns are not evidence that normal terminal parsing is missing. They are host integration behavior, so they need an explicit adapter layer regardless of which parser API is used.

## APC/DCS and Kitty graphics

Our current APC/DCS actions are ignored. Therefore Kitty graphics are **not a current project feature** and should not be presented as an additional feature loss that this migration must solve now. The public C API actually has Kitty graphics configuration/data surfaces (`/tmp/ghostty-ours/include/ghostty/vt/terminal.h:764-824,1122-1170`), so a future migration could gain Kitty graphics rather than lose it. The current project adapter does not use them.

## Verdict

Adopting the public C API preserves the core terminal-parser behavior and the listed PTY/UI effects. It does, however, intentionally narrow the observable effect surface. The six losses above matter only where the app needs host-visible progress, notifications, palette updates, write-result accounting, direct Kitty keyboard synchronization, or raw action interception.

The low-risk path is to use the public C API for normal parsing and keep the existing adapter work for snapshots, links, workers/coalescing, and mouse policy. If progress, notifications, palette updates, or exact write accounting are required product features, the preferred mitigation is to extend the C API with a versioned callback/effect-queue surface (rather than duplicate terminal parsing in the adapter). A direct Kitty keyboard setter and a raw-action hook should be added only when a concrete host use case requires them. On that basis, the public C API is viable for the current project, with the six boundary losses documented as deliberate follow-up seams rather than blockers.

## Source references

- `terminal-emulator/src/main/zig/src/termux_ghostty.zig` — project-side adapter and the effects it expects to bridge.
- `/tmp/ghostty-ours/src/terminal/c/terminal.zig` — C-facing terminal implementation and internal effect handling.
- `/tmp/ghostty-ours/src/terminal/stream_terminal.zig` — stream/write path and the observable output boundary.
- `/tmp/ghostty-ours/include/ghostty/vt/terminal.h` — public terminal C declarations and write/callback contract.
