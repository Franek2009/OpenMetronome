package org.openmetronome.data

import java.time.*
import org.junit.Assert.*
import org.junit.Test
import org.openmetronome.domain.model.*

class PracticeAnalyticsTest {
    private val date = LocalDate.of(2026, 11, 7)
    private val config = ConfigCodec.encode(MetronomeConfig())
    private fun session(id: String, ms: Long, start: ZonedDateTime = date.atStartOfDay(ZoneId.of("Europe/Warsaw"))) =
        PracticeSession(id, start.toInstant().toEpochMilli(), start.toInstant().toEpochMilli() + ms, ms, true, false, start.zone.id, config)

    @Test fun thresholdsRecalculateWithoutDeletingShortSessionsOrInventingAnalysis() {
        val sessions = listOf(session("short", 19999), session("edge", 20000), session("long", 280000))
        val stats = PracticeAnalyticsCalculator.calculate(sessions, StatisticsPreferences(), date)
        assertEquals(2, stats.qualifying.size); assertEquals(300000L, stats.todayMs)
        assertEquals(setOf(date), stats.activeDates); assertEquals(1, stats.summary.currentStreak)
        assertEquals(0L, stats.knownMs); assertEquals(300000L, stats.unknownMs)
        assertEquals(3, sessions.size)
        val strict = PracticeAnalyticsCalculator.calculate(sessions, StatisticsPreferences(30, 5), date)
        assertEquals(280000L, strict.todayMs); assertTrue(strict.activeDates.isEmpty())
    }

    @Test fun qualificationUsesWholeSessionThenSplitsTimeAtItsZoneMidnight() {
        val crossing = session("midnight", 40000, date.minusDays(1).atTime(23, 59, 50).atZone(ZoneId.of("Europe/Warsaw")))
        val stats = PracticeAnalyticsCalculator.calculate(listOf(crossing), StatisticsPreferences(30, 0), date)
        assertEquals(10000L, stats.days.single { it.date == date.minusDays(1).toString() }.durationMs)
        assertEquals(30000L, stats.todayMs); assertEquals(2, stats.summary.currentStreak)
        val dst = session("dst", 25 * 3600000L, LocalDate.of(2026, 10, 25).atStartOfDay(ZoneId.of("Europe/Warsaw")))
        assertEquals(25 * 3600000L, PracticeAnalyticsCalculator.calculate(listOf(dst), StatisticsPreferences(), date).days.single().durationMs)
    }

    @Test fun effectiveTempoAnalysisUsesOnlyCompletePlayedSegments() {
        val fast = ConfigCodec.encode(MetronomeConfig(tempo = Tempo(121), tempoPercent = TempoPercent.THREE_QUARTERS))
        val slow = ConfigCodec.encode(MetronomeConfig(tempo = Tempo(20), tempoPercent = TempoPercent.QUARTER,
            silence = SilenceConfig(50), ramp = TempoRamp(enabled = true)))
        val timeline = SessionTimelineCodec.encode(SessionTimeline(true, listOf(SessionConfiguration(0, fast), SessionConfiguration(10000, slow))))
        val complete = session("known", 30000).copy(timeline = timeline)
        val partial = session("partial", 30000).copy(timeline = SessionTimelineCodec.encode(SessionTimeline(false, listOf(SessionConfiguration(0, fast)))))
        val stats = PracticeAnalyticsCalculator.calculate(listOf(complete, partial), StatisticsPreferences(), date)
        assertEquals(30000L, stats.knownMs); assertEquals(30000L, stats.unknownMs)
        assertEquals(10000L, stats.tempoMs.getValue("90–119.99 BPM")); assertEquals(20000L, stats.tempoMs.getValue("5–59.99 BPM"))
        assertEquals(20000L, stats.trainingMs); assertEquals(20000L, stats.rampMs)
    }

    @Test fun timelineRejectsOutOfOrderOutOfDurationAndUnsupportedConfigs() {
        for (changes in listOf(listOf(SessionConfiguration(1, config)), listOf(SessionConfiguration(0, config), SessionConfiguration(20, config)),
            listOf(SessionConfiguration(0, config), SessionConfiguration(0, config)), listOf(SessionConfiguration(0, "version=999")))) {
            val encoded = SessionTimelineCodec.encode(SessionTimeline(true, changes))
            assertThrows(Exception::class.java) { SessionTimelineCodec.decode(encoded, 10) }
        }
        assertThrows(Exception::class.java) { SessionTimelineCodec.decode("A".repeat(800000), 1) }
    }
}
