package com.omnidocs.app.data.local

import androidx.room.*
import com.omnidocs.app.data.local.entity.AiArtifactEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface AiArtifactDao {
    @Query("SELECT * FROM ai_artifacts WHERE noteId = :noteId ORDER BY createdAt ASC")
    fun getArtifactsForNote(noteId: String): Flow<List<AiArtifactEntity>>

    @Query("SELECT * FROM ai_artifacts WHERE approvalState = 'PENDING' ORDER BY createdAt DESC")
    fun getPendingArtifacts(): Flow<List<AiArtifactEntity>>

    @Query("SELECT * FROM ai_artifacts WHERE id = :id")
    suspend fun getArtifactById(id: String): AiArtifactEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertArtifact(artifact: AiArtifactEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertArtifacts(artifacts: List<AiArtifactEntity>)

    @Query("UPDATE ai_artifacts SET approvalState = :approvalState, updatedAt = :updatedAt WHERE id = :id")
    suspend fun updateApprovalState(id: String, approvalState: String, updatedAt: Long = System.currentTimeMillis())

    @Query("DELETE FROM ai_artifacts WHERE id = :id")
    suspend fun deleteArtifact(id: String)
}
