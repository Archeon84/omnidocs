package com.omnidocs.app.data.local

import androidx.room.*
import com.omnidocs.app.data.local.entity.EmbeddingEntity

@Dao
interface EmbeddingDao {
    @Query("SELECT * FROM embeddings WHERE sourceType = :sourceType AND sourceId = :sourceId")
    suspend fun getEmbeddingsBySource(sourceType: String, sourceId: String): List<EmbeddingEntity>

    @Query("SELECT * FROM embeddings WHERE sourceType = :sourceType")
    suspend fun getEmbeddingsByType(sourceType: String): List<EmbeddingEntity>

    @Query("SELECT * FROM embeddings WHERE sourceType = :sourceType AND modelName = :modelName")
    suspend fun getEmbeddingsByTypeAndModel(sourceType: String, modelName: String): List<EmbeddingEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertEmbedding(embedding: EmbeddingEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertEmbeddings(embeddings: List<EmbeddingEntity>)

    @Query("DELETE FROM embeddings WHERE sourceType = :sourceType AND sourceId = :sourceId")
    suspend fun deleteEmbeddingsBySource(sourceType: String, sourceId: String)

    @Query("SELECT COUNT(*) FROM embeddings WHERE sourceType = :sourceType")
    suspend fun countEmbeddingsByType(sourceType: String): Int

    @Query("SELECT COUNT(*) FROM embeddings WHERE sourceType = :sourceType AND modelName = :modelName")
    suspend fun countEmbeddingsByTypeAndModel(sourceType: String, modelName: String): Int

    @Query("DELETE FROM embeddings WHERE modelName = :modelName")
    suspend fun deleteEmbeddingsByModel(modelName: String)

    @Query("DELETE FROM embeddings WHERE sourceId IN (:sourceIds)")
    suspend fun deleteEmbeddingsBySourceIds(sourceIds: List<String>)

    @Query("DELETE FROM embeddings WHERE id IN (:ids)")
    suspend fun deleteEmbeddingsByIds(ids: List<String>)
}