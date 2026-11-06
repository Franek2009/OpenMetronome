package org.openmetronome.data

import java.io.IOException
import java.time.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import org.junit.Assert.*
import org.junit.Test

class DailyPracticeTest {
    private val date = LocalDate.of(2026, 11, 6)

    @Test fun loadingSuccessfulZeroAndReadFailureAreDistinct() = runBlocking<Unit> {
        assertEquals(listOf(DailyPractice.Loading(date), DailyPractice.Ready(date, 0)),
            observeDailyPractice(date, flowOf(0L)).toList())
        assertEquals(listOf(DailyPractice.Loading(date), DailyPractice.Unavailable(date)),
            observeDailyPractice(date, flow { throw IOException("disk") }).toList())
        assertEquals(listOf(DailyPractice.Loading(date), DailyPractice.Unavailable(date)),
            observeDailyPractice(date, flowOf(-1L)).toList())
    }

    @Test fun checkpointsReplaceTimeWithoutAddingAnotherLiveSession() = runBlocking<Unit> {
        val values = observeDailyPractice(date, flowOf(5000L, 10000L, 10000L, 15000L)).toList()
        assertEquals(listOf(DailyPractice.Loading(date), DailyPractice.Ready(date, 5000),
            DailyPractice.Ready(date, 10000), DailyPractice.Ready(date, 15000)), values)
    }

    @Test fun cancellingCollectionNeverBecomesAReadError() = runBlocking<Unit> {
        assertThrows(CancellationException::class.java) {
            runBlocking { observeDailyPractice(date, flow { throw CancellationException("leaving statistics") }).toList() }
        }
    }

    @Test fun nextMidnightUsesCalendarDaysAcrossDstAndDifferentZones() {
        val warsaw = ZoneId.of("Europe/Warsaw")
        assertEquals(23 * 3_600_000L, millisUntilNextPracticeDate(LocalDate.of(2026, 3, 29).atStartOfDay(warsaw)))
        assertEquals(25 * 3_600_000L, millisUntilNextPracticeDate(LocalDate.of(2026, 10, 25).atStartOfDay(warsaw)))
        assertEquals(1000L, millisUntilNextPracticeDate(ZonedDateTime.of(2026, 11, 6, 23, 59, 59, 0, warsaw)))
        assertEquals(24 * 3_600_000L, millisUntilNextPracticeDate(date.atStartOfDay(ZoneId.of("Asia/Tokyo"))))
    }

    @Test fun durationShowsHoursMinutesAndSecondsWithoutRoundingOrWrapping() {
        assertEquals("0 min 00 s", dailyPracticeDuration(0))
        assertEquals("0 min 05 s", dailyPracticeDuration(5999))
        assertEquals("59 min 59 s", dailyPracticeDuration(3_599_999))
        assertEquals("3 h 14 min 25 s", dailyPracticeDuration(11_665_000))
        assertEquals("25 h 00 min 00 s", dailyPracticeDuration(25 * 3_600_000L))
    }
}
