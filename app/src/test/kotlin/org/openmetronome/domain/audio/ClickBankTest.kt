package org.openmetronome.domain.audio

import java.io.DataOutputStream
import java.io.File
import kotlin.math.*
import kotlin.random.Random
import org.junit.Assert.*
import org.junit.Test
import org.openmetronome.domain.model.*

class ClickBankTest {
    @Test fun `every bank has headroom short tails and low DC at supported device rates`() {
        for (rate in listOf(8000, 11025, 22050, 44100, 48000, 96000, 192000)) {
            for (sound in ClickSound.entries) for (accent in listOf(false, true)) {
                val pcm = ClickGenerator.generate(rate, accent, sound)
                assertTrue(pcm.size <= rate * 40 / 1000)
                assertEquals(0, pcm.first().toInt())
                assertEquals(0, pcm.last().toInt())
                assertEquals(29162, pcm.maxOf { abs(it.toInt()) })
                assertTrue("DC: $sound $rate", abs(pcm.map { it.toDouble() }.average()) < Short.MAX_VALUE * .02)
                assertTrue(pcm.none { it == Short.MIN_VALUE || it == Short.MAX_VALUE })
            }
        }
    }

    @Test fun `new voices differ in waveform shape not only overall gain`() {
        val rate = 48000
        val bank = ClickSound.entries.map { sound ->
            val pcm = ClickGenerator.generate(rate, false, sound)
            DoubleArray(rate * 40 / 1000) { pcm.getOrElse(it) { 0 }.toDouble() }
        }
        for (i in bank.indices) for (j in 0 until i) {
            val a = bank[i]; val b = bank[j]
            val correlation = a.indices.sumOf { a[it] * b[it] } /
                sqrt(a.sumOf { it * it } * b.sumOf { it * it })
            assertTrue("${ClickSound.entries[i]} vs ${ClickSound.entries[j]}", abs(correlation) < .95)
        }
    }

    @Test fun `new banks fit between fastest subdivisions with exact zero and full mute`() {
        for (rate in listOf(44100, 48000)) for (sound in ClickSound.entries) {
            val config = MetronomeConfig(tempo = Tempo(300), rhythm = RhythmConfig(Subdivision.SIXTEENTHS),
                sound = SoundConfig(100, sound))
            val pcm = ShortArray(rate)
            PcmRenderer(rate, config).render(pcm, requested = config)
            val slotFrames = rate / 20
            for (slot in 0 until 20) {
                val click = ClickGenerator.generate(rate, slot % 16 == 0, sound)
                assertArrayEquals(click, pcm.copyOfRange(slot * slotFrames, slot * slotFrames + click.size))
                assertTrue(pcm.copyOfRange(slot * slotFrames + click.size, (slot + 1) * slotFrames).all { it == 0.toShort() })
            }
            for (muted in listOf(config.copy(sound = SoundConfig(0, sound)), config.copy(silence = SilenceConfig(100)))) {
                val pulses = mutableListOf<Long>()
                val renderer = PcmRenderer(rate, muted) { event, _ -> pulses += event.frame }
                val silent = ShortArray(rate)
                renderer.render(silent, requested = muted)
                assertTrue(silent.all { it == 0.toShort() })
                assertEquals(5, pulses.size)
                assertEquals(rate.toLong(), renderer.renderedFrames)
            }
        }
    }

    @Test fun `synthetic noise never consumes the silence planner random stream`() {
        fun decisions(sound: ClickSound): List<Boolean> {
            val config = MetronomeConfig(tempo = Tempo(300), sound = SoundConfig(100, sound), silence = SilenceConfig(50))
            val decisions = mutableListOf<Boolean>()
            PcmRenderer(48000, config, Random(123)) { _, silent -> decisions += silent }
                .render(ShortArray(48000 * 8), requested = config)
            return decisions
        }
        val expected = decisions(ClickSound.CLASSIC)
        for (sound in ClickSound.entries) assertEquals(expected, decisions(sound))
    }

    @Test fun `export comparison banks and numerical report without playing audio`() {
        val directory = File("build/reports/audio/v09").also { it.mkdirs() }
        val report = StringBuilder("rate,sound,accent,durationMs,peak,rmsDbfs,legacyGainDb\n")
        for (rate in listOf(44100, 48000)) {
            val bank = mutableListOf<ShortArray>()
            val comparison = mutableListOf<ShortArray>()
            for (sound in ClickSound.entries) for (accent in listOf(false, true)) {
                val pcm = ClickGenerator.generate(rate, accent, sound)
                bank += pcm
                val energy = pcm.sumOf { it.toDouble() * it }
                val legacyGain = if (sound in listOf(ClickSound.CLASSIC, ClickSound.WOOD, ClickSound.SHORT)) {
                    val old = LegacyClickFixture.generate(rate, accent, sound)
                    comparison += old; comparison += pcm
                    (10 * log10(energy / old.sumOf { it.toDouble() * it })).toString()
                } else ""
                report.append("$rate,$sound,$accent,${pcm.size * 1000.0 / rate},${pcm.maxOf { abs(it.toInt()) }},${10 * log10(energy / pcm.size / Short.MAX_VALUE.toDouble().pow(2))},$legacyGain\n")
            }
            writeBank(File(directory, "bank-$rate.wav"), rate, bank)
            writeBank(File(directory, "legacy-then-new-$rate.wav"), rate, comparison)
        }
        File(directory, "levels.csv").writeText(report.toString())
    }

    private fun writeBank(file: File, rate: Int, clicks: List<ShortArray>) {
        val slot = rate * 3 / 10
        val frames = slot * clicks.size
        DataOutputStream(file.outputStream().buffered()).use { out ->
            fun le32(value: Int) { repeat(4) { out.writeByte(value ushr (8 * it)) } }
            fun le16(value: Int) { out.writeByte(value); out.writeByte(value ushr 8) }
            out.writeBytes("RIFF"); le32(36 + frames * 2); out.writeBytes("WAVEfmt ")
            le32(16); le16(1); le16(1); le32(rate); le32(rate * 2); le16(2); le16(16)
            out.writeBytes("data"); le32(frames * 2)
            for (click in clicks) repeat(slot) { le16(click.getOrElse(it) { 0 }.toInt()) }
        }
    }
}
