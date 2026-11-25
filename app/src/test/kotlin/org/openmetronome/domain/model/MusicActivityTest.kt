package org.openmetronome.domain.model

import org.junit.Assert.*
import org.junit.Test

class MusicActivityTest {
    private val details = MusicActivityDetails(MusicActivityKind.ENSEMBLE_REHEARSAL, MusicActivitySource.FIT,
        0, 200_000, "Europe/Warsaw", 133_360, 1_000,
        listOf(MusicActivityInterval(0, 61_000), MusicActivityInterval(125_000, 197_000)))

    @Test fun reportedDurationAndReconstructedIntervalsAreIndependent() {
        assertEquals(133_360L, details.timerDurationMs)
        assertEquals(133_000L, details.intervals.sumOf { it.endAt - it.startAt })
        assertEquals(1_000L, details.intervalResolutionMs)
        val summary = details.copy(intervals = emptyList(), intervalResolutionMs = null)
        assertEquals(details.timerDurationMs, summary.timerDurationMs)
        assertTrue(summary.intervals.isEmpty())
    }

    @Test fun invalidIntervalCoverageAndDurationAreRejected() {
        assertThrows(IllegalArgumentException::class.java) { details.copy(intervals = details.intervals.reversed()) }
        assertThrows(IllegalArgumentException::class.java) {
            details.copy(intervals = listOf(MusicActivityInterval(0, 61_000), MusicActivityInterval(60_000, 70_000)))
        }
        assertThrows(IllegalArgumentException::class.java) {
            details.copy(intervals = listOf(MusicActivityInterval(0, details.endAt + 1)))
        }
        assertThrows(IllegalArgumentException::class.java) { details.copy(intervalResolutionMs = null) }
        assertThrows(IllegalArgumentException::class.java) { details.copy(intervals = emptyList()) }
        assertThrows(IllegalArgumentException::class.java) { details.copy(timerDurationMs = details.endAt + 1) }
        assertThrows(ArithmeticException::class.java) { MusicActivityInterval(Long.MIN_VALUE, Long.MAX_VALUE) }
    }

    @Test fun fitDurationAllowsOneResolutionStepPerIntervalInEitherDirection() {
        for (duration in listOf(131_000L, 135_000L)) {
            assertEquals(duration, details.copy(timerDurationMs = duration).timerDurationMs)
        }
        for (duration in listOf(130_999L, 135_001L, 190_000L)) {
            assertThrows(IllegalArgumentException::class.java) { details.copy(timerDurationMs = duration) }
        }
        assertThrows(IllegalArgumentException::class.java) { details.copy(intervalResolutionMs = 100) }
        val summary = details.copy(timerDurationMs = 190_000, intervals = emptyList(), intervalResolutionMs = null)
        assertTrue(summary.intervals.isEmpty())
    }

    @Test fun manualTimeIsDerivedFromItsInterval() {
        val entry = MusicActivityDetails.manual(MusicActivityKind.CONCERT, 100, 2_100, "UTC", "  Kontrabas  ", "  Próba\nII część  ")
        assertEquals(2_000L, entry.timerDurationMs)
        assertEquals("Kontrabas", entry.instrument)
        assertEquals("Próba\nII część", entry.note)
        assertNull(MusicActivityDetails.manual(MusicActivityKind.CONCERT, 100, 2_100, "UTC", note = "  ").note)
        assertThrows(IllegalArgumentException::class.java) { entry.copy(note = "x".repeat(2_001)) }
        assertThrows(IllegalArgumentException::class.java) { entry.copy(timerDurationMs = 1_900) }
    }
}
