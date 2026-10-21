package org.openmetronome.domain.audio

import kotlin.math.*
import org.openmetronome.domain.model.ClickSound

/** Authored synthesis, not samples from another app. Runs only when constructing a PCM bank. */
internal object ExperimentalClickGenerator {
    private data class Profile(
        val milliseconds: Int,
        val holdMs: Double,
        val decayMs: Double,
        val partials: List<Pair<Double, Double>>,
        val noise: Double = 0.0,
        val noiseLowHz: Double = 800.0,
        val noiseHighHz: Double = 3600.0,
        val drive: Double = 1.0,
    )

    // Same synthesis as 06; each candidate changes a small, documented set of parameters.
    private fun fullImpulse(
        hz: Double = 1050.0, milliseconds: Int = 28, holdMs: Double = 9.0, decayMs: Double = 10.0,
        third: Double = .33, fifth: Double = .2, seventh: Double = .14, drive: Double = 1.8,
    ) = Profile(milliseconds, holdMs, decayMs,
        listOf(hz to 1.0, hz * 3 to third, hz * 5 to fifth, hz * 7 to seventh), drive = drive)

    private fun profile(sound: ClickSound): Profile = when (sound) {
        ClickSound.ELECTRO_LOW -> Profile(24, 8.0, 8.0, listOf(630.0 to 1.0))
        ClickSound.ELECTRO_MID -> Profile(24, 10.0, 7.0, listOf(1450.0 to 1.0))
        ClickSound.ELECTRO_HIGH -> Profile(18, 6.0, 5.0, listOf(2750.0 to 1.0))
        ClickSound.ELECTRO_DUAL -> Profile(28, 9.0, 9.0, listOf(1150.0 to 1.0, 1930.0 to .8))
        ClickSound.IMPULSE_SHORT -> Profile(8, 0.0, 2.5, listOf(1870.0 to 1.0, 5610.0 to .33, 9350.0 to .2))
        ClickSound.IMPULSE_FULL -> Profile(28, 9.0, 10.0, listOf(1050.0 to 1.0, 3150.0 to .33, 5250.0 to .2, 7350.0 to .14), drive = 1.8)
        ClickSound.IMPULSE_BRIGHT -> Profile(20, 5.0, 7.0, listOf(2250.0 to 1.0, 4500.0 to .45), drive = 1.4)
        ClickSound.IMPULSE_ROUGH -> Profile(32, 4.0, 8.0, listOf(1320.0 to 1.0, 3960.0 to .6), noise = .45, drive = 2.0)
        ClickSound.WOOD_DRY -> Profile(11, 0.0, 2.2, listOf(1350.0 to 1.0, 3465.0 to .45), noise = .1)
        ClickSound.CLAVES -> Profile(16, 0.0, 4.5, listOf(2100.0 to 1.0, 3280.0 to .3))
        ClickSound.WOOD_RESONANT -> Profile(34, 0.0, 12.0, listOf(730.0 to 1.0, 1870.0 to .55, 3100.0 to .2))
        ClickSound.WOOD_HARD -> Profile(9, 0.0, 2.5, listOf(3100.0 to 1.0, 3700.0 to .6), noise = .25)
        ClickSound.METAL_BELL -> Profile(38, 4.0, 18.0, listOf(880.0 to 1.0, 1397.0 to .8, 2213.0 to .6))
        ClickSound.METAL_ANVIL -> Profile(30, 0.0, 9.0, listOf(1250.0 to 1.0, 2017.0 to .9, 3370.0 to .7), noise = .15)
        ClickSound.METAL_PING -> Profile(22, 4.0, 6.0, listOf(3450.0 to 1.0, 1100.0 to .15))
        ClickSound.METAL_DRY -> Profile(12, 0.0, 3.0, listOf(2450.0 to 1.0, 3990.0 to .8), noise = .15)
        ClickSound.NOISE_CRACK -> Profile(8, 0.0, 1.8, listOf(1900.0 to .2), noise = 1.0, noiseLowHz = 1200.0)
        ClickSound.NOISE_BRUSH -> Profile(30, 8.0, 12.0, emptyList(), noise = 1.0, noiseLowHz = 350.0, noiseHighHz = 2200.0, drive = 1.5)
        ClickSound.NOISE_RIM -> Profile(15, 0.0, 4.0, listOf(1690.0 to .7, 2810.0 to 1.0), noise = .7)
        ClickSound.NOISE_WIDE -> Profile(37, 10.0, 14.0, emptyList(), noise = 1.0, noiseLowHz = 150.0, noiseHighHz = 6500.0, drive = 2.0)
        ClickSound.FULL_LOW_750 -> fullImpulse(hz = 750.0)
        ClickSound.FULL_LOW_900 -> fullImpulse(hz = 900.0)
        ClickSound.FULL_HIGH_1200 -> fullImpulse(hz = 1200.0)
        ClickSound.FULL_HIGH_1400 -> fullImpulse(hz = 1400.0)
        ClickSound.FULL_HIGH_1650 -> fullImpulse(hz = 1650.0)
        ClickSound.FULL_SHORT -> fullImpulse(milliseconds = 16, holdMs = 4.0, decayMs = 5.0)
        ClickSound.FULL_TIGHT -> fullImpulse(milliseconds = 22, holdMs = 6.0, decayMs = 7.0)
        ClickSound.FULL_LONG -> fullImpulse(milliseconds = 34, holdMs = 13.0, decayMs = 11.0)
        ClickSound.FULL_HELD -> fullImpulse(milliseconds = 38, holdMs = 20.0)
        ClickSound.FULL_SOFT -> fullImpulse(third = .18, fifth = .08, seventh = .04, drive = 1.3)
        ClickSound.FULL_BRIGHT -> fullImpulse(holdMs = 10.0, third = .5, fifth = .32, seventh = .22)
        ClickSound.FULL_ROUND -> fullImpulse(third = .2, fifth = .1, seventh = .06, drive = .9)
        ClickSound.FULL_DENSE -> fullImpulse(drive = 2.8)
        ClickSound.FULL_LOW_DENSE -> fullImpulse(hz = 850.0, milliseconds = 34, holdMs = 13.0, drive = 2.5)
        ClickSound.FULL_HIGH_TIGHT -> fullImpulse(hz = 1350.0, milliseconds = 20, holdMs = 5.0, decayMs = 6.0, drive = 2.2)
        else -> error("Not an experimental sound: $sound")
    }

    fun generate(sampleRate: Int, accented: Boolean, sound: ClickSound): ShortArray {
        val p = profile(sound)
        val length = (sampleRate.toLong() * p.milliseconds / 1000).toInt()
        val pitch = if (accented) 1.22 else 1.0
        val partials = p.partials.filter { (hz, _) -> hz * pitch < sampleRate * .45 }
        val edges = DoubleArray(length) { i ->
            min(1.0, i / (sampleRate * .00025)) * min(1.0, (length - 1 - i) / (sampleRate * .0015))
        }
        // Private deterministic noise state: never touches the training/muting RNG.
        var seed = 0x13579BDF xor (sound.auditionNumber!! * 7919) xor (if (accented) 0x61C88647 else 0)
        var high = 0.0
        var low = 0.0
        val highStep = 1 - exp(-2 * PI * min(p.noiseHighHz * pitch, sampleRate * .4) / sampleRate)
        val lowStep = 1 - exp(-2 * PI * min(p.noiseLowHz * pitch, sampleRate * .2) / sampleRate)
        val raw = DoubleArray(length) { i ->
            seed = seed xor (seed shl 13)
            seed = seed xor (seed ushr 17)
            seed = seed xor (seed shl 5)
            val white = (seed ushr 1).toDouble() / Int.MAX_VALUE * 2 - 1
            high += highStep * (white - high)
            low += lowStep * (white - low)
            val t = i.toDouble() / sampleRate
            val tonal = partials.sumOf { (hz, weight) -> weight * sin(2 * PI * hz * pitch * t) }
            // A held body raises energy without raising the peak or overflowing PCM16.
            val envelope = exp(-max(0.0, t * 1000 - p.holdMs) / p.decayMs)
            edges[i] * envelope * tanh((tonal + p.noise * (high - low)) * p.drive)
        }
        val offset = raw.sum() / edges.sum()
        for (i in raw.indices) raw[i] -= offset * edges[i]
        val peak = raw.maxOf { abs(it) }
        check(peak.isFinite() && peak > 0.0)
        return ShortArray(length) { (raw[it] / peak * ClickGenerator.PEAK_SAMPLE).roundToInt().toShort() }
    }
}
