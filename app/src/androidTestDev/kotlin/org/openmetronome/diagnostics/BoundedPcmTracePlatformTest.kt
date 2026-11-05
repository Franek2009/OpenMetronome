package org.openmetronome.diagnostics

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.openmetronome.domain.audio.PcmTraceKind

/** DEV flavor only; no device playback or application data. */
@RunWith(AndroidJUnit4::class)
class BoundedPcmTracePlatformTest {
    @Test fun boundedRingOnArtKeepsUnreadRecordsAndReportsEachLostBeat() {
        val trace = BoundedPcmTrace(2)
        trace.record(PcmTraceKind.BEAT, 10)
        trace.record(PcmTraceKind.BEAT, 20)
        trace.record(PcmTraceKind.BEAT, 30)
        assertEquals(10L, trace.poll()!!.frame)
        trace.record(PcmTraceKind.BEAT, 40)
        assertEquals(20L, trace.poll()!!.frame)
        val last = trace.poll()!!
        assertEquals(40L, last.frame)
        assertEquals(3L, last.sequence)
        assertNull(trace.poll())
        trace.close()
        val health = trace.health()!!
        assertEquals(4L, health.attempted)
        assertEquals(3L, health.accepted)
        assertEquals(1L, health.droppedByKind.getValue(PcmTraceKind.BEAT))
        assertTrue(health.closed)
        assertFalse(health.faulted)
    }
}
