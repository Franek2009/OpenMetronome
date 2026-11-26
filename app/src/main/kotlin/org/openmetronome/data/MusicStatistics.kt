package org.openmetronome.data

import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.TemporalAdjusters

data class MusicStatisticsPreferences(
    val zone: ZoneId = ZoneId.systemDefault(),
    val activeDayMinutes: Int = 5,
    val weeklyGoalDays: Int? = null,
) {
    init {
        require(activeDayMinutes in 0..1440)
        require(weeklyGoalDays == null || weeklyGoalDays in 1..7)
    }

    companion object {
        const val SUGGESTED_WEEKLY_GOAL_DAYS = 5
    }
}

sealed interface MusicStatisticsState {
    data object Loading : MusicStatisticsState
    data object Unavailable : MusicStatisticsState
    data class Ready(val summary: MusicStatisticsSummary) : MusicStatisticsState
}

data class MusicDay(val date: LocalDate, val durationMs: Long, val active: Boolean)
data class MusicStreak(val current: Int, val longest: Int)
data class MusicWeek(val monday: LocalDate, val activeDays: Int, val goalDays: Int?)

data class MusicStatisticsSummary(
    val preferences: MusicStatisticsPreferences,
    val today: LocalDate,
    val days: List<MusicDay>,
    val totalMs: Long,
    val todayMs: Long,
    val weekMs: Long,
    val monthMs: Long,
    val activeDays: Int,
    val averageCalendarDayMs: Long?,
    val averageActiveDayMs: Long?,
    val dayStreak: MusicStreak,
    val weekStreak: MusicStreak,
    val currentWeek: MusicWeek,
)

/** Playback uses elapsed audio duration; an activity row already has an absolute end. */
internal data class MusicStatisticsRange(val startAt: Long, val extent: Long, val playback: Boolean) {
    fun endAt(): Long = if (playback) Math.addExact(startAt, extent) else extent
}

internal object MusicStatisticsCalculator {
    fun calculate(ranges: List<MusicStatisticsRange>, preferences: MusicStatisticsPreferences, now: Instant,
        checkActive: () -> Unit = {}): MusicStatisticsSummary {
        val zone = preferences.zone
        val today = now.atZone(zone).toLocalDate()
        val cutoff = now.toEpochMilli()
        val totals = sortedMapOf<LocalDate, Long>()
        var start: Long? = null
        var end = 0L
        for (range in ranges) {
            checkActive()
            if (range.startAt >= cutoff) break
            val through = minOf(range.endAt(), cutoff)
            require(through > range.startAt) { "Invalid music statistics interval" }
            val currentStart = start
            if (currentStart == null || range.startAt > end) {
                if (currentStart != null) addDays(currentStart, end, zone, totals, checkActive)
                start = range.startAt
                end = through
            } else {
                require(range.startAt >= currentStart) { "Music statistics intervals must be sorted" }
                end = maxOf(end, through)
            }
        }
        start?.let { addDays(it, end, zone, totals, checkActive) }

        val activeThresholdMs = preferences.activeDayMinutes * 60_000L
        val days = totals.map { (date, duration) -> MusicDay(date, duration, duration >= activeThresholdMs) }
        val active = days.filter { it.active }
        val total = days.sumOf { it.durationMs }
        val monday = mondayOf(today)
        val activeByWeek = active.asSequence().takeWhile { it.date <= today }.groupingBy { mondayOf(it.date) }.eachCount()
        val goal = preferences.weeklyGoalDays
        val qualifyingWeeks = activeByWeek.filterValues { it >= (goal ?: 1) }.keys.map(LocalDate::toEpochDay)
        val calendarDays = days.firstOrNull()?.let { maxOf(today, days.last().date).toEpochDay() - it.date.toEpochDay() + 1 }
        val monthStart = today.withDayOfMonth(1)
        return MusicStatisticsSummary(preferences, today, days, total, totals[today] ?: 0L,
            days.filter { it.date in monday..today }.sumOf { it.durationMs },
            days.filter { it.date in monthStart..today }.sumOf { it.durationMs }, active.size,
            calendarDays?.let { total / it }, active.takeIf { it.isNotEmpty() }?.let { it.sumOf(MusicDay::durationMs) / it.size },
            streak(active.map { it.date.toEpochDay() }, today.toEpochDay(), 1),
            streak(qualifyingWeeks, monday.toEpochDay(), 7),
            MusicWeek(monday, activeByWeek[monday] ?: 0, goal))
    }

    private fun addDays(start: Long, end: Long, zone: ZoneId, totals: MutableMap<LocalDate, Long>, checkActive: () -> Unit) {
        var cursor = start
        while (cursor < end) {
            checkActive()
            val instant = Instant.ofEpochMilli(cursor)
            val day = instant.atZone(zone).toLocalDate()
            val nextDay = day.plusDays(1).atStartOfDay(zone)
            val midnight = (if (nextDay.toInstant() > instant) nextDay else nextDay.withLaterOffsetAtOverlap()).toInstant().toEpochMilli()
            // Some transitions move the local clock back across midnight.
            val transition = zone.rules.nextTransition(instant)?.instant?.toEpochMilli() ?: Long.MAX_VALUE
            val until = minOf(end, midnight, transition)
            require(until > cursor)
            require(day in totals || totals.size < MAX_DAYS) { "Music statistics exceed the supported calendar span" }
            totals[day] = Math.addExact(totals[day] ?: 0, until - cursor)
            cursor = until
        }
    }

    private fun mondayOf(date: LocalDate): LocalDate = date.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))

    private fun streak(periods: List<Long>, currentPeriod: Long, step: Long): MusicStreak {
        var previous: Long? = null
        var running = 0
        var longest = 0
        for (period in periods) {
            if (period > currentPeriod) break
            running = if (previous == period - step) running + 1 else 1
            longest = maxOf(longest, running)
            previous = period
        }
        return MusicStreak(if (previous == currentPeriod || previous == currentPeriod - step) running else 0, longest)
    }

    private const val MAX_DAYS = 100_000
}
