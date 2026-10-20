package org.openmetronome.domain.audio

import kotlin.random.Random
import org.junit.Assert.*
import org.junit.Test
import org.openmetronome.domain.model.MetronomeConfig
import org.openmetronome.domain.model.SilenceConfig

class SilencePlannerTest {
    @Test fun `probability boundaries and seeded fifty percent`() {
        for (p in listOf(0, 100)) {
            val planner = SilencePlanner(Random(42))
            repeat(1000) { assertEquals(p == 100, planner.isSilent(it.toLong(), SilenceConfig(p))) }
        }
        val first = SilencePlanner(Random(42))
        val second = SilencePlanner(Random(42))
        val results = (0L..9999).map { first.isSilent(it, SilenceConfig(50)) }
        assertEquals(results, (0L..9999).map { second.isSilent(it, SilenceConfig(50)) })
        assertTrue(results.count { it } in 4500..5500)
    }

    @Test fun `cycles and random silence compose without changing bar count`() {
        val planner = SilencePlanner(Random(7))
        val config = SilenceConfig(cycleEnabled = true)
        assertEquals(listOf(false, false, true, true, false, false, true, true),
            (0L..7).map { planner.isSilent(it, config) })
        repeat(8) { assertTrue(planner.isSilent(it.toLong(), config.copy(probability = 100))) }
    }

    @Test fun `silent PCM advances the timeline and depends on no block boundaries`() {
        fun render(blockSize: Int): ShortArray {
            val config = MetronomeConfig(silence = SilenceConfig(50, true))
            val renderer = PcmRenderer(48000, config, Random(42))
            val result = ShortArray(48000 * 10)
            var position = 0
            while (position < result.size) {
                val block = ShortArray(minOf(blockSize, result.size - position))
                renderer.render(block, requested = config)
                block.copyInto(result, position)
                position += block.size
            }
            assertEquals(result.size.toLong(), renderer.renderedFrames)
            return result
        }
        assertArrayEquals(render(1), render(997))
        val config = MetronomeConfig(silence = SilenceConfig(100))
        val renderer = PcmRenderer(48000, config)
        val samples = ShortArray(48000 * 3)
        renderer.render(samples, requested = config)
        assertTrue(samples.all { it == 0.toShort() })
        assertEquals(samples.size.toLong(), renderer.renderedFrames)
    }
}
