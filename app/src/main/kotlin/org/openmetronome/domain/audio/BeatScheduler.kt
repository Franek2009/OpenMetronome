package org.openmetronome.domain.audio

import java.math.BigInteger
import org.openmetronome.domain.model.MetronomeConfig
import org.openmetronome.domain.model.Tempo
import org.openmetronome.domain.model.ClickAccent

data class BeatEvent(
    val frame: Long,
    val barIndex: Long,
    val beatInBar: Int,
    val accented: Boolean,
)

/** Single-producer timeline. All positions are exact until rounded DOWN to an audio frame. */
class BeatScheduler(
    val sampleRate: Int, initialConfig: MetronomeConfig,
    private val configAtBar: (Long, Long, MetronomeConfig) -> MetronomeConfig = { _, _, config -> config },
) {
    init { require(sampleRate > 0) }

    var activeConfig: MetronomeConfig = initialConfig
        private set
    private var requestedConfig = initialConfig
    private var position = BigInteger.ZERO
    private var interval = BigInteger.ZERO
    var appliedConfig = initialConfig
        private set
    private var bar = 0L
    private var beat = 0
    var nextFrame: Long = 0
        private set

    fun updateConfig(config: MetronomeConfig) { requestedConfig = config }

    fun nextBeat(): BeatEvent {
        if (beat == 0) {
            appliedConfig = requestedConfig
            activeConfig = configAtBar(nextFrame, bar, requestedConfig)
        }
        val event = BeatEvent(nextFrame, bar, beat, activeConfig.beatAccentAt(beat) == ClickAccent.ACCENT)
        // Every permitted BPM divides this common denominator. Keeping the same units
        // across tempo changes preserves the fractional phase, without floating-point drift.
        interval = FRAME_DENOMINATOR.divide(BigInteger.valueOf(activeConfig.tempo.bpm.toLong()))
            .multiply(BigInteger.valueOf(sampleRate.toLong() * 60))
        position = position.add(interval)
        val wholeFrames = position.divide(FRAME_DENOMINATOR)
        // BigInteger.longValueExact is only available from Android API 31.
        check(wholeFrames.bitLength() <= 63) { "Audio timeline exceeded Long range" }
        nextFrame = wholeFrames.toLong()
        beat++
        if (beat == activeConfig.timeSignature.pulsesPerBar) {
            beat = 0
            bar++
        }
        return event
    }

    /** Exact location inside the last emitted pulse, retaining its unrounded phase. */
    fun subdivisionFrame(numerator: Int, denominator: Int): Long {
        require(denominator > 0 && numerator in 0 until denominator)
        val d = BigInteger.valueOf(denominator.toLong())
        val frame = position.multiply(d).subtract(interval.multiply(BigInteger.valueOf((denominator - numerator).toLong())))
            .divide(FRAME_DENOMINATOR.multiply(d))
        check(frame.signum() >= 0 && frame.bitLength() <= 63)
        return frame.toLong()
    }

    companion object {
        // ~430 bits: inexpensive at <= 5 beats/second, never evaluated per PCM sample.
        private val FRAME_DENOMINATOR = (Tempo.MIN_BPM..Tempo.MAX_BPM)
            .fold(BigInteger.ONE) { lcm, bpm ->
                val divisor = BigInteger.valueOf(bpm.toLong())
                lcm.divide(lcm.gcd(divisor)).multiply(divisor)
            }
    }
}
