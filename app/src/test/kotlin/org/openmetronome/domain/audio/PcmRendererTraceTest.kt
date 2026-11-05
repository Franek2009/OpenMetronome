package org.openmetronome.domain.audio

import kotlin.random.Random
import org.junit.Assert.*
import org.junit.Test
import org.openmetronome.domain.model.*

class PcmRendererTraceTest {
    @Test fun tracingPreservesExactPcmAndPulseDecisionsAcrossTrainingAndConfigChanges() {
        for (rate in listOf(44100, 48000)) for (subdivision in Subdivision.entries) {
            var requested = MetronomeConfig(tempo = Tempo(137),
                silence = SilenceConfig(33, true, 2, 1),
                progression = SilenceProgression(true, 10, 1),
                ramp = TempoRamp(true, 137, 211, 17, 1),
                rhythm = RhythmConfig(subdivision))
            val referencePulses = mutableListOf<Pair<BeatEvent, Boolean>>()
            val tracedPulses = mutableListOf<Pair<BeatEvent, Boolean>>()
            val trace = CollectingPcmTrace()
            val reference = PcmRenderer(rate, requested, Random(342)) { event, silent -> referencePulses += event to silent }
            val observed = PcmRenderer(rate, requested, Random(342), trace = trace) { event, silent -> tracedPulses += event to silent }
            val left = ShortArray(256)
            val right = ShortArray(256)
            repeat(rate * 6 / left.size) { block ->
                if (block == rate * 2 / left.size) requested = requested.copy(sound = requested.sound.copy(volumePercent = 37))
                if (block == rate * 3 / left.size) requested = requested.copy(timeSignature = TimeSignature.THREE_FOUR)
                reference.render(left, requested = requested)
                observed.render(right, requested = requested)
                assertArrayEquals("rate=$rate subdivision=$subdivision block=$block", left, right)
            }
            assertEquals(referencePulses, tracedPulses)
            assertEquals(reference.renderedFrames, observed.renderedFrames)
            assertEquals(0L, trace.health()!!.dropped)
        }
    }

    @Test fun everySubdivisionIncludingMutedAndAccentedOnesHasItsExactFrame() {
        val config = MetronomeConfig(tempo = Tempo(120),
            beatAccents = listOf(ClickAccent.ACCENT, ClickAccent.NORMAL, ClickAccent.MUTE, ClickAccent.NORMAL),
            rhythm = RhythmConfig(Subdivision.TRIPLETS, listOf(ClickAccent.NORMAL, ClickAccent.ACCENT, ClickAccent.MUTE)))
        val trace = CollectingPcmTrace()
        PcmRenderer(48000, config, trace = trace).render(ShortArray(96000), requested = config)
        val beats = trace.entries.filter { it.kind == PcmTraceKind.BEAT }
        assertEquals(12, beats.size)
        beats.forEachIndexed { index, row ->
            val beat = index / 3
            val subdivision = index % 3
            assertEquals(index * 8000L, row.frame)
            assertEquals(0L, row.a)
            assertEquals(beat.toLong(), row.b)
            assertEquals(subdivision.toLong(), row.c)
            val expectedFlags = (if (beat == 2) PcmTraceFlags.BEAT_MUTE else 0) or
                (if (subdivision == 2) PcmTraceFlags.SUBDIVISION_MUTE else 0) or
                (if (subdivision == 1 || beat == 0 && subdivision == 0) PcmTraceFlags.ACCENT else 0)
            assertEquals(expectedFlags.toLong(), row.d)
        }
    }

    @Test fun fullySilentTrainingStillRecordsEveryPlannedSubdivision() {
        val config = MetronomeConfig(tempo = Tempo(120), silence = SilenceConfig(100),
            rhythm = RhythmConfig(Subdivision.SIXTEENTHS))
        val trace = CollectingPcmTrace()
        val pcm = ShortArray(96000)
        PcmRenderer(48000, config, trace = trace).render(pcm, requested = config)
        assertTrue(pcm.all { it == 0.toShort() })
        val beats = trace.entries.filter { it.kind == PcmTraceKind.BEAT }
        assertEquals(16, beats.size)
        assertTrue(beats.all { it.d.toInt() and PcmTraceFlags.TRAINING_MUTE != 0 })
    }

    @Test fun programSnapshotIsNotAppliedBeforeTheActualBarBoundary() {
        val first = MetronomeConfig(tempo = Tempo(120))
        val changed = first.copy(tempo = Tempo(60), timeSignature = TimeSignature.THREE_FOUR)
        val trace = CollectingPcmTrace()
        val renderer = PcmRenderer(48000, first, trace = trace)
        renderer.render(ShortArray(1000), requested = first)
        renderer.render(ShortArray(94000), requested = changed)
        assertEquals(listOf(first), trace.entries.filter { it.kind == PcmTraceKind.CONFIG_APPLIED }.map { it.config })
        renderer.render(ShortArray(2000), requested = changed)
        val applied = trace.entries.last { it.kind == PcmTraceKind.CONFIG_APPLIED }
        assertEquals(96000L, applied.frame)
        assertEquals(changed, applied.config)
    }

    @Test fun volumeTargetHasItsBlockFrameRatherThanPretendingToWaitForABar() {
        val first = MetronomeConfig()
        val trace = CollectingPcmTrace()
        val renderer = PcmRenderer(48000, first, trace = trace)
        renderer.render(ShortArray(1000), requested = first)
        renderer.render(ShortArray(1000), requested = first.copy(sound = first.sound.copy(volumePercent = 37)))
        val target = trace.entries.single { it.kind == PcmTraceKind.VOLUME_TARGET }
        assertEquals(1000L, target.frame)
        assertEquals(100L, target.a)
        assertEquals(37L, target.b)
        assertEquals(240L, target.c)
    }

    @Test fun plannedBeatIsRetainedEvenWhenTheSelectedSampleFailsAtItsFrame() {
        val first = MetronomeConfig()
        val missing = first.copy(sound = first.sound.copy(customSampleId = "11111111-1111-1111-1111-111111111111"))
        val trace = CollectingPcmTrace()
        val renderer = PcmRenderer(48000, first, trace = trace)
        renderer.render(ShortArray(96000), requested = first)
        assertThrows(MissingSampleException::class.java) {
            renderer.render(ShortArray(16), requested = missing)
        }
        val last = trace.entries.last()
        assertEquals(PcmTraceKind.BEAT, last.kind)
        assertEquals(96000L, last.frame)
        assertEquals(1L, last.a)
        assertEquals(0L, last.b)
        assertEquals(0L, last.c)
    }
}
