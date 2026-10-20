#!/usr/bin/env bash
# ONLY for a fresh disposable emulator. Never target a user's phone or tablet.
set -euo pipefail
source "$(dirname -- "${BASH_SOURCE[0]}")/release-info.sh"
release_smoke_serial="${ANDROID_SERIAL:?Set the disposable emulator serial explicitly}"
[[ "$release_smoke_serial" =~ ^emulator-[0-9]+$ ]] || { echo 'Refusing a physical device' >&2; exit 1; }
release_smoke_apk="${1:-release-assets/$release_apk_name}"
release_smoke_adb=(adb -s "$release_smoke_serial")
[[ -z "$("${release_smoke_adb[@]}" shell pm path org.openmetronome | tr -d '\r')" ]] || {
    echo 'Requires a clean emulator without OpenMetronome' >&2; exit 1;
}
release_smoke_report="${2:-app/build/reports/release-smoke}"
mkdir -p -- "$release_smoke_report"
"${release_smoke_adb[@]}" install "$release_smoke_apk"
"${release_smoke_adb[@]}" shell am start -W -n org.openmetronome/.MainActivity

dump_ui() {
    "${release_smoke_adb[@]}" shell uiautomator dump /data/local/tmp/openmetronome-release-ui.xml >/dev/null
    "${release_smoke_adb[@]}" exec-out cat /data/local/tmp/openmetronome-release-ui.xml | sed 's/></>\n</g'
}
await_ui() {
    local expected="$1" attempt
    for attempt in {1..10}; do
        release_smoke_ui="$(dump_ui)"
        if grep -Fq "$expected" <<< "$release_smoke_ui"; then return; fi
        sleep 1
    done
    printf '%s\n' "$release_smoke_ui" > "$release_smoke_report/failed-ui.xml"
    echo "Missing UI: $expected" >&2; exit 1
}
tap_node() {
    local expression="$1" bounds left top right bottom
    bounds="$(grep -F "$expression" <<< "$release_smoke_ui" | head -1 | sed -n 's/.*bounds="\[\([0-9]*\),\([0-9]*\)\]\[\([0-9]*\),\([0-9]*\)\]".*/\1 \2 \3 \4/p')"
    read -r left top right bottom <<< "$bounds"
    [[ "$left $top $right $bottom" =~ ^[0-9]+\ [0-9]+\ [0-9]+\ [0-9]+$ ]]
    "${release_smoke_adb[@]}" shell input tap "$(((left+right)/2))" "$(((top+bottom)/2))"
}

await_ui 'content-desc="120 BPM"'
tap_node 'content-desc="120 BPM"'
await_ui 'text="Zastosuj"'
tap_node 'class="android.widget.EditText"'
"${release_smoke_adb[@]}" shell input keyevent KEYCODE_MOVE_END
for release_smoke_digit in 1 2 3; do "${release_smoke_adb[@]}" shell input keyevent KEYCODE_DEL; done
"${release_smoke_adb[@]}" shell input text 137
await_ui 'text="Zastosuj"'
tap_node 'text="Zastosuj"'
await_ui 'content-desc="137 BPM"'
# Wait for the asynchronous settings write by navigating through UI, never Start.
tap_node 'text="Ustawienia"'
await_ui 'text="Dźwięk"'
tap_node 'text="Metronom"'
await_ui 'content-desc="137 BPM"'
"${release_smoke_adb[@]}" shell am force-stop org.openmetronome
"${release_smoke_adb[@]}" install -r "$release_smoke_apk"
"${release_smoke_adb[@]}" shell am start -W -n org.openmetronome/.MainActivity
await_ui 'content-desc="137 BPM"'
await_ui 'text="Start"'
"${release_smoke_adb[@]}" shell dumpsys package org.openmetronome > "$release_smoke_report/package.txt"
grep -q "versionName=$release_version" "$release_smoke_report/package.txt"
grep -q "versionCode=$release_code " "$release_smoke_report/package.txt"
"${release_smoke_adb[@]}" exec-out screencap -p > "$release_smoke_report/release.png"
printf '%s\n' "$release_smoke_ui" > "$release_smoke_report/final-ui.xml"
"${release_smoke_adb[@]}" shell am force-stop org.openmetronome
echo 'PASS: release installed, BPM persisted across reinstall, no playback requested.'
