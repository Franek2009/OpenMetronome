# AndroidJUnitRunner's "OK (N tests)" includes assumptions. Parse actual terminal statuses.
/^INSTRUMENTATION_STATUS: numtests=/ {
    value = $0; sub(/^.*numtests=/, "", value)
    if (expected && expected != value + 0) malformed = 1
    expected = value + 0
}
/^INSTRUMENTATION_STATUS: stack=/ {
    if ($0 ~ /Audio not authorized:/) reason = "audio"
    else reason = "other"
}
/^INSTRUMENTATION_STATUS_CODE:/ {
    code = $2 + 0
    if (code == 1) { reason = ""; started++ }
    else if (code == 0) passed++
    else if (code == -4 && reason == "audio") audioSkipped++
    else if (code == -4 || code == -3) otherSkipped++
    else failed++
}
/^INSTRUMENTATION_CODE: -1\r?$/ { finished = 1 }
/INSTRUMENTATION_FAILED|^INSTRUMENTATION_RESULT: shortMsg=/ { malformed = 1 }
END {
    completed = passed + audioSkipped + otherSkipped + failed
    printf "PASS=%d AUDIO_SKIPPED=%d OTHER_SKIPPED=%d FAIL=%d TOTAL=%d\n", passed, audioSkipped, otherSkipped, failed, completed
    if (!finished || malformed || expected < 1 || completed != expected || failed) {
        print "ERROR: niepowodzenie, przerwany lub niekompletny raport instrumentacji."
        exit 1
    }
    if (otherSkipped) {
        print "INCOMPLETE: testy pominięte z powodów innych niż blokada audio."
        exit 2
    }
    if (passed < 1) { print "INCOMPLETE: nie wykonano żadnego cichego testu."; exit 2 }
    print "OK: cichy zestaw zaliczony; pominięcia audio nie są zaliczeniami."
}
