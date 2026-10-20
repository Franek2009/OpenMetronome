package org.openmetronome.data

import java.time.Instant
import java.time.ZoneId

/** Uses anchored elapsed audio duration, not a possibly adjusted wall clock. */
object PracticeTimeline {
    fun days(sessionId: String, startedAt: Long, durationMs: Long, zone: ZoneId): List<PracticeDay> {
        require(durationMs >= 0)
        val end = Math.addExact(startedAt, durationMs)
        var cursor = startedAt
        val days = mutableListOf<PracticeDay>()
        while (cursor < end) {
            val local = Instant.ofEpochMilli(cursor).atZone(zone)
            val midnight = local.toLocalDate().plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
            val until = minOf(end, midnight)
            days.add(PracticeDay(sessionId, local.toLocalDate().toString(), until - cursor))
            cursor = until
        }
        return days
    }
}
