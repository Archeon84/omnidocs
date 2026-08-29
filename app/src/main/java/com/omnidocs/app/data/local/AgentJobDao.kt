package com.omnidocs.app.data.local

import androidx.room.*
import com.omnidocs.app.data.local.entity.AgentJobEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface AgentJobDao {
    @Query("SELECT * FROM agent_jobs WHERE id = :id")
    suspend fun getJobById(id: String): AgentJobEntity?

    @Query("SELECT * FROM agent_jobs WHERE status = :status ORDER BY createdAt DESC")
    fun getJobsByStatus(status: String): Flow<List<AgentJobEntity>>

    @Query("SELECT * FROM agent_jobs ORDER BY createdAt DESC LIMIT :limit")
    fun getRecentJobs(limit: Int = 50): Flow<List<AgentJobEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertJob(job: AgentJobEntity)

    @Update
    suspend fun updateJob(job: AgentJobEntity)

    @Query("UPDATE agent_jobs SET status = :status, completedAt = :completedAt WHERE id = :id")
    suspend fun updateJobStatus(id: String, status: String, completedAt: Long? = null)

    @Query("UPDATE agent_jobs SET status = :status, startedAt = :startedAt WHERE id = :id")
    suspend fun startJob(id: String, status: String, startedAt: Long)

    @Query("UPDATE agent_jobs SET progress = :progress WHERE id = :id")
    suspend fun updateProgress(id: String, progress: Int)

    @Query("UPDATE agent_jobs SET retryCount = retryCount + 1 WHERE id = :id")
    suspend fun incrementRetryCount(id: String)

    @Query("SELECT COUNT(*) FROM agent_jobs WHERE status IN ('QUEUED', 'RUNNING', 'WAITING_FOR_MODEL')")
    suspend fun countActiveJobs(): Int
}
