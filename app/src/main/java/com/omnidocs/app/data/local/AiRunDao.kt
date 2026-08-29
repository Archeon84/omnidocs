package com.omnidocs.app.data.local

import androidx.room.*
import com.omnidocs.app.data.local.entity.AiRunEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface AiRunDao {
    @Query("SELECT * FROM ai_runs ORDER BY createdAt DESC LIMIT :limit")
    fun getRecentRuns(limit: Int = 50): Flow<List<AiRunEntity>>

    @Query("SELECT * FROM ai_runs WHERE operation = :operation ORDER BY createdAt DESC")
    fun getRunsByOperation(operation: String): Flow<List<AiRunEntity>>

    @Query("SELECT * FROM ai_runs WHERE id = :id")
    suspend fun getRunById(id: String): AiRunEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertRun(run: AiRunEntity)

    @Update
    suspend fun updateRun(run: AiRunEntity)

    @Query("UPDATE ai_runs SET status = :status, completedAt = :completedAt WHERE id = :id")
    suspend fun completeRun(id: String, status: String, completedAt: Long)

    @Query("SELECT COUNT(*) FROM ai_runs WHERE operation = :operation AND createdAt > :since")
    suspend fun countRunsSince(operation: String, since: Long): Int
}