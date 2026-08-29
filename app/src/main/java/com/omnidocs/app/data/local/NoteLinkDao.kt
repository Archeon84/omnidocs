package com.omnidocs.app.data.local

import androidx.room.*
import com.omnidocs.app.data.local.entity.NoteLinkEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface NoteLinkDao {
    @Query("SELECT * FROM note_links WHERE sourceNoteId = :noteId OR targetNoteId = :noteId")
    fun getLinksForNote(noteId: String): Flow<List<NoteLinkEntity>>

    @Query("SELECT * FROM note_links WHERE sourceNoteId = :noteId AND linkType = :linkType")
    fun getLinksByType(noteId: String, linkType: String): Flow<List<NoteLinkEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertLink(link: NoteLinkEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertLinks(links: List<NoteLinkEntity>)

    @Query("DELETE FROM note_links WHERE (sourceNoteId = :noteId AND targetNoteId = :targetNoteId) OR (sourceNoteId = :targetNoteId AND targetNoteId = :noteId)")
    suspend fun deleteLinkBetween(noteId: String, targetNoteId: String)

    @Query("DELETE FROM note_links WHERE sourceNoteId = :noteId OR targetNoteId = :noteId")
    suspend fun deleteAllLinksForNote(noteId: String)
}