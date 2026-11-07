package org.openmetronome.data

import java.time.Duration
import java.time.LocalDate
import java.time.ZonedDateTime
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.*

sealed interface DailyPractice {
    val date: LocalDate
    data class Loading(override val date: LocalDate) : DailyPractice
    data class Ready(override val date: LocalDate, val durationMs: Long) : DailyPractice
    data class Unavailable(override val date: LocalDate) : DailyPractice
}

/** A failed or pending query is never a successful zero. No live timer is added to persisted time. */
fun observeDailyPractice(date: LocalDate, durations: Flow<Long>): Flow<DailyPractice> = durations
    .map<Long, DailyPractice> { require(it >= 0); DailyPractice.Ready(date, it) }
    .onStart { emit(DailyPractice.Loading(date)) }
    .catch { error -> if (error is CancellationException) throw error else emit(DailyPractice.Unavailable(date)) }
    .distinctUntilChanged()

fun dailyPracticeDuration(ms: Long): String {
    require(ms >= 0)
    val seconds = (ms / 1000 % 60).toString().padStart(2, '0')
    val minutes = ms / 60_000
    return if (minutes >= 60) "${minutes / 60} h ${(minutes % 60).toString().padStart(2, '0')} min $seconds s"
        else "$minutes min $seconds s"
}

/** Uses calendar midnight, not a fixed 24-hour interval (DST and historical zones). */
fun millisUntilNextPracticeDate(now: ZonedDateTime): Long = Duration.between(now.toInstant(),
    now.toLocalDate().plusDays(1).atStartOfDay(now.zone).toInstant()).toMillis().coerceAtLeast(1)
