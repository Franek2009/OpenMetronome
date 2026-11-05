package org.openmetronome.diagnostics

import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlin.random.Random
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.openmetronome.domain.audio.*
import org.openmetronome.domain.model.*

/** ART verification only: PCM stays in arrays, no AudioTrack, focus, Activity or vibration. */
@RunWith(AndroidJUnit4::class)
class PcmTracePlatformTest {
    @Test fun observedOfflinePcmAndAllSubdivisionFramesMatchOnAndroid() {
        val frames = mutableListOf<Long>()
        val times = mutableListOf<Long>()
        val trace = object : PcmTrace() {
            override fun append(sequence: Long, nanos: Long, kind: PcmTraceKind, frame: Long,
                a: Long, b: Long, c: Long, d: Long, config: MetronomeConfig?): Boolean {
                times.add(nanos)
                if (kind == PcmTraceKind.BEAT) frames.add(frame)
                return true
            }
        }
        val config = MetronomeConfig(tempo = Tempo(120), rhythm = RhythmConfig(Subdivision.TRIPLETS))
        val reference = ShortArray(24000)
        val observed = ShortArray(24000)
        PcmRenderer(48000, config, Random(17)).render(reference, requested = config)
        PcmRenderer(48000, config, Random(17), trace = trace).render(observed, requested = config)
        trace.close()
        assertArrayEquals(reference, observed)
        assertEquals(listOf(0L, 8000L, 16000L), frames)
        assertTrue(times.zipWithNext().all { (first, second) -> second >= first })
        assertEquals(5L, trace.health()!!.accepted)
        assertEquals(0L, trace.health()!!.dropped)
        assertTrue(trace.health()!!.closed)
    }

    @Test fun failedObserverOnArtDoesNotChangeOfflinePcmOrEscapeIntoRenderer() {
        var calls = 0
        val trace = object : PcmTrace() {
            override fun append(sequence: Long, nanos: Long, kind: PcmTraceKind, frame: Long,
                a: Long, b: Long, c: Long, d: Long, config: MetronomeConfig?): Boolean {
                calls++
                error("synthetic recorder failure")
            }
        }
        val config = MetronomeConfig(rhythm = RhythmConfig(Subdivision.TRIPLETS))
        val reference = ShortArray(24000)
        val observed = ShortArray(24000)
        PcmRenderer(48000, config, Random(17)).render(reference, requested = config)
        PcmRenderer(48000, config, Random(17), trace = trace).render(observed, requested = config)
        trace.close()
        assertArrayEquals(reference, observed)
        assertEquals(1, calls)
        val health = trace.health()!!
        assertTrue(health.faulted)
        assertEquals(health.attempted, health.dropped)
        assertEquals(5L, health.dropped)
    }
}
