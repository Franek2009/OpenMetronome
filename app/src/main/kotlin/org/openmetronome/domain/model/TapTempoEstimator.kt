package org.openmetronome.domain.model

import kotlin.math.roundToInt

/** Input estimation only; this clock never schedules audio. Own one instance per UI model. */
class TapTempoEstimator {
    private var lastTap: Long? = null
    private val intervals = ArrayDeque<Long>()

    fun reset() { lastTap = null; intervals.clear() }

    fun tap(nowNanos: Long): Int? {
        val previous = lastTap
        if (previous == null) { lastTap = nowNanos; return null }
        if (nowNanos <= previous) return null
        val elapsed = nowNanos - previous
        if (elapsed < 100_000_000L) return null // Ignore bounce without moving the anchor.
        lastTap = nowNanos
        if (elapsed !in 200_000_000L..3_000_000_000L) {
            intervals.clear()
            return null
        }
        intervals.addLast(elapsed)
        if (intervals.size > 4) intervals.removeFirst()
        val sorted = intervals.sorted()
        val middle = sorted.size / 2
        val median = if (sorted.size % 2 == 1) sorted[middle].toDouble()
            else (sorted[middle - 1] + sorted[middle]) / 2.0
        return (60_000_000_000.0 / median).roundToInt().coerceIn(Tempo.MIN_BPM, Tempo.MAX_BPM)
    }
}
