#!/usr/bin/env python3
"""Read-only structural/loss summary for OpenMetronome DEV ZIP v1. No playback."""
import csv
import io
import json
import sys
import zipfile

HEADER = ['source', 'sequence', 'nanos', 'kind_code', 'kind', 'frame', 'a', 'b', 'c', 'd', 'config']
EVENT_CODES = {1, 3, 4, 5, 14, 15, 16, 17, 18, 19, 20}


def analyze(path):
    csv.field_size_limit(64 * 1024)
    with zipfile.ZipFile(path) as archive:
        names = archive.namelist()
        if len(names) != 4 or set(names) != {'beats.csv', 'events.csv', 'audio.csv', 'metadata.json'}:
            raise ValueError('Expected exactly four unique report files')
        if sum(item.file_size for item in archive.infolist()) > 2 * 1024**3:
            raise ValueError('Uncompressed report exceeds analysis limit')
        if archive.getinfo('metadata.json').file_size > 128 * 1024:
            raise ValueError('Metadata too large')
        metadata = json.loads(archive.read('metadata.json'))
        if type(metadata.get('schemaVersion')) is not int or metadata['schemaVersion'] != 1:
            raise ValueError('Unsupported schema')
        if metadata.get('state') not in ('complete', 'incomplete', 'interrupted'):
            raise ValueError('Invalid report state')
        counts = {'pcm': 0, 'control': 0}
        for name in ('beats.csv', 'events.csv', 'audio.csv'):
            last = {}
            with archive.open(name) as source:
                rows = csv.reader(io.TextIOWrapper(source, encoding='utf-8', newline=''))
                if next(rows, None) != HEADER:
                    raise ValueError('Unexpected CSV header')
                for row in rows:
                    if len(row) != len(HEADER) or row[0] not in counts:
                        raise ValueError('Invalid CSV row')
                    lane = row[0]
                    values = [int(row[i]) for i in (1, 2, 3, 6, 7, 8, 9)]
                    if any(value < -(2**63) or value >= 2**63 for value in values):
                        raise ValueError('Integer outside signed 64-bit range')
                    sequence, _, code = values[:3]
                    if sequence < 0 or sequence <= last.get(lane, -1):
                        raise ValueError('Sequence is not increasing within a lane/file')
                    last[lane] = sequence
                    dictionary = 'pcmKind' if lane == 'pcm' else 'controlKind'
                    if metadata.get(f'{dictionary}.{code}') != row[4]:
                        raise ValueError('Unknown or mismatched event code')
                    if row[5] and not 0 <= int(row[5]) < 2**63:
                        raise ValueError('Invalid PCM frame')
                    if name == 'beats.csv' and (lane != 'pcm' or code != 6):
                        raise ValueError('Non-beat in beats.csv')
                    if name == 'audio.csv' and (lane != 'pcm' or code == 6 or code in EVENT_CODES):
                        raise ValueError('Non-audio record in audio.csv')
                    if name == 'events.csv' and lane == 'pcm' and code not in EVENT_CODES:
                        raise ValueError('Non-event in events.csv')
                    counts[lane] += 1
        for lane, key in (('pcm', 'audioRecords'), ('control', 'controlRecords')):
            if type(metadata.get(key)) is not int or metadata[key] != counts[lane]:
                raise ValueError('Metadata/CSV count mismatch')
        if metadata['state'] == 'complete':
            for key in ('finalCountersKnown', 'controlFinalCountersKnown', 'audioAvailable', 'controlAvailable'):
                if metadata.get(key) is not True:
                    raise ValueError('Complete report has unavailable data/counters')
            for key, lane in (('accepted', 'pcm'), ('controlAccepted', 'control')):
                if type(metadata.get(key)) is not int or metadata[key] != counts[lane]:
                    raise ValueError('Complete report lost accepted records')
            for key in ('dropped', 'controlDropped'):
                if type(metadata.get(key)) is not int or metadata[key] != 0:
                    raise ValueError('Complete report declares capture loss')
        return {'schemaVersion': 1, 'state': metadata['state'], 'rows': counts,
                'captureDropped': metadata.get('dropped'), 'controlDropped': metadata.get('controlDropped'),
                'storageLostRecords': metadata.get('storageLostRecords'),
                'controlStorageLostRecords': metadata.get('controlStorageLostRecords'),
                'physicalJitterMeasured': False, 'physicalLatencyMeasured': False}


if __name__ == '__main__':
    try:
        if len(sys.argv) != 2:
            raise ValueError('Usage: analyze-dev-trace.py report.zip')
        print(json.dumps(analyze(sys.argv[1]), indent=2))
    except (ValueError, KeyError, OSError, zipfile.BadZipFile, csv.Error, UnicodeError) as error:
        print(f'FAIL: {error}', file=sys.stderr)
        sys.exit(1)
