package com.omnidocs.app.data.local

import androidx.room.*
import com.omnidocs.app.data.local.entity.ActionItemEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface ActionItemDao {
    @Query("SELECT * FROM action_items WHERE noteId = :noteId ORDER BY CASE WHEN dueAt IS NULL THEN 1 ELSE 0 END, dueAt ASC")
    fun getActionItemsByNoteId(noteId: String): Flow<List<ActionItemEntity>>

    @Query("SELECT * FROM action_items WHERE status != 'completed' AND status != 'cancelled' ORDER BY CASE WHEN dueAt IS NULL THEN 1 ELSE 0 END, dueAt ASC")
    fun getActiveActionItems(): Flow<List<ActionItemEntity>>

    @Query("SELECT * FROM action_items ORDER BY CASE WHEN dueAt IS NULL THEN 1 ELSE 0 END, dueAt ASC")
    fun getAllActionItems(): Flow<List<ActionItemEntity>>

    @Query("SELECT COUNT(*) FROM action_items WHERE status != 'completed' AND status != 'cancelled'")
    fun getActiveCount(): Flow<Int>

    @Query("SELECT COUNT(*) FROM action_items WHERE status = 'completed'")
    fun getCompletedCount(): Flow<Int>

    @Query("SELECT COUNT(*) FROM action_items WHERE status != 'completed' AND status != 'cancelled' AND dueAt < :now")
    fun getOverdueCount(now: Long = System.currentTimeMillis()): Flow<Int>

    @Query("SELECT * FROM action_items WHERE id = :id")
    suspend fun getActionItemById(id: String): ActionItemEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertActionItem(actionItem: ActionItemEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertActionItems(actionItems: List<ActionItemEntity>)

    @Update
    suspend fun updateActionItem(actionItem: ActionItemEntity)

    @Query("UPDATE action_items SET status = :status, completedAt = :completedAt, updatedAt = :updatedAt WHERE id = :id")
    suspend fun completeActionItem(id: String, status: String, completedAt: Long, updatedAt: Long)

    @Query("DELETE FROM action_items WHERE noteId = :noteId")
    suspend fun deleteActionItemsByNoteId(noteId: String)

    /**
     * Delete only the auto-extracted (still pending) action items for a note,
     * keeping in_progress/completed/cancelled items the user has acted on. Used
     * to refresh AI knowledge without resetting task progress on re-save.
     */
    @Query("DELETE FROM action_items WHERE noteId = :noteId AND status = 'pending'")
    suspend fun deletePendingActionItemsByNoteId(noteId: String)
}