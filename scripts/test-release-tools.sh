#!/usr/bin/env bash
set -euo pipefail
release_tests_root="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)"
cd -- "$release_tests_root"
for release_test_script in scripts/*.sh; do bash -n "$release_test_script"; done
source scripts/release-info.sh
[[ "$release_tag" == "v$release_version" && "$release_code" -gt 0 ]]
[[ "$release_apk_name" == "OpenMetronome-$release_version.apk" ]]
release_test_dir="$(mktemp -d "${TMPDIR:-/tmp}/openmetronome-release-test.XXXXXXXX")"
# Existing directories must be refused before any key is generated or modified.
if OM_SIGNING_DIR="$release_test_dir" bash scripts/prepare-release-key.sh > "$release_test_dir/refusal.log" 2>&1; then
    echo 'FAIL: key overwrite was allowed' >&2; exit 1
fi
grep -q 'Refusing to overwrite' "$release_test_dir/refusal.log"
[[ ! -e "$release_test_dir/openmetronome.p12" ]]
if (unset ANDROID_HOME; bash scripts/package-release.sh) > "$release_test_dir/no-sdk.log" 2>&1; then
    echo 'FAIL: packaging without SDK succeeded' >&2; exit 1
fi
if ANDROID_SERIAL=physical-device bash scripts/smoke-release.sh > "$release_test_dir/physical.log" 2>&1; then
    echo 'FAIL: release smoke accepted a physical device' >&2; exit 1
fi
grep -q 'Refusing a physical device' "$release_test_dir/physical.log"
echo 'Release tools: syntax, metadata, key overwrite, missing-SDK and physical-device guards PASS.'
