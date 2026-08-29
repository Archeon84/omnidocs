package com.omnidocs.app.data.local

import androidx.room.*
import com.omnidocs.app.data.local.entity.EntityMentionEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface EntityMentionDao {
    @Query("SELECT * FROM entity_mentions WHERE entityId = :entityId")
    fun getMentionsByEntityId(entityId: String): Flow<List<EntityMentionEntity>>

    @Query("SELECT * FROM entity_mentions WHERE noteId = :noteId")
    fun getMentionsByNoteId(noteId: String): Flow<List<EntityMentionEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertMention(mention: EntityMentionEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertMentions(mentions: List<EntityMentionEntity>)

    @Query("DELETE FROM entity_mentions WHERE entityId = :entityId")
    suspend fun deleteMentionsByEntityId(entityId: String)

    @Query("DELETE FROM entity_mentions WHERE noteId = :noteId")
    suspend fun deleteMentionsByNoteId(noteId: String)
}