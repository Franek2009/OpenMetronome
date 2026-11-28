#!/usr/bin/env bash
# Test-only. Install PROD debug and its matching instrumentation on a disposable emulator first.
set -euo pipefail

serial="${ANDROID_SERIAL:?Set the isolated emulator serial}"
[[ "$serial" =~ ^emulator-[0-9]+$ ]] || { echo 'Refusing a physical device' >&2; exit 1; }
adb_cmd=("${ADB:-adb}" -s "$serial")
api="$("${adb_cmd[@]}" shell getprop ro.build.version.sdk | tr -d '\r')"
hardware="$("${adb_cmd[@]}" shell getprop ro.hardware | tr -d '\r')"
[[ "$api" == 26 || "$api" == 35 ]] || { echo 'Expected API 26 or 35' >&2; exit 1; }
[[ "$hardware" == ranchu || "$hardware" == goldfish ]] || { echo 'Expected Android Emulator hardware' >&2; exit 1; }
out="${BACKUP_BASELINE_REPORT_DIR:-app/build/reports/backup-baseline/api$api}"
mkdir -p "$out"
pkg=org.openmetronome.debug
class=org.openmetronome.data.BackupBaselineTest
if (($#)); then
    [[ $# == 2 ]] || { echo 'Usage: measure-backup-baseline.sh [count profile]' >&2; exit 2; }
    counts=("$1")
    profiles=("$2")
else
    counts=(10000 50000 100000)
    profiles=(simple dense)
fi
for count in "${counts[@]}"; do
    for profile in "${profiles[@]}"; do
        [[ "$count" =~ ^(10000|50000|100000)$ && "$profile" =~ ^(simple|dense)$ ]] || exit 2
        file="backup-baseline-$count-$profile.json"
        # Remove only an old generated report to avoid collecting stale evidence after a failure.
        "${adb_cmd[@]}" shell run-as "$pkg" rm -f "cache/$file"
        "${adb_cmd[@]}" shell input keyevent KEYCODE_WAKEUP
        "${adb_cmd[@]}" shell wm dismiss-keyguard
        "${adb_cmd[@]}" shell am instrument -w -r -e allowAudio false -e allowHaptics false \
            -e backupBaseline true -e count "$count" -e profile "$profile" -e class "$class" \
            "$pkg.test/org.openmetronome.OpenMetronomeTestRunner" | tee "$out/$count-$profile.log"
        "${adb_cmd[@]}" exec-out run-as "$pkg" cat "cache/$file" > "$out/$file"
        python3 - "$out/$file" "$out/$count-$profile.log" <<'PY'
import json
import re
import sys
from pathlib import Path

report = json.loads(Path(sys.argv[1]).read_text())
log = Path(sys.argv[2]).read_text()
if report["status"] != "complete" or not re.search(r"^OK \(1 test\)", log, re.MULTILINE):
    raise SystemExit("Incomplete benchmark. See the saved report and instrumentation log.")
print(f'API {report["api"]}: {report["playbacks"]}/{report["profile"]} complete')
PY
    done
done
