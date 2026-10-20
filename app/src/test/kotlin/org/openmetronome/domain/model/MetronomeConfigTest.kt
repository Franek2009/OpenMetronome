package org.openmetronome.domain.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class MetronomeConfigTest {
    @Test
    fun `defaults are 120 BPM four four and accented first beat`() {
        val config = MetronomeConfig()

        assertEquals(Tempo(120), config.tempo)
        assertEquals(TimeSignature.FOUR_FOUR, config.timeSignature)
        assertTrue(config.accentFirstBeat)
    }

    @Test
    fun `tempo accepts both boundaries`() {
        assertEquals(20, Tempo(20).bpm)
        assertEquals(300, Tempo(300).bpm)
    }

    @Test
    fun `tempo rejects values outside supported range`() {
        listOf(Int.MIN_VALUE, -1, 0, 19, 301, Int.MAX_VALUE).forEach { bpm ->
            assertThrows(IllegalArgumentException::class.java) { Tempo(bpm) }
        }
    }

    @Test
    fun `supported meters retain written numerator and denominator`() {
        assertEquals(
            listOf(2 to 4, 3 to 4, 4 to 4, 6 to 8),
            TimeSignature.entries.map { it.numerator to it.denominator },
        )
    }

    @Test
    fun `changing a configuration preserves the original value`() {
        val original = MetronomeConfig()
        val changed = original.copy(
            tempo = Tempo(80),
            timeSignature = TimeSignature.SIX_EIGHT,
            accentFirstBeat = false,
        )

        assertEquals(MetronomeConfig(), original)
        assertEquals(Tempo(80), changed.tempo)
        assertEquals(TimeSignature.SIX_EIGHT, changed.timeSignature)
        assertEquals(false, changed.accentFirstBeat)
    }
}
