#!/usr/bin/env python3
"""Emulate emulator-runner: every nonempty script line runs separately in sh."""
import os
from pathlib import Path
import subprocess
import tempfile
import unittest

ROOT = Path(__file__).resolve().parent.parent
WORKFLOW = ROOT / ".github/workflows/release.yml"


class SmokeLinesTest(unittest.TestCase):
    def run_lines(self, include_dev, dev_exit=0):
        workflow = WORKFLOW.read_text()
        smoke = workflow.split("  smoke:\n", 1)[1].split("  draft:\n", 1)[0]
        lines = smoke.split("          script: |\n", 1)[1].split("      - uses:", 1)[0]
        with tempfile.TemporaryDirectory(prefix="release-smoke-lines-") as temporary:
            root = Path(temporary)
            (root / "scripts").mkdir()
            for folder in ("prod-assets", "dev-assets"):
                (root / folder).mkdir()
                (root / folder / "signed.apk").touch()
            (root / "scripts/smoke-release.sh").write_text('''#!/usr/bin/env bash
set -eu
[[ "$ANDROID_SERIAL" == emulator-5554 ]]
printf '%s\\n' "$*" >> "$CALLS"
if [[ "${1:-}" == --dev ]]; then exit "$DEV_EXIT"; fi
''')
            env = os.environ | {"CALLS": str(root / "calls"), "DEV_EXIT": str(dev_exit)}
            results = []
            for line in lines.splitlines():
                if line.strip():
                    results.append(subprocess.run(["/bin/sh", "-c", line.strip().replace(
                        "${{ inputs.include_dev }}", str(include_dev).lower())],
                        cwd=root, env=env, capture_output=True, text=True))
                    if results[-1].returncode:
                        break
            calls = (root / "calls").read_text().splitlines()
            return results, calls

    def test_prod_only_skips_dev_without_shell_error(self):
        results, calls = self.run_lines(False)
        self.assertTrue(all(result.returncode == 0 for result in results), [r.stderr for r in results])
        self.assertEqual(1, len(calls))
        self.assertIn("prod-assets/signed.apk", calls[0])

    def test_default_runs_both_variants_and_propagates_dev_failure(self):
        results, calls = self.run_lines(True)
        self.assertTrue(all(result.returncode == 0 for result in results), [r.stderr for r in results])
        self.assertEqual(2, len(calls))
        self.assertTrue(calls[1].startswith("--dev dev-assets/signed.apk"))
        results, _ = self.run_lines(True, dev_exit=9)
        self.assertEqual(9, results[-1].returncode)


if __name__ == "__main__":
    unittest.main()
