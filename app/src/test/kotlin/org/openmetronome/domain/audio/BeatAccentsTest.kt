package org.openmetronome.domain.audio

import org.junit.Assert.*
import org.junit.Test
import org.openmetronome.data.ConfigCodec
import org.openmetronome.domain.model.*

class BeatAccentsTest {
    @Test fun `legacy configuration and optional codec field preserve first accent`() {
        for (first in listOf(false, true)) {
            val config = MetronomeConfig(accentFirstBeat = first)
            val old = ConfigCodec.encode(config).lineSequence().filterNot { it.startsWith("beatAccents=") }.joinToString("\n")
            assertEquals(config, ConfigCodec.decode(old))
            assertEquals(if (first) ClickAccent.ACCENT else ClickAccent.NORMAL, config.beatAccentAt(0))
            assertEquals(ClickAccent.NORMAL, config.beatAccentAt(1))
        }
    }

    @Test fun `cycling materializes legacy pattern and resizing preserves common slots`() {
        val config = MetronomeConfig().cycleBeatAccent(1).cycleBeatAccent(1)
        assertEquals(listOf(ClickAccent.ACCENT, ClickAccent.MUTE, ClickAccent.NORMAL, ClickAccent.NORMAL), config.beatAccents)
        assertEquals(config, ConfigCodec.decode(ConfigCodec.encode(config)))
        val short = config.withTimeSignature(TimeSignature.TWO_FOUR)
        val long = short.withTimeSignature(TimeSignature.SIX_EIGHT)
        assertEquals(config.beatAccents.take(2) + List(4) { ClickAccent.NORMAL }, long.beatAccents)
        assertEquals(ClickAccent.NORMAL, config.cycleBeatAccent(1).beatAccentAt(1))
        assertTrue(MetronomeConfig().withTimeSignature(TimeSignature.SIX_EIGHT).beatAccents.isEmpty())
    }

    @Test fun `invalid pattern length or accent is rejected without silently changing it`() {
        for (payload in listOf("beatAccents=NORMAL", "beatAccents=NORMAL,NORMAL,NORMAL,INVALID")) {
            assertThrows(IllegalArgumentException::class.java) { ConfigCodec.decode("version=1\n$payload") }
        }
    }

    @Test fun `muted pulse silences all subdivisions and callback even with accents`() {
        for (rate in listOf(44100, 48000)) {
            val config = MetronomeConfig(tempo = Tempo(120), beatAccents = listOf(ClickAccent.MUTE, ClickAccent.ACCENT, ClickAccent.NORMAL, ClickAccent.NORMAL),
                rhythm = RhythmConfig(Subdivision.SIXTEENTHS, List(4) { ClickAccent.ACCENT }))
            val events = mutableListOf<Boolean>()
            val renderer = PcmRenderer(rate, config, onPulse = { _, silent -> events.add(silent) })
            val pcm = ShortArray(rate)
            renderer.render(pcm, requested = config)
            assertTrue(pcm.take(rate / 2).all { it == 0.toShort() })
            assertTrue(pcm.drop(rate / 2).any { it != 0.toShort() })
            assertEquals(listOf(true, false), events)
        }
    }

    @Test fun `subdivision mute overrides pulse accent and training mute overrides both`() {
        val config = MetronomeConfig(rhythm = RhythmConfig(Subdivision.EIGHTHS, listOf(ClickAccent.MUTE, ClickAccent.ACCENT)))
        val pcm = ShortArray(24000)
        PcmRenderer(48000, config).render(pcm, requested = config)
        assertTrue(pcm.take(12000).all { it == 0.toShort() })
        val click = ClickGenerator.generate(48000, true, config.sound.clickSound)
        assertArrayEquals(click, pcm.copyOfRange(12000, 12000 + click.size))
        val silent = config.copy(silence = SilenceConfig(100))
        PcmRenderer(48000, silent).render(pcm, requested = silent)
        assertTrue(pcm.all { it == 0.toShort() })
    }

    @Test fun `pattern update waits until next bar and does not shift scheduler frames`() {
        val config = MetronomeConfig(tempo = Tempo(137))
        val changed = config.copy(beatAccents = List(4) { ClickAccent.MUTE })
        val renderer = PcmRenderer(48000, config)
        renderer.render(ShortArray(1000), requested = config)
        val boundary = 4L * 48000 * 60 / 137
        renderer.render(ShortArray(boundary.toInt() - 1000), requested = changed)
        assertEquals(config, renderer.activeConfig)
        val pcm = ShortArray(40000)
        renderer.render(pcm, requested = changed)
        assertEquals(changed, renderer.activeConfig)
        assertTrue(pcm.all { it == 0.toShort() })
        val scheduler = BeatScheduler(48000, config)
        repeat(16) { index ->
            if (index == 1) scheduler.updateConfig(changed)
            val event = scheduler.nextBeat()
            assertEquals(index.toLong() * 48000 * 60 / 137, event.frame)
            assertEquals(index == 0, event.accented)
        }
    }
}
