package com.omnidocs.app.ui.screens.localchat

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.omnidocs.app.ai.LlamaCppService
import com.omnidocs.app.ai.ModelDownloadManager
import com.omnidocs.app.ai.ModelInfo
import com.omnidocs.app.ai.ModelPreferences
import com.omnidocs.app.ai.PromptBuilder
import com.omnidocs.app.ai.StopStringFilter
import com.omnidocs.app.ai.ThinkingStreamFilter
import com.omnidocs.app.ai.ThermalBudgetManager
import com.omnidocs.app.ai.ThermalStatus
import com.omnidocs.app.ai.resolveActiveModel
import com.omnidocs.app.data.local.BUILTIN_PERSONAS
import com.omnidocs.app.data.local.ChatPersona
import com.omnidocs.app.data.local.LocalChatMessage
import com.omnidocs.app.data.local.LocalChatSession
import com.omnidocs.app.data.local.LocalChatStorage
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.UUID
import javax.inject.Inject

/**
 * ViewModel powering the dedicated, airtight offline Local LLM chat experience.
 * Only interacts with local on-device neural runtimes (Google LiteRT-LM / ARM NEON)
 * with zero cloud fallback or telemetry.
 */
@HiltViewModel
class LocalChatViewModel @Inject constructor(
    private val llamaCppService: LlamaCppService,
    private val modelDownloadManager: ModelDownloadManager,
    private val modelPreferences: ModelPreferences,
    private val localChatStorage: LocalChatStorage,
    thermalBudgetManager: ThermalBudgetManager? = null
) : ViewModel() {

    val sessions: StateFlow<List<LocalChatSession>> = localChatStorage.sessions

    private val _currentSession = MutableStateFlow<LocalChatSession?>(null)
    val currentSession: StateFlow<LocalChatSession?> = _currentSession.asStateFlow()

    private val _messages = MutableStateFlow<List<LocalChatMessage>>(emptyList())
    val messages: StateFlow<List<LocalChatMessage>> = _messages.asStateFlow()

    private val _isGenerating = MutableStateFlow(false)
    val isGenerating: StateFlow<Boolean> = _isGenerating.asStateFlow()

    private val _streamingText = MutableStateFlow("")
    val streamingText: StateFlow<String> = _streamingText.asStateFlow()

    private val _tokensGenerated = MutableStateFlow(0)
    val tokensGenerated: StateFlow<Int> = _tokensGenerated.asStateFlow()

    private val _tokensPerSecond = MutableStateFlow(0.0)
    val tokensPerSecond: StateFlow<Double> = _tokensPerSecond.asStateFlow()

    private val _generationDurationMs = MutableStateFlow(0L)
    val generationDurationMs: StateFlow<Long> = _generationDurationMs.asStateFlow()

    private val _activeModel = MutableStateFlow<ModelInfo?>(null)
    val activeModel: StateFlow<ModelInfo?> = _activeModel.asStateFlow()

    private val _snackbarEvent = MutableSharedFlow<String>()
    val snackbarEvent: SharedFlow<String> = _snackbarEvent.asSharedFlow()

    val isDeviceWarm: StateFlow<Boolean> =
        thermalBudgetManager?.currentThermalStatus
            ?.map { it.ordinal >= ThermalStatus.MODERATE.ordinal }
            ?.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)
            ?: MutableStateFlow(false)

    private var activeJob: Job? = null

    init {
        refreshActiveModel()
        initializeChatSession()
    }

    fun refreshActiveModel() {
        viewModelScope.launch {
            _activeModel.value = resolveActiveModel(modelPreferences, modelDownloadManager)
        }
    }

    private fun initializeChatSession() {
        viewModelScope.launch {
            val list = localChatStorage.sessions.value
            if (list.isNotEmpty()) {
                selectSession(list.first().id)
            } else {
                createNewSession()
            }
        }
    }

    fun createNewSession(persona: ChatPersona = BUILTIN_PERSONAS.first()) {
        if (_isGenerating.value) stopGeneration()
        viewModelScope.launch {
            val model = resolveActiveModel(modelPreferences, modelDownloadManager)
            val session = localChatStorage.createSession(
                title = "New Chat",
                persona = persona,
                modelId = model?.id ?: ""
            )
            _currentSession.value = session
            _messages.value = emptyList()
            _streamingText.value = ""
        }
    }

    fun selectSession(sessionId: String) {
        if (_currentSession.value?.id == sessionId) return
        if (_isGenerating.value) stopGeneration()
        viewModelScope.launch {
            val session = localChatStorage.sessions.value.find { it.id == sessionId }
            if (session != null) {
                _currentSession.value = session
                _messages.value = localChatStorage.loadMessages(sessionId)
                _streamingText.value = ""
            }
        }
    }

    fun renameSession(sessionId: String, newTitle: String) {
        if (newTitle.isBlank()) return
        viewModelScope.launch {
            localChatStorage.renameSession(sessionId, newTitle.trim())
            if (_currentSession.value?.id == sessionId) {
                _currentSession.value = _currentSession.value?.copy(title = newTitle.trim())
            }
        }
    }

    fun deleteSession(sessionId: String) {
        viewModelScope.launch {
            localChatStorage.deleteSession(sessionId)
            if (_currentSession.value?.id == sessionId) {
                val remaining = localChatStorage.sessions.value.filter { it.id != sessionId }
                if (remaining.isNotEmpty()) {
                    selectSession(remaining.first().id)
                } else {
                    createNewSession()
                }
            }
        }
    }

    fun clearCurrentSession() {
        val session = _currentSession.value ?: return
        if (_isGenerating.value) stopGeneration()
        viewModelScope.launch {
            _messages.value = emptyList()
            _streamingText.value = ""
            localChatStorage.saveMessages(session.id, emptyList())
        }
    }

    fun updatePersona(persona: ChatPersona) {
        val session = _currentSession.value ?: return
        val updated = session.copy(
            personaId = persona.id,
            systemPrompt = persona.systemPrompt,
            updatedAt = System.currentTimeMillis()
        )
        _currentSession.value = updated
        viewModelScope.launch {
            localChatStorage.updateSession(updated)
        }
    }

    fun updateCustomSystemPrompt(prompt: String) {
        val session = _currentSession.value ?: return
        val updated = session.copy(
            systemPrompt = prompt,
            updatedAt = System.currentTimeMillis()
        )
        _currentSession.value = updated
        viewModelScope.launch {
            localChatStorage.updateSession(updated)
        }
    }

    fun updateInferenceParameters(temperature: Float, topP: Float, maxTokens: Int) {
        val session = _currentSession.value ?: return
        val updated = session.copy(
            temperature = temperature,
            topP = topP,
            maxTokens = maxTokens,
            updatedAt = System.currentTimeMillis()
        )
        _currentSession.value = updated
        viewModelScope.launch {
            localChatStorage.updateSession(updated)
        }
    }

    fun sendMessage(promptText: String) {
        val trimmed = promptText.trim()
        if (trimmed.isBlank() || _isGenerating.value) return

        val session = _currentSession.value ?: run {
            createNewSession()
            return
        }

        val userMessage = LocalChatMessage(
            id = UUID.randomUUID().toString(),
            sessionId = session.id,
            role = "user",
            content = trimmed,
            timestamp = System.currentTimeMillis()
        )

        val updatedList = _messages.value + userMessage
        _messages.value = updatedList
        _isGenerating.value = true
        _streamingText.value = ""
        _tokensGenerated.value = 0
        _tokensPerSecond.value = 0.0
        _generationDurationMs.value = 0L

        executeInference(session, updatedList)
    }

    fun regenerateLast() {
        if (_isGenerating.value) return
        val current = _messages.value.toMutableList()
        if (current.isEmpty()) return

        // If last message is assistant, remove it and re-generate
        if (current.last().role == "assistant") {
            current.removeAt(current.size - 1)
        }
        if (current.isEmpty()) return

        val session = _currentSession.value ?: return
        _messages.value = current
        _isGenerating.value = true
        _streamingText.value = ""
        _tokensGenerated.value = 0
        _tokensPerSecond.value = 0.0
        _generationDurationMs.value = 0L

        executeInference(session, current)
    }

    fun editMessageAndResend(messageId: String, newContent: String) {
        if (_isGenerating.value) stopGeneration()
        val current = _messages.value
        val index = current.indexOfFirst { it.id == messageId }
        if (index == -1) return

        val session = _currentSession.value ?: return
        // Truncate everything after this message, update this message, and generate
        val truncated = current.take(index).toMutableList()
        val editedUserMsg = current[index].copy(content = newContent.trim(), timestamp = System.currentTimeMillis())
        truncated.add(editedUserMsg)

        _messages.value = truncated
        _isGenerating.value = true
        _streamingText.value = ""
        _tokensGenerated.value = 0
        _tokensPerSecond.value = 0.0
        _generationDurationMs.value = 0L

        executeInference(session, truncated)
    }

    fun deleteMessage(messageId: String) {
        val session = _currentSession.value ?: return
        val updated = _messages.value.filter { it.id != messageId }
        _messages.value = updated
        viewModelScope.launch {
            localChatStorage.saveMessages(session.id, updated)
        }
    }

    private fun executeInference(session: LocalChatSession, currentHistory: List<LocalChatMessage>) {
        activeJob?.cancel()
        activeJob = viewModelScope.launch(Dispatchers.IO) {
            val startTime = System.currentTimeMillis()
            var tokenCount = 0

            val model = resolveActiveModel(modelPreferences, modelDownloadManager)
            _activeModel.value = model

            if (model == null) {
                withContext(Dispatchers.Main) {
                    val errorMsg = LocalChatMessage(
                        sessionId = session.id,
                        role = "assistant",
                        content = "⚠️ No local AI model found.\n\nPlease navigate to Settings -> AI Models to download an on-device model (e.g. Gemma 4 E2B). This chat operates strictly offline and will never connect to cloud services.",
                        timestamp = System.currentTimeMillis(),
                        isError = true
                    )
                    val finalList = currentHistory + errorMsg
                    _messages.value = finalList
                    _isGenerating.value = false
                    _streamingText.value = ""
                    localChatStorage.saveMessages(session.id, finalList)
                }
                return@launch
            }

            // Build multi-turn prompt from history
            val conversationPairs = currentHistory.map { it.role to it.content }
            val formattedPrompt = PromptBuilder.buildMultiTurnPrompt(
                format = model.promptFormat,
                systemPrompt = session.systemPrompt,
                messages = conversationPairs,
                model = model
            )

            val thinkingFilter = ThinkingStreamFilter()
            val stopFilter = StopStringFilter()
            val responseBuilder = StringBuilder()

            try {
                llamaCppService.generateFlow(
                    prompt = formattedPrompt,
                    maxTokens = session.maxTokens,
                    modelId = model.id
                ).collect { rawToken ->
                    val filtered = thinkingFilter.feed(rawToken)
                    if (filtered.isNotEmpty()) {
                        val releasable = stopFilter.feed(filtered)
                        if (releasable.isNotEmpty()) {
                            responseBuilder.append(releasable)
                            tokenCount++
                            val elapsed = System.currentTimeMillis() - startTime
                            val speed = if (elapsed > 0) (tokenCount.toDouble() / elapsed) * 1000.0 else 0.0

                            withContext(Dispatchers.Main) {
                                _streamingText.value = responseBuilder.toString()
                                _tokensGenerated.value = tokenCount
                                _tokensPerSecond.value = speed
                                _generationDurationMs.value = elapsed
                            }
                        }
                    }
                    if (stopFilter.stopped) {
                        llamaCppService.stopGeneration()
                    }
                }

                // Flush remaining tokens
                val thinkFlush = thinkingFilter.flush()
                if (thinkFlush.isNotEmpty()) {
                    val releasable = stopFilter.feed(thinkFlush)
                    if (releasable.isNotEmpty()) responseBuilder.append(releasable)
                }
                val stopFlush = stopFilter.flush()
                if (stopFlush.isNotEmpty()) {
                    responseBuilder.append(stopFlush)
                }

                val totalDuration = System.currentTimeMillis() - startTime
                val finalSpeed = if (totalDuration > 0) (tokenCount.toDouble() / totalDuration) * 1000.0 else 0.0
                val finalContent = responseBuilder.toString().trim()

                withContext(Dispatchers.Main) {
                    val assistantMsg = LocalChatMessage(
                        sessionId = session.id,
                        role = "assistant",
                        content = if (finalContent.isNotBlank()) finalContent else "No response generated.",
                        timestamp = System.currentTimeMillis(),
                        durationMs = totalDuration,
                        tokenCount = tokenCount,
                        tokPerSec = finalSpeed,
                        modelName = model.name
                    )
                    val finalList = currentHistory + assistantMsg
                    _messages.value = finalList
                    localChatStorage.saveMessages(session.id, finalList)
                }
            } catch (e: CancellationException) {
                // Generation cancelled by user stop
                val totalDuration = System.currentTimeMillis() - startTime
                val partialContent = responseBuilder.toString().trim()
                if (partialContent.isNotBlank()) {
                    withContext(Dispatchers.Main) {
                        val assistantMsg = LocalChatMessage(
                            sessionId = session.id,
                            role = "assistant",
                            content = partialContent,
                            timestamp = System.currentTimeMillis(),
                            durationMs = totalDuration,
                            tokenCount = tokenCount,
                            tokPerSec = if (totalDuration > 0) (tokenCount.toDouble() / totalDuration) * 1000.0 else 0.0,
                            modelName = model.name
                        )
                        val finalList = currentHistory + assistantMsg
                        _messages.value = finalList
                        localChatStorage.saveMessages(session.id, finalList)
                    }
                }
            } catch (e: Throwable) {
                withContext(Dispatchers.Main) {
                    val errorMsg = LocalChatMessage(
                        sessionId = session.id,
                        role = "assistant",
                        content = "Generation error: ${e.message ?: "Unknown native error"}",
                        timestamp = System.currentTimeMillis(),
                        isError = true
                    )
                    val finalList = currentHistory + errorMsg
                    _messages.value = finalList
                    localChatStorage.saveMessages(session.id, finalList)
                }
            } finally {
                withContext(Dispatchers.Main) {
                    _isGenerating.value = false
                    _streamingText.value = ""
                }
            }
        }
    }

    fun stopGeneration() {
        activeJob?.cancel()
        activeJob = null
        llamaCppService.stopGeneration()
        _isGenerating.value = false
    }

    fun copyToClipboard(context: android.content.Context, text: String) {
        val clipboard = context.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as? android.content.ClipboardManager
        val clip = android.content.ClipData.newPlainText("OmniDocs Local Chat", text)
        clipboard?.setPrimaryClip(clip)
        viewModelScope.launch {
            _snackbarEvent.emit("Copied to clipboard")
        }
    }

    override fun onCleared() {
        stopGeneration()
        super.onCleared()
    }
}
