#!/usr/bin/env python3
"""Regression tests for the upgrade comparison oracle, using only a synthetic archive."""
import os
import runpy
import io
import struct
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest
import zipfile

ROOT = Path(__file__).resolve().parent.parent
FIXTURE = ROOT / "app/src/test/resources/backups/v1-beta1.ombak"
COMPARE = ROOT / "scripts/compare-backup-fixtures.py"


class UpgradeOracleTest(unittest.TestCase):
    def v2_fixture(self, path, timeline=None, preference_changes=None):
        module = runpy.run_path(str(COMPARE))
        settings, presets, sessions, days, samples, files, prefs = module["snapshot"](FIXTURE)
        prefs.update(preference_changes or {})
        data = io.BytesIO()
        def number(fmt, value): data.write(struct.pack(fmt, value))
        def string(value):
            raw = value.encode("utf-8"); number(">i", len(raw)); data.write(raw)
        def config(value): string("\n".join(f"{k}={v}" for k, v in value.items()))
        number(">i", 2); config(settings)
        number(">i", len(presets))
        for identifier, name, cfg, created, updated in presets:
            string(identifier); string(name); config(cfg); number(">q", created); number(">q", updated)
        number(">i", len(sessions))
        for identifier, started, checkpoint, duration, finished, interrupted, zone, cfg, _ in sessions:
            string(identifier)
            for value in (started, checkpoint, duration): number(">q", value)
            number(">?", finished); number(">?", interrupted); string(zone); config(cfg)
            number(">?", timeline is not None)
            if timeline is not None: string(timeline)
        number(">i", len(days))
        for identifier, date, duration in days: string(identifier); string(date); number(">q", duration)
        number(">i", len(samples))
        for identifier, name, created in samples: string(identifier); string(name); number(">q", created)
        number(">?", True); config(prefs)
        with zipfile.ZipFile(path, "w", zipfile.ZIP_DEFLATED) as archive:
            archive.writestr("openmetronome.dat", data.getvalue())
            for name, raw in files.items(): archive.writestr(name, raw)

    def test_v2_defaults_are_compatible_and_added_history_or_changed_preferences_are_detected(self):
        with tempfile.TemporaryDirectory(prefix="upgrade-v2-") as directory:
            path = Path(directory) / "v2.ombak"
            self.v2_fixture(path)
            self.assertEqual(0, self.run_compare(path).returncode)
            self.v2_fixture(path, timeline="unexpected history for a legacy session")
            self.assertNotEqual(0, self.run_compare(path).returncode)
            self.v2_fixture(path, preference_changes={"activeDayMinutes": "10"})
            self.assertNotEqual(0, self.run_compare(path).returncode)

    def run_compare(self, actual):
        return subprocess.run([sys.executable, str(COMPARE), str(FIXTURE), str(actual)],
                              env=os.environ | {"PYTHONOPTIMIZE": "1"}, capture_output=True)

    def test_new_optional_defaults_preserve_semantics_but_nondefaults_do_not(self):
        canonical = runpy.run_path(str(COMPARE))["config"]
        legacy = canonical("version=1\nbpm=91")
        self.assertEqual(legacy, canonical("version=1\nbpm=91\ntempoPercent=FULL\ntempoChange=NEXT_BAR\ncountInBars=0"))
        for field in ("tempoPercent=HALF", "tempoChange=NEXT_BEAT", "countInBars=1"):
            self.assertNotEqual(legacy, canonical("version=1\nbpm=91\n" + field))

    def test_identical_complete_fixture_passes(self):
        self.assertEqual(0, self.run_compare(FIXTURE).returncode)

    def test_changed_settings_records_days_names_or_sample_bytes_are_rejected(self):
        mutations = [
            (b"bpm=91", b"bpm=92"),
            (b"Kopia 0.13", b"Kopia 9.99"),
            (b"\x00\x00\x00\x00\x00\x00\x03\xe8", b"\x00\x00\x00\x00\x00\x00\x03\xe9"),
            (b"1970-01-01", b"1970-01-02"),
            (b"Klik", b"Klok"),
            (None, None),
        ]
        for old, new in mutations:
            with self.subTest(field=old), tempfile.TemporaryDirectory(prefix="upgrade-oracle-") as directory:
                actual = Path(directory) / "changed.ombak"
                with zipfile.ZipFile(FIXTURE) as source, zipfile.ZipFile(actual, "w", zipfile.ZIP_DEFLATED) as target:
                    for name in source.namelist():
                        content = source.read(name)
                        if name == "openmetronome.dat" and old is not None:
                            self.assertIn(old, content)
                            content = content.replace(old, new, 1)
                        if name.endswith(".wav") and old is None:
                            content = content[:-1] + bytes([content[-1] ^ 1])
                        target.writestr(name, content)
                self.assertNotEqual(0, self.run_compare(actual).returncode)


if __name__ == "__main__":
    unittest.main()
