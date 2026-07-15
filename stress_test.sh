#!/bin/bash
# Stress test for Termux terminal rendering performance
# Usage: ./stress_test.sh <version_name>

set -euo pipefail

ADB="${ANDROID_HOME:-$HOME/Library/Android/sdk}/platform-tools/adb"
PKG="com.termux"
RESULTS_DIR="/tmp/termux_stress_$1"
VERSION="${1:-unknown}"
mkdir -p "$RESULTS_DIR"

get_pid() { $ADB shell pidof "$PKG" 2>/dev/null | tr -d '\r'; }

echo "============================================"
echo "  Termux Stress Test: $VERSION"
echo "============================================"
echo ""

# --- Launch ---
echo "Launching app..."
$ADB shell am force-stop "$PKG" 2>/dev/null
sleep 1
$ADB shell am start -n "$PKG/.app.TermuxActivity" 2>&1
sleep 4
$ADB logcat -c 2>/dev/null

# --- Warmup ---
echo "Warming up (500 lines)..."
$ADB shell "seq 1 500" 2>/dev/null > /dev/null
sleep 3
$ADB logcat -c 2>/dev/null

# --- Test 1: Rapid resize ---
echo "[1/4] Rapid resize (20 resizes)..."
T0=$(date +%s%3N)
for i in $(seq 1 20); do
    $ADB shell wm size "$(( 50 + RANDOM % 60 ))x$(( 20 + RANDOM % 40 ))" 2>/dev/null
    sleep 0.1
done
$ADB shell wm size reset 2>/dev/null
sleep 3
T1=$(date +%s%3N)
echo "  Wall time: $((T1 - T0))ms"

# Capture all ghostty logs
PID=$(get_pid)
$ADB logcat -d 2>/dev/null | grep -E "(Snapshot fill|Worker frame|resize)" | grep -q "$PID" && \
    $ADB logcat -d 2>/dev/null | grep -E "(Snapshot fill|Worker frame|resize)" | grep "$PID" > "$RESULTS_DIR/resize_all.log" || true
$ADB logcat -c 2>/dev/null

# --- Test 2: High-throughput output ---
echo "[2/4] High-throughput (5000 lines)..."
T0=$(date +%s%3N)
$ADB shell "seq 1 5000" 2>/dev/null > /dev/null
sleep 4
T1=$(date +%s%3N)
echo "  Wall time: $((T1 - T0))ms"

PID=$(get_pid)
$ADB logcat -d 2>/dev/null | grep -E "(Snapshot fill|Worker frame)" | grep "$PID" > "$RESULTS_DIR/throughput_all.log" || true
$ADB logcat -c 2>/dev/null

# --- Test 3: Scroll + output ---
echo "[3/4] Scroll + output (2000 lines + scroll)..."
T0=$(date +%s%3N)
$ADB shell 'for i in $(seq 1 2000); do echo "LINE_${i}_PADDING_PADDING_PADDING"; done' 2>/dev/null > /dev/null
sleep 3
for i in $(seq 1 30); do
    $ADB shell input swipe 500 1500 500 500 50 2>/dev/null
done
sleep 2
T1=$(date +%s%3N)
echo "  Wall time: $((T1 - T0))ms"

PID=$(get_pid)
$ADB logcat -d 2>/dev/null | grep -E "(Snapshot fill|Worker frame)" | grep "$PID" > "$RESULTS_DIR/scroll_all.log" || true
$ADB logcat -c 2>/dev/null

# --- Test 4: Rapid command burst ---
echo "[4/4] Rapid command burst (50 parallel)..."
T0=$(date +%s%3N)
for i in $(seq 1 50); do
    $ADB shell "echo BURST_$i" 2>/dev/null &
done
wait
sleep 3
T1=$(date +%s%3N)
echo "  Wall time: $((T1 - T0))ms"

PID=$(get_pid)
$ADB logcat -d 2>/dev/null | grep -E "(Snapshot fill|Worker frame)" | grep "$PID" > "$RESULTS_DIR/burst_all.log" || true

# --- Memory ---
echo ""
echo "Collecting memory..."
PID=$(get_pid)
if [ -n "$PID" ]; then
    $ADB shell dumpsys meminfo "$PID" 2>/dev/null > "$RESULTS_DIR/meminfo.txt"
    TOTAL_PSS=$(grep "TOTAL" "$RESULTS_DIR/meminfo.txt" 2>/dev/null | head -1 | awk '{print $2}' || echo "?")
    NATIVE_PSS=$(grep "Native Heap" "$RESULTS_DIR/meminfo.txt" 2>/dev/null | head -1 | awk '{print $3}' || echo "?")
    echo "  Total PSS: ${TOTAL_PSS}KB"
    echo "  Native Heap: ${NATIVE_PSS}KB"
fi

# --- Summary ---
echo ""
echo "============================================"
echo "  Performance Summary: $VERSION"
echo "============================================"

for test in resize throughput scroll burst; do
    f="$RESULTS_DIR/${test}_all.log"
    if [ -s "$f" ]; then
        snapshot_count=$(grep -c "Snapshot fill perf" "$f" 2>/dev/null || echo "0")
        frame_count=$(grep -c "Worker frame perf" "$f" 2>/dev/null || echo "0")
        avg_total=$(grep -o 'avgTotalMs=[0-9.]*' "$f" 2>/dev/null | tail -1 | cut -d= -f2 || echo "?")
        avg_native=$(grep -o 'avgNativeFillMs=[0-9.]*' "$f" 2>/dev/null | tail -1 | cut -d= -f2 || echo "?")
        avg_parse=$(grep -o 'avgParseMs=[0-9.]*' "$f" 2>/dev/null | tail -1 | cut -d= -f2 || echo "?")
        avg_build=$(grep -o 'avgBuildMs=[0-9.]*' "$f" 2>/dev/null | tail -1 | cut -d= -f2 || echo "?")
        coalesced_b=$(grep -o 'coalescedBuilds=[0-9]*' "$f" 2>/dev/null | tail -1 | cut -d= -f2 || echo "?")
        coalesced_w=$(grep -o 'coalescedUiWakeups=[0-9]*' "$f" 2>/dev/null | tail -1 | cut -d= -f2 || echo "?")
        echo "  [$test] snapshots=$snapshot_count frames=$frame_count avgTotal=${avg_total}ms native=${avg_native}ms parse=${avg_parse}ms build=${avg_build}ms coalesced=${coalesced_b}/${coalesced_w}"
    else
        echo "  [$test] no perf data"
    fi
done

echo ""
echo "Done: $VERSION"
echo "Results in: $RESULTS_DIR"
