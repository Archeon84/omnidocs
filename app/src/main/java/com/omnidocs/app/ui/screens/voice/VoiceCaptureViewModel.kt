package com.omnidocs.app.ui.screens.voice

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.omnidocs.app.data.local.RecordingDao
import com.omnidocs.app.data.local.TranscriptSegmentDao
import com.omnidocs.app.data.local.entity.RecordingEntity
import com.omnidocs.app.data.local.entity.TranscriptSegmentEntity
import com.omnidocs.app.data.repository.NotesRepository
import com.omnidocs.app.util.sanitizeForHtml
import com.omnidocs.app.voice.VoiceCaptureManager
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import java.util.UUID
import javax.inject.Inject

@HiltViewModel
class VoiceCaptureViewModel @Inject constructor(
    private val voiceCaptureManager: VoiceCaptureManager,
    private val repository: NotesRepository,
    private val recordingDao: RecordingDao,
    private val transcriptSegmentDao: TranscriptSegmentDao
) : ViewModel() {

    val transcript: StateFlow<String> = voiceCaptureManager.transcript
    val isListening: StateFlow<Boolean> = voiceCaptureManager.isListening
    val error: StateFlow<String?> = voiceCaptureManager.error

    private val _isStructuring = MutableStateFlow(false)
    val isStructuring: StateFlow<Boolean> = _isStructuring.asStateFlow()

    private val _structuredContent = MutableStateFlow<String?>(null)
    val structuredContent: StateFlow<String?> = _structuredContent.asStateFlow()

    private val _savedNoteId = MutableStateFlow<String?>(null)
    val savedNoteId: StateFlow<String?> = _savedNoteId.asStateFlow()

    fun startListening(languageCode: String = "en") {
        // Do NOT clear the transcript here: "Record More" must append to the
        // previous session's text. The manager starts fresh on its own when
        // nothing was recorded yet, and dismiss() resets the whole session.
        voiceCaptureManager.startListening(languageCode)
    }

    fun stopListening() {
        voiceCaptureManager.stopListening()
    }

    fun confirmAndSave(language: String = "en") {
        val rawText = transcript.value
        if (rawText.isBlank()) return

        _isStructuring.value = true
        viewModelScope.launch {
            try {
                val structured = voiceCaptureManager.structureTranscript(rawText, language)
                _structuredContent.value = structured

                val title = generateTitle(rawText)

                val note = repository.createNote(
                    title = title,
                    content = structured,
                    plainText = rawText,
                    language = language
                )

                // Save recording and transcript segment records
                saveRecordingAndSegments(note.id, rawText, language)

                _savedNoteId.value = note.id
            } catch (e: Exception) {
                // If structuring fails, save raw text with HTML-escaped content
                val title = generateTitle(rawText)
                val escapedText = sanitizeForHtml(rawText).replace("\n", "<br/>")

                val note = repository.createNote(
                    title = title,
                    content = "<p>$escapedText</p>",
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
