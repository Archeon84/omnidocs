package com.omnidocs.app.ui.screens.voice

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.omnidocs.app.ai.ModelPreferences
import com.omnidocs.app.data.local.RecordingDao
import com.omnidocs.app.data.local.TranscriptSegmentDao
import com.omnidocs.app.data.local.entity.RecordingEntity
import com.omnidocs.app.data.local.entity.TranscriptSegmentEntity
import com.omnidocs.app.data.repository.NotesRepository
import com.omnidocs.app.util.sanitizeForHtml
import com.omnidocs.app.voice.VoiceCaptureManager
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.UUID
import javax.inject.Inject

@HiltViewModel
class VoiceCaptureViewModel @Inject constructor(
    private val voiceCaptureManager: VoiceCaptureManager,
    private val repository: NotesRepository,
    private val recordingDao: RecordingDao,
    private val transcriptSegmentDao: TranscriptSegmentDao,
    private val modelPreferences: ModelPreferences
) : ViewModel() {

    val transcript: StateFlow<String> = voiceCaptureManager.transcript
    val isListening: StateFlow<Boolean> = voiceCaptureManager.isListening
    val error: StateFlow<String?> = voiceCaptureManager.error
    val liveAudioLevel: StateFlow<Float> = voiceCaptureManager.liveAudioLevel
    val engineType: StateFlow<String> = voiceCaptureManager.engineType

    private val _isStructuring = MutableStateFlow(false)
    val isStructuring: StateFlow<Boolean> = _isStructuring.asStateFlow()

    private val _structuredContent = MutableStateFlow<String?>(null)
    val structuredContent: StateFlow<String?> = _structuredContent.asStateFlow()

    private val _savedNoteId = MutableStateFlow<String?>(null)
    val savedNoteId: StateFlow<String?> = _savedNoteId.asStateFlow()

    /**
     * Single source of truth with Settings → Speech Language: seeded from the
     * persisted preference on open, and every overlay change writes straight
     * back. The STT engine re-initializes from the preference on each capture
     * start, so the new choice takes effect immediately.
     */
    private val _selectedLanguageCode = MutableStateFlow("")
    val selectedLanguageCode: StateFlow<String> = _selectedLanguageCode.asStateFlow()

    init {
        viewModelScope.launch {
            _selectedLanguageCode.value = modelPreferences.sttLanguage.first()
        }
    }

    fun setLanguage(code: String) {
        _selectedLanguageCode.value = code
        viewModelScope.launch {
            modelPreferences.setSttLanguage(code)
        }
    }

    /** Elapsed ms of the current capture session (ticks while listening). */
    private val _elapsedMs = MutableStateFlow(0L)
    val elapsedMs: StateFlow<Long> = _elapsedMs.asStateFlow()

    private var timerJob: Job? = null

    /** True once any capture started this overlay session (for empty-take guidance). */
    private val _hasRecorded = MutableStateFlow(false)
    val hasRecorded: StateFlow<Boolean> = _hasRecorded.asStateFlow()

    /**
     * User-edited transcript override. Null means "follow the live manager
     * stream". A new capture session clears the override because the manager
     * stream is the source of truth for appended speech.
     */
    private val _editedTranscript = MutableStateFlow<String?>(null)

    /** Text shown in the box and used at save time. */
    val displayTranscript: StateFlow<String> =
        combine(transcript, _editedTranscript) { live, edited -> edited ?: live }
            .stateIn(viewModelScope, SharingStarted.Eagerly, "")

    fun updateEditedTranscript(text: String) {
        _editedTranscript.value = text
    }

    fun startListening() {
        // Do NOT clear the transcript here: "Record More" must append to the
        // previous session's text. The manager starts fresh on its own when
        // nothing was recorded yet, and dismiss() resets the whole session.
        // A fresh session invalidates any manual edit (see _editedTranscript).
        _editedTranscript.value = null
        _hasRecorded.value = true
        _elapsedMs.value = 0L
        voiceCaptureManager.startListening(_selectedLanguageCode.value)
        timerJob?.cancel()
        val startedAt = System.currentTimeMillis()
        timerJob = viewModelScope.launch {
            while (isActive) {
                delay(250)
                _elapsedMs.value = System.currentTimeMillis() - startedAt
            }
        }
    }

    fun stopListening() {
        timerJob?.cancel()
        timerJob = null
        voiceCaptureManager.stopListening()
    }

    /** Discard the current take and reset the session, staying in the overlay. */
    fun startOver() {
        timerJob?.cancel()
        timerJob = null
        voiceCaptureManager.stopListening()
        voiceCaptureManager.clearTranscript()
        _editedTranscript.value = null
        _elapsedMs.value = 0L
        _hasRecorded.value = false
    }

    private var saveJob: Job? = null

    fun confirmAndSave() {
        saveNote(structured = true)
    }

    /** Save immediately without LLM structuring (instant when no model is downloaded). */
    fun saveRaw() {
        saveNote(structured = false)
    }

    fun cancelSaving() {
        saveJob?.cancel()
        saveJob = null
        _isStructuring.value = false
    }

    private fun saveNote(structured: Boolean) {
        val rawText = displayTranscript.value
        if (rawText.isBlank()) return
        val language = _selectedLanguageCode.value

        saveJob?.cancel()
        _isStructuring.value = structured
        saveJob = viewModelScope.launch {
            try {
                val content = if (structured) {
                    voiceCaptureManager.structureTranscript(rawText, language)
                        .also { _structuredContent.value = it }
                } else {
                    rawSaveContent(rawText)
                }

                val title = generateTitle(rawText)

                val note = repository.createNote(
                    title = title,
                    content = content,
                    plainText = rawText,
                    language = language
                )

                // Save recording and transcript segment records
                saveRecordingAndSegments(note.id, rawText, language)

                _savedNoteId.value = note.id
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // If structuring fails, save raw text with HTML-escaped content
                val title = generateTitle(rawText)

                val note = repository.createNote(
                    title = title,
                    content = rawSaveContent(rawText),
                    plainText = rawText,
                    language = language
                )

                saveRecordingAndSegments(note.id, rawText, language)

                _savedNoteId.value = note.id
            } finally {
                _isStructuring.value = false
            }
        }
    }

    private fun rawSaveContent(rawText: String): String {
        val escapedText = sanitizeForHtml(rawText).replace("\n", "<br/>")
        return "<p>$escapedText</p>"
    }

    /**
     * Save recording metadata and transcript segment after note is created.
     */
    private suspend fun saveRecordingAndSegments(noteId: String, rawText: String, language: String) {
        // getLastRecordingResult persists the accumulated audio here (once).
        val recResult = voiceCaptureManager.getLastRecordingResult() ?: return

        val now = System.currentTimeMillis()
        val recordingId = UUID.randomUUID().toString()

        // Create recording record
        val recording = RecordingEntity(
            id = recordingId,
            noteId = noteId,
            filename = recResult.storageKey,
            storageKey = recResult.storageKey,
            durationMs = recResult.durationMs,
            language = language,
            processingMode = "local",
            processingStatus = "completed",
            createdAt = now
        )
        recordingDao.insertRecording(recording)

        // Create transcript segment (full transcript as single segment for now)
        // Future: split into timed segments when word-level timestamps are available
        val segment = TranscriptSegmentEntity(
            id = UUID.randomUUID().toString(),
            recordingId = recordingId,
            noteId = noteId,
            speakerId = null,
            startMs = 0,
            endMs = recResult.durationMs,
            rawText = rawText,
            correctedText = null,
            language = language,
            confidence = 1.0f,
            createdAt = now,
            updatedAt = now
        )
        transcriptSegmentDao.insertSegment(segment)
    }

    fun dismiss() {
        timerJob?.cancel()
        timerJob = null
        saveJob?.cancel()
        saveJob = null
        _isStructuring.value = false
        _editedTranscript.value = null
        _elapsedMs.value = 0L
        _hasRecorded.value = false
        voiceCaptureManager.clearTranscript()
        _structuredContent.value = null
        _savedNoteId.value = null
    }

    /**
     * Auto-generate a title from the first sentence of the transcript.
     */
    private fun generateTitle(rawText: String): String {
        return rawText.split(Regex("[.!?]"))
            .firstOrNull { it.trim().isNotEmpty() }
            ?.trim()
            ?.take(80)
            ?: "Voice Note"
    }

    override fun onCleared() {
        super.onCleared()
        voiceCaptureManager.destroy()
    }
}
