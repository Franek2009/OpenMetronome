package org.openmetronome.data

import androidx.room.testing.MigrationTestHelper
import androidx.test.platform.app.InstrumentationRegistry
import java.io.ByteArrayOutputStream
import java.io.File
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.openmetronome.domain.model.MetronomeConfig
import org.openmetronome.domain.model.MusicActivityDetails
import org.openmetronome.domain.model.MusicActivityKind

class PracticeGroupingStorageTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    @get:Rule val migration = MigrationTestHelper(instrumentation, PracticeDatabase::class.java)
    private val now = Instant.ofEpochMilli(810_000)
    private val config = ConfigCodec.encode(MetronomeConfig())

    @Test fun migrationFromRoom4KeepsRawPlaybacksAndMusicActivities() {
        val name = "grouping-migration-${UUID.randomUUID()}.db"
        try {
            migration.createDatabase(name, 4).use { db ->
                db.execSQL("INSERT INTO practice_sessions VALUES ('playback',0,70000,60000,1,0,'UTC',?,NULL)", arrayOf(config))
                db.execSQL("INSERT INTO practice_days VALUES ('playback','1970-01-01',60000)")
                db.execSQL("INSERT INTO music_activities VALUES ('activity','LESSON','MANUAL',0,1000,'UTC',1000,1,'Altówka','Notatka',1,2,2)")
                db.execSQL("INSERT INTO music_activity_intervals VALUES ('activity',0,1000)")
            }
            migration.runMigrationsAndValidate(name, 5, true, PracticeDatabase.MIGRATION_4_5).use { db ->
                db.query("SELECT checkpointAt,durationMs,config FROM practice_sessions WHERE id='playback'").use {
                    assertTrue(it.moveToFirst()); assertEquals(70000L, it.getLong(0)); assertEquals(60000L, it.getLong(1)); assertEquals(config, it.getString(2))
                }
                db.query("SELECT note,deletedAt FROM music_activities WHERE id='activity'").use {
                    assertTrue(it.moveToFirst()); assertEquals("Notatka", it.getString(0)); assertEquals(2L, it.getLong(1))
                }
                for ((table, count) in listOf("practice_days" to 1, "music_activity_intervals" to 1, "grouping_corrections" to 0)) {
                    db.query("SELECT COUNT(*) FROM $table").use { assertTrue(it.moveToFirst()); assertEquals(count, it.getInt(0)) }
                }
            }
        } finally { context.deleteDatabase(name) }
    }

    @Test fun settingsCorrectionsAndResetSurviveRestartAndFullBackup(): Unit = runBlocking {
        val dir = File(context.cacheDir, "practice-grouping-${UUID.randomUUID()}").also { check(it.mkdirs()) }
        val data = AppData(context, dir)
        val a = playback(1, 0)
        val b = playback(2, 700_000)
        val rows = listOf(a, b)
        try {
            data.ready.await()
            data.dao.insertSessions(rows)
            data.savePracticeGroupingGap(PracticeGroupingGap.MINUTES_5)
            data.saveMusicStatisticsPreferences(MusicStatisticsPreferences(ZoneId.of("UTC"), 0, 1))
            data.addMusicActivity(MusicActivityDetails.manual(MusicActivityKind.LESSON, 30_000, 100_000, "UTC"))
            data.awaitWrites()
            val legacy = legacyStats(data)
            val music = musicStats(data)
            val dbTotal = data.dao.total().first()
            val join = data.previewPracticeGrouping(PracticeGroupingChange.Join(a.id, b.id), now)
            assertEquals(2, join.before.size)
            assertEquals(listOf(a.id, b.id), join.after.single().playbackIds)
            data.applyPracticeGrouping(join)
            data.awaitWrites()
            assertEquals(1, data.practiceGrouping(now).groups.size)
            assertEquals(legacy, legacyStats(data)); assertEquals(music, musicStats(data)); assertEquals(dbTotal, data.dao.total().first())

            val staleSplit = data.previewPracticeGrouping(PracticeGroupingChange.SplitBefore(b.id), now)
            val late = playback(3, 500_000)
            data.dao.insertSessions(listOf(late))
            data.applyPracticeGrouping(staleSplit)
            data.awaitWrites()
            assertNotNull(data.error.value)
            assertEquals(listOf(a.id, late.id, b.id), data.practiceGrouping(now).groups.single().playbackIds)
            val local = data.previewPracticeGrouping(PracticeGroupingChange.ResetGroup(b.id), now)
            assertEquals(1, local.resetCount)
            data.applyPracticeGrouping(local)
            data.awaitWrites()
            assertEquals(2, data.practiceGrouping(now).groups.size)
            assertNotNull(data.database.grouping().corrections().single().deletedAt)
            val backup = ByteArrayOutputStream()
            data.exportBackup { backup }; data.awaitWrites()
            val archive = BackupArchive.read(backup.toByteArray().inputStream())
            assertEquals(PracticeGroupingGap.MINUTES_5, AppPreferencesCodec.decode(archive.preferences!!).groupingGap)
            assertNotNull(archive.groupingCorrections.single().deletedAt)
            assertEquals(listOf(a, late, b), data.dao.sessionsForExport())
            data.closeTestStorage()

            val reopened = AppData(context, dir)
            try {
                reopened.ready.await()
                assertEquals(PracticeGroupingGap.MINUTES_5, reopened.settings.groupingGap.first())
                assertEquals(archive.groupingCorrections, reopened.database.grouping().corrections().map(GroupingCorrectionRow::toDomain))
                reopened.savePracticeGroupingGap(PracticeGroupingGap.MINUTES_60)
                reopened.awaitWrites()
                assertEquals(1, reopened.practiceGrouping(now).groups.size)
                reopened.importBackup({ backup.toByteArray().inputStream() }) {}
                reopened.awaitWrites()
                assertEquals(PracticeGroupingGap.MINUTES_5, reopened.settings.groupingGap.first())
                assertEquals(2, reopened.practiceGrouping(now).groups.size)
            } finally { reopened.closeTestStorage() }
        } finally { data.closeTestStorage(); dir.deleteRecursively() }
    }

    @Test fun finishWaitsForPlaybackReservationAndOnlyThenClosesTheGroup(): Unit = runBlocking {
        val data = AppData(context, isolatedTestStorage = true)
        try {
            data.ready.await()
            val last = playback(1, 700_000)
            data.dao.insertSessions(listOf(last))
            assertTrue(data.reservePlayback())
            val finish = data.previewPracticeGrouping(PracticeGroupingChange.FinishAfter(last.id), now)
            data.applyPracticeGrouping(finish)
            data.awaitWrites()
            assertTrue(data.database.grouping().corrections().isEmpty())
            assertFalse(data.practiceGrouping(now).groups.single().completed)
            data.playbackFinished()
            data.sessionWriteFailed.value = true
            data.applyPracticeGrouping(finish)
            data.awaitWrites()
            assertTrue(data.database.grouping().corrections().isEmpty())
            data.sessionWriteFailed.value = false
            data.applyPracticeGrouping(finish)
            data.awaitWrites()
            val saved = data.database.grouping().corrections().single()
            assertEquals(GroupingCorrectionKind.FINISH_AFTER.name, saved.kind)
            assertTrue(data.practiceGrouping(now).groups.single().completed)
            val repeat = data.previewPracticeGrouping(PracticeGroupingChange.FinishAfter(last.id), now)
            assertTrue(repeat.before.isEmpty())
            assertTrue(repeat.after.isEmpty())
            data.error.value = null
            data.applyPracticeGrouping(finish)
            data.applyPracticeGrouping(repeat)
            data.awaitWrites()
            assertEquals(listOf(saved), data.database.grouping().corrections())
            assertNull(data.error.value)
            assertEquals(last, data.dao.sessionsForExport().single())
        } finally { data.closeTestStorage() }
    }

    @Test fun resetAcrossSqlBatchesRollsBackEveryTombstoneOnFailure(): Unit = runBlocking {
        val data = AppData(context, isolatedTestStorage = true)
        try {
            data.ready.await()
            val last = playback(1, 0)
            data.dao.insertSessions(listOf(last))
            val rows = (1L..901L).map { GroupingCorrectionRow(GroupingCorrection(UUID(4, it).toString(), it,
                GroupingCorrectionKind.FINISH_AFTER, last.id)) }
            val dao = data.database.grouping()
            dao.insert(rows)
            val preview = data.previewPracticeGrouping(PracticeGroupingChange.ResetAll, Instant.EPOCH)
            assertEquals(901, preview.resetCount)
            val sqlite = data.database.openHelper.writableDatabase
            sqlite.execSQL("""
                CREATE TRIGGER reject_second_batch BEFORE UPDATE OF deletedAt ON grouping_corrections
                WHEN (SELECT COUNT(*) FROM grouping_corrections WHERE deletedAt IS NOT NULL) >= 900
                BEGIN SELECT RAISE(ABORT, 'grouping reset fixture'); END
            """.trimIndent())
            data.applyPracticeGrouping(preview)
            data.awaitWrites()
            assertNotNull(data.error.value)
            assertEquals(rows, dao.corrections())
            sqlite.execSQL("DROP TRIGGER reject_second_batch")
            data.applyPracticeGrouping(preview)
            data.awaitWrites()
            val tombstones = dao.corrections()
            assertEquals(rows.map { it.id }, tombstones.map { it.id })
            assertTrue(tombstones.all { it.deletedAt == tombstones.first().deletedAt && it.deletedAt != null })
        } finally { data.closeTestStorage() }
    }

    private fun playback(id: Long, start: Long) = PracticeSession(UUID(0, id).toString(), start,
        start + 60_000, 60_000, true, false, "UTC", config)

    private suspend fun legacyStats(data: AppData) = data.calculateAnalytics(data.dao.sessionsForExport(), StatisticsPreferences(), LocalDate.ofEpochDay(0))
    private suspend fun musicStats(data: AppData) = data.observeMusicStatistics(flowOf(now)).first { it is MusicStatisticsState.Ready }
}
