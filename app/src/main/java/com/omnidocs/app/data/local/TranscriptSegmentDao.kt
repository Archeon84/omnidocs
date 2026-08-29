package com.omnidocs.app.data.local

import androidx.room.*
import com.omnidocs.app.data.local.entity.TranscriptSegmentEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface TranscriptSegmentDao {
    @Query("SELECT * FROM transcript_segments WHERE recordingId = :recordingId ORDER BY startMs ASC")
    fun getSegmentsByRecording(recordingId: String): Flow<List<TranscriptSegmentEntity>>

    @Query("SELECT * FROM transcript_segments WHERE noteId = :noteId ORDER BY startMs ASC")
    fun getSegmentsByNoteId(noteId: String): Flow<List<TranscriptSegmentEntity>>

    @Query("SELECT * FROM transcript_segments WHERE id = :id")
    suspend fun getSegmentById(id: String): TranscriptSegmentEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertSegment(segment: TranscriptSegmentEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertSegments(segments: List<TranscriptSegmentEntity>)

    @Update
    suspend fun updateSegment(segment: TranscriptSegmentEntity)

    @Query("DELETE FROM transcript_segments WHERE recordingId = :recordingId")
    suspend fun deleteSegmentsByRecording(recordingId: String)

    @Query("UPDATE transcript_segments SET correctedText = :correctedText, updatedAt = :updatedAt WHERE id = :id")
    suspend fun correctSegment(id: String, correctedText: String, updatedAt: Long = System.currentTimeMillis())
}