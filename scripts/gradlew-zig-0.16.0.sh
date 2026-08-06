#!/usr/bin/env bash
set -euo pipefail

repo_root="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)"
zig_executable="${ZIG_0_16_0_EXECUTABLE:-${HOME}/.nix-profile/bin/zig}"

if [[ ! -x "$zig_executable" ]]; then
    printf 'Zig 0.16.0 was not found at %s\n' "$zig_executable" >&2
    printf 'Set ZIG_0_16_0_EXECUTABLE to the Nix store binary.\n' >&2
    exit 1
fi

zig_version="$("$zig_executable" version)"
if [[ "$zig_version" != '0.16.0' ]]; then
    printf 'Expected Zig 0.16.0, found %s at %s\n' "$zig_version" "$zig_executable" >&2
    exit 1
fi

cd "$repo_root"
export ZIG_EXECUTABLE="$zig_executable"
exec ./gradlew "$@"
