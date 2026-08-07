package com.omnidocs.app.ui.screens.voice

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.omnidocs.app.data.repository.NotesRepository
import com.omnidocs.app.util.sanitizeForHtml
import com.omnidocs.app.voice.VoiceCaptureManager
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class VoiceCaptureViewModel @Inject constructor(
    private val voiceCaptureManager: VoiceCaptureManager,
    private val repository: NotesRepository
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
        voiceCaptureManager.clearTranscript()
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
                _savedNoteId.value = note.id
            } finally {
                _isStructuring.value = false
            }
        }
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
