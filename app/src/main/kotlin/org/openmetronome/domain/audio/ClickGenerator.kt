package org.openmetronome.domain.audio

import kotlin.math.*
import org.openmetronome.domain.model.ClickSound

/** Bank construction only, never called from the sample loop. No shared/random training state. */
object ClickGenerator {
    const val PEAK_SAMPLE: Int = 29162 // 0.89 full scale, rounded down (~1 dB headroom).

    fun generate(sampleRate: Int, accented: Boolean, sound: ClickSound = ClickSound.CLASSIC): ShortArray {
        require(sampleRate >= 8000)
        val milliseconds = when (sound) {
            ClickSound.CLASSIC, ClickSound.WOOD -> 10
            ClickSound.SHORT -> 4
            ClickSound.WOODBLOCK, ClickSound.PULSE -> 20
            ClickSound.RIMSHOT -> 12
            ClickSound.COWBELL -> 35
            ClickSound.CLAP -> 30
            else -> return ExperimentalClickGenerator.generate(sampleRate, accented, sound)
        }
        val length = (sampleRate.toLong() * milliseconds / 1000).toInt()
        val noise = ClickNoise(sampleRate, accented)
        val edges = DoubleArray(length) { index ->
            min(1.0, index / (sampleRate * 0.0005)) *
                min(1.0, (length - 1 - index) / (sampleRate * 0.001))
        }
        val raw = DoubleArray(length) { index ->
            val time = index.toDouble() / sampleRate
            fun tone(hz: Double): Double =
                if (hz < sampleRate * 0.45) sin(2 * PI * hz * time) else 0.0
            val wave = when (sound) {
                // Preserve their pitch, envelope, duration and onset, but no longer their quiet gain.
                ClickSound.CLASSIC -> tone(if (accented) 1800.0 else 1200.0) * exp(-6.0 * index / length)
                ClickSound.WOOD -> {
                    val hz = if (accented) 1200.0 else 800.0
                    (0.75 * tone(hz) + 0.25 * tone(2 * hz)) * exp(-6.0 * index / length)
                }
                ClickSound.SHORT -> tone(if (accented) 3200.0 else 2400.0) * exp(-6.0 * index / length)
                ClickSound.WOODBLOCK -> {
                    val hz = if (accented) 1400.0 else 950.0
                    (tone(hz) + 0.45 * tone(2.63 * hz)) * exp(-time / 0.004) +
                        0.3 * noise.next() * exp(-time / 0.001)
                }
                ClickSound.RIMSHOT -> {
                    val pitch = if (accented) 1.3 else 1.0
                    (0.35 * tone(210 * pitch) + 0.7 * tone(780 * pitch) + tone(2300 * pitch)) *
                        exp(-time / 0.0025) + 0.9 * noise.next() * exp(-time / 0.0015)
                }
                ClickSound.COWBELL -> {
                    val pitch = if (accented) 1.25 else 1.0
                    (tone(540 * pitch) + 0.8 * tone(845 * pitch) + 0.3 * tone(1517 * pitch)) *
                        exp(-time / 0.012)
                }
                ClickSound.CLAP -> {
                    fun burst(start: Double) = if (time >= start) exp(-(time - start) / 0.0035) else 0.0
                    noise.next() * (burst(0.0) + 0.8 * burst(0.007) + 0.65 * burst(0.014))
                }
                ClickSound.PULSE -> {
                    val hz = if (accented) 1500.0 else 1000.0
                    // Truncated additive pulse: do not alias square-wave harmonics into the passband.
                    (tone(hz) + tone(3 * hz) / 3 + tone(5 * hz) / 5 + tone(7 * hz) / 7) *
                        exp(-time / 0.007)
                }
            }
            edges[index] * wave
        }
        when (sound) {
            ClickSound.CLASSIC, ClickSound.WOOD, ClickSound.SHORT -> Unit
            else -> {
                // Remove finite-click DC without introducing nonzero first/last samples.
                val offset = raw.sum() / edges.sum()
                for (index in raw.indices) raw[index] -= offset * edges[index]
            }
        }
        val peak = raw.maxOf { abs(it) }
        check(peak.isFinite() && peak > 0.0)
        // Normalize BEFORE converting to 16-bit PCM. No overflowing Short casts or hard-clipped boost.
        return ShortArray(length) { (raw[it] / peak * PEAK_SAMPLE).roundToInt().toShort() }
    }

    /** Local seeded xorshift plus a band-pass colour, independent of SilencePlanner's Random. */
    private class ClickNoise(sampleRate: Int, accented: Boolean) {
        private var state = if (accented) 0x61C88647 else 0x13579BDF
        private var high = 0.0
        private var low = 0.0
        private val highStep = 1 - exp(-2 * PI * min(if (accented) 3800.0 else 3000.0, sampleRate * 0.4) / sampleRate)
        private val lowStep = 1 - exp(-2 * PI * (if (accented) 900.0 else 600.0) / sampleRate)
        fun next(): Double {
            state = state xor (state shl 13)
            state = state xor (state ushr 17)
            state = state xor (state shl 5)
            val white = (state ushr 1).toDouble() / Int.MAX_VALUE * 2 - 1
            high += highStep * (white - high)
            low += lowStep * (white - low)
            return high - low
        }
    }
}
