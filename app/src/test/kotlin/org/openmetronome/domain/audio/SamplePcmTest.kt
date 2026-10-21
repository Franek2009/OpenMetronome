package org.openmetronome.domain.audio

import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.*
import org.junit.Assert.*
import org.junit.Test
import org.openmetronome.data.ConfigCodec
import org.openmetronome.domain.model.*

class SamplePcmTest {
    private val id = "16e6ff0f-f1c8-464d-bc50-806ebfedff90"
    private fun tone(rate: Int = 48000, length: Int = rate / 5, silence: Int = 0) = SamplePcm.Audio(rate,
        DoubleArray(length) { if (it < silence) 0.0 else .6 * sin(2 * PI * 1000 * (it - silence) / rate) })

    @Test fun `prepare trims leading silence normalizes edges and removes DC`() {
        val prepared = SamplePcm.prepare(tone(silence = 2400))
        assertTrue(prepared.trimmed)
        assertEquals(1920, prepared.pcm.size)
        assertEquals(0, prepared.pcm.first().toInt()); assertEquals(0, prepared.pcm.last().toInt())
        assertEquals(29162, prepared.pcm.maxOf { abs(it.toInt()) })
        assertTrue(abs(prepared.pcm.map { it.toDouble() }.average()) < 1)
        val decoded = SamplePcm.decodeWav(SamplePcm.encodeCanonical(prepared.pcm))
        assertEquals(48000, decoded.rate)
        assertArrayEquals(prepared.pcm, ShortArray(decoded.mono.size) { (decoded.mono[it] * 32768).roundToInt().toShort() })
    }

    @Test fun `silence DC nonfinite oversized and too short inputs fail`() {
        for (audio in listOf(SamplePcm.Audio(48000, DoubleArray(1000)), SamplePcm.Audio(48000, DoubleArray(1000) { .5 }),
            SamplePcm.Audio(48000, doubleArrayOf(Double.NaN)), tone(length = 4), tone(length = 480001), tone(rate = 7999))) {
            assertThrows(IllegalArgumentException::class.java) { SamplePcm.prepare(audio) }
        }
        for (bytes in listOf(ByteArray(0), ByteArray(44), ByteArray(SamplePcm.MAX_BYTES + 1))) {
            assertThrows(IllegalArgumentException::class.java) { SamplePcm.decodeWav(bytes) }
        }
    }

    @Test fun `own sample accent is higher and PCM bounded at every supported output rate`() {
        val canonical = SamplePcm.prepare(tone()).pcm
        for (rate in listOf(8000, 11025, 22050, 44100, 48000, 96000, 192000)) {
            val normal = SamplePcm.click(canonical, rate, false)
            val accent = SamplePcm.click(canonical, rate, true)
            for (pcm in listOf(normal, accent)) {
                assertEquals(0, pcm.first().toInt()); assertEquals(0, pcm.last().toInt())
                assertTrue(pcm.size <= rate * 40 / 1000)
                assertEquals(29162, pcm.maxOf { abs(it.toInt()) })
                assertTrue(abs(pcm.map { it.toDouble() }.average()) < 1)
            }
            fun frequency(pcm: ShortArray): Double = (1 until pcm.size).count { pcm[it - 1] <= 0 && pcm[it] > 0 } * rate.toDouble() / pcm.size
            assertTrue("$rate", frequency(accent) > frequency(normal) * 1.15)
        }
    }

    @Test fun `downsampling suppresses out of band content before peak normalization`() {
        // Keep an in-band tone, so normalization cannot hide an aliased high-frequency residual.
        val source = SamplePcm.Audio(48000, DoubleArray(1920) { i -> sin(2 * PI * 1000 * i / 48000) + sin(2 * PI * 10000 * i / 48000) })
        val click = SamplePcm.click(SamplePcm.prepare(source).pcm, 8000, false)
        fun power(hz: Int): Double {
            var re = 0.0; var im = 0.0
            for (i in click.indices) {
                val w = .5 - .5 * cos(2 * PI * i / click.lastIndex)
                re += click[i] * w * cos(2 * PI * hz * i / 8000)
                im += click[i] * w * sin(2 * PI * hz * i / 8000)
            }
            return re * re + im * im
        }
        assertTrue(power(2000) / power(1000) < .001)
    }

    @Test fun `custom choice round trips v1 while old configurations retain classic`() {
        val config = MetronomeConfig(sound = SoundConfig(37, ClickSound.IMPULSE_FULL, id))
        assertEquals(config, ConfigCodec.decode(ConfigCodec.encode(config)))
        assertEquals(ClickSound.CLASSIC, ConfigCodec.decode("version=1").sound.clickSound)
        assertEquals(ClickSound.IMPULSE_FULL, MetronomeConfig().sound.clickSound)
        for (bad in listOf("../outside", "not-a-uuid", "00000000-0000-0000-0000-00000000000Z")) {
            assertThrows(IllegalArgumentException::class.java) { SoundConfig(customSampleId = bad) }
        }
    }

    @Test fun `custom PCM changes on bar boundary without moving time and mutes exactly`() {
        val canonical = SamplePcm.prepare(tone()).pcm
        val original = MetronomeConfig(tempo = Tempo(300))
        val custom = original.copy(sound = SoundConfig(100, ClickSound.IMPULSE_FULL, id))
        val pulses = mutableListOf<Long>()
        val renderer = PcmRenderer(48000, original, customSamples = mapOf(id to canonical)) { event, _ -> pulses += event.frame }
        renderer.render(ShortArray(1), requested = original)
        renderer.render(ShortArray(38399), requested = custom)
        assertNull(renderer.activeConfig.sound.customSampleId)
        val expected = SamplePcm.click(canonical, 48000, true)
        val block = ShortArray(expected.size)
        renderer.render(block, requested = custom)
        assertArrayEquals(expected, block)
        assertEquals(listOf(0L, 9600L, 19200L, 28800L, 38400L), pulses)
        for (silenced in listOf(custom.copy(sound = custom.sound.copy(volumePercent = 0)), custom.copy(silence = SilenceConfig(100)))) {
            val output = ShortArray(48000)
            val quiet = PcmRenderer(48000, silenced, customSamples = mapOf(id to canonical))
            quiet.render(output, requested = silenced)
            assertTrue(output.all { it == 0.toShort() }); assertEquals(48000L, quiet.renderedFrames)
        }
        assertThrows(MissingSampleException::class.java) { PcmRenderer(48000, custom) }
    }

    @Test fun `WAV accepts integer depths float32 stereo and unknown chunks but rejects malformed frames`() {
        for (bits in listOf(8, 16, 24, 32)) for (floating in listOf(false, true)) {
            if (floating && bits != 32) continue
            val frames = 200; val channels = 2; val bytes = frames * channels * bits / 8
            val b = ByteBuffer.allocate(56 + bytes).order(ByteOrder.LITTLE_ENDIAN)
            b.put("RIFF".toByteArray()); b.putInt(b.capacity() - 8); b.put("WAVEJUNK".toByteArray()); b.putInt(4); b.putInt(0)
            b.put("fmt ".toByteArray()); b.putInt(16); b.putShort(if (floating) 3 else 1); b.putShort(2)
            b.putInt(44100); b.putInt(44100 * channels * bits / 8); b.putShort((channels * bits / 8).toShort()); b.putShort(bits.toShort())
            b.put("data".toByteArray()); b.putInt(bytes)
            repeat(frames * channels) { when { floating -> b.putFloat(.25f); bits == 8 -> b.put(160.toByte()); bits == 16 -> b.putShort(8192); bits == 24 -> { b.put(0); b.put(0); b.put(32) }; else -> b.putInt(536870912) } }
            val audio = SamplePcm.decodeWav(b.array())
            assertEquals(frames, audio.mono.size); assertEquals(44100, audio.rate)
            assertTrue(audio.mono.all { abs(it - .25) < 1e-9 })
            val bad = b.array().copyOf(); ByteBuffer.wrap(bad).order(ByteOrder.LITTLE_ENDIAN).putInt(4, Int.MAX_VALUE)
            assertThrows(IllegalArgumentException::class.java) { SamplePcm.decodeWav(bad) }
        }
    }
}
