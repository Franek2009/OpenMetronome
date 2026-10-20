#!/usr/bin/env bash
# Single source of version metadata; sourcing this script performs no writes.
release_script_root="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)"
release_version="$(sed -n 's/^[[:space:]]*versionName = "\([^"]*\)"/\1/p' "$release_script_root/app/build.gradle.kts")"
release_code="$(sed -n 's/^[[:space:]]*versionCode = \([0-9]*\)/\1/p' "$release_script_root/app/build.gradle.kts")"
[[ "$release_version" =~ ^[0-9]+\.[0-9]+\.[0-9]+(-[a-z]+\.[0-9]+)?$ && "$release_code" =~ ^[1-9][0-9]*$ ]] || {
    echo 'Invalid release version metadata' >&2; return 1 2>/dev/null || exit 1;
}
release_tag="v$release_version"
release_apk_name="OpenMetronome-$release_version.apk"
