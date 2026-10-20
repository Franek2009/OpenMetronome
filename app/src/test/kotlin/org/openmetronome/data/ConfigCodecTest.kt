package org.openmetronome.data

import org.junit.Assert.*
import org.junit.Test
import org.openmetronome.domain.model.*

class ConfigCodecTest {
    @Test fun `old payloads retain classic full volume and extended fields round trip`() {
        val old = "version=1\nbpm=87\nmeter=SIX_EIGHT\nprobability=51"
        val restored = ConfigCodec.decode(old)
        assertEquals(SoundConfig(), restored.sound)
        assertEquals(87, restored.tempo.bpm)
        for (sound in ClickSound.entries) for (percent in listOf(0, 37, 100)) {
            val config = restored.copy(sound = SoundConfig(percent, sound))
            assertEquals(config, ConfigCodec.decode(ConfigCodec.encode(config)))
        }
    }
    @Test fun `invalid sound values fail instead of silently replacing stored configuration`() {
        for (field in listOf("volumePercent=-1", "volumePercent=101", "volumePercent=NaN", "clickSound=UNKNOWN")) {
            assertThrows(IllegalArgumentException::class.java) { ConfigCodec.decode("version=1\n$field") }
        }
    }
    @Test fun `all settings survive a versioned round trip`() {
        val config = MetronomeConfig(Tempo(299), TimeSignature.SIX_EIGHT, false,
            SilenceConfig(51, true, 3, 4), false, true, SilenceProgression(true, 7, 13),
            TempoRamp(true, 290, 20, 17, 3), RhythmConfig(Subdivision.TRIPLETS, ClickAccent.entries))
        assertEquals(config, ConfigCodec.decode(ConfigCodec.encode(config)))
        assertEquals(MetronomeConfig(), ConfigCodec.decode(ConfigCodec.encode(MetronomeConfig())))
    }
    @Test fun `unknown version invalid enum boolean and ranges are rejected`() {
        for (payload in listOf("version=2", "version=1\nbpm=301", "version=1\naccent=yes", "version=1\nmeter=BAD", "version=1\naccents=MUTE,MUTE")) {
            assertThrows(IllegalArgumentException::class.java) { ConfigCodec.decode(payload) }
        }
        assertEquals(MetronomeConfig(), ConfigCodec.decode("version=1"))
    }
}
