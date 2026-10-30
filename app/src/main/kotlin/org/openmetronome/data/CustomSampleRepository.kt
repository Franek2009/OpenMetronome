package org.openmetronome.data

import androidx.room.withTransaction
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.UUID
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import org.openmetronome.audio.*
import org.openmetronome.domain.audio.SamplePcm

data class CustomSampleItem(val id: String, val name: String, val available: Boolean)

/** All file operations run on IO. The audio thread receives memory-only, immutable snapshots. */
class CustomSampleRepository(private val db: PracticeDatabase, private val directory: File) : SampleBankSource {
    private val gate = Any()
    private var playing = 0
    private var editing = false
    private var loaded = false
    private var bank: Map<String, ShortArray> = emptyMap()
    private val mutableItems = MutableStateFlow<List<CustomSampleItem>>(emptyList())
    val items = mutableItems.asStateFlow()
    private val mutableBusy = MutableStateFlow(false)
    val busy = mutableBusy.asStateFlow()

    private fun file(id: String): File {
        UUID.fromString(id).also { require(it.toString() == id) }
        return File(directory, "$id.wav")
    }

    suspend fun load() = edit {
        directory.mkdirs()
        val samples = mutableMapOf<String, ShortArray>()
        val entries = db.practice().allSamples().map { record ->
            val pcm = runCatching {
                val f = file(record.id)
                require(f.length() in 46..(44 + SamplePcm.MAX_FRAMES * 2).toLong())
                val bytes = f.readBytes()
                val audio = SamplePcm.decodeWav(bytes)
                require(audio.rate == SamplePcm.RATE && audio.mono.size in 48..SamplePcm.MAX_FRAMES)
                ShortArray(audio.mono.size) { (audio.mono[it] * 32768).toInt().toShort() }.also { pcm ->
                    require(pcm.first() == 0.toShort() && pcm.last() == 0.toShort())
                    require(pcm.all { kotlin.math.abs(it.toInt()) <= org.openmetronome.domain.audio.ClickGenerator.PEAK_SAMPLE })
                    require(kotlin.math.abs(pcm.map { it.toDouble() }.average()) < 655)
                    require(bytes.contentEquals(SamplePcm.encodeCanonical(pcm)))
                }
            }.getOrNull()
            if (pcm != null && pcm.any { it != 0.toShort() }) samples[record.id] = pcm
            CustomSampleItem(record.id, record.name, samples.containsKey(record.id))
        }
        synchronized(gate) { bank = samples.toMap(); loaded = true; mutableItems.value = entries }
    }

    override fun acquire(): SampleBankLease = synchronized(gate) {
        if (!loaded || editing) throw AudioEngineException(AudioFailure.LIBRARY_BUSY)
        playing++
        SampleBankLease(bank) { synchronized(gate) { playing-- } }
    }

    private suspend fun <T> edit(action: suspend () -> T): T {
        synchronized(gate) {
            check(!editing && playing == 0) { "Zatrzymaj metronom i poczekaj na zakończenie operacji biblioteki." }
            editing = true
            mutableBusy.value = true
        }
        try { return action() } finally { synchronized(gate) { editing = false; mutableBusy.value = false } }
    }

    suspend fun importSample(name: String, decode: suspend () -> SamplePcm.Audio): Pair<String, Boolean> = edit {
        check(loaded)
        require(mutableItems.value.size < 32) { "Limit 32 sampli. Usuń nieużywany sample." }
        val prepared = SamplePcm.prepare(decode())
        var id = UUID.randomUUID().toString()
        while (mutableItems.value.any { it.id == id } || file(id).exists()) id = UUID.randomUUID().toString()
        val target = file(id)
        val temp = File(directory, "$id.tmp")
        val cleanName = name.substringBeforeLast('.').trim().take(80).ifBlank { "Mój sample" }
        withContext(NonCancellable) {
            var published = false
            try {
                directory.mkdirs()
                java.io.FileOutputStream(temp).use { it.write(SamplePcm.encodeCanonical(prepared.pcm)); it.fd.sync() }
                Files.move(temp.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE)
                published = true
                db.practice().insertSample(CustomSampleRecord(id, cleanName, System.currentTimeMillis()))
            } catch (error: Exception) {
                // Only this newly generated UUID, never an existing recording or source document.
                temp.delete(); if (published) target.delete(); throw error
            }
            synchronized(gate) {
                bank = bank + (id to prepared.pcm)
                mutableItems.value = mutableItems.value + CustomSampleItem(id, cleanName, true)
            }
        }
        id to prepared.trimmed
    }

    suspend fun rename(id: String, name: String) = edit {
        require(name.trim().isNotEmpty() && name.trim().length <= 80) { "Nazwa musi mieć 1–80 znaków." }
        db.practice().renameSample(id, name.trim())
        mutableItems.value = mutableItems.value.map { if (it.id == id) it.copy(name = name.trim()) else it }
    }

    suspend fun delete(id: String) = edit {
        withContext(NonCancellable) {
            db.withTransaction {
                // Unreadable presets conservatively block deletion rather than risking their data.
                check(db.practice().allPresets().none { ConfigCodec.decode(it.config).sound.customSampleId == id }) {
                    "Sample jest używany w presecie. Najpierw zmień brzmienie tego presetu."
                }
                db.practice().deleteSample(id)
            }
            synchronized(gate) { bank = bank - id; mutableItems.value = mutableItems.value.filterNot { it.id == id } }
            // Metadata removed first: a process interruption may leave an orphan, never a broken live entry.
            file(id).delete()
        }
    }

    suspend fun backupFiles(records: List<CustomSampleRecord>): Map<String, ByteArray> = edit {
        records.associate { record -> record.id to file(record.id).readBytes() }
    }

    /** Called only after the complete archive has been parsed and validated. */
    suspend fun replaceFromBackup(snapshot: BackupSnapshot, replaceDatabase: suspend () -> Unit) = edit {
        check(loaded)
        val prepared = snapshot.samples.associate { record ->
            val bytes = requireNotNull(snapshot.sampleFiles[record.id])
            val audio = SamplePcm.decodeWav(bytes)
            val pcm = ShortArray(audio.mono.size) { (audio.mono[it] * 32768).toInt().toShort() }
            require(bytes.contentEquals(SamplePcm.encodeCanonical(pcm)))
            record.id to pcm
        }
        val staging = File(directory.parentFile, "sample-restore-${UUID.randomUUID()}")
        val rollback = File(directory.parentFile, "sample-rollback-${UUID.randomUUID()}")
        var filesTouched = false
        var databaseReplaced = false
        try {
            staging.mkdirs()
            snapshot.sampleFiles.forEach { (id, bytes) -> File(staging, "$id.wav").writeBytes(bytes) }
            rollback.mkdirs()
            directory.listFiles()?.filter { it.extension == "wav" }?.forEach { it.copyTo(File(rollback, it.name)) }
            withContext(NonCancellable) {
                directory.mkdirs()
                filesTouched = true
                snapshot.samples.forEach { record ->
                    Files.move(File(staging, "${record.id}.wav").toPath(), file(record.id).toPath(), StandardCopyOption.REPLACE_EXISTING)
                }
                replaceDatabase()
                databaseReplaced = true
                directory.listFiles()?.filter { it.extension == "wav" && it.nameWithoutExtension !in prepared }?.forEach(File::delete)
                synchronized(gate) {
                    bank = prepared
                    mutableItems.value = snapshot.samples.map { CustomSampleItem(it.id, it.name, true) }
                }
            }
        } catch (error: Exception) {
            if (filesTouched && !databaseReplaced) {
                directory.listFiles()?.filter { it.extension == "wav" }?.forEach(File::delete)
                rollback.listFiles()?.forEach { it.copyTo(File(directory, it.name), overwrite = true) }
            }
            throw error
        } finally {
            staging.listFiles()?.forEach(File::delete); staging.delete()
            rollback.listFiles()?.forEach(File::delete); rollback.delete()
        }
    }
}
