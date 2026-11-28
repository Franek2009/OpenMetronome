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
import java.time.Instant
import java.time.ZoneId
import org.openmetronome.domain.model.MetronomeConfig
import org.openmetronome.domain.model.MusicActivity
import org.openmetronome.domain.model.MusicActivityDetails

private val Context.settingsStore by preferencesDataStore("metronome-settings")
private const val BACKUP_RECOVERY_NOTICE = "Nie udało się cofnąć wszystkich zmian. Dane mogą być niepełne. Zachowano pliki odzyskiwania; nie wykonuj kolejnych zmian."

class SettingsRepository(private val store: DataStore<Preferences>) {
    constructor(context: Context) : this(context.settingsStore)
    private val key = stringPreferencesKey("configuration_v1")
    suspend fun load(): MetronomeConfig = store.data.first()[key]?.let(ConfigCodec::decode) ?: MetronomeConfig()
    suspend fun save(config: MetronomeConfig) { store.edit { it[key] = ConfigCodec.encode(config) } }
    private val awakeKey = booleanPreferencesKey("fullscreen_awake")
    private val motionKey = booleanPreferencesKey("reduce_motion")
    private val musicScopeKey = booleanPreferencesKey("music_statistics_scope")
    private val sessionThresholdKey = intPreferencesKey("minimum_session_seconds")
    private val dayThresholdKey = intPreferencesKey("active_day_minutes")
    private val musicZoneKey = stringPreferencesKey("music_statistics_zone")
    private val musicDayKey = intPreferencesKey("music_active_day_minutes")
    private val musicGoalKey = intPreferencesKey("music_weekly_goal_days")
    private val groupingGapKey = stringPreferencesKey("practice_grouping_gap")
    val display = store.data.map { PracticeDisplayPreferences(it[awakeKey] ?: false, it[motionKey] ?: false) }
    val statistics = store.data.map { StatisticsPreferences(it[sessionThresholdKey] ?: 20, it[dayThresholdKey] ?: 5) }
    val musicScope = store.data.map { it[musicScopeKey] ?: false }
    suspend fun saveMusicScope(value: Boolean) { store.edit { it[musicScopeKey] = value } }
    val musicStatistics = store.data.map { it.musicStatistics() }.distinctUntilChanged()
    val groupingGap = store.data.map { it[groupingGapKey]?.let(PracticeGroupingGap::valueOf) ?: PracticeGroupingGap.DEFAULT }
    suspend fun saveDisplay(value: PracticeDisplayPreferences) {
        store.edit { it[awakeKey] = value.keepFullscreenAwake; it[motionKey] = value.reduceMotion }
    }
    suspend fun saveStatistics(value: StatisticsPreferences) {
        store.edit { it[sessionThresholdKey] = value.minimumSessionSeconds; it[dayThresholdKey] = value.activeDayMinutes }
    }
    suspend fun saveMusicStatistics(value: MusicStatisticsPreferences) { store.edit { it.putMusicStatistics(value) } }
    suspend fun saveGroupingGap(value: PracticeGroupingGap) { store.edit { it[groupingGapKey] = value.name } }

    private fun Preferences.musicStatistics() = this[musicZoneKey]?.let { zone ->
        MusicStatisticsPreferences(ZoneId.of(zone), this[musicDayKey] ?: 5, this[musicGoalKey]?.takeUnless { it == 0 })
    }

    private fun MutablePreferences.putMusicStatistics(value: MusicStatisticsPreferences?) {
        if (value == null) {
            remove(musicZoneKey); remove(musicDayKey); remove(musicGoalKey)
        } else {
            this[musicZoneKey] = value.zone.id
            this[musicDayKey] = value.activeDayMinutes
            this[musicGoalKey] = value.weeklyGoalDays ?: 0
        }
    }

    suspend fun preferencesSnapshot(): String {
        val prefs = store.data.first()
        return AppPreferencesCodec.encode(AppPreferences(PracticeDisplayPreferences(prefs[awakeKey] ?: false, prefs[motionKey] ?: false),
            StatisticsPreferences(prefs[sessionThresholdKey] ?: 20, prefs[dayThresholdKey] ?: 5), prefs.musicStatistics(),
            prefs[groupingGapKey]?.let(PracticeGroupingGap::valueOf) ?: PracticeGroupingGap.DEFAULT))
    }
    suspend fun restorePreferences(payload: String?) {
        if (payload == null) return // Old archives have no new display/statistics choices.
        val value = AppPreferencesCodec.decode(payload)
        store.edit {
            it[awakeKey] = value.display.keepFullscreenAwake; it[motionKey] = value.display.reduceMotion
            it[sessionThresholdKey] = value.statistics.minimumSessionSeconds; it[dayThresholdKey] = value.statistics.activeDayMinutes
            it.putMusicStatistics(value.musicStatistics)
            if (value.groupingGap != null) it[groupingGapKey] = value.groupingGap.name else it.remove(groupingGapKey)
        }
    }
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
        if ((storageReady.value && !backupLiveReserved && !recoveryRequired.value) || applyingBackup.get() == true) action()
    }
    fun reservePlayback(): Boolean = synchronized(admission) {
        if (!storageReady.value || backupLiveReserved || exporting.value || recoveryRequired.value || sampleWorking.value || playbackBusy.value) false
        else { playbackBusy.value = true; true }
    }
    fun playbackFinished() = synchronized(admission) { playbackBusy.value = false }
    private fun submit(action: suspend () -> Unit): Boolean = synchronized(admission) {
        if (!storageReady.value || backupLiveReserved || recoveryRequired.value) false else actions.trySend(action).isSuccess
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
    val diagnosticReportDirectory: File = if (isolatedTestStorage)
        File(testDirectory ?: context.cacheDir, "dev-reports-test-${UUID.randomUUID()}")
        else File(context.filesDir, "dev-reports")

    val error = MutableStateFlow<String?>(null)
    internal val activityEditorVisible = MutableStateFlow(false)
    val exporting = MutableStateFlow(false)
    val exportNotice = MutableStateFlow<String?>(null)
    val backupWorking = MutableStateFlow(false)
    private val backupDelivering = MutableStateFlow(false)
    internal val backupLiveReserved: Boolean get() = backupWorking.value && !backupDelivering.value
    internal val backupReservation = combine(backupWorking, backupDelivering) { working, delivering -> working && !delivering }
    private val pendingBackupDocument = MutableStateFlow<Int?>(null)
    internal val backupDocumentRequest = pendingBackupDocument.asStateFlow()
    private var backupDocumentResult: CompletableDeferred<(() -> OutputStream)?>? = null
    internal var backupExportPhase: (BackupExportPhase) -> Unit = {}
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
        File(testDirectory, "practice.db").absolutePath).addMigrations(PracticeDatabase.MIGRATION_1_2, PracticeDatabase.MIGRATION_2_3,
            PracticeDatabase.MIGRATION_3_4, PracticeDatabase.MIGRATION_4_5).build()
        else if (isolatedTestStorage) Room.inMemoryDatabaseBuilder(context.applicationContext, PracticeDatabase::class.java).build()
        else Room.databaseBuilder(context.applicationContext, PracticeDatabase::class.java, "openmetronome.db")
            .addMigrations(PracticeDatabase.MIGRATION_1_2, PracticeDatabase.MIGRATION_2_3,
                PracticeDatabase.MIGRATION_3_4, PracticeDatabase.MIGRATION_4_5).build()
    private val sampleDirectory = if (isolatedTestStorage)
        if (testDirectory != null) File(testDirectory, "samples") else File(context.cacheDir, "sample-library-${UUID.randomUUID()}")
        else File(context.filesDir, "custom-samples")
    val samples = CustomSampleRepository(database, sampleDirectory)
    private val contextDirectory = testDirectory ?: if (isolatedTestStorage) context.cacheDir else context.filesDir
    private val journalRoot = File(contextDirectory, if (isolatedTestStorage && testDirectory == null)
        "restore-journal-${UUID.randomUUID()}" else "restore-journal")
    private val restoreJournal = RestoreJournal(journalRoot, ::syncDirectory)
    private val backupStagingRoot = File(contextDirectory, if (isolatedTestStorage && testDirectory == null)
        "backup-staging-${UUID.randomUUID()}" else "backup-staging")
    val dao = database.practice()
    private val musicDao = database.musicActivities()
    private val groupingDao = database.grouping()

    val musicActivities: Flow<List<MusicActivity>> = flow {
        ready.await()
        emitAll(musicDao.active().map { rows -> rows.map(StoredMusicActivity::toDomain) })
    }

    fun addMusicActivity(details: MusicActivityDetails) {
        val snapshot = details.copy(intervals = details.intervals.toList())
        operation {
            val now = System.currentTimeMillis()
            musicDao.insert(MusicActivity(UUID.randomUUID().toString(), snapshot, now, now))
        }
    }

    fun editMusicActivity(id: String, details: MusicActivityDetails) {
        val snapshot = details.copy(intervals = details.intervals.toList())
        operation { musicDao.edit(id, snapshot, System.currentTimeMillis()) }
    }

    fun deleteMusicActivity(id: String) = operation { musicDao.delete(id, System.currentTimeMillis()) }

    suspend fun saveMusicActivity(details: MusicActivityDetails, id: String? = null) {
        val snapshot = details.copy(intervals = details.intervals.toList())
        val result = CompletableDeferred<Result<Unit>>()
        check(submit {
            try {
                ready.await()
                val now = System.currentTimeMillis()
                if (id == null) musicDao.insert(MusicActivity(UUID.randomUUID().toString(), snapshot, now, now))
                else musicDao.edit(id, snapshot, now)
                result.complete(Result.success(Unit))
            } catch (cancelled: CancellationException) {
                result.cancel(cancelled)
                throw cancelled
            } catch (error: Exception) {
                result.complete(Result.failure(error))
            }
        }) { "Zapis jest obecnie niedostępny" }
        result.await().getOrThrow()
    }
    internal var sessionCheckpoint: suspend (PracticeSession, List<PracticeDay>) -> Unit = { session, days ->
        dao.checkpoint(session, days)
        syncDatabase()
    }
    private val sessionWrites = SessionWriteQueue(File(contextDirectory, if (isolatedTestStorage && testDirectory == null)
        "session-writes-${UUID.randomUUID()}" else "session-writes"), ::syncDirectory) { session, days -> sessionCheckpoint(session, days) }
    val sessionWriteFailed = MutableStateFlow(false)

    /** An accepted snapshot stays in process memory even if both journal and database are unwritable. */
    internal suspend fun recordSession(session: PracticeSession) = withContext(NonCancellable) {
        scope.async {
            ready.await()
            try { sessionWrites.record(session); sessionWriteFailed.value = false }
            catch (_: Exception) {
                sessionWriteFailed.value = true
                error.value = "Nie udało się zapisać całej sesji. Zapis zostanie ponowiony; eksport pozostaje zablokowany. Nie usuwaj danych aplikacji."
            }
        }.await()
    }

    internal fun launchSessionWork(action: suspend CoroutineScope.() -> Unit): Job = scope.launch(Dispatchers.Main.immediate, block = action)

    private suspend fun retrySessionWrites(recovering: Boolean = false) {
        try { sessionWrites.retry(recovering); sessionWriteFailed.value = false }
        catch (error: Exception) { sessionWriteFailed.value = true; throw error }
    }

    val ready = CompletableDeferred<Unit>()
    val presets = dao.presets().catch { error.value = "Nie udało się odczytać presetów"; emit(emptyList()) }
    val sessions = dao.sessions().catch { error.value = "Nie udało się odczytać historii"; emit(emptyList()) }
    val days = dao.days().catch { error.value = "Nie udało się odczytać statystyk"; emit(emptyList()) }
    fun dailyPractice(date: java.time.LocalDate): Flow<DailyPractice> = observeDailyPractice(date, flow {
        ready.await()
        emitAll(dao.durationOn(date.toString()))
    })
    fun statisticsHistory(): Flow<StatisticsHistory> = flow {
        emit(StatisticsHistory.Loading)
        ready.await()
        emitAll(dao.statisticsSessions().map<List<PracticeSession>, StatisticsHistory> { StatisticsHistory.Ready(it) })
    }.catch { error -> if (error is CancellationException) throw error else emit(StatisticsHistory.Unavailable) }
    @OptIn(ExperimentalCoroutinesApi::class)
    fun observeMusicStatistics(now: Flow<Instant>, retry: Flow<Unit> = emptyFlow()): Flow<MusicStatisticsState> = flow {
        val defaults = MusicStatisticsPreferences()
        emitAll(retry.onStart { emit(Unit) }.flatMapLatest { musicStatisticsAttempt(now, defaults) })
    }.flowOn(Dispatchers.Default)

    @OptIn(ExperimentalCoroutinesApi::class)
    private fun musicStatisticsAttempt(now: Flow<Instant>, defaults: MusicStatisticsPreferences): Flow<MusicStatisticsState> = flow {
        emit(MusicStatisticsState.Loading)
        ready.await()
        emitAll(combine(backupReservation, recoveryRequired) { reserved, recovery ->
            when {
                recovery -> MusicStatisticsState.Unavailable
                reserved -> MusicStatisticsState.Loading
                else -> null
            }
        }.distinctUntilChanged().flatMapLatest { blocked ->
            if (blocked != null) flowOf(blocked)
            else combine(musicDao.statisticsRanges().distinctUntilChanged(), settings.musicStatistics, now) { ranges, prefs, instant ->
                val owner = currentCoroutineContext()
                MusicStatisticsState.Ready(MusicStatisticsCalculator.calculate(ranges, prefs ?: defaults, instant) { owner.ensureActive() })
            }
        })
    }.catch { error ->
        if (error is CancellationException) throw error
        emit(MusicStatisticsState.Unavailable)
    }
    suspend fun calculateAnalytics(sessions: List<PracticeSession>, preferences: StatisticsPreferences, today: java.time.LocalDate): PracticeAnalytics = withContext(Dispatchers.IO) {
        val owner = currentCoroutineContext()
        PracticeAnalyticsCalculator.calculate(sessions, preferences, today) { session ->
            owner.ensureActive()
            dao.timelineForStatistics(session.id, session.durationMs, session.checkpointAt)?.let {
                runCatching { SessionTimelineCodec.decode(it, session.durationMs) }.getOrNull()
            }
        }
    }
    suspend fun sessionTimeline(session: PracticeSession): String? = withContext(Dispatchers.IO) {
        dao.timelineForStatistics(session.id, session.durationMs, session.checkpointAt)
    }
    val total = dao.total().catch { error.value = "Nie udało się odczytać statystyk"; emit(PracticeTotal(0, 0)) }
    val displayPreferences = settings.display.catch { error.value = "Nie udało się odczytać ustawień ekranu"; emit(PracticeDisplayPreferences()) }
    fun statisticsPreferences(): Flow<Result<StatisticsPreferences>?> = settings.statistics
        .map<StatisticsPreferences, Result<StatisticsPreferences>?> { Result.success(it) }
        .catch { error -> if (error is CancellationException) throw error else emit(Result.failure(error)) }
    fun saveDisplayPreferences(value: PracticeDisplayPreferences) = operation { settings.saveDisplay(value) }
    fun saveStatisticsPreferences(value: StatisticsPreferences) = operation { settings.saveStatistics(value) }
    fun saveMusicStatisticsPreferences(value: MusicStatisticsPreferences) = operation { settings.saveMusicStatistics(value) }
    fun saveMusicScope(value: Boolean) = operation { settings.saveMusicScope(value) }
    fun savePracticeGroupingGap(value: PracticeGroupingGap) = operation { settings.saveGroupingGap(value) }

    suspend fun practiceGrouping(at: Instant): PracticeGroupingSummary = readGrouping(at) { summarize() }

    /** Cold and lifecycle-collected: no grouping timer runs when the UI is stopped. */
    @OptIn(ExperimentalCoroutinesApi::class)
    fun observePracticeGrouping(): Flow<Result<PracticeGroupingSummary>> = flow {
        ready.await()
        val storageAvailability = combine(backupReservation, recoveryRequired) { reserved, recovery -> reserved to recovery }
        emitAll(combine(dao.statisticsSessions(), groupingDao.observeCorrections(), settings.groupingGap,
            storageAvailability) { _, _, gap, _ -> gap }.flatMapLatest { gap ->
            flow {
                while (currentCoroutineContext().isActive) {
                    val now = System.currentTimeMillis()
                    val result = try { Result.success(practiceGrouping(Instant.ofEpochMilli(now))) }
                    catch (cancelled: CancellationException) { throw cancelled }
                    catch (error: Exception) { Result.failure(error) }
                    emit(result)
                    val last = result.getOrNull()?.groups?.lastOrNull()
                    val gapMs = gap.minutes?.times(60_000L)
                    val deadline = if (last != null && !last.completed && gapMs != null)
                        runCatching { Math.addExact(last.endedAt, gapMs) }.getOrNull() else null
                    if (deadline == null || deadline < now) awaitCancellation()
                    delay((deadline - now).coerceAtMost(Long.MAX_VALUE - 1) + 1)
                }
            }
        })
    }.catch { error -> if (error is CancellationException) throw error else emit(Result.failure(error)) }.flowOn(Dispatchers.IO)

    suspend fun previewPracticeGrouping(change: PracticeGroupingChange, at: Instant): PracticeGroupingPreview =
        readGrouping(at) { plan(change).preview }

    fun applyPracticeGrouping(preview: PracticeGroupingPreview) = operation {
        database.withTransaction {
            val plan = groupingProjection(preview.at).plan(preview.change)
            if (preview.change is PracticeGroupingChange.FinishAfter) {
                if (plan.addition == null) return@withTransaction
                check(!playbackBusy.value && !sessionWriteFailed.value) { "Wait for durable playback finalization" }
            }
            check(plan.preview == preview) { "Practice grouping preview changed" }
            plan.addition?.let { groupingDao.insert(listOf(GroupingCorrectionRow(it))) }
            val deletedAt = System.currentTimeMillis()
            preview.correctionsToReset.chunked(900).forEach { groupingDao.markDeleted(it, deletedAt) }
        }
    }

    private suspend fun <T> readGrouping(at: Instant, read: PracticeGrouping.() -> T): T {
        awaitWrites()
        return database.withTransaction {
            check(!backupLiveReserved && !recoveryRequired.value)
            groupingProjection(at).read()
        }
    }

    private suspend fun groupingProjection(at: Instant) = PracticeGrouping(groupingDao.playbacks(),
        groupingDao.corrections().map(GroupingCorrectionRow::toDomain), settings.groupingGap.first(), at)

    init {
        scope.launch {
            for (action in actions) {
                try { action() }
                catch (cancelled: CancellationException) { currentCoroutineContext().ensureActive() }
            }
        }
        scope.launch {
            try {
                BackupStaging.cleanInterrupted(backupStagingRoot)
                samples.load()
                recoverRestore()
                try { retrySessionWrites(recovering = true) }
                catch (_: Exception) { error.value = "Nie udało się odzyskać całej historii sesji. Eksport pozostaje zablokowany; zachowano dane odzyskiwania." }
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
            if (!storageReady.value || backupLiveReserved || recoveryRequired.value || !sampleWorking.compareAndSet(false, true)) return
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
            if (!storageReady.value || playbackBusy.value || backupWorking.value || recoveryRequired.value || !exporting.compareAndSet(false, true)) {
                exportNotice.value = "Zatrzymaj metronom i poczekaj na zakończenie zapisu oraz innych operacji."
                return
            }
            exportNotice.value = null
            actions.trySend {
                try {
                    ready.await()
                    retrySessionWrites()
                    val snapshot = dao.csvSessions()
                    openOutput().bufferedWriter(Charsets.UTF_8).use { writer ->
                        SessionCsv.write(snapshot.asSequence().map { session -> session.copy(
                            timeline = dao.timelineForStatistics(session.id, session.durationMs, session.checkpointAt)) }, writer)
                    }
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

    private class BackupDelivery(val staging: BackupStaging, val openOutput: suspend () -> OutputStream?)

    private fun backup(action: suspend () -> Unit) =
        startBackup("Nie udało się przywrócić kopii. Bieżące dane pozostają bez zmian.") {
            action()
            null
        }

    private fun startBackup(failureNotice: String, prepare: suspend () -> BackupDelivery?) {
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
                var handedOff = false
                try {
                    ready.await()
                    check(!settingsWriteFailed)
                    retrySessionWrites()
                    val delivery = prepare()
                    if (delivery != null) {
                        launchDelivery(delivery, failureNotice)
                        handedOff = true
                    }
                } catch (_: BackupRecoveryException) {
                    recoveryRequired.value = true
                    backupNotice.value = BACKUP_RECOVERY_NOTICE
                    error.value = BACKUP_RECOVERY_NOTICE
                } catch (cancelled: CancellationException) {
                    backupNotice.value = "Operacja kopii zapasowej została anulowana."
                    throw cancelled
                } catch (_: Exception) {
                    backupNotice.value = failureNotice
                } finally {
                    if (!handedOff) finishBackup()
                }
            }
        }
    }

    private fun finishBackup() = synchronized(admission) {
        backupDocumentResult = null
        pendingBackupDocument.value = null
        backupWorking.value = false
        backupDelivering.value = false
    }

    private fun launchDelivery(delivery: BackupDelivery, failureNotice: String) {
        synchronized(admission) { backupDelivering.value = true }
        val job = scope.launch(start = CoroutineStart.LAZY) {
            try {
                val owner = currentCoroutineContext()
                backupExportPhase(BackupExportPhase.DELIVERY)
                delivery.openOutput()?.let { output ->
                    delivery.staging.deliver(output, owner::ensureActive)
                    backupNotice.value = "Kopia zapasowa została zapisana."
                }
            } catch (cancelled: CancellationException) {
                backupNotice.value = "Operacja kopii zapasowej została anulowana."
                throw cancelled
            } catch (_: Exception) {
                backupNotice.value = failureNotice
            }
        }
        job.invokeOnCompletion {
            try { delivery.staging.close() } finally { finishBackup() }
        }
        job.start()
    }

    private suspend fun snapshot(access: CustomSampleRepository.BackupAccess, full: Boolean = true): BackupSnapshot {
        val historyBytes = dao.backupSessionBytes()
        require(historyBytes <= BackupArchive.MAX_ARCHIVE_BYTES) { "Backup history exceeds archive limit" }
        if (full) {
            val size = musicDao.backupSize()
            require(size.activities <= BackupArchive.MAX_MUSIC_ACTIVITIES && size.intervals <= BackupArchive.MAX_MUSIC_INTERVALS) {
                "Too many music records for backup"
            }
            val activityBytes = size.activityBytes + 16 * size.intervals
            val grouping = groupingDao.backupSize()
            require(grouping.records <= BackupArchive.MAX_GROUPING_CORRECTIONS) { "Too many grouping corrections for backup" }
            require(activityBytes + grouping.bytes <= BackupArchive.MAX_ARCHIVE_BYTES - historyBytes) { "Backup data exceeds archive limit" }
        }
        val records = dao.allSamples()
        return BackupSnapshot(ConfigCodec.encode(settings.load()), dao.allPresets(), dao.sessionsForExport(),
            dao.allPracticeDays(), records, access.files(records), settings.preferencesSnapshot(),
            if (full) musicDao.all().map(StoredMusicActivity::toDomain) else emptyList(),
            if (full) groupingDao.corrections().map(GroupingCorrectionRow::toDomain) else emptyList())
    }

    fun exportBackup(openOutput: () -> OutputStream) = exportBackupVersion(3) { openOutput() }
    fun exportLegacyBackup(openOutput: () -> OutputStream) = exportBackupVersion(1) { openOutput() }
    fun exportV2Backup(openOutput: () -> OutputStream) = exportBackupVersion(2) { openOutput() }

    internal fun prepareBackupDocument(version: Int): Unit = synchronized(admission) {
        if (backupWorking.value) return@synchronized
        val result = CompletableDeferred<(() -> OutputStream)?>()
        backupDocumentResult = result
        exportBackupVersion(version) {
            synchronized(admission) { if (!result.isCompleted) pendingBackupDocument.value = version }
            result.await()?.invoke()
        }
        if (!backupWorking.value) backupDocumentResult = null
    }

    internal fun takeBackupDocumentRequest(): Int? = synchronized(admission) {
        pendingBackupDocument.value.also { pendingBackupDocument.value = null }
    }

    internal fun completeBackupDocument(openOutput: (() -> OutputStream)?) {
        synchronized(admission) {
            if (backupDocumentResult?.complete(openOutput) == true) pendingBackupDocument.value = null
        }
    }

    internal suspend fun awaitBackup() { backupWorking.first { !it } }

    private fun exportBackupVersion(version: Int, openOutput: suspend () -> OutputStream?) = startBackup(
        "Nie udało się zapisać kopii zapasowej. Plik może być niepełny; dane aplikacji nie zostały zmienione."
    ) {
        val staging = BackupStaging.create(backupStagingRoot)
        try {
            val owner = currentCoroutineContext()
            val limits = BackupStreamLimits()
            samples.backup {
                database.withTransaction {
                    StreamingBackupWriter(database.openHelper.writableDatabase, limits, owner::ensureActive)
                        .write(staging.archive, version, ConfigCodec.encode(settings.load()),
                            settings.preferencesSnapshot(), ::backupSample, backupExportPhase)
                }
            }
            backupExportPhase(BackupExportPhase.VALIDATION)
            StreamingBackupValidator(limits, owner::ensureActive).validate(staging.archive, staging.index)
            BackupDelivery(staging, openOutput)
        } catch (error: Throwable) {
            staging.close()
            throw error
        }
    }

    fun importBackup(openInput: () -> InputStream, applied: (MetronomeConfig) -> Unit) = backup {
        val limits = BackupStreamLimits()
        val owner = currentCoroutineContext()
        val archive = BackupStaging.create(backupStagingRoot).use { staging ->
            staging.stageInput(openInput(), limits.compressed, owner::ensureActive)
            StreamingBackupValidator(limits, owner::ensureActive).validate(staging.archive, staging.index)
            // B1.3 will consume a prepared, disk-backed snapshot. Existing restore still needs these rows.
            staging.archive.inputStream().use(BackupArchive::read)
        }
        val config = ConfigCodec.decode(archive.settings)
        samples.backup {
            val previous = database.withTransaction { snapshot(this) }
            val incoming = archive.copy(preferences = AppPreferencesCodec.forRestore(archive.preferences, previous.preferences))
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
                                settings.restorePreferences(incoming.preferences)
                                restoreCheckpoint(RestoreCheckpoint.SETTINGS_WRITTEN)
                                applyConfiguration(config, applied)
                            }
                            restoreCheckpoint(RestoreCheckpoint.DATABASE_COMMITTED)
                        } catch (error: Exception) {
                            try {
                                backupFault(BackupStage.ROLLBACK_SETTINGS)
                                settings.save(oldConfig)
                                settings.restorePreferences(previous.preferences)
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
        backupNotice.value = "Kopia została przywrócona: ${archive.sessions.size} sesji, ${archive.presets.size} presetów."
    }

    private suspend fun replaceRows(snapshot: BackupSnapshot) {
        dao.deleteAllPracticeDays(); dao.deleteAllSessions(); dao.deleteAllPresets(); dao.deleteAllSamples()
        dao.insertSamples(snapshot.samples); dao.insertPresets(snapshot.presets)
        dao.insertSessions(snapshot.sessions); dao.insertPracticeDays(snapshot.days)
        musicDao.replaceAll(snapshot.musicActivities)
        groupingDao.clear()
        groupingDao.insert(snapshot.groupingCorrections.map(::GroupingCorrectionRow))
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
                    settings.restorePreferences(pending.snapshot.preferences)
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

    private fun syncDatabase() {
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
    }

    private fun syncStores() {
        syncDatabase()
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
