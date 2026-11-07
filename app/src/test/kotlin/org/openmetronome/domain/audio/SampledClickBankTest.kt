package org.openmetronome.domain.audio

import java.security.MessageDigest
import kotlin.math.abs
import kotlin.random.Random
import org.junit.Assert.*
import org.junit.Test
import org.openmetronome.data.ConfigCodec
import org.openmetronome.domain.model.*

class SampledClickBankTest {
    private fun hash(pcm: ShortArray): String {
        val bytes = ByteArray(pcm.size * 2) { i -> (pcm[i / 2].toInt() shr (8 * (i % 2))).toByte() }
        return MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
    }

    @Test fun `all 43 historical banks remain bit identical at both output rates`() {
        val rows = javaClass.getResourceAsStream("/audio/pre-sampled-bank-sha256.csv")!!.bufferedReader().use { it.readLines() }
        assertEquals(172, rows.size)
        for (row in rows) {
            val (rate, id, accent, expected) = row.split(',')
            assertEquals(row, expected, hash(ClickGenerator.generate(rate.toInt(), accent.toBooleanStrict(), ClickSound.valueOf(id))))
        }
    }

    @Test fun `both roles are exactly the accepted preview PCM without second accent transposition`() {
        val expected = listOf(
            "b160c455017a7c679abe9001a958dacbaa6a7ca41776fa0ce7940bdde09547d6",
            "f4332c0e0bdb17cd1d91694859217f5645a69a7dfe14d84240dc6103074661e9",
            "3c84ee2b48baebb3fa568b50b4a2dcb3a334e6c0000f8e9d44c31b5d243808ec",
            "bc0f1e73802159b2354ced566a8d92f9931540ee83d168828ccc308118761259",
            "79beb6bf82e0b9371a551de18f447d4e5811acb150881e6e73a605d707869a23",
            "c845f201033e99ecd716e68ddb4d6485046ffc9cc92cd65f25ac2a2bf5826e5b",
        )
        var i = 0
        for (sound in ClickSound.sampled) for (accent in listOf(false, true)) {
            val pcm = ClickGenerator.generate(48000, accent, sound)
            assertEquals(expected[i++], hash(pcm))
            assertEquals(if (sound == ClickSound.SAMPLE_SHORT) 1152 else 1920, pcm.size)
            // A renderer must never receive the mutable shared source bank.
            pcm.fill(0)
            assertTrue(ClickGenerator.generate(48000, accent, sound).any { it != 0.toShort() })
        }
    }

    @Test fun `new IDs round trip through persisted configurations and do not replace old names`() {
        for (sound in ClickSound.sampled) {
            val config = MetronomeConfig(sound = SoundConfig(37, sound))
            assertEquals(config, ConfigCodec.decode(ConfigCodec.encode(config)))
        }
        assertEquals(ClickSound.WOOD, ConfigCodec.decode("version=1\nclickSound=WOOD").sound.clickSound)
        assertEquals(ClickSound.IMPULSE_FULL, SoundConfig().clickSound)
    }

    @Test fun `sample changes respect bar boundaries and retain pulse frame positions`() {
        for (sound in ClickSound.sampled) {
            val original = MetronomeConfig(tempo = Tempo(300))
            val changed = original.copy(sound = SoundConfig(100, sound))
            val frames = mutableListOf<Long>()
            val renderer = PcmRenderer(48000, original, Random(1), onPulse = { event, _ -> frames += event.frame })
            renderer.render(ShortArray(1), requested = original)
            renderer.render(ShortArray(38399), requested = changed)
            assertEquals(original.sound, renderer.activeConfig.sound)
            val expected = ClickGenerator.generate(48000, true, sound)
            val block = ShortArray(expected.size)
            renderer.render(block, requested = changed)
            assertArrayEquals(expected, block)
            assertEquals(listOf(0L, 9600L, 19200L, 28800L, 38400L), frames)
        }
    }

    @Test fun `sample pairs remain distinct and have low DC at device rates`() {
        for (rate in listOf(8000, 11025, 22050, 44100, 48000, 96000, 192000)) {
            val hashes = mutableSetOf<String>()
            for (sound in ClickSound.sampled) for (accent in listOf(false, true)) {
                val pcm = ClickGenerator.generate(rate, accent, sound)
                assertTrue(hashes.add(hash(pcm)))
                assertEquals(0, pcm.first().toInt()); assertEquals(0, pcm.last().toInt())
                assertTrue(pcm.size <= rate * 40 / 1000)
                assertTrue(abs(pcm.map { it.toDouble() }.average()) < 1)
                assertEquals(29162, pcm.maxOf { abs(it.toInt()) })
            }
        }
    }
}
