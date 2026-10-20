package org.openmetronome.domain.audio

/** Five-millisecond linear gain transitions, advanced by samples (including silent ones). */
internal class VolumeEnvelope(sampleRate: Int, initialPercent: Int) {
    private val transitionFrames = (sampleRate / 200).coerceAtLeast(1)
    var targetPercent = initialPercent
        private set
    private var gain = initialPercent / 100.0
    private var targetGain = gain
    private var remaining = 0
    private var step = 0.0

    fun target(percent: Int) {
        require(percent in 0..100)
        if (percent == targetPercent) return
        targetPercent = percent
        targetGain = percent / 100.0
        remaining = transitionFrames
        step = (targetGain - gain) / transitionFrames
    }

    fun nextGain(): Double {
        if (remaining > 0) {
            gain += step
            remaining--
            if (remaining == 0) gain = targetGain
        }
        return gain.coerceIn(0.0, 1.0)
    }
}
