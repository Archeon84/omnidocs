package com.omnidocs.app.data.repository

import androidx.room.withTransaction
import com.omnidocs.app.ai.ModelDownloadManager
import com.omnidocs.app.ai.isAnyEmbeddingModelDownloaded
import com.omnidocs.app.data.local.EmbeddingDao
import com.omnidocs.app.data.local.NoteDao
import com.omnidocs.app.data.local.NotesDatabase
import com.omnidocs.app.data.local.RecordingDao
import com.omnidocs.app.data.local.entity.NoteEntity
import com.omnidocs.app.domain.model.Note
import com.omnidocs.app.search.EmbeddingService
import com.omnidocs.app.voice.RecordingStorage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import android.util.Log
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class NotesRepository @Inject constructor(
    private val noteDao: NoteDao,
    private val recordingDao: RecordingDao,
    private val recordingStorage: RecordingStorage,
    private val notesDatabase: NotesDatabase,
    private val embeddingService: EmbeddingService,
    private val embeddingDao: EmbeddingDao,
    private val modelDownloadManager: ModelDownloadManager
) {
    // Fire-and-forget scope for post-save re-indexing. SupervisorJob so a single
    // failed embed doesn't kill the scope for later saves.
    private val reindexScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
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
        reindexOnSave(note)
        return note.toDomain()
    }

    suspend fun updateNote(note: Note) {
        noteDao.updateNote(note.toEntity())
        reindexOnSave(note.toEntity())
    }

    /**
     * Refresh a note's semantic embedding after a save so search stays current.
     * Fire-and-forget and only when a real embedding model is downloaded (the
     * n-gram path is handled by the lazy first-search reindex in VectorSearch).
     * Idempotent upsert in EmbeddingService prevents duplicate rows.
     */
    private fun reindexOnSave(note: NoteEntity) {
        val modelDownloaded = isAnyEmbeddingModelDownloaded(modelDownloadManager)
        Log.d("NotesRepository", "reindexOnSave: note=${note.id}, modelDownloaded=$modelDownloaded")
        if (!modelDownloaded) return
        reindexScope.launch {
            try {
                Log.d("NotesRepository", "reindexOnSave: starting embed for note ${note.id}")
                embeddingService.embedAndStore("note", note.id, "${note.title} ${note.plainText}")
                Log.d("NotesRepository", "reindexOnSave: completed embed for note ${note.id}")
            } catch (e: Exception) {
                Log.e("NotesRepository", "reindexOnSave failed for note ${note.id}", e)
            }
        }
    }

    /** Soft-delete a note (marks as deleted, keeps in database for sync). Its
     *  recordings are soft-deleted and their audio files removed too, so the
     *  Recordings screen never orphans a deleted note's audio. */
    suspend fun deleteNote(note: Note) {
        deleteNotesWithRecordings(listOf(note.id))
    }

    /** Soft-delete multiple notes by ID (recordings handled as in deleteNote) */
    suspend fun deleteNotesByIds(ids: List<String>) {
        deleteNotesWithRecordings(ids)
    }

    private suspend fun deleteNotesWithRecordings(ids: List<String>) {
        if (ids.isEmpty()) return
        val now = System.currentTimeMillis()
        val keysToDelete = mutableListOf<String>()
        // Note + its recordings soft-delete atomically; the audio file cleanup is
        // deferred until after the transaction so disk I/O doesn't hold the DB lock.
        notesDatabase.withTransaction {
            for (id in ids) {
                noteDao.deleteNoteById(id, now)
                val recordings = recordingDao.getRecordingsByNoteId(id).first()
                if (recordings.isNotEmpty()) {
                    recordingDao.softDeleteRecordingsByNoteId(id, now)
                    keysToDelete += recordings.map { it.storageKey }
                }
            }
        }
        keysToDelete.forEach { recordingStorage.deleteRecording(it) }
        // Clean up orphaned embeddings so they don't suppress lazy reindex
        try {
            for (id in ids) {
                embeddingDao.deleteEmbeddingsBySource("note", id)
            }
        } catch (e: Exception) {
            Log.e("NotesRepository", "Failed to clean up embeddings for deleted notes", e)
        }
    }

    /** Permanently remove a note from the database */
    suspend fun permanentlyDeleteNote(note: Note) {
        noteDao.permanentlyDeleteNoteById(note.id)
    }

    suspend fun togglePin(note: Note) {
        // Flip only the pinned flag. Passing the full note here would write back
        // the caller's (possibly stale) content snapshot, discarding unsaved edits.
        noteDao.togglePinById(note.id)
    }

    suspend fun getAllNotesSync(): List<Note> {
        return noteDao.getAllNotesSync().map { it.toDomain() }
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
        isDeleted = isDeleted,
        tags = tags,
        relatedNotes = relatedNotes
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
        isDeleted = isDeleted,
        tags = tags,
        relatedNotes = relatedNotes
    )
}
