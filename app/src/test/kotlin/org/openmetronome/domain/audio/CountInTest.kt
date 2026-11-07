package org.openmetronome.domain.audio

import org.junit.Assert.*
import org.junit.Test
import org.openmetronome.domain.model.*

class CountInTest {
    @Test fun prefixKeepsContinuousFramesAndStartsTrainingAtFirstPracticeBar() {
        val config = MetronomeConfig(tempo = Tempo(120), countInBars = 1,
            ramp = TempoRamp(true, 60, 120, 10, 1), silence = SilenceConfig(probability = 100))
        val events = mutableListOf<Pair<BeatEvent, Boolean>>()
        val renderer = PcmRenderer(48000, config, onPulse = { beat, silent -> events.add(beat to silent) })
        val block = ShortArray(2003)
        while (renderer.renderedFrames < 192001) renderer.render(block, minOf(block.size.toLong(), 192001 - renderer.renderedFrames).toInt(), config)
        assertEquals(192000L, renderer.countInFrames)
        assertEquals(listOf(0L, 48000L, 96000L, 144000L, 192000L), events.map { it.first.frame })
        assertTrue(events.take(4).all { it.first.barIndex == -1L && !it.second })
        assertEquals(0L, events.last().first.barIndex)
        assertTrue(events.last().second)
        assertEquals(60, renderer.activeConfig.tempo.bpm)
    }

    @Test fun countInIsFrozenWhileNewConfigurationWaitsForPractice() {
        val initial = MetronomeConfig(tempo = Tempo(121), countInBars = 2, tempoPercent = TempoPercent.THREE_QUARTERS)
        val scheduler = BeatScheduler(44100, initial)
        scheduler.nextBeat()
        scheduler.updateConfig(initial.copy(tempo = Tempo(300), timeSignature = TimeSignature.TWO_FOUR, tempoChange = TempoChange.NEXT_BEAT))
        repeat(7) { scheduler.nextBeat() }
        val first = scheduler.nextBeat()
        assertEquals(0L, first.barIndex)
        assertEquals(8L * 44100 * 60 * 4 / (121 * 3), first.frame)
        assertEquals(scheduler.countInFrames, first.frame)
        assertEquals(TimeSignature.TWO_FOUR, scheduler.activeConfig.timeSignature)
        assertEquals(300, scheduler.activeConfig.tempo.bpm)
    }

    @Test fun groupsPreserveEighthPulseMeaningAndUseDistinctAccentBoundaries() {
        val config = MetronomeConfig().withTimeSignature(TimeSignature.SEVEN_EIGHT)
        assertEquals(listOf(0, 2, 4), (0..6).filter { config.beatAccentAt(it) == ClickAccent.ACCENT })
        val other = config.withAccentGroups(listOf(3, 2, 2))
        assertEquals(listOf(0, 3, 5), (0..6).filter { other.beatAccentAt(it) == ClickAccent.ACCENT })
        val scheduler = BeatScheduler(48000, other)
        repeat(14) { assertEquals(it * 24000L, scheduler.nextBeat().frame) }
        assertEquals(PulseUnit.EIGHTH, other.timeSignature.pulseUnit)
    }
}
