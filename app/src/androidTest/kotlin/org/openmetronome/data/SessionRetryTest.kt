package org.openmetronome.data

import androidx.test.core.app.ApplicationProvider
import android.content.Context
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.openmetronome.audio.*
import org.openmetronome.domain.model.MusicActivityDetails
import org.openmetronome.domain.model.MusicActivityKind
import java.io.ByteArrayOutputStream

class SessionRetryTest {
    @Test fun reopeningStorageRecoversDurablyQueuedFinalSession() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val directory = java.io.File(context.cacheDir, "session-restart-${java.util.UUID.randomUUID()}").also { it.mkdirs() }
        val data = AppData(context, directory)
        val activity = MusicActivityDetails.manual(MusicActivityKind.LESSON, 0, 30_000, "UTC")
        try {
            data.ready.await()
            data.addMusicActivity(activity)
            data.awaitWrites()
            data.sessionCheckpoint = { _, _ -> throw java.io.IOException("database full") }
            SessionRecorder(data).accept(MetronomeState(audio = AudioEngineState(PlaybackStatus.STOPPED,
                diagnostics = AudioDiagnostics(48000, 256, 1024, 48000, 0, framesPlayed = 48000, sessionId = 9))))
            assertTrue(data.sessionWriteFailed.value)
        } finally { data.closeTestStorage() }
        val reopened = AppData(context, directory)
        try {
            reopened.ready.await()
            assertFalse(reopened.sessionWriteFailed.value)
            val session = reopened.dao.sessionsForExport().single()
            assertEquals(1000L, session.durationMs)
            assertTrue(session.finished)
            val storedActivity = reopened.musicActivities.first().single()
            assertEquals(activity, storedActivity.details)
            val output = ByteArrayOutputStream()
            reopened.exportBackup { output }; reopened.awaitWrites()
            val snapshot = BackupArchive.read(output.toByteArray().inputStream())
            assertEquals(session, snapshot.sessions.single())
            assertEquals(storedActivity, snapshot.musicActivities.single())
        } finally { reopened.closeTestStorage(); directory.deleteRecursively() }
    }

    @Test fun failedWriteBlocksBothExportsThenRetryPersistsWholeSession() = runBlocking {
        val data = AppData(ApplicationProvider.getApplicationContext<Context>(), isolatedTestStorage = true)
        try {
            data.ready.await()
            val original = data.sessionCheckpoint
            data.sessionCheckpoint = { _, _ -> throw java.io.IOException("disk full") }
            val recorder = SessionRecorder(data)
            recorder.accept(MetronomeState(audio = AudioEngineState(PlaybackStatus.STOPPED,
                diagnostics = AudioDiagnostics(48000, 256, 1024, 48000, 0, framesPlayed = 48000, sessionId = 1))))
            assertTrue(data.sessionWriteFailed.value)
            assertTrue("Disk errors do not permanently reserve playback", data.reservePlayback())
            data.playbackFinished()
            var backupOpened = false; var csvOpened = false
            data.exportBackup { backupOpened = true; ByteArrayOutputStream() }; data.awaitWrites()
            data.exportCsv { csvOpened = true; ByteArrayOutputStream() }; data.awaitWrites()
            assertFalse(backupOpened); assertFalse(csvOpened)
            data.sessionCheckpoint = original
            val output = ByteArrayOutputStream()
            data.exportBackup { output }; data.awaitWrites()
            assertFalse(data.sessionWriteFailed.value)
            val restored = BackupArchive.read(output.toByteArray().inputStream())
            assertEquals(1000L, restored.sessions.single().durationMs)
            assertTrue(restored.sessions.single().finished)
            assertEquals(1000L, restored.days.sumOf { it.durationMs })
        } finally { data.closeTestStorage() }
    }
}
