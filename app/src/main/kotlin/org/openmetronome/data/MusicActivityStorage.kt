package org.openmetronome.data

import androidx.room.*
import kotlinx.coroutines.flow.Flow
import org.openmetronome.domain.model.MusicActivity
import org.openmetronome.domain.model.MusicActivityDetails
import org.openmetronome.domain.model.MusicActivityInterval
import org.openmetronome.domain.model.MusicActivityKind
import org.openmetronome.domain.model.MusicActivitySource

@Entity(tableName = "music_activities", indices = [Index(value = ["deletedAt", "startAt"])])
internal data class MusicActivityRow(
    @PrimaryKey val id: String,
    val kind: String,
    val source: String,
    val startAt: Long,
    val endAt: Long,
    val zoneId: String,
    val timerDurationMs: Long,
    val intervalResolutionMs: Long?,
    val instrument: String?,
    val note: String?,
    val createdAt: Long,
    val updatedAt: Long,
    val deletedAt: Long?,
) {
    constructor(activity: MusicActivity) : this(
        activity.id, activity.details.kind.name, activity.details.source.name, activity.details.startAt,
        activity.details.endAt, activity.details.zoneId, activity.details.timerDurationMs,
        activity.details.intervalResolutionMs, activity.details.instrument, activity.details.note, activity.createdAt,
        activity.updatedAt, activity.deletedAt,
    )
}

@Entity(tableName = "music_activity_intervals", primaryKeys = ["activityId", "startAt"],
    foreignKeys = [ForeignKey(entity = MusicActivityRow::class, parentColumns = ["id"],
        childColumns = ["activityId"], onDelete = ForeignKey.CASCADE)])
internal data class MusicActivityIntervalRow(val activityId: String, val startAt: Long, val endAt: Long)

internal data class StoredMusicActivity(
    @Embedded val row: MusicActivityRow,
    @Relation(parentColumn = "id", entityColumn = "activityId") val intervals: List<MusicActivityIntervalRow>,
) {
    fun toDomain() = with(row) {
        MusicActivity(id, MusicActivityDetails(MusicActivityKind.valueOf(kind), MusicActivitySource.valueOf(source),
            startAt, endAt, zoneId, timerDurationMs, intervalResolutionMs,
            intervals.sortedBy { it.startAt }.map { MusicActivityInterval(it.startAt, it.endAt) }, instrument, note),
            createdAt, updatedAt, deletedAt)
    }
}

internal data class MusicActivityBackupSize(val activities: Long, val intervals: Long, val activityBytes: Long)

@Dao
internal abstract class MusicActivityDao {
    @Transaction
    @Query("SELECT * FROM music_activities WHERE deletedAt IS NULL ORDER BY startAt DESC, id")
    abstract fun active(): Flow<List<StoredMusicActivity>>

    @Transaction
    @Query("SELECT * FROM music_activities ORDER BY startAt, id")
    abstract suspend fun all(): List<StoredMusicActivity>

    @Query("""
        SELECT startedAt AS startAt, durationMs AS extent, 1 AS playback
        FROM practice_sessions WHERE durationMs > 0
        UNION ALL
        SELECT intervals.startAt, intervals.endAt, 0
        FROM music_activity_intervals AS intervals
        JOIN music_activities AS activity ON activity.id = intervals.activityId
        WHERE activity.deletedAt IS NULL
        ORDER BY startAt
    """)
    abstract fun statisticsRanges(): Flow<List<MusicStatisticsRange>>

    // Serialized record size: 70 fixed bytes, then optional instrument, deletion and note fields.
    @Query("""
        SELECT COUNT(*) AS activities, (SELECT COUNT(*) FROM music_activity_intervals) AS intervals,
            4 + CASE WHEN COUNT(note) > 0 THEN 4 ELSE 0 END +
            COALESCE(SUM(70 + length(CAST(id AS BLOB)) + length(CAST(kind AS BLOB)) + length(CAST(source AS BLOB)) +
                length(CAST(zoneId AS BLOB)) + COALESCE(4 + length(CAST(instrument AS BLOB)), 0) +
                CASE WHEN deletedAt IS NULL THEN 0 ELSE 8 END + COALESCE(44 + length(CAST(note AS BLOB)), 0)), 0) AS activityBytes
        FROM music_activities
    """)
    abstract suspend fun backupSize(): MusicActivityBackupSize

    @Query("SELECT * FROM music_activities WHERE id = :id")
    protected abstract suspend fun find(id: String): MusicActivityRow?

    @Insert
    protected abstract suspend fun insertRow(row: MusicActivityRow)

    @Update
    protected abstract suspend fun updateRow(row: MusicActivityRow)

    @Insert
    protected abstract suspend fun insertIntervals(rows: List<MusicActivityIntervalRow>)

    @Query("DELETE FROM music_activity_intervals WHERE activityId = :id")
    protected abstract suspend fun clearIntervals(id: String)

    @Query("DELETE FROM music_activities")
    protected abstract suspend fun clearActivities()

    @Transaction
    open suspend fun insert(activity: MusicActivity) {
        insertRow(MusicActivityRow(activity))
        insertIntervals(activity.intervalRows())
    }

    @Transaction
    open suspend fun edit(id: String, details: MusicActivityDetails, now: Long) {
        val previous = requireNotNull(find(id)) { "Unknown music activity" }
        check(previous.deletedAt == null) { "Music activity was deleted" }
        check(details.source.name == previous.source) { "Cannot change activity source" }
        val edited = MusicActivity(id, details, previous.createdAt, maxOf(now, previous.updatedAt))
        updateRow(MusicActivityRow(edited))
        clearIntervals(id)
        insertIntervals(edited.intervalRows())
    }

    @Transaction
    open suspend fun delete(id: String, now: Long) {
        val row = requireNotNull(find(id)) { "Unknown music activity" }
        if (row.deletedAt != null) return
        val deletedAt = maxOf(now, row.updatedAt)
        updateRow(row.copy(deletedAt = deletedAt, updatedAt = deletedAt))
    }

    @Transaction
    open suspend fun replaceAll(activities: List<MusicActivity>) {
        clearActivities()
        for (activity in activities) insert(activity)
    }

    private fun MusicActivity.intervalRows() =
        details.intervals.map { MusicActivityIntervalRow(id, it.startAt, it.endAt) }
}
