package org.openmetronome.data

import android.database.Cursor
import androidx.sqlite.db.SupportSQLiteDatabase
import java.io.DataOutputStream
import java.io.File
import java.io.OutputStream
import java.time.ZoneId
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.openmetronome.data.BackupArchive.writeMusicActivity
import org.openmetronome.data.BackupArchive.writeString

internal data class BackupStreamLimits(
    val expanded: Long = BackupArchive.MAX_ARCHIVE_BYTES.toLong(),
    val compressed: Long = expanded + 1024 * 1024,
    val items: Int = BackupArchive.MAX_ITEMS,
    val activities: Int = BackupArchive.MAX_MUSIC_ACTIVITIES,
    val intervals: Int = BackupArchive.MAX_MUSIC_INTERVALS,
    val corrections: Int = BackupArchive.MAX_GROUPING_CORRECTIONS,
)

internal enum class BackupExportPhase { PREPARATION, ENCODING, VALIDATION, DELIVERY }

/** The caller pins one Room transaction and the sample library for the entire write. */
internal class StreamingBackupWriter(
    private val db: SupportSQLiteDatabase,
    private val limits: BackupStreamLimits,
    private val checkActive: () -> Unit,
) {
    fun write(file: File, version: Int, settings: String, preferences: String?, sample: (String) -> ByteArray,
        phase: (BackupExportPhase) -> Unit = {}) {
        require(version in 1..BackupArchive.VERSION)
        phase(BackupExportPhase.PREPARATION)
        val counts = counts(version)
        var normalizedDays = 0
        visitSessionDates { id, start, duration, zone ->
            normalizedDays += PracticeTimeline.visitDays(id, start, duration, zone, limits.items - normalizedDays) { checkActive() }
        }
        phase(BackupExportPhase.ENCODING)
        file.outputStream().use { raw ->
            ZipOutputStream(BackupOutputLimit(raw, limits.compressed, checkActive).buffered()).use { zip ->
                val out = DataOutputStream(BackupOutputLimit(zip, limits.expanded, checkActive))
                zip.putNextEntry(ZipEntry(BackupArchive.METADATA))
                out.writeInt(version)
                if (version == 1) BackupArchive.requireLegacyConfig(settings)
                out.writeString(settings)
                out.writeInt(counts.presets)
                rows("presets", "id,name,config,createdAt,updatedAt", "id", "id,name,config") {
                    val config = it.getString(2)
                    if (version == 1) BackupArchive.requireLegacyConfig(config)
                    out.writeString(it.getString(0)); out.writeString(it.getString(1)); out.writeString(config)
                    out.writeLong(it.getLong(3)); out.writeLong(it.getLong(4))
                }
                out.writeInt(counts.sessions)
                writeSessions(out, version)
                out.writeInt(normalizedDays)
                var remainingDays = normalizedDays
                visitSessionDates { id, start, duration, zone ->
                    remainingDays -= PracticeTimeline.visitDays(id, start, duration, zone, remainingDays) { day ->
                        out.writeString(day.sessionId); out.writeString(day.date); out.writeLong(day.durationMs)
                    }
                }
                check(remainingDays == 0)
                out.writeInt(counts.samples)
                rows("custom_samples", "id,name,createdAt", "id", "id,name") {
                    out.writeString(it.getString(0)); out.writeString(it.getString(1)); out.writeLong(it.getLong(2))
                }
                if (version >= 2) {
                    val payload = if (version == 2) AppPreferencesCodec.forCompatibility(preferences) else preferences
                    out.writeBoolean(payload != null)
                    payload?.let { out.writeString(it) }
                }
                if (version >= 3) writeActivities(out, counts)
                zip.closeEntry()
                rows("custom_samples", "id", "id", "id") {
                    val id = it.getString(0)
                    zip.putNextEntry(ZipEntry("samples/$id.wav"))
                    out.write(sample(id))
                    zip.closeEntry()
                }
            }
        }
    }

    private fun writeSessions(out: DataOutputStream, version: Int) {
        val timeline = if (version >= 2) "timeline" else "NULL"
        val payload = if (version >= 2) "id,zoneId,config,timeline" else "id,zoneId,config"
        rows("practice_sessions", "id,startedAt,checkpointAt,durationMs,finished,interrupted,zoneId,config,$timeline", "id", payload) {
            val started = it.getLong(1)
            val duration = it.getLong(3)
            require(duration >= 0 && it.getLong(2) >= started)
            val config = it.getString(7)
            if (version == 1) BackupArchive.requireLegacyConfig(config)
            out.writeString(it.getString(0)); out.writeLong(started); out.writeLong(Math.addExact(started, duration))
            out.writeLong(duration); out.writeBoolean(it.getInt(4) != 0); out.writeBoolean(it.getInt(5) != 0)
            out.writeString(it.getString(6)); out.writeString(config)
            if (version >= 2) {
                val value = it.nullableText(8)
                out.writeBoolean(value != null)
                if (value != null) out.writeString(value)
            }
        }
    }

    private fun writeActivities(out: DataOutputStream, counts: Counts) {
        out.writeInt(counts.activities)
        rows("music_activities",
            "id,kind,source,startAt,endAt,zoneId,timerDurationMs,intervalResolutionMs,instrument,createdAt,updatedAt,deletedAt",
            "id", "id,kind,source,zoneId,instrument") { cursor ->
            val row = MusicActivityRow(cursor.getString(0), cursor.getString(1), cursor.getString(2),
                cursor.getLong(3), cursor.getLong(4), cursor.getString(5), cursor.getLong(6), cursor.nullableLong(7),
                cursor.nullableText(8), null, cursor.getLong(9), cursor.getLong(10), cursor.nullableLong(11))
            val intervals = db.query("SELECT startAt,endAt FROM music_activity_intervals WHERE activityId=? ORDER BY startAt",
                arrayOf(row.id)).use {
                buildList {
                    while (it.moveToNext()) {
                        checkActive()
                        require(size < org.openmetronome.domain.model.MusicActivityDetails.MAX_INTERVALS)
                        add(MusicActivityIntervalRow(row.id, it.getLong(0), it.getLong(1)))
                    }
                }
            }
            out.writeMusicActivity(StoredMusicActivity(row, intervals).toDomain())
        }
        if (counts.notes > 0 || counts.corrections > 0) {
            out.writeInt(counts.notes)
            rows("music_activities", "id,note", "id", "id,note", "note IS NOT NULL") {
                out.writeString(it.getString(0)); out.writeString(it.getString(1))
            }
        }
        if (counts.corrections > 0) {
            out.writeInt(BackupArchive.GROUPING_SECTION)
            out.writeInt(counts.corrections)
            rows("grouping_corrections", "sequenceNumber,id,kind,playbackId,otherPlaybackId,deletedAt",
                "sequenceNumber", "id,kind,playbackId,otherPlaybackId") {
                out.writeString(it.getString(1)); out.writeLong(it.getLong(0)); out.writeString(it.getString(2))
                out.writeString(it.getString(3))
                val other = it.nullableText(4)
                out.writeBoolean(other != null); other?.let { value -> out.writeString(value) }
                val deleted = it.nullableLong(5)
                out.writeBoolean(deleted != null); deleted?.let(out::writeLong)
            }
        }
    }

    private fun visitSessionDates(visit: (String, Long, Long, ZoneId) -> Unit) =
        rows("practice_sessions", "id,startedAt,durationMs,zoneId", "id", "id,zoneId") {
            visit(it.getString(0), it.getLong(1), it.getLong(2), ZoneId.of(it.getString(3)))
        }

    private data class Counts(val sessions: Int, val presets: Int, val samples: Int,
        val activities: Int, val notes: Int, val corrections: Int)

    private fun counts(version: Int): Counts {
        fun scalar(sql: String): Long = db.query(sql).use { check(it.moveToFirst()); it.getLong(0) }
        fun count(table: String, limit: Int, filter: String = "1"): Int =
            scalar("SELECT count(*) FROM $table WHERE $filter").also {
                require(it <= limit) { "Backup row limit exceeded: $table" }
            }.toInt()
        val sessionBytes = "(38 + length(CAST(id AS BLOB)) + length(CAST(zoneId AS BLOB)) + length(CAST(config AS BLOB))" +
            (if (version >= 2) " + 1 + COALESCE(4 + length(CAST(timeline AS BLOB)), 0)" else "") + ")"
        val history = scalar("SELECT COALESCE(sum($sessionBytes),0) FROM practice_sessions")
        require(history <= limits.expanded) { "Backup history exceeds archive limit" }
        val sessions = count("practice_sessions", limits.items)
        val presets = count("presets", limits.items)
        val samples = count("custom_samples", 32)
        if (version < 3) return Counts(sessions, presets, samples, 0, 0, 0)
        val activities = count("music_activities", limits.activities)
        val intervals = count("music_activity_intervals", limits.intervals)
        val notes = count("music_activities", limits.activities, "note IS NOT NULL")
        val corrections = count("grouping_corrections", limits.corrections)
        require(history + 16L * intervals <= limits.expanded) { "Backup data exceeds archive limit" }
        return Counts(sessions, presets, samples, activities, notes, corrections)
    }

    // Keyset paging keeps cursors short. Advance only after a row is consumed.
    // Account for UTF-8 variable-width fields and a conservative allowance for scalar columns.
    private fun rows(table: String, projection: String, key: String, text: String,
        filter: String = "1", visit: (Cursor) -> Unit) {
        val payload = text.split(',').joinToString(" + ") { "COALESCE(length(CAST($it AS BLOB)),0)" }
        var after: String? = null // SQLite applies the indexed column's affinity to the bound value.
        while (true) {
            checkActive()
            val boundary = if (after == null) "" else "AND $key > ?"
            val sql = "SELECT $projection,$key,128 + $payload FROM $table WHERE ($filter) $boundary ORDER BY $key LIMIT 256"
            var more = false
            var used = 0L
            db.query(sql, after?.let { arrayOf(it) } ?: emptyArray()).use { cursor ->
                while (cursor.moveToNext()) {
                    checkActive()
                    val size = cursor.getLong(cursor.columnCount - 1)
                    require(size <= PAGE_BYTES) { "Backup row is too large to stream" }
                    if (used + size > PAGE_BYTES) { more = true; break }
                    visit(cursor)
                    after = cursor.getString(cursor.columnCount - 2)
                    used += size
                    if (cursor.position == 255) more = true
                }
            }
            if (!more) return
        }
    }

    private fun Cursor.nullableText(i: Int) = if (isNull(i)) null else getString(i)
    private fun Cursor.nullableLong(i: Int) = if (isNull(i)) null else getLong(i)

    companion object { private const val PAGE_BYTES = 2L * 1024 * 1024 }
}

/** Count before writing and check cancellation at byte-buffer boundaries. */
internal class BackupOutputLimit(private val out: OutputStream, private val limit: Long,
    private val checkActive: () -> Unit) : OutputStream() {
    private var written = 0L
    override fun write(value: Int) { count(1); out.write(value) }
    override fun write(buffer: ByteArray, offset: Int, length: Int) { count(length); out.write(buffer, offset, length) }
    override fun flush() = out.flush()
    override fun close() = out.close()
    private fun count(size: Int) {
        if (written / 8192 != (written + size) / 8192) checkActive()
        require(size <= limit - written) { "Backup byte limit exceeded" }
        written += size
    }
}
