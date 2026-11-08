#!/usr/bin/env python3
"""Offline listening prototypes, not the application's SamplePcm implementation.
Only independently verified CC0 sources are used. Never plays audio.
"""
import hashlib, json, pathlib, wave
import numpy as np
ROOT = pathlib.Path(__file__).resolve().parents[3]
OUT = pathlib.Path(__file__).resolve().parent
SOURCE = ROOT / 'assets/samples/source/freesound.org/250552__druminfected__metronome.wav'
EXPECTED = '4a0309f22f3bf478911d73f724c9ad9520dfec8fbe2f8390252ec92b50cdc140'
RATE = 48000

def render(signal, source_rate, pitch, milliseconds):
    signal = signal - signal.mean()
    onset = max(0, int(np.argmax(np.abs(signal) >= np.max(np.abs(signal)) * .01)) - int(source_rate * .00025))
    count = int(RATE * milliseconds / 1000)
    # Windowed-sinc prototype; production qualification must call SamplePcm itself.
    ratio = source_rate * pitch / RATE
    cutoff = min(1., 1. / ratio) * .9
    radius = int(np.ceil(24 / cutoff))
    result = np.zeros(count)
    for i in range(count):
        pos = onset + i * ratio
        js = np.arange(max(0, int(pos) - radius), min(len(signal), int(pos) + radius + 1))
        distance = pos - js
        weights = cutoff * np.sinc(cutoff * distance) * (.5 + .5 * np.cos(np.pi * distance / (radius + 1)))
        result[i] = np.dot(signal[js], weights) / weights.sum() if abs(weights.sum()) > 1e-12 else 0
    edges = np.minimum(1, np.arange(count)/(RATE*.00025)) * np.minimum(1, np.arange(count-1,-1,-1)/(RATE*.0015))
    result *= edges
    result -= result.sum()/edges.sum()*edges
    result *= 29162 / np.max(np.abs(result))
    pcm = np.where(result >= 0, np.floor(result+.5), np.ceil(result-.5)).astype('<i2')
    assert pcm[0] == pcm[-1] == 0 and np.max(np.abs(pcm.astype(np.int32))) <= 29162
    assert len(pcm) <= 1920 and abs(float(pcm.mean())) < 1
    return pcm

def save(name, pcm):
    p = OUT / name
    with wave.open(str(p), 'wb') as w:
        w.setparams((1,2,RATE,0,'NONE','not compressed')); w.writeframes(pcm.tobytes())
    return dict(file=name,sha256=hashlib.sha256(p.read_bytes()).hexdigest(),frames=len(pcm),peak=int(np.max(np.abs(pcm.astype(np.int32)))),dc_lsb=float(pcm.mean()))

def main():
    assert hashlib.sha256(SOURCE.read_bytes()).hexdigest() == EXPECTED, 'Source changed: review first'
    with wave.open(str(SOURCE)) as w:
        assert w.getsampwidth() == 2
        rate, channels = w.getframerate(), w.getnchannels()
        signal = np.frombuffer(w.readframes(w.getnframes()),dtype='<i2').astype(float).reshape(-1,channels).mean(axis=1)/32768
    report=[]
    for name,pitch,duration in [('01-natural',1.,40),('02-short',1.,24),('03-low',.8,40)]:
        normal = render(signal,rate,pitch,duration)
        accent = render(signal,rate,pitch*1.22,duration)
        report += [save(name+'-normal.wav',normal),save(name+'-accent.wav',accent)]
        demo = np.zeros(RATE*5,dtype='<i2')
        for beat in range(8):
            pcm = accent if beat%4 == 0 else normal
            start = RATE//4 + beat*RATE//2
            demo[start:start+len(pcm)] = pcm
        # Demo is deliberately longer than the runtime sample limit.
        report.append(save(name+'-demo.wav',demo))
    (OUT/'measurements.json').write_text(json.dumps(report,indent=2)+'\n')
    print('PASS: 3 prototype pairs, 3 demos; bounded PCM, zero endpoints, DC < 1 LSB; no playback')

def vcsl_previews():
    metadata = json.loads((ROOT / 'assets/samples/licenses/clr-vcsl-metadata.json').read_text())
    audited = {pathlib.Path(row['file']).name: row['sha256'] for row in json.loads((ROOT / 'assets/samples/source-measurements.json').read_text())}
    names = [('04-cowbell-one','cowbell1'), ('05-cowbell-two','cowbell2'), ('06-tom-high','tomh'), ('07-tom-low','toml')]
    report = []
    for prefix, needle in names:
        item = next(row for row in metadata if needle in row['file'])
        assert item['license']['name'] == 'CC0'
        source = ROOT / 'assets/samples/source/crl.fm' / pathlib.Path(item['file']).name
        assert hashlib.sha256(source.read_bytes()).hexdigest() == audited[source.name]
        with wave.open(str(source)) as w:
            assert w.getsampwidth() == 2
            rate, channels = w.getframerate(), w.getnchannels()
            signal = np.frombuffer(w.readframes(w.getnframes()), dtype='<i2').astype(float).reshape(-1, channels).mean(axis=1)/32768
        normal = render(signal, rate, 1., 40)
        accent = render(signal, rate, 1.22, 40)
        report += [save(prefix+'-normal.wav', normal), save(prefix+'-accent.wav', accent)]
        demo = np.zeros(RATE*5, dtype='<i2')
        for beat in range(8):
            pcm = accent if beat%4 == 0 else normal
            start = RATE//4 + beat*RATE//2
            demo[start:start+len(pcm)] = pcm
        report.append(save(prefix+'-demo.wav', demo))
    (OUT/'vcsl-measurements.json').write_text(json.dumps(report, indent=2)+'\n')

if __name__ == '__main__':
    main()
    vcsl_previews()
