package org.openmetronome.data

import java.io.*
import java.util.UUID
import java.time.LocalDate
import java.time.ZoneId
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import org.openmetronome.domain.audio.SamplePcm

data class BackupSnapshot(
    val settings: String,
    val presets: List<Preset>,
    val sessions: List<PracticeSession>,
    val days: List<PracticeDay>,
    val samples: List<CustomSampleRecord>,
    val sampleFiles: Map<String, ByteArray>,
)

/** Versioned, private backup format. Parsing is bounded and validates all data before import. */
object BackupArchive {
    private const val VERSION = 1
    private const val METADATA = "openmetronome.dat"
    private const val MAX_ARCHIVE_BYTES = 16 * 1024 * 1024
    private const val MAX_ITEMS = 100_000

    fun write(snapshot: BackupSnapshot, output: OutputStream) {
        val encoded = ByteArrayOutputStream()
        encode(snapshot, encoded)
        val bytes = encoded.toByteArray()
        read(ByteArrayInputStream(bytes)) // Export must satisfy exactly the same limits as import.
        output.write(bytes)
    }

    private fun encode(snapshot: BackupSnapshot, output: OutputStream) {
        var total = 0
        ZipOutputStream(BufferedOutputStream(output)).use { zip ->
            val bounded = object : OutputStream() {
                override fun write(value: Int) {
                    require(++total <= MAX_ARCHIVE_BYTES); zip.write(value)
                }
                override fun write(bytes: ByteArray, offset: Int, length: Int) {
                    require(length <= MAX_ARCHIVE_BYTES - total); total += length
                    zip.write(bytes, offset, length)
                }
                override fun flush() = zip.flush()
            }
            zip.putNextEntry(ZipEntry(METADATA))
            DataOutputStream(bounded).apply {
                writeInt(VERSION); writeString(snapshot.settings)
                writeList(snapshot.presets) { item ->
                    writeString(item.id); writeString(item.name); writeString(item.config)
                    writeLong(item.createdAt); writeLong(item.updatedAt)
                }
                writeList(snapshot.sessions) { item ->
                    writeString(item.id); writeLong(item.startedAt); writeLong(item.checkpointAt)
                    writeLong(item.durationMs); writeBoolean(item.finished); writeBoolean(item.interrupted)
                    writeString(item.zoneId); writeString(item.config)
                }
                writeList(snapshot.days) { item ->
                    writeString(item.sessionId); writeString(item.date); writeLong(item.durationMs)
                }
                writeList(snapshot.samples) { item ->
                    writeString(item.id); writeString(item.name); writeLong(item.createdAt)
                }
                flush()
            }
            zip.closeEntry()
            snapshot.samples.forEach { sample ->
                val bytes = requireNotNull(snapshot.sampleFiles[sample.id])
                zip.putNextEntry(ZipEntry("samples/${sample.id}.wav")); bounded.write(bytes); zip.closeEntry()
            }
        }
    }

    fun read(input: InputStream): BackupSnapshot {
        // ZipInputStream alone accepts a missing central directory. Require the complete
        // envelope produced by v1, and bound compressed data as well as inflated entries.
        val raw = ByteArrayOutputStream()
        input.use { source ->
            val buffer = ByteArray(8192)
            while (true) {
                val count = source.read(buffer)
                if (count < 0) break
                require(raw.size() + count <= MAX_ARCHIVE_BYTES + 1024 * 1024)
                raw.write(buffer, 0, count)
            }
        }
        val archive = raw.toByteArray()
        require(archive.size >= 22)
        val envelope = java.nio.ByteBuffer.wrap(archive).order(java.nio.ByteOrder.LITTLE_ENDIAN)
        val end = (archive.size - 22 downTo (archive.size - 65557).coerceAtLeast(0)).firstOrNull {
            envelope.getInt(it) == 0x06054b50 && it + 22 + (envelope.getShort(it + 20).toInt() and 65535) == archive.size
        } ?: throw IllegalArgumentException("Incomplete ZIP")
        require(envelope.getShort(end + 4).toInt() == 0 && envelope.getShort(end + 6).toInt() == 0)
        val entryCount = envelope.getShort(end + 10).toInt() and 65535
        require(entryCount in 1..33 && envelope.getShort(end + 8) == envelope.getShort(end + 10))
        val directorySize = envelope.getInt(end + 12).toLong()
        val directoryOffset = envelope.getInt(end + 16).toLong()
        require(directorySize >= 0 && directoryOffset >= 0 && directorySize + directoryOffset == end.toLong())
        val entries = linkedMapOf<String, ByteArray>()
        var total = 0
        ZipInputStream(ByteArrayInputStream(archive)).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                require(!entry.isDirectory && entry.name !in entries && !entry.name.contains(".."))
                require(entries.size < 33) // One metadata entry and at most 32 samples.
                val bytes = ByteArrayOutputStream()
                val buffer = ByteArray(8192)
                while (true) {
                    val count = zip.read(buffer)
                    if (count < 0) break
                    total += count; require(total <= MAX_ARCHIVE_BYTES); bytes.write(buffer, 0, count)
                }
                entries[entry.name] = bytes.toByteArray(); zip.closeEntry()
            }
        }
        require(entries.size == entryCount)
        var cursor = directoryOffset.toInt()
        val indexed = hashSetOf<String>()
        repeat(entryCount) {
            require(cursor + 46 <= end && envelope.getInt(cursor) == 0x02014b50)
            val nameSize = envelope.getShort(cursor + 28).toInt() and 65535
            val extraSize = envelope.getShort(cursor + 30).toInt() and 65535
            val commentSize = envelope.getShort(cursor + 32).toInt() and 65535
            val next = cursor + 46 + nameSize + extraSize + commentSize
            require(next <= end)
            val name = String(archive, cursor + 46, nameSize, Charsets.UTF_8)
            require(indexed.add(name))
            val content = requireNotNull(entries[name])
            require(envelope.getInt(cursor + 24) == content.size)
            val crc = java.util.zip.CRC32().apply { update(content) }.value
            require((envelope.getInt(cursor + 16).toLong() and 0xffffffffL) == crc)
            cursor = next
        }
        require(cursor == end)
        val metadata = DataInputStream(ByteArrayInputStream(requireNotNull(entries.remove(METADATA))))
        require(metadata.readInt() == VERSION)
        val settings = metadata.readString().also(ConfigCodec::decode)
        val presets = metadata.readList { Preset(readString(), readString(), readString().also(ConfigCodec::decode), readLong(), readLong()) }
        val sessions = metadata.readList {
            PracticeSession(readString(), readLong(), readLong(), readLong(), readBoolean(), readBoolean(), readString(), readString().also(ConfigCodec::decode))
        }
        val days = metadata.readList { PracticeDay(readString(), readString(), readLong()) }
        val samples = metadata.readList { CustomSampleRecord(readString(), readString(), readLong()) }
        require(metadata.read() == -1)
        require(presets.map { it.id }.distinct().size == presets.size)
        require(sessions.map { it.id }.distinct().size == sessions.size)
        require(samples.size <= 32 && samples.map { it.id }.distinct().size == samples.size)
        require(presets.all { it.name.isNotBlank() && it.name.length <= 120 })
        require(samples.all { it.name.isNotBlank() && it.name.length <= 80 })
        require(sessions.all { it.durationMs >= 0 && it.checkpointAt >= it.startedAt })
        sessions.forEach { ZoneId.of(it.zoneId) }
        val sessionIds = sessions.mapTo(hashSetOf()) { it.id }
        require(days.all { it.sessionId in sessionIds && it.durationMs >= 0 })
        days.forEach { LocalDate.parse(it.date) }
        require(days.map { it.sessionId to it.date }.distinct().size == days.size)
        val files = samples.associate { sample ->
            UUID.fromString(sample.id).also { require(it.toString() == sample.id) }
            val bytes = requireNotNull(entries.remove("samples/${sample.id}.wav"))
            SamplePcm.decodeCanonical(bytes)
            sample.id to bytes
        }
        val sampleIds = samples.mapTo(hashSetOf()) { it.id }
        val configs = buildList {
            add(ConfigCodec.decode(settings))
            presets.forEach { add(ConfigCodec.decode(it.config)) }
        }
        require(configs.all { it.sound.customSampleId == null || it.sound.customSampleId in sampleIds })
        require(entries.isEmpty())
        return BackupSnapshot(settings, presets, sessions, days, samples, files)
    }

    private fun DataOutputStream.writeString(value: String) {
        val bytes = value.toByteArray(Charsets.UTF_8); require(bytes.size <= 1_000_000)
        writeInt(bytes.size); write(bytes)
    }
    private fun DataInputStream.readString(): String {
        val size = readInt(); require(size in 0..1_000_000)
        return String(ByteArray(size).also(::readFully), Charsets.UTF_8)
    }
    private fun <T> DataOutputStream.writeList(items: List<T>, writeItem: DataOutputStream.(T) -> Unit) {
        require(items.size <= MAX_ITEMS); writeInt(items.size); items.forEach { writeItem(it) }
    }
    private fun <T> DataInputStream.readList(readItem: DataInputStream.() -> T): List<T> {
        val count = readInt(); require(count in 0..MAX_ITEMS)
        return List(count) { readItem() }
    }
}
