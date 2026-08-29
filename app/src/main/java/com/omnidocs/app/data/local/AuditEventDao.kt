package com.omnidocs.app.data.local

import androidx.room.*
import com.omnidocs.app.data.local.entity.AuditEventEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface AuditEventDao {
    @Query("SELECT * FROM audit_events ORDER BY createdAt DESC LIMIT :limit")
    fun getRecentEvents(limit: Int = 100): Flow<List<AuditEventEntity>>

    @Query("SELECT * FROM audit_events WHERE entityType = :entityType AND entityId = :entityId ORDER BY createdAt DESC")
    fun getEventsForEntity(entityType: String, entityId: String): Flow<List<AuditEventEntity>>

    @Query("SELECT * FROM audit_events WHERE action = :action ORDER BY createdAt DESC")
    fun getEventsByAction(action: String): Flow<List<AuditEventEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertEvent(event: AuditEventEntity)

    @Query("DELETE FROM audit_events WHERE createdAt < :before")
    suspend fun pruneOldEvents(before: Long)
}