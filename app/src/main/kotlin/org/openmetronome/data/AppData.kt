package org.openmetronome.data

import android.content.Context
import androidx.datastore.preferences.core.*
import androidx.datastore.preferences.preferencesDataStore
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
import org.openmetronome.domain.model.MetronomeConfig

private val Context.settingsStore by preferencesDataStore("metronome-settings")

class SettingsRepository(private val store: DataStore<Preferences>) {
    constructor(context: Context) : this(context.settingsStore)
    private val key = stringPreferencesKey("configuration_v1")
    suspend fun load(): MetronomeConfig = store.data.first()[key]?.let(ConfigCodec::decode) ?: MetronomeConfig()
    suspend fun save(config: MetronomeConfig) { store.edit { it[key] = ConfigCodec.encode(config) } }
}

/** Process-scoped repositories; service and UI share storage, never an audio worker. */
class AppData(context: Context, isolatedTestStorage: Boolean = false) {
    val error = MutableStateFlow<String?>(null)
    val exporting = MutableStateFlow(false)
    val exportNotice = MutableStateFlow<String?>(null)
    val sampleWorking = MutableStateFlow(false)
    val sampleNotice = MutableStateFlow<String?>(null)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    val settings = if (isolatedTestStorage) SettingsRepository(PreferenceDataStoreFactory.create(scope = scope) {
        File(context.cacheDir, "instrumentation-${UUID.randomUUID()}.preferences_pb")
    }) else SettingsRepository(context.applicationContext)
    val database = if (isolatedTestStorage) Room.inMemoryDatabaseBuilder(context.applicationContext, PracticeDatabase::class.java).build()
        else Room.databaseBuilder(context.applicationContext, PracticeDatabase::class.java, "openmetronome.db")
            .addMigrations(PracticeDatabase.MIGRATION_1_2).build()
    val samples = CustomSampleRepository(database, if (isolatedTestStorage)
        File(context.cacheDir, "sample-library-${UUID.randomUUID()}") else File(context.filesDir, "custom-samples"))
    private val settingUpdates = Channel<MetronomeConfig>(Channel.CONFLATED)
    val dao = database.practice()
    val ready = CompletableDeferred<Unit>()
    val presets = dao.presets().catch { error.value = "Nie udało się odczytać presetów"; emit(emptyList()) }
    val sessions = dao.sessions().catch { error.value = "Nie udało się odczytać historii"; emit(emptyList()) }
    val days = dao.days().catch { error.value = "Nie udało się odczytać statystyk"; emit(emptyList()) }
    val total = dao.total().catch { error.value = "Nie udało się odczytać statystyk"; emit(PracticeTotal(0, 0)) }
    init {
        scope.launch {
            for (config in settingUpdates) {
                try { settings.save(config) }
                catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) { error.value = "Nie udało się zapisać ustawień" }
            }
        }
        scope.launch {
            try { dao.recoverInterrupted(); samples.load(); ready.complete(Unit) }
            catch (e: Exception) { ready.completeExceptionally(e); error.value = "Błąd otwarcia bazy. Dane nie zostały usunięte." }
        }
    }
    fun saveSettings(config: MetronomeConfig) { settingUpdates.trySend(config) }
    fun operation(action: suspend () -> Unit) {
        scope.launch {
            try { ready.await(); action() }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { error.value = "Nie udało się zapisać danych. Spróbuj ponownie." }
        }
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
        if (!sampleWorking.compareAndSet(false, true)) return
        sampleNotice.value = null
        scope.launch {
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
        if (!exporting.compareAndSet(false, true)) return
        exportNotice.value = null
        scope.launch {
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
