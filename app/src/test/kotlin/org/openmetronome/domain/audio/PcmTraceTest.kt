package org.openmetronome.domain.audio

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.Assert.*
import org.junit.Test
import org.openmetronome.domain.model.MetronomeConfig

class PcmTraceTest {
    @Test fun recorderFailureDisablesCaptureAndCountsEverySubsequentLostRecord() {
        var calls = 0
        val trace = object : PcmTrace() {
            override fun append(sequence: Long, nanos: Long, kind: PcmTraceKind, frame: Long,
                a: Long, b: Long, c: Long, d: Long, config: MetronomeConfig?): Boolean {
                calls++
                error("broken observer")
            }
        }
        repeat(10) { trace.record(PcmTraceKind.BEAT, it.toLong()) }
        trace.close()
        assertEquals(1, calls)
        val health = trace.health()!!
        assertTrue(health.faulted)
        assertTrue(health.closed)
        assertEquals(10L, health.attempted)
        assertEquals(0L, health.accepted)
        assertEquals(10L, health.dropped)
    }

    @Test fun clockFailureCannotEscapeIntoRendering() {
        var appended = false
        val trace = object : PcmTrace({ error("clock unavailable") }) {
            override fun append(sequence: Long, nanos: Long, kind: PcmTraceKind, frame: Long,
                a: Long, b: Long, c: Long, d: Long, config: MetronomeConfig?): Boolean {
                appended = true
                return true
            }
        }
        trace.record(PcmTraceKind.SESSION_BEGIN)
        assertFalse(appended)
        assertTrue(trace.health()!!.faulted)
        assertEquals(1L, trace.health()!!.dropped)
    }

    @Test fun inFlightHealthIsReportedAsUnavailableRatherThanAnInconsistentTotal() {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val trace = object : PcmTrace() {
            override fun append(sequence: Long, nanos: Long, kind: PcmTraceKind, frame: Long,
                a: Long, b: Long, c: Long, d: Long, config: MetronomeConfig?): Boolean {
                entered.countDown()
                check(release.await(5, TimeUnit.SECONDS))
                return true
            }
        }
        val thread = Thread { trace.record(PcmTraceKind.BEAT) }
        try {
            thread.start()
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            assertNull(trace.health())
        } finally { release.countDown(); thread.join(5000) }
        assertEquals(1L, trace.health()!!.accepted)
    }

    @Test fun closingDoesNotFlushOrAcceptLateRecords() {
        val trace = CollectingPcmTrace()
        trace.record(PcmTraceKind.SESSION_BEGIN)
        trace.close()
        trace.close()
        trace.record(PcmTraceKind.BEAT)
        val health = trace.health()!!
        assertEquals(1, trace.entries.size)
        assertEquals(2L, health.attempted)
        assertEquals(1L, health.dropped)
        assertTrue(health.closed)
    }

    @Test fun eventCodesAreStableAndDistinctForFutureExportReaders() {
        assertEquals((1..20).toList(), PcmTraceKind.entries.map { it.code })
        assertEquals(6, PcmTraceKind.BEAT.code)
        assertEquals(9, PcmTraceKind.WRITE_RESULT.code)
        assertEquals(16, PcmTraceKind.SESSION_END.code)
    }
}
