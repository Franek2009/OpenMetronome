#!/usr/bin/env python3
"""Read-only WAV qualification measurements; never plays or modifies sources."""
import argparse
import hashlib
import json
import math
import struct
import wave
from pathlib import Path


def audit(path):
    with wave.open(str(path)) as wav:
        rate, channels, width, frames = wav.getframerate(), wav.getnchannels(), wav.getsampwidth(), wav.getnframes()
        data = wav.readframes(frames)
    if width not in (2, 3, 4) or channels not in (1, 2):
        raise ValueError(f'Unsupported format: {path}')
    scale = 2 ** (width * 8 - 1)
    if width in (2, 4):
        values = [v / scale for (v,) in struct.iter_unpack('<h' if width == 2 else '<i', data)]
    else:
        values = [int.from_bytes(data[i:i+3], 'little', signed=True) / scale for i in range(0, len(data), 3)]
    tracks = [values[i::channels] for i in range(channels)]
    mono = [sum(row) / channels for row in zip(*tracks)]
    peak = max(map(abs, mono))
    onset = max(0, next(i for i, value in enumerate(mono) if abs(value) >= peak * .01) - int(rate * .00025))
    total = sum(v*v for v in mono)
    window = mono[onset:onset + rate * 40 // 1000]
    def db(value): return 20 * math.log10(value) if value > 0 else None
    return dict(file=str(path), sha256=hashlib.sha256(path.read_bytes()).hexdigest(),
                rate=rate, channels=channels, bits=width*8, durationSeconds=frames/rate,
                channelPeakDbfs=[db(max(map(abs,t))) for t in tracks],
                channelDc=[sum(t)/len(t) for t in tracks],
                channelRmsDbfs=[db(math.sqrt(sum(v*v for v in t)/len(t))) for t in tracks],
                fullScaleSamples=sum(abs(v) >= (scale-1)/scale for v in values),
                onsetMs=onset*1000/rate, energy40Percent=100*sum(v*v for v in window)/total,
                downmixEnergyRatio=(total/len(mono))/(sum(v*v for v in values)/len(values)),
                monoLargestStep=max(abs(b-a) for a,b in zip(mono,mono[1:])),
                monoFirst=mono[0], monoLast=mono[-1])


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('directory', type=Path, nargs='?', default=Path('assets/samples/source'))
    args = parser.parse_args()
    files = sorted(args.directory.rglob('*.wav'))
    if not files: parser.error('No WAV sources')
    print(json.dumps([audit(path) for path in files], indent=2, allow_nan=False))
