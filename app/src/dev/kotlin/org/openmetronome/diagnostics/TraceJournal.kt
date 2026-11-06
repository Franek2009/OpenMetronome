package org.openmetronome.diagnostics

import java.io.*
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.Properties
import java.util.UUID
import java.util.zip.CRC32
import org.openmetronome.data.ConfigCodec
import org.openmetronome.domain.audio.PcmTraceHealth
import org.openmetronome.domain.audio.PcmTraceKind

/** DEV-private framed journal. Only its IO consumer may call this class. */
internal class TraceJournal(
    private val root: File,
    private val sessionLimit: Long = 512L * 1024 * 1024,
    private val totalLimit: Long = 2L * 1024 * 1024 * 1024,
    private val fault: (String) -> Unit = {},
) {
    companion object {
        const val METADATA_RESERVE = 128L * 1024 // current + atomic replacement
        const val MAX_RECORD = 32 * 1024
        private const val MAGIC = 0x4f4d4431 // OMD1
    }

    internal data class Scan(val records: Long, val validBytes: Long, val damagedTail: Boolean)
    private var reservedTotal = 0L

    /** Executed before accepting new files. Never resumes an interrupted producer. */
    fun recover() {
        check(root.isDirectory || root.mkdirs()) { "trace_directory" }
        reservedTotal = 0
        root.listFiles().orEmpty().forEach { directory ->
            if (!directory.isDirectory || runCatching { UUID.fromString(directory.name) }.isFailure) {
                reservedTotal += if (directory.isFile) directory.length() else directory.walkTopDown().filter { it.isFile }.sumOf { it.length() }
                return@forEach
            }
            val journal = File(directory, "records.bin")
            val scan = scan(journal)
            val metadata = readMetadata(directory)
            if (metadata.getProperty("state") !in listOf("complete", "incomplete", "interrupted") ||
                scan.damagedTail) {
                metadata.setProperty("state", "interrupted")
                metadata.setProperty("reason", "process_interrupted")
                metadata.setProperty("finalCountersKnown", "false")
                metadata.setProperty("recoveredRecords", scan.records.toString())
                metadata.setProperty("validBytes", scan.validBytes.toString())
                metadata.setProperty("damagedTail", scan.damagedTail.toString())
                saveMetadata(directory, metadata)
            }
            reservedTotal += maxOf(directory.walkTopDown().filter { it.isFile }.sumOf { it.length() }, journal.length() + METADATA_RESERVE)
        }
    }

    fun open(id: UUID, engineId: Long = 0): Writer {
        require(sessionLimit >= METADATA_RESERVE && totalLimit >= METADATA_RESERVE)
        if (reservedTotal > totalLimit - METADATA_RESERVE) throw IOException("total_limit")
        val directory = File(root, id.toString())
        check(directory.mkdir()) { "trace_session_directory" }
        reservedTotal += METADATA_RESERVE
        java.nio.channels.FileChannel.open(root.toPath(), java.nio.file.StandardOpenOption.READ).use { it.force(true) }
        return Writer(directory, engineId)
    }

    inner class Writer internal constructor(val directory: File, engineId: Long) : AutoCloseable {
        private val stream = FileOutputStream(File(directory, "records.bin"))
        private val output = DataOutputStream(BufferedOutputStream(stream, 16 * 1024))
        private var bytes = 0L
        var persistedRecords = 0L
            private set
        private val metadata = Properties().apply {
            setProperty("schemaVersion", "1")
            setProperty("id", directory.name)
            setProperty("engineSessionId", engineId.toString())
            setProperty("state", "recording")
            setProperty("finalCountersKnown", "false")
        }

        init {
            try { saveMetadata(directory, metadata) }
            catch (error: Exception) { output.close(); throw error }
        }

        fun append(record: PcmTraceRecord) {
            fault("append")
            val payload = encode(record)
            val count = payload.size.toLong() + 12
            if (bytes + count + METADATA_RESERVE > sessionLimit) throw IOException("session_limit")
            if (reservedTotal > totalLimit - count) throw IOException("total_limit")
            // Charge before IO: failed/partial writes must not evade quotas.
            bytes += count
            reservedTotal += count
            output.writeInt(MAGIC)
            output.writeInt(payload.size)
            output.write(payload)
            output.writeInt(CRC32().apply { update(payload) }.value.toInt())
            persistedRecords++
        }

        fun checkpoint(health: PcmTraceHealth?, state: String = "recording", reason: String? = null) {
            output.flush()
            fault("sync")
            stream.fd.sync()
            metadata.setProperty("state", state)
            metadata.setProperty("writtenRecords", persistedRecords.toString())
            metadata.setProperty("finalCountersKnown", (health?.closed == true).toString())
            reason?.let { metadata.setProperty("reason", it.take(80)) }
            health?.let {
                metadata.setProperty("attempted", it.attempted.toString())
                metadata.setProperty("accepted", it.accepted.toString())
                metadata.setProperty("dropped", it.dropped.toString())
                metadata.setProperty("observerFaulted", it.faulted.toString())
                it.droppedByKind.forEach { (kind, count) -> metadata.setProperty("dropped.${kind.code}", count.toString()) }
            }
            saveMetadata(directory, metadata)
        }

        override fun close() = output.close()
    }

    fun finishFailed(id: UUID, health: PcmTraceHealth, reason: String) {
        val directory = File(root, id.toString())
        if (!directory.isDirectory) return // admission failed before a reservation
        val metadata = readMetadata(directory)
        val scan = scan(File(directory, "records.bin"))
        metadata.setProperty("schemaVersion", "1")
        metadata.setProperty("id", id.toString())
        metadata.setProperty("state", "incomplete")
        metadata.setProperty("reason", reason)
        metadata.setProperty("finalCountersKnown", "true")
        metadata.setProperty("attempted", health.attempted.toString())
        metadata.setProperty("accepted", health.accepted.toString())
        metadata.setProperty("dropped", health.dropped.toString())
        health.droppedByKind.forEach { (kind, count) -> metadata.setProperty("dropped.${kind.code}", count.toString()) }
        metadata.setProperty("recoveredRecords", scan.records.toString())
        metadata.setProperty("storageLostRecords", maxOf(0L, health.accepted - scan.records).toString())
        metadata.setProperty("validBytes", scan.validBytes.toString())
        metadata.setProperty("damagedTail", scan.damagedTail.toString())
        saveMetadata(directory, metadata)
    }

    fun readMetadata(directory: File): Properties = Properties().apply {
        val file = File(directory, "metadata.properties")
        if (file.isFile) {
            require(file.length() <= METADATA_RESERVE / 2) { "metadata_too_large" }
            FileInputStream(file).use { load(it) }
        }
    }

    private fun saveMetadata(directory: File, properties: Properties) {
        fault("metadata")
        val bytes = ByteArrayOutputStream().also { properties.store(it, null) }.toByteArray()
        require(bytes.size <= METADATA_RESERVE / 2)
        val temporary = File(directory, "metadata.tmp")
        FileOutputStream(temporary).use { it.write(bytes); it.fd.sync() }
        fault("metadata_replace")
        Files.move(temporary.toPath(), File(directory, "metadata.properties").toPath(),
            StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        java.nio.channels.FileChannel.open(directory.toPath(), java.nio.file.StandardOpenOption.READ).use { it.force(true) }
        // The previous valid manifest remains intact if the replacement fails.
    }

    fun scan(file: File, consume: (PcmTraceRecord) -> Unit = {}): Scan {
        if (!file.exists()) return Scan(0, 0, false)
        var valid = 0L
        var records = 0L
        DataInputStream(BufferedInputStream(FileInputStream(file))).use { input ->
            while (valid < file.length()) {
                val record: PcmTraceRecord
                var length = 0
                try {
                    if (input.readInt() != MAGIC) return Scan(records, valid, true)
                    length = input.readInt()
                    if (length !in 64..MAX_RECORD) return Scan(records, valid, true)
                    val bytes = ByteArray(length)
                    input.readFully(bytes)
                    if (input.readInt() != CRC32().apply { update(bytes) }.value.toInt()) return Scan(records, valid, true)
                    record = decode(bytes)
                } catch (_: EOFException) { return Scan(records, valid, true) }
                catch (_: IllegalArgumentException) { return Scan(records, valid, true) }
                catch (_: UTFDataFormatException) { return Scan(records, valid, true) }
                consume(record)
                valid += length + 12
                records++
            }
        }
        return Scan(records, valid, false)
    }

    private fun encode(record: PcmTraceRecord): ByteArray = ByteArrayOutputStream().also { bytes ->
        DataOutputStream(bytes).use { out ->
            out.writeLong(record.sequence); out.writeLong(record.nanos); out.writeInt(record.kind.code)
            out.writeLong(record.frame)
            out.writeLong(record.a); out.writeLong(record.b); out.writeLong(record.c); out.writeLong(record.d)
            val config = record.config?.let(ConfigCodec::encode)?.toByteArray(Charsets.UTF_8) ?: byteArrayOf()
            require(config.size <= 16384)
            out.writeInt(config.size); out.write(config)
        }
    }.toByteArray()

    private fun decode(bytes: ByteArray): PcmTraceRecord = DataInputStream(ByteArrayInputStream(bytes)).use { input ->
        val sequence = input.readLong(); val nanos = input.readLong()
        val code = input.readInt()
        val kind = requireNotNull(PcmTraceKind.entries.find { it.code == code })
        val frame = input.readLong(); val a = input.readLong(); val b = input.readLong()
        val c = input.readLong(); val d = input.readLong()
        val size = input.readInt()
        require(size in 0..16384 && size == input.available())
        val config = ByteArray(size).also(input::readFully).takeIf { it.isNotEmpty() }
            ?.toString(Charsets.UTF_8)?.let(ConfigCodec::decode)
        PcmTraceRecord(sequence, nanos, kind, frame, a, b, c, d, config)
    }
}
