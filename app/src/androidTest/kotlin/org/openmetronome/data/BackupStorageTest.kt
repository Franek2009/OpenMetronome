package org.openmetronome.data

import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.openmetronome.domain.audio.SamplePcm
import org.openmetronome.domain.model.*
import java.io.*
import java.util.UUID

/** Real file-backed Room, DataStore and sample files; never production storage or playback. */
@RunWith(AndroidJUnit4::class)
class BackupStorageTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private suspend fun <T> fixture(action: suspend (AppData, File) -> T): T {
        val dir = File(context.cacheDir, "backup-test-${UUID.randomUUID()}").also { check(it.mkdirs()) }
        val data = AppData(context, dir)
        try { data.ready.await(); return action(data, dir) }
        finally { data.closeTestStorage(); dir.deleteRecursively() }
    }
    private fun bytes(snapshot: BackupSnapshot) = ByteArrayOutputStream().also { BackupArchive.write(snapshot, it) }.toByteArray()
    private fun sample(label: String, bpm: Int): BackupSnapshot {
        val id = UUID.randomUUID().toString()
        val config = ConfigCodec.encode(MetronomeConfig(tempo = Tempo(bpm), sound = SoundConfig(customSampleId = id)))
        val wav = SamplePcm.encodeCanonical(ShortArray(96).also { it[20] = 1000; it[21] = -1000 })
        return BackupSnapshot(config, listOf(Preset(label, label, config, 1, 2)),
            listOf(PracticeSession(label, 0, 125000, 125000, true, false, "UTC", config)),
            listOf(PracticeDay(label, "1970-01-01", 125000)), listOf(CustomSampleRecord(id, label, 3)), mapOf(id to wav))
    }
    private suspend fun imported(data: AppData, value: BackupSnapshot, apply: (MetronomeConfig) -> Unit = {}) {
        data.importBackup({ ByteArrayInputStream(bytes(value)) }, apply)
        data.awaitWrites()
    }
    private suspend fun exported(data: AppData): BackupSnapshot {
        val output = ByteArrayOutputStream()
        data.exportBackup { output }; data.awaitWrites()
        assertEquals("Kopia zapasowa została zapisana.", data.backupNotice.value)
        return BackupArchive.read(ByteArrayInputStream(output.toByteArray()))
    }
    private fun same(expected: BackupSnapshot, actual: BackupSnapshot) {
        assertEquals(expected.copy(sampleFiles = emptyMap()), actual.copy(sampleFiles = emptyMap()))
        assertEquals(expected.sampleFiles.keys, actual.sampleFiles.keys)
        expected.sampleFiles.forEach { (id, bytes) -> assertArrayEquals(bytes, actual.sampleFiles[id]) }
    }

    @Test fun completeRoundTripPersistsAfterReopeningAllStores(): Unit = runBlocking {
        fixture { data, dir ->
            val a = sample("A", 91); val b = sample("B", 137)
            imported(data, a); val archive = exported(data)
            imported(data, b); same(b, exported(data))
            imported(data, archive); same(a, exported(data))
            data.closeTestStorage()
            val reopened = AppData(context, dir)
            try {
                reopened.ready.await(); same(a, exported(reopened))
                assertTrue(reopened.samples.items.value.single().available)
                assertEquals(PracticeTotal(1, 125000), reopened.dao.total().first())
            } finally { reopened.closeTestStorage() }
        }
    }

    @Test fun failuresInFilesDatabaseSettingsAndUiRestorePreviousData(): Unit = runBlocking {
        fixture { data, _ ->
            val a = sample("A", 91); val b = sample("B", 137)
            imported(data, a)
            for (stage in listOf(BackupStage.FILES, BackupStage.DATABASE, BackupStage.SETTINGS)) {
                data.backupFault = { if (it == stage) throw IOException("injected $stage") }
                imported(data, b)
                assertTrue(data.backupNotice.value!!.contains("pozostają bez zmian"))
                assertFalse(data.recoveryRequired.value)
                data.backupFault = {}; same(a, exported(data))
            }
            var visible = ConfigCodec.decode(a.settings)
            imported(data, b) { visible = it; if (it.tempo.bpm == 137) throw IOException("UI") }
            assertEquals(91, visible.tempo.bpm)
            assertTrue(data.backupNotice.value!!.contains("pozostają bez zmian"))
            same(a, exported(data))
        }
    }

    @Test fun rollbackFailureRetainsRecoveryAndBlocksFurtherMutations(): Unit = runBlocking {
        fixture { data, dir ->
            val a = sample("A", 91); imported(data, a)
            data.backupFault = { if (it == BackupStage.SETTINGS || it == BackupStage.ROLLBACK_SETTINGS) throw IOException("injected $it") }
            imported(data, sample("B", 137))
            assertTrue(data.recoveryRequired.value)
            assertTrue(data.backupNotice.value!!.contains("Dane mogą być niepełne"))
            val recovery = File(dir, "restore-journal/active/before.ombak")
            same(a, recovery.inputStream().use(BackupArchive::read))
            assertFalse(data.reservePlayback())
            data.exportBackup { throw AssertionError("Recovery must block export") }
            assertTrue(data.backupNotice.value!!.contains("Dane mogą być niepełne"))
            var edited = false; data.editConfiguration { edited = true }; assertFalse(edited)
        }
    }

    @Test fun fileRollbackFailureRetainsOldDirectoryAndRecoveryArchive(): Unit = runBlocking {
        fixture { data, dir ->
            val a = sample("A", 91); imported(data, a)
            data.backupFault = { if (it == BackupStage.FILES || it == BackupStage.ROLLBACK_FILES) throw IOException("injected $it") }
            imported(data, sample("B", 137))
            assertTrue(data.recoveryRequired.value)
            val saved = File(dir, "restore-journal/active").walkTopDown().single { it.isDirectory && it.name == "old" }
            assertArrayEquals(a.sampleFiles.values.single(), File(saved, "${a.samples.single().id}.wav").readBytes())
            assertTrue(File(dir, "restore-journal/active/before.ombak").exists())
        }
    }

    @Test fun unreadableInvalidAndFailedExportLeaveDataUnchanged(): Unit = runBlocking {
        fixture { data, _ ->
            val a = sample("A", 91); imported(data, a)
            data.importBackup({ throw SecurityException("denied") }, {}); data.awaitWrites()
            assertTrue(data.backupNotice.value!!.contains("pozostają bez zmian"))
            data.importBackup({ ByteArrayInputStream(byteArrayOf(1, 2)) }, {}); data.awaitWrites()
            same(a, exported(data))
            data.exportBackup { object : OutputStream() {
                override fun write(value: Int) { throw IOException("full") }
            } }; data.awaitWrites()
            assertTrue(data.backupNotice.value!!.contains("Plik może być niepełny"))
            same(a, exported(data))
        }
    }

    @Test fun queuedWritesAreIncludedAndConcurrentActionsAreRejected(): Unit = runBlocking {
        fixture { data, _ ->
            val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
            data.operation { entered.complete(Unit); release.await() }
            entered.await()
            data.saveSettings(MetronomeConfig(tempo = Tempo(83)))
            data.savePreset("Queued", MetronomeConfig())
            val output = ByteArrayOutputStream(); data.exportBackup { output }
            var secondOpened = false
            try {
            assertTrue(data.backupWorking.value)
            assertFalse(data.reservePlayback())
            var edited = false; data.editConfiguration { edited = true }; assertFalse(edited)
            data.saveSettings(MetronomeConfig(tempo = Tempo(200)))
            data.savePreset("Rejected", MetronomeConfig())
            data.exportBackup { secondOpened = true; ByteArrayOutputStream() }
            } finally { release.complete(Unit) }
            data.awaitWrites()
            assertFalse(secondOpened)
            val restored = BackupArchive.read(ByteArrayInputStream(output.toByteArray()))
            assertEquals(83, ConfigCodec.decode(restored.settings).tempo.bpm)
            assertEquals(listOf("Queued"), restored.presets.map { it.name })
            assertTrue(data.reservePlayback())
            var opened = false; data.exportBackup { opened = true; ByteArrayOutputStream() }
            data.awaitWrites(); assertFalse(opened); data.playbackFinished()
            val lease = data.samples.acquire() // No hardware: verifies actual library exclusion.
            try { data.exportBackup { opened = true; ByteArrayOutputStream() }; data.awaitWrites(); assertFalse(opened) }
            finally { lease.close() }
        }
    }
    @Test fun cancelledOperationDoesNotKillWriterQueueOrChangeData(): Unit = runBlocking {
        fixture { data, _ ->
            val a = sample("A", 91); imported(data, a)
            data.importBackup({ throw CancellationException("cancelled read") }, {})
            data.awaitWrites()
            assertFalse(data.backupWorking.value)
            assertTrue(data.backupNotice.value!!.contains("anulowana"))
            same(a, exported(data))
        }
    }

    @Test fun applyingRestoredUiConfigurationDoesNotAdmitEditsFromOtherThreads(): Unit = runBlocking {
        fixture { data, _ ->
            val entered = java.util.concurrent.CountDownLatch(1)
            val release = java.util.concurrent.CountDownLatch(1)
            val incoming = sample("A", 91)
            data.importBackup({ ByteArrayInputStream(bytes(incoming)) }) {
                entered.countDown()
                check(release.await(5, java.util.concurrent.TimeUnit.SECONDS))
            }
            try {
                assertTrue(entered.await(5, java.util.concurrent.TimeUnit.SECONDS))
                var edited = false
                data.editConfiguration { edited = true }
                assertFalse(edited)
                assertFalse(data.reservePlayback())
            } finally { release.countDown() }
            data.awaitWrites()
            same(incoming, exported(data))
        }
    }

    @Test fun failedRuntimeRollbackIsRecoveredBeforeReopenedStorageIsReleased(): Unit = runBlocking {
        fixture { data, dir ->
            val a = sample("A", 91); imported(data, a)
            data.backupFault = { if (it == BackupStage.SETTINGS || it == BackupStage.ROLLBACK_SETTINGS) throw IOException("injected") }
            imported(data, sample("B", 137)); assertTrue(data.recoveryRequired.value)
            data.closeTestStorage()
            val reopened = AppData(context, dir)
            try {
                reopened.ready.await(); assertTrue(reopened.storageReady.value)
                assertTrue(reopened.backupNotice.value!!.startsWith("Cofnięto przerwane"))
                same(a, exported(reopened))
                assertFalse(File(dir, "restore-journal/active").exists())
            } finally { reopened.closeTestStorage() }
        }
    }

    @Test fun committedRestoreIsCompletedAfterReopenAndStartupBlocksConcurrentMutation(): Unit = runBlocking {
        fixture { data, dir ->
            imported(data, sample("A", 91))
            val b = sample("B", 137)
            data.restoreCheckpoint = { if (it == RestoreCheckpoint.COMMITTED) throw IOException("interrupted cleanup") }
            imported(data, b); assertTrue(data.recoveryRequired.value)
            data.closeTestStorage()
            val entered = java.util.concurrent.CountDownLatch(1)
            val release = java.util.concurrent.CountDownLatch(1)
            val reopened = AppData(context, dir) { stage ->
                if (stage == RestoreCheckpoint.RECOVERY_FILES_MOVED) {
                    entered.countDown(); check(release.await(10, java.util.concurrent.TimeUnit.SECONDS))
                }
            }
            try {
                try {
                    assertTrue(entered.await(10, java.util.concurrent.TimeUnit.SECONDS))
                    assertFalse(reopened.storageReady.value); assertFalse(reopened.ready.isCompleted)
                    assertFalse(reopened.reservePlayback())
                    var edited = false; reopened.editConfiguration { edited = true }; assertFalse(edited)
                    reopened.saveSettings(MetronomeConfig(tempo = Tempo(200)))
                } finally { release.countDown() }
                reopened.ready.await()
                assertTrue(reopened.backupNotice.value!!.startsWith("Dokończono zatwierdzone"))
                same(b, exported(reopened))
            } finally { reopened.closeTestStorage() }
        }
    }

    @Test fun unreadableRecoveryJournalPreservesMaterialsAndBlocksStartup(): Unit = runBlocking {
        fixture { data, dir ->
            val a = sample("A", 91); imported(data, a)
            data.backupFault = { if (it == BackupStage.SETTINGS || it == BackupStage.ROLLBACK_SETTINGS) throw IOException("injected") }
            imported(data, sample("B", 137)); data.closeTestStorage()
            val archive = File(dir, "restore-journal/active/before.ombak")
            archive.writeText("corrupt")
            val reopened = AppData(context, dir)
            try {
                try { reopened.ready.await(); fail("Unreadable journal must not release storage") }
                catch (_: IllegalArgumentException) { }
                assertFalse(reopened.storageReady.value); assertTrue(reopened.recoveryRequired.value)
                assertFalse(reopened.reservePlayback())
                assertEquals("corrupt", archive.readText())
                assertEquals(a.presets, reopened.dao.allPresets())
            } finally { reopened.closeTestStorage() }
        }
    }

}
