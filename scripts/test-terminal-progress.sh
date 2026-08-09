#!/bin/sh
# Run inside a terminal session to exercise Ghostty OSC 9;4 progress rendering.
set -eu

DELAY_SECONDS=${1:-1}

case "$DELAY_SECONDS" in
    ''|*[!0-9.]*)
        printf 'Usage: %s [delay-seconds]\n' "$0" >&2
        exit 64
        ;;
esac

emit_progress() {
    state=$1
    value=${2-}
    if [ -n "$value" ]; then
        printf '\033]9;4;%s;%s\007' "$state" "$value"
    else
        printf '\033]9;4;%s\007' "$state"
    fi
}

clear_progress() {
    emit_progress 0
}

trap clear_progress EXIT HUP INT TERM

printf 'Testing determinate progress.\n'
for value in 0 10 25 50 75 100; do
    emit_progress 1 "$value"
    sleep "$DELAY_SECONDS"
done

printf 'Testing indeterminate progress.\n'
emit_progress 3
sleep "$DELAY_SECONDS"

printf 'Testing paused progress.\n'
emit_progress 4 60
sleep "$DELAY_SECONDS"

printf 'Testing error progress.\n'
emit_progress 2 60
sleep "$DELAY_SECONDS"

printf 'Clearing progress.\n'
