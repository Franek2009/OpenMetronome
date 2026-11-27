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
# Capture settled windows, not intermediate transition frames, on this disposable emulator.
for quiet_scale in window_animation_scale transition_animation_scale animator_duration_scale; do
    "${quiet_adb[@]}" shell settings put global "$quiet_scale" 0
done
"${quiet_adb[@]}" shell am instrument -w -r -e allowAudio false -e allowHaptics false -e captureUi true \
    -e notAnnotation org.openmetronome.DevPerformanceQualification \
    "$quiet_package.test/org.openmetronome.OpenMetronomeTestRunner" | tee "$quiet_report/instrumentation.log"
awk -f scripts/instrumentation-summary.awk "$quiet_report/instrumentation.log" | tee "$quiet_report/summary.txt"
printf '%s\n' 'DEV performance qualification: NOT RUN in release regression; capture disabled in beta.' > "$quiet_report/qualification-scope.txt"
for quiet_capture in initial landscape sound-controls sound-picker sample-library fullscreen statistics music-summary music-analysis music-form music-history music-group music-settings widget-mini widget-control widget-statistics; do
    "${quiet_adb[@]}" exec-out run-as "$quiet_package" cat "cache/ui-v012-$quiet_capture.png" > "$quiet_report/$quiet_capture.png"
done

# Exercise the activity flow on a compact screen, including large text and landscape.
"${quiet_adb[@]}" shell wm size 320x640
"${quiet_adb[@]}" shell wm density 160
for quiet_variant in font150 landscape; do
    quiet_extra=()
    if [[ "$quiet_variant" == font150 ]]; then
        "${quiet_adb[@]}" shell settings put system font_scale 1.5
    else
        "${quiet_adb[@]}" shell settings put system font_scale 1.0
        quiet_extra=(-e layoutLandscape true)
    fi
    "${quiet_adb[@]}" shell am instrument -w -r -e allowAudio false -e allowHaptics false -e captureUi true \
        "${quiet_extra[@]}" -e class 'org.openmetronome.ui.SilentFeaturesTest#musicActivityCanBeAddedAndScopeSurvivesRecreation' \
        "$quiet_package.test/org.openmetronome.OpenMetronomeTestRunner" | tee "$quiet_report/$quiet_variant-instrumentation.log"
    awk -f scripts/instrumentation-summary.awk "$quiet_report/$quiet_variant-instrumentation.log"
    for quiet_capture in music-summary music-analysis music-form music-history music-group music-settings; do
        "${quiet_adb[@]}" exec-out run-as "$quiet_package" cat "cache/ui-v012-$quiet_capture.png" > "$quiet_report/$quiet_capture-$quiet_variant.png"
    done
done
"${quiet_adb[@]}" shell settings put system font_scale 1.0
"${quiet_adb[@]}" shell wm size reset
"${quiet_adb[@]}" shell wm density reset

if [[ "$quiet_flavor" == dev ]]; then
    "${quiet_adb[@]}" exec-out run-as "$quiet_package" cat cache/instrumentation-trace-export.zip > "$quiet_report/trace-export-fixture.zip"
    python3 scripts/analyze-dev-trace.py "$quiet_report/trace-export-fixture.zip" > "$quiet_report/trace-export-summary.json"
fi
