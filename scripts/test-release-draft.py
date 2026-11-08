#!/usr/bin/env python3
"""Exercise real draft assembly with fake artifacts and gh; no signing or network."""
import hashlib
import json
import os
from pathlib import Path
import shutil
import subprocess
import tempfile
import unittest

ROOT = Path(__file__).resolve().parent.parent


class DraftTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory(prefix="release-draft-")
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        for folder in ("scripts", "app", "docs/releases", "bin", "prod", "dev"):
            (self.root / folder).mkdir(parents=True)
        for name in ("release-info.sh", "create-release-draft.sh"):
            shutil.copyfile(ROOT / "scripts" / name, self.root / "scripts" / name)
        fake = self.root / "bin/gh"
        fake.write_text('''#!/usr/bin/env python3
import hashlib, json, os, pathlib, sys
args = sys.argv[1:]
files = {a: pathlib.Path(a).read_text() for a in args if pathlib.Path(a).is_file()}
pathlib.Path(os.environ["CAPTURE"]).write_text(json.dumps({"args": args, "files": files}))
sys.exit(int(os.environ.get("GH_FAILURE", "0")))
''')
        fake.chmod(0o755)
        self.env = os.environ | {"PATH": str(self.root / "bin") + ":" + os.environ["PATH"],
                                 "CAPTURE": str(self.root / "capture.json")}

    def artifacts(self, version="1.1.0"):
        (self.root / "app/build.gradle.kts").write_text(f'versionName = "{version}"\nversionCode = 20\n')
        (self.root / f"docs/releases/{version}.md").write_text("Release notes\n")
        for directory, prefix, cert in (("prod", "", "SIGNING-CERT-SHA256.txt"),
                                        ("dev", "DEV-", "DEV-SIGNING-CERT-SHA256.txt")):
            files = {f"OpenMetronome-{prefix}{version}.apk": directory + " APK", cert: directory + " cert"}
            for name, content in files.items():
                (self.root / directory / name).write_text(content)
            (self.root / directory / "SHA256SUMS").write_text("".join(
                f"{hashlib.sha256(content.encode()).hexdigest()}  {name}\n" for name, content in files.items()))

    def run_draft(self, **env):
        return subprocess.run(["bash", "scripts/create-release-draft.sh", "prod", "dev"],
                              cwd=self.root, env=self.env | env, capture_output=True, text=True)

    def captured(self):
        captured = json.loads((self.root / "capture.json").read_text())
        for line in captured["files"]["SHA256SUMS"].splitlines():
            digest, name = line.split("  ", 1)
            self.assertEqual(digest, hashlib.sha256(captured["files"][name].encode()).hexdigest())
        return captured

    def test_prod_only_stable_has_exact_three_assets_and_no_dev_dependency(self):
        self.artifacts()
        shutil.rmtree(self.root / "dev")
        result = self.run_draft(RELEASE_INCLUDE_DEV="false")
        self.assertEqual(0, result.returncode, result.stderr)
        captured = self.captured()
        self.assertEqual({"OpenMetronome-1.1.0.apk", "SIGNING-CERT-SHA256.txt", "SHA256SUMS"},
                         {name for name in captured["files"] if not name.startswith("/")})
        self.assertIn("--draft", captured["args"])
        self.assertIn("--verify-tag", captured["args"])
        self.assertNotIn("--prerelease", captured["args"])
        self.assertNotIn("DEV", captured["files"]["SHA256SUMS"])
        self.assertFalse(list((self.root / "app/build").glob("draft-assets.*")))

    def test_default_includes_dev_and_prerelease_flag_for_rc(self):
        self.artifacts("1.1.0-rc.1")
        self.assertEqual(0, self.run_draft().returncode)
        captured = self.captured()
        self.assertIn("--prerelease", captured["args"])
        self.assertEqual(5, len([name for name in captured["files"] if not name.startswith("/")]))
        self.assertIn("OpenMetronome-DEV-1.1.0-rc.1.apk", captured["files"])
        self.assertIn("--draft", captured["args"])

    def test_corrupt_artifact_missing_dev_or_invalid_selection_never_calls_gh(self):
        for failure in ("checksum", "missing-dev", "invalid"):
            with self.subTest(failure=failure):
                self.artifacts()
                env = {}
                if failure == "checksum":
                    (self.root / "prod/OpenMetronome-1.1.0.apk").write_text("changed")
                elif failure == "missing-dev":
                    (self.root / "dev/OpenMetronome-DEV-1.1.0.apk").unlink()
                else:
                    env["RELEASE_INCLUDE_DEV"] = "yes"
                self.assertNotEqual(0, self.run_draft(**env).returncode)
                self.assertFalse((self.root / "capture.json").exists())

    def test_existing_release_refusal_is_propagated_without_edit_or_overwrite(self):
        self.artifacts()
        self.assertEqual(7, self.run_draft(GH_FAILURE="7").returncode)
        self.assertEqual(["release", "create", "v1.1.0"], self.captured()["args"][:3])


if __name__ == "__main__":
    unittest.main()
