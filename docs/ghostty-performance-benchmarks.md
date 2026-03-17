# Ghostty Performance Benchmarks

Repeatable manual workloads for the Ghostty backend migration.

## Log Capture

Run before each benchmark:

```sh
adb logcat -c
adb logcat -v time -s TermuxGhostty
```

Look for:
- `core perf fillSnapshot ...`
- `Snapshot fill perf ...`
- `Worker frame perf ...`
- `Frame apply perf ...`
- `onDraw perf ...`

## 1. tmux Scroll Repaint Benchmark

Generate a deterministic scrollback file inside Termux:

```sh
seq 1 200000 | awk '{printf "%06d 0123456789abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ\n", $1}' > ~/ghostty-scroll-bench.txt
pkg install -y tmux less
```

Open it inside tmux:

```sh
tmux new-session -A -s ghostty-scroll 'less -SR ~/ghostty-scroll-bench.txt'
```

Workload:
- Hold `PageUp` / `PageDown`.
- Fling scroll in the terminal view.
- Mouse-wheel scroll if available.
- Repeat once at the bottom of scrollback and once ~50% into history.

Capture:
- dirty row counts
- full rebuild count vs partial count
- worker coalesced frame counters
- `Frame apply` and `onDraw` timings

## 2. Large Output Flood

Run a deterministic flood:

```sh
yes '0123456789abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ' | head -n 200000
```

Optional wider-line flood:

```sh
yes '0123456789abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ' | head -n 100000
```

Capture:
- slow snapshot warnings
- worker frame coalescing
- draw timing under sustained output
- ANR / jank behavior while interacting with the UI

## 3. SSH Resize Storm

Setup:

```sh
pkg install -y openssh tmux
ssh your-host
```

On the remote host:

```sh
tmux new-session -A -s ghostty-resize
```

Workload:
- Alternate portrait / landscape 10 times.
- Open and close the soft keyboard 10 times.
- Enter split-screen or freeform resize if the device supports it.
- Repeat while the remote tmux pane is actively repainting, e.g.:

```sh
while true; do date; sleep 0.05; clear; done
```

Capture:
- resize-triggered rebuild timing
- coalesced frame counts
- frame apply / draw latency during resize bursts
- whether input stays responsive throughout

## Run Notes

For apples-to-apples comparisons:
- start from a fresh app launch
- use the same font size and color scheme
- run each benchmark at least 3 times
- save the relevant `adb logcat` window with the commit hash
