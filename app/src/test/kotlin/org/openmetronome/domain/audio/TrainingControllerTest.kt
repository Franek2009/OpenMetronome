package org.openmetronome.domain.audio

import kotlin.random.Random
import org.junit.Assert.*
import org.junit.Test
import org.openmetronome.domain.model.*

class TrainingControllerTest {
    @Test fun `tempo ramps both directions clamp at target and reset on program edit`() {
        for ((start, end) in listOf(60 to 117, 117 to 60, 60 to 60)) {
            val controller = TrainingController(48000)
            val config = MetronomeConfig(ramp = TempoRamp(true, start, end, 5, 4))
            assertEquals(start, controller.configAtBar(0, 0, config).tempo.bpm)
            assertEquals(start, controller.configAtBar(100, 3, config).tempo.bpm)
            assertEquals(end, controller.configAtBar(10000, 100, config).tempo.bpm)
            assertEquals(0L, controller.startBar)
            assertEquals(70, controller.configAtBar(20000, 101, config.copy(ramp = config.ramp.copy(startBpm = 70))).tempo.bpm)
            assertEquals(101L, controller.startBar)
        }
    }
    @Test fun `progression uses audio time including silence and caps at hundred`() {
        val controller = TrainingController(48000)
        val config = MetronomeConfig(silence = SilenceConfig(10, true), progression = SilenceProgression(true))
        assertEquals(10, controller.configAtBar(0, 0, config).silence.probability)
        assertEquals(10, controller.configAtBar(48000 * 60L - 1, 10, config).silence.probability)
        assertEquals(20, controller.configAtBar(48000 * 60L, 11, config).silence.probability)
        assertEquals(100, controller.configAtBar(48000 * 6000L, 12, config).silence.probability)
    }
    @Test fun `subdivision frames retain exact phase for awkward BPM`() {
        for (rate in listOf(44100, 48000)) {
            val scheduler = BeatScheduler(rate, MetronomeConfig(tempo = Tempo(137)))
            repeat(10000) { beat ->
                scheduler.nextBeat()
                for (parts in listOf(1, 2, 3, 4)) repeat(parts) { slot ->
                    assertEquals((beat.toLong() * parts + slot) * rate * 60 / (137 * parts), scheduler.subdivisionFrame(slot, parts))
                }
            }
        }
    }
    @Test fun `all rhythm patterns and mute decisions are independent of PCM blocks`() {
        for (subdivision in Subdivision.entries) {
            val config = MetronomeConfig(tempo = Tempo(137), silence = SilenceConfig(50), rhythm = RhythmConfig(subdivision))
            fun pcm(blockSize: Int): ShortArray {
                val renderer = PcmRenderer(48000, config, Random(7))
                val output = ShortArray(144000)
                var offset = 0
                while (offset < output.size) {
                    val block = ShortArray(minOf(blockSize, output.size - offset))
                    renderer.render(block, requested = config)
                    block.copyInto(output, offset); offset += block.size
                }
                return output
            }
            assertArrayEquals(pcm(1), pcm(997))
        }
        val config = MetronomeConfig(rhythm = RhythmConfig(Subdivision.TRIPLETS, List(3) { ClickAccent.MUTE }))
        val pcm = ShortArray(48000)
        PcmRenderer(48000, config).render(pcm, requested = config)
        assertTrue(pcm.all { it == 0.toShort() })
    }
}
