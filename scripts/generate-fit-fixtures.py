#!/usr/bin/env python3
"""Generate synthetic FIT inputs. No captured FIT or personal data is used."""
from pathlib import Path
import struct

ROOT = Path(__file__).resolve().parents[1] / 'app/src/test/resources/fit'
ANCHOR = 1_150_000_000
U32 = 0x86
EVENT = ((253, 4, U32), (0, 1, 0), (1, 1, 0))
SESSION = ((253, 4, U32), (2, 4, U32), (7, 4, U32), (8, 4, U32), (5, 1, 0), (6, 1, 0))


def definition(local, message, fields, big=False, developer=()):
    return (bytes([0x40 | local | (0x20 if developer else 0), 0, int(big)])
            + struct.pack('>H' if big else '<H', message) + bytes((len(fields),))
            + b''.join(bytes(field) for field in fields)
            + (bytes((len(developer),)) + b''.join(bytes(field) for field in developer) if developer else b''))


def record(local, layout, *values, big=False):
    return bytes((local,)) + struct.pack(('>' if big else '<') + layout, *values)


def file_id(kind=4):
    return definition(0, 0, ((0, 1, 0),)) + record(0, 'B', kind)


def event(t, kind):
    return record(1, 'IBB', ANCHOR + t, 0, kind)


def session(begin, stop, elapsed, timer, big=False):
    return record(2, 'IIIIBB', ANCHOR + stop, ANCHOR + begin, elapsed, timer, 0, 0, big=big)


def activity(count):
    return definition(3, 34, ((1, 2, 0x84),)) + record(3, 'H', count)


def crc16(data):
    # CRC-16/ARC, independent direct long-division implementation.
    crc = 0
    for byte in data:
        for bit in range(8):
            carry = (crc ^ (byte >> bit)) & 1
            crc >>= 1
            if carry:
                crc ^= 0xA001
    return crc


def container(body, header_size=14, with_header_crc=True, protocol=0x20):
    header = struct.pack('<BBHI4s', header_size, protocol, 2100, len(body), b'.FIT')
    if header_size == 14:
        header += struct.pack('<H', crc16(header) if with_header_crc else 0)
    payload = header + body
    return payload + struct.pack('<H', crc16(payload))


def main():
    start = file_id() + definition(1, 21, EVENT)
    summary = definition(2, 18, SESSION)
    continuous = start + event(0, 0) + event(130, 4) + summary + session(0, 130, 130051, 130051) + activity(1)
    paused = (file_id() + definition(2, 18, SESSION, big=True) + session(0, 197, 196565, 133360, big=True)
              + definition(1, 21, EVENT) + event(0, 0) + event(61, 1) + event(125, 0) + event(197, 4) + activity(1))
    developers = (definition(4, 207, ((1, 16, 0x0D), (3, 1, 2))) + record(4, '16sB', bytes(16), 0)
                  + definition(4, 206, ((0, 1, 2), (1, 1, 2), (2, 1, 2), (3, 8, 7)))
                  + record(4, 'BBB8s', 0, 1, 2, b'synthetic'))
    compressed = (start + event(28, 0) + developers + definition(1, 21, EVENT[1:], developer=((1, 1, 0),))
                  + record(0xA0 | ((ANCHOR + 34) & 31), 'BBB', 0, 4, 42)
                  + summary + session(28, 34, 6000, 6000) + activity(1))
    multiple = (start + event(120, 4) + event(0, 0) + event(60, 1) + event(60, 0) + event(60, 0)
                + summary + session(60, 120, 60000, 60000) + session(0, 60, 60000, 60000) + activity(2))
    incomplete = start + event(0, 0) + summary + session(0, 130, 130051, 130051) + activity(1)
    fixtures = {
        'continuous': container(continuous),
        'paused-big-endian': container(paused, 12, protocol=0x10),
        'compressed-developer': container(compressed),
        'multiple-reordered': container(multiple, with_header_crc=False),
        'summary-only': container(incomplete),
    }
    for name, data in fixtures.items():
        (ROOT / f'{name}.fit').write_bytes(data)


if __name__ == '__main__':
    main()
