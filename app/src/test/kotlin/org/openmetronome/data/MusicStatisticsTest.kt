package org.openmetronome.data

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import org.junit.Assert.*
import org.junit.Test

class MusicStatisticsTest {
    private val utc = MusicStatisticsPreferences(ZoneId.of("UTC"))

    @Test fun unionIncludesShortPlaybackAndCountsOverlappingSourcesOnlyOnce() {
        val now = Instant.ofEpochMilli(60_000)
        val ranges = listOf(playback(0, 10_000), activity(5_000, 30_000), playback(6_000, 9_000),
            activity(30_000, 40_000), playback(45_000, 5_000), activity(55_000, 65_000), activity(65_000, 70_000))
        val value = MusicStatisticsCalculator.calculate(ranges, utc, now)
        assertEquals(50_000L, value.totalMs)
        assertEquals(value.totalMs, value.todayMs)
        assertEquals(0, value.activeDays)
        assertNull(value.averageActiveDayMs)
        assertEquals(MusicStreak(0, 0), value.dayStreak)
    }

    @Test fun dayAverageUsesCalendarGapsAndActiveAverageUsesOnlyQualifyingDays() {
        val ranges = listOf(on("2026-01-01", 3), on("2026-01-03", 5), on("2026-01-04", 7), on("2026-01-05", 1))
        val value = summarize(ranges, "2026-01-05T12:00:00Z")
        assertEquals(16 * 60_000L, value.totalMs)
        assertEquals(192_000L, value.averageCalendarDayMs)
        assertEquals(360_000L, value.averageActiveDayMs)
        assertEquals(2, value.activeDays)
        assertEquals(MusicStreak(2, 2), value.dayStreak)
        assertEquals(MusicStreak(0, 2), summarize(ranges, "2026-01-06T12:00:00Z").dayStreak)
        val changed = summarize(ranges, "2026-01-05T12:00:00Z", utc.copy(activeDayMinutes = 7))
        assertEquals(value.totalMs, changed.totalMs)
        assertEquals(1, changed.activeDays)
    }

    @Test fun weeksStartMondayAcrossYearsAndAnOpenWeekCanContinueTheStreak() {
        val ranges = listOf("2025-12-28", "2025-12-29", "2026-01-01", "2026-01-05", "2026-01-08", "2026-01-12")
            .map { on(it, 5) }
        val preferences = utc.copy(weeklyGoalDays = 2)
        val value = summarize(ranges, "2026-01-12T12:00:00Z", preferences)
        assertEquals(MusicWeek(LocalDate.parse("2026-01-12"), 1, 2), value.currentWeek)
        assertEquals(MusicStreak(2, 2), value.weekStreak)
        assertEquals(300_000L, value.weekMs)
        assertEquals(1_200_000L, value.monthMs)
        assertEquals(MusicStreak(0, 2), summarize(ranges, "2026-01-19T12:00:00Z", preferences).weekStreak)
        val reached = summarize(ranges + on("2026-01-13", 5), "2026-01-13T12:00:00Z", preferences)
        assertEquals(MusicStreak(3, 3), reached.weekStreak)
        val disabled = summarize(ranges, "2026-01-12T12:00:00Z", preferences.copy(weeklyGoalDays = null))
        assertEquals(MusicStreak(4, 4), disabled.weekStreak)
        assertEquals(1, disabled.currentWeek.activeDays)
        assertNull(disabled.currentWeek.goalDays)
    }

    @Test fun changingTheStatisticsZoneRebucketsExistingInstantsAtMidnight() {
        val ranges = listOf(at("2026-01-04T22:30:00Z", "2026-01-04T23:30:00Z"))
        val now = "2026-01-05T00:30:00Z"
        val warsaw = summarize(ranges, now, utc.copy(zone = ZoneId.of("Europe/Warsaw")))
        assertEquals(listOf(1_800_000L, 1_800_000L), warsaw.days.map { it.durationMs })
        assertEquals(1_800_000L, warsaw.weekMs)
        val losAngeles = summarize(ranges, now, utc.copy(zone = ZoneId.of("America/Los_Angeles")))
        assertEquals(listOf(MusicDay(LocalDate.parse("2026-01-04"), 3_600_000, true)), losAngeles.days)
        assertEquals(LocalDate.parse("2026-01-04"), losAngeles.today)
        assertEquals(warsaw.totalMs, losAngeles.totalMs)
    }

    @Test fun fullDaysInWarsawCanContainTwentyThreeOrTwentyFiveHours() {
        val warsaw = utc.copy(zone = ZoneId.of("Europe/Warsaw"))
        val spring = summarize(listOf(at("2026-03-28T23:00:00Z", "2026-03-29T22:00:00Z")), "2026-03-30T12:00:00Z", warsaw)
        val autumn = summarize(listOf(at("2026-10-24T22:00:00Z", "2026-10-25T23:00:00Z")), "2026-10-26T12:00:00Z", warsaw)
        assertEquals(MusicDay(LocalDate.parse("2026-03-29"), 23 * 3_600_000L, true), spring.days.single())
        assertEquals(MusicDay(LocalDate.parse("2026-10-25"), 25 * 3_600_000L, true), autumn.days.single())
    }

    @Test fun dateReversalAtMidnightAndSkippedDateKeepAllElapsedMilliseconds() {
        val gooseBay = utc.copy(zone = ZoneId.of("America/Goose_Bay"))
        val repeated = summarize(listOf(at("1988-10-30T01:59:00Z", "1988-10-30T04:01:00Z")), "1988-10-30T05:00:00Z", gooseBay)
        assertEquals(listOf(120 * 60_000L, 2 * 60_000L), repeated.days.map { it.durationMs })
        val beforeSecondMidnight = summarize(listOf(at("1988-10-30T02:00:00Z", "1988-10-30T02:01:00Z")),
            "1988-10-30T02:15:00Z", gooseBay)
        assertEquals(60_000L, beforeSecondMidnight.averageCalendarDayMs)
        val apia = utc.copy(zone = ZoneId.of("Pacific/Apia"))
        val skipped = summarize(listOf(at("2011-12-30T09:59:00Z", "2011-12-30T10:01:00Z")), "2011-12-30T11:00:00Z", apia)
        assertEquals(listOf("2011-12-29", "2011-12-31"), skipped.days.map { it.date.toString() })
        assertEquals(40_000L, skipped.averageCalendarDayMs)
    }

    @Test fun emptyHistoryHasZerosAndUndefinedAveragesWhileInvalidIntervalsFail() {
        val empty = summarize(emptyList(), "2026-01-01T12:00:00Z")
        assertTrue(empty.days.isEmpty())
        assertEquals(0L, empty.totalMs)
        assertEquals(0, empty.activeDays)
        assertEquals(MusicWeek(LocalDate.parse("2025-12-29"), 0, null), empty.currentWeek)
        assertEquals(5, MusicStatisticsPreferences.SUGGESTED_WEEKLY_GOAL_DAYS)
        assertNull(empty.averageCalendarDayMs)
        assertNull(empty.averageActiveDayMs)
        assertThrows(IllegalArgumentException::class.java) { summarize(listOf(activity(5, 1)), "2026-01-01T12:00:00Z") }
        assertThrows(ArithmeticException::class.java) { summarize(listOf(playback(5, Long.MAX_VALUE)), "2026-01-01T12:00:00Z") }
    }

    @Test(timeout = 10_000) fun fifteenYearsAndOverSixtyFiveThousandIntervals() {
        val start = Instant.parse("2010-01-01T00:00:00Z").toEpochMilli()
        val dayCount = 5_500
        val ranges = buildList {
            repeat(dayCount) { day ->
                val from = start + day * 86_400_000L
                add(activity(from, from + 600_000))
                for (index in 1..11) add(playback(from + index * 10_000, 5_000))
            }
        }
        val summary = MusicStatisticsCalculator.calculate(ranges, utc, Instant.ofEpochMilli(start + dayCount * 86_400_000L))
        assertEquals(dayCount, summary.activeDays)
        assertEquals(dayCount * 600_000L, summary.totalMs)
        assertEquals(MusicStreak(dayCount, dayCount), summary.dayStreak)
    }

    private fun summarize(ranges: List<MusicStatisticsRange>, now: String, prefs: MusicStatisticsPreferences = utc) =
        MusicStatisticsCalculator.calculate(ranges, prefs, Instant.parse(now))

    private fun on(date: String, minutes: Int): MusicStatisticsRange {
        val from = Instant.parse(date + "T10:00:00Z").toEpochMilli()
        return activity(from, from + minutes * 60_000L)
    }

    private fun at(from: String, to: String) = activity(Instant.parse(from).toEpochMilli(), Instant.parse(to).toEpochMilli())
    private fun playback(from: Long, duration: Long) = MusicStatisticsRange(from, duration, playback = true)
    private fun activity(from: Long, to: Long) = MusicStatisticsRange(from, to, playback = false)
}
