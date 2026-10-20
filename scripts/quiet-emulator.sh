#!/usr/bin/env bash
# Isolated UI/storage validation only. Refuse user devices and existing installs.
set -euo pipefail
quiet_serial="${ANDROID_SERIAL:?Set the disposable emulator serial}"
[[ "$quiet_serial" =~ ^emulator-[0-9]+$ ]] || { echo 'Refusing a physical device' >&2; exit 1; }
quiet_adb=(adb -s "$quiet_serial")
[[ -z "$("${quiet_adb[@]}" shell pm path org.openmetronome | tr -d '\r')" ]] || {
    echo 'Requires a fresh emulator without OpenMetronome' >&2; exit 1;
}
quiet_report=app/build/reports/quiet-emulator
mkdir -p "$quiet_report"
"${quiet_adb[@]}" install quiet-apks/debug/app-debug.apk
"${quiet_adb[@]}" install quiet-apks/androidTest/debug/app-debug-androidTest.apk
"${quiet_adb[@]}" shell am instrument -w -r -e allowAudio false -e allowHaptics false -e captureUi true \
    org.openmetronome.test/org.openmetronome.OpenMetronomeTestRunner | tee "$quiet_report/instrumentation.log"
awk -f scripts/instrumentation-summary.awk "$quiet_report/instrumentation.log" | tee "$quiet_report/summary.txt"
for quiet_capture in initial landscape sound-controls; do
    "${quiet_adb[@]}" exec-out run-as org.openmetronome cat "cache/ui-v09-$quiet_capture.png" > "$quiet_report/$quiet_capture.png"
done
