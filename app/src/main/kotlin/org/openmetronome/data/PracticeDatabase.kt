package org.openmetronome.data

import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "presets")
data class Preset(@PrimaryKey val id: String, val name: String, val config: String, val createdAt: Long, val updatedAt: Long)

@Entity(tableName = "practice_sessions")
data class PracticeSession(
    @PrimaryKey val id: String, val startedAt: Long, val checkpointAt: Long,
    val durationMs: Long, val finished: Boolean, val interrupted: Boolean,
    val zoneId: String, val config: String,
)

@Entity(tableName = "practice_days", primaryKeys = ["sessionId", "date"],
    foreignKeys = [ForeignKey(entity = PracticeSession::class, parentColumns = ["id"], childColumns = ["sessionId"], onDelete = ForeignKey.CASCADE)])
data class PracticeDay(val sessionId: String, val date: String, val durationMs: Long)
data class DayTotal(val date: String, val durationMs: Long)
data class PracticeTotal(val sessions: Long, val durationMs: Long)

@Entity(tableName = "custom_samples")
data class CustomSampleRecord(@PrimaryKey val id: String, val name: String, val createdAt: Long)

@Dao
abstract class PracticeDao {
    @Query("SELECT * FROM custom_samples ORDER BY createdAt, id") abstract suspend fun allSamples(): List<CustomSampleRecord>
    @Insert abstract suspend fun insertSample(sample: CustomSampleRecord)
    @Query("UPDATE custom_samples SET name = :name WHERE id = :id") abstract suspend fun renameSample(id: String, name: String)
    @Query("DELETE FROM custom_samples WHERE id = :id") abstract suspend fun deleteSample(id: String)
    @Query("SELECT * FROM presets") abstract suspend fun allPresets(): List<Preset>
    @Query("SELECT * FROM presets ORDER BY updatedAt DESC") abstract fun presets(): Flow<List<Preset>>
    @Upsert abstract suspend fun savePreset(preset: Preset)
    @Query("DELETE FROM presets WHERE id = :id") abstract suspend fun deletePreset(id: String)
    @Query("SELECT * FROM practice_sessions ORDER BY startedAt DESC LIMIT 100") abstract fun sessions(): Flow<List<PracticeSession>>
    @Query("SELECT * FROM practice_sessions ORDER BY startedAt ASC, id ASC") abstract suspend fun sessionsForExport(): List<PracticeSession>
    @Query("SELECT COUNT(*) AS sessions, COALESCE(SUM(durationMs), 0) AS durationMs FROM practice_sessions") abstract fun total(): Flow<PracticeTotal>
    @Query("SELECT date, SUM(durationMs) AS durationMs FROM practice_days GROUP BY date ORDER BY date DESC") abstract fun days(): Flow<List<DayTotal>>
    @Upsert abstract suspend fun saveSession(session: PracticeSession)
    @Insert(onConflict = OnConflictStrategy.REPLACE) abstract suspend fun saveDays(days: List<PracticeDay>)
    @Query("DELETE FROM practice_days WHERE sessionId = :id") abstract suspend fun clearDays(id: String)
    @Query("UPDATE practice_sessions SET finished = 1, interrupted = 1 WHERE finished = 0") abstract suspend fun recoverInterrupted()
    @Transaction open suspend fun checkpoint(session: PracticeSession, days: List<PracticeDay>) {
        saveSession(session)
        clearDays(session.id)
        saveDays(days)
    }
}

@Database(entities = [Preset::class, PracticeSession::class, PracticeDay::class, CustomSampleRecord::class], version = 2, exportSchema = true)
abstract class PracticeDatabase : RoomDatabase() {
    abstract fun practice(): PracticeDao
    companion object {
        val MIGRATION_1_2 = object : androidx.room.migration.Migration(1, 2) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                db.execSQL("CREATE TABLE IF NOT EXISTS `custom_samples` (`id` TEXT NOT NULL, `name` TEXT NOT NULL, `createdAt` INTEGER NOT NULL, PRIMARY KEY(`id`))")
            }
        }
    }
}
