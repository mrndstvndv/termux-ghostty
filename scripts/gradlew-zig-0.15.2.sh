#!/usr/bin/env bash
set -euo pipefail

repo_root="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)"
zig_executable="${ZIG_0_15_2_EXECUTABLE:-${HOME}/.nix-profile/bin/zig}"

if [[ ! -x "$zig_executable" ]]; then
    printf 'Zig 0.15.2 was not found at %s\n' "$zig_executable" >&2
    printf 'Set ZIG_0_15_2_EXECUTABLE to the Nix store binary.\n' >&2
    exit 1
fi

zig_version="$("$zig_executable" version)"
if [[ "$zig_version" != '0.15.2' ]]; then
    printf 'Expected Zig 0.15.2, found %s at %s\n' "$zig_version" "$zig_executable" >&2
    exit 1
fi

if [[ "$(uname -s)" == 'Darwin' && -z "${DEVELOPER_DIR+x}" ]]; then
    active_developer_dir="$(xcode-select -p 2>/dev/null || true)"
    if [[ "$active_developer_dir" == '/Library/Developer/CommandLineTools' ]]; then
        # Zig 0.15.2 cannot link its arm64 host tools against macOS 26's CLT SDK.
        export DEVELOPER_DIR="${ZIG_0_15_2_DEVELOPER_DIR:-/nonexistent}"
    fi
fi

cd "$repo_root"
export ZIG_EXECUTABLE="$zig_executable"
exec ./gradlew "$@"
