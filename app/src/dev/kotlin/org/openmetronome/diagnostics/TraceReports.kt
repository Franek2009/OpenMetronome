package org.openmetronome.diagnostics

import java.io.*
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import org.openmetronome.data.ConfigCodec
import org.openmetronome.domain.audio.PcmTraceKind

/** Stable public ZIP v1; all file work occurs outside audio and UI threads. */
class TraceReports(
    private val store: TraceSessionStore,
    private val playbackBusy: () -> Boolean = { false },
) {
    data class Report(
        val id: UUID, val state: String, val bytes: Long, val historyId: String?, val reason: String?,
        val pcmDropped: Long?, val controlDropped: Long?, val pcmStorageLost: Long?, val controlStorageLost: Long?,
    )
    data class ExportResult(val incomplete: Boolean, val audioRecords: Long, val controlRecords: Long)

    private suspend fun <T> operation(block: suspend () -> T): T = withContext(Dispatchers.IO) {
        check(!playbackBusy()) { "playback_active" }
        check(store.reserveReportOperation()) { "reports_busy" }
        try { currentCoroutineContext().ensureActive(); block() }
        finally { store.releaseReportOperation() }
    }

    /** Bounded pages, latest directory modification first; no unbounded report DTO list. */
    suspend fun list(offset: Int = 0, limit: Int = 50): List<Report> = operation {
        require(offset >= 0 && limit in 1..100)
        val directories = (store.root.listFiles() ?: throw IOException("trace_directory_unreadable"))
            .filter { it.isDirectory && runCatching { UUID.fromString(it.name).toString() == it.name }.getOrDefault(false) }
            .sortedWith(compareByDescending<File> { it.lastModified() }.thenBy { it.name })
        directories.drop(offset).take(limit).map { directory ->
            currentCoroutineContext().ensureActive()
            val metadata = runCatching { store.journal.readMetadata(directory) }.getOrNull()
            fun count(key: String): Long? = metadata?.getProperty(key)?.toLongOrNull()?.takeIf { it >= 0 }
            val pcmStorageLost = count("storageLostRecords") ?: if (metadata?.getProperty("finalCountersKnown") == "true") {
                count("accepted")?.let { accepted -> count("writtenRecords")?.let { written ->
                    (accepted - written).takeIf { it >= 0 }
                } }
            } else null
            Report(UUID.fromString(directory.name), metadata?.getProperty("state") ?: "unreadable",
                directory.walkTopDown().filter { it.isFile }.sumOf { it.length() },
                metadata?.getProperty("historyId"), metadata?.getProperty("reason"),
                count("dropped"), count("controlDropped"), pcmStorageLost, count("controlStorageLostRecords"))
        }
    }

    suspend fun delete(id: UUID) = operation { store.journal.deleteReport(id) }

    suspend fun export(id: UUID, open: () -> OutputStream): ExportResult = operation {
        val directory = File(store.root, id.toString())
        check(directory.isDirectory) { "report_missing" }
        val metadata = store.journal.readMetadata(directory)
        check(metadata.getProperty("state") in listOf("complete", "incomplete", "interrupted")) { "report_not_finalized" }
        val coroutine = currentCoroutineContext()
        fun checkRunning() {
            coroutine.ensureActive()
            if (playbackBusy()) throw kotlinx.coroutines.CancellationException("playback_started")
        }
        val audioFile = File(directory, "records.bin")
        val controlFile = File(directory, "control.bin")
        val audio = store.journal.scanRecords(audioFile) { checkRunning() }
        var recordedHistory: UUID? = null
        var historyConflict = false
        val control = store.journal.scanRecords(controlFile) { row ->
            checkRunning()
            if (row.kindCode == TraceControlKind.HISTORY_CREATED.code) {
                val id = UUID(row.a, row.b)
                if (recordedHistory != null && recordedHistory != id) historyConflict = true
                recordedHistory = id
            }
        }
        val manifestHistory = metadata.getProperty("historyId")?.let { runCatching { UUID.fromString(it) }.getOrNull() }
        if (recordedHistory != null && manifestHistory != null && recordedHistory != manifestHistory) historyConflict = true
        val historyId = if (historyConflict) null else recordedHistory ?: manifestHistory
        val missingAudio = !audioFile.isFile
        val missingControl = !controlFile.isFile
        fun validCounters(prefix: String): Boolean {
            fun number(suffix: String): Long? = metadata.getProperty(if (prefix.isEmpty()) suffix.replaceFirstChar { it.lowercase() } else prefix + suffix)
                ?.toLongOrNull()?.takeIf { it >= 0 }
            val attempted = number("Attempted") ?: return false
            val accepted = number("Accepted") ?: return false
            val dropped = number("Dropped") ?: return false
            return attempted >= accepted && attempted - accepted == dropped
        }
        val pcmCountersKnown = metadata.getProperty("finalCountersKnown") == "true" && validCounters("")
        val controlCountersKnown = metadata.getProperty("controlFinalCountersKnown") == "true" && validCounters("control")
        fun mismatch(key: String, observed: Long): Boolean = metadata.getProperty(key)?.let { it.toLongOrNull() != observed } ?: false
        val countsMismatch = mismatch("writtenRecords", audio.records) || mismatch("controlWrittenRecords", control.records) ||
            mismatch("accepted", audio.records) || mismatch("controlAccepted", control.records)
        val hasLoss = (metadata.getProperty("dropped")?.toLongOrNull() ?: 0) > 0 ||
            (metadata.getProperty("controlDropped")?.toLongOrNull() ?: 0) > 0 || metadata.getProperty("observerFaulted") == "true"
        val incomplete = metadata.getProperty("state") != "complete" || audio.damagedTail || control.damagedTail ||
            missingAudio || missingControl || countsMismatch || historyConflict || !pcmCountersKnown || !controlCountersKnown || hasLoss
        checkRunning()
        fun scanStable(file: File, consume: (JournalRecord) -> Unit): TraceJournal.Scan {
            val expected = if (file == audioFile) audio else control
            val expectedPresent = if (file == audioFile) !missingAudio else !missingControl
            if (file.isFile != expectedPresent) throw IOException("source_changed_during_export")
            val actual = store.journal.scanRecords(file, consume)
            if (actual != expected) throw IOException("source_changed_during_export")
            return actual
        }
        ZipOutputStream(BufferedOutputStream(open(), 64 * 1024)).use { zip ->
            fun csv(name: String, source: String, file: File, accept: (JournalRecord) -> Boolean) {
                checkRunning()
                zip.putNextEntry(ZipEntry(name))
                val out = OutputStreamWriter(zip, Charsets.UTF_8)
                out.write("source,sequence,nanos,kind_code,kind,frame,a,b,c,d,config\r\n")
                scanStable(file) { row ->
                    checkRunning()
                    if (accept(row)) {
                        val kind = if (source == "pcm") PcmTraceKind.entries.find { it.code == row.kindCode }?.name
                            else TraceControlKind.entries.find { it.code == row.kindCode }?.name
                        val fields = listOf(source, row.sequence.toString(), row.nanos.toString(), row.kindCode.toString(),
                            kind ?: "UNKNOWN", row.frame.takeIf { it >= 0 }?.toString() ?: "", row.a.toString(),
                            row.b.toString(), row.c.toString(), row.d.toString(), row.config?.let(ConfigCodec::encode) ?: "")
                        out.write(fields.joinToString(",") { csvField(it) }); out.write("\r\n")
                    }
                }
                out.flush(); zip.closeEntry() // never close the per-entry writer
            }
            csv("beats.csv", "pcm", audioFile) { it.kindCode == PcmTraceKind.BEAT.code }
            // Include PCM lifecycle/config events as well as the separate control lane.
            zip.putNextEntry(ZipEntry("events.csv"))
            val events = OutputStreamWriter(zip, Charsets.UTF_8)
            events.write("source,sequence,nanos,kind_code,kind,frame,a,b,c,d,config\r\n")
            fun eventRow(source: String, row: JournalRecord) {
                checkRunning()
                val kind = if (source == "pcm") PcmTraceKind.entries.find { it.code == row.kindCode }?.name
                    else TraceControlKind.entries.find { it.code == row.kindCode }?.name
                events.write(listOf(source, row.sequence.toString(), row.nanos.toString(), row.kindCode.toString(),
                    kind ?: "UNKNOWN", row.frame.takeIf { it >= 0 }?.toString() ?: "", row.a.toString(), row.b.toString(),
                    row.c.toString(), row.d.toString(), row.config?.let(ConfigCodec::encode) ?: "").joinToString(",") { csvField(it) })
                events.write("\r\n")
            }
            scanStable(audioFile) { if (it.kindCode in EVENT_CODES) eventRow("pcm", it) else checkRunning() }
            scanStable(controlFile) { eventRow("control", it) }
            events.flush(); zip.closeEntry()
            csv("audio.csv", "pcm", audioFile) { it.kindCode != PcmTraceKind.BEAT.code && it.kindCode !in EVENT_CODES }
            checkRunning()
            zip.putNextEntry(ZipEntry("metadata.json"))
            val json = linkedMapOf<String, String?>()
            json["schemaVersion"] = "1"; json["reportId"] = id.toString()
            json["state"] = if (incomplete && metadata.getProperty("state") == "complete") "incomplete" else metadata.getProperty("state")
            json["audioRecords"] = audio.records.toString(); json["controlRecords"] = control.records.toString()
            json["audioAvailable"] = (!missingAudio).toString(); json["controlAvailable"] = (!missingControl).toString()
            json["countsMismatch"] = countsMismatch.toString()
            json["countersVerified"] = (pcmCountersKnown && controlCountersKnown).toString()
            json["audioDamagedTail"] = audio.damagedTail.toString(); json["controlDamagedTail"] = control.damagedTail.toString()
            json["timeUnit"] = "monotonic_nanoseconds"; json["frameUnit"] = "mono_pcm_frame"
            json["physicalJitter"] = null; json["physicalLatency"] = null
            json["ordering"] = "independent_lane_sequences; events.csv groups pcm then control; compare nanos"
            json["missingFrame"] = "empty_csv_field"
            json["historyId"] = historyId?.toString()
            json["historyLinkConflict"] = historyConflict.toString()
            json["historyLinkSource"] = if (historyConflict) "conflicting_records" else if (recordedHistory != null) "control" else if (manifestHistory != null) "manifest" else null
            json["finalCountersKnown"] = pcmCountersKnown.toString()
            json["controlFinalCountersKnown"] = controlCountersKnown.toString()
            metadata.stringPropertyNames().sorted().forEach { key ->
                if (key !in json && (key in MANIFEST_KEYS || key.startsWith("environment.") ||
                    key.startsWith("dropped.") || key.startsWith("controlDropped."))) json[key] = metadata.getProperty(key)
            }
            PcmTraceKind.entries.forEach { json["pcmKind.${it.code}"] = it.name }
            TraceControlKind.entries.forEach { json["controlKind.${it.code}"] = it.name }
            org.openmetronome.audio.AudioFailure.entries.forEachIndexed { index, failure -> json["audioFailure.$index"] = failure.name }
            listOf("pause", "flush", "track", "focus", "bank").forEachIndexed { index, resource -> json["cleanupResource.$index"] = resource }
            json["beatFlag.1"] = "training_mute"; json["beatFlag.2"] = "beat_mute"
            json["beatFlag.4"] = "subdivision_mute"; json["beatFlag.256"] = "accent_sample"
            json["platformErrorAvailability"] = "ERROR: c=1 means b is raw code; WRITE_THROW: d=1 means c is raw code; flag 0 means unavailable"
            json["checkpointReturned"] = "checkpoint function returned; not proof of durable history commit"
            json["historyEncoding"] = "HISTORY_CREATED/CHECKPOINT_*: a,b=UUID high/low signed 64-bit; c=finished; d=interrupted"
            json["focusResult.0"] = "failed"; json["focusResult.1"] = "granted"; json["focusResult.2"] = "delayed"
            json["focusChange.1"] = "gain"; json["focusChange.-1"] = "loss"
            json["focusChange.-2"] = "transient_loss"; json["focusChange.-3"] = "duck_request"
            json["stopReason.0"] = "explicit_stop"; json["stopReason.1"] = "engine_close"
            json["startRejected.1"] = "engine_closed"; json["startRejected.2"] = "engine_busy"
            json["startRejected.3"] = "sample_lease_failed; b=audioFailure code"
            val encoded = json.entries.joinToString(",\n", "{\n", "\n}\n") { (key, value) ->
                val encodedValue = when {
                    value == null -> "null"
                    key in BOOLEAN_KEYS -> value.takeIf { it == "true" || it == "false" } ?: "null"
                    key in NUMBER_KEYS || key.startsWith("dropped.") || key.startsWith("controlDropped.") -> value.toLongOrNull()?.toString() ?: "null"
                    else -> jsonString(value)
                }
                "  ${jsonString(key)}: $encodedValue"
            }
            zip.write(encoded.toByteArray(Charsets.UTF_8)); zip.closeEntry()
            checkRunning()
        }
        ExportResult(incomplete, audio.records, control.records)
    }

    companion object {
        private val MANIFEST_KEYS = setOf("engineSessionId", "reason", "attempted", "accepted", "dropped", "observerFaulted",
            "writtenRecords", "recoveredRecords", "storageLostRecords", "validBytes", "damagedTail", "controlRecoveredRecords",
            "controlDamagedTail", "controlAttempted", "controlAccepted", "controlDropped", "controlWrittenRecords", "controlStorageLostRecords")
        private val BOOLEAN_KEYS = setOf("historyLinkConflict", "audioAvailable", "controlAvailable", "countsMismatch", "countersVerified", "audioDamagedTail", "controlDamagedTail", "damagedTail", "finalCountersKnown", "controlFinalCountersKnown", "observerFaulted")
        private val NUMBER_KEYS = MANIFEST_KEYS - setOf("reason", "observerFaulted", "damagedTail", "controlDamagedTail") +
            setOf("schemaVersion", "audioRecords", "controlRecords", "environment.versionCode", "environment.api", "environment.startedAtEpochMs", "environment.startedAtMonotonicNs")
        private val EVENT_CODES = setOf(1, 3, 4, 5, 14, 15, 16, 17, 18, 19, 20)
        private fun csvField(value: String): String = "\"${value.replace("\"", "\"\"")}\""
        internal fun jsonString(value: String): String = buildString {
            append('"')
            value.forEach { char ->
                when (char) {
                    '"' -> append("\\\""); '\\' -> append("\\\\")
                    '\n' -> append("\\n"); '\r' -> append("\\r"); '\t' -> append("\\t")
                    else -> if (char < ' ') append("\\u%04x".format(char.code)) else append(char)
                }
            }
            append('"')
        }
    }
}
