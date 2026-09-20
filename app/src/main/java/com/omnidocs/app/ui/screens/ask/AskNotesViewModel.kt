package com.omnidocs.app.ui.screens.ask

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.omnidocs.app.agent.Citation
import com.omnidocs.app.agent.ResearchCoordinator
import com.omnidocs.app.ai.LlamaCppService
import com.omnidocs.app.ai.ModelDownloadManager
import com.omnidocs.app.ai.ModelPreferences
import com.omnidocs.app.ai.ThermalBudgetManager
import com.omnidocs.app.ai.ThermalStatus
import com.omnidocs.app.ai.resolveActiveModel
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/** One grounded question/answer round with verification and structured citations. */
data class GroundedAskMessage(
    val question: String,
    val answer: String,
    val citations: List<Citation>,
    val confidence: String,
    val isVerified: Boolean,
    val insufficientEvidence: Boolean,
    val durationMs: Long,
    /** True when no model was downloaded and the answer is a verbatim quote. */
    val ruleBased: Boolean = false,
    /** True when generation was cut short (token/context/thermal/stall). */
    val answerIncomplete: Boolean = false,
    /** Native stop reason name: "eos", "max_tokens", "ctx_full", ... */
    val stopReason: String = "unknown"
)

/**
 * ViewModel for the cross-note grounded RAG Q&A screen.
 * Orchestrates Workflow 3 via [ResearchCoordinator] with active model tracking.
 */
@HiltViewModel
class AskNotesViewModel @Inject constructor(
    private val researchCoordinator: ResearchCoordinator,
    private val llamaCppService: LlamaCppService,
    private val modelDownloadManager: ModelDownloadManager,
    private val modelPreferences: ModelPreferences,
    thermalBudgetManager: ThermalBudgetManager? = null
) : ViewModel() {

    private val _messages = MutableStateFlow<List<GroundedAskMessage>>(emptyList())
    val messages: StateFlow<List<GroundedAskMessage>> = _messages.asStateFlow()

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    private val _streamingText = MutableStateFlow("")
    val streamingText: StateFlow<String> = _streamingText.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    val activeModelId = modelPreferences.selectedModelId
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), ModelPreferences.DEFAULT_MODEL_ID)

    /** True when the device is thermally throttled: answers will be slow.
     * Shown as a notice so a long spinner reads as "warm phone", not "broken". */
    val isDeviceWarm: StateFlow<Boolean> =
        thermalBudgetManager?.currentThermalStatus
            ?.map { it.ordinal >= ThermalStatus.MODERATE.ordinal }
            ?.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)
            ?: MutableStateFlow(false)

    private var askJob: Job? = null

    fun ask(question: String, language: String = "en", searchQuery: String? = null) {
        if (question.isBlank() || _isLoading.value) return
        _isLoading.value = true
        _error.value = null
        _streamingText.value = ""
        val lastMessage = _messages.value.lastOrNull()
        val effectiveSearch = searchQuery ?: deriveEffectiveSearchQuery(question, lastMessage)
        val conversationHistory = _messages.value.takeLast(3).flatMap { msg ->
            listOf("user" to msg.question, "assistant" to msg.answer)
        }
        askJob = viewModelScope.launch {
            try {
                val result = researchCoordinator.executeGroundedAsk(
                    question = question,
                    language = language,
                    searchQuery = effectiveSearch,
                    conversationHistory = conversationHistory,
                    onAnswerToken = { token ->
                        _streamingText.value = _streamingText.value + token
                    }
                )
                _messages.value = _messages.value + GroundedAskMessage(
                    question = result.question,
                    answer = result.answer,
                    citations = result.citations,
                    confidence = result.confidence,
                    isVerified = result.isVerified,
                    insufficientEvidence = result.insufficientEvidence,
                    durationMs = result.durationMs,
                    ruleBased = result.ruleBased,
                    answerIncomplete = result.answerIncomplete,
                    stopReason = result.stopReason
                )
            } catch (e: CancellationException) {
                // Stopped by user; leave current messages untouched
            } catch (e: Exception) {
                _error.value = "Failed: ${e.message ?: "Unknown error"}"
            } finally {
                _isLoading.value = false
                _streamingText.value = ""
            }
        }
    }

    fun retry(message: GroundedAskMessage) {
        ask(message.question)
    }

    fun continueAnswer(message: GroundedAskMessage) {
        val continuePrompt = "Continue the following answer from where it was cut off without repeating the earlier content:\n\nQuestion: ${message.question}\n\nEarlier response so far:\n${message.answer}"
        ask(question = continuePrompt, searchQuery = message.question)
    }

    fun stop() {
        askJob?.cancel()
        askJob = null
        llamaCppService.stopGeneration()
        _isLoading.value = false
        _streamingText.value = ""
    }

    override fun onCleared() {
        askJob?.cancel()
        // The native stream holds modelMutex: without an explicit stop it
        // outlives the screen and wedges future inference until the watchdog.
        llamaCppService.stopGeneration()
        super.onCleared()
    }

    companion object {
        private val ANAPHORIC_PRONOUN_REGEX = Regex("""(?i)\b(it|its|they|them|their|this|that|these|those|she|her|he|him|his)\b""")

        internal fun deriveEffectiveSearchQuery(question: String, lastMessage: GroundedAskMessage?): String {
            if (lastMessage == null) return question
            val trimmed = question.trim()
            val words = trimmed.split(Regex("\\s+")).filter { it.isNotBlank() }
            val isFollowup = words.size <= 3 || ANAPHORIC_PRONOUN_REGEX.containsMatchIn(trimmed)
            if (!isFollowup) return question

            // 1. Extract salient keywords from previous question
            val prevQuestionWords = lastMessage.question.replace(Regex("[^a-zA-Z0-9\\s]"), " ")
                .split(Regex("\\s+"))
                .map { it.trim().lowercase() }
                .filter { it.length > 2 && it !in com.omnidocs.app.search.Tokenizer.STOP_WORDS }

            // 2. Extract salient entities from cited note titles (e.g. "Database Storage Engine")
            val citedTitles = lastMessage.citations.map { it.noteTitle.replace(Regex("(?i)\\(.*\\)"), "").trim() }
                .flatMap { it.split(Regex("\\s+")) }
                .map { it.trim().lowercase() }
                .filter { it.length > 2 && it !in com.omnidocs.app.search.Tokenizer.STOP_WORDS }

            // 3. Extract salient capitalized entities/proper nouns from the previous answer
            val answerNouns = lastMessage.answer.replace(Regex("[^a-zA-Z0-9\\s]"), " ")
                .split(Regex("\\s+"))
                .map { it.trim() }
                .filter { it.length > 2 && (it[0].isUpperCase() || it.all { ch -> ch.isUpperCase() }) }
                .map { it.lowercase() }
                .filter { it !in com.omnidocs.app.search.Tokenizer.STOP_WORDS }

            val combinedSalient = (prevQuestionWords + citedTitles + answerNouns).distinct().take(4)
            return if (combinedSalient.isNotEmpty()) {
                "${combinedSalient.joinToString(" ")} $trimmed"
            } else {
                question
            }
        }

        internal fun deriveEffectiveSearchQuery(question: String, previousQuestion: String?): String {
            if (previousQuestion.isNullOrBlank()) return question
            return deriveEffectiveSearchQuery(
                question = question,
                lastMessage = GroundedAskMessage(
                    question = previousQuestion,
                    answer = "",
                    citations = emptyList(),
                    confidence = "MEDIUM",
                    isVerified = true,
                    insufficientEvidence = false,
                    durationMs = 0L
                )
            )
        }
    }
}
