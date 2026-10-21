package org.openmetronome.data

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import android.content.Context
import java.io.File
import java.util.UUID
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.openmetronome.domain.model.*
import org.openmetronome.audio.*

/** No Activity, audio, vibrator, or access to the user's production database/preferences. */
@RunWith(AndroidJUnit4::class)
class StorageTest {
    @Test fun allFortyThreeSoundsPersistInSettingsAndPresetsWithoutHardware() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val db = Room.inMemoryDatabaseBuilder(context, PracticeDatabase::class.java).build()
        val file = File(context.cacheDir, "sound-bank-${UUID.randomUUID()}.preferences_pb")
        suspend fun withStore(action: suspend (SettingsRepository) -> Unit) {
            val owner = SupervisorJob()
            val store = PreferenceDataStoreFactory.create(scope = CoroutineScope(owner + Dispatchers.IO)) { file }
            try { action(SettingsRepository(store)) } finally { owner.cancelAndJoin() }
        }
        try {
            for (sound in ClickSound.entries) {
                val config = MetronomeConfig(sound = SoundConfig(37, sound))
                withStore { it.save(config) }
                withStore { assertEquals(config, it.load()) }
                db.practice().savePreset(Preset(sound.name, "Fixture ${sound.name}", ConfigCodec.encode(config), 1, 1))
            }
            val restored = db.practice().presets().first().map { ConfigCodec.decode(it.config).sound }.toSet()
            assertEquals(ClickSound.entries.map { SoundConfig(37, it) }.toSet(), restored)
            assertEquals(43, restored.size)
        } finally { db.close(); file.delete() } // Only this UUID-named test fixture.
    }

    @Test fun exportReadsAllSessionsAndDoesNotMutateHistory() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val db = Room.inMemoryDatabaseBuilder(context, PracticeDatabase::class.java).build()
        try {
            val dao = db.practice()
            val config = MetronomeConfig().cycleBeatAccent(2)
            val payload = ConfigCodec.encode(config)
            repeat(151) { index ->
                dao.saveSession(PracticeSession("fixture-$index", index.toLong(), index + 1000L, 1000, index != 150, false, "UTC", payload))
            }
            val before = dao.total().first()
            val snapshot = dao.sessionsForExport()
            assertEquals(100, dao.sessions().first().size)
            assertEquals(151, snapshot.size)
            val output = java.io.StringWriter()
            SessionCsv.write(snapshot, output)
            assertEquals(152, output.toString().count { it == '\n' })
            assertEquals(before, dao.total().first())
            assertEquals(config, ConfigCodec.decode(snapshot.first().config))
            assertFalse(dao.sessionsForExport().last().finished)
        } finally { db.close() }
    }
    @Test fun fileDatabaseSurvivesReopenAndRecoveryDoesNotDuplicateSessions() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val file = File(context.cacheDir, "room-reopen-${UUID.randomUUID()}.db")
        val config = MetronomeConfig(sound = SoundConfig(37, ClickSound.WOOD)).cycleBeatAccent(1)
        val preset = Preset("fixture-preset", "Fixture", ConfigCodec.encode(config), 1, 1)
        val session = PracticeSession("fixture-session", 0, 1000, 1000, false, false, "UTC", preset.config)
        suspend fun withDatabase(action: suspend (PracticeDatabase) -> Unit) {
            val db = Room.databaseBuilder(context, PracticeDatabase::class.java, file.absolutePath).build()
            try { action(db) } finally { db.close() }
        }
        try {
            withDatabase { db ->
                db.practice().savePreset(preset)
                db.practice().checkpoint(session, listOf(PracticeDay(session.id, "1970-01-01", 1000)))
                db.practice().checkpoint(session.copy(durationMs = 2000),
                    listOf(PracticeDay(session.id, "1970-01-01", 2000)))
            }
            withDatabase { db ->
                val dao = db.practice()
                assertEquals(preset, dao.presets().first().single())
                assertEquals(config, ConfigCodec.decode(dao.presets().first().single().config))
                repeat(2) { dao.recoverInterrupted() }
                assertEquals(PracticeTotal(1, 2000), dao.total().first())
                assertEquals(2000L, dao.days().first().single().durationMs)
                val restored = dao.sessions().first().single()
                assertTrue(restored.finished); assertTrue(restored.interrupted)
                assertEquals(session.id, restored.id)
            }
            withDatabase { db ->
                assertEquals(PracticeTotal(1, 2000), db.practice().total().first())
                assertTrue(db.practice().sessions().first().single().interrupted)
            }
        } finally { context.deleteDatabase(file.absolutePath) } // Only this UUID-named cache fixture.
    }

    @Test fun presetsAndSessionCheckpointsAreAtomicAndRecoverable() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val db = Room.inMemoryDatabaseBuilder(context, PracticeDatabase::class.java).build()
        try {
            val dao = db.practice()
            val preset = Preset("p", "Trening", ConfigCodec.encode(MetronomeConfig()), 1, 1)
            dao.savePreset(preset)
            dao.savePreset(preset.copy(name = "Nowa nazwa"))
            assertEquals("Nowa nazwa", dao.presets().first().single().name)
            val session = PracticeSession("s", 0, 1000, 1000, false, false, "UTC", preset.config)
            dao.checkpoint(session, listOf(PracticeDay("s", "1970-01-01", 1000)))
            dao.checkpoint(session.copy(durationMs = 2000), listOf(PracticeDay("s", "1970-01-01", 2000)))
            assertEquals(PracticeTotal(1, 2000), dao.total().first())
            assertEquals(2000L, dao.days().first().single().durationMs)
            dao.recoverInterrupted()
            assertTrue(dao.sessions().first().single().interrupted)
            dao.deletePreset("p")
            assertTrue(dao.presets().first().isEmpty())
            assertEquals(1L, dao.total().first().sessions)
        } finally { db.close() }
    }
    @Test fun settingsLoadDoesNotStartPlaybackAndSurvivesRepositoryRecreation() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val store = PreferenceDataStoreFactory.create(scope = scope) { File(context.cacheDir, "settings-test-${UUID.randomUUID()}.preferences_pb") }
        try {
            val config = MetronomeConfig(silence = SilenceConfig(100), sound = SoundConfig(37, ClickSound.WOOD))
            SettingsRepository(store).save(config)
            assertEquals(config, SettingsRepository(store).load())
        } finally { scope.cancel() }
    }

    @Test fun settingsSurviveClosingAndReopeningTheUnderlyingStore() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val file = File(context.cacheDir, "reopened-${UUID.randomUUID()}.preferences_pb")
        val config = MetronomeConfig(tempo = Tempo(137), silence = SilenceConfig(50, true, 3, 2),
            progression = SilenceProgression(true, 7, 13), ramp = TempoRamp(true, 300, 20, 17, 3),
            rhythm = RhythmConfig(Subdivision.TRIPLETS, ClickAccent.entries), sound = SoundConfig(23, ClickSound.SHORT))
        suspend fun withStore(action: suspend (SettingsRepository) -> Unit) {
            val owner = SupervisorJob()
            val store = PreferenceDataStoreFactory.create(scope = CoroutineScope(owner + Dispatchers.IO)) { file }
            try { action(SettingsRepository(store)) } finally { owner.cancelAndJoin() }
        }
        try {
            withStore { it.save(config) }
            withStore { assertEquals(config, it.load()) }
        } finally { file.delete() } // Only this test's UUID-named cache fixture.
    }

    @Test fun unsupportedSettingsRemainIntactAfterReadFailure() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val owner = SupervisorJob()
        val file = File(context.cacheDir, "unsupported-${UUID.randomUUID()}.preferences_pb")
        val store = PreferenceDataStoreFactory.create(scope = CoroutineScope(owner + Dispatchers.IO)) { file }
        val key = stringPreferencesKey("configuration_v1")
        try {
            store.edit { it[key] = "version=999" }
            try {
                SettingsRepository(store).load()
                fail("Unsupported settings must not silently become defaults")
            } catch (_: IllegalArgumentException) { /* Existing bytes must be preserved. */ }
            assertEquals("version=999", store.data.first()[key])
        } finally { owner.cancelAndJoin(); file.delete() }
    }

    @Test fun veryShortCompletedSessionsAreRecordedOnceEvenWithoutPlayingSnapshot() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val data = AppData(context, isolatedTestStorage = true)
        data.ready.await()
        val recorder = SessionRecorder(data)
        val state = MetronomeState(audio = AudioEngineState(PlaybackStatus.STOPPED,
            diagnostics = AudioDiagnostics(48000, 256, 1024, 1024, 0, framesPlayed = 48, sessionId = 1)))
        recorder.accept(state)
        recorder.accept(state)
        assertEquals(PracticeTotal(1, 1), data.dao.total().first())
        recorder.accept(state.copy(audio = state.audio.copy(diagnostics = state.audio.diagnostics!!.copy(sessionId = 2))))
        assertEquals(PracticeTotal(2, 2), data.dao.total().first())
    }
}
