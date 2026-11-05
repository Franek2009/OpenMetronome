#!/usr/bin/env bash
# Disposable emulator only; never replace an existing production installation.
set -euo pipefail
isolation_serial="${ANDROID_SERIAL:?Set disposable emulator serial}"
[[ "$isolation_serial" =~ ^emulator-[0-9]+$ ]] || { echo 'Refusing a physical device' >&2; exit 1; }
[[ $# == 1 ]] || { echo 'Usage: verify-variant-isolation.sh official-1.0.0.apk' >&2; exit 2; }
isolation_baseline="$1"
: "${ANDROID_HOME:?Set ANDROID_HOME}"
isolation_tools="$ANDROID_HOME/build-tools/36.0.0"
isolation_adb=(adb -s "$isolation_serial")
[[ -z "$("${isolation_adb[@]}" shell pm path org.openmetronome | tr -d '\r')" ]] || {
    echo 'Requires a fresh emulator without production OpenMetronome' >&2; exit 1;
}
printf '%s  %s\n' '8817ee2ef382634a0de1a6a7699d7de8e22bb629afa2a24411692e36e5c9b631' "$isolation_baseline" | sha256sum --check
"$isolation_tools/apksigner" verify --print-certs "$isolation_baseline" |
    grep -q 'SHA-256 digest: 563cd960efc3fc5b483ceae4b96e66728af9e2bb0c52d6162fe37cded72ac4a2'
# Do not launch the ordinary production app or request any playback.
"${isolation_adb[@]}" install "$isolation_baseline"
isolation_report="${ISOLATION_REPORT_DIR:-app/build/reports/variant-isolation}"
mkdir -p "$isolation_report"
isolation_uids=()
for isolation_package in org.openmetronome org.openmetronome.debug org.openmetronome.dev.debug; do
    "${isolation_adb[@]}" shell pm path "$isolation_package" | grep -q '^package:'
    "${isolation_adb[@]}" shell dumpsys package "$isolation_package" > "$isolation_report/$isolation_package.txt"
    # Android 26 prints userId; Android 35 calls the same user-0 value appId.
    isolation_uid="$(sed -n 's/^[[:space:]]*\(userId\|appId\)=\([0-9]*\).*/\2/p' "$isolation_report/$isolation_package.txt" | head -1)"
    [[ "$isolation_uid" =~ ^[0-9]+$ ]] || {
        echo "Cannot determine package UID: $isolation_package" >&2; exit 1;
    }
    isolation_uids+=("$isolation_uid")
done
[[ "${isolation_uids[0]}" != "${isolation_uids[1]}" && "${isolation_uids[0]}" != "${isolation_uids[2]}" && "${isolation_uids[1]}" != "${isolation_uids[2]}" ]]
for isolation_package in org.openmetronome.debug org.openmetronome.dev.debug; do
    "${isolation_adb[@]}" shell "run-as $isolation_package mkdir -p files"
    [[ "$("${isolation_adb[@]}" shell "run-as $isolation_package sh -c 'test -e files/variant-isolation.txt'; echo \$?" | tr -d '\r')" == 1 ]]
    "${isolation_adb[@]}" shell "run-as $isolation_package sh -c 'printf %s $isolation_package > files/variant-isolation.txt'"
    [[ "$("${isolation_adb[@]}" exec-out run-as "$isolation_package" cat files/variant-isolation.txt)" == "$isolation_package" ]]
done
for isolation_package in org.openmetronome.debug org.openmetronome.dev.debug; do
    isolation_other=org.openmetronome.debug
    [[ "$isolation_package" != "$isolation_other" ]] || isolation_other=org.openmetronome.dev.debug
    if "${isolation_adb[@]}" shell run-as "$isolation_package" cat "/data/user/0/$isolation_other/files/variant-isolation.txt" > "$isolation_report/access-$isolation_package.log" 2>&1; then
        echo 'FAIL: private data accessible across variants' >&2; exit 1
    fi
    grep -qi 'Permission denied' "$isolation_report/access-$isolation_package.log"
done
isolation_path="$("${isolation_adb[@]}" shell pm path org.openmetronome | tr -d '\r' | sed 's/^package://')"
"${isolation_adb[@]}" pull "$isolation_path" "$isolation_report/installed-production.apk"
cmp "$isolation_baseline" "$isolation_report/installed-production.apk"
echo 'PASS: official 1.0.0 and both debug flavors coexist; distinct UIDs and private files; production APK unchanged.' | tee "$isolation_report/summary.txt"
