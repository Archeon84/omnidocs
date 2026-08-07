package com.omnidocs.app.ui.screens.settings

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.google.android.gms.auth.api.signin.GoogleSignIn
import com.omnidocs.app.ai.DownloadState
import com.omnidocs.app.ai.LlamaCppService
import com.omnidocs.app.ai.ModelDownloadManager
import com.omnidocs.app.ai.ModelInfo
import com.omnidocs.app.ai.ModelPreferences
import com.omnidocs.app.data.remote.DriveService
import com.omnidocs.app.ui.theme.AppTheme
import com.omnidocs.app.ui.theme.ThemeManager
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import javax.inject.Inject

@HiltViewModel
class SettingsViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val driveService: DriveService,
    val modelDownloadManager: ModelDownloadManager,
    private val llamaCppService: LlamaCppService,
    private val modelPreferences: ModelPreferences
) : ViewModel() {

    private val themeManager = ThemeManager(context)

    val currentTheme: StateFlow<AppTheme> = themeManager.currentTheme

    private val _isSignedIn = MutableStateFlow(false)
    val isSignedIn: StateFlow<Boolean> = _isSignedIn.asStateFlow()

    private val _isSyncing = MutableStateFlow(false)
    val isSyncing: StateFlow<Boolean> = _isSyncing.asStateFlow()

    private val _syncMessage = MutableStateFlow<String?>(null)
    val syncMessage: StateFlow<String?> = _syncMessage.asStateFlow()

    private val _snackbarEvent = MutableSharedFlow<String>(extraBufferCapacity = 1)
    val snackbarEvent: SharedFlow<String> = _snackbarEvent.asSharedFlow()

    val downloadedModels: StateFlow<List<ModelInfo>> = modelDownloadManager.downloadState
        .map { modelDownloadManager.getDownloadedModels() }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = modelDownloadManager.getDownloadedModels()
        )

    val downloadState: StateFlow<DownloadState> = modelDownloadManager.downloadState

    /** Currently selected model ID, persisted across restarts. */
    val selectedModelId: StateFlow<String> = modelPreferences.selectedModelId
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = ModelPreferences.DEFAULT_MODEL_ID
        )

    init {
        checkSignInStatus()
    }

    fun setTheme(theme: AppTheme) {
        themeManager.setTheme(theme)
    }

    fun checkSignInStatus() {
        _isSignedIn.value = GoogleSignIn.getLastSignedInAccount(context) != null
        _syncMessage.value = null
    }

    fun syncToCloud() {
        viewModelScope.launch {
            _isSyncing.value = true
            _syncMessage.value = null
            try {
                val success = driveService.syncToCloud()
                val msg = if (success) "Notes synced to Google Drive" else "Sync failed"
                _syncMessage.value = msg
                _snackbarEvent.tryEmit(msg)
            } catch (e: Exception) {
                val msg = "Sync error: ${e.message}"
                _syncMessage.value = msg
                _snackbarEvent.tryEmit(msg)
            } finally {
                _isSyncing.value = false
            }
        }
    }

    fun syncFromCloud() {
        viewModelScope.launch {
            _isSyncing.value = true
            _syncMessage.value = null
            try {
                val success = driveService.syncFromCloud()
                val msg = if (success) "Notes restored from Google Drive" else "Restore failed"
                _syncMessage.value = msg
                _snackbarEvent.tryEmit(msg)
            } catch (e: Exception) {
                val msg = "Restore error: ${e.message}"
                _syncMessage.value = msg
                _snackbarEvent.tryEmit(msg)
            } finally {
                _isSyncing.value = false
            }
        }
    }

    fun signOut() {
        driveService.signOut()
        _isSignedIn.value = false
        _syncMessage.value = "Signed out"
        _snackbarEvent.tryEmit("Signed out")
    }

    fun downloadModel(model: ModelInfo) {
        viewModelScope.launch {
            modelDownloadManager.downloadModel(model)
        }
    }

    fun deleteModel(model: ModelInfo) {
        modelDownloadManager.deleteModel(model)
        // If the deleted model was the selected one, reset to default
        if (model.id == selectedModelId.value) {
            viewModelScope.launch {
                modelPreferences.setSelectedModelId(ModelPreferences.DEFAULT_MODEL_ID)
            }
        }
        _snackbarEvent.tryEmit("${model.name} deleted")
    }

    /**
     * Select and load a model. Unloads any currently loaded model first.
     */
    fun selectModel(modelId: String) {
        viewModelScope.launch {
            // Save selection to DataStore first
            modelPreferences.setSelectedModelId(modelId)

            // Unload current model if one is loaded
            if (llamaCppService.isReady()) {
                _syncMessage.value = "Switching model..."
                llamaCppService.unloadModel()
            }

            // Load the new model
            val success = llamaCppService.loadModel(modelId)
            val model = modelDownloadManager.getDownloadedModels().find { it.id == modelId }
            val name = model?.name ?: modelId
            if (success) {
                _syncMessage.value = "$name loaded successfully"
                _snackbarEvent.tryEmit("$name loaded")
            } else {
                _syncMessage.value = "Failed to load $name"
                _snackbarEvent.tryEmit("Failed to load $name")
            }
        }
    }

    /**
     * Load the currently selected model (used on first app open or after settings change).
     */
    fun loadModel() {
        viewModelScope.launch {
            val modelId = modelPreferences.selectedModelId.first()
            val success = llamaCppService.loadModel(modelId)
            val model = modelDownloadManager.getDownloadedModels().find { it.id == modelId }
            if (success) {
                _syncMessage.value = "${model?.name ?: "Model"} loaded successfully"
            } else {
                _syncMessage.value = "Failed to load model"
            }
        }
    }

    fun isModelReady(): Boolean = llamaCppService.isReady()

    // ---- STT model management ----

    val sttDownloadedModels: StateFlow<List<com.omnidocs.app.stt.SttModelInfo>> = modelDownloadManager.sttDownloadState
        .map { modelDownloadManager.getDownloadedSttModels() }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = modelDownloadManager.getDownloadedSttModels()
        )

    val sttDownloadState: StateFlow<DownloadState> = modelDownloadManager.sttDownloadState

    val selectedSttModelId: StateFlow<String?> = modelPreferences.selectedSttModelId
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = null
        )

    fun downloadSttModel(model: com.omnidocs.app.stt.SttModelInfo) {
        viewModelScope.launch {
            modelDownloadManager.downloadSttModel(model)
        }
    }

    fun deleteSttModel(model: com.omnidocs.app.stt.SttModelInfo) {
        modelDownloadManager.deleteSttModel(model)
        if (model.id == selectedSttModelId.value) {
            viewModelScope.launch {
                modelPreferences.setSelectedSttModelId(null)
            }
        }
        _snackbarEvent.tryEmit("${model.name} deleted")
    }

    fun selectSttModel(modelId: String?) {
        viewModelScope.launch {
            modelPreferences.setSelectedSttModelId(modelId)
            if (modelId != null) {
                _snackbarEvent.tryEmit("STT model selected")
            } else {
                _snackbarEvent.tryEmit("Using system recognizer")
            }
        }
    }
}
