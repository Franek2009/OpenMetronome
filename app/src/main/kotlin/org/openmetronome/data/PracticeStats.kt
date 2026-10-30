package org.openmetronome.data

import java.time.DayOfWeek
import java.time.LocalDate
import java.time.temporal.TemporalAdjusters

enum class StatisticsPeriod { DAYS_7, DAYS_30, WEEKS_12 }

data class PracticeStatsSummary(
    val currentStreak: Int,
    val longestStreak: Int,
    val bestDay: DayTotal?,
    val averageSessionMs: Long,
)

data class PracticeChartBar(val label: String, val durationMs: Long)

/** Locale-independent calculations kept outside Compose and Room. */
object PracticeStats {
    fun summary(days: List<DayTotal>, total: PracticeTotal, today: LocalDate): PracticeStatsSummary {
        val positive = days.filter { it.durationMs > 0 }.associate { LocalDate.parse(it.date) to it.durationMs }
        val currentStart = if (positive.containsKey(today)) today else today.minusDays(1)
        var cursor = currentStart
        var current = 0
        while (positive.containsKey(cursor)) { current++; cursor = cursor.minusDays(1) }

        var longest = 0
        var run = 0
        var previous: LocalDate? = null
        positive.keys.sorted().forEach { date ->
            run = if (date == previous?.plusDays(1)) run + 1 else 1
            if (run > longest) longest = run
            previous = date
        }
        val best = days.filter { it.durationMs > 0 }.maxWithOrNull(
            compareBy<DayTotal> { it.durationMs }.thenBy { it.date })
        return PracticeStatsSummary(current, longest, best,
            if (total.sessions == 0L) 0 else total.durationMs / total.sessions)
    }

    fun chart(days: List<DayTotal>, period: StatisticsPeriod, today: LocalDate): List<PracticeChartBar> {
        val values = days.associate { LocalDate.parse(it.date) to it.durationMs }
        return when (period) {
            StatisticsPeriod.DAYS_7 -> daily(values, today, 7)
            StatisticsPeriod.DAYS_30 -> daily(values, today, 30)
            StatisticsPeriod.WEEKS_12 -> {
                val thisMonday = today.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
                (11L downTo 0L).map { offset -> thisMonday.minusWeeks(offset) }.map { monday ->
                    val duration = (0L..6L).sumOf { values[monday.plusDays(it)] ?: 0 }
                    PracticeChartBar(monday.toString().drop(5), duration)
                }
            }
        }
    }

    private fun daily(values: Map<LocalDate, Long>, today: LocalDate, count: Int) =
        ((count - 1).toLong() downTo 0L).map { today.minusDays(it) }.map { date ->
            PracticeChartBar(date.toString().drop(5), values[date] ?: 0)
        }
}
