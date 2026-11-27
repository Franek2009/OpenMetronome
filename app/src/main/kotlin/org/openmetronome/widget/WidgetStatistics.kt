package org.openmetronome.widget

import java.time.LocalDate
import org.openmetronome.data.MusicStatisticsSummary
import org.openmetronome.data.PracticeAnalytics

/** A projection of the existing statistics, not an independent statistics calculator. */
internal data class WidgetDay(val date: LocalDate, val durationMs: Long, val active: Boolean)

internal data class WidgetStatistics(
    val todayMs: Long,
    val streakDays: Int,
    val weekMs: Long,
    val activeDays: Int,
    val weeklyGoal: Int?,
    val week: List<WidgetDay>,
    val hasData: Boolean = true,
)

internal object WidgetStatisticsProjector {
    fun metronome(today: LocalDate, savedTodayMs: Long, analytics: PracticeAnalytics): WidgetStatistics {
        val monday = today.minusDays((today.dayOfWeek.value - 1).toLong())
        val values = analytics.days.associate { LocalDate.parse(it.date) to it.durationMs }
        val days = (0L..6L).map { offset ->
            val date = monday.plusDays(offset)
            WidgetDay(date, if (date <= today) values[date] ?: 0L else 0L,
                date <= today && date in analytics.activeDates)
        }
        return WidgetStatistics(savedTodayMs, analytics.summary.currentStreak, analytics.weekMs,
            days.count { it.active }, null, days, analytics.days.isNotEmpty() || savedTodayMs > 0)
    }

    fun music(summary: MusicStatisticsSummary): WidgetStatistics {
        val values = summary.days.associateBy { it.date }
        val days = (0L..6L).map { offset ->
            val date = summary.currentWeek.monday.plusDays(offset)
            val day = values[date]
            WidgetDay(date, day?.durationMs ?: 0L, day?.active == true)
        }
        return WidgetStatistics(summary.todayMs, summary.dayStreak.current, summary.weekMs,
            summary.currentWeek.activeDays, summary.currentWeek.goalDays, days, summary.days.isNotEmpty())
    }
}
