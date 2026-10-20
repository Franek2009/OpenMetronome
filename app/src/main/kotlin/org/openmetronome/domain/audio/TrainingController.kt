package org.openmetronome.domain.audio

import org.openmetronome.domain.model.*

/** Evaluated at bar boundaries on the sample timeline, never by a wall-clock coroutine. */
class TrainingController(private val sampleRate: Int) {
    private var program: List<Any>? = null
    private var startFrame = 0L
    var startBar = 0L
        private set

    fun configAtBar(frame: Long, bar: Long, requested: MetronomeConfig): MetronomeConfig {
        val key = listOf(requested.tempo, requested.timeSignature, requested.silence, requested.progression, requested.ramp)
        if (program != key) {
            program = key
            startFrame = frame
            startBar = bar
        }
        val progression = requested.progression
        val probability = if (progression.enabled) {
            val steps = (frame - startFrame) / (sampleRate.toLong() * progression.intervalSeconds)
            (requested.silence.probability + steps * progression.increment).coerceAtMost(100).toInt()
        } else requested.silence.probability
        val ramp = requested.ramp
        val bpm = if (ramp.enabled) {
            val distance = ((bar - startBar) / ramp.barsPerStep * ramp.step)
                .coerceAtMost(kotlin.math.abs(ramp.targetBpm - ramp.startBpm).toLong()).toInt()
            ramp.startBpm + if (ramp.targetBpm >= ramp.startBpm) distance else -distance
        } else requested.tempo.bpm
        return requested.copy(tempo = Tempo(bpm), silence = requested.silence.copy(probability = probability))
    }
}
