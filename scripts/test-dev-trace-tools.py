#!/usr/bin/env python3
import importlib.util
import json
from pathlib import Path
import tempfile
import unittest
import zipfile

spec = importlib.util.spec_from_file_location('trace_analyzer', Path(__file__).with_name('analyze-dev-trace.py'))
module = importlib.util.module_from_spec(spec)
spec.loader.exec_module(module)
HEADER = ','.join(module.HEADER) + '\r\n'


class TraceToolTest(unittest.TestCase):
    def fixture(self, directory, metadata=None, duplicate=False):
        path = Path(directory) / 'trace.zip'
        meta = {'schemaVersion': 1, 'state': 'complete', 'audioRecords': 1, 'controlRecords': 0,
                'finalCountersKnown': True, 'controlFinalCountersKnown': True, 'audioAvailable': True,
                'controlAvailable': True, 'accepted': 1, 'controlAccepted': 0, 'dropped': 0,
                'controlDropped': 0, 'pcmKind.6': 'BEAT'}
        meta.update(metadata or {})
        with zipfile.ZipFile(path, 'w') as archive:
            archive.writestr('beats.csv', HEADER + 'pcm,0,100,6,BEAT,123,0,0,0,256,""\r\n')
            archive.writestr('events.csv', HEADER)
            archive.writestr('audio.csv', HEADER)
            archive.writestr('metadata.json', json.dumps(meta))
            if duplicate:
                archive.writestr('extra.txt', 'unexpected')
        return path

    def test_complete_fixture(self):
        with tempfile.TemporaryDirectory() as directory:
            result = module.analyze(self.fixture(directory))
            self.assertEqual(result['rows'], {'pcm': 1, 'control': 0})
            self.assertIsNone(result['storageLostRecords'])

    def test_incomplete_missing_counters_remain_unknown(self):
        with tempfile.TemporaryDirectory() as directory:
            result = module.analyze(self.fixture(directory, {'state': 'interrupted', 'dropped': None,
                'controlDropped': None, 'finalCountersKnown': False}))
            self.assertIsNone(result['captureDropped'])
            self.assertIsNone(result['controlDropped'])

    def test_false_complete_or_bad_counts_are_rejected(self):
        with tempfile.TemporaryDirectory() as directory:
            for overrides in ({'dropped': 1}, {'audioRecords': 2}, {'finalCountersKnown': False}, {'schemaVersion': 2}):
                with self.assertRaises(ValueError):
                    module.analyze(self.fixture(directory, overrides))

    def test_unexpected_archive_entry_is_rejected(self):
        with tempfile.TemporaryDirectory() as directory:
            with self.assertRaises(ValueError):
                module.analyze(self.fixture(directory, duplicate=True))


if __name__ == '__main__':
    unittest.main()
