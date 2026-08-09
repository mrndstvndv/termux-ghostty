#!/bin/sh
set -eu

ROOT_DIR=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
SERIAL=${ANDROID_SERIAL:?Set ANDROID_SERIAL to the target device serial.}
PACKAGE=com.mrndtvndv.term
ACTIVITY="$PACKAGE/.MainActivity"
APK=${ECTO_PROFILE_APK:-"$ROOT_DIR/compose-app/build/outputs/apk/arm64/debug/ecto_debug-arm64-v8a.apk"}
REPORT_DIR=${ECTO_PROFILE_REPORT_DIR:-"$ROOT_DIR/compose-app/build/reports/terminal-profile"}
REPORT_FILE="$REPORT_DIR/terminal-burst-profile.json"
SCREENSHOT_FILE="$REPORT_DIR/terminal-burst-profile.png"
TAP_X=${LOCAL_TERMINAL_TAP_X:-540}
TAP_Y=${LOCAL_TERMINAL_TAP_Y:-388}
TIMEOUT_SECONDS=${ECTO_PROFILE_TIMEOUT_SECONDS:-30}
SETTLE_SECONDS=${ECTO_PROFILE_SETTLE_SECONDS:-15}
EXTRA_KEY_PROBE=${ECTO_PROFILE_EXTRA_KEY_PROBE:-1}
EXTRA_KEY_PROBE_X=${ECTO_PROFILE_EXTRA_KEY_PROBE_X:-539}
EXTRA_KEY_PROBE_Y=${ECTO_PROFILE_EXTRA_KEY_PROBE_Y:-2184}
if [ "$EXTRA_KEY_PROBE" = 1 ]; then
    EXTRA_KEY_PROBE_ENABLED=true
else
    EXTRA_KEY_PROBE_ENABLED=false
fi

adb_device() {
    adb -s "$SERIAL" "$@"
}

wait_for_text() {
    expected=$1
    deadline=$(( $(date +%s) + TIMEOUT_SECONDS ))
    while [ "$(date +%s)" -lt "$deadline" ]; do
        adb_device shell uiautomator dump /sdcard/ecto-profile-window.xml >/dev/null 2>&1 || true
        if adb_device shell cat /sdcard/ecto-profile-window.xml 2>/dev/null | grep -Fq "$expected"; then
            return 0
        fi
        sleep 1
    done
    echo "Timed out waiting for accessibility text: $expected" >&2
    return 1
}

wait_for_terminal_route() {
    deadline=$(( $(date +%s) + TIMEOUT_SECONDS ))
    while [ "$(date +%s)" -lt "$deadline" ]; do
        adb_device shell uiautomator dump /sdcard/ecto-profile-window.xml >/dev/null 2>&1 || true
        window_xml=$(adb_device shell cat /sdcard/ecto-profile-window.xml 2>/dev/null || true)
        case "$window_xml" in
            *"Local Terminal"*)
                printf '%s\n' home
                return 0
                ;;
            *":/ $"*)
                printf '%s\n' terminal
                return 0
                ;;
        esac
        sleep 1
    done
    echo "Timed out waiting for Local Terminal or its prompt" >&2
    return 1
}

assert_no_anr_dialog() {
    adb_device shell uiautomator dump /sdcard/ecto-profile-window.xml >/dev/null 2>&1 || true
    if adb_device shell cat /sdcard/ecto-profile-window.xml 2>/dev/null | grep -Fq "isn't responding"; then
        echo "The terminal triggered an ANR dialog" >&2
        return 1
    fi
}

metric() {
    label=$1
    printf '%s\n' "$GFXINFO" | sed -n "s/^$label: \([0-9][0-9]*\).*/\1/p" | sed -n '1p'
}

if [ "${ECTO_PROFILE_SKIP_BUILD:-0}" != 1 ]; then
    "$ROOT_DIR/gradlew" -p "$ROOT_DIR" :compose-app:assembleArm64Debug
fi

mkdir -p "$REPORT_DIR"
adb_device install -r "$APK" >/dev/null
adb_device shell am force-stop "$PACKAGE"
adb_device shell am start -W -n "$ACTIVITY" >/dev/null
case "$(wait_for_terminal_route)" in
    home)
        adb_device shell input tap "$TAP_X" "$TAP_Y"
        ;;
    terminal) ;;
esac
wait_for_text ":/ \$"

adb_device shell dumpsys gfxinfo "$PACKAGE" reset >/dev/null
adb_device shell input text 'seq%s1%s5000'
adb_device shell input keyevent ENTER
if [ "$EXTRA_KEY_PROBE" = 1 ]; then
    sleep 1
    adb_device shell input tap "$EXTRA_KEY_PROBE_X" "$EXTRA_KEY_PROBE_Y"
fi
sleep "$SETTLE_SECONDS"
assert_no_anr_dialog

GFXINFO=$(adb_device shell dumpsys gfxinfo "$PACKAGE")
TOTAL_FRAMES=$(metric "Total frames rendered")
JANKY_FRAMES=$(metric "Janky frames")
P95_FRAME_MILLIS=$(metric "95th percentile")
RAW_GFXINFO_BASE64=$(printf '%s' "$GFXINFO" | base64 | tr -d '\n')
adb_device exec-out screencap -p > "$SCREENSHOT_FILE"

cat > "$REPORT_FILE" <<EOF
{
  "workload": "seq 1 5000",
  "deviceSerial": "$SERIAL",
  "extraKeyProbeEnabled": $EXTRA_KEY_PROBE_ENABLED,
  "totalFrames": ${TOTAL_FRAMES:-0},
  "jankyFrames": ${JANKY_FRAMES:-0},
  "p95FrameMillis": ${P95_FRAME_MILLIS:-0},
  "rawGfxInfoBase64": "$RAW_GFXINFO_BASE64"
}
EOF

printf 'Profile report: %s\nScreenshot: %s\n' "$REPORT_FILE" "$SCREENSHOT_FILE"
