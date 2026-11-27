package org.openmetronome.data

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.PrimaryKey
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "grouping_corrections", indices = [Index(value = ["sequenceNumber"], unique = true)])
internal data class GroupingCorrectionRow(
    @PrimaryKey val id: String, val sequenceNumber: Long, val kind: String,
    val playbackId: String, val otherPlaybackId: String?, val deletedAt: Long?,
) {
    constructor(value: GroupingCorrection) : this(value.id, value.sequenceNumber, value.kind.name,
        value.playbackId, value.otherPlaybackId, value.deletedAt)

    fun toDomain() = GroupingCorrection(id, sequenceNumber, GroupingCorrectionKind.valueOf(kind), playbackId, otherPlaybackId, deletedAt)
}

internal data class GroupingBackupSize(val records: Long, val bytes: Long)

@Dao
internal interface PracticeGroupingDao {
    @Query("SELECT id, startedAt, durationMs, finished FROM practice_sessions WHERE durationMs > 0")
    suspend fun playbacks(): List<GroupingPlayback>

    @Query("SELECT * FROM grouping_corrections ORDER BY sequenceNumber")
    suspend fun corrections(): List<GroupingCorrectionRow>

    @Query("SELECT * FROM grouping_corrections ORDER BY sequenceNumber")
    fun observeCorrections(): Flow<List<GroupingCorrectionRow>>

    // Each UUID uses 40 bytes, plus the sequence, kind, nullable flags and deletion timestamp.
    @Query("""
        SELECT COUNT(*) AS records, COALESCE(SUM(94 + length(kind) +
            CASE WHEN otherPlaybackId IS NULL THEN 0 ELSE 40 END +
            CASE WHEN deletedAt IS NULL THEN 0 ELSE 8 END), 0) AS bytes FROM grouping_corrections
    """)
    suspend fun backupSize(): GroupingBackupSize

    @Insert suspend fun insert(rows: List<GroupingCorrectionRow>)

    @Query("UPDATE grouping_corrections SET deletedAt = :at WHERE deletedAt IS NULL AND id IN (:ids)")
    suspend fun markDeleted(ids: List<String>, at: Long)

    @Query("DELETE FROM grouping_corrections") suspend fun clear()
}
