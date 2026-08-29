package com.omnidocs.app.data.local

import androidx.room.*
import com.omnidocs.app.data.local.entity.AgentEventEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface AgentEventDao {
    @Query("SELECT * FROM agent_events WHERE jobId = :jobId ORDER BY createdAt ASC")
    fun getEventsForJob(jobId: String): Flow<List<AgentEventEntity>>

    @Query("SELECT * FROM agent_events ORDER BY createdAt DESC LIMIT :limit")
    fun getRecentEvents(limit: Int = 100): Flow<List<AgentEventEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertEvent(event: AgentEventEntity)

    @Query("SELECT COUNT(*) FROM agent_events WHERE jobId = :jobId")
    suspend fun countEventsForJob(jobId: String): Int
}
