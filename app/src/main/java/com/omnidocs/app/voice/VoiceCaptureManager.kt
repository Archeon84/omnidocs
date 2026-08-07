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
import com.omnidocs.app.ai.resolveActiveModel
import com.omnidocs.app.stt.SttEngine
import com.omnidocs.app.stt.SttEngineFactory
import com.omnidocs.app.util.HtmlSanitizer
import com.omnidocs.app.util.sanitizeForHtml
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "VoiceCaptureManager"

@Singleton
class VoiceCaptureManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val llamaCppService: LlamaCppService,
    private val modelDownloadManager: ModelDownloadManager,
    private val modelPreferences: ModelPreferences,
    private val sttEngineFactory: SttEngineFactory
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var activeEngine: SttEngine? = null
    private var useSystemRecognizer = false
    private var recognizer: SpeechRecognizer? = null

    private val _transcript = MutableStateFlow("")
    val transcript: StateFlow<String> = _transcript.asStateFlow()

    private val _isListening = MutableStateFlow(false)
    val isListening: StateFlow<Boolean> = _isListening.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    private val _engineType = MutableStateFlow("system")
    val engineType: StateFlow<String> = _engineType.asStateFlow()

    private suspend fun getActiveModel(): ModelInfo? {
        return resolveActiveModel(modelPreferences, modelDownloadManager)
    }

    /**
     * Resolve which engine to use and initialize it.
     */
    private suspend fun resolveEngine(): Boolean {
        val sherpaEngine = sttEngineFactory.getEngine()
        if (sherpaEngine != null) {
            activeEngine = sherpaEngine
            useSystemRecognizer = false
            _engineType.value = "offline"
            return true
        }

        // Fall back to system SpeechRecognizer
        if (SpeechRecognizer.isRecognitionAvailable(context)) {
            useSystemRecognizer = true
            _engineType.value = "system"
            return true
        }

        _error.value = "No speech recognition available. Download a model in Settings."
        return false
    }

    fun startListening(languageCode: String = "en") {
        _isListening.value = true
        _error.value = null
        _transcript.value = ""

        scope.launch {
            val ready = resolveEngine()
            if (!ready) {
                _isListening.value = false
                return@launch
            }

            if (!useSystemRecognizer && activeEngine != null) {
                activeEngine?.startListening(languageCode) { partial ->
                    // Partial results update the UI preview
                }
            } else {
                startSystemRecognizer(languageCode)
            }
        }
    }

    private fun startSystemRecognizer(languageCode: String) {
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
                        SpeechRecognizer.ERROR_NETWORK -> "Network required for system recognizer. Download an offline model in Settings."
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

                override fun onPartialResults(partialResults: Bundle?) {}
                override fun onEvent(eventType: Int, params: Bundle?) {}
            })
        }

        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, languageCode)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3)
        }

        recognizer?.startListening(intent)
    }

    fun stopListening() {
        if (!useSystemRecognizer && activeEngine != null) {
            val result = activeEngine?.stopListening()
            if (result != null && result.text.isNotBlank()) {
                _transcript.value = if (_transcript.value.isEmpty()) {
                    result.text
                } else {
                    "${_transcript.value} ${result.text}"
                }
            }
        } else {
            recognizer?.stopListening()
        }
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
     * Returns sanitized HTML with headings, bullets, and action items.
     */
    suspend fun structureTranscript(rawText: String, language: String): String = withContext(Dispatchers.IO) {
        val model = getActiveModel() ?: return@withContext "<p>${sanitizeForHtml(rawText)}</p>"
        val langInstruction = if (language != "en") "\nRespond in $language language." else ""

        val systemPrompt = "You are a note structuring assistant. Convert raw speech transcript into " +
            "well-structured HTML notes. Add headings (h2), bullet lists (ul/li), action items with " +
            "checkboxes (input type=checkbox disabled), and bold key terms (strong). Preserve all meaning. " +
            "Return only HTML, no markdown code blocks.$langInstruction"

        val userPrompt = "Structure this speech transcript into a well-organized HTML note:\n\n" +
            "<transcript>\n$rawText\n</transcript>"

        val prompt = PromptBuilder.buildPrompt(model.promptFormat, systemPrompt, userPrompt)
        val result = llamaCppService.generate(prompt, maxTokens = 1500)
        val processed = result?.let { AiOutputProcessor.process(it) }

        // If LLM failed or returned empty, return the raw text as a paragraph
        if (processed.isNullOrBlank()) {
            "<p>${sanitizeForHtml(rawText)}</p>"
        } else {
            // Strip any markdown code block wrappers the LLM might have added
            val stripped = processed
                .replace(Regex("```html\\s*"), "")
                .replace(Regex("```\\s*"), "")
                .trim()
            // Sanitize HTML to prevent XSS -- allow only safe tags
            HtmlSanitizer.sanitize(stripped)
        }
    }

    fun destroy() {
        if (!useSystemRecognizer) {
            activeEngine?.release()
            activeEngine = null
        } else {
            recognizer?.stopListening()
            _isListening.value = false
            recognizer?.destroy()
            recognizer = null
        }
    }
}
