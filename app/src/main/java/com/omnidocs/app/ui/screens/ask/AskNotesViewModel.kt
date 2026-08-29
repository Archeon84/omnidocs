package com.omnidocs.app.ui.screens.ask

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.omnidocs.app.agent.Citation
import com.omnidocs.app.agent.ResearchCoordinator
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
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
    val durationMs: Long
)

/**
 * ViewModel for the cross-note grounded RAG Q&A screen.
 * Orchestrates Workflow 3 via [ResearchCoordinator].
 */
@HiltViewModel
class AskNotesViewModel @Inject constructor(
    private val researchCoordinator: ResearchCoordinator
) : ViewModel() {

    private val _messages = MutableStateFlow<List<GroundedAskMessage>>(emptyList())
    val messages: StateFlow<List<GroundedAskMessage>> = _messages.asStateFlow()

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    fun ask(question: String, language: String = "en") {
        if (question.isBlank() || _isLoading.value) return
        _isLoading.value = true
        _error.value = null
        viewModelScope.launch {
            try {
                val result = researchCoordinator.executeGroundedAsk(question, language)
                _messages.value = _messages.value + GroundedAskMessage(
                    question = result.question,
                    answer = result.answer,
                    citations = result.citations,
                    confidence = result.confidence,
                    isVerified = result.isVerified,
                    insufficientEvidence = result.insufficientEvidence,
                    durationMs = result.durationMs
                )
            } catch (e: Exception) {
                _error.value = "Failed: ${e.message ?: "Unknown error"}"
            } finally {
                _isLoading.value = false
            }
        }
    }
}
