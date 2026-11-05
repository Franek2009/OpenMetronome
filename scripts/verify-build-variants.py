#!/usr/bin/env python3
"""Inspect actual build artifacts; no installation, playback or signing."""
import os
from pathlib import Path
import re
import subprocess
import tempfile
import zipfile

ROOT = Path(__file__).resolve().parent.parent
TOOLS = Path(os.environ['ANDROID_HOME']) / 'build-tools/36.0.0'
META = subprocess.check_output(['bash', '-c',
    'source scripts/release-info.sh; printf "%s\\n%s\\n" "$release_version" "$release_code"'],
    cwd=ROOT, text=True).splitlines()

for flavor in ('prod', 'dev'):
    for kind in ('debug', 'release'):
        package = 'org.openmetronome' + ('.dev' if flavor == 'dev' else '') + ('.debug' if kind == 'debug' else '')
        version = META[0] + ('-dev' if flavor == 'dev' else '')
        directory = ROOT / f'app/build/outputs/apk/{flavor}/{kind}'
        # A stale signed output must not hide a newly built unsigned output.
        candidates = list(directory.glob(f'app-{flavor}-{kind}*.apk'))
        if len(candidates) != 1:
            raise SystemExit(f'Expected exactly one {flavor}/{kind} APK; found {candidates}')
        apk = candidates[0]
        badging = subprocess.check_output([TOOLS / 'aapt', 'dump', 'badging', apk], text=True)
        expected = f"package: name='{package}' versionCode='{META[1]}' versionName='{version}'"
        if not badging.startswith(expected + ' '):
            raise SystemExit(f'Wrong identity: {apk}\n{badging.splitlines()[0]}')
        if ('application-debuggable' in badging) != (kind == 'debug'):
            raise SystemExit(f'Wrong debuggable flag: {apk}')
        label = 'OpenMetronome DEV' if flavor == 'dev' else 'OpenMetronome'
        if f"application-label:'{label}'" not in badging:
            raise SystemExit(f'Wrong label: {apk}')
        manifest = subprocess.check_output([TOOLS / 'aapt', 'dump', 'xmltree', apk, 'AndroidManifest.xml'], text=True)
        if 'testOnly' in manifest:
            raise SystemExit(f'testOnly leaked: {apk}')
        if kind == 'release':
            forbidden = (b'FailingForegroundService', b'RestoreCrashProbeService', b'BackupDocumentsProvider',
                         b'Lorg/openmetronome/TestApplication;', b'Landroidx/test/', b'Lorg/junit/')
            with zipfile.ZipFile(apk) as archive:
                dex = b''.join(archive.read(name) for name in archive.namelist() if re.fullmatch(r'classes\d*\.dex', name))
            if any(word in dex or word.decode() in manifest for word in forbidden):
                raise SystemExit(f'Test components leaked: {apk}')
            dev_recorder = (b'Lorg/openmetronome/diagnostics/BoundedPcmTrace;',
                            b'Lorg/openmetronome/diagnostics/PcmTraceRecord;')
            if flavor == 'prod' and any(name in dex for name in dev_recorder):
                raise SystemExit(f'DEV recorder leaked into production: {apk}')
            if flavor == 'dev' and not all(name in dex for name in dev_recorder):
                raise SystemExit(f'DEV recorder missing from DEV artifact: {apk}')
        else:
            tests = ROOT / f'app/build/outputs/apk/androidTest/{flavor}/debug/app-{flavor}-debug-androidTest.apk'
            tree = subprocess.check_output([TOOLS / 'aapt', 'dump', 'xmltree', tests, 'AndroidManifest.xml'], text=True)
            if f'"{package}.test"' not in tree or f'"{package}"' not in tree:
                raise SystemExit(f'Wrong instrumentation target: {tests}')
            if f'"{package}.backup-documents"' not in manifest:
                raise SystemExit(f'Wrong debug provider authority: {apk}')
        if package != 'org.openmetronome':
            # Exercise the publisher with actual non-production bytes disguised
            # by a production filename; names are not a trust boundary.
            with tempfile.TemporaryDirectory(prefix='openmetronome-package-guard-') as directory:
                renamed = Path(directory) / 'app-prod-release.apk'
                renamed.symlink_to(apk)
                rejected = subprocess.run(['bash', 'scripts/package-release.sh', renamed],
                                          cwd=ROOT, capture_output=True, text=True)
                if rejected.returncode == 0 or 'Refusing a non-production package' not in rejected.stderr:
                    raise SystemExit(f'Publisher failed to reject {package}: {rejected.stdout}{rejected.stderr}')
        print(f'PASS {flavor}/{kind}: {package}, {version} ({META[1]})')
