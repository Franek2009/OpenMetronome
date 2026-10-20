package org.openmetronome.domain.audio

import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin
import org.openmetronome.domain.model.ClickSound

/** Synthesized PCM with bounded amplitude; CLASSIC preserves the original samples. */
object ClickGenerator {
    fun generate(sampleRate: Int, accented: Boolean, sound: ClickSound = ClickSound.CLASSIC): ShortArray {
        require(sampleRate >= 8000)
        val length = if (sound == ClickSound.SHORT) sampleRate * 4 / 1000 else sampleRate / 100
        val frequency = when (sound) {
            ClickSound.CLASSIC -> if (accented) 1800.0 else 1200.0
            ClickSound.WOOD -> if (accented) 1200.0 else 800.0
            ClickSound.SHORT -> if (accented) 3200.0 else 2400.0
        }
        return ShortArray(length) { index ->
            val attack = min(1.0, index / (sampleRate * 0.0005))
            val release = min(1.0, (length - 1 - index) / (sampleRate * 0.001))
            val envelope = attack * release * exp(-6.0 * index / length)
            val phase = 2 * PI * frequency * index / sampleRate
            val wave = if (sound == ClickSound.WOOD) 0.75 * sin(phase) + 0.25 * sin(2 * phase) else sin(phase)
            (Short.MAX_VALUE * 0.4 * envelope * wave)
                .roundToInt().toShort()
        }
    }
}
