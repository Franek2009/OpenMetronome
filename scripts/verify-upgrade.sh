#!/usr/bin/env bash
# Disposable emulator only. Re-sign copies with a throwaway test key, never official keys/APKs.
set -euo pipefail
source "$(dirname -- "${BASH_SOURCE[0]}")/release-info.sh"
cd -- "$release_script_root"
upgrade_serial="${ANDROID_SERIAL:?Set disposable emulator serial}"
[[ "$upgrade_serial" =~ ^emulator-[0-9]+$ ]] || { echo 'Refusing a physical device' >&2; exit 1; }
[[ $# == 2 ]] || { echo 'Usage: verify-upgrade.sh old-official.apk new-unsigned.apk' >&2; exit 2; }
: "${ANDROID_HOME:?Set ANDROID_HOME}"
upgrade_old="$1"
upgrade_new="$2"
upgrade_tools="$ANDROID_HOME/build-tools/36.0.0"
upgrade_adb=(adb -s "$upgrade_serial")
[[ -z "$("${upgrade_adb[@]}" shell pm path org.openmetronome | tr -d '\r')" ]] || {
    echo 'Requires an emulator without OpenMetronome; no uninstall or data clearing is allowed.' >&2; exit 1;
}
upgrade_old_badging="$($upgrade_tools/aapt dump badging "$upgrade_old")"
upgrade_new_badging="$($upgrade_tools/aapt dump badging "$upgrade_new")"
grep -q "^package: name='org.openmetronome' versionCode='14' versionName='0.13.0-beta.2'" <<< "$upgrade_old_badging"
grep -q "^package: name='org.openmetronome' versionCode='$release_code' versionName='$release_version'" <<< "$upgrade_new_badging"
[[ "$release_code" -gt 14 ]]
if grep -q 'application-debuggable' <<< "$upgrade_old_badging" ||
    grep -q 'application-debuggable' <<< "$upgrade_new_badging"; then
    echo 'Upgrade validation requires non-debuggable release APKs.' >&2; exit 1
fi
upgrade_old_cert="$($upgrade_tools/apksigner verify --print-certs "$upgrade_old")"
grep -q 'SHA-256 digest: 563cd960efc3fc5b483ceae4b96e66728af9e2bb0c52d6162fe37cded72ac4a2' <<< "$upgrade_old_cert"
upgrade_report="${UPGRADE_REPORT_DIR:-app/build/reports/upgrade-validation}"
upgrade_apk_dir="app/build/upgrade-fixtures/$upgrade_serial"
mkdir -p -- "$upgrade_report" "$upgrade_apk_dir"
upgrade_key_dir="$(mktemp -d "$release_script_root/app/build/upgrade-key.XXXXXXXX")"
trap 'rm -f -- "$upgrade_key_dir/key.p12"; rmdir -- "$upgrade_key_dir"' EXIT
umask 077
export OM_UPGRADE_TEST_PASSWORD="$(openssl rand -hex 24)"
keytool -genkeypair -keystore "$upgrade_key_dir/key.p12" -storetype PKCS12 \
    -storepass:env OM_UPGRADE_TEST_PASSWORD -keypass:env OM_UPGRADE_TEST_PASSWORD \
    -alias upgrade-test -keyalg RSA -keysize 2048 -validity 2 \
    -dname 'CN=OpenMetronome Upgrade Test' >/dev/null 2>&1
for upgrade_kind in old new; do
    if [[ "$upgrade_kind" == old ]]; then upgrade_input="$upgrade_old"; else upgrade_input="$upgrade_new"; fi
    "$upgrade_tools/apksigner" sign --ks "$upgrade_key_dir/key.p12" --ks-key-alias upgrade-test \
        --ks-pass env:OM_UPGRADE_TEST_PASSWORD --key-pass env:OM_UPGRADE_TEST_PASSWORD \
        --out "$upgrade_apk_dir/$upgrade_kind-test.apk" "$upgrade_input"
done
unset OM_UPGRADE_TEST_PASSWORD
"${upgrade_adb[@]}" install "$upgrade_apk_dir/old-test.apk"
"${upgrade_adb[@]}" shell mkdir -p /sdcard/Download
upgrade_input_name="openmetronome-upgrade-$RANDOM.ombak"
"${upgrade_adb[@]}" push app/src/test/resources/backups/v1-beta1.ombak "/sdcard/Download/$upgrade_input_name"
# Never overwrite or pull an unrelated old export.
[[ -z "$("${upgrade_adb[@]}" shell 'ls /sdcard/Download/OpenMetronome-*.ombak 2>/dev/null' | tr -d '\r')" ]] || {
    echo 'Requires Downloads without a previous OpenMetronome export.' >&2; exit 1;
}
upgrade_size="$("${upgrade_adb[@]}" shell wm size | tr -d '\r' | tail -1 | sed -n 's/.*: \([0-9]*\)x\([0-9]*\)/\1 \2/p')"
read -r upgrade_width upgrade_height <<< "$upgrade_size"
[[ "$upgrade_width $upgrade_height" =~ ^[0-9]+\ [0-9]+$ ]]

dump_ui() {
    # Some platform versions report a transient null accessibility root while a window opens.
    # Never reuse an XML dump left by the preceding screen.
    "${upgrade_adb[@]}" shell rm -f /data/local/tmp/openmetronome-upgrade-ui.xml
    if ! "${upgrade_adb[@]}" shell uiautomator dump /data/local/tmp/openmetronome-upgrade-ui.xml >/dev/null; then return 1; fi
    "${upgrade_adb[@]}" exec-out cat /data/local/tmp/openmetronome-upgrade-ui.xml | sed 's/></>\n</g'
}
await_ui() {
    local expected="$1" scroll="${2:-false}" attempt
    for attempt in {1..12}; do
        if ! upgrade_ui="$(dump_ui)"; then sleep 1; continue; fi
        if grep -Fq "$expected" <<< "$upgrade_ui"; then return; fi
        if [[ "$scroll" == true ]]; then
            "${upgrade_adb[@]}" shell input swipe "$((upgrade_width/2))" "$((upgrade_height*65/100))" "$((upgrade_width/2))" "$((upgrade_height*30/100))" 350
        fi
        sleep 1
    done
    printf '%s\n' "$upgrade_ui" > "$upgrade_report/failed-ui.xml"
    echo "Missing upgrade UI: $expected" >&2; exit 1
}
tap_node() {
    local expression="$1" bounds left top right bottom
    bounds="$(grep -F "$expression" <<< "$upgrade_ui" | head -1 | sed -n 's/.*bounds="\[\([0-9]*\),\([0-9]*\)\]\[\([0-9]*\),\([0-9]*\)\]".*/\1 \2 \3 \4/p')"
    read -r left top right bottom <<< "$bounds"
    [[ "$left $top $right $bottom" =~ ^[0-9]+\ [0-9]+\ [0-9]+\ [0-9]+$ && "$right" -gt "$left" && "$bottom" -gt "$top" ]]
    "${upgrade_adb[@]}" shell input tap "$(((left+right)/2))" "$(((top+bottom)/2))"
}
open_downloads() {
    await_ui 'package="com.android.documentsui"'
    if grep -Fq "text=\"$upgrade_input_name\"" <<< "$upgrade_ui"; then return; fi
    if ! grep -Fq 'text="Downloads"' <<< "$upgrade_ui"; then
        tap_node 'content-desc="Show roots"'
        await_ui 'text="Downloads"'
    fi
    tap_node 'text="Downloads"'
}

"${upgrade_adb[@]}" shell am start -W -n org.openmetronome/.MainActivity
await_ui 'text="Statystyki"'; tap_node 'text="Statystyki"'
await_ui 'text="Przywróć kopię"' true; tap_node 'text="Przywróć kopię"'
open_downloads
await_ui "text=\"$upgrade_input_name\""; tap_node "text=\"$upgrade_input_name\""
await_ui 'text="Przywróć"'; tap_node 'text="Przywróć"'
await_ui 'Kopia została przywrócona:' true
await_ui 'text="Metronom"'; tap_node 'text="Metronom"'
await_ui 'content-desc="91 BPM"'
"${upgrade_adb[@]}" shell am force-stop org.openmetronome
# The actual version upgrade; only -r, never uninstall or pm clear.
"${upgrade_adb[@]}" install -r "$upgrade_apk_dir/new-test.apk"
"${upgrade_adb[@]}" shell am start -W -n org.openmetronome/.MainActivity
await_ui 'content-desc="91 BPM"'
await_ui 'text="Start"'
await_ui 'text="Statystyki"'; tap_node 'text="Statystyki"'
await_ui 'text="Kopia zapasowa"' true; tap_node 'text="Kopia zapasowa"'
open_downloads
upgrade_save_node=''
for upgrade_save_attempt in {1..12}; do
    if ! upgrade_ui="$(dump_ui)"; then sleep 1; continue; fi
    for upgrade_label in Save SAVE Zapisz ZAPISZ; do
        if grep -Fq "text=\"$upgrade_label\"" <<< "$upgrade_ui"; then upgrade_save_node="text=\"$upgrade_label\""; break; fi
    done
    [[ -z "$upgrade_save_node" ]] || break
    sleep 1
done
[[ -n "$upgrade_save_node" ]]
tap_node "$upgrade_save_node"
await_ui 'Kopia zapasowa została zapisana.' true
upgrade_output="$("${upgrade_adb[@]}" shell 'ls /sdcard/Download/OpenMetronome-*.ombak' | tr -d '\r')"
[[ "$upgrade_output" != *$'\n'* && "$upgrade_output" == /sdcard/Download/OpenMetronome-*.ombak ]]
"${upgrade_adb[@]}" pull "$upgrade_output" "$upgrade_report/after-upgrade.ombak"
python3 scripts/compare-backup-fixtures.py app/src/test/resources/backups/v1-beta1.ombak "$upgrade_report/after-upgrade.ombak" | tee "$upgrade_report/summary.txt"
"${upgrade_adb[@]}" shell dumpsys package org.openmetronome > "$upgrade_report/package.txt"
grep -q "versionName=$release_version" "$upgrade_report/package.txt"
grep -q "versionCode=$release_code " "$upgrade_report/package.txt"
"${upgrade_adb[@]}" shell am force-stop org.openmetronome
printf '%s\n' 'Test used re-signed copies with an ephemeral key; no official signing key was accessed.' >> "$upgrade_report/summary.txt"
