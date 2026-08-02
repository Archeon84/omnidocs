package com.omnidocs.app.ui.screens.editor

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.omnidocs.app.domain.model.Note
import com.omnidocs.app.data.repository.NotesRepository
import com.omnidocs.app.ai.AiService
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class EditorViewModel @Inject constructor(
    private val repository: NotesRepository,
    private val aiService: AiService
) : ViewModel() {

    private val _currentNote = MutableStateFlow<Note?>(null)
    val currentNote: StateFlow<Note?> = _currentNote.asStateFlow()

    private val _title = MutableStateFlow("")
    val title: StateFlow<String> = _title.asStateFlow()

    private val _content = MutableStateFlow("")
    val content: StateFlow<String> = _content.asStateFlow()

    val wordCount: StateFlow<Int> = _content.map { html ->
        val text = stripHtml(html)
        if (text.isBlank()) 0 else text.split(Regex("\\s+")).size
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

    private val _snackbarEvent = MutableSharedFlow<String>(extraBufferCapacity = 1)
    val snackbarEvent: SharedFlow<String> = _snackbarEvent.asSharedFlow()

    private val _aiPreview = MutableStateFlow<AiPreviewState?>(null)
    val aiPreview: StateFlow<AiPreviewState?> = _aiPreview.asStateFlow()

    private var autoSaveJob: Job? = null
    private var noteLoadJob: Job? = null
    private var loadedNoteId: String? = null
    private var lastHtmlContent: String = ""
    private var lastPlainText: String = ""
    private val AUTO_SAVE_DELAY = 3000L // 3 seconds - debounce rapid edits

    // ── Undo / Redo (20 steps) ──────────────────────────────────────────
    private val undoStack = ArrayDeque<String>(20)
    private val redoStack = ArrayDeque<String>(10)
    private var lastContentBeforeTyping: String = ""
    private var typingDebounceJob: Job? = null
    private val TYPING_DEBOUNCE_MS = 1500L

    private val _canUndo = MutableStateFlow(false)
    val canUndo: StateFlow<Boolean> = _canUndo.asStateFlow()

    private fun pushUndo() {
        val current = _content.value
        if (undoStack.isEmpty() || undoStack.last() != current) {
            undoStack.addLast(current)
            if (undoStack.size > 20) undoStack.removeFirst()
            redoStack.clear()
            _canUndo.value = undoStack.isNotEmpty()
        }
    }

    fun undo() {
        if (undoStack.isEmpty()) return
        val current = _content.value
        val previous = undoStack.removeLast()
        redoStack.addLast(current)
        _content.value = previous
        _canUndo.value = undoStack.isNotEmpty()
        scheduleAutoSave()
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
                    _currentNote.value = it
                    _title.value = it.title
                    _content.value = it.content
                    _currentLanguage.value = it.language
                }
            }
        }
    }

    fun updateTitle(title: String) {
        _title.value = title
        scheduleAutoSave()
    }

    fun updateContent(content: String) {
        // Capture the state BEFORE this edit for undo.
        // On the first keystroke of a typing session, save the pre-edit state.
        // Then debounce so we don't push every keystroke — only the state
        // before the user started typing.
        if (lastContentBeforeTyping.isEmpty() || content == lastContentBeforeTyping) {
            // First call or unchanged — skip
        } else if (typingDebounceJob == null) {
            // First real edit in a new typing session — push the pre-edit state
            val preEdit = lastContentBeforeTyping
            if (undoStack.isEmpty() || undoStack.last() != preEdit) {
                undoStack.addLast(preEdit)
                if (undoStack.size > 20) undoStack.removeFirst()
                redoStack.clear()
                _canUndo.value = true
            }
        }
        lastContentBeforeTyping = content
        typingDebounceJob?.cancel()
        typingDebounceJob = viewModelScope.launch {
            delay(TYPING_DEBOUNCE_MS)
            typingDebounceJob = null
            lastContentBeforeTyping = ""
        }

        _content.value = content
        scheduleAutoSave()
    }

    fun appendToContent(text: String) {
        pushUndo()
        val current = _content.value
        _content.value = if (current.isEmpty()) text else "$current\n$text"
        scheduleAutoSave()
    }

    fun updateLanguage(language: String) {
        _currentLanguage.value = language
    }

    fun togglePin() {
        viewModelScope.launch {
            _currentNote.value?.let { note ->
                repository.togglePin(note)
            }
        }
    }

    private fun scheduleAutoSave() {
        autoSaveJob?.cancel()
        autoSaveJob = viewModelScope.launch {
            delay(AUTO_SAVE_DELAY)
            saveNote()
        }
    }

    fun saveNote() {
        viewModelScope.launch {
            saveNoteInternal()
        }
    }

    /**
     * Save the current note and navigate back only after the save completes.
     * Prevents data loss when the user navigates away immediately.
     */
    fun saveAndNavigate(onNavigated: () -> Unit) {
        viewModelScope.launch {
            saveNoteInternal()
            onNavigated()
        }
    }

    private suspend fun saveNoteInternal() {
        _isSaving.value = true
        try {
            val note = _currentNote.value
            if (note != null) {
                repository.updateNote(
                    note.copy(
                        title = _title.value,
                        content = _content.value,
                        plainText = stripHtml(_content.value),
                        language = _currentLanguage.value,
                        updatedAt = System.currentTimeMillis()
                    )
                )
            } else {
                val newNote = repository.createNote(
                    title = _title.value,
                    content = _content.value,
                    plainText = stripHtml(_content.value),
                    language = _currentLanguage.value
                )
                _currentNote.value = newNote
            }
            _snackbarEvent.tryEmit("Note saved")
        } catch (e: Exception) {
            _snackbarEvent.tryEmit("Failed to save note: ${e.message ?: "Unknown error"}")
        } finally {
            _isSaving.value = false
        }
    }

    fun insertFormatting(before: String, after: String) {
        pushUndo()
        _content.value = "$before${_content.value}$after"
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

        viewModelScope.launch {
            try {
                val result = aiService.summarize(text, _currentLanguage.value)
                _aiPreview.value = _aiPreview.value?.copy(
                    result = result ?: "AI returned no result. The model may not be downloaded.",
                    isLoading = false,
                    error = if (result == null) "AI returned no result" else null
                )
            } catch (e: Exception) {
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

        viewModelScope.launch {
            try {
                val result = aiService.proofread(text, _currentLanguage.value)
                _aiPreview.value = _aiPreview.value?.copy(
                    result = result ?: "AI returned no result. The model may not be downloaded.",
                    isLoading = false,
                    error = if (result == null) "AI returned no result" else null
                )
            } catch (e: Exception) {
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

        viewModelScope.launch {
            try {
                val result = aiService.rewrite(text, _currentLanguage.value)
                _aiPreview.value = _aiPreview.value?.copy(
                    result = result ?: "AI returned no result. The model may not be downloaded.",
                    isLoading = false,
                    error = if (result == null) "AI returned no result" else null
                )
            } catch (e: Exception) {
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
        val safeResult = com.omnidocs.app.util.HtmlSanitizer.toPlainText(preview.result)
        when (preview.operation) {
            AiOperation.SUMMARIZE -> {
                // Convert bullet-point lines to HTML list items for rich rendering
                val htmlResult = safeResult.lines().joinToString("\n") { line ->
                    val trimmed = line.trim()
                    when {
                        trimmed.startsWith("•") -> "<li>${trimmed.removePrefix("•").trim()}</li>"
                        trimmed.startsWith("-") -> "<li>${trimmed.removePrefix("-").trim()}</li>"
                        trimmed.startsWith("**") && trimmed.endsWith("**") -> {
                            "<p><strong>${trimmed.removeSurrounding("**")}</strong></p>"
                        }
                        trimmed.isBlank() -> ""
                        else -> "<p>$trimmed</p>"
                    }
                }
                _content.value = "<p><strong>Summary:</strong></p><ul>$htmlResult</ul><hr><p>${preview.originalContent}</p>"
            }
            AiOperation.PROOFREAD -> {
                _content.value = "${preview.originalContent}<hr><p><strong>Proofread Version:</strong></p><p>${safeResult}</p>"
            }
            AiOperation.REWRITE -> {
                _content.value = "${preview.originalContent}<hr><p><strong>Rewritten:</strong></p><p>${safeResult}</p>"
            }
        }
        _aiPreview.value = null
        scheduleAutoSave()
    }

    fun dismissAiPreview() {
        _aiPreview.value = null
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
