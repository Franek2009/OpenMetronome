package org.openmetronome.domain.audio

import kotlin.math.*
import org.openmetronome.domain.model.ClickSound

/** Frozen 0.8 formula. Test-only: never a second quiet mode in the app. */
internal object LegacyClickFixture {
    fun generate(rate: Int, accented: Boolean, sound: ClickSound): ShortArray {
        require(sound in listOf(ClickSound.CLASSIC, ClickSound.WOOD, ClickSound.SHORT))
        val length = if (sound == ClickSound.SHORT) rate * 4 / 1000 else rate / 100
        val hz = when (sound) {
            ClickSound.CLASSIC -> if (accented) 1800.0 else 1200.0
            ClickSound.WOOD -> if (accented) 1200.0 else 800.0
            else -> if (accented) 3200.0 else 2400.0
        }
        return ShortArray(length) { i ->
            val envelope = min(1.0, i / (rate * 0.0005)) *
                min(1.0, (length - 1 - i) / (rate * 0.001)) * exp(-6.0 * i / length)
            val phase = 2 * PI * hz * i / rate
            val wave = if (sound == ClickSound.WOOD) 0.75 * sin(phase) + 0.25 * sin(2 * phase) else sin(phase)
            (Short.MAX_VALUE * 0.4 * envelope * wave).roundToInt().toShort()
        }
    }
}
