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
if ANDROID_SERIAL=physical-device bash scripts/quiet-emulator.sh > "$release_test_dir/quiet-physical.log" 2>&1; then
    echo 'FAIL: quiet emulator suite accepted a physical device' >&2; exit 1
fi
grep -q 'Refusing a physical device' "$release_test_dir/quiet-physical.log"
if ANDROID_SERIAL=physical-device bash scripts/verify-upgrade.sh > "$release_test_dir/upgrade-physical.log" 2>&1; then
    echo 'FAIL: upgrade validation accepted a physical device' >&2; exit 1
fi
grep -q 'Refusing a physical device' "$release_test_dir/upgrade-physical.log"
# Prove either debuggable APK is rejected before signing or installation.
mkdir -p "$release_test_dir/bin" "$release_test_dir/sdk/build-tools/36.0.0"
cat > "$release_test_dir/bin/adb" <<'STUB'
#!/usr/bin/env bash
[[ "$*" == *'shell pm path org.openmetronome' ]]
STUB
cat > "$release_test_dir/sdk/build-tools/36.0.0/aapt" <<'STUB'
#!/usr/bin/env bash
if [[ "$3" == old.apk ]]; then
    echo "package: name='org.openmetronome' versionCode='14' versionName='0.13.0-beta.2'"
else
    echo "package: name='org.openmetronome' versionCode='$STUB_RELEASE_CODE' versionName='$STUB_RELEASE_VERSION'"
fi
if [[ "$3" == "$STUB_DEBUG_APK" ]]; then echo 'application-debuggable'; fi
exit 0
STUB
chmod +x "$release_test_dir/bin/adb" "$release_test_dir/sdk/build-tools/36.0.0/aapt"
for release_debug_apk in old.apk new.apk; do
    if PATH="$release_test_dir/bin:$PATH" ANDROID_HOME="$release_test_dir/sdk" ANDROID_SERIAL=emulator-5554 \
        STUB_RELEASE_CODE="$release_code" STUB_RELEASE_VERSION="$release_version" STUB_DEBUG_APK="$release_debug_apk" \
        bash scripts/verify-upgrade.sh old.apk new.apk > "$release_test_dir/debug-$release_debug_apk.log" 2>&1; then
        echo 'FAIL: upgrade validation accepted a debuggable APK' >&2; exit 1
    fi
    grep -q 'requires non-debuggable release APKs' "$release_test_dir/debug-$release_debug_apk.log"
done
echo 'Release tools: syntax, metadata, key overwrite, missing-SDK, physical-device and debuggable-upgrade guards PASS.'
