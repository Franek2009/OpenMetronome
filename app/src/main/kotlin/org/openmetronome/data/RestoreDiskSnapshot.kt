package org.openmetronome.data

import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import androidx.sqlite.db.SupportSQLiteDatabase
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.util.UUID
import org.openmetronome.domain.audio.SamplePcm

/**
 * Private journal-v2 row storage. The table/column projections are part of the
 * journal protocol and must remain readable after a live Room migration.
 */
internal class RestoreDiskSnapshot(val directory: File) {
    private val records = File(directory, "records.db")
    val preferences = File(directory, "preferences.pb")
    val samples = File(directory, "samples")
    fun sizeOnDisk(): Long = records.length() + preferences.length() + samples.listFiles().orEmpty().sumOf { it.length() }

    fun create(write: (Writer) -> Unit) {
        check(directory.mkdir())
        check(samples.mkdir())
        SQLiteDatabase.openOrCreateDatabase(records, null).use { database ->
            database.rawQuery("PRAGMA journal_mode=DELETE", null).use {
                check(it.moveToFirst() && it.getString(0) == "delete")
            }
            database.execSQL("PRAGMA cache_size=-1024")
            database.beginTransaction()
            try {
                for (table in Table.entries) {
                    database.execSQL("CREATE TABLE ${table.sqlName} (${table.definition})")
                }
                database.execSQL("CREATE UNIQUE INDEX restore_note_target ON music_activities(id)")
                write(Writer(database, samples))
                database.setTransactionSuccessful()
            } finally {
                database.endTransaction()
            }
        }
    }

    fun verifyRows() = read { database ->
        database.rawQuery("PRAGMA quick_check(1)", null).use {
            require(it.moveToFirst() && it.getString(0) == "ok") { "Unreadable restore snapshot database" }
        }
        for (table in Table.entries) {
            database.rawQuery(table.select + " LIMIT 0", null).use { cursor ->
                require(cursor.columnNames.toList() == table.columns)
            }
        }
    }

    fun applyRows(live: SupportSQLiteDatabase) = read { database ->
        for (table in Table.entries.reversed()) live.execSQL("DELETE FROM ${table.sqlName}")
        for (table in Table.entries) {
            database.rawQuery(table.select, null).use { cursor ->
                while (cursor.moveToNext()) live.execSQL(table.insert, cursor.row())
            }
        }
    }

    fun sampleRecords(): List<CustomSampleRecord> = read { database ->
        database.rawQuery(Table.SAMPLES.select + " ORDER BY createdAt, id LIMIT 33", null).use { cursor ->
            buildList {
                while (cursor.moveToNext()) {
                    require(size < 32)
                    add(CustomSampleRecord(cursor.getString(0), cursor.getString(1), cursor.getLong(2)))
                }
            }
        }
    }

    fun wav(id: String): ByteArray {
        require(UUID.fromString(id).toString() == id)
        val file = File(samples, "$id.wav")
        require(file.length() in 46L..(44L + SamplePcm.MAX_FRAMES * 2L))
        return file.readBytes()
    }

    fun sync(syncDirectory: (File) -> Unit) {
        require(!File(records.path + "-wal").exists() && !File(records.path + "-journal").exists())
        val files = requireNotNull(samples.listFiles())
        val expected = sampleRecords()
        require(files.map { it.name }.toSet() == expected.map { "${it.id}.wav" }.toSet())
        for (file in listOf(records, preferences) + files) {
            require(file.isFile && !java.nio.file.Files.isSymbolicLink(file.toPath()))
            FileInputStream(file).use { it.fd.sync() }
        }
        expected.forEach { SamplePcm.decodeCanonical(wav(it.id)) }
        syncDirectory(samples)
        syncDirectory(directory)
    }

    private fun <T> read(block: (SQLiteDatabase) -> T): T =
        SQLiteDatabase.openDatabase(records.absolutePath, null, SQLiteDatabase.OPEN_READONLY).use(block)

    internal class Writer(private val database: SQLiteDatabase, private val sampleDirectory: File) :
        RestoreArchiveSink {
        private fun insert(table: Table, vararg values: Any?) = database.execSQL(table.insert, values)

        fun captureRows(live: SupportSQLiteDatabase, checkActive: () -> Unit) {
            for (table in Table.entries) {
                live.query(table.select).use { cursor ->
                    while (cursor.moveToNext()) {
                        checkActive()
                        database.execSQL(table.insert, cursor.row())
                    }
                }
            }
        }

        override fun preset(value: Preset) = with(value) { insert(Table.PRESETS, id, name, config, createdAt, updatedAt) }

        override fun session(value: PracticeSession) = with(value) {
            insert(Table.SESSIONS, id, startedAt, checkpointAt, durationMs, if (finished) 1 else 0,
                if (interrupted) 1 else 0, zoneId, config, timeline)
        }

        override fun day(value: PracticeDay) = with(value) { insert(Table.DAYS, sessionId, date, durationMs) }

        override fun sample(value: CustomSampleRecord) = with(value) { insert(Table.SAMPLES, id, name, createdAt) }

        override fun wav(id: String, bytes: ByteArray) {
            require(java.util.UUID.fromString(id).toString() == id)
            FileOutputStream(File(sampleDirectory, "$id.wav")).use { it.write(bytes) }
        }

        override fun activity(value: org.openmetronome.domain.model.MusicActivity) {
            val row = MusicActivityRow(value)
            insert(Table.ACTIVITIES, row.id, row.kind, row.source, row.startAt, row.endAt, row.zoneId,
                row.timerDurationMs, row.intervalResolutionMs, row.instrument, row.note, row.createdAt,
                row.updatedAt, row.deletedAt)
            for (interval in value.details.intervals) {
                insert(Table.INTERVALS, row.id, interval.startAt, interval.endAt)
            }
        }

        override fun note(id: String, note: String) {
            database.execSQL("UPDATE music_activities SET note=? WHERE id=?", arrayOf(note, id))
        }

        override fun correction(value: GroupingCorrection) = with(value) {
            insert(Table.CORRECTIONS, id, sequenceNumber, kind.name, playbackId, otherPlaybackId, deletedAt)
        }
    }

    private enum class Table(val sqlName: String, val definition: String) {
        SAMPLES("custom_samples", "id TEXT,name TEXT,createdAt INTEGER"),
        PRESETS("presets", "id TEXT,name TEXT,config TEXT,createdAt INTEGER,updatedAt INTEGER"),
        SESSIONS("practice_sessions", "id TEXT,startedAt INTEGER,checkpointAt INTEGER,durationMs INTEGER,finished INTEGER,interrupted INTEGER,zoneId TEXT,config TEXT,timeline TEXT"),
        DAYS("practice_days", "sessionId TEXT,date TEXT,durationMs INTEGER"),
        ACTIVITIES("music_activities", "id TEXT,kind TEXT,source TEXT,startAt INTEGER,endAt INTEGER,zoneId TEXT,timerDurationMs INTEGER,intervalResolutionMs INTEGER,instrument TEXT,note TEXT,createdAt INTEGER,updatedAt INTEGER,deletedAt INTEGER"),
        INTERVALS("music_activity_intervals", "activityId TEXT,startAt INTEGER,endAt INTEGER"),
        CORRECTIONS("grouping_corrections", "id TEXT,sequenceNumber INTEGER,kind TEXT,playbackId TEXT,otherPlaybackId TEXT,deletedAt INTEGER");

        val columns = definition.split(',').map { it.substringBefore(' ') }
        private val projection = columns.joinToString(",")
        val select = "SELECT $projection FROM $sqlName"
        val insert = "INSERT INTO $sqlName($projection) VALUES(${columns.joinToString(",") { "?" }})"
    }
}

private fun Cursor.row(): Array<Any?> {
    var bytes = 0
    return Array(columnCount) { i ->
        when (getType(i)) {
            Cursor.FIELD_TYPE_NULL -> null
            Cursor.FIELD_TYPE_INTEGER -> getLong(i)
            Cursor.FIELD_TYPE_STRING -> getString(i).also {
                bytes += it.toByteArray(Charsets.UTF_8).size
                require(bytes <= 2 * 1024 * 1024) { "Restore row exceeds the private row byte budget" }
            }
            else -> error("Unexpected type in restore row")
        }
    }
}
