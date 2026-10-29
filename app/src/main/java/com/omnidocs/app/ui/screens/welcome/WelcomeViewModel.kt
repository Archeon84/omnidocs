package com.omnidocs.app.ui.screens.welcome

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.omnidocs.app.ai.DownloadState
import com.omnidocs.app.ai.ModelDownloadManager
import com.omnidocs.app.ai.ModelInfo
import com.omnidocs.app.ai.ModelPreferences
import com.omnidocs.app.stt.SttModelInfo
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class WelcomeViewModel @Inject constructor(
    val modelDownloadManager: ModelDownloadManager,
    private val modelPreferences: ModelPreferences,
    private val onboardingPreferences: OnboardingPreferences,
    private val demoNotebookSeeder: com.omnidocs.app.data.repository.DemoNotebookSeeder? = null
) : ViewModel() {

    val recommendedModelId: String = modelPreferences.getDefaultModelId()

    val availableLlmModels: List<ModelInfo> = modelDownloadManager.availableModels
    val availableEmbeddingModels: List<ModelInfo> = modelDownloadManager.embeddingModels
    val availableSttModels: List<SttModelInfo> = modelDownloadManager.sttModels

    val downloadedModels: StateFlow<List<ModelInfo>> = modelDownloadManager.downloadState
        .map { modelDownloadManager.getDownloadedModels() }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = modelDownloadManager.getDownloadedModels()
        )

    val downloadState: StateFlow<DownloadState> = modelDownloadManager.downloadState

    val downloadedEmbeddingModels: StateFlow<List<ModelInfo>> = modelDownloadManager.embeddingDownloadState
        .map { modelDownloadManager.getDownloadedEmbeddingModels() }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = modelDownloadManager.getDownloadedEmbeddingModels()
        )

    val embeddingDownloadState: StateFlow<DownloadState> = modelDownloadManager.embeddingDownloadState

    val downloadedSttModels: StateFlow<List<SttModelInfo>> = modelDownloadManager.sttDownloadState
        .map { modelDownloadManager.getDownloadedSttModels() }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = modelDownloadManager.getDownloadedSttModels()
        )

    val sttDownloadState: StateFlow<DownloadState> = modelDownloadManager.sttDownloadState

    fun downloadModel(model: ModelInfo) {
        viewModelScope.launch {
            modelDownloadManager.downloadModel(model)
            if (downloadState.value !is DownloadState.Error) {
                modelPreferences.setSelectedModelId(model.id)
            }
        }
    }

    fun downloadEmbeddingModel(model: ModelInfo) {
        viewModelScope.launch {
            modelDownloadManager.downloadEmbeddingModel(model)
            if (embeddingDownloadState.value !is DownloadState.Error) {
                modelPreferences.setSelectedEmbeddingModelId(model.id)
            }
        }
    }

    fun downloadSttModel(model: SttModelInfo) {
        viewModelScope.launch {
            modelDownloadManager.downloadSttModel(model)
            if (sttDownloadState.value !is DownloadState.Error) {
                modelPreferences.setSelectedSttModelId(model.id)
            }
        }
    }

    fun completeOnboarding() {
        onboardingPreferences.setOnboardingCompleted(true)
        viewModelScope.launch {
            demoNotebookSeeder?.seedIfEmptyWorkspace()
        }
    }
}
