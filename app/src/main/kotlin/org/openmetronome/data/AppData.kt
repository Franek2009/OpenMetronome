package org.openmetronome.data

import android.content.Context
import androidx.datastore.preferences.core.*
import androidx.datastore.preferences.preferencesDataStore
import androidx.datastore.preferences.preferencesDataStoreFile
import androidx.datastore.core.DataStore
import androidx.room.Room
import androidx.room.withTransaction
import java.util.UUID
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.channels.Channel
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import java.io.File
import java.io.OutputStream
import java.io.InputStream
import org.openmetronome.domain.model.MetronomeConfig

private val Context.settingsStore by preferencesDataStore("metronome-settings")
private const val BACKUP_RECOVERY_NOTICE = "Nie udało się cofnąć wszystkich zmian. Dane mogą być niepełne. Zachowano pliki odzyskiwania; nie wykonuj kolejnych zmian."

class SettingsRepository(private val store: DataStore<Preferences>) {
    constructor(context: Context) : this(context.settingsStore)
    private val key = stringPreferencesKey("configuration_v1")
    suspend fun load(): MetronomeConfig = store.data.first()[key]?.let(ConfigCodec::decode) ?: MetronomeConfig()
    suspend fun save(config: MetronomeConfig) { store.edit { it[key] = ConfigCodec.encode(config) } }
}

/** Process-scoped repositories; service and UI share storage, never an audio worker. */
class AppData private constructor(
    context: Context, isolatedTestStorage: Boolean, testDirectory: File?, initialCheckpoint: (RestoreCheckpoint) -> Unit,
) {
    constructor(context: Context, isolatedTestStorage: Boolean = false) : this(context, isolatedTestStorage, null, {})
    internal constructor(context: Context, testDirectory: File, checkpoint: (RestoreCheckpoint) -> Unit = {}) :
        this(context, true, testDirectory, checkpoint)

    private val admission = Any()
    val storageReady = MutableStateFlow(false)
    val playbackBusy = MutableStateFlow(false)
    internal var restoreCheckpoint: (RestoreCheckpoint) -> Unit = initialCheckpoint
    val recoveryRequired = MutableStateFlow(false)
    internal var backupFault: (BackupStage) -> Unit = {}
    private var settingsWriteFailed = false
    private val actions = Channel<suspend () -> Unit>(Channel.UNLIMITED)
    private val applyingBackup = ThreadLocal<Boolean>()

    /** Synchronous admission keeps Start/config edits ordered against backup reservation. */
    fun editConfiguration(action: () -> Unit) = synchronized(admission) {
        if ((storageReady.value && !backupWorking.value && !recoveryRequired.value) || applyingBackup.get() == true) action()
    }
    fun reservePlayback(): Boolean = synchronized(admission) {
        if (!storageReady.value || backupWorking.value || recoveryRequired.value || sampleWorking.value || playbackBusy.value) false
        else { playbackBusy.value = true; true }
    }
    fun playbackFinished() = synchronized(admission) { playbackBusy.value = false }
    private fun submit(action: suspend () -> Unit): Boolean = synchronized(admission) {
        if (!storageReady.value || backupWorking.value || recoveryRequired.value) false else actions.trySend(action).isSuccess
    }
    internal suspend fun awaitWrites() {
        ready.await()
        val done = CompletableDeferred<Unit>()
        check(actions.trySend { done.complete(Unit) }.isSuccess)
        done.await()
    }
    internal suspend fun closeTestStorage() {
        check(isTestStorage)
        if (!scope.isActive) return
        try { if (!ready.isCancelled) withTimeout(10000) { awaitWrites() } }
        finally { scope.cancel(); scope.coroutineContext[Job]!!.join(); database.close() }
    }
    private val isTestStorage = isolatedTestStorage

    val error = MutableStateFlow<String?>(null)
    val exporting = MutableStateFlow(false)
    val exportNotice = MutableStateFlow<String?>(null)
    val backupWorking = MutableStateFlow(false)
    val backupNotice = MutableStateFlow<String?>(null)
    val sampleWorking = MutableStateFlow(false)
    val sampleNotice = MutableStateFlow<String?>(null)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val settingsFile = when {
        testDirectory != null -> File(testDirectory, "settings.preferences_pb")
        isolatedTestStorage -> File(context.cacheDir, "instrumentation-${UUID.randomUUID()}.preferences_pb")
        else -> context.preferencesDataStoreFile("metronome-settings")
    }
    val settings = if (isolatedTestStorage) SettingsRepository(PreferenceDataStoreFactory.create(scope = scope) { settingsFile })
        else SettingsRepository(context.applicationContext)
    val database = if (testDirectory != null) Room.databaseBuilder(context, PracticeDatabase::class.java,
        File(testDirectory, "practice.db").absolutePath).addMigrations(PracticeDatabase.MIGRATION_1_2).build()
        else if (isolatedTestStorage) Room.inMemoryDatabaseBuilder(context.applicationContext, PracticeDatabase::class.java).build()
        else Room.databaseBuilder(context.applicationContext, PracticeDatabase::class.java, "openmetronome.db")
            .addMigrations(PracticeDatabase.MIGRATION_1_2).build()
    private val sampleDirectory = if (isolatedTestStorage)
        if (testDirectory != null) File(testDirectory, "samples") else File(context.cacheDir, "sample-library-${UUID.randomUUID()}")
        else File(context.filesDir, "custom-samples")
    val samples = CustomSampleRepository(database, sampleDirectory)
    private val contextDirectory = testDirectory ?: if (isolatedTestStorage) context.cacheDir else context.filesDir
    private val journalRoot = File(contextDirectory, if (isolatedTestStorage && testDirectory == null)
        "restore-journal-${UUID.randomUUID()}" else "restore-journal")
    private val restoreJournal = RestoreJournal(journalRoot, ::syncDirectory)
    val dao = database.practice()
    val ready = CompletableDeferred<Unit>()
    val presets = dao.presets().catch { error.value = "Nie udało się odczytać presetów"; emit(emptyList()) }
    val sessions = dao.sessions().catch { error.value = "Nie udało się odczytać historii"; emit(emptyList()) }
    val days = dao.days().catch { error.value = "Nie udało się odczytać statystyk"; emit(emptyList()) }
    val total = dao.total().catch { error.value = "Nie udało się odczytać statystyk"; emit(PracticeTotal(0, 0)) }
    init {
        scope.launch {
            for (action in actions) {
                try { action() }
                catch (cancelled: CancellationException) { currentCoroutineContext().ensureActive() }
            }
        }
        scope.launch {
            try {
                samples.load()
                recoverRestore()
                dao.recoverInterrupted()
                storageReady.value = true
                ready.complete(Unit)
            } catch (e: Exception) {
                recoveryRequired.value = true
                error.value = "Nie udało się bezpiecznie przygotować danych. Zachowano materiały odzyskiwania; nie usuwaj danych aplikacji."
                ready.completeExceptionally(e)
            }
        }
    }
    fun saveSettings(config: MetronomeConfig) {
        submit {
            try { settings.save(config); settingsWriteFailed = false }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { settingsWriteFailed = true; error.value = "Nie udało się zapisać ustawień" }
        }
    }
    fun operation(action: suspend () -> Unit) {
        if (!submit {
            try { ready.await(); action() }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { error.value = "Nie udało się zapisać danych. Spróbuj ponownie." }
        }) error.value = if (recoveryRequired.value) BACKUP_RECOVERY_NOTICE else "Poczekaj na zakończenie kopii zapasowej."
    }
    fun savePreset(name: String, config: MetronomeConfig, existing: Preset? = null) = operation {
        require(name.trim().isNotEmpty() && name.trim().length <= 120)
        val now = System.currentTimeMillis()
        database.withTransaction {
            config.sound.customSampleId?.let { id ->
                check(dao.allSamples().any { it.id == id }) { "Brak wybranego sampla." }
            }
            dao.savePreset(Preset(existing?.id ?: UUID.randomUUID().toString(), name.trim(), ConfigCodec.encode(config), existing?.createdAt ?: now, now))
        }
    }
    fun renamePreset(preset: Preset, name: String) = operation {
        require(name.trim().isNotEmpty() && name.trim().length <= 120)
        dao.savePreset(preset.copy(name = name.trim(), updatedAt = System.currentTimeMillis()))
    }
    fun deletePreset(preset: Preset) = operation { dao.deletePreset(preset.id) }

    private fun sampleOperation(action: suspend () -> Unit) {
        synchronized(admission) {
            if (!storageReady.value || backupWorking.value || recoveryRequired.value || !sampleWorking.compareAndSet(false, true)) return
            sampleNotice.value = null
            actions.trySend {
                try { ready.await(); action() }
                catch (cancelled: CancellationException) { throw cancelled }
                catch (e: Exception) {
                    error.value = if (e.message == null || e.message == "Failed requirement." || e.message == "Check failed.")
                        "Nieobsługiwany lub uszkodzony sample. Wybierz WAV, MP3 lub OGG."
                        else e.message
                }
                finally { sampleWorking.value = false }
            }
        }
    }

    fun importSample(name: () -> String, decode: () -> org.openmetronome.domain.audio.SamplePcm.Audio, selected: (String) -> Unit) = sampleOperation {
        val (id, trimmed) = samples.importSample(name()) { decode() }
        sampleNotice.value = if (trimmed) "Zaimportowano sample. Usunięto ciszę lub przycięto dźwięk do 40 ms." else "Zaimportowano sample (do 40 ms)."
        withContext(Dispatchers.Main) { selected(id) }
    }

    fun renameSample(id: String, name: String) = sampleOperation { samples.rename(id, name) }
    fun deleteSample(id: String, deleted: () -> Unit) = sampleOperation {
        samples.delete(id)
        withContext(Dispatchers.Main) { deleted() }
    }

    /** Process-owned IO job survives screen changes. Only writes the document chosen by the user. */
    fun exportCsv(openOutput: () -> OutputStream) {
        synchronized(admission) {
            if (!storageReady.value || backupWorking.value || recoveryRequired.value || !exporting.compareAndSet(false, true)) return
            exportNotice.value = null
            actions.trySend {
                try {
                    ready.await()
                    val snapshot = dao.sessionsForExport()
                    openOutput().bufferedWriter(Charsets.UTF_8).use { SessionCsv.write(snapshot, it) }
                    exportNotice.value = "Zapisano CSV. Liczba sesji: ${snapshot.size}."
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    exportNotice.value = "Nie udało się zapisać CSV. Plik może być niepełny; dane aplikacji nie zostały zmienione."
                } finally {
                    exporting.value = false
                }
            }
        }
    }

    private fun backup(failureNotice: String = "Nie udało się przywrócić kopii. Bieżące dane pozostają bez zmian.", action: suspend () -> Unit) {
        synchronized(admission) {
            if (recoveryRequired.value) {
                backupNotice.value = BACKUP_RECOVERY_NOTICE
                error.value = BACKUP_RECOVERY_NOTICE
                return
            }
            if (!storageReady.value || playbackBusy.value || sampleWorking.value || exporting.value || backupWorking.value) {
                backupNotice.value = "Zatrzymaj metronom i poczekaj na zakończenie zapisu oraz innych operacji."
                return
            }
            backupWorking.value = true
            backupNotice.value = null
            actions.trySend {
                try { ready.await(); check(!settingsWriteFailed); action() }
                catch (_: BackupRecoveryException) {
                    recoveryRequired.value = true
                    backupNotice.value = BACKUP_RECOVERY_NOTICE
                    error.value = backupNotice.value
                }
                catch (cancelled: CancellationException) {
                    backupNotice.value = "Operacja kopii zapasowej została anulowana."
                    throw cancelled
                }
                catch (_: Exception) { backupNotice.value = failureNotice }
                finally { backupWorking.value = false }
            }
        }
    }

    private suspend fun snapshot(access: CustomSampleRepository.BackupAccess): BackupSnapshot {
        val records = dao.allSamples()
        return BackupSnapshot(ConfigCodec.encode(settings.load()), dao.allPresets(), dao.sessionsForExport(),
            dao.allPracticeDays(), records, access.files(records))
    }

    fun exportBackup(openOutput: () -> OutputStream) = backup("Nie udało się zapisać kopii zapasowej. Plik może być niepełny; dane aplikacji nie zostały zmienione.") {
        try {
            samples.backup {
                val snapshot = database.withTransaction { snapshot(this) }
                // Prepare and validate before touching the user's destination document.
                val bytes = java.io.ByteArrayOutputStream().also { BackupArchive.write(snapshot, it) }.toByteArray()
                openOutput().use { it.write(bytes) }
            }
            backupNotice.value = "Kopia zapasowa została zapisana."
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) {
            backupNotice.value = "Nie udało się zapisać kopii zapasowej. Plik może być niepełny; dane aplikacji nie zostały zmienione."
        }
    }

    fun importBackup(openInput: () -> InputStream, applied: (MetronomeConfig) -> Unit) = backup {
        val incoming = openInput().use(BackupArchive::read)
        val config = ConfigCodec.decode(incoming.settings)
        samples.backup {
            val previous = database.withTransaction { snapshot(this) }
            val oldConfig = ConfigCodec.decode(previous.settings)
            var journalPrepared = false
            var newDataApplied = false
            try {
                restoreJournal.prepare(previous, incoming)
                journalPrepared = true
                restoreCheckpoint(RestoreCheckpoint.PREPARED)
                replace(incoming, backupFault, restoreJournal.active, restoreCheckpoint) {
                    withContext(NonCancellable) {
                        try {
                            database.withTransaction {
                                replaceRows(incoming)
                                backupFault(BackupStage.DATABASE)
                                restoreCheckpoint(RestoreCheckpoint.DATABASE_CHANGED)
                                backupFault(BackupStage.SETTINGS)
                                settings.save(config)
                                restoreCheckpoint(RestoreCheckpoint.SETTINGS_WRITTEN)
                                applyConfiguration(config, applied)
                            }
                            restoreCheckpoint(RestoreCheckpoint.DATABASE_COMMITTED)
                        } catch (error: Exception) {
                            try {
                                backupFault(BackupStage.ROLLBACK_SETTINGS)
                                settings.save(oldConfig)
                                applyConfiguration(oldConfig, applied)
                            } catch (rollback: Exception) {
                                error.addSuppressed(rollback)
                                throw BackupRecoveryException(error)
                            }
                            throw error
                        }
                    }
                }
                newDataApplied = true
                withContext(NonCancellable) {
                    syncStores()
                    restoreCheckpoint(RestoreCheckpoint.STORES_SYNCED)
                    restoreJournal.markCommitted()
                    restoreCheckpoint(RestoreCheckpoint.COMMITTED)
                    restoreJournal.finish()
                    restoreCheckpoint(RestoreCheckpoint.JOURNAL_RETIRED)
                }
            } catch (error: Exception) {
                if (error is BackupRecoveryException || newDataApplied || (!journalPrepared && restoreJournal.active.exists()))
                    throw BackupRecoveryException(error)
                try {
                    // Runtime rollback already restored the previous stores. Durably retire
                    // the undo journal only after that state has reached storage as well.
                    if (journalPrepared) syncStores()
                    restoreJournal.finish()
                } catch (rollback: Exception) {
                    error.addSuppressed(rollback)
                    throw BackupRecoveryException(error)
                }
                throw error
            }
        }
        backupNotice.value = "Kopia została przywrócona: ${incoming.sessions.size} sesji, ${incoming.presets.size} presetów."
    }

    private suspend fun replaceRows(snapshot: BackupSnapshot) {
        dao.deleteAllPracticeDays(); dao.deleteAllSessions(); dao.deleteAllPresets(); dao.deleteAllSamples()
        dao.insertSamples(snapshot.samples); dao.insertPresets(snapshot.presets)
        dao.insertSessions(snapshot.sessions); dao.insertPracticeDays(snapshot.days)
    }

    private suspend fun recoverRestore() {
        val pending = restoreJournal.pending() ?: return
        samples.backup {
            replace(pending.snapshot, workDirectory = restoreJournal.active, checkpoint = { stage ->
                if (stage == RestoreCheckpoint.NEW_FILES_MOVED) restoreCheckpoint(RestoreCheckpoint.RECOVERY_FILES_MOVED)
            }) {
                database.withTransaction {
                    replaceRows(pending.snapshot)
                    settings.save(ConfigCodec.decode(pending.snapshot.settings))
                    restoreCheckpoint(RestoreCheckpoint.RECOVERY_DATABASE_CHANGED)
                }
            }
            syncStores()
            restoreCheckpoint(RestoreCheckpoint.RECOVERY_STORES_SYNCED)
            restoreJournal.finish()
        }
        backupNotice.value = if (pending.committed) "Dokończono zatwierdzone przywracanie kopii po przerwaniu aplikacji."
            else "Cofnięto przerwane przywracanie kopii. Zachowano poprzednie dane."
    }

    private fun syncStores() {
        val sqlite = database.openHelper.writableDatabase
        val path = sqlite.path
        if (path != null && path != ":memory:") {
            sqlite.query("PRAGMA wal_checkpoint(FULL)").use { cursor ->
                check(cursor.moveToFirst() && cursor.getInt(0) == 0) { "Database checkpoint is busy" }
            }
            val dbFile = File(path)
            for (file in listOf(dbFile, File(path + "-wal"))) syncFile(file)
            syncDirectory(dbFile.parentFile!!)
        }
        syncFile(settingsFile)
        if (settingsFile.parentFile!!.exists()) syncDirectory(settingsFile.parentFile!!)
        sampleDirectory.listFiles()?.filter { it.isFile }?.forEach(::syncFile)
        if (sampleDirectory.exists()) syncDirectory(sampleDirectory)
        syncDirectory(sampleDirectory.parentFile!!)
    }

    private fun syncFile(file: File) {
        if (file.exists()) java.io.FileInputStream(file).use { it.fd.sync() }
    }
    private fun syncDirectory(directory: File) {
        val descriptor = android.system.Os.open(directory.absolutePath,
            android.system.OsConstants.O_RDONLY, 0)
        try {
            check(android.system.OsConstants.S_ISDIR(android.system.Os.fstat(descriptor).st_mode))
            android.system.Os.fsync(descriptor)
        } finally { android.system.Os.close(descriptor) }
    }

    private suspend fun applyConfiguration(config: MetronomeConfig, applied: (MetronomeConfig) -> Unit) {
        withContext(Dispatchers.Main) {
            applyingBackup.set(true)
            try { applied(config) } finally { applyingBackup.remove() }
        }
    }
}

internal enum class BackupStage { FILES, DATABASE, SETTINGS, ROLLBACK_FILES, ROLLBACK_SETTINGS }
