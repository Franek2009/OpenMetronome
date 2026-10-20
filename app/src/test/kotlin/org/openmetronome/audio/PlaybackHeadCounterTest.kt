package org.openmetronome.audio

import org.junit.Assert.*
import org.junit.Test

class PlaybackHeadCounterTest {
    @Test fun `unsigned sign bit and complete wrap keep a monotonic Long`() {
        val counter = PlaybackHeadCounter()
        assertEquals(0L, counter.update(0))
        assertEquals(2147483647L, counter.update(Int.MAX_VALUE))
        assertEquals(2147483648L, counter.update(Int.MIN_VALUE))
        assertEquals(4294967295L, counter.update(-1))
        assertEquals(4294967296L, counter.update(0))
        assertEquals(4294967552L, counter.update(256))
    }
    @Test fun `new sessions start with an independent counter`() {
        PlaybackHeadCounter().update(-1)
        assertEquals(0L, PlaybackHeadCounter().update(0))
    }
}
