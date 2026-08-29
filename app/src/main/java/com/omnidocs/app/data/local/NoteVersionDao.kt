package com.omnidocs.app.data.local

import androidx.room.*
import com.omnidocs.app.data.local.entity.NoteVersionEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface NoteVersionDao {
    @Query("SELECT * FROM note_versions WHERE noteId = :noteId ORDER BY version DESC")
    fun getVersionsByNoteId(noteId: String): Flow<List<NoteVersionEntity>>

    @Query("SELECT * FROM note_versions WHERE noteId = :noteId ORDER BY version DESC LIMIT 1")
    suspend fun getLatestVersion(noteId: String): NoteVersionEntity?

    @Query("SELECT * FROM note_versions WHERE id = :id")
    suspend fun getVersionById(id: String): NoteVersionEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertVersion(version: NoteVersionEntity)

    @Query("DELETE FROM note_versions WHERE noteId = :noteId AND version < :keepAfter")
    suspend fun pruneOldVersions(noteId: String, keepAfter: Int)
}