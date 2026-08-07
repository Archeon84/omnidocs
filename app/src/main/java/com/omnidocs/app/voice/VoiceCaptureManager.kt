package com.omnidocs.app.voice

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log
import com.omnidocs.app.ai.AiOutputProcessor
import com.omnidocs.app.ai.LlamaCppService
import com.omnidocs.app.ai.ModelDownloadManager
import com.omnidocs.app.ai.ModelInfo
import com.omnidocs.app.ai.ModelPreferences
import com.omnidocs.app.ai.PromptBuilder
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "VoiceCaptureManager"

@Singleton
class VoiceCaptureManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val llamaCppService: LlamaCppService,
    private val modelDownloadManager: ModelDownloadManager,
    private val modelPreferences: ModelPreferences
) {
    private var recognizer: SpeechRecognizer? = null

    private val _transcript = MutableStateFlow("")
    val transcript: StateFlow<String> = _transcript.asStateFlow()

    private val _isListening = MutableStateFlow(false)
    val isListening: StateFlow<Boolean> = _isListening.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    private suspend fun getActiveModel(): ModelInfo? {
        val selectedId = modelPreferences.selectedModelId.first()
        val downloaded = modelDownloadManager.getDownloadedModels()
        return downloaded.find { it.id == selectedId && it.isDownloaded }
            ?: downloaded.firstOrNull { it.isDownloaded }
    }

    fun startListening(languageCode: String = "en") {
        if (!SpeechRecognizer.isRecognitionAvailable(context)) {
            _error.value = "Speech recognition not available on this device"
            return
        }

        recognizer?.destroy()
        recognizer = SpeechRecognizer.createSpeechRecognizer(context).apply {
            setRecognitionListener(object : RecognitionListener {
                override fun onReadyForSpeech(params: Bundle?) {
                    _isListening.value = true
                    _error.value = null
                }

                override fun onBeginningOfSpeech() {}
                override fun onRmsChanged(rmsdB: Float) {}
                override fun onBufferReceived(buffer: ByteArray?) {}

                override fun onEndOfSpeech() {
                    _isListening.value = false
                }

                override fun onError(error: Int) {
                    _isListening.value = false
                    _error.value = when (error) {
                        SpeechRecognizer.ERROR_NO_MATCH -> "No speech detected. Try again."
                        SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "Speech timeout. Try again."
                        SpeechRecognizer.ERROR_AUDIO -> "Audio recording error."
                        SpeechRecognizer.ERROR_CLIENT -> "Client error. Try again."
                        SpeechRecognizer.ERROR_NETWORK -> "Network error."
                        SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "Network timeout."
                        else -> "Recognition error ($error)"
                    }
                }

                override fun onResults(results: Bundle?) {
                    val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    val fullText = matches?.firstOrNull() ?: ""
                    if (fullText.isNotBlank()) {
                        _transcript.value = if (_transcript.value.isEmpty()) {
                            fullText
                        } else {
                            "${_transcript.value} $fullText"
                        }
                    }
                }

                override fun onPartialResults(partialResults: Bundle?) {
                    val matches = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    val partial = matches?.firstOrNull() ?: ""
                    if (partial.isNotBlank()) {
                        _transcript.value = partial
                    }
                }

                override fun onEvent(eventType: Int, params: Bundle?) {}
            })
        }

        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, languageCode)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
        }

        recognizer?.startListening(intent)
    }

    fun stopListening() {
        recognizer?.stopListening()
        _isListening.value = false
    }

    fun clearTranscript() {
        _transcript.value = ""
        _error.value = null
    }

    fun clearError() {
        _error.value = null
    }

    /**
     * Send the raw transcript to the LLM for structuring into formatted HTML.
     * Returns structured HTML with headings, bullets, and action items.
     */
    suspend fun structureTranscript(rawText: String, language: String): String = withContext(Dispatchers.IO) {
        val model = getActiveModel() ?: return@withContext "<p>$rawText</p>"
        val langInstruction = if (language != "en") "\nRespond in $language language." else ""

        val systemPrompt = "You are a note structuring assistant. Convert raw speech transcript into " +
            "well-structured HTML notes. Add headings (h2), bullet lists (ul/li), action items with " +
            "checkboxes (input type=checkbox), and bold key terms (strong). Preserve all meaning. " +
            "Return only HTML, no markdown code blocks.$langInstruction"

        val userPrompt = "Structure this speech transcript into a well-organized HTML note:\n\n$rawText"

        val prompt = PromptBuilder.buildPrompt(model.promptFormat, systemPrompt, userPrompt)
        val result = llamaCppService.generate(prompt, maxTokens = 1500)
        val processed = result?.let { AiOutputProcessor.process(it) }

        // If LLM failed or returned empty, return the raw text as a paragraph
        if (processed.isNullOrBlank()) {
            "<p>${rawText.replace("\n", "<br/>")}</p>"
        } else {
            // Strip any markdown code block wrappers the LLM might have added
            processed.replace(Regex("```html\\s*"), "").replace(Regex("```\\s*"), "").trim()
        }
    }

    fun destroy() {
        recognizer?.destroy()
        recognizer = null
    }
}