#!/usr/bin/env python3
"""Regression tests for the upgrade comparison oracle, using only a synthetic archive."""
import os
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
    def run_compare(self, actual):
        return subprocess.run([sys.executable, str(COMPARE), str(FIXTURE), str(actual)],
                              env=os.environ | {"PYTHONOPTIMIZE": "1"}, capture_output=True)

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
