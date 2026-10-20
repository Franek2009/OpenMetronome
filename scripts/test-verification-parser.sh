#!/usr/bin/env bash
set -euo pipefail
cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.."
check_summary() {
    local expected_status="$1" fixture="$2" actual=0 output
    output=$(printf '%s\n' "$fixture" | awk -f scripts/instrumentation-summary.awk) || actual=$?
    if [[ "$actual" != "$expected_status" ]]; then
        printf 'Parser: oczekiwano %s, otrzymano %s\n%s\n' "$expected_status" "$actual" "$output" >&2
        exit 1
    fi
}
passing=$'INSTRUMENTATION_STATUS: numtests=1\nINSTRUMENTATION_STATUS_CODE: 1\nINSTRUMENTATION_STATUS_CODE: 0\nINSTRUMENTATION_CODE: -1'
check_summary 0 "$passing"
check_summary 1 $'INSTRUMENTATION_STATUS: numtests=1\nINSTRUMENTATION_STATUS_CODE: 1\nINSTRUMENTATION_STATUS_CODE: -2\nINSTRUMENTATION_CODE: -1'
check_summary 2 $'INSTRUMENTATION_STATUS: numtests=1\nINSTRUMENTATION_STATUS_CODE: 1\nINSTRUMENTATION_STATUS: stack=ENVIRONMENT: locked\nINSTRUMENTATION_STATUS_CODE: -4\nINSTRUMENTATION_CODE: -1'
check_summary 1 $'INSTRUMENTATION_STATUS: numtests=2\nINSTRUMENTATION_STATUS_CODE: 1\nINSTRUMENTATION_STATUS_CODE: 0\nINSTRUMENTATION_CODE: -1'
check_summary 1 'INSTRUMENTATION_FAILED: process crashed'
check_summary 2 $'INSTRUMENTATION_STATUS: numtests=1\nINSTRUMENTATION_STATUS_CODE: 1\nINSTRUMENTATION_STATUS: stack=Audio not authorized: flag missing\nINSTRUMENTATION_STATUS_CODE: -4\nINSTRUMENTATION_CODE: -1'
check_summary 0 $'INSTRUMENTATION_STATUS: numtests=2\nINSTRUMENTATION_STATUS_CODE: 1\nINSTRUMENTATION_STATUS_CODE: 0\nINSTRUMENTATION_STATUS_CODE: 1\nINSTRUMENTATION_STATUS: stack=Audio not authorized: flag missing\nINSTRUMENTATION_STATUS_CODE: -4\nINSTRUMENTATION_CODE: -1'
echo 'Parser: 7 scenariuszy zaliczonych.'
