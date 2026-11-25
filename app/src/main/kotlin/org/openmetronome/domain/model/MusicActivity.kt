package org.openmetronome.domain.model

import java.time.ZoneId
import java.util.UUID
import kotlin.math.abs

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
    val note: String? = null,
) {
    init {
        val elapsed = Math.subtractExact(endAt, startAt)
        ZoneId.of(zoneId)
        require(timerDurationMs in 1..elapsed)
        require(instrument == null || (instrument.isNotBlank() && instrument.length <= 120))
        require(note == null || (note.isNotBlank() && note.length <= MAX_NOTE_LENGTH))
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
        } else if (intervalResolutionMs != null) {
            // Each start/end pair on the same timer clock can differ by up to one quantum.
            require(abs(timerDurationMs - duration) <= intervals.size * intervalResolutionMs) {
                "FIT timer duration disagrees with reconstructed intervals"
            }
        }
    }

    companion object {
        const val MAX_INTERVALS = 4_096
        const val MAX_NOTE_LENGTH = 2_000

        fun manual(kind: MusicActivityKind, startAt: Long, endAt: Long, zoneId: String,
            instrument: String? = null, note: String? = null) =
            MusicActivityDetails(kind, MusicActivitySource.MANUAL, startAt, endAt, zoneId,
                Math.subtractExact(endAt, startAt), 1, listOf(MusicActivityInterval(startAt, endAt)),
                instrument?.trim()?.takeIf { it.isNotEmpty() }, note?.trim()?.takeIf { it.isNotEmpty() })
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
