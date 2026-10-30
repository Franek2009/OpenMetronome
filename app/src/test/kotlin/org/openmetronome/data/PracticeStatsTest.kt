package org.openmetronome.data

import java.time.LocalDate
import org.junit.Assert.*
import org.junit.Test

class PracticeStatsTest {
    private val today = LocalDate.of(2026, 10, 30)
    private fun day(date: String, minutes: Long) = DayTotal(date, minutes * 60_000)

    @Test fun `summary calculates streaks best day and average`() {
        val days = listOf(
            day("2026-10-20", 10), day("2026-10-21", 20), day("2026-10-23", 15),
            day("2026-10-27", 5), day("2026-10-28", 30), day("2026-10-29", 12),
        )
        val result = PracticeStats.summary(days, PracticeTotal(4, 80 * 60_000), today)
        assertEquals(3, result.currentStreak)
        assertEquals(3, result.longestStreak)
        assertEquals("2026-10-28", result.bestDay?.date)
        assertEquals(20 * 60_000L, result.averageSessionMs)
    }

    @Test fun `today extends current streak and empty history returns zeroes`() {
        assertEquals(2, PracticeStats.summary(listOf(day("2026-10-29", 1), day("2026-10-30", 1)), PracticeTotal(2, 2), today).currentStreak)
        assertEquals(PracticeStatsSummary(0, 0, null, 0), PracticeStats.summary(emptyList(), PracticeTotal(0, 0), today))
    }

    @Test fun `charts contain complete periods and aggregate weeks from monday`() {
        val days = listOf(day("2026-10-30", 7), day("2026-10-26", 3), day("2026-10-25", 5))
        val week = PracticeStats.chart(days, StatisticsPeriod.DAYS_7, today)
        assertEquals(7, week.size); assertEquals(7 * 60_000L, week.last().durationMs)
        val months = PracticeStats.chart(days, StatisticsPeriod.DAYS_30, today)
        assertEquals(30, months.size)
        val weeks = PracticeStats.chart(days, StatisticsPeriod.WEEKS_12, today)
        assertEquals(12, weeks.size); assertEquals(10 * 60_000L, weeks.last().durationMs)
    }
}
