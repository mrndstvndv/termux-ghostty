# Local terminal rendering profile

## Canonical runner

Run the shell script against a connected arm64 device:

```sh
ANDROID_SERIAL=<device-serial> ./scripts/profile-local-terminal-rendering.sh
```

The script builds the arm64 debug app, installs and launches it, and uses the already-open local terminal or opens **Local Terminal** from Home. It runs `seq 1 5000`, taps the terminal’s **HOME** extra key one second later, then waits 15 seconds before collecting metrics. That tap verifies that extra-key handling stays responsive while Ghostty is appending; the runner fails if Android shows an ANR dialog. Set `ECTO_PROFILE_EXTRA_KEY_PROBE=0` to omit it, or override its device-specific coordinates with `ECTO_PROFILE_EXTRA_KEY_PROBE_X` and `ECTO_PROFILE_EXTRA_KEY_PROBE_Y`.

The fixed settle window is required because the accessibility tree already contains `5000` in the typed command; it is not a reliable completion signal. Override it for a slower or faster device with `ECTO_PROFILE_SETTLE_SECONDS=<seconds>`.

```text
compose-app/build/reports/terminal-profile/terminal-burst-profile.json
compose-app/build/reports/terminal-profile/terminal-burst-profile.png
```

The JSON includes `dumpsys gfxinfo` frame metrics and Base64-encoded raw output. The screenshot confirms that the final published terminal state was visible, not only accessible.

## Local shell command injection

The local terminal must own focus before injection. On the connected 1080×2400 device, **Local Terminal** is tapped at `540 388`; set `LOCAL_TERMINAL_TAP_X` and `LOCAL_TERMINAL_TAP_Y` to override that coordinate on another device.

`adb shell input text` uses `%s` for a literal space. It does **not** decode `%20`.

```sh
adb -s <device-serial> shell input text 'seq%s1%s5000'
adb -s <device-serial> shell input keyevent ENTER
```

The failed form `seq%201%205000` is sent to the terminal literally and produces `inaccessible or not found`.

## Workload semantics

`seq 1 5000` is a scroll-heavy 5,000-line terminal burst. It exercises worker publication, immutable frame adaptation, Compose frame scheduling, terminal damage tracking, glyph recording, and presentation. It is not a startup benchmark.

Treat device-specific frame metrics as observations, not fixed pass/fail thresholds. Compare persisted JSON artifacts from the same device, refresh rate, shader setting, and keyboard state.
