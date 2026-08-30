package com.omnidocs.app.data.local

import androidx.room.*
import com.omnidocs.app.data.local.entity.NoteEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface NoteDao {
    @Query("SELECT * FROM notes WHERE isDeleted = 0 ORDER BY isPinned DESC, updatedAt DESC")
    fun getAllNotes(): Flow<List<NoteEntity>>

    @Query("SELECT * FROM notes WHERE isDeleted = 0 ORDER BY isPinned DESC, updatedAt DESC")
    suspend fun getAllNotesSync(): List<NoteEntity>

    @Query("SELECT * FROM notes ORDER BY isPinned DESC, updatedAt DESC")
    suspend fun getAllNotesIncludeDeleted(): List<NoteEntity>

    @Query("SELECT * FROM notes WHERE id = :id AND isDeleted = 0")
    suspend fun getNoteById(id: String): NoteEntity?

    @Query("SELECT * FROM notes WHERE id = :id AND isDeleted = 0")
    fun getNoteByIdFlow(id: String): Flow<NoteEntity?>

    @Query("SELECT * FROM notes WHERE id = :id")
    suspend fun getNoteByIdIncludeDeleted(id: String): NoteEntity?

    @Query("SELECT * FROM notes WHERE (plainText LIKE '%' || :query || '%' OR title LIKE '%' || :query || '%') AND isDeleted = 0 ORDER BY updatedAt DESC")
    fun searchNotes(query: String): Flow<List<NoteEntity>>

    /** FTS4 full-text search — much faster than LIKE for large datasets */
    @Query("SELECT * FROM notes WHERE rowid IN (SELECT docid FROM notes_fts WHERE notes_fts MATCH :query) AND isDeleted = 0 ORDER BY updatedAt DESC")
    fun searchNotesFts(query: String): Flow<List<NoteEntity>>

    @Query("SELECT * FROM notes WHERE rowid IN (SELECT docid FROM notes_fts WHERE notes_fts MATCH :query) AND isDeleted = 0 ORDER BY updatedAt DESC")
    suspend fun searchNotesFtsSync(query: String): List<NoteEntity>

    @Query("SELECT * FROM notes WHERE id IN (:ids) AND isDeleted = 0")
    suspend fun getNotesByIdsSync(ids: List<String>): List<NoteEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertNote(note: NoteEntity)

    @Update
    suspend fun updateNote(note: NoteEntity)

    /** Soft-delete: mark as deleted instead of removing from the database.
     *  Records the deletion timestamp so backup restore can tell a deletion that
     *  happened AFTER a backup (must stay deleted) from one that predates it. */
    @Query("UPDATE notes SET isDeleted = 1, deletedAt = :deletedAt WHERE id = :id")
    suspend fun deleteNoteById(id: String, deletedAt: Long)

    /** Flip only the pinned flag — never touches content/updatedAt, so toggling
     * pin while the editor has unsaved changes cannot clobber them. */
    @Query("UPDATE notes SET isPinned = (CASE WHEN isPinned = 1 THEN 0 ELSE 1 END) WHERE id = :id")
    suspend fun togglePinById(id: String)

    /** Hard-delete: permanently remove from the database */
    @Query("DELETE FROM notes WHERE id = :id")
    suspend fun permanentlyDeleteNoteById(id: String)

    @Query("DELETE FROM notes WHERE id IN (:ids)")
    suspend fun permanentlyDeleteNotesByIds(ids: List<String>)

    @Query("SELECT * FROM notes WHERE isSynced = 0 AND isDeleted = 0")
    suspend fun getUnsyncedNotes(): List<NoteEntity>

    @Query("UPDATE notes SET isSynced = 1 WHERE id = :id")
    suspend fun markAsSynced(id: String)

    @Query("UPDATE notes SET isSynced = 1 WHERE id IN (:ids)")
    suspend fun markAsSynced(ids: List<String>)
}
