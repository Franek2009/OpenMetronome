package org.openmetronome.domain.audio

import java.security.MessageDigest
import kotlin.math.*
import org.junit.Assert.*
import org.junit.Test
import org.openmetronome.domain.model.*

class ExperimentalClickTest {
    @Test fun `twenty stable audition numbers do not change default sound or original IDs`() {
        assertEquals((1..20).toList(), ClickSound.firstAudition.map { it.auditionNumber })
        assertEquals((21..35).toList(), ClickSound.fullImpulseVariants.map { it.auditionNumber })
        assertEquals((1..35).toList(), ClickSound.experimental.map { it.auditionNumber })
        assertEquals(listOf("CLASSIC", "WOOD", "SHORT", "WOODBLOCK", "RIMSHOT", "COWBELL", "CLAP", "PULSE"),
            ClickSound.standard.map { it.name })
        assertEquals(ClickSound.IMPULSE_FULL, SoundConfig().clickSound)
        assertEquals(listOf(6, 21, 23, 25, 26, 29), ClickSound.visible.map { it.auditionNumber })
        assertEquals(43, ClickSound.entries.size)
    }

    @Test fun `chosen reference 06 stays byte identical to v010 at both export rates`() {
        val expected = listOf(
            "c30f652d382961bfb6228479ee13a0d0dd5d81fb9958f51182fb3cf41a2d9225",
            "2febe891b632354e5532476ac9f25e8b2ecbdcc3aa899d22a1a048e3680814a6",
            "1bafea338eff239eff731e279b60e611559dd4af5a53bad14b0312b908fe9ca5",
            "b64c0efa48f46ea11c0a21ac0c2fd2d3e020fd01a91a50a17b6a0e072a53e83a",
        )
        var index = 0
        for (rate in listOf(44100, 48000)) for (accent in listOf(false, true)) {
            val pcm = ClickGenerator.generate(rate, accent, ClickSound.IMPULSE_FULL)
            val bytes = ByteArray(pcm.size * 2) { i -> (pcm[i / 2].toInt() shr (8 * (i % 2))).toByte() }
            val hash = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
            assertEquals(expected[index++], hash)
        }
    }

    @Test fun `all fifteen relatives differ from reference and each other beyond rounding noise`() {
        val sounds = listOf(ClickSound.IMPULSE_FULL) + ClickSound.fullImpulseVariants
        assertEquals(16, sounds.size)
        for (rate in listOf(44100, 48000)) for (accent in listOf(false, true)) {
            val bank = sounds.map { ClickGenerator.generate(rate, accent, it) }
            for (i in bank.indices) for (j in 0 until i) {
                val frames = max(bank[i].size, bank[j].size)
                val distance = (0 until frames).sumOf { k ->
                    ((bank[i].getOrElse(k) { 0 }.toDouble() - bank[j].getOrElse(k) { 0 }) / ClickGenerator.PEAK_SAMPLE).pow(2)
                } / frames
                assertTrue("${sounds[i]} vs ${sounds[j]} $rate $accent: $distance", distance > 1e-6)
            }
        }
    }

    @Test fun `original eight normal and accented waveforms remain byte identical to v09 at 48k`() {
        // SHA-256 of little-endian PCM from the validated v09 offline bank, not competitor assets.
        val digests = listOf(
            "84f2370e8d8aeab2de71889e6f09dd0504e5cd580340cb4ccbf23671f9dc955c",
            "9ec74c0b5cf3b79f3011e2e8369798be990c57f12c6f2402018714815a7efd54",
            "3e451d8b565c921dfcf9d230c14ee065dc61caa1946e0a35f9cd63fd0f541949",
            "6f18e170eee1169e553665f58b87bc8d6e185068c8cf8c19218dcdb5dfb06c88",
            "d309b66d806b55b33367ce552b72ee19ba2142496b4956704803c50d503df2f4",
            "0d64f8eb28c1ef7ac3736f999c37921dab2de893a00634c283dee244ddf7399a",
            "9e6cb1393153c1b1dad8c2245d734f0a6bf2444e75a53a135064ca2d66684825",
            "abb915750f98319e6d203d6ca24abf54ce2bc661704fb24b7a34bd92af130bdb",
            "91af0ce4556ac0d8fee5d7e2fb2734c5998b69fa5e552feb1685b55d95096936",
            "9d7eb9c998fe38dd9695a60f350f9d7533339805e867ef1e21ff83c3b97f89c5",
            "e3fc78772bf5aaa85c9f0b0dc7228e5782b47a4d8afd72648b32af5a17097d56",
            "bcf11c15247319157001dec6d9519fc8ae4e12d39bdd6f4e20bce0238f111375",
            "f0c6327bfdaf64d01b0dfb36fc7997a4a851e44f9dab3f114011f9d8eec49e9f",
            "6cdfef6e72ac9cc8ebcd533955bc17439693bbb346be949811190e4320ab709f",
            "ef1d0d6cdab73e8d9f7c5a04ce10f907950fda71ecf2b26df692d2d35bbb65bf",
            "ab52f4c298d5eb5a035896a772cafb08189580ecc48b5fe0cc6a7d94fc836ebc",
        )
        var index = 0
        for (sound in ClickSound.standard) for (accent in listOf(false, true)) {
            val pcm = ClickGenerator.generate(48000, accent, sound)
            val bytes = ByteArray(pcm.size * 2) { i -> (pcm[i / 2].toInt() shr (8 * (i % 2))).toByte() }
            val actual = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
            assertEquals("$sound accent=$accent", digests[index++], actual)
        }
    }

    @Test fun `held bodies deliver more energy than classic without higher peaks`() {
        for (rate in listOf(44100, 48000)) for (accent in listOf(false, true)) {
            fun energy(sound: ClickSound) = ClickGenerator.generate(rate, accent, sound).sumOf { it.toDouble().pow(2) }
            val reference = energy(ClickSound.CLASSIC)
            for (sound in listOf(ClickSound.ELECTRO_LOW, ClickSound.ELECTRO_MID, ClickSound.IMPULSE_FULL)) {
                assertTrue("$sound $rate $accent", energy(sound) / reference >= 4.0)
            }
        }
    }

    @Test fun `all new voices apply at bar boundary with unchanged pulse times`() {
        for (sound in ClickSound.experimental) {
            val original = MetronomeConfig(tempo = Tempo(300))
            val changed = original.copy(sound = SoundConfig(100, sound))
            val pulses = mutableListOf<Long>()
            val renderer = PcmRenderer(48000, original, onPulse = { event, _ -> pulses += event.frame })
            renderer.render(ShortArray(1), requested = original)
            renderer.render(ShortArray(38399), requested = changed)
            assertEquals(original.sound.clickSound, renderer.activeConfig.sound.clickSound)
            val firstClick = ClickGenerator.generate(48000, true, sound)
            val buffer = ShortArray(firstClick.size)
            renderer.render(buffer, requested = changed)
            assertEquals(sound, renderer.activeConfig.sound.clickSound)
            assertArrayEquals(firstClick, buffer)
            assertEquals(listOf(0L, 9600L, 19200L, 28800L, 38400L), pulses)
        }
    }
}
