package com.omnidocs.app.data.local

import androidx.room.*
import com.omnidocs.app.data.local.entity.RecordingEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface RecordingDao {
    @Query("SELECT * FROM recordings WHERE deletedAt IS NULL ORDER BY createdAt DESC")
    fun getAllRecordings(): Flow<List<RecordingEntity>>

    @Query("SELECT * FROM recordings")
    suspend fun getAllRecordingsIncludeDeleted(): List<RecordingEntity>

    @Query("SELECT * FROM recordings WHERE noteId = :noteId AND deletedAt IS NULL")
    fun getRecordingsByNoteId(noteId: String): Flow<List<RecordingEntity>>

    @Query("SELECT * FROM recordings WHERE noteId IN (:noteIds) AND deletedAt IS NULL")
    suspend fun getRecordingsByNoteIds(noteIds: List<String>): List<RecordingEntity>

    @Query("SELECT * FROM recordings WHERE id = :id")
    suspend fun getRecordingById(id: String): RecordingEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertRecording(recording: RecordingEntity)

    @Update
    suspend fun updateRecording(recording: RecordingEntity)

    @Query("UPDATE recordings SET deletedAt = :deletedAt WHERE id = :id")
    suspend fun softDeleteRecording(id: String, deletedAt: Long = System.currentTimeMillis())

    /** Soft-delete every non-deleted recording of a note (used when the note is
     *  deleted, so its audio doesn't linger as orphans). Existing deletions are
     *  left untouched to preserve their original deletion timestamps. */
    @Query("UPDATE recordings SET deletedAt = :deletedAt WHERE noteId = :noteId AND deletedAt IS NULL")
    suspend fun softDeleteRecordingsByNoteId(noteId: String, deletedAt: Long)

    @Query("UPDATE recordings SET deletedAt = :deletedAt WHERE noteId IN (:noteIds) AND deletedAt IS NULL")
    suspend fun softDeleteRecordingsByNoteIds(noteIds: List<String>, deletedAt: Long)

    @Query("DELETE FROM recordings WHERE id = :id")
    suspend fun permanentlyDeleteRecording(id: String)

    @Query("SELECT * FROM recordings WHERE processingStatus != 'completed' AND deletedAt IS NULL")
    suspend fun getPendingRecordings(): List<RecordingEntity>
}