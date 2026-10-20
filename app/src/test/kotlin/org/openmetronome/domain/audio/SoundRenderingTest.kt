package org.openmetronome.domain.audio

import kotlin.math.*
import org.junit.Assert.*
import org.junit.Test
import org.openmetronome.audio.*
import org.openmetronome.domain.model.*

class SoundRenderingTest {
    @Test fun `legacy voices gain at least ten digital decibels without moving their onset`() {
        for (rate in listOf(44100, 48000)) for (accent in listOf(false, true)) {
            for (sound in listOf(ClickSound.CLASSIC, ClickSound.WOOD, ClickSound.SHORT)) {
                val original = LegacyClickFixture.generate(rate, accent, sound)
                val louder = ClickGenerator.generate(rate, accent, sound)
                assertEquals(original.size, louder.size)
                assertEquals(original.indexOfFirst { it != 0.toShort() }, louder.indexOfFirst { it != 0.toShort() })
                val gainDb = 10 * log10(louder.sumOf { it.toDouble() * it } / original.sumOf { it.toDouble() * it })
                assertTrue("$sound $rate accent=$accent gain=$gainDb", gainDb >= 10.0)
            }
        }
    }
    @Test fun `all timbres and accents are deterministic distinct bounded and end at zero`() {
        for (rate in listOf(8000, 44100, 48000, 192000)) {
            val samples = ClickSound.entries.flatMap { sound -> listOf(false, true).map { accent ->
                val pcm = ClickGenerator.generate(rate, accent, sound)
                assertArrayEquals(pcm, ClickGenerator.generate(rate, accent, sound))
                assertEquals(0, pcm.first().toInt()); assertEquals(0, pcm.last().toInt())
                assertTrue(pcm.any { it != 0.toShort() })
                assertEquals(ClickGenerator.PEAK_SAMPLE, pcm.maxOf { abs(it.toInt()) })
                assertTrue(pcm.all { abs(it.toInt()) <= ClickGenerator.PEAK_SAMPLE })
                pcm.toList()
            } }
            assertEquals(16, samples.toSet().size)
        }
    }
    @Test fun `fixed volume scales PCM exactly and zero does not stop the timeline`() {
        val reference = ShortArray(48000)
        val config = MetronomeConfig(tempo = Tempo(300))
        PcmRenderer(48000, config).render(reference, requested = config)
        for (percent in listOf(0, 1, 25, 50, 100)) {
            val changed = config.copy(sound = SoundConfig(percent))
            val pulses = mutableListOf<BeatEvent>()
            val renderer = PcmRenderer(48000, changed, onPulse = { event, _ -> pulses += event })
            val pcm = ShortArray(reference.size)
            renderer.render(pcm, requested = changed)
            assertArrayEquals(ShortArray(pcm.size) { (reference[it] * (percent / 100.0)).roundToInt().toShort() }, pcm)
            assertEquals(48000L, renderer.renderedFrames)
            assertEquals(listOf(0L, 9600L, 19200L, 28800L, 38400L), pulses.map { it.frame })
        }
    }
    @Test fun `gain changes over five milliseconds and interrupted ramps start at current gain`() {
        val gain = VolumeEnvelope(48000, 100)
        gain.target(0)
        repeat(119) { gain.nextGain() }
        assertEquals(0.5, gain.nextGain(), 1e-12)
        gain.target(100)
        repeat(239) { gain.nextGain() }
        assertEquals(1.0, gain.nextGain(), 0.0)
        gain.target(0)
        val descending = List(240) { gain.nextGain() }
        assertTrue(descending.zipWithNext().all { (a, b) -> a >= b })
        assertEquals(0.0, descending.last(), 0.0)
    }
    @Test fun `volume applies now while timbre waits for bar without cutting the current click`() {
        val initial = MetronomeConfig(tempo = Tempo(300))
        val changed = initial.copy(sound = SoundConfig(100, ClickSound.WOOD))
        val renderer = PcmRenderer(48000, initial)
        renderer.render(ShortArray(100), requested = initial)
        val tail = ShortArray(380)
        renderer.render(tail, requested = changed)
        assertArrayEquals(ClickGenerator.generate(48000, true).copyOfRange(100, 480), tail)
        renderer.render(ShortArray(38400 - 480), requested = changed)
        assertEquals(initial, renderer.appliedConfig)
        val wood = ShortArray(480)
        renderer.render(wood, requested = changed)
        assertEquals(changed, renderer.appliedConfig)
        assertArrayEquals(ClickGenerator.generate(48000, true, ClickSound.WOOD), wood)

        val volumeOnly = changed.copy(sound = changed.sound.copy(volumePercent = 20))
        renderer.render(ShortArray(1), requested = volumeOnly)
        assertEquals(volumeOnly, renderer.appliedConfig)
        assertFalse(MetronomeState(volumeOnly, AudioEngineState(PlaybackStatus.PLAYING,
            activeConfig = renderer.activeConfig, appliedConfig = renderer.appliedConfig)).hasPendingChanges)
        val pending = volumeOnly.copy(sound = volumeOnly.sound.copy(clickSound = ClickSound.SHORT))
        renderer.render(ShortArray(1), requested = pending)
        assertTrue(MetronomeState(pending, AudioEngineState(PlaybackStatus.PLAYING,
            activeConfig = renderer.activeConfig, appliedConfig = renderer.appliedConfig)).hasPendingChanges)
    }
    @Test fun `timbres and gain transitions are independent of PCM block boundaries`() {
        for (sound in ClickSound.entries) {
            fun render(blockSize: Int): ShortArray {
                val first = MetronomeConfig(tempo = Tempo(137), sound = SoundConfig(100, sound))
                val renderer = PcmRenderer(44100, first)
                val result = ShortArray(88200)
                for ((start, end, percent) in listOf(Triple(0, 123, 100), Triple(123, 22050, 0), Triple(22050, 88200, 73))) {
                    var offset = start
                    val requested = first.copy(sound = first.sound.copy(volumePercent = percent))
                    while (offset < end) {
                        val block = ShortArray(minOf(blockSize, end - offset))
                        renderer.render(block, requested = requested)
                        block.copyInto(result, offset); offset += block.size
                    }
                }
                return result
            }
            assertArrayEquals(render(1), render(997))
        }
    }
    @Test fun `sound edits do not restart training and sound configuration validates ranges`() {
        assertThrows(IllegalArgumentException::class.java) { SoundConfig(-1) }
        assertThrows(IllegalArgumentException::class.java) { SoundConfig(101) }
        val training = TrainingController(48000)
        val config = MetronomeConfig(progression = SilenceProgression(true, 10, 1), ramp = TempoRamp(true, 60, 120, 5, 1))
        training.configAtBar(0, 0, config)
        val changed = config.copy(sound = SoundConfig(20, ClickSound.SHORT))
        val active = training.configAtBar(144000, 3, changed)
        assertEquals(0L, training.startBar)
        assertEquals(30, active.silence.probability)
        assertEquals(75, active.tempo.bpm)
    }
}
