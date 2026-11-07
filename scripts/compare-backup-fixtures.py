#!/usr/bin/env python3
"""Compare complete synthetic .ombak fixtures semantically, including exact sample bytes."""
import io
import json
import struct
import sys
import zipfile

DEFAULTS = dict(version="1", bpm="120", meter="FOUR_FOUR", accent="true", probability="0",
                cycle="false", audibleBars="2", silentBars="2", visual="true", vibration="false",
                progression="false", increment="10", interval="60", ramp="false", start="60",
                target="120", step="5", barsPerStep="4", subdivision="SINGLE", accents="",
                volumePercent="100", clickSound="CLASSIC", customSampleId="", beatAccents="",
                tempoPercent="FULL", tempoChange="NEXT_BAR", countInBars="0")


def config(value):
    return DEFAULTS | dict(line.split("=", 1) for line in value.splitlines() if line.strip())


PREFERENCE_DEFAULTS = dict(version="1", awake="false", reduceMotion="false", minimumSessionSeconds="20", activeDayMinutes="5")


def preferences(value):
    return PREFERENCE_DEFAULTS | dict(line.split("=", 1) for line in value.splitlines() if line.strip())


def snapshot(path):
    with zipfile.ZipFile(path) as archive:
        data = io.BytesIO(archive.read("openmetronome.dat"))

        def read(fmt):
            size = struct.calcsize(fmt)
            return struct.unpack(fmt, data.read(size))[0]

        def string():
            size = read(">i")
            if not 0 <= size <= 1_000_000:
                raise ValueError("Invalid string size")
            value = data.read(size)
            if len(value) != size:
                raise ValueError("Truncated string")
            return value.decode("utf-8")

        def rows(item):
            count = read(">i")
            if not 0 <= count <= 100_000:
                raise ValueError("Invalid record count")
            return sorted((item() for _ in range(count)), key=lambda row: json.dumps(row, sort_keys=True))

        version = read(">i")
        if version not in (1, 2):
            raise ValueError("Unsupported archive version")
        settings = config(string())
        presets = rows(lambda: [string(), string(), config(string()), read(">q"), read(">q")])
        def session():
            row = [string(), read(">q"), read(">q"), read(">q"), read(">?"), read(">?"), string(), config(string())]
            timeline = string() if version >= 2 and read(">?") else None
            return row + [timeline]
        sessions = rows(session)
        days = rows(lambda: [string(), string(), read(">q")])
        samples = rows(lambda: [string(), string(), read(">q")])
        app_preferences = preferences(string()) if version >= 2 and read(">?") else preferences("")
        if data.read() != b"":
            raise ValueError("Trailing metadata")
        files = {name: archive.read(name) for name in archive.namelist() if name != "openmetronome.dat"}
        return settings, presets, sessions, days, samples, files, app_preferences


if __name__ == "__main__":
    if len(sys.argv) != 3:
        raise SystemExit("Usage: compare-backup-fixtures.py expected.ombak actual.ombak")
    expected, actual = map(snapshot, sys.argv[1:])
    for label, before, after in zip(["settings", "presets", "sessions", "days", "samples", "sample bytes", "app preferences"], expected, actual):
        if before != after:
            raise ValueError(f"Upgrade changed {label}")
    print("PASS: settings, presets, complete history, day totals and exact sample bytes survived upgrade.")
