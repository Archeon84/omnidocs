package com.omnidocs.app.data.local

import androidx.room.*
import com.omnidocs.app.data.local.entity.ContentBlockEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface ContentBlockDao {
    @Query("SELECT * FROM content_blocks WHERE noteId = :noteId ORDER BY blockIndex ASC")
    fun getBlocksForNote(noteId: String): Flow<List<ContentBlockEntity>>

    @Query("SELECT * FROM content_blocks WHERE noteId = :noteId ORDER BY blockIndex ASC")
    suspend fun getBlocksForNoteSync(noteId: String): List<ContentBlockEntity>

    @Query("SELECT * FROM content_blocks WHERE noteId IN (:noteIds) ORDER BY noteId ASC, blockIndex ASC")
    suspend fun getBlocksForNoteIds(noteIds: List<String>): List<ContentBlockEntity>

    @Query("SELECT * FROM content_blocks WHERE sourceDocumentId = :sourceDocId ORDER BY pageNumber ASC, blockIndex ASC")
    fun getBlocksForSourceDocument(sourceDocId: String): Flow<List<ContentBlockEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertBlocks(blocks: List<ContentBlockEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertBlock(block: ContentBlockEntity)

    @Query("DELETE FROM content_blocks WHERE noteId = :noteId")
    suspend fun deleteBlocksForNote(noteId: String)

    @Query("DELETE FROM content_blocks WHERE sourceDocumentId = :sourceDocId")
    suspend fun deleteBlocksForSourceDocument(sourceDocId: String)
}
