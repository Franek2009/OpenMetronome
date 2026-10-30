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
        ZipOutputStream(BufferedOutputStream(output)).use { zip ->
            zip.putNextEntry(ZipEntry(METADATA))
            DataOutputStream(zip).apply {
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
                zip.putNextEntry(ZipEntry("samples/${sample.id}.wav")); zip.write(bytes); zip.closeEntry()
            }
        }
    }

    fun read(input: InputStream): BackupSnapshot {
        val entries = linkedMapOf<String, ByteArray>()
        var total = 0
        ZipInputStream(BufferedInputStream(input)).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                require(!entry.isDirectory && entry.name !in entries && !entry.name.contains(".."))
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
            val decoded = SamplePcm.decodeWav(bytes)
            require(decoded.rate == SamplePcm.RATE && decoded.mono.size in 48..SamplePcm.MAX_FRAMES)
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
