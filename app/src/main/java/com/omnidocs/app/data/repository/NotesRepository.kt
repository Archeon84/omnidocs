package com.omnidocs.app.data.repository

import androidx.room.withTransaction
import com.omnidocs.app.ai.BackgroundInferenceDispatcher
import com.omnidocs.app.data.local.ContentBlockDao
import com.omnidocs.app.data.local.EmbeddingDao
import com.omnidocs.app.data.local.FlashcardDao
import com.omnidocs.app.data.local.NoteDao
import com.omnidocs.app.data.local.NotesDatabase
import com.omnidocs.app.data.local.RecordingDao
import com.omnidocs.app.data.local.SourceDocumentDao
import com.omnidocs.app.data.local.entity.FlashcardEntity
import com.omnidocs.app.data.local.entity.NoteEntity
import com.omnidocs.app.domain.model.Note
import com.omnidocs.app.search.EmbeddingService
import com.omnidocs.app.util.MarkdownCodec
import com.omnidocs.app.voice.RecordingStorage
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import android.util.Log
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class NotesRepository @Inject constructor(
    private val noteDao: NoteDao,
    private val recordingDao: RecordingDao,
    private val contentBlockDao: ContentBlockDao,
    private val sourceDocumentDao: SourceDocumentDao,
    private val recordingStorage: RecordingStorage,
    private val notesDatabase: NotesDatabase,
    private val embeddingService: EmbeddingService,
    private val embeddingDao: EmbeddingDao,
    private val inferenceDispatcher: BackgroundInferenceDispatcher,
    private val flashcardDao: FlashcardDao
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
        // Strip the full FTS4 operator set (- : + . ? | & ! /) plus bare
        // AND/OR/NOT/NEAR keywords: any of these in MATCH syntax throws
        // SQLiteException ("malformed MATCH", "no such column") on ordinary
        // input like "well-known" or "10:30".
        val ftsQuery = query.trim()
            .replace(Regex("[\"'*()^\\[\\]{}:?!|&/+-]"), " ")
            .replace(Regex("\\."), " ")
            .split(Regex("\\s+"))
            .filter { it.isNotBlank() }
            .filterNot { it.uppercase() in FTS_BARE_OPERATORS }
            .joinToString(" ") { "\"$it\"*" }  // quoted prefix matching
        return if (ftsQuery.isBlank()) {
            searchNotes(query)
        } else {
            noteDao.searchNotesFts(ftsQuery).map { entities ->
                entities.map { it.toDomain() }
            }.catch { e ->
                // Belt-and-braces: a surviving edge case falls back to LIKE
                // instead of crashing the search collector.
                Log.w("NotesRepository", "FTS query failed, falling back to LIKE", e)
                emitAll(searchNotes(query))
            }
        }
    }

    companion object {
        private val FTS_BARE_OPERATORS = setOf("AND", "OR", "NOT", "NEAR")

        /**
         * Canonical text representation of a note for chunking and passage embeddings.
         * Ensures 100% representation parity across index-on-save, background full-reindex,
         * and retrieval-time passage extraction.
         */
        fun canonicalChunkText(note: NoteEntity): String {
            return if (note.content.contains("<") && note.content.contains(">")) {
                MarkdownCodec.htmlToMarkdown(note.content).ifBlank { note.plainText }
            } else {
                note.plainText.ifBlank { note.content }
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
     * Always enqueued: the n-gram fallback is cheap CPU, and the real-model
     * path is incremental per chunkHash — skipping it left no-model installs
     * with permanently stale vectors. Rapid autosaves coalesce by note key in
     * the dispatcher.
     * Idempotent upsert in EmbeddingService prevents duplicate rows.
     */
    private fun reindexOnSave(note: NoteEntity) {
        Log.d("NotesRepository", "reindexOnSave: note=${note.id}")
        inferenceDispatcher.enqueue("reindex:${note.id}") {
            try {
                Log.d("NotesRepository", "reindexOnSave: starting passage embed for note ${note.id}")
                val chunkContent = canonicalChunkText(note)
                embeddingService.embedAndStoreNotePassages(note.id, note.title, chunkContent)
                Log.d("NotesRepository", "reindexOnSave: completed passage embed for note ${note.id}")
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
        // Batch-fetch recordings BEFORE the transaction so the DB lock is held
        // only for pure writes, never across N read queries.
        val recordings = recordingDao.getRecordingsByNoteIds(ids)
        val keysToDelete = recordings.map { it.storageKey }
        // Note + its recordings soft-delete atomically; the audio file cleanup is
        // deferred until after the transaction so disk I/O doesn't hold the DB lock.
        notesDatabase.withTransaction {
            for (id in ids) {
                noteDao.deleteNoteById(id, now)
            }
            recordingDao.softDeleteRecordingsByNoteIds(ids, now)
        }
        keysToDelete.forEach { recordingStorage.deleteRecording(it) }
        // Clean up orphaned embeddings so they don't suppress lazy reindex
        try {
            embeddingDao.deleteEmbeddingsBySourceIds(ids)
        } catch (e: Exception) {
            Log.e("NotesRepository", "Failed to clean up embeddings for deleted notes", e)
        }
        try {
            for (id in ids) {
                flashcardDao.deleteFlashcardsByNoteId(id)
            }
        } catch (e: Exception) {
            Log.e("NotesRepository", "Failed to clean up flashcards for deleted notes", e)
        }
    }

    /**
     * Hard-delete a note that was just imported but discarded in review.
     * Only applies when the row exists AND never synced (nothing remote can
     * reference it). Removes the note row plus its blocks, embeddings, and
     * source documents so no orphans remain. Returns false when the guard
     * refuses (caller should fall back to a soft delete).
     */
    suspend fun discardUnsyncedNote(noteId: String): Boolean {
        val local = noteDao.getNoteByIdIncludeDeleted(noteId) ?: return true
        if (local.isSynced) return false
        // Collect owning source docs BEFORE deleting blocks.
        val sourceDocIds = contentBlockDao.getBlocksForNoteSync(noteId)
            .mapNotNull { it.sourceDocumentId }
            .distinct()
        notesDatabase.withTransaction {
            contentBlockDao.deleteBlocksForNote(noteId)
            embeddingDao.deleteEmbeddingsBySource("note", noteId)
            flashcardDao.deleteFlashcardsByNoteId(noteId)
            noteDao.permanentlyDeleteNoteById(noteId)
            for (docId in sourceDocIds) {
                sourceDocumentDao.deleteSourceDocument(docId)
            }
        }
        return true
    }

    /** Permanently remove a note: writes a tombstone so the deletion propagates
     *  to the cloud, then GC hard-purges it after the retention window. */
    suspend fun permanentlyDeleteNote(note: Note) {
        deleteNotesWithRecordings(listOf(note.id))
    }

    suspend fun togglePin(note: Note) {
        // Flip only the pinned flag. Passing the full note here would write back
        // the caller's (possibly stale) content snapshot, discarding unsaved edits.
        noteDao.togglePinById(note.id)
    }

    suspend fun getAllNotesSync(): List<Note> {
        return noteDao.getAllNotesSync().map { it.toDomain() }
    }

    suspend fun markAsSynced(id: String) {
        noteDao.markAsSynced(id)
    }

    suspend fun markAsSynced(ids: List<String>) {
        // Room generates `IN ()` for empty lists → SQLiteException. No-op instead.
        if (ids.isEmpty()) return
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

    // ── Flashcards & Spaced Repetition Study ──────────────────────────

    suspend fun getFlashcardsForNote(noteId: String): List<FlashcardEntity> {
        return flashcardDao.getFlashcardsByNoteId(noteId)
    }

    fun getFlashcardsForNoteFlow(noteId: String): Flow<List<FlashcardEntity>> {
        return flashcardDao.getFlashcardsByNoteIdFlow(noteId)
    }

    suspend fun getDueFlashcards(nowMs: Long = System.currentTimeMillis(), limit: Int = 100): List<FlashcardEntity> {
        return flashcardDao.getDueFlashcards(nowMs, limit)
    }

    fun getDueFlashcardsFlow(nowMs: Long = System.currentTimeMillis()): Flow<List<FlashcardEntity>> {
        return flashcardDao.getDueFlashcardsFlow(nowMs)
    }

    suspend fun countDueFlashcards(nowMs: Long = System.currentTimeMillis()): Int {
        return flashcardDao.countDueFlashcards(nowMs)
    }

    fun countDueFlashcardsFlow(nowMs: Long = System.currentTimeMillis()): Flow<Int> {
        return flashcardDao.countDueFlashcardsFlow(nowMs)
    }

    suspend fun saveFlashcards(cards: List<FlashcardEntity>) {
        flashcardDao.insertFlashcards(cards)
    }

    suspend fun updateFlashcardReview(
        id: String,
        repetitionCount: Int,
        intervalDays: Int,
        easinessFactor: Float,
        nextReviewDateMs: Long,
        lastReviewedAtMs: Long,
        updatedAt: Long = System.currentTimeMillis()
    ) {
        flashcardDao.updateReviewState(
            id = id,
            repetitionCount = repetitionCount,
            intervalDays = intervalDays,
            easinessFactor = easinessFactor,
            nextReviewDateMs = nextReviewDateMs,
            lastReviewedAtMs = lastReviewedAtMs,
            updatedAt = updatedAt
        )
    }

    suspend fun deleteFlashcardsForNote(noteId: String) {
        flashcardDao.deleteFlashcardsByNoteId(noteId)
    }
}
