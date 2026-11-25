package org.openmetronome.data

import androidx.test.platform.app.InstrumentationRegistry
import java.io.ByteArrayOutputStream
import java.io.File
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test
import org.openmetronome.domain.model.MetronomeConfig
import org.openmetronome.domain.model.MusicActivityDetails
import org.openmetronome.domain.model.MusicActivityInterval
import org.openmetronome.domain.model.MusicActivityKind
import org.openmetronome.domain.model.MusicActivitySource

class MusicStatisticsStorageTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val now = flowOf(Instant.ofEpochMilli(600_000))
    private val config = ConfigCodec.encode(MetronomeConfig())

    @Test fun observesEditAndDeletionAndKeepsLegacyMetronomeSemantics(): Unit = runBlocking {
        val data = AppData(context, isolatedTestStorage = true)
        try {
            data.ready.await()
            data.saveMusicStatisticsPreferences(MusicStatisticsPreferences(ZoneId.of("UTC")))
            data.awaitWrites()
            val samples = Channel<MusicStatisticsSummary>(Channel.UNLIMITED)
            val observer = launch {
                data.observeMusicStatistics(now).collect { if (it is MusicStatisticsState.Ready) samples.send(it.summary) }
            }
            try {
                suspend fun next(duration: Long): MusicStatisticsSummary = withTimeout(10_000) {
                    var value = samples.receive()
                    while (value.totalMs != duration) value = samples.receive()
                    value
                }
                assertTrue(next(0).days.isEmpty())
                val short = PracticeSession("short", 0, 90_000, 10_000, false, false, "America/Los_Angeles", config)
                data.dao.insertSessions(listOf(short))
                assertEquals(10_000L, next(10_000).todayMs)
                val details = MusicActivityDetails.manual(MusicActivityKind.LESSON, 5_000, 35_000, "Asia/Tokyo")
                data.addMusicActivity(details)
                data.awaitWrites()
                val record = data.musicActivities.first().single()
                assertEquals(35_000L, next(35_000).todayMs)
                data.editMusicActivity(record.id, details.copy(endAt = 400_000, timerDurationMs = 395_000,
                    intervals = listOf(MusicActivityInterval(5_000, 400_000))))
                data.awaitWrites()
                assertEquals(1, next(400_000).activeDays)
                data.deleteMusicActivity(record.id)
                data.addMusicActivity(MusicActivityDetails(MusicActivityKind.OTHER, MusicActivitySource.FIT,
                    0, 600_000, "UTC", 500_000, null, emptyList()))
                data.awaitWrites()
                assertEquals(0, next(10_000).activeDays)
                assertEquals(10_000L, ready(data).totalMs)
                val history = data.statisticsHistory().first { it is StatisticsHistory.Ready } as StatisticsHistory.Ready
                assertEquals(0L, data.calculateAnalytics(history.sessions, StatisticsPreferences(), LocalDate.ofEpochDay(0)).todayMs)
            } finally { observer.cancelAndJoin() }
        } finally { data.closeTestStorage() }
    }

    @Test fun invalidStoredIntervalProducesUnavailableInsteadOfEmptyHistory(): Unit = runBlocking {
        val data = AppData(context, isolatedTestStorage = true)
        try {
            data.ready.await()
            data.addMusicActivity(MusicActivityDetails.manual(MusicActivityKind.LESSON, 0, 30_000, "UTC"))
            data.awaitWrites()
            assertEquals(30_000L, ready(data).totalMs)
            data.database.openHelper.writableDatabase.execSQL("UPDATE music_activity_intervals SET endAt = startAt")
            assertEquals(MusicStatisticsState.Unavailable, withTimeout(10_000) {
                data.observeMusicStatistics(now).first { it != MusicStatisticsState.Loading }
            })
            data.database.openHelper.writableDatabase.execSQL("DROP TABLE music_activity_intervals")
            assertEquals(MusicStatisticsState.Unavailable, withTimeout(10_000) {
                data.observeMusicStatistics(now).first { it != MusicStatisticsState.Loading }
            })
        } finally { data.closeTestStorage() }
    }

    @Test fun settingsPersistAndV1AndV2ImportsKeepTheCurrentMusicChoices(): Unit = runBlocking {
        val dir = File(context.cacheDir, "music-statistics-${UUID.randomUUID()}").also { check(it.mkdirs()) }
        val data = AppData(context, dir)
        val original = MusicStatisticsPreferences(ZoneId.of("Pacific/Auckland"), 8, null)
        val changed = MusicStatisticsPreferences(ZoneId.of("Europe/Warsaw"), 0, 6)
        suspend fun restore(data: AppData, bytes: ByteArray) {
            data.importBackup({ bytes.inputStream() }) {}
            data.awaitWrites()
            assertTrue(data.backupNotice.value!!.startsWith("Kopia została przywrócona"))
        }
        try {
            data.ready.await()
            assertNull(data.settings.musicStatistics.first())
            data.saveMusicStatisticsPreferences(original)
            data.awaitWrites()
            val full = ByteArrayOutputStream()
            val legacy = ByteArrayOutputStream()
            val v2 = ByteArrayOutputStream()
            data.exportBackup { full }
            data.awaitWrites()
            data.exportLegacyBackup { legacy }
            data.awaitWrites()
            data.exportV2Backup { v2 }
            data.awaitWrites()
            data.saveMusicStatisticsPreferences(changed)
            data.awaitWrites()
            for (archive in listOf(legacy, v2)) {
                restore(data, archive.toByteArray())
                assertEquals(changed, ready(data).preferences)
            }
            restore(data, full.toByteArray())
            assertEquals(original, ready(data).preferences)
            data.closeTestStorage()
            val reopened = AppData(context, dir)
            try {
                reopened.ready.await()
                assertEquals(original, ready(reopened).preferences)
            } finally { reopened.closeTestStorage() }
        } finally { data.closeTestStorage(); dir.deleteRecursively() }
    }

    private suspend fun ready(data: AppData): MusicStatisticsSummary = withTimeout(10_000) {
        val state = data.observeMusicStatistics(now).first { it != MusicStatisticsState.Loading }
        (state as MusicStatisticsState.Ready).summary
    }
}
