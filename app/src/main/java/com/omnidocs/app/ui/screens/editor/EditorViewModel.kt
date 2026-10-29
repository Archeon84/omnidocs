package com.omnidocs.app.ui.screens.editor

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.omnidocs.app.domain.model.Note
import com.omnidocs.app.data.local.ContentBlockDao
import com.omnidocs.app.data.local.RecordingDao
import com.omnidocs.app.data.local.entity.ContentBlockEntity
import com.omnidocs.app.data.local.entity.RecordingEntity
import com.omnidocs.app.data.repository.NotesRepository
import com.omnidocs.app.ai.AiService
import com.omnidocs.app.ai.AutoTagger
import com.omnidocs.app.ai.EvidenceExtractor
import com.omnidocs.app.ai.ExtractedConcept
import com.omnidocs.app.ai.NoteIntelligenceService
import com.omnidocs.app.ai.ThermalBudgetManager
import com.omnidocs.app.ai.ThermalStatus
import com.omnidocs.app.ui.screens.editor.IntelligenceMessage
import com.omnidocs.app.search.Tokenizer
import com.omnidocs.app.util.MarkdownCodec
import com.omnidocs.app.util.sanitizeForHtml
import com.omnidocs.app.voice.AudioPlaybackController
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.launch
import javax.inject.Inject

/** Where a content change originated. WEBVIEW = the user typed/edited in the
 * rich editor; PROGRAMMATIC = the app set content (load note, OCR, AI result,
 * template, image/audio attach). RichTextEditor only pushes PROGRAMMATIC
 * changes back into the WebView, so WebView-sourced edits never get their
 * caret/focus reset by a redundant innerHTML write. */
enum class ContentSource { WEBVIEW, PROGRAMMATIC }

/** Which editor the user is currently viewing. RICH is the default WebView
 *  contenteditable editor; MARKDOWN is a plain-text source view; PREVIEW is a
 *  read-only rendered view. */
enum class EditorMode { RICH, MARKDOWN, PREVIEW }

@HiltViewModel
class EditorViewModel @Inject constructor(
    private val repository: NotesRepository,
    private val aiService: AiService,
    private val autoTagger: AutoTagger,
    private val noteIntelligenceService: NoteIntelligenceService,
    private val evidenceExtractor: EvidenceExtractor,
    private val recordingDao: RecordingDao,
    private val audioPlaybackController: AudioPlaybackController,
    private val contentBlockDao: ContentBlockDao,
    private val claimDao: com.omnidocs.app.data.local.ClaimDao,
    private val noteVersionDao: com.omnidocs.app.data.local.NoteVersionDao,
    private val transcriptSegmentDao: com.omnidocs.app.data.local.TranscriptSegmentDao? = null,
    thermalBudgetManager: ThermalBudgetManager? = null
) : ViewModel() {

    private val _currentNote = MutableStateFlow<Note?>(null)
    val currentNote: StateFlow<Note?> = _currentNote.asStateFlow()

    private val _title = MutableStateFlow("")
    val title: StateFlow<String> = _title.asStateFlow()

    private val _content = MutableStateFlow("")
    val content: StateFlow<String> = _content.asStateFlow()

    // Tracks whether the latest content update came from the WebView editor
    // (typing/formatting) or from the app (load/OCR/AI/template). RichTextEditor
    // uses this to avoid pushing WebView-originated content back into the WebView.
    private val _contentSource = MutableStateFlow(ContentSource.PROGRAMMATIC)
    val contentSource: StateFlow<ContentSource> = _contentSource.asStateFlow()

    private val _editorMode = MutableStateFlow(EditorMode.RICH)
    val editorMode: StateFlow<EditorMode> = _editorMode.asStateFlow()

    private val _markdownText = MutableStateFlow("")
    val markdownText: StateFlow<String> = _markdownText.asStateFlow()

    // Snapshot of the HTML as it was when markdown mode was entered. If the user
    // edits nothing and returns to Rich, this exact HTML is restored (fidelity).
    private var richHtmlSnapshot: String = ""
    // True once the markdown text has diverged from htmlToMarkdown(richHtmlSnapshot).
    private var markdownDirty = false

    val wordCount: StateFlow<Int> = _content.map { html ->
        val text = stripHtml(html)
        calculateWordCount(text)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0)

    val readingTimeMinutes: StateFlow<Int> = _content.map { html ->
        val text = stripHtml(html)
        calculateReadingTime(text)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0)

    private val _currentLanguage = MutableStateFlow("en")
    val currentLanguage: StateFlow<String> = _currentLanguage.asStateFlow()

    val aiStatus: String
        get() = aiService.getAiStatus()

    private val _aiModelName = MutableStateFlow("")
    val aiModelName: StateFlow<String> = _aiModelName.asStateFlow()

    init {
        // Load model name once
        viewModelScope.launch {
            _aiModelName.value = aiService.getAiStatus()
        }
    }

    private val _isSaving = MutableStateFlow(false)
    val isSaving: StateFlow<Boolean> = _isSaving.asStateFlow()

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    val claims: StateFlow<List<com.omnidocs.app.data.local.entity.ClaimEntity>> = _currentNote
        .flatMapLatest { note ->
            if (note != null) claimDao.getClaimsByNoteId(note.id)
            else flowOf(emptyList())
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    val versions: StateFlow<List<com.omnidocs.app.data.local.entity.NoteVersionEntity>> = _currentNote
        .flatMapLatest { note ->
            if (note != null) noteVersionDao.getVersionsByNoteId(note.id)
            else flowOf(emptyList())
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    fun approveClaim(claim: com.omnidocs.app.data.local.entity.ClaimEntity) {
        viewModelScope.launch {
            claimDao.updateClaimStatus(claim.id, "approved")
        }
    }

    fun rejectClaim(claim: com.omnidocs.app.data.local.entity.ClaimEntity) {
        viewModelScope.launch {
            claimDao.updateClaimStatus(claim.id, "rejected")
        }
    }

    fun restoreVersion(version: com.omnidocs.app.data.local.entity.NoteVersionEntity) {
        pushUndo()
        _contentSource.value = ContentSource.PROGRAMMATIC
        _content.value = version.content
        isDirty = true
        scheduleAutoSave()
    }

    private val _snackbarEvent = MutableSharedFlow<String>(extraBufferCapacity = 1)
    val snackbarEvent: SharedFlow<String> = _snackbarEvent.asSharedFlow()

    private val _aiPreview = MutableStateFlow<AiPreviewState?>(null)
    val aiPreview: StateFlow<AiPreviewState?> = _aiPreview.asStateFlow()

    private var aiPreviewJob: Job? = null
    // Per-op intelligence jobs: a shared job let explain/extract abort an
    // in-flight ask, and a cancelled job's finally could wipe a newer stream.
    private var askJob: Job? = null
    private var explainJob: Job? = null
    private var conceptsJob: Job? = null
    private var askGeneration = 0
    private var explainGeneration = 0
    private var conceptsGeneration = 0

    /** Clear shared intelligence UI state only when no intelligence op is active. */
    private fun clearIntelligenceUiIfIdle() {
        if (askJob?.isActive == true || explainJob?.isActive == true || conceptsJob?.isActive == true) return
        _isIntelligenceLoading.value = false
        _intelligenceStreaming.value = ""
    }

    // ── Note Intelligence ──────────────────────────────────────────────
    private val _intelligenceMessages = MutableStateFlow<List<IntelligenceMessage>>(emptyList())
    val intelligenceMessages: StateFlow<List<IntelligenceMessage>> = _intelligenceMessages.asStateFlow()

    private val _isIntelligenceLoading = MutableStateFlow(false)
    val isIntelligenceLoading: StateFlow<Boolean> = _isIntelligenceLoading.asStateFlow()

    /** True when the device is thermally throttled: answers will be slow. */
    val isDeviceWarm: StateFlow<Boolean> =
        thermalBudgetManager?.currentThermalStatus
            ?.map { it.ordinal >= ThermalStatus.MODERATE.ordinal }
            ?.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)
            ?: MutableStateFlow(false)

    private val _intelligenceStreaming = MutableStateFlow("")
    val intelligenceStreaming: StateFlow<String> = _intelligenceStreaming.asStateFlow()

    private val _intelligenceConcepts = MutableStateFlow<List<ExtractedConcept>>(emptyList())
    val intelligenceConcepts: StateFlow<List<ExtractedConcept>> = _intelligenceConcepts.asStateFlow()

    private val _showConceptDialog = MutableStateFlow(false)
    val showConceptDialog: StateFlow<Boolean> = _showConceptDialog.asStateFlow()

    // ── OCR Provenance & Content Blocks ───────────────────────────────────
    private val _contentBlocks = MutableStateFlow<List<ContentBlockEntity>>(emptyList())
    val contentBlocks: StateFlow<List<ContentBlockEntity>> = _contentBlocks.asStateFlow()

    private val _ocrImageUrl = MutableStateFlow<String?>(null)
    val ocrImageUrl: StateFlow<String?> = _ocrImageUrl.asStateFlow()
    private var pendingOcrBlocksJson: String? = null

    val hasOcrBlocks: StateFlow<Boolean> = _contentBlocks.map { blocks ->
        blocks.any { it.boundingBoxJson != null }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)

    private val _showOcrInspector = MutableStateFlow(false)
    val showOcrInspector: StateFlow<Boolean> = _showOcrInspector.asStateFlow()

    private val _selectedBlockIndex = MutableStateFlow<Int?>(null)
    val selectedBlockIndex: StateFlow<Int?> = _selectedBlockIndex.asStateFlow()

    fun applyOcrProvenance(imageUrl: String?, blocksJson: String?) {
        if (!imageUrl.isNullOrBlank()) {
            _ocrImageUrl.value = imageUrl
            _currentNote.value = _currentNote.value?.copy(imageUrl = imageUrl)
        }
        if (!blocksJson.isNullOrBlank() && blocksJson != "[]") {
            pendingOcrBlocksJson = blocksJson
            val parsed = parseOcrBlocksJson(blocksJson, _currentNote.value?.id)
            if (parsed.isNotEmpty()) {
                _contentBlocks.value = parsed
                val existingNoteId = _currentNote.value?.id
                if (existingNoteId != null) {
                    viewModelScope.launch {
                        try {
                            contentBlockDao.insertBlocks(parsed)
                            pendingOcrBlocksJson = null
                        } catch (_: Exception) {}
                    }
                }
            }
        }
    }

    private fun parseOcrBlocksJson(blocksJson: String, noteId: String?): List<ContentBlockEntity> {
        return try {
            val arr = org.json.JSONArray(blocksJson)
            val now = System.currentTimeMillis()
            (0 until arr.length()).mapNotNull { idx ->
                val obj = arr.optJSONObject(idx) ?: return@mapNotNull null
                val content = obj.optString("content", "")
                if (content.isBlank()) return@mapNotNull null
                ContentBlockEntity(
                    id = java.util.UUID.randomUUID().toString(),
                    noteId = noteId,
                    blockIndex = obj.optInt("blockIndex", idx),
                    blockType = obj.optString("blockType", "ocr_block"),
                    content = content,
                    boundingBoxJson = obj.optString("boundingBoxJson", "").takeIf { it.isNotBlank() },
                    confidence = if (obj.has("confidence") && !obj.isNull("confidence")) {
                        obj.optDouble("confidence", 0.0).toFloat()
                    } else null,
                    createdAt = now
                )
            }
        } catch (_: Exception) {
            emptyList()
        }
    }

    fun openOcrInspector() {
        _showOcrInspector.value = true
    }

    fun closeOcrInspector() {
        _showOcrInspector.value = false
        _selectedBlockIndex.value = null
    }

    fun selectBlock(index: Int?) {
        _selectedBlockIndex.value = index
    }

    private var autoSaveJob: Job? = null
    private var noteLoadJob: Job? = null
    private var loadedNoteId: String? = null
    // True once the user edits title/content and until the next successful save.
    // loadNote's Room flow re-emits on every DB write; this guard prevents those
    // emissions from overwriting unsaved in-editor state.
    private var isDirty = false
    private var lastHtmlContent: String = ""
    private var lastPlainText: String = ""
    private val AUTO_SAVE_DELAY = 3000L // 3 seconds - debounce rapid edits

    // ── Undo / Redo (20 steps) ──────────────────────────────────────────
    private val undoStack = ArrayDeque<String>(20)
    private val redoStack = ArrayDeque<String>(20)
    private var typingDebounceJob: Job? = null
    private val TYPING_DEBOUNCE_MS = 1500L

    private val _canUndo = MutableStateFlow(false)
    val canUndo: StateFlow<Boolean> = _canUndo.asStateFlow()

    private val _canRedo = MutableStateFlow(false)
    val canRedo: StateFlow<Boolean> = _canRedo.asStateFlow()

    private fun pushUndo() {
        val current = _content.value
        if (undoStack.isEmpty() || undoStack.last() != current) {
            undoStack.addLast(current)
            if (undoStack.size > 20) undoStack.removeFirst()
            redoStack.clear()
            _canUndo.value = undoStack.isNotEmpty()
            _canRedo.value = false
        }
    }

    fun undo() {
        if (undoStack.isEmpty()) return
        // Cancel any pending typing session so that the first keystroke after undo
        // correctly starts a fresh session and saves the restored state for re-undo.
        typingDebounceJob?.cancel()
        typingDebounceJob = null
        val current = _content.value
        val previous = undoStack.removeLast()
        redoStack.addLast(current)
        if (redoStack.size > 20) redoStack.removeFirst()
        _contentSource.value = ContentSource.PROGRAMMATIC
        _content.value = previous
        _canUndo.value = undoStack.isNotEmpty()
        _canRedo.value = redoStack.isNotEmpty()
        isDirty = true
        scheduleAutoSave()
    }

    fun redo() {
        if (redoStack.isEmpty()) return
        // Cancel any pending typing session (same reason as undo).
        typingDebounceJob?.cancel()
        typingDebounceJob = null
        val current = _content.value
        val next = redoStack.removeLast()
        // Push the current state back so the redo is itself undoable. Done inline
        // rather than via pushUndo(), which would wipe the redo stack.
        if (undoStack.isEmpty() || undoStack.last() != current) {
            undoStack.addLast(current)
            if (undoStack.size > 20) undoStack.removeFirst()
        }
        _contentSource.value = ContentSource.PROGRAMMATIC
        _content.value = next
        _canUndo.value = undoStack.isNotEmpty()
        _canRedo.value = redoStack.isNotEmpty()
        isDirty = true
        scheduleAutoSave()
    }

    /** Capture the current content before an attachment insert so image/audio
     *  additions can be undone like any other change. */
    fun pushUndoBeforeChange() {
        pushUndo()
    }

    // ── Note Recordings (voice notes attached to this note) ─────────────
    private val _recordings = MutableStateFlow<List<RecordingEntity>>(emptyList())
    val recordings: StateFlow<List<RecordingEntity>> = _recordings.asStateFlow()

    private val _transcriptSegments = MutableStateFlow<List<com.omnidocs.app.data.local.entity.TranscriptSegmentEntity>>(emptyList())
    val transcriptSegments: StateFlow<List<com.omnidocs.app.data.local.entity.TranscriptSegmentEntity>> = _transcriptSegments.asStateFlow()

    val playingRecordingKey: StateFlow<String?> = audioPlaybackController.currentPlayingKey
    val playbackPositionMs: StateFlow<Long> = audioPlaybackController.playbackPositionMs
    val playbackDurationMs: StateFlow<Long> = audioPlaybackController.playbackDurationMs
    val isPlaybackPaused: StateFlow<Boolean> = audioPlaybackController.isPaused

    private val _playbackSpeed = MutableStateFlow(1f)
    val playbackSpeed: StateFlow<Float> = _playbackSpeed.asStateFlow()

    fun togglePlayback(recording: RecordingEntity) {
        audioPlaybackController.toggle(recording.storageKey)
    }

    fun togglePausePlayback(recording: RecordingEntity) {
        audioPlaybackController.togglePause(recording.storageKey)
    }

    fun seekPlaybackTo(positionMs: Long) {
        audioPlaybackController.seekTo(positionMs)
    }

    fun playSegment(
        recording: RecordingEntity,
        segment: com.omnidocs.app.data.local.entity.TranscriptSegmentEntity
    ) {
        if (audioPlaybackController.currentPlayingKey.value != recording.storageKey) {
            audioPlaybackController.play(recording.storageKey)
        } else if (audioPlaybackController.isPaused.value) {
            audioPlaybackController.resume()
        }
        audioPlaybackController.seekTo(segment.startMs)
    }

    fun cyclePlaybackSpeed() {
        val next = when (_playbackSpeed.value) {
            1f -> 1.25f
            1.25f -> 1.5f
            1.5f -> 2f
            else -> 1f
        }
        _playbackSpeed.value = next
        audioPlaybackController.setSpeed(next)
    }

    fun stopPlayback() {
        audioPlaybackController.stop()
    }

    fun loadNote(noteId: String) {
        // Never re-load the same note — protects unsaved ViewModel state
        // (e.g. OCR text) from being overwritten by stale database content
        // when the editor re-enters composition.
        if (noteId == loadedNoteId) return
        loadedNoteId = noteId
        noteLoadJob?.cancel()
        noteLoadJob = viewModelScope.launch {
            repository.getNoteByIdFlow(noteId).collect { note ->
                note?.let {
                    // The Room flow re-emits on every write (autosave, togglePin).
                    // Only push DB state into the editor when there are no unsaved
                    // edits; otherwise the emission overwrites in-progress typing
                    // with stale database content.
                    if (!isDirty) {
                        _currentNote.value = it
                        if (it.title != _title.value) _title.value = it.title
                        if (it.content != _content.value) {
                            _contentSource.value = ContentSource.PROGRAMMATIC
                            _content.value = it.content
                        }
                        if (it.language != _currentLanguage.value) _currentLanguage.value = it.language
                    } else {
                        // Unsaved edits in progress: keep the editor state, only
                        // refresh cheap metadata that can't clobber content.
                        _currentNote.value = _currentNote.value?.copy(
                            isPinned = it.isPinned,
                            isDeleted = it.isDeleted
                        )
                    }
                }
            }
        }

        // Load the recordings belonging to this note.
        viewModelScope.launch {
            recordingDao.getRecordingsByNoteId(noteId).collect { recordings ->
                _recordings.value = recordings
            }
        }

        // Load timestamped transcript segments belonging to this note.
        transcriptSegmentDao?.let { segDao ->
            viewModelScope.launch {
                segDao.getSegmentsByNoteId(noteId).collect { segments ->
                    _transcriptSegments.value = segments
                }
            }
        }

        // Load content blocks with OCR bounding boxes belonging to this note.
        viewModelScope.launch {
            contentBlockDao.getBlocksForNote(noteId).collect { blocks ->
                _contentBlocks.value = blocks
            }
        }
    }

    fun updateTitle(title: String) {
        _title.value = title
        isDirty = true
        scheduleAutoSave()
    }

    fun updateContent(content: String, fromWebView: Boolean = false) {
        _contentSource.value = if (fromWebView) ContentSource.WEBVIEW else ContentSource.PROGRAMMATIC
        if (fromWebView) {
            // On the FIRST keystroke of each typing session, push the content that
            // existed before typing started so the user can undo back to it.
            // typingDebounceJob == null means we are not currently in an active session.
            // _content.value at this point is still the pre-edit state (we haven't
            // assigned it yet), so this correctly captures the snapshot to restore.
            if (typingDebounceJob == null) {
                val preEdit = _content.value
                if (undoStack.isEmpty() || undoStack.last() != preEdit) {
                    undoStack.addLast(preEdit)
                    if (undoStack.size > 20) undoStack.removeFirst()
                    redoStack.clear()
                    _canUndo.value = true
                    _canRedo.value = false
                }
            }
            // Reset the inactivity window. When the timer fires the session is over
            // and the next keystroke will start a new session (and push a new undo point).
            typingDebounceJob?.cancel()
            typingDebounceJob = viewModelScope.launch {
                delay(TYPING_DEBOUNCE_MS)
                typingDebounceJob = null
            }
        }
        _content.value = content
        isDirty = true
        scheduleAutoSave()
    }

    fun appendToContent(text: String) {
        pushUndo()
        val current = _content.value
        _contentSource.value = ContentSource.PROGRAMMATIC
        _content.value = if (current.isEmpty()) text else "$current\n$text"
        isDirty = true
        scheduleAutoSave()
    }

    fun setEditorMode(mode: EditorMode) {
        val previous = _editorMode.value
        if (previous == mode) return

        when (mode) {
            EditorMode.RICH -> {
                // updateMarkdown keeps _content in sync with the markdown source, so
                // a dirty markdown session already has the converted HTML in _content.
                // Only an untouched session needs the exact original HTML restored.
                if (previous == EditorMode.MARKDOWN && !markdownDirty) {
                    if (richHtmlSnapshot != _content.value) {
                        _contentSource.value = ContentSource.PROGRAMMATIC
                        _content.value = richHtmlSnapshot
                    }
                }
                // In RICH, current content (converted or restored) is the source of
                // truth; markdownText is stale until the next markdown entry.
                markdownDirty = false
            }
            EditorMode.MARKDOWN -> {
                // Seed markdown from the current HTML, snapshotting it for fidelity.
                richHtmlSnapshot = _content.value
                _markdownText.value = MarkdownCodec.htmlToMarkdown(richHtmlSnapshot)
                markdownDirty = false
            }
            EditorMode.PREVIEW -> {
                // No state mutation; Preview merely renders currentMarkdown().
            }
        }
        _editorMode.value = mode
    }

    fun updateMarkdown(text: String) {
        _markdownText.value = text
        markdownDirty = true
        val html = MarkdownCodec.markdownToHtml(text)
        _contentSource.value = ContentSource.PROGRAMMATIC
        _content.value = html
        isDirty = true
        scheduleAutoSave()
    }

    /** Markdown source shown in Preview mode. From RICH it re-derives from HTML so
     *  unstaged HTML edits are reflected; from MARKDOWN it is the live edited text. */
    fun currentMarkdown(): String = when (_editorMode.value) {
        EditorMode.RICH, EditorMode.PREVIEW -> MarkdownCodec.htmlToMarkdown(_content.value)
        EditorMode.MARKDOWN -> _markdownText.value
    }

    fun updateLanguage(language: String) {
        _currentLanguage.value = language
    }

    fun togglePin() {
        viewModelScope.launch {
            _currentNote.value?.let { note ->
                // Optimistically flip the in-memory pin state. The DAO toggles only
                // the pinned flag (never content), so unsaved edits are preserved.
                _currentNote.value = note.copy(isPinned = !note.isPinned)
                repository.togglePin(note)
            }
        }
    }

    private val saveMutex = Mutex()

    private fun scheduleAutoSave() {
        autoSaveJob?.cancel()
        autoSaveJob = viewModelScope.launch {
            delay(AUTO_SAVE_DELAY)
            saveNoteInternal(isAutoSave = true)
        }
    }

    fun saveNote() {
        viewModelScope.launch {
            saveNoteInternal(isAutoSave = false)
        }
    }

    /**
     * Save the current note and navigate back only after the save completes.
     * Prevents data loss when the user navigates away immediately.
     */
    fun saveAndNavigate(onNavigated: () -> Unit) {
        viewModelScope.launch {
            saveNoteInternal(isAutoSave = false)
            // Trigger evidence extraction exactly once per deliberate save, not on
            // every 3-second auto-save. The EvidenceExtractor's isExtracting guard
            // and content-hash check ensure only one GGML call runs at a time and
            // only when the content has actually changed.
            val note = _currentNote.value
            val plainText = stripHtml(_content.value)
            if (note != null && plainText.length > 200) {
                evidenceExtractor.extractFromNoteAsync(
                    noteId = note.id,
                    text = plainText,
                    language = _currentLanguage.value
                )
            }
            onNavigated()
        }
    }

    fun exportAsMarkdown(context: android.content.Context) {
        viewModelScope.launch {
            try {
                val note = _currentNote.value ?: run {
                    _snackbarEvent.tryEmit("Save the note first before exporting")
                    return@launch
                }
                val markdown = com.omnidocs.app.util.HtmlToMarkdown.convert(_content.value)
                val safeName = note.title.take(50).replace(Regex("[^a-zA-Z0-9\\s-]"), "").trim().replace(Regex("\\s+"), "_")
                val fileName = if (safeName.isNotEmpty()) "$safeName.md" else "note.md"
                val dir = java.io.File(context.getExternalFilesDir(null), "exports")
                dir.mkdirs()
                val file = java.io.File(dir, fileName)
                file.writeText("# ${note.title}\n\n$markdown")
                _snackbarEvent.tryEmit("Exported to: ${file.name}")
            } catch (e: Exception) {
                _snackbarEvent.tryEmit("Export failed: ${e.message ?: "Unknown error"}")
            }
        }
    }

    private suspend fun saveNoteInternal(isAutoSave: Boolean = false) {
        saveMutex.withLock {
            _isSaving.value = true
            try {
                val note = _currentNote.value
                if (note != null) {
                    val updatedNote = note.copy(
                        title = _title.value,
                        content = _content.value,
                        plainText = stripHtml(_content.value),
                        language = _currentLanguage.value,
                        imageUrl = _ocrImageUrl.value ?: note.imageUrl,
                        updatedAt = System.currentTimeMillis()
                    )
                    repository.updateNote(updatedNote)
                    _currentNote.value = updatedNote
                    // DB now matches the editor; allow the Room flow to re-sync.
                    isDirty = false
                } else {
                    val createdNote = repository.createNote(
                        title = _title.value,
                        content = _content.value,
                        plainText = stripHtml(_content.value),
                        language = _currentLanguage.value
                    )
                    val newNote = if (_ocrImageUrl.value != null) {
                        val withImage = createdNote.copy(imageUrl = _ocrImageUrl.value)
                        repository.updateNoteMetadataOnly(withImage)
                        withImage
                    } else {
                        createdNote
                    }
                    _currentNote.value = newNote
                    // DB now matches the editor; allow the Room flow to re-sync.
                    isDirty = false
                    // Auto-tag new notes with sufficient content. Fire-and-forget so
                    // the slow offline-LLM call never blocks save.
                    if (_content.value.length > 100) {
                        autoTagger.tagNoteAsync(newNote)
                    }
                }
                val savedNoteId = _currentNote.value?.id
                val pendingBlocks = pendingOcrBlocksJson
                if (savedNoteId != null && !pendingBlocks.isNullOrBlank()) {
                    val blocks = parseOcrBlocksJson(pendingBlocks, savedNoteId)
                    if (blocks.isNotEmpty()) {
                        try {
                            contentBlockDao.insertBlocks(blocks)
                            _contentBlocks.value = blocks
                            pendingOcrBlocksJson = null
                        } catch (_: Exception) {}
                    }
                }
                if (!isAutoSave) {
                    val savedId = note?.id ?: _currentNote.value?.id
                    if (savedId != null) {
                        val latestVer = noteVersionDao.getLatestVersion(savedId)?.version ?: 0
                        noteVersionDao.insertVersion(
                            com.omnidocs.app.data.local.entity.NoteVersionEntity(
                                id = java.util.UUID.randomUUID().toString(),
                                noteId = savedId,
                                version = latestVer + 1,
                                content = _content.value,
                                plainText = com.omnidocs.app.util.HtmlSanitizer.toPlainText(_content.value),
                                createdAt = System.currentTimeMillis()
                            )
                        )
                    }
                    _snackbarEvent.tryEmit("Note saved")
                }
            } catch (e: Exception) {
                if (!isAutoSave) {
                    _snackbarEvent.tryEmit("Failed to save note: ${e.message ?: "Unknown error"}")
                }
            } finally {
                _isSaving.value = false
            }
        }
    }

    fun insertFormatting(before: String, after: String) {
        pushUndo()
        _contentSource.value = ContentSource.PROGRAMMATIC
        _content.value = "$before${_content.value}$after"
        isDirty = true
        scheduleAutoSave()
    }

    fun setLanguage(language: String) {
        _currentLanguage.value = language
    }

    // ---- AI Preview Dialog ----

    /**
     * Show AI result in a preview dialog so the user can review before applying.
     * This prevents the destructive "replace content" pattern.
     */
    fun aiSummarize() {
        val originalContent = _content.value
        val text = stripHtml(originalContent)
        if (text.isBlank()) return

        _aiPreview.value = AiPreviewState(
            operation = AiOperation.SUMMARIZE,
            originalContent = originalContent,
            result = "",
            isLoading = true
        )

        aiPreviewJob?.cancel()
        aiPreviewJob = viewModelScope.launch {
            try {
                val result = aiService.summarize(text, _currentLanguage.value)
                currentCoroutineContext().ensureActive()
                _aiPreview.value = _aiPreview.value?.copy(
                    result = result ?: "No AI model is downloaded. Please download an on-device model in Settings.",
                    isLoading = false,
                    error = if (result == null) "No on-device AI model is downloaded. Go to Settings > AI Models to download a local model." else null
                )
            } catch (e: CancellationException) {
                currentCoroutineContext().ensureActive()
                throw e
            } catch (e: Exception) {
                currentCoroutineContext().ensureActive()
                _aiPreview.value = _aiPreview.value?.copy(
                    isLoading = false,
                    error = e.message ?: "AI operation failed"
                )
            }
        }
    }

    fun aiProofread() {
        val originalContent = _content.value
        val text = stripHtml(originalContent)
        if (text.isBlank()) return

        _aiPreview.value = AiPreviewState(
            operation = AiOperation.PROOFREAD,
            originalContent = originalContent,
            result = "",
            isLoading = true
        )

        aiPreviewJob?.cancel()
        aiPreviewJob = viewModelScope.launch {
            try {
                val result = aiService.proofread(text, _currentLanguage.value)
                currentCoroutineContext().ensureActive()
                _aiPreview.value = _aiPreview.value?.copy(
                    result = result ?: "No AI model is downloaded. Please download an on-device model in Settings.",
                    isLoading = false,
                    error = if (result == null) "No on-device AI model is downloaded. Go to Settings > AI Models to download a local model." else null
                )
            } catch (e: CancellationException) {
                currentCoroutineContext().ensureActive()
                throw e
            } catch (e: Exception) {
                currentCoroutineContext().ensureActive()
                _aiPreview.value = _aiPreview.value?.copy(
                    isLoading = false,
                    error = e.message ?: "AI operation failed"
                )
            }
        }
    }

    fun aiRewrite() {
        val originalContent = _content.value
        val text = stripHtml(originalContent)
        if (text.isBlank()) return

        _aiPreview.value = AiPreviewState(
            operation = AiOperation.REWRITE,
            originalContent = originalContent,
            result = "",
            isLoading = true
        )

        aiPreviewJob?.cancel()
        aiPreviewJob = viewModelScope.launch {
            try {
                val result = aiService.rewrite(text, _currentLanguage.value)
                currentCoroutineContext().ensureActive()
                _aiPreview.value = _aiPreview.value?.copy(
                    result = result ?: "No AI model is downloaded. Please download an on-device model in Settings.",
                    isLoading = false,
                    error = if (result == null) "No on-device AI model is downloaded. Go to Settings > AI Models to download a local model." else null
                )
            } catch (e: CancellationException) {
                currentCoroutineContext().ensureActive()
                throw e
            } catch (e: Exception) {
                currentCoroutineContext().ensureActive()
                _aiPreview.value = _aiPreview.value?.copy(
                    isLoading = false,
                    error = e.message ?: "AI operation failed"
                )
            }
        }
    }

    fun applyAiResult() {
        val preview = _aiPreview.value ?: return
        if (preview.isLoading || preview.error != null) return

        pushUndo()
        val plainResult = preview.result.trim()

        if (_editorMode.value == EditorMode.MARKDOWN) {
            val currentMd = _markdownText.value
            val formattedMd = when (preview.operation) {
                AiOperation.SUMMARIZE -> "## Summary\n\n$plainResult\n\n---\n\n$currentMd"
                AiOperation.PROOFREAD -> "$currentMd\n\n---\n\n### Proofread Version\n\n$plainResult"
                AiOperation.REWRITE -> "$currentMd\n\n---\n\n### Rewritten Version\n\n$plainResult"
            }
            updateMarkdown(formattedMd)
        } else {
            _contentSource.value = ContentSource.PROGRAMMATIC
            when (preview.operation) {
                AiOperation.SUMMARIZE -> {
                    // Convert bullet-point lines to HTML list items for rich rendering
                    val htmlSummary = plainResult.lines().joinToString("\n") { line ->
                        val trimmed = line.trim()
                        when {
                            trimmed.startsWith("•") -> "<li>${sanitizeForHtml(trimmed.removePrefix("•").trim())}</li>"
                            trimmed.startsWith("-") -> "<li>${sanitizeForHtml(trimmed.removePrefix("-").trim())}</li>"
                            trimmed.startsWith("**") && trimmed.endsWith("**") -> {
                                "<p><strong>${sanitizeForHtml(trimmed.removeSurrounding("**"))}</strong></p>"
                            }
                            trimmed.isBlank() -> ""
                            else -> "<p>${sanitizeForHtml(trimmed)}</p>"
                        }
                    }
                    _content.value = "<p><strong>Summary:</strong></p><ul>$htmlSummary</ul><hr><p>${preview.originalContent}</p>"
                }
                AiOperation.PROOFREAD -> {
                    val htmlParagraphs = plainResult.lines()
                        .filter { it.isNotBlank() }
                        .joinToString("") { "<p>${sanitizeForHtml(it.trim())}</p>" }
                    _content.value = "${preview.originalContent}<hr><p><strong>Proofread Version:</strong></p>$htmlParagraphs"
                }
                AiOperation.REWRITE -> {
                    val htmlParagraphs = plainResult.lines()
                        .filter { it.isNotBlank() }
                        .joinToString("") { "<p>${sanitizeForHtml(it.trim())}</p>" }
                    _content.value = "${preview.originalContent}<hr><p><strong>Rewritten:</strong></p>$htmlParagraphs"
                }
            }
        }
        isDirty = true
        _aiPreview.value = null
        scheduleAutoSave()
    }

    fun dismissAiPreview() {
        aiPreviewJob?.cancel()
        aiPreviewJob = null
        aiService.stopCurrentGeneration()
        _aiPreview.value = null
    }

    // ── Note Intelligence Methods ──────────────────────────────────────

    fun askAboutNote(question: String) {
        val noteContent = _content.value
        if (noteContent.isBlank() || question.isBlank()) return

        _intelligenceMessages.value = _intelligenceMessages.value + IntelligenceMessage("user", question)
        _isIntelligenceLoading.value = true
        _intelligenceStreaming.value = ""

        askJob?.cancel()
        val myGeneration = ++askGeneration
        askJob = viewModelScope.launch {
            val collected = StringBuilder()
            try {
                val history = _intelligenceMessages.value.dropLast(1).map { it.role to it.content }
                noteIntelligenceService.askAboutNoteStream(
                    noteContent = stripHtml(noteContent),
                    question = question,
                    language = _currentLanguage.value,
                    conversationHistory = history
                ).collect { token ->
                    collected.append(token)
                    _intelligenceStreaming.value = collected.toString()
                }
                val answer = collected.toString().ifBlank {
                    "No answer was generated. Check a model is downloaded in Settings, then try again."
                }
                _intelligenceMessages.value = _intelligenceMessages.value +
                    IntelligenceMessage("assistant", answer)
            } catch (e: CancellationException) {
                // Stopped by user; partial streaming text is discarded
            } catch (e: Exception) {
                _intelligenceMessages.value = _intelligenceMessages.value +
                    IntelligenceMessage("assistant", "Error: ${e.message ?: "Unknown error"}")
            } finally {
                // A stale (cancelled) run must not wipe a newer ask's UI state.
                if (myGeneration == askGeneration) clearIntelligenceUiIfIdle()
            }
        }
    }

    fun explainSelectedText(selectedText: String) {
        val noteContent = _content.value
        if (noteContent.isBlank() || selectedText.isBlank()) return

        _intelligenceMessages.value = _intelligenceMessages.value +
            IntelligenceMessage("user", "Explain: \"$selectedText\"")
        _isIntelligenceLoading.value = true

        explainJob?.cancel()
        val myGeneration = ++explainGeneration
        explainJob = viewModelScope.launch {
            try {
                val explanation = noteIntelligenceService.explainText(
                    fullNote = stripHtml(noteContent),
                    selectedText = selectedText,
                    language = _currentLanguage.value
                )
                _intelligenceMessages.value = _intelligenceMessages.value +
                    IntelligenceMessage("assistant", explanation)
            } catch (e: CancellationException) {
                // Stopped by user
            } catch (e: Exception) {
                _intelligenceMessages.value = _intelligenceMessages.value +
                    IntelligenceMessage("assistant", "Error: ${e.message ?: "Unknown error"}")
            } finally {
                if (myGeneration == explainGeneration) clearIntelligenceUiIfIdle()
            }
        }
    }

    fun extractConcepts() {
        val noteContent = _content.value
        if (noteContent.isBlank()) return

        _isIntelligenceLoading.value = true
        conceptsJob?.cancel()
        val myGeneration = ++conceptsGeneration
        conceptsJob = viewModelScope.launch {
            try {
                val concepts = noteIntelligenceService.extractConcepts(
                    noteContent = stripHtml(noteContent),
                    language = _currentLanguage.value
                )
                _intelligenceConcepts.value = concepts
                _showConceptDialog.value = true
            } catch (e: CancellationException) {
                // Stopped by user
            } catch (e: Exception) {
                _snackbarEvent.tryEmit("Failed to extract concepts: ${e.message}")
            } finally {
                if (myGeneration == conceptsGeneration) clearIntelligenceUiIfIdle()
            }
        }
    }

    fun createNoteFromConcept(title: String, description: String) {
        viewModelScope.launch {
            try {
                val note = repository.createNote(
                    title = title,
                    content = "<p>${sanitizeForHtml(description)}</p>",
                    plainText = description,
                    language = _currentLanguage.value
                )
                _snackbarEvent.tryEmit("Created note: $title")
            } catch (e: Exception) {
                _snackbarEvent.tryEmit("Failed to create note: ${e.message}")
            }
        }
    }

    fun dismissConceptDialog() {
        _showConceptDialog.value = false
    }

    fun stopIntelligence() {
        askJob?.cancel()
        explainJob?.cancel()
        conceptsJob?.cancel()
        askJob = null
        explainJob = null
        conceptsJob = null
        noteIntelligenceService.stopIntelligence()
        _isIntelligenceLoading.value = false
        _intelligenceStreaming.value = ""
    }

    fun clearIntelligenceChat() {
        // Clearing mid-stream must stop generation, or the orphaned stream
        // appends an answer to the fresh (empty) chat afterwards.
        stopIntelligence()
        _intelligenceMessages.value = emptyList()
    }

    // ---- End AI Preview Dialog ----

    private fun stripHtml(html: String): String {
        if (html == lastHtmlContent) return lastPlainText
        val plain = android.text.Html.fromHtml(html, android.text.Html.FROM_HTML_MODE_COMPACT)
            .toString()
            .trim()
        lastHtmlContent = html
        lastPlainText = plain
        return plain
    }

    override fun onCleared() {
        super.onCleared()
        askJob?.cancel()
        explainJob?.cancel()
        conceptsJob?.cancel()
        aiPreviewJob?.cancel()
        noteIntelligenceService.stopIntelligence()
        audioPlaybackController.stop()
    }

    companion object {
        private val UNSPACED_SCRIPT_REGEX = Regex("[一-鿿㐀-䶿぀-ヿ]")

        fun calculateWordCount(text: String): Int {
            if (text.isBlank()) return 0
            var unspacedCount = 0
            val spacedBuilder = StringBuilder()
            for (ch in text) {
                if (UNSPACED_SCRIPT_REGEX.matches(ch.toString())) {
                    unspacedCount++
                    spacedBuilder.append(' ')
                } else {
                    spacedBuilder.append(ch)
                }
            }
            val spacedWords = spacedBuilder.toString().trim().split(Regex("\\s+")).count { it.isNotBlank() }
            return unspacedCount + spacedWords
        }

        fun calculateReadingTime(text: String): Int {
            if (text.isBlank()) return 0
            var unspacedCount = 0
            val spacedBuilder = StringBuilder()
            for (ch in text) {
                if (UNSPACED_SCRIPT_REGEX.matches(ch.toString())) {
                    unspacedCount++
                    spacedBuilder.append(' ')
                } else {
                    spacedBuilder.append(ch)
                }
            }
            val spacedWords = spacedBuilder.toString().trim().split(Regex("\\s+")).count { it.isNotBlank() }
            val totalTokens = unspacedCount + spacedWords
            if (totalTokens == 0) return 0
            // Reading rate: Spaced languages ~200 WPM, unspaced CJK ~350 characters/min
            val minutes = (spacedWords / 200.0) + (unspacedCount / 350.0)
            return maxOf(1, Math.round(minutes).toInt())
        }
    }
}

enum class AiOperation {
    SUMMARIZE, PROOFREAD, REWRITE
}

data class AiPreviewState(
    val operation: AiOperation,
    val originalContent: String,
    val result: String,
    val isLoading: Boolean = true,
    val error: String? = null
)
