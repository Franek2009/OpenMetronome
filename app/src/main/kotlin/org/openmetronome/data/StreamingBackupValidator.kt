package org.openmetronome.data

import android.database.sqlite.SQLiteDatabase
import java.io.DataInputStream
import java.io.File
import java.io.FilterInputStream
import java.io.InputStream
import java.io.PushbackInputStream
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import org.openmetronome.data.BackupArchive.readMusicActivity
import org.openmetronome.data.BackupArchive.readString
import org.openmetronome.domain.audio.SamplePcm
import org.openmetronome.domain.model.MusicActivityDetails

/** Validation does not open the live database or extract names from the archive to paths. */
internal class StreamingBackupValidator(private val limits: BackupStreamLimits, private val checkActive: () -> Unit) {
    fun validate(file: File, index: File) {
        val central = centralEntries(file)

        ValidationKeys(index).use { keys ->
            var remaining = limits.expanded
            val seen = hashSetOf<String>()
            ZipInputStream(file.inputStream().buffered()).use { zip ->
                while (true) {
                    checkActive()
                    val entry = zip.nextEntry ?: break
                    val expected = requireNotNull(central[entry.name])
                    require(seen.add(entry.name) && expected.method == entry.method) { "Duplicate or inconsistent ZIP entry" }
                    val max = if (entry.name == BackupArchive.METADATA) remaining else
                        minOf(remaining, (44 + SamplePcm.MAX_FRAMES * 2).toLong())
                    require(expected.size <= max) { "Backup expanded byte limit exceeded" }
                    val checked = EntryStream(zip, expected.size, checkActive)
                    if (entry.name == BackupArchive.METADATA) validateMetadata(checked, keys)
                    else SamplePcm.decodeCanonical(checked.readBytes())
                    require(checked.read() == -1 && checked.count == expected.size && checked.crc.value == expected.crc &&
                        entry.compressedSize == expected.compressedSize) {
                        "Backup ZIP entry length or CRC differs"
                    }
                    remaining -= checked.count
                    zip.closeEntry()
                }
            }
            require(seen == central.keys)
            val samples = seen.mapNotNull(::sampleId).toSet()
            require(samples == keys.samples())
            keys.verifySampleReferences()
        }
    }

    private fun validateMetadata(source: InputStream, keys: ValidationKeys) {
        val peek = PushbackInputStream(source.buffered(), 1)
        val input = DataInputStream(peek)
        fun hasMore(): Boolean {
            val byte = peek.read()
            if (byte == -1) return false
            peek.unread(byte)
            return true
        }
        fun count(limit: Int = limits.items): Int = input.readInt().also { require(it in 0..limit) }
        fun referencedSample(config: String) {
            ConfigCodec.decode(config).sound.customSampleId?.let(keys::sampleReference)
        }
        val version = input.readInt()
        require(version in 1..BackupArchive.VERSION)
        referencedSample(input.readString())
        repeat(count()) {
            checkActive()
            keys.insert("presets", input.readString())
            require(input.readString().let { it.isNotBlank() && it.length <= 120 })
            referencedSample(input.readString())
            input.readLong(); input.readLong()
        }
        var daysRemaining = limits.items
        repeat(count()) {
            checkActive()
            val id = input.readString()
            val start = input.readLong()
            val checkpoint = input.readLong()
            val duration = input.readLong()
            input.readBoolean(); input.readBoolean()
            require(checkpoint >= start && duration >= 0)
            val zone = ZoneId.of(input.readString())
            ConfigCodec.decode(input.readString())
            if (version >= 2 && input.readBoolean()) SessionTimelineCodec.decode(input.readString(), duration)
            keys.insert("sessions", id)
            // Validate the public normalization without retaining derived day rows.
            daysRemaining -= PracticeTimeline.visitDays(id, start, duration, zone, daysRemaining) { checkActive() }
        }
        repeat(count()) {
            checkActive()
            val session = input.readString()
            val day = input.readString()
            LocalDate.parse(day)
            require(input.readLong() >= 0)
            keys.day(session, day)
        }
        repeat(count(32)) {
            val id = input.readString()
            require(UUID.fromString(id).toString() == id)
            require(input.readString().let { it.isNotBlank() && it.length <= 80 })
            input.readLong()
            keys.insert("samples", id)
        }
        if (version >= 2 && input.readBoolean()) AppPreferencesCodec.decode(input.readString())
        if (version >= 3) {
            val activities = count(limits.activities)
            var remaining = limits.intervals
            repeat(activities) {
                checkActive()
                val value = input.readMusicActivity(remaining)
                remaining -= value.details.intervals.size
                keys.insert("activities", value.id)
            }
            if (hasMore()) {
                val notes = count(activities)
                require(notes > 0 || hasMore())
                repeat(notes) {
                    checkActive()
                    keys.insert("notes", input.readString(36))
                    val note = input.readString(MusicActivityDetails.MAX_NOTE_LENGTH * 3)
                    require(note.isNotBlank() && note.length <= MusicActivityDetails.MAX_NOTE_LENGTH)
                }
            }
            if (hasMore()) {
                require(input.readInt() == BackupArchive.GROUPING_SECTION)
                val corrections = count(limits.corrections)
                require(corrections > 0)
                repeat(corrections) {
                    checkActive()
                    val value = GroupingCorrection(input.readString(36), input.readLong(),
                        GroupingCorrectionKind.valueOf(input.readString(12)), input.readString(36),
                        if (input.readBoolean()) input.readString(36) else null,
                        if (input.readBoolean()) input.readLong() else null)
                    keys.correction(value.id, value.sequenceNumber)
                }
            }
        }
        require(!hasMore()) { "Unexpected backup metadata after final section" }
    }

    private fun sampleId(name: String): String? {
        if (!name.startsWith("samples/") || !name.endsWith(".wav") || name.length != 48) return null
        val id = name.removePrefix("samples/").removeSuffix(".wav")
        return id.takeIf { runCatching { UUID.fromString(it).toString() == it }.getOrDefault(false) }
    }

    private data class CentralEntry(
        val localOffset: Long, val flags: Int, val method: Int, val crc: Long, val compressedSize: Long, val size: Long,
    ) {
        fun matchesPayload(header: ByteBuffer, offset: Int) = header.zipUInt(offset) == crc &&
            header.zipUInt(offset + 4) == compressedSize && header.zipUInt(offset + 8) == size
    }

    private fun centralEntries(file: File): Map<String, CentralEntry> = RandomAccessFile(file, "r").use { input ->
        require(input.length() in 22..limits.compressed)
        val tail = ByteArray(minOf(input.length(), 65_557).toInt())
        input.seek(input.length() - tail.size)
        input.readFully(tail)
        val bytes = ByteBuffer.wrap(tail).order(ByteOrder.LITTLE_ENDIAN)
        val end = (tail.size - 22 downTo 0).firstOrNull {
            bytes.getInt(it) == 0x06054b50 && it + 22 + (bytes.getShort(it + 20).toInt() and 65535) == tail.size
        } ?: throw IllegalArgumentException("Incomplete ZIP")
        require(bytes.getShort(end + 4).toInt() == 0 && bytes.getShort(end + 6).toInt() == 0)
        val count = bytes.getShort(end + 10).toInt() and 65535
        require(count in 1..33 && bytes.getShort(end + 8) == bytes.getShort(end + 10))
        val size = bytes.getInt(end + 12).toLong()
        val offset = bytes.getInt(end + 16).toLong()
        val directoryEnd = input.length() - tail.size + end
        require(size >= 0 && offset >= 0 && size + offset == directoryEnd)
        val header = ByteBuffer.allocate(46).order(ByteOrder.LITTLE_ENDIAN)
        val names = linkedMapOf<String, CentralEntry>()
        input.seek(offset)
        repeat(count) {
            checkActive()
            require(input.filePointer + header.capacity() <= directoryEnd)
            input.readFully(header.array())
            require(header.getInt(0) == 0x02014b50 && header.zipUShort(34) == 0 && header.zipUInt(42) < offset)
            val nameLength = header.zipUShort(28)
            val next = input.filePointer + nameLength + header.zipUShort(30) + header.zipUShort(32)
            require(next <= directoryEnd && nameLength in 1..48)
            val name = ByteArray(nameLength).also(input::readFully).toString(Charsets.UTF_8)
            require(name == BackupArchive.METADATA || sampleId(name) != null)
            val entry = CentralEntry(header.zipUInt(42), header.zipUShort(8), header.zipUShort(10),
                header.zipUInt(16), header.zipUInt(20), header.zipUInt(24))
            require(names.put(name, entry) == null) { "Duplicate backup ZIP entry" }
            input.seek(next)
        }
        require(input.filePointer == directoryEnd && BackupArchive.METADATA in names)
        verifyLocalRecords(input, offset, names)
        names
    }

    private fun verifyLocalRecords(input: RandomAccessFile, directoryOffset: Long, names: Map<String, CentralEntry>) {
        val records = names.entries.sortedBy { it.value.localOffset }
        require(records.first().value.localOffset == 0L) { "Backup ZIP must begin with its first local header" }
        val local = ByteBuffer.allocate(30).order(ByteOrder.LITTLE_ENDIAN)
        val descriptor = ByteBuffer.allocate(16).order(ByteOrder.LITTLE_ENDIAN)
        records.forEachIndexed { index, (name, entry) ->
            checkActive()
            val end = records.getOrNull(index + 1)?.value?.localOffset ?: directoryOffset
            require(entry.localOffset + local.capacity() <= end) { "Overlapping ZIP local headers" }
            input.seek(entry.localOffset)
            input.readFully(local.array())
            require(local.getInt(0) == 0x04034b50 && local.zipUShort(6) == entry.flags &&
                local.zipUShort(8) == entry.method) { "ZIP local header differs from its central record" }
            val nameLength = local.zipUShort(26)
            val dataEnd = input.filePointer + nameLength + local.zipUShort(28) + entry.compressedSize
            require(nameLength == name.length && dataEnd <= end) { "ZIP local record crosses the next boundary" }
            require(ByteArray(nameLength).also(input::readFully).toString(Charsets.UTF_8) == name) {
                "ZIP local entry name differs from its central record"
            }
            val descriptorSize = end - dataEnd
            if (entry.flags and 0x08 == 0) {
                require(descriptorSize == 0L && entry.matchesPayload(local, 14)) { "Inconsistent ZIP local metadata or entry extent" }
            } else {
                require(entry.method == ZipEntry.DEFLATED && (descriptorSize == 12L || descriptorSize == 16L)) {
                    "Invalid ZIP data descriptor extent"
                }
                require(local.zipUInt(14) == 0L && local.zipUInt(18) == 0L && local.zipUInt(22) == 0L ||
                    entry.matchesPayload(local, 14)) { "Inconsistent ZIP local metadata with data descriptor" }
                input.seek(dataEnd)
                input.readFully(descriptor.array(), 0, descriptorSize.toInt())
                val fieldsOffset = if (descriptorSize == 16L) 4 else 0
                require((fieldsOffset == 0 || descriptor.getInt(0) == 0x08074b50) &&
                    entry.matchesPayload(descriptor, fieldsOffset)) { "ZIP data descriptor differs from its central record" }
            }
        }
    }

    private class EntryStream(source: InputStream, private val limit: Long, private val checkActive: () -> Unit) :
        FilterInputStream(source) {
        val crc = CRC32()
        var count = 0L
            private set
        override fun read(): Int {
            checkActive()
            val byte = super.read()
            if (byte >= 0) {
                require(++count <= limit)
                crc.update(byte)
            }
            return byte
        }
        override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
            checkActive()
            if (length == 0) return 0
            val actual = super.read(buffer, offset, minOf(length.toLong(), limit - count + 1).toInt())
            if (actual >= 0) {
                count += actual
                require(count <= limit)
                crc.update(buffer, offset, actual)
            }
            return actual
        }
    }

    private class ValidationKeys(index: File) : AutoCloseable {
        private val db = SQLiteDatabase.openOrCreateDatabase(index, null)
        private val statements = mutableMapOf<String, android.database.sqlite.SQLiteStatement>()
        init {
            try {
                db.rawQuery("PRAGMA journal_mode=OFF", null).use { it.moveToFirst() }
                db.execSQL("PRAGMA foreign_keys=ON")
                db.execSQL("PRAGMA cache_size=-1024")
                db.beginTransaction()
                for (table in listOf("presets", "sessions", "samples", "activities", "sample_refs")) {
                    db.execSQL("CREATE TABLE $table (id TEXT NOT NULL PRIMARY KEY) WITHOUT ROWID")
                }
                db.execSQL("CREATE TABLE notes (id TEXT NOT NULL PRIMARY KEY REFERENCES activities(id)) WITHOUT ROWID")
                db.execSQL("CREATE TABLE days (id TEXT NOT NULL REFERENCES sessions(id), day TEXT NOT NULL, PRIMARY KEY(id,day)) WITHOUT ROWID")
                db.execSQL("CREATE TABLE corrections (id TEXT NOT NULL PRIMARY KEY, seq INTEGER NOT NULL UNIQUE) WITHOUT ROWID")
            } catch (error: Throwable) {
                close()
                throw error
            }
        }

        fun insert(table: String, id: String) = statement("INSERT INTO $table VALUES(?)").run { bindString(1, id); execute() }
        fun sampleReference(id: String) = statement("INSERT OR IGNORE INTO sample_refs VALUES(?)").run { bindString(1, id); execute() }
        fun day(id: String, date: String) = statement("INSERT INTO days VALUES(?,?)").run {
            bindString(1, id); bindString(2, date); execute()
        }
        fun correction(id: String, seq: Long) = statement("INSERT INTO corrections VALUES(?,?)").run {
            bindString(1, id); bindLong(2, seq); execute()
        }
        fun samples(): Set<String> = db.rawQuery("SELECT id FROM samples", null).use {
            buildSet { while (it.moveToNext()) add(it.getString(0)) }
        }
        fun verifySampleReferences() {
            db.rawQuery("SELECT id FROM sample_refs EXCEPT SELECT id FROM samples LIMIT 1", null).use {
                require(!it.moveToFirst()) { "Backup refers to a missing sample" }
            }
        }
        private fun statement(sql: String) = statements.getOrPut(sql) { db.compileStatement(sql) }
        override fun close() {
            try {
                statements.values.forEach { it.close() }
                if (db.inTransaction()) db.endTransaction()
            } finally { db.close() }
        }
    }
}

private fun ByteBuffer.zipUShort(offset: Int) = getShort(offset).toInt() and 65535
private fun ByteBuffer.zipUInt(offset: Int) = getInt(offset).toLong() and 0xffffffffL
