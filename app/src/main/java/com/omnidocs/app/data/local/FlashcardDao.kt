package com.omnidocs.app.data.local

import androidx.room.*
import com.omnidocs.app.data.local.entity.FlashcardEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface FlashcardDao {

    @Query("SELECT * FROM flashcards WHERE noteId = :noteId ORDER BY createdAt ASC")
    suspend fun getFlashcardsByNoteId(noteId: String): List<FlashcardEntity>

    @Query("SELECT * FROM flashcards WHERE noteId = :noteId ORDER BY createdAt ASC")
    fun getFlashcardsByNoteIdFlow(noteId: String): Flow<List<FlashcardEntity>>

    @Query("SELECT * FROM flashcards WHERE nextReviewDateMs <= :nowMs ORDER BY nextReviewDateMs ASC LIMIT :limit")
    suspend fun getDueFlashcards(nowMs: Long, limit: Int = 100): List<FlashcardEntity>

    @Query("SELECT * FROM flashcards WHERE nextReviewDateMs <= :nowMs ORDER BY nextReviewDateMs ASC")
    fun getDueFlashcardsFlow(nowMs: Long): Flow<List<FlashcardEntity>>

    @Query("SELECT * FROM flashcards WHERE noteId = :noteId AND nextReviewDateMs <= :nowMs ORDER BY nextReviewDateMs ASC")
    suspend fun getDueFlashcardsByNoteId(noteId: String, nowMs: Long): List<FlashcardEntity>

    @Query("SELECT COUNT(*) FROM flashcards WHERE nextReviewDateMs <= :nowMs")
    suspend fun countDueFlashcards(nowMs: Long): Int

    @Query("SELECT COUNT(*) FROM flashcards WHERE nextReviewDateMs <= :nowMs")
    fun countDueFlashcardsFlow(nowMs: Long): Flow<Int>

    @Query("SELECT COUNT(*) FROM flashcards WHERE noteId = :noteId")
    suspend fun countFlashcardsByNoteId(noteId: String): Int

    @Query("SELECT * FROM flashcards WHERE id = :id")
    suspend fun getFlashcardById(id: String): FlashcardEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertFlashcards(flashcards: List<FlashcardEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertFlashcard(flashcard: FlashcardEntity)

    @Update
    suspend fun updateFlashcard(flashcard: FlashcardEntity)

    @Query("""
        UPDATE flashcards
        SET repetitionCount = :repetitionCount,
            intervalDays = :intervalDays,
            easinessFactor = :easinessFactor,
            nextReviewDateMs = :nextReviewDateMs,
            lastReviewedAtMs = :lastReviewedAtMs,
            updatedAt = :updatedAt
        WHERE id = :id
    """)
    suspend fun updateReviewState(
        id: String,
        repetitionCount: Int,
        intervalDays: Int,
        easinessFactor: Float,
        nextReviewDateMs: Long,
        lastReviewedAtMs: Long,
        updatedAt: Long
    )

    @Query("DELETE FROM flashcards WHERE id = :id")
    suspend fun deleteFlashcardById(id: String)

    @Query("DELETE FROM flashcards WHERE noteId = :noteId")
    suspend fun deleteFlashcardsByNoteId(noteId: String)
}
