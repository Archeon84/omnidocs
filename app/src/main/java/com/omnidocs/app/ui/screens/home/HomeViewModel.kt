package com.omnidocs.app.ui.screens.home

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Log
import androidx.core.content.FileProvider
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.omnidocs.app.agent.ImportPipelineCoordinator
import com.omnidocs.app.agent.ImportPipelineResult
import com.omnidocs.app.data.local.ActionItemDao
import com.omnidocs.app.data.local.AiArtifactDao
import com.omnidocs.app.data.local.entity.ActionItemEntity
import com.omnidocs.app.data.local.entity.AiArtifactEntity
import com.omnidocs.app.data.local.entity.NoteEntity
import com.omnidocs.app.data.repository.NotesRepository
import com.omnidocs.app.docimport.DocumentConverterFactory
import com.omnidocs.app.domain.model.Note
import com.omnidocs.app.search.Tokenizer
import com.omnidocs.app.search.VectorSearch
import com.omnidocs.app.ui.components.IngestionReviewState
import com.omnidocs.app.ui.theme.AppTheme
import com.omnidocs.app.ui.theme.ThemeManager
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import java.io.File
import java.text.SimpleDateFormat
import java.util.*
import javax.inject.Inject

data class PendingImportReview(
    val noteId: String,
    val initialTitle: String,
    val initialTags: List<String>,
    val extractedTasks: List<String>,
    val reviewState: IngestionReviewState
)

private const val SEARCH_DEBOUNCE_MS = 300L
private const val MIN_SEARCH_CHARS = 2

fun isMeaningfulSearchQuery(query: String): Boolean {
    val trimmed = query.trim()
    if (trimmed.length >= MIN_SEARCH_CHARS) return true
    if (trimmed.length == 1) {
        return Tokenizer.isCjk(trimmed)
    }
    return false
}

@ExperimentalCoroutinesApi
@HiltViewModel
class HomeViewModel @Inject constructor(
    private val repository: NotesRepository,
    private val vectorSearch: VectorSearch,
    private val converterFactory: DocumentConverterFactory,
    private val importPipelineCoordinator: ImportPipelineCoordinator,
    private val aiArtifactDao: AiArtifactDao,
    private val actionItemDao: ActionItemDao,
    @ApplicationContext private val context: Context
) : ViewModel() {

    private val themeManager = ThemeManager(context)

    val currentTheme: StateFlow<AppTheme> = themeManager.currentTheme

    private val _searchQuery = MutableStateFlow("")
    val searchQuery: StateFlow<String> = _searchQuery.asStateFlow()

    private val _matchDetails = MutableStateFlow<Map<String, String>>(emptyMap())
    val matchDetails: StateFlow<Map<String, String>> = _matchDetails.asStateFlow()

    private val _isGridView = MutableStateFlow(true)
    val isGridView: StateFlow<Boolean> = _isGridView.asStateFlow()

    private val _isSelectionMode = MutableStateFlow(false)
    val isSelectionMode: StateFlow<Boolean> = _isSelectionMode.asStateFlow()

    private val _selectedNoteIds = MutableStateFlow<Set<String>>(emptySet())
    val selectedNoteIds: StateFlow<Set<String>> = _selectedNoteIds.asStateFlow()

    private val _importResult = MutableSharedFlow<Note>(extraBufferCapacity = 1)
    val importResult: SharedFlow<Note> = _importResult.asSharedFlow()

    private val _pendingReview = MutableStateFlow<PendingImportReview?>(null)
    val pendingReview: StateFlow<PendingImportReview?> = _pendingReview.asStateFlow()

    private val _isProcessingImport = MutableStateFlow(false)
    val isProcessingImport: StateFlow<Boolean> = _isProcessingImport.asStateFlow()

    private val _importingFileName = MutableStateFlow<String?>(null)
    val importingFileName: StateFlow<String?> = _importingFileName.asStateFlow()

    private var importJob: Job? = null

    private val _snackbarEvent = MutableSharedFlow<String>(extraBufferCapacity = 1)
    val snackbarEvent: SharedFlow<String> = _snackbarEvent.asSharedFlow()

    private val _loadError = MutableStateFlow<String?>(null)
    val loadError: StateFlow<String?> = _loadError.asStateFlow()

    val dueCardsCount: StateFlow<Int> = repository.countDueFlashcardsFlow()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0)

    val notes: StateFlow<List<Note>> = _searchQuery
        // Don't run a full hybrid search per keystroke: wait for a typing
        // pause (instant when cleared), skip duplicate emissions, and only
        // search once the query is meaningful (1 char matches everything).
        .debounce { query -> if (query.isBlank()) 0 else SEARCH_DEBOUNCE_MS }
        .distinctUntilChanged()
        .flatMapLatest { query ->
            if (!isMeaningfulSearchQuery(query)) {
                _matchDetails.value = emptyMap()
                repository.getAllNotes().map { list ->
                    list.sortedWith(
                        compareByDescending<Note> { it.isPinned }
                            .thenByDescending { it.updatedAt }
                    )
                }
            } else {
                flow {
                    val results = withContext(Dispatchers.IO) {
                        vectorSearch.hybridSearch(query)
                    }
                    _matchDetails.value = results.associate { it.note.id to it.explanation }
                    emit(results.map { it.note.toDomainNote() })
                }
            }
        }
        .catch { e ->
            Log.e("HomeViewModel", "Failed to load notes", e)
            _loadError.value = e.message ?: "Failed to load notes"
            emit(emptyList())
        }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = emptyList()
        )

    fun setTheme(theme: AppTheme) {
        themeManager.setTheme(theme)
    }

    fun updateSearchQuery(query: String) {
        _searchQuery.value = query
    }

    fun retryLoad() {
        _loadError.value = null
        val q = _searchQuery.value
        _searchQuery.value = ""
        _searchQuery.value = q
    }

    fun toggleViewMode() {
        _isGridView.value = !_isGridView.value
    }

    fun toggleSelectionMode() {
        _isSelectionMode.value = !_isSelectionMode.value
        if (!_isSelectionMode.value) {
            _selectedNoteIds.value = emptySet()
        }
    }

    fun toggleNoteSelection(noteId: String) {
        val current = _selectedNoteIds.value
        _selectedNoteIds.value = if (noteId in current) {
            current - noteId
        } else {
            current + noteId
        }
    }

    fun selectAllNotes() {
        _selectedNoteIds.value = notes.value.map { it.id }.toSet()
    }

    fun deselectAllNotes() {
        _selectedNoteIds.value = emptySet()
        _isSelectionMode.value = false
    }

    fun deleteSelectedNotes() {
        viewModelScope.launch {
            val selectedIds = _selectedNoteIds.value.toList()
            if (selectedIds.isNotEmpty()) {
                repository.deleteNotesByIds(selectedIds)
                _snackbarEvent.tryEmit("${selectedIds.size} note(s) deleted")
            }
            _selectedNoteIds.value = emptySet()
            _isSelectionMode.value = false
        }
    }

    fun deleteNote(note: Note) {
        viewModelScope.launch {
            repository.deleteNote(note)
            _snackbarEvent.tryEmit("Note deleted")
        }
    }

    /**
     * Executes the Import to Knowledge agent pipeline and prompts user review.
     */
    fun importFile(uri: Uri, mimeType: String) {
        importJob?.cancel()
        importJob = viewModelScope.launch {
            _isProcessingImport.value = true
            try {
                val fileName = getFileName(uri)
                _importingFileName.value = fileName
                val result: ImportPipelineResult = importPipelineCoordinator.runImportPipeline(
                    uri = uri.toString(),
                    mimeType = mimeType,
                    fileName = fileName
                )

                if (result.isSuccess && result.noteId != null) {
                    val note = repository.getNoteById(result.noteId)
                    if (note != null) {
                        val parsedTags = parseTagsJson(note.tags)
                        val reviewState = IngestionReviewState(
                            initialTitle = note.title,
                            initialTags = parsedTags,
                            extractedTasks = emptyList(),
                            pageCount = null,
                            language = result.language ?: "en",
                            characterCount = note.plainText.length
                        )

                        _pendingReview.value = PendingImportReview(
                            noteId = note.id,
                            initialTitle = note.title,
                            initialTags = parsedTags,
                            extractedTasks = emptyList(),
                            reviewState = reviewState
                        )
                    }
                } else {
                    _snackbarEvent.tryEmit(result.errorMessage ?: "Could not import this file format")
                }
            } catch (e: CancellationException) {
                _snackbarEvent.tryEmit("Import cancelled")
            } catch (e: Exception) {
                _snackbarEvent.tryEmit("Import failed: ${e.message}")
            } finally {
                _importingFileName.value = null
                _isProcessingImport.value = false
            }
        }
    }

    /**
     * Cancel an in-flight import: stops the coroutine and flags the pipeline
     * job so agents halt at the next step boundary. Partial work (note row,
     * blocks) may already exist — the review sheet's Discard path cleans up.
     */
    fun cancelImport() {
        importJob?.cancel()
        importJob = null
        viewModelScope.launch {
            try {
                importPipelineCoordinator.cancelActiveImport()
            } catch (e: Exception) {
                Log.e("HomeViewModel", "Failed to cancel import job", e)
            }
            // Remove a note row saved before the cancel landed — unless it's
            // already sitting in the review sheet (then Discard owns cleanup).
            try {
                val noteId = importPipelineCoordinator.takeCreatedNoteId()
                if (noteId != null && _pendingReview.value?.noteId != noteId) {
                    repository.discardUnsyncedNote(noteId)
                }
            } catch (e: Exception) {
                Log.e("HomeViewModel", "Failed to clean up cancelled import", e)
            }
        }
    }

    fun confirmImport(
        pending: PendingImportReview,
        confirmedTitle: String,
        confirmedTags: List<String>,
        selectedTasks: List<String>
    ) {
        viewModelScope.launch {
            val note = repository.getNoteById(pending.noteId)
            if (note != null) {
                val updatedNote = note.copy(
                    title = confirmedTitle,
                    tags = JSONArray(confirmedTags).toString(),
                    updatedAt = System.currentTimeMillis()
                )
                repository.updateNote(updatedNote)

                val now = System.currentTimeMillis()
                // Persist AI Artifact approval
                val artifacts = listOf(
                    AiArtifactEntity(
                        id = UUID.randomUUID().toString(),
                        noteId = note.id,
                        artifactType = AiArtifactEntity.TYPE_TITLE,
                        content = confirmedTitle,
                        approvalState = AiArtifactEntity.APPROVAL_ACCEPTED,
                        verificationStatus = AiArtifactEntity.STATUS_VERIFIED,
                        createdAt = now,
                        updatedAt = now
                    ),
                    AiArtifactEntity(
                        id = UUID.randomUUID().toString(),
                        noteId = note.id,
                        artifactType = AiArtifactEntity.TYPE_TAGS,
                        content = JSONArray(confirmedTags).toString(),
                        approvalState = AiArtifactEntity.APPROVAL_ACCEPTED,
                        verificationStatus = AiArtifactEntity.STATUS_VERIFIED,
                        createdAt = now,
                        updatedAt = now
                    )
                )
                aiArtifactDao.insertArtifacts(artifacts)

                // Persist accepted action items
                if (selectedTasks.isNotEmpty()) {
                    val actions = selectedTasks.map { taskText ->
                        ActionItemEntity(
                            id = UUID.randomUUID().toString(),
                            noteId = note.id,
                            claimId = null,
                            title = taskText,
                            description = "",
                            owner = null,
                            dueAt = null,
                            status = "OPEN",
                            priority = "MEDIUM",
                            createdAt = now,
                            updatedAt = now
                        )
                    }
                    actionItemDao.insertActionItems(actions)
                }

                _pendingReview.value = null
                _importResult.tryEmit(updatedNote)
                _snackbarEvent.tryEmit("Imported & reviewed \"${updatedNote.title}\"")
            }
        }
    }

    /**
     * Discard an import from the review sheet: hard-deletes the just-imported
     * note (plus blocks/embeddings/source docs) so "Discard" no longer leaves
     * the file saved as a note. Falls back to a soft delete when the row
     * already synced (shouldn't happen this early, but never lose data).
     */
    fun dismissImportReview() {
        val pending = _pendingReview.value ?: return
        _pendingReview.value = null
        viewModelScope.launch {
            val discarded = try {
                repository.discardUnsyncedNote(pending.noteId)
            } catch (e: Exception) {
                Log.e("HomeViewModel", "Failed to discard import ${pending.noteId}", e)
                false
            }
            if (!discarded) {
                try {
                    repository.getNoteById(pending.noteId)?.let { note ->
                        repository.deleteNote(note)
                    }
                } catch (e: Exception) {
                    Log.e("HomeViewModel", "Fallback soft-delete failed for ${pending.noteId}", e)
                }
            }
            _snackbarEvent.tryEmit("Import discarded")
        }
    }

    private fun parseTagsJson(tagsJson: String): List<String> {
        return try {
            val array = JSONArray(tagsJson)
            (0 until array.length()).map { array.getString(it) }
        } catch (e: Exception) {
            emptyList()
        }
    }

    private fun NoteEntity.toDomainNote(): Note = Note(
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

    private fun getFileName(uri: Uri): String {
        val cursor = context.contentResolver.query(uri, null, null, null, null)
        cursor?.use {
            if (it.moveToFirst()) {
                val nameIndex = it.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (nameIndex >= 0) {
                    val name = it.getString(nameIndex)
                    return name.substringBeforeLast(".")
                }
            }
        }
        val pathSegment = uri.lastPathSegment ?: "Imported"
        return pathSegment.substringBeforeLast(".")
    }

    fun shareSelectedNotes(format: String) {
        viewModelScope.launch {
            val selectedIds = _selectedNoteIds.value
            val selectedNotes = notes.value.filter { it.id in selectedIds }

            if (selectedNotes.isEmpty()) return@launch

            val dateFormat = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())
            val fileName = "OmniDocs_${dateFormat.format(Date())}"

            when (format) {
                "txt" -> shareAsTxt(selectedNotes, fileName)
                "html" -> shareAsHtml(selectedNotes, fileName)
                "md" -> shareAsMarkdown(selectedNotes, fileName)
            }
            _selectedNoteIds.value = emptySet()
            _isSelectionMode.value = false
        }
    }

    private fun shareAsTxt(notes: List<Note>, fileName: String) {
        val content = notes.joinToString("\n\n" + "=".repeat(40) + "\n\n") { note ->
            "${note.title}\n\n${note.plainText}"
        }
        shareText(content, "$fileName.txt", "text/plain")
    }

    private fun shareAsHtml(notes: List<Note>, fileName: String) {
        val body = notes.joinToString("\n<hr/>\n") { note ->
            "<h1>${note.title}</h1>\n${note.content}"
        }
        val html = """
            <!DOCTYPE html>
            <html>
            <head><meta charset="utf-8"><title>OmniDocs Export</title></head>
            <body>$body</body>
            </html>
        """.trimIndent()
        shareText(html, "$fileName.html", "text/html")
    }

    private fun shareAsMarkdown(notes: List<Note>, fileName: String) {
        val md = notes.joinToString("\n\n---\n\n") { note ->
            "# ${note.title}\n\n${note.plainText}"
        }
        shareText(md, "$fileName.md", "text/markdown")
    }

    private fun shareText(content: String, fileName: String, mimeType: String) {
        try {
            // Must live under a FileProvider root (see file_paths.xml):
            // cache root itself is NOT shared.
            val sharedDir = File(context.cacheDir, "shared_notes").also { it.mkdirs() }
            val cacheFile = File(sharedDir, fileName)
            cacheFile.writeText(content)
            val uri = FileProvider.getUriForFile(
                context,
                "${context.packageName}.fileprovider",
                cacheFile
            )
            val intent = Intent(Intent.ACTION_SEND).apply {
                type = mimeType
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(Intent.createChooser(intent, "Share Notes").apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            })
        } catch (e: Exception) {
            _snackbarEvent.tryEmit("Failed to share notes")
        }
    }
}
