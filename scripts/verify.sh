#!/usr/bin/env bash
# No connectedDebugAndroidTest: device installation and audio flags are controlled here.
set -euo pipefail
verification_root="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)"
verification_device=""
verification_offline=()
while (($#)); do
    case "$1" in
        --device)
            [[ $# -ge 2 && -n "$2" && "$2" != -* ]] || { echo 'Podaj numer urządzenia po --device.' >&2; exit 2; }
            verification_device="$2"; shift 2 ;;
        --offline) verification_offline=(--offline); shift ;;
        --help|-h)
            echo 'Użycie: bash scripts/verify.sh [--offline] [--device NUMER_ADB]'
            echo 'Domyślnie: JVM, Lint, oba APK. --device: install -r i wyłącznie ciche testy.'
            echo 'Kod wyjścia: 0 zaliczone, 1 błąd, 2 niepełna weryfikacja lub błędne opcje.'
            exit 0 ;;
        *) echo "Nieznana opcja: $1" >&2; exit 2 ;;
    esac
done
cd -- "$verification_root"
if [[ -z "${JAVA_HOME:-}" || -z "${ANDROID_HOME:-}" ]]; then
    source scripts/dev-env.sh
fi
export GRADLE_USER_HOME="${GRADLE_USER_HOME:-$verification_root/.tooling/gradle-home}"
if [[ -n "$verification_device" ]]; then
    command -v adb >/dev/null || { echo 'Brak ADB w PATH.' >&2; exit 1; }
    [[ "$(adb -s "$verification_device" get-state)" == device ]] || { echo 'Urządzenie niedostępne.' >&2; exit 2; }
fi
verification_report="app/build/reports/verification-$(date -u +%Y%m%dT%H%M%SZ)"
mkdir -p -- "$verification_report"
echo "Raporty: $verification_report"
./gradlew --no-daemon --console=plain "${verification_offline[@]}" \
    :app:testDebugUnitTest :app:lintDebug :app:assembleDebug :app:assembleDebugAndroidTest \
    2>&1 | tee "$verification_report/build.log"
if [[ -z "$verification_device" ]]; then
    echo 'Weryfikacja lokalna zakończona. Nie uruchamiano testów urządzeniowych.'
    exit 0
fi
[[ "$(adb -s "$verification_device" get-state)" == device ]] || { echo 'Utracono urządzenie.' >&2; exit 2; }
adb -s "$verification_device" install -r app/build/outputs/apk/debug/app-debug.apk
adb -s "$verification_device" install -r app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
echo 'Cicha instrumentacja. Zablokowany ekran spowoduje pominięcie UI, nie PASS.'
adb -s "$verification_device" shell am instrument -w -r \
    -e allowAudio false -e allowHaptics false \
    org.openmetronome.test/org.openmetronome.OpenMetronomeTestRunner \
    2>&1 | tee "$verification_report/instrumentation.log"
awk -f scripts/instrumentation-summary.awk "$verification_report/instrumentation.log" \
    | tee "$verification_report/summary.txt"
