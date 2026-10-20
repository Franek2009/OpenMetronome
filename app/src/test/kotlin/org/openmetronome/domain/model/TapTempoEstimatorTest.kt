package org.openmetronome.domain.model

import org.junit.Assert.*
import org.junit.Test

class TapTempoEstimatorTest {
    @Test fun `first tap does not produce a tempo and supported endpoints are inclusive`() {
        for ((interval, bpm) in listOf(200_000_000L to 300, 500_000_000L to 120, 3_000_000_000L to 20)) {
            val taps = TapTempoEstimator()
            assertNull(taps.tap(0))
            repeat(8) { assertEquals(bpm, taps.tap((it + 1) * interval)) }
        }
    }
    @Test fun `median rejects an outlier and retains at most four recent intervals`() {
        val taps = TapTempoEstimator()
        var now = 0L
        taps.tap(now)
        for (interval in listOf(500, 500, 1000, 500)) {
            now += interval * 1_000_000L
            taps.tap(now)
        }
        assertEquals(120, taps.tap(now + 500_000_000L))
        now += 500_000_000L
        repeat(4) { now += 1_000_000_000L; taps.tap(now) }
        assertEquals(60, taps.tap(now + 1_000_000_000L))
    }
    @Test fun `bounce duplicate and backwards timestamps do not move the anchor`() {
        val taps = TapTempoEstimator()
        taps.tap(1_000_000_000L)
        assertNull(taps.tap(1_000_000_000L))
        assertNull(taps.tap(900_000_000L))
        assertNull(taps.tap(1_050_000_000L))
        assertEquals(120, taps.tap(1_500_000_000L))
    }
    @Test fun `out of range intervals reset measurement without a tempo`() {
        for (invalid in listOf(100_000_000L, 199_999_999L, 3_000_000_001L, 60_000_000_000L)) {
            val taps = TapTempoEstimator()
            taps.tap(0)
            assertEquals(120, taps.tap(500_000_000L))
            val restart = 500_000_000L + invalid
            assertNull(taps.tap(restart))
            assertEquals(60, taps.tap(restart + 1_000_000_000L))
        }
    }
    @Test fun `explicit reset discards both anchor and history`() {
        val taps = TapTempoEstimator()
        taps.tap(0); taps.tap(500_000_000L)
        taps.reset()
        assertNull(taps.tap(750_000_000L))
        assertEquals(60, taps.tap(1_750_000_000L))
    }
    @Test fun `bpm is rounded from nanoseconds not truncated millisecond intervals`() {
        val taps = TapTempoEstimator()
        taps.tap(0)
        assertEquals(137, taps.tap(60_000_000_000L / 137))
    }
}
