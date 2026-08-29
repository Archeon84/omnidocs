package com.omnidocs.app.data.local

import androidx.room.*
import com.omnidocs.app.data.local.entity.SourceDocumentEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface SourceDocumentDao {
    @Query("SELECT * FROM source_documents WHERE id = :id")
    suspend fun getSourceDocumentById(id: String): SourceDocumentEntity?

    @Query("SELECT * FROM source_documents WHERE checksum = :checksum LIMIT 1")
    suspend fun getSourceDocumentByChecksum(checksum: String): SourceDocumentEntity?

    @Query("SELECT * FROM source_documents ORDER BY importedAt DESC")
    fun getAllSourceDocuments(): Flow<List<SourceDocumentEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertSourceDocument(doc: SourceDocumentEntity)

    @Update
    suspend fun updateSourceDocument(doc: SourceDocumentEntity)

    @Query("DELETE FROM source_documents WHERE id = :id")
    suspend fun deleteSourceDocument(id: String)
}
