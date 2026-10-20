package org.openmetronome.data

import java.time.*
import org.junit.Assert.*
import org.junit.Test

class PracticeTimelineTest {
    @Test fun `practice crossing midnight is split without losing milliseconds`() {
        val zone = ZoneId.of("Europe/Warsaw")
        val start = ZonedDateTime.of(2026, 10, 18, 23, 59, 59, 500000000, zone).toInstant().toEpochMilli()
        val days = PracticeTimeline.days("test", start, 3000, zone)
        assertEquals(listOf("2026-10-18", "2026-10-19"), days.map { it.date })
        assertEquals(listOf(500L, 2500L), days.map { it.durationMs })
    }
    @Test fun `DST days have actual elapsed lengths not fixed twenty four hours`() {
        val zone = ZoneId.of("Europe/Warsaw")
        for ((date, hours) in listOf(LocalDate.of(2026, 3, 29) to 23, LocalDate.of(2026, 10, 25) to 25)) {
            val start = date.atStartOfDay(zone).toInstant().toEpochMilli()
            val duration = hours * 3600000L + 1000
            val days = PracticeTimeline.days("dst", start, duration, zone)
            assertEquals(hours * 3600000L, days.first().durationMs)
            assertEquals(1000L, days.last().durationMs)
            assertEquals(duration, days.sumOf { it.durationMs })
        }
    }
    @Test fun `zero duration is not an active practice day`() {
        assertTrue(PracticeTimeline.days("zero", 0, 0, ZoneId.of("UTC")).isEmpty())
        assertThrows(IllegalArgumentException::class.java) { PracticeTimeline.days("bad", 0, -1, ZoneId.of("UTC")) }
    }
}
