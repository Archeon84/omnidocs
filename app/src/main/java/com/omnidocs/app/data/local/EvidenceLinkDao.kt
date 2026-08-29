package com.omnidocs.app.data.local

import androidx.room.*
import com.omnidocs.app.data.local.entity.EvidenceLinkEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface EvidenceLinkDao {
    @Query("SELECT * FROM evidence_links WHERE claimId = :claimId ORDER BY relevanceScore DESC")
    fun getEvidenceByClaimId(claimId: String): Flow<List<EvidenceLinkEntity>>

    @Query("SELECT * FROM evidence_links WHERE sourceType = :sourceType AND sourceId = :sourceId")
    fun getEvidenceBySource(sourceType: String, sourceId: String): Flow<List<EvidenceLinkEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertEvidenceLink(link: EvidenceLinkEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertEvidenceLinks(links: List<EvidenceLinkEntity>)

    @Query("DELETE FROM evidence_links WHERE claimId = :claimId")
    suspend fun deleteEvidenceByClaimId(claimId: String)

    @Query("DELETE FROM evidence_links WHERE claimId IN (:claimIds)")
    suspend fun deleteEvidenceByClaimIds(claimIds: List<String>)

    @Query("DELETE FROM evidence_links WHERE sourceType = :sourceType AND sourceId = :sourceId")
    suspend fun deleteEvidenceBySource(sourceType: String, sourceId: String)
}