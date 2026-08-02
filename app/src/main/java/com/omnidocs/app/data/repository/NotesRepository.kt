package com.omnidocs.app.data.repository

import com.omnidocs.app.data.local.NoteDao
import com.omnidocs.app.data.local.entity.NoteEntity
import com.omnidocs.app.domain.model.Note
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class NotesRepository @Inject constructor(
    private val noteDao: NoteDao
) {
    fun getAllNotes(): Flow<List<Note>> {
        return noteDao.getAllNotes().map { entities ->
            entities.map { it.toDomain() }
        }
    }

    fun searchNotes(query: String): Flow<List<Note>> {
        return noteDao.searchNotes(query).map { entities ->
            entities.map { it.toDomain() }
        }
    }

    /**
     * FTS4 full-text search with prefix matching.
     * Converts plain user input to FTS MATCH syntax automatically.
     * Falls back to LIKE search on empty/malformed FTS query.
     */
    fun searchNotesFts(query: String): Flow<List<Note>> {
        val ftsQuery = query.trim()
            .replace(Regex("[\"'*()^\\[\\]{}]"), "")  // strip FTS special chars
            .split(Regex("\\s+"))
            .filter { it.isNotBlank() }
            .joinToString(" ") { "$it*" }  // prefix matching
        return if (ftsQuery.isBlank()) {
            searchNotes(query)
        } else {
            noteDao.searchNotesFts(ftsQuery).map { entities ->
                entities.map { it.toDomain() }
            }
        }
    }

    suspend fun getNoteById(id: String): Note? {
        return noteDao.getNoteById(id)?.toDomain()
    }

    fun getNoteByIdFlow(id: String): Flow<Note?> {
        return noteDao.getNoteByIdFlow(id).map { it?.toDomain() }
    }

    suspend fun createNote(title: String, content: String, plainText: String, language: String = "en"): Note {
        val note = NoteEntity(
            id = UUID.randomUUID().toString(),
            title = title,
            content = content,
            plainText = plainText,
            isPinned = false,
            language = language,
            createdAt = System.currentTimeMillis(),
            updatedAt = System.currentTimeMillis(),
            imageUrl = null
        )
        noteDao.insertNote(note)
        return note.toDomain()
    }

    suspend fun updateNote(note: Note) {
        noteDao.updateNote(note.toEntity())
    }

    /** Soft-delete a note (marks as deleted, keeps in database for sync) */
    suspend fun deleteNote(note: Note) {
        noteDao.deleteNoteById(note.id)
    }

    /** Soft-delete multiple notes by ID */
    suspend fun deleteNotesByIds(ids: List<String>) {
        ids.forEach { noteDao.deleteNoteById(it) }
    }

    /** Permanently remove a note from the database */
    suspend fun permanentlyDeleteNote(note: Note) {
        noteDao.permanentlyDeleteNoteById(note.id)
    }

    suspend fun togglePin(note: Note) {
        noteDao.updateNote(note.copy(isPinned = !note.isPinned).toEntity())
    }

    suspend fun getUnsyncedNotes(): List<Note> {
        return noteDao.getUnsyncedNotes().map { it.toDomain() }
    }

    suspend fun markAsSynced(id: String) {
        noteDao.markAsSynced(id)
    }

    suspend fun markAsSynced(ids: List<String>) {
        noteDao.markAsSynced(ids)
    }

    /** Return all notes including soft-deleted ones (for sync reconciliation) */
    suspend fun getAllNotesIncludeDeleted(): List<Note> {
        return noteDao.getAllNotesIncludeDeleted().map { it.toDomain() }
    }

    private fun NoteEntity.toDomain() = Note(
        id = id,
        title = title,
        content = content,
        plainText = plainText,
        isPinned = isPinned,
        language = language,
        createdAt = createdAt,
        updatedAt = updatedAt,
        imageUrl = imageUrl,
        attachments = attachments,
        isDeleted = isDeleted
    )

    private fun Note.toEntity() = NoteEntity(
        id = id,
        title = title,
        content = content,
        plainText = plainText,
        isPinned = isPinned,
        language = language,
        createdAt = createdAt,
        updatedAt = updatedAt,
        imageUrl = imageUrl,
        attachments = attachments,
        isDeleted = isDeleted
    )
}
