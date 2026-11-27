#!/usr/bin/env python3
"""Check the committed synthetic fixtures with fitdecode 0.11.0 (development only)."""
from datetime import datetime, timedelta, timezone
from pathlib import Path

import fitdecode

FIXTURES = Path(__file__).resolve().parents[1] / 'app/src/test/resources/fit'
ORIGIN = datetime(2026, 6, 10, 4, 26, 40, tzinfo=timezone.utc)
EXPECTED = {
    'continuous': ([(0, 130, 130051, 130051)], [(0, 'start'), (130, 'stop_all')]),
    'paused-big-endian': (
        [(0, 197, 196565, 133360)],
        [(0, 'start'), (61, 'stop'), (125, 'start'), (197, 'stop_all')],
    ),
    'compressed-developer': ([(28, 34, 6000, 6000)], [(28, 'start'), (34, 'stop_all')]),
    'multiple-reordered': (
        [(60, 120, 60000, 60000), (0, 60, 60000, 60000)],
        [(120, 'stop_all'), (0, 'start'), (60, 'stop'), (60, 'start'), (60, 'start')],
    ),
    'summary-only': ([(0, 130, 130051, 130051)], [(0, 'start')]),
}


def check(name, expected_sessions, expected_events):
    with fitdecode.FitReader(
        str(FIXTURES / f'{name}.fit'),
        check_crc=fitdecode.CrcCheck.RAISE,
        error_handling=fitdecode.ErrorHandling.RAISE,
    ) as reader:
        frames = list(reader)
    crc = [frame for frame in frames if isinstance(frame, fitdecode.FitCRC)]
    assert len(crc) == 1 and crc[0].matched, name
    messages = [frame for frame in frames if isinstance(frame, fitdecode.FitDataMessage)]
    assert messages[0].name == 'file_id' and messages[0].get_value('type') == 'activity', name
    sessions = [(
        session.get_value('start_time'),
        session.get_value('timestamp'),
        session.get_raw_value('total_elapsed_time'),
        session.get_raw_value('total_timer_time'),
    ) for session in messages if session.name == 'session']
    events = [(event.get_value('timestamp'), event.get_value('event_type'))
              for event in messages if event.name == 'event']
    assert sessions == [(ORIGIN + timedelta(seconds=start), ORIGIN + timedelta(seconds=end), elapsed, timer)
                        for start, end, elapsed, timer in expected_sessions], name
    assert events == [(ORIGIN + timedelta(seconds=seconds), kind) for seconds, kind in expected_events], name
    if name == 'compressed-developer':
        stop = [event for event in messages if event.name == 'event'][-1]
        assert stop.get_value('syntheti') == 42


if __name__ == '__main__':
    for fixture, expected in EXPECTED.items():
        check(fixture, *expected)
    print(f'PASS: {len(EXPECTED)} synthetic FIT files verified by fitdecode')
