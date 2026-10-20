package org.openmetronome.domain.audio

import java.io.DataOutputStream
import java.io.File
import kotlin.math.abs
import org.junit.Assert.*
import org.junit.Test
import org.openmetronome.domain.model.MetronomeConfig
import org.openmetronome.domain.model.Tempo
import org.openmetronome.domain.model.TimeSignature
import org.openmetronome.domain.model.RhythmConfig
import org.openmetronome.domain.model.Subdivision

class PcmRendererTest {
    @Test fun `a full minute of triplet PCM matches independently placed clicks at both sample rates`() {
        val bpm = 299
        for (rate in listOf(44100, 48000)) {
            val config = MetronomeConfig(tempo = Tempo(bpm), rhythm = RhythmConfig(Subdivision.TRIPLETS))
            val count = rate * 60
            val actual = render(rate, count, 997, config)
            val expected = ShortArray(count)
            val regular = ClickGenerator.generate(rate, false)
            val accented = ClickGenerator.generate(rate, true)
            var slot = 0L
            while (true) {
                val onset = slot * rate * 60 / (bpm * 3)
                if (onset >= count) break
                val click = if (slot % 12 == 0L) accented else regular
                click.copyInto(expected, onset.toInt(), endIndex = minOf(click.size, count - onset.toInt()))
                slot++
            }
            assertArrayEquals(expected, actual)
        }
    }

    private fun render(rate: Int, count: Int, blockSize: Int, config: MetronomeConfig): ShortArray {
        val renderer = PcmRenderer(rate, config)
        val result = ShortArray(count)
        val block = ShortArray(blockSize)
        var offset = 0
        while (offset < count) {
            val size = minOf(block.size, count - offset)
            renderer.render(block, size, config)
            block.copyInto(result, offset, 0, size)
            offset += size
        }
        return result
    }

    @Test fun `PCM is independent of block boundaries including split clicks`() {
        for (rate in listOf(44100, 48000)) {
            val config = MetronomeConfig(tempo = Tempo(137))
            val expected = render(rate, rate * 10, 1024, config)
            for (size in listOf(1, 64, 257, 997)) {
                assertArrayEquals(expected, render(rate, expected.size, size, config))
            }
        }
    }

    @Test fun `clicks start and finish at zero and remain below clipping`() {
        for (rate in listOf(44100, 48000)) {
            for (accent in listOf(false, true)) {
                val click = ClickGenerator.generate(rate, accent)
                assertEquals(rate / 100, click.size)
                assertEquals(0, click.first().toInt())
                assertEquals(0, click.last().toInt())
                assertTrue(click.any { it != 0.toShort() })
                assertTrue(click.all { abs(it.toInt()) <= Short.MAX_VALUE * 0.4 })
            }
        }
    }

    @Test fun `pending tempo meter and accent affect PCM only at next bar`() {
        val rate = 48000
        val initial = MetronomeConfig(tempo = Tempo(300))
        val requested = MetronomeConfig(Tempo(137), TimeSignature.SIX_EIGHT, false)
        val renderer = PcmRenderer(rate, initial)
        val boundary = rate * 60 / 300 * 4
        renderer.render(ShortArray(10000), requested = initial)
        renderer.render(ShortArray(boundary - 10000), requested = requested)
        assertEquals(initial, renderer.activeConfig)
        val nextBeatOffset = rate * 60 / 137
        val click = ClickGenerator.generate(rate, false)
        val pcm = ShortArray(nextBeatOffset + click.size)
        renderer.render(pcm, requested = requested)
        assertEquals(requested, renderer.activeConfig)
        assertArrayEquals(click, pcm.copyOfRange(0, click.size))
        assertTrue(pcm.copyOfRange(click.size, nextBeatOffset).all { it == 0.toShort() })
        assertArrayEquals(click, pcm.copyOfRange(nextBeatOffset, pcm.size))
    }

    @Test fun `PCM onsets match closed form and test WAV is exported`() {
        val rate = 48000
        val bpm = 137
        val pcm = render(rate, rate * 10, 257, MetronomeConfig(tempo = Tempo(bpm)))
        // A click starts with a zero sample; its first non-zero PCM sample follows one frame later.
        var beat = 0L
        while (true) {
            val start = beat * rate * 60 / bpm
            if (start + rate / 100 >= pcm.size) break
            assertEquals(0, pcm[start.toInt()].toInt())
            assertNotEquals(0, pcm[start.toInt() + 1].toInt())
            assertTrue((start.toInt() + rate / 100 until minOf(pcm.size, ((beat + 1) * rate * 60 / bpm).toInt()))
                .all { pcm[it] == 0.toShort() })
            beat++
        }
        val file = File("build/reports/audio/m1-137bpm.wav")
        checkNotNull(file.parentFile).mkdirs()
        DataOutputStream(file.outputStream().buffered()).use { out ->
            fun le(value: Int) { repeat(4) { out.writeByte(value ushr (it * 8)) } }
            fun leShort(value: Int) { out.writeByte(value); out.writeByte(value ushr 8) }
            out.writeBytes("RIFF"); le(36 + pcm.size * 2); out.writeBytes("WAVEfmt ")
            le(16); leShort(1); leShort(1); le(rate); le(rate * 2); leShort(2); leShort(16)
            out.writeBytes("data"); le(pcm.size * 2)
            pcm.forEach { leShort(it.toInt()) }
        }
        assertEquals(44L + pcm.size * 2, file.length())
    }
}
