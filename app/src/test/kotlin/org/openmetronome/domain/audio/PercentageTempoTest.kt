package org.openmetronome.domain.audio

import org.junit.Assert.*
import org.junit.Test
import org.openmetronome.data.ConfigCodec
import org.openmetronome.domain.model.*

class PercentageTempoTest {
    @Test fun allBasesAndMultipliersKeepExactFramePositions() {
        for (rate in listOf(44100, 48000)) for (base in 20..300) for (factor in TempoPercent.entries) {
            val scheduler = BeatScheduler(rate, MetronomeConfig(tempo = Tempo(base), tempoPercent = factor))
            repeat(128) { pulse ->
                assertEquals(pulse.toLong() * rate * 60 * factor.denominator / (base * factor.numerator), scheduler.nextBeat().frame)
            }
        }
    }

    @Test fun nextBeatRetainsItsFrameAndDefersMeterToBar() {
        val initial = MetronomeConfig(tempoChange = TempoChange.NEXT_BEAT)
        val scheduler = BeatScheduler(48000, initial)
        assertEquals(0L, scheduler.nextBeat().frame)
        scheduler.updateConfig(initial.copy(tempoPercent = TempoPercent.HALF, timeSignature = TimeSignature.SIX_EIGHT))
        assertEquals(24000L, scheduler.nextBeat().frame)
        assertEquals(TimeSignature.FOUR_FOUR, scheduler.activeConfig.timeSignature)
        assertEquals(72000L, scheduler.nextBeat().frame)
        assertEquals(120000L, scheduler.nextBeat().frame)
        val boundary = scheduler.nextBeat()
        assertEquals(168000L, boundary.frame)
        assertEquals(0, boundary.beatInBar)
        assertEquals(TimeSignature.SIX_EIGHT, scheduler.activeConfig.timeSignature)
    }

    @Test fun multiplierDoesNotRestartRampOrOverwriteBase() {
        val initial = MetronomeConfig(ramp = TempoRamp(true, 60, 120, 10, 1))
        val training = TrainingController(48000)
        val scheduler = BeatScheduler(48000, initial, training::configAtBar)
        repeat(4) { scheduler.nextBeat() }
        scheduler.updateConfig(initial.copy(tempoPercent = TempoPercent.QUARTER))
        scheduler.nextBeat()
        assertEquals(70, scheduler.activeConfig.tempo.bpm)
        assertEquals("17.5", scheduler.activeConfig.effectiveBpmLabel)
        assertEquals(120, scheduler.appliedConfig.tempo.bpm)
        assertEquals(0L, training.startBar)
    }

    @Test fun fiveBpmRendersContinuousSilenceBetweenExactlySpacedClicks() {
        val config = MetronomeConfig(tempo = Tempo(20), tempoPercent = TempoPercent.QUARTER)
        val pulses = mutableListOf<Long>()
        val renderer = PcmRenderer(48000, config, onPulse = { event, _ -> pulses.add(event.frame) })
        val block = ShortArray(1009)
        while (renderer.renderedFrames < 576001) renderer.render(block, minOf(block.size.toLong(), 576001 - renderer.renderedFrames).toInt(), config)
        assertEquals(listOf(0L, 576000L), pulses)
    }

    @Test fun codecDefaultsOldDataAndRoundTripsFractionalTempo() {
        val config = MetronomeConfig(tempo = Tempo(121), tempoPercent = TempoPercent.THREE_QUARTERS, tempoChange = TempoChange.NEXT_BEAT)
        assertEquals("90.75", config.effectiveBpmLabel)
        assertEquals(config, ConfigCodec.decode(ConfigCodec.encode(config)))
        val old = ConfigCodec.encode(config).lineSequence().filterNot { it.startsWith("tempoPercent=") || it.startsWith("tempoChange=") }.joinToString("\n")
        val restored = ConfigCodec.decode(old)
        assertEquals(TempoPercent.FULL, restored.tempoPercent)
        assertEquals(TempoChange.NEXT_BAR, restored.tempoChange)
    }
}
