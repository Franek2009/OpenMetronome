package org.openmetronome.data

import java.time.Instant
import java.time.ZoneId

/** Uses anchored elapsed audio duration, not a possibly adjusted wall clock. */
object PracticeTimeline {
    fun days(sessionId: String, startedAt: Long, durationMs: Long, zone: ZoneId, maxDays: Int = 100_000): List<PracticeDay> {
        val days = mutableListOf<PracticeDay>()
        visitDays(sessionId, startedAt, durationMs, zone, maxDays, days::add)
        return days
    }

    internal fun visitDays(sessionId: String, startedAt: Long, durationMs: Long, zone: ZoneId,
        maxDays: Int, visit: (PracticeDay) -> Unit): Int {
        require(durationMs >= 0)
        val end = Math.addExact(startedAt, durationMs)
        var cursor = startedAt
        var count = 0
        while (cursor < end) {
            require(count < maxDays) { "Session timeline exceeds supported backup size" }
            val local = Instant.ofEpochMilli(cursor).atZone(zone)
            val midnight = local.toLocalDate().plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
            val until = minOf(end, midnight)
            require(until > cursor)
            visit(PracticeDay(sessionId, local.toLocalDate().toString(), until - cursor))
            count++
            cursor = until
        }
        return count
    }
}
