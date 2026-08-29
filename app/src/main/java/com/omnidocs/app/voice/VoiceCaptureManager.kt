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
import java.io.ByteArrayOutputStream
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "VoiceCaptureManager"

@Singleton
class VoiceCaptureManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val llamaCppService: LlamaCppService,
    private val modelDownloadManager: ModelDownloadManager,
    private val modelPreferences: ModelPreferences,
    private val sttEngineFactory: SttEngineFactory,
    private val recordingStorage: RecordingStorage
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var activeEngine: SttEngine? = null
    private var useSystemRecognizer = false
    private var recognizer: SpeechRecognizer? = null

    // Audio capture for recording storage. PCM bytes from the active engine
    // accumulate here across Record More sessions and are persisted lazily when
    // the note is saved (see getLastRecordingResult). Synchronized because the
    // system recognizer delivers onBufferReceived on a binder thread.
    private val audioBuffer = ByteArrayOutputStream()
    private var recordingStartTime = 0L
    // Transcript accumulated across Record More sessions ("" until first stop).
    private var sessionText = ""
    // Bumped on every start/stop/cancel so a stale async resolveEngine() result
    // from an earlier tap can never start/stop the wrong engine instance.
    @Volatile private var sessionGeneration = 0
    // Raw final text of the current system-recognizer capture (no session prefix),
    // delivered asynchronously by onResults. stopListening() polls this so the
    // accumulated session ends with the final words instead of the last partial.
    // "" means onError fired with no text; null means no result delivered yet.
    @Volatile private var pendingSystemFinal: String? = null

    private val _transcript = MutableStateFlow("")
    val transcript: StateFlow<String> = _transcript.asStateFlow()

    private val _isListening = MutableStateFlow(false)
    val isListening: StateFlow<Boolean> = _isListening.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    private val _engineType = MutableStateFlow("system")
    val engineType: StateFlow<String> = _engineType.asStateFlow()

    private val _liveAudioLevel = MutableStateFlow(0f)
    val liveAudioLevel: StateFlow<Float> = _liveAudioLevel.asStateFlow()

    /** Result of a recording session, available after stopListening(). */
    data class RecordingResult(
        val storageKey: String,
        val durationMs: Long,
        val rawText: String
    )

    private var lastRecordingResult: RecordingResult? = null

    /**
     * Resolve the recording result for this capture session. The audio is
     * persisted here (once) rather than in stopListening() so that Record More
     * segments accumulate into a single audio file instead of leaking one
     * partial file per stop. Called from the save path (coroutine context).
     */
    suspend fun getLastRecordingResult(): RecordingResult? {
        if (lastRecordingResult == null && sessionText.isNotBlank()) {
            lastRecordingResult = withContext(Dispatchers.IO) {
                val audioData = synchronized(audioBuffer) { audioBuffer.toByteArray() }
                if (audioData.isEmpty()) return@withContext null
                val durationMs = System.currentTimeMillis() - recordingStartTime
                val storageKey = recordingStorage.saveAudio(audioData)
                Log.d(TAG, "Recording saved: $storageKey, ${durationMs}ms, ${audioData.size} bytes")
                RecordingResult(storageKey = storageKey, durationMs = durationMs, rawText = sessionText)
            }
        }
        return lastRecordingResult
    }

    private suspend fun getActiveModel(): ModelInfo? {
        return resolveActiveModel(modelPreferences, modelDownloadManager)
    }

    /**
     * Resolve which engine to use and initialize it.
     */
    private suspend fun resolveEngine(): Boolean {
        // Native model init (initialize()) can take seconds; never run it on the
        // main thread or the UI janks/ANRs.
        val sherpaEngine = withContext(Dispatchers.Default) { sttEngineFactory.getEngine() }
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

        _error.value = "No speech recognition available. Go to Settings > Speech Recognition and download + select a model."
        return false
    }

    fun startListening(languageCode: String = "en") {
        _error.value = null
        lastRecordingResult = null

        // "Record More": a fresh capture only when nothing was recorded yet.
        // Otherwise keep the accumulated transcript + audio so segments append.
        val isFresh = sessionText.isBlank()
        if (isFresh) {
            synchronized(audioBuffer) { audioBuffer.reset() }
            recordingStartTime = System.currentTimeMillis()
            _transcript.value = ""
        } else {
            _transcript.value = sessionText
        }

        val session = ++sessionGeneration
        // Each capture (fresh or Record More) starts with no delivered final.
        pendingSystemFinal = null
        scope.launch {
            val ready = resolveEngine()
            // A newer start/stop already happened while the engine was resolving.
            if (!ready || session != sessionGeneration) {
                _isListening.value = false
                return@launch
            }

            _isListening.value = true

            if (!useSystemRecognizer && activeEngine != null) {
                activeEngine?.startListening(
                    languageCode,
                    partialCallback = { partial ->
                        // Show accumulated text + current session partial on main thread
                        scope.launch(Dispatchers.Main) {
                            _transcript.value = if (sessionText.isBlank()) partial
                                else "$sessionText $partial"
                        }
                    },
                    onAudio = { bytes ->
                        synchronized(audioBuffer) { audioBuffer.write(bytes) }
                        if (bytes.isNotEmpty()) {
                            var sum = 0.0
                            val sampleCount = bytes.size / 2
                            for (i in 0 until sampleCount) {
                                val sample = (bytes[i * 2].toInt() and 0xFF) or (bytes[i * 2 + 1].toInt() shl 8)
                                val normalized = sample.toShort().toFloat() / 32768f
                                sum += normalized * normalized
                            }
                            val rms = kotlin.math.sqrt(sum / sampleCount).toFloat()
                            _liveAudioLevel.value = (rms * 3.5f).coerceIn(0f, 1f)
                        }
                    }
                )
            } else {
                startSystemRecognizer(languageCode, session)
            }
        }
    }

    private fun startSystemRecognizer(languageCode: String, session: Int) {
        recognizer?.destroy()
        recognizer = SpeechRecognizer.createSpeechRecognizer(context).apply {
            setRecognitionListener(object : RecognitionListener {
                // Ignore callbacks from a stale recognizer instance after a newer
                // start/stop replaced this session.
                private fun isCurrentSession() = session == sessionGeneration

                override fun onReadyForSpeech(params: Bundle?) {
                    if (!isCurrentSession()) return
                    _isListening.value = true
                    _error.value = null
                }

                override fun onBeginningOfSpeech() {}
                override fun onRmsChanged(rmsdB: Float) {
                    if (!isCurrentSession()) return
                    val normalized = ((rmsdB + 2f) / 12f).coerceIn(0f, 1f)
                    _liveAudioLevel.value = normalized
                }
                override fun onBufferReceived(buffer: ByteArray?) {
                    // Persist the mic audio the system recognizer captured so the
                    // saved recording is not empty.
                    buffer?.let { bytes ->
                        synchronized(audioBuffer) { audioBuffer.write(bytes) }
                    }
                }

                override fun onEndOfSpeech() {
                    if (!isCurrentSession()) return
                    _isListening.value = false
                }

                override fun onError(error: Int) {
                    if (!isCurrentSession()) return
                    // Signal stopListening()'s poll that no final result is coming,
                    // so it stops waiting immediately instead of timing out.
                    pendingSystemFinal = pendingSystemFinal ?: ""
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
                    if (!isCurrentSession()) return
                    // Only the first final delivery for this capture is applied; a
                    // duplicate onResults would otherwise re-prefix the session.
                    if (pendingSystemFinal != null) return
                    val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    val fullText = matches?.firstOrNull() ?: ""
                    pendingSystemFinal = fullText
                    if (fullText.isNotBlank()) {
                        // Show accumulated session + this session's final (prefix once).
                        _transcript.value = listOf(sessionText, fullText)
                            .filter { it.isNotBlank() }
                            .joinToString(" ")
                    }
                }

                override fun onPartialResults(partialResults: Bundle?) {
                    if (!isCurrentSession()) return
                    val matches = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    val partial = matches?.firstOrNull() ?: ""
                    if (partial.isNotBlank()) {
                        // Show accumulated + current partial (don't clobber prior sessions)
                        _transcript.value = if (sessionText.isBlank()) partial
                            else "$sessionText $partial"
                    }
                }
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
        // Invalidate any in-flight resolveEngine from a tap that raced stop.
        sessionGeneration++

        var finalText = ""
        if (!useSystemRecognizer && activeEngine != null) {
            val result = activeEngine?.stopListening()
            if (result != null && result.text.isNotBlank()) {
                finalText = result.text
            }
        } else {
            recognizer?.stopListening()
            // The final onResults arrives asynchronously after stopListening()
            // returns; poll for it so the session ends with the final words, not
            // the last partial. onResults sets the raw text, onError sets "" to
            // unblock immediately -- so this resolves in milliseconds normally.
            val deadline = System.currentTimeMillis() + 2500
            while (pendingSystemFinal == null && System.currentTimeMillis() < deadline) {
                Thread.sleep(50)
            }
            val newFinal = pendingSystemFinal
            if (newFinal.isNullOrBlank()) {
                // No final delivered (e.g. no-match error): keep the last partial
                // already shown, stripped of the session prefix so it is not doubled.
                val prefix = if (sessionText.isBlank()) "" else "$sessionText "
                finalText = if (_transcript.value.startsWith(prefix))
                    _transcript.value.removePrefix(prefix) else _transcript.value
            } else {
                finalText = newFinal
            }
        }
        _isListening.value = false
        _liveAudioLevel.value = 0f

        if (finalText.isNotBlank()) {
            // Accumulate across Record More sessions; audio persistence is
            // deferred to getLastRecordingResult() so it happens once at save.
            sessionText = if (sessionText.isBlank()) finalText else "$sessionText $finalText"
            _transcript.value = sessionText
        }
    }

    fun clearTranscript() {
        // Full reset: a new overlay session starts from scratch (no accumulation).
        // Bump the session so in-flight recognizer callbacks can't repopulate the
        // reset transcript.
        sessionGeneration++
        pendingSystemFinal = null
        sessionText = ""
        synchronized(audioBuffer) { audioBuffer.reset() }
        lastRecordingResult = null
        _transcript.value = ""
        _liveAudioLevel.value = 0f
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
        sessionGeneration++
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
