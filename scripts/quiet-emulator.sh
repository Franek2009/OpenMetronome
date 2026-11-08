#!/usr/bin/env bash
# Isolated UI/storage validation only. Refuse user devices and existing installs.
set -euo pipefail
quiet_serial="${ANDROID_SERIAL:?Set the disposable emulator serial}"
[[ "$quiet_serial" =~ ^emulator-[0-9]+$ ]] || { echo 'Refusing a physical device' >&2; exit 1; }
quiet_flavor="${1:-prod}"
case "$quiet_flavor" in
    prod) quiet_package=org.openmetronome.debug ;;
    dev) quiet_package=org.openmetronome.dev.debug ;;
    *) echo 'Expected prod or dev flavor' >&2; exit 2 ;;
esac
quiet_adb=(adb -s "$quiet_serial")
[[ -z "$("${quiet_adb[@]}" shell pm path "$quiet_package" | tr -d '\r')" ]] || {
    echo "Requires a fresh emulator without $quiet_package" >&2; exit 1;
}
quiet_report="${QUIET_REPORT_DIR:-app/build/reports/quiet-emulator/$quiet_flavor}"
quiet_apks="${QUIET_APK_DIR:-quiet-apks}"
mkdir -p "$quiet_report"
"${quiet_adb[@]}" install "$quiet_apks/$quiet_flavor/debug/app-$quiet_flavor-debug.apk"
"${quiet_adb[@]}" install "$quiet_apks/androidTest/$quiet_flavor/debug/app-$quiet_flavor-debug-androidTest.apk"
"${quiet_adb[@]}" shell am instrument -w -r -e allowAudio false -e allowHaptics false -e captureUi true \
    -e notAnnotation org.openmetronome.DevPerformanceQualification \
    "$quiet_package.test/org.openmetronome.OpenMetronomeTestRunner" | tee "$quiet_report/instrumentation.log"
awk -f scripts/instrumentation-summary.awk "$quiet_report/instrumentation.log" | tee "$quiet_report/summary.txt"
printf '%s\n' 'DEV performance qualification: NOT RUN in this regression; consult the separate exact-head qualification workflow.' > "$quiet_report/qualification-scope.txt"
for quiet_capture in initial landscape sound-controls sound-picker sample-library; do
    "${quiet_adb[@]}" exec-out run-as "$quiet_package" cat "cache/ui-v012-$quiet_capture.png" > "$quiet_report/$quiet_capture.png"
done

if [[ "$quiet_flavor" == dev ]]; then
    "${quiet_adb[@]}" exec-out run-as "$quiet_package" cat cache/instrumentation-trace-export.zip > "$quiet_report/trace-export-fixture.zip"
    python3 scripts/analyze-dev-trace.py "$quiet_report/trace-export-fixture.zip" > "$quiet_report/trace-export-summary.json"
fi
