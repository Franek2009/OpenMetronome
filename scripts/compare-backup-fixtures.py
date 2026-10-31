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
                volumePercent="100", clickSound="CLASSIC", customSampleId="", beatAccents="")


def config(value):
    return DEFAULTS | dict(line.split("=", 1) for line in value.splitlines() if line.strip())


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
            return data.read(size).decode("utf-8")

        def rows(item):
            count = read(">i")
            if not 0 <= count <= 100_000:
                raise ValueError("Invalid record count")
            return sorted((item() for _ in range(count)), key=lambda row: json.dumps(row, sort_keys=True))

        if read(">i") != 1:
            raise ValueError("Unsupported archive version")
        settings = config(string())
        presets = rows(lambda: [string(), string(), config(string()), read(">q"), read(">q")])
        sessions = rows(lambda: [string(), read(">q"), read(">q"), read(">q"), read(">?"), read(">?"), string(), config(string())])
        days = rows(lambda: [string(), string(), read(">q")])
        samples = rows(lambda: [string(), string(), read(">q")])
        if data.read() != b"":
            raise ValueError("Trailing metadata")
        files = {name: archive.read(name) for name in archive.namelist() if name != "openmetronome.dat"}
        return settings, presets, sessions, days, samples, files


if __name__ == "__main__":
    if len(sys.argv) != 3:
        raise SystemExit("Usage: compare-backup-fixtures.py expected.ombak actual.ombak")
    expected, actual = map(snapshot, sys.argv[1:])
    for label, before, after in zip(["settings", "presets", "sessions", "days", "samples", "sample bytes"], expected, actual):
        if before != after:
            raise ValueError(f"Upgrade changed {label}")
    print("PASS: settings, presets, complete history, day totals and exact sample bytes survived upgrade.")
