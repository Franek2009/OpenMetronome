#!/usr/bin/env python3
"""Offline spectral report for our exported click bank. Standard library only; never plays audio."""
import argparse
import cmath
import csv
import math
from pathlib import Path
import struct
import wave


def spectrum(samples, rate):
    size = 1 << (len(samples) - 1).bit_length()
    # Window just for spectral analysis; exported/played PCM stays unmodified.
    values = [complex(x * (.5 - .5 * math.cos(2 * math.pi * i / (len(samples) - 1))))
              for i, x in enumerate(samples)] + [0j] * (size - len(samples))
    j = 0
    for i in range(1, size):
        bit = size >> 1
        while j & bit:
            j ^= bit
            bit >>= 1
        j ^= bit
        if i < j:
            values[i], values[j] = values[j], values[i]
    span = 2
    while span <= size:
        step = cmath.exp(-2j * math.pi / span)
        for start in range(0, size, span):
            factor = 1 + 0j
            for offset in range(span // 2):
                a = values[start + offset]
                b = factor * values[start + offset + span // 2]
                values[start + offset] = a + b
                values[start + offset + span // 2] = a - b
                factor *= step
        span *= 2
    powers = [abs(x) ** 2 for x in values[:size // 2 + 1]]
    powers[0] = 0  # DC is checked separately by the JVM tests.
    total = sum(powers)
    frequencies = [i * rate / size for i in range(len(powers))]
    peak = frequencies[max(range(len(powers)), key=powers.__getitem__)]
    centroid = sum(f * p for f, p in zip(frequencies, powers)) / total
    fractions = [sum(p for f, p in zip(frequencies, powers) if lo <= f < hi) / total
                 for lo, hi in [(0, 1000), (1000, 4000), (4000, float('inf'))]]
    return peak, centroid, *fractions


def analyze(directory):
    with (directory / 'levels.csv').open(newline='') as source:
        rows = list(csv.DictReader(source))
    results = []
    for rate in (44100, 48000):
        with wave.open(str(directory / f'bank-{rate}.wav'), 'rb') as source:
            assert (source.getnchannels(), source.getsampwidth(), source.getframerate()) == (1, 2, rate)
            data = source.readframes(source.getnframes())
        rate_rows = [row for row in rows if int(row['rate']) == rate]
        slot = rate * 3 // 10
        assert len(data) == slot * len(rate_rows) * 2
        for i, row in enumerate(rate_rows):
            frames = round(float(row['durationMs']) * rate / 1000)
            pcm = struct.unpack(f'<{frames}h', data[i * slot * 2:(i * slot + frames) * 2])
            assert max(abs(x) for x in pcm) == 29162
            assert pcm[0] == pcm[-1] == 0
            results.append([rate, row['sound'], row['accent'], row['auditionNumber'],
                            *[f'{value:.6f}' for value in spectrum(pcm, rate)]])
    with (directory / 'spectrum.csv').open('w', newline='') as target:
        writer = csv.writer(target)
        writer.writerow(['rate', 'sound', 'accent', 'auditionNumber', 'peakHz', 'centroidHz',
                         'fractionBelow1k', 'fraction1kTo4k', 'fractionAbove4k'])
        writer.writerows(results)
    print(f'PASS: {len(results)} clicks checked; spectrum.csv written; no playback.')


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('directory', type=Path, nargs='?', default=Path('app/build/reports/audio/v012'))
    analyze(parser.parse_args().directory)
