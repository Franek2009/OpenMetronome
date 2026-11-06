package org.openmetronome.data

import androidx.room.Room
import androidx.test.platform.app.InstrumentationRegistry
import java.time.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import org.junit.Assert.*
import org.junit.Test
import org.openmetronome.domain.model.MetronomeConfig

class DailyPracticeStorageTest {
    @Test fun dailyQuerySumsAllSessionsSplitsMidnightAndReplacesCheckpoints() = runBlocking<Unit> {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val db = Room.inMemoryDatabaseBuilder(context, PracticeDatabase::class.java).build()
        val dao = db.practice()
        val zone = ZoneId.of("Europe/Warsaw")
        val today = LocalDate.of(2026, 11, 6)
        suspend fun checkpoint(id: String, start: ZonedDateTime, ms: Long, finished: Boolean = false) {
            val started = start.toInstant().toEpochMilli()
            dao.checkpoint(PracticeSession(id, started, started + ms, ms, finished, false,
                zone.id, ConfigCodec.encode(MetronomeConfig())), PracticeTimeline.days(id, started, ms, zone))
        }
        try {
            assertEquals(0L, dao.durationOn(today.toString()).first())
            val start = today.atTime(12, 0).atZone(zone)
            checkpoint("running", start, 5000)
            checkpoint("other", start.minusHours(1), 1000, true)
            val subscribed = CompletableDeferred<Unit>()
            val changed = async(Dispatchers.IO) {
                withTimeout(5000) { dao.durationOn(today.toString()).onEach { if (it == 6000L) subscribed.complete(Unit) }.first { it == 11000L } }
            }
            withTimeout(5000) { subscribed.await() }
            checkpoint("running", start, 10000)
            assertEquals(11000L, changed.await())
            checkpoint("running", start, 10000, true)
            assertEquals(11000L, dao.durationOn(today.toString()).first())
            checkpoint("crossing", today.minusDays(1).atTime(23, 59, 55).atZone(zone), 15000, true)
            assertEquals(5000L, dao.durationOn(today.minusDays(1).toString()).first())
            assertEquals(21000L, dao.durationOn(today.toString()).first())
            assertEquals(0L, dao.durationOn(today.plusDays(1).toString()).first())
        } finally { db.close() }
    }
}
