package com.omnidocs.app.data.local

import androidx.room.*
import com.omnidocs.app.data.local.entity.ClaimEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface ClaimDao {
    @Query("SELECT * FROM claims WHERE noteId = :noteId ORDER BY createdAt DESC")
    fun getClaimsByNoteId(noteId: String): Flow<List<ClaimEntity>>

    @Query("SELECT * FROM claims WHERE claimType = :type ORDER BY createdAt DESC")
    fun getClaimsByType(type: String): Flow<List<ClaimEntity>>

    @Query("SELECT * FROM claims WHERE status = 'ai_suggested' ORDER BY confidence ASC")
    fun getPendingClaims(): Flow<List<ClaimEntity>>

    @Query("SELECT * FROM claims WHERE id = :id")
    suspend fun getClaimById(id: String): ClaimEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertClaim(claim: ClaimEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertClaims(claims: List<ClaimEntity>)

    @Update
    suspend fun updateClaim(claim: ClaimEntity)

    @Query("UPDATE claims SET status = :status, updatedAt = :updatedAt WHERE id = :id")
    suspend fun updateClaimStatus(id: String, status: String, updatedAt: Long = System.currentTimeMillis())

    @Query("DELETE FROM claims WHERE noteId = :noteId")
    suspend fun deleteClaimsByNoteId(noteId: String)

    /**
     * Delete only the auto-extracted claims for a note, keeping any the user
     * approved/rejected/resolved. Used to refresh AI knowledge without wiping
     * manual curation on every re-save.
     */
    @Query("DELETE FROM claims WHERE noteId = :noteId AND status = 'ai_suggested'")
    suspend fun deleteAiSuggestedClaimsByNoteId(noteId: String)

    /** IDs of the note's auto-extracted claims. Called before the refresh delete so
     *  the caller can scope evidence-link cleanup to exactly the rows being replaced. */
    @Query("SELECT id FROM claims WHERE noteId = :noteId AND status = 'ai_suggested'")
    suspend fun getAiSuggestedClaimIdsByNoteId(noteId: String): List<String>
}