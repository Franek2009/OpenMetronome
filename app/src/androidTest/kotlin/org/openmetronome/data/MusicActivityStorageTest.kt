package org.openmetronome.data

import androidx.room.testing.MigrationTestHelper
import androidx.test.platform.app.InstrumentationRegistry
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.UUID
import java.util.zip.ZipInputStream
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.openmetronome.domain.model.*

class MusicActivityStorageTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    @get:Rule val migration = MigrationTestHelper(instrumentation, PracticeDatabase::class.java)

    @Test fun roomMigrationPreservesMetronomeRowsForEverySupportedSourceSchema() {
        for (version in 1..3) {
            val name = "music-migration-${UUID.randomUUID()}.db"
            val config = ConfigCodec.encode(MetronomeConfig())
            val timeline = SessionTimelineCodec.encode(SessionTimeline(true, listOf(SessionConfiguration(0, config))))
            try {
                migration.createDatabase(name, version).use { db ->
                    db.execSQL("INSERT INTO presets VALUES ('preset-id','Preset',?,1,2)", arrayOf(config))
                    db.execSQL("""
                        INSERT INTO practice_sessions (id,startedAt,checkpointAt,durationMs,finished,interrupted,zoneId,config)
                        VALUES ('session-id',0,1000,1000,1,1,'UTC',?)
                    """.trimIndent(), arrayOf(config))
                    if (version == 3) db.execSQL("UPDATE practice_sessions SET timeline = ?", arrayOf(timeline))
                    db.execSQL("INSERT INTO practice_days VALUES ('session-id','1970-01-01',1000)")
                    if (version >= 2) db.execSQL("INSERT INTO custom_samples VALUES ('sample-id','Klik',1)")
                }
                migration.runMigrationsAndValidate(name, 4, true, PracticeDatabase.MIGRATION_1_2,
                    PracticeDatabase.MIGRATION_2_3, PracticeDatabase.MIGRATION_3_4).use { db ->
                    db.query("SELECT id,durationMs,interrupted,config,timeline FROM practice_sessions").use {
                        assertTrue(it.moveToFirst())
                        assertEquals("session-id", it.getString(0))
                        assertEquals(1000L, it.getLong(1))
                        assertEquals(1, it.getInt(2))
                        assertEquals(config, it.getString(3))
                        assertEquals(timeline.takeIf { version == 3 }, it.getString(4))
                    }
                    db.query("SELECT id,config FROM presets").use {
                        assertTrue(it.moveToFirst()); assertEquals("preset-id", it.getString(0)); assertEquals(config, it.getString(1))
                    }
                    db.query("SELECT sessionId,date,durationMs FROM practice_days").use {
                        assertTrue(it.moveToFirst()); assertEquals("session-id", it.getString(0))
                        assertEquals("1970-01-01", it.getString(1)); assertEquals(1000L, it.getLong(2))
                    }
                    db.query("SELECT id FROM custom_samples").use {
                        if (version >= 2) { assertTrue(it.moveToFirst()); assertEquals("sample-id", it.getString(0)) }
                        else assertFalse(it.moveToFirst())
                    }
                    for (table in listOf("music_activities", "music_activity_intervals")) {
                        db.query("SELECT COUNT(*) FROM $table").use { assertTrue(it.moveToFirst()); assertEquals(0, it.getInt(0)) }
                    }
                }
            } finally { context.deleteDatabase(name) }
        }
    }

    @Test fun failedChildWriteRollsBackEditAndDeletionSurvivesReopen(): Unit = runBlocking {
        val fixture = File(context.cacheDir, "music-activity-${UUID.randomUUID()}")
        lateinit var previous: MusicActivity
        suspend fun withStorage(test: suspend (AppData) -> Unit) {
            val data = AppData(context, testDirectory = fixture)
            try { data.ready.await(); test(data) }
            finally { data.closeTestStorage() }
        }
        try {
            withStorage { data ->
                val details = MusicActivityDetails.manual(MusicActivityKind.LESSON, 0, 2_000, "UTC", "Altówka", "Próba")
                data.addMusicActivity(details)
                data.awaitWrites()
                previous = data.musicActivities.first().single()
                val replacement = MusicActivityDetails.manual(MusicActivityKind.CONCERT, 4_000, 9_000, "UTC", note = "II część")
                data.database.openHelper.writableDatabase.execSQL("""
                    CREATE TRIGGER deny_test_interval BEFORE INSERT ON music_activity_intervals
                    BEGIN SELECT RAISE(ABORT, 'fixture interval write error'); END
                """.trimIndent())
                data.editMusicActivity(previous.id, replacement)
                data.awaitWrites()
                assertNotNull(data.error.value)
                assertEquals(previous, data.musicActivities.first().single())
                data.database.openHelper.writableDatabase.execSQL("DROP TRIGGER deny_test_interval")
                data.editMusicActivity(previous.id, replacement)
                data.awaitWrites()
                val edited = data.musicActivities.first().single()
                assertEquals(previous.id, edited.id)
                assertEquals(previous.createdAt, edited.createdAt)
                assertEquals(replacement, edited.details)
                data.deleteMusicActivity(previous.id)
                data.awaitWrites()
                assertTrue(data.musicActivities.first().isEmpty())
            }
            withStorage { data ->
                assertTrue(data.musicActivities.first().isEmpty())
                val tombstone = data.database.musicActivities().all().single().toDomain()
                assertEquals(previous.id, tombstone.id)
                assertNotNull(tombstone.deletedAt)
                assertEquals("II część", tombstone.details.note)
                data.editMusicActivity(previous.id, previous.details)
                data.awaitWrites()
                assertTrue(data.musicActivities.first().isEmpty())
                assertEquals(tombstone, data.database.musicActivities().all().single().toDomain())
                fun metadataSize(bytes: ByteArrayOutputStream) = ZipInputStream(bytes.toByteArray().inputStream()).use { zip ->
                    assertEquals("openmetronome.dat", zip.nextEntry.name)
                    zip.readBytes().size
                }
                val v2 = ByteArrayOutputStream()
                val v3 = ByteArrayOutputStream()
                data.exportV2Backup { v2 }
                data.awaitBackup()
                data.exportBackup { v3 }
                data.awaitBackup()
                val size = data.database.musicActivities().backupSize()
                assertEquals(1L, size.activities)
                assertEquals(1L, size.intervals)
                val preferences = data.settings.preferencesSnapshot()
                val legacy = AppPreferencesCodec.forCompatibility(preferences)!!
                val preferenceBytes = preferences.toByteArray().size - legacy.toByteArray().size
                assertEquals((metadataSize(v3) - metadataSize(v2) - preferenceBytes).toLong(), size.activityBytes + 16 * size.intervals)
            }
        } finally { fixture.deleteRecursively() }
    }
    @Test fun legacyRestoreReplacesActivitiesOnlyAfterSuccessfulValidation(): Unit = runBlocking {
        val data = AppData(context, isolatedTestStorage = true)
        try {
            data.ready.await()
            data.addMusicActivity(MusicActivityDetails.manual(MusicActivityKind.LESSON, 0, 1_000, "UTC"))
            data.awaitWrites()
            val before = data.musicActivities.first().single()
            val legacy = ByteArrayOutputStream().also { out ->
                BackupArchive.writeV2(BackupSnapshot(ConfigCodec.encode(MetronomeConfig(tempo = Tempo(100))),
                    emptyList(), emptyList(), emptyList(), emptyList(), emptyMap()), out)
            }.toByteArray()
            data.importBackup({ legacy.copyOf(legacy.size / 2).inputStream() }) {}
            data.awaitWrites()
            assertEquals(before, data.musicActivities.first().single())
            data.importBackup({ legacy.inputStream() }) {}
            data.awaitWrites()
            assertTrue(data.musicActivities.first().isEmpty())
            assertTrue(data.database.musicActivities().all().isEmpty())
            assertEquals(100, data.settings.load().tempo.bpm)
        } finally { data.closeTestStorage() }
    }

}
