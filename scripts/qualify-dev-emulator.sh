#!/usr/bin/env bash
# Separate strict qualification of the inactive DEV recorder. No physical output.
set -euo pipefail
qualification_serial="${ANDROID_SERIAL:?Set disposable emulator serial}"
[[ "$qualification_serial" =~ ^emulator-[0-9]+$ ]] || { echo 'Refusing a physical device' >&2; exit 1; }
qualification_adb=(adb -s "$qualification_serial")
qualification_package=org.openmetronome.dev.debug
[[ -z "$("${qualification_adb[@]}" shell pm path "$qualification_package" | tr -d '\r')" ]] || {
    echo 'Requires a fresh emulator; no uninstall or data clearing.' >&2; exit 1;
}
qualification_report=app/build/reports/dev-qualification
qualification_apks="${QUIET_APK_DIR:-quiet-apks}"
mkdir -p "$qualification_report"
"${qualification_adb[@]}" install "$qualification_apks/dev/debug/app-dev-debug.apk"
"${qualification_adb[@]}" install "$qualification_apks/androidTest/dev/debug/app-dev-debug-androidTest.apk"
"${qualification_adb[@]}" shell am instrument -w -r -e allowAudio false -e allowHaptics false \
    -e annotation org.openmetronome.DevPerformanceQualification \
    "$qualification_package.test/org.openmetronome.OpenMetronomeTestRunner" | tee "$qualification_report/instrumentation.log"
"${qualification_adb[@]}" exec-out run-as "$qualification_package" cat cache/dev-qualification.json > "$qualification_report/dev-qualification.json"
awk -f scripts/instrumentation-summary.awk "$qualification_report/instrumentation.log" | tee "$qualification_report/summary.txt"
