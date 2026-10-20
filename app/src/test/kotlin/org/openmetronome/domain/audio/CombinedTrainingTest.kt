package org.openmetronome.domain.audio

import java.math.BigInteger
import kotlin.random.Random
import org.junit.Assert.*
import org.junit.Test
import org.openmetronome.domain.model.*

/** Offline only: generated PCM and exact arithmetic, never a hardware audio device. */
class CombinedTrainingTest {
    @Test fun `ramp and progression share exact bar boundaries through silent bars`() {
        for (rate in listOf(44100, 48000)) {
            for ((start, target) in listOf(300 to 137, 137 to 300)) {
                val requested = MetronomeConfig(
                    silence = SilenceConfig(0, true, 1, 1),
                    progression = SilenceProgression(true, 100, 2),
                    ramp = TempoRamp(true, start, target, 37, 1),
                    rhythm = RhythmConfig(Subdivision.TRIPLETS),
                )
                val pulses = mutableListOf<Pair<BeatEvent, Boolean>>()
                val renderer = PcmRenderer(rate, requested, Random(17)) { event, silent -> pulses += event to silent }
                val block = ShortArray(997)
                repeat(rate * 60 / block.size) { renderer.render(block, requested = requested) }
                var numerator = BigInteger.ZERO
                var denominator = BigInteger.ONE
                var probability = 0
                pulses.forEach { (event, silent) ->
                    assertEquals(numerator.divide(denominator).toLong(), event.frame)
                    if (event.beatInBar == 0) probability = if (event.frame >= rate * 2L) 100 else 0
                    assertEquals(event.barIndex % 2 == 1L || probability == 100, silent)
                    val distance = minOf(event.barIndex * 37, kotlin.math.abs(target - start).toLong()).toInt()
                    val bpm = start + if (target > start) distance else -distance
                    val divisor = BigInteger.valueOf(bpm.toLong())
                    numerator = numerator.multiply(divisor).add(denominator.multiply(BigInteger.valueOf(rate * 60L)))
                    denominator = denominator.multiply(divisor)
                    val gcd = numerator.gcd(denominator)
                    numerator /= gcd
                    denominator /= gcd
                }
                assertTrue(pulses.last().first.frame > rate * 59L)
                assertEquals(target, renderer.activeConfig.tempo.bpm)
                assertEquals(100, renderer.activeConfig.silence.probability)
                assertEquals(requested, renderer.appliedConfig)
            }
        }
    }

    @Test fun `editing program resets cycle and progression together at the next bar`() {
        val original = MetronomeConfig(tempo = Tempo(300), silence = SilenceConfig(100, true, 1, 1))
        val changed = original.copy(timeSignature = TimeSignature.SIX_EIGHT,
            silence = SilenceConfig(0, true, 1, 1), progression = SilenceProgression(true, 100, 1),
            rhythm = RhythmConfig(Subdivision.SIXTEENTHS))
        val pulses = mutableListOf<Pair<BeatEvent, Boolean>>()
        val renderer = PcmRenderer(48000, original, Random(1)) { event, silent -> pulses += event to silent }
        renderer.render(ShortArray(10000), requested = original)
        val boundary = 48000 * 60 / 300 * 4
        renderer.render(ShortArray(boundary - 10000), requested = changed)
        assertEquals(original, renderer.appliedConfig)
        renderer.render(ShortArray(1), requested = changed)
        assertEquals(BeatEvent(boundary.toLong(), 1, 0, true) to false, pulses.last())
        assertEquals(changed, renderer.appliedConfig)
        assertEquals(changed, renderer.activeConfig)
    }

    @Test fun `visual rhythm and accent edits do not restart a training program`() {
        val controller = TrainingController(48000)
        val config = MetronomeConfig(silence = SilenceConfig(10),
            progression = SilenceProgression(true, 10, 1), ramp = TempoRamp(true, 60, 120, 5, 1))
        controller.configAtBar(0, 0, config)
        val cosmetic = config.copy(visualPulse = false, accentFirstBeat = false,
            rhythm = RhythmConfig(Subdivision.DOTTED))
        val effective = controller.configAtBar(48000 * 3L, 3, cosmetic)
        assertEquals(0L, controller.startBar)
        assertEquals(40, effective.silence.probability)
        assertEquals(75, effective.tempo.bpm)
        assertEquals(cosmetic.rhythm, effective.rhythm)
    }
}
