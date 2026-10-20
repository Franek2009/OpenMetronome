package org.openmetronome.domain.audio

import java.math.BigInteger
import org.junit.Assert.*
import org.junit.Test
import org.openmetronome.domain.model.MetronomeConfig
import org.openmetronome.domain.model.PulseUnit
import org.openmetronome.domain.model.Tempo
import org.openmetronome.domain.model.TimeSignature

class BeatSchedulerTest {
    @Test fun `positions equal the closed form at awkward and boundary tempos`() {
        for (rate in listOf(44100, 48000)) {
            for (bpm in listOf(20, 60, 120, 137, 299, 300)) {
                val scheduler = BeatScheduler(rate, MetronomeConfig(tempo = Tempo(bpm)))
                repeat(10000) { beat ->
                    assertEquals(beat.toLong() * rate * 60 / bpm, scheduler.nextBeat().frame)
                }
            }
        }
    }

    @Test fun `24 hour simulation retains precision beyond signed 32 bit frame count`() {
        for (rate in listOf(44100, 48000)) {
            for (bpm in listOf(137, 300)) {
                val scheduler = BeatScheduler(rate, MetronomeConfig(tempo = Tempo(bpm)))
                repeat(bpm * 60 * 24 + 1) { beat ->
                    assertEquals(beat.toLong() * rate * 60 / bpm, scheduler.nextBeat().frame)
                }
                assertTrue(scheduler.nextFrame > Int.MAX_VALUE)
            }
        }
    }

    @Test fun `only the first pulse of each meter is accented`() {
        for (meter in TimeSignature.entries) {
            val scheduler = BeatScheduler(48000, MetronomeConfig(timeSignature = meter))
            repeat(meter.pulsesPerBar * 3) { index ->
                val event = scheduler.nextBeat()
                assertEquals(index % meter.pulsesPerBar, event.beatInBar)
                assertEquals((index / meter.pulsesPerBar).toLong(), event.barIndex)
                assertEquals(index % meter.pulsesPerBar == 0, event.accented)
            }
        }
        assertEquals(PulseUnit.EIGHTH, TimeSignature.SIX_EIGHT.pulseUnit)
        assertEquals(6, TimeSignature.SIX_EIGHT.pulsesPerBar)
    }

    @Test fun `disabled accent uses regular clicks including the first beat`() {
        val scheduler = BeatScheduler(48000, MetronomeConfig(accentFirstBeat = false))
        repeat(12) { assertFalse(scheduler.nextBeat().accented) }
    }

    @Test fun `latest edit takes effect together on next bar and retains fractional phase`() {
        val initial = MetronomeConfig(tempo = Tempo(137))
        val scheduler = BeatScheduler(48000, initial)
        scheduler.nextBeat()
        scheduler.updateConfig(initial.copy(tempo = Tempo(60)))
        scheduler.nextBeat()
        val final = initial.copy(tempo = Tempo(299), timeSignature = TimeSignature.SIX_EIGHT, accentFirstBeat = false)
        scheduler.updateConfig(final)
        scheduler.nextBeat()
        scheduler.nextBeat()
        assertEquals(initial, scheduler.activeConfig)
        val boundary = scheduler.nextBeat()
        assertEquals(4L * 48000 * 60 / 137, boundary.frame)
        assertEquals(0, boundary.beatInBar)
        assertEquals(1L, boundary.barIndex)
        assertFalse(boundary.accented)
        assertEquals(final, scheduler.activeConfig)
        val following = scheduler.nextBeat()
        val expected = (4L * 48000 * 60 * 299 + 48000L * 60 * 137) / (137L * 299)
        assertEquals(expected, following.frame)
    }

    @Test fun `new scheduler restarts at frame zero first pulse`() {
        val config = MetronomeConfig()
        val first = BeatScheduler(48000, config)
        repeat(10) { first.nextBeat() }
        assertEquals(BeatEvent(0, 0, 0, true), BeatScheduler(48000, config).nextBeat())
    }

    @Test fun `all tempos rising and falling preserve rational phase through meter changes`() {
        val rate = 48000
        val scheduler = BeatScheduler(rate, MetronomeConfig())
        // Independent reduced fraction oracle, not the production LCM representation.
        var numerator = BigInteger.ZERO
        var denominator = BigInteger.ONE
        val tempos = (20..300).toList() + (300 downTo 20).toList()
        tempos.forEachIndexed { bar, bpm ->
            val config = MetronomeConfig(
                tempo = Tempo(bpm),
                timeSignature = TimeSignature.entries[bar % TimeSignature.entries.size],
                accentFirstBeat = bar % 2 == 0,
            )
            scheduler.updateConfig(config)
            repeat(config.timeSignature.pulsesPerBar) { beat ->
                val event = scheduler.nextBeat()
                assertEquals(numerator.divide(denominator).toLong(), event.frame)
                assertEquals(bar.toLong(), event.barIndex)
                assertEquals(beat, event.beatInBar)
                assertEquals(config, scheduler.activeConfig)
                assertEquals(beat == 0 && config.accentFirstBeat, event.accented)
                val divisor = BigInteger.valueOf(bpm.toLong())
                numerator = numerator.multiply(divisor)
                    .add(denominator.multiply(BigInteger.valueOf(rate * 60L)))
                denominator = denominator.multiply(divisor)
                val gcd = numerator.gcd(denominator)
                numerator = numerator.divide(gcd)
                denominator = denominator.divide(gcd)
            }
        }
    }
}
