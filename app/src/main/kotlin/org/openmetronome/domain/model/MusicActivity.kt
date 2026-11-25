package org.openmetronome.domain.model

import java.time.ZoneId
import java.util.UUID

enum class MusicActivityKind { LESSON, INDIVIDUAL_PRACTICE, ENSEMBLE_REHEARSAL, CONCERT, OTHER }

enum class MusicActivitySource { MANUAL, FIT }

/** Half-open interval on the UTC timeline. */
data class MusicActivityInterval(val startAt: Long, val endAt: Long) {
    init { require(endAt > startAt); Math.subtractExact(endAt, startAt) }
}

data class MusicActivityDetails(
    val kind: MusicActivityKind,
    val source: MusicActivitySource,
    val startAt: Long,
    val endAt: Long,
    val zoneId: String,
    val timerDurationMs: Long,
    val intervalResolutionMs: Long?,
    val intervals: List<MusicActivityInterval>,
    val instrument: String? = null,
) {
    init {
        val elapsed = Math.subtractExact(endAt, startAt)
        ZoneId.of(zoneId)
        require(timerDurationMs in 1..elapsed)
        require(instrument == null || (instrument.isNotBlank() && instrument.length <= 120))
        require(intervals.size <= MAX_INTERVALS)
        // A summary without known placement has a timer duration but no intervals.
        require(if (intervals.isEmpty()) intervalResolutionMs == null else intervalResolutionMs in 1L..1_000L)
        var previousEnd = startAt
        var duration = 0L
        for (interval in intervals) {
            require(interval.startAt >= previousEnd && interval.endAt <= endAt)
            duration += interval.endAt - interval.startAt
            previousEnd = interval.endAt
        }
        if (source == MusicActivitySource.MANUAL) {
            require(intervals.isNotEmpty() && timerDurationMs == duration)
        }
    }

    companion object {
        const val MAX_INTERVALS = 4_096

        fun manual(kind: MusicActivityKind, startAt: Long, endAt: Long, zoneId: String, instrument: String? = null) =
            MusicActivityDetails(kind, MusicActivitySource.MANUAL, startAt, endAt, zoneId,
                Math.subtractExact(endAt, startAt), 1, listOf(MusicActivityInterval(startAt, endAt)),
                instrument?.trim()?.takeIf { it.isNotEmpty() })
    }
}

data class MusicActivity(
    val id: String,
    val details: MusicActivityDetails,
    val createdAt: Long,
    val updatedAt: Long,
    val deletedAt: Long? = null,
) {
    init {
        require(UUID.fromString(id).toString() == id)
        require(updatedAt >= createdAt)
        require(deletedAt == null || deletedAt in createdAt..updatedAt)
    }
}
