#!/usr/bin/env python3
"""Pre-install gates of the real upgrade script; fake SDK/adb, never a device."""
import hashlib
import os
from pathlib import Path
import shutil
import subprocess
import tempfile
import unittest

ROOT = Path(__file__).resolve().parent.parent
CERT = "563cd960efc3fc5b483ceae4b96e66728af9e2bb0c52d6162fe37cded72ac4a2"


class OfficialUpgradeGuardsTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory(prefix="official-upgrade-guards-")
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        for folder in ("scripts", "app", "bin", "sdk/build-tools/36.0.0"):
            (self.root / folder).mkdir(parents=True)
        for name in ("release-info.sh", "verify-upgrade.sh"):
            shutil.copyfile(ROOT / "scripts" / name, self.root / "scripts" / name)
        self.metadata(20)
        for name in ("old.apk", "new.apk"):
            (self.root / name).write_bytes(name.encode())
        self.stub("bin/adb", '''#!/usr/bin/env bash
if [[ "$*" == *'shell pm path'* ]]; then exit 0; fi
if [[ "$*" == *'install old.apk' ]]; then touch "$INSTALL_MARKER"; exit 73; fi
echo 'Unexpected adb command' >&2; exit 99
''')
        self.stub("sdk/build-tools/36.0.0/aapt", '''#!/usr/bin/env bash
if [[ "$3" == old.apk ]]; then code="$OLD_CODE"; version="$OLD_VERSION"; else code="$NEW_CODE"; version=1.1.0; fi
printf "package: name='%s' versionCode='%s' versionName='%s'\n" "$PACKAGE" "$code" "$version"
[[ "${DEBUG_APK:-}" != "$3" ]] || echo application-debuggable
exit 0
''')
        self.stub("sdk/build-tools/36.0.0/apksigner", '''#!/usr/bin/env bash
[[ "$1" == verify ]] || { echo 'Signing forbidden' >&2; exit 99; }
printf 'Signer #1 certificate SHA-256 digest: %s\n' "$CERTIFICATE"
''')
        self.env = os.environ | {
            "PATH": str(self.root / "bin") + ":" + os.environ["PATH"],
            "ANDROID_HOME": str(self.root / "sdk"), "ANDROID_SERIAL": "emulator-5554",
            "OLD_CODE": "19", "OLD_VERSION": "1.1.0-beta.1", "NEW_CODE": "20",
            "PACKAGE": "org.openmetronome", "CERTIFICATE": CERT,
            "INSTALL_MARKER": str(self.root / "installed"),
            "OFFICIAL_BASELINE_SHA256": hashlib.sha256(b"old.apk").hexdigest(),
            "OFFICIAL_CANDIDATE_SHA256": hashlib.sha256(b"new.apk").hexdigest(),
        }

    def stub(self, name, content):
        path = self.root / name
        path.write_text(content)
        path.chmod(0o755)

    def metadata(self, code):
        (self.root / "app/build.gradle.kts").write_text(f'versionName = "1.1.0"\nversionCode = {code}\n')

    def run_gate(self, mode="--official-beta", **env):
        (self.root / "installed").unlink(missing_ok=True)
        result = subprocess.run(["bash", "scripts/verify-upgrade.sh", mode, "old.apk", "new.apk"],
                                cwd=self.root, env=self.env | env, capture_output=True, text=True)
        return result, (self.root / "installed").exists()

    def test_both_official_baselines_pass_all_gates_without_signing(self):
        for mode, version, code in (("--official", "1.0.0", "18"),
                                    ("--official-beta", "1.1.0-beta.1", "19")):
            with self.subTest(mode=mode):
                result, installed = self.run_gate(mode, OLD_VERSION=version, OLD_CODE=code)
                self.assertEqual(73, result.returncode, result.stderr)
                self.assertTrue(installed)

    def test_wrong_baseline_package_hash_certificate_or_debug_refused_before_install(self):
        for change in ({"OLD_CODE": "18"}, {"OLD_VERSION": "1.0.0"},
                       {"PACKAGE": "org.openmetronome.dev"}, {"CERTIFICATE": "wrong"},
                       {"OFFICIAL_BASELINE_SHA256": "wrong"}, {"OFFICIAL_CANDIDATE_SHA256": "wrong"},
                       {"DEBUG_APK": "old.apk"}, {"DEBUG_APK": "new.apk"}):
            with self.subTest(change=change):
                result, installed = self.run_gate(**change)
                self.assertNotEqual(0, result.returncode)
                self.assertFalse(installed)

    def test_same_version_code_is_not_an_upgrade(self):
        self.metadata(19)
        result, installed = self.run_gate(NEW_CODE="19")
        self.assertNotEqual(0, result.returncode)
        self.assertFalse(installed)


if __name__ == "__main__":
    unittest.main()
