package org.openmetronome.widget

import java.time.LocalDate
import java.time.ZoneId
import org.junit.Assert.*
import org.junit.Test
import org.openmetronome.data.*

class WidgetStatisticsTest {
    @Test fun musicProjectionUsesProvidedSummaryAndMondayToSunday() {
        val monday = LocalDate.of(2026, 11, 23)
        val summary = MusicStatisticsSummary(
            MusicStatisticsPreferences(ZoneId.of("Europe/Warsaw"), 5, 5), monday.plusDays(2),
            listOf(MusicDay(monday, 600_000, true), MusicDay(monday.plusDays(2), 120_000, false)),
            720_000, 120_000, 720_000, 720_000, 1, null, null,
            MusicStreak(0, 1), MusicStreak(0, 0), MusicWeek(monday, 1, 5),
        )
        val result = WidgetStatisticsProjector.music(summary)
        assertEquals(120_000L, result.todayMs)
        assertEquals(720_000L, result.weekMs)
        assertEquals(1, result.activeDays)
        assertEquals(5, result.weeklyGoal)
        assertEquals((0L..6L).map(monday::plusDays), result.week.map { it.date })
        assertEquals(listOf(true, false, false, false, false, false, false), result.week.map { it.active })
        assertEquals(listOf(600_000L, 0L, 120_000L, 0L, 0L, 0L, 0L), result.week.map { it.durationMs })
    }

    @Test fun metronomeRetainsSavedTodayEvenWhenShortSessionsAreExcludedFromStatistics() {
        val today = LocalDate.of(2026, 11, 25)
        val analytics = PracticeAnalyticsCalculator.calculate(emptyList(), StatisticsPreferences(), today) { null }
        val result = WidgetStatisticsProjector.metronome(today, 10_000, analytics)
        assertEquals(10_000L, result.todayMs)
        assertTrue(result.hasData)
        assertEquals(0L, result.weekMs)
        assertEquals(0, result.activeDays)
        assertNull(result.weeklyGoal)
        assertEquals(today.minusDays(2), result.week.first().date)
        assertEquals(today.plusDays(4), result.week.last().date)
        assertFalse(WidgetStatisticsProjector.metronome(today, 0, analytics).hasData)
    }
}
