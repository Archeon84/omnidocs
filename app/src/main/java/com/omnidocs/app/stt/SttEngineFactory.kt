package com.omnidocs.app.stt

import android.content.Context
import android.speech.SpeechRecognizer
import android.util.Log
import com.omnidocs.app.ai.ModelDownloadManager
import com.omnidocs.app.ai.ModelPreferences
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.first
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "SttEngineFactory"

/**
 * Factory for creating the appropriate speech recognition engine.
 * Uses Sherpa-onnx when an STT model is downloaded, falls back to system SpeechRecognizer.
 */
@Singleton
class SttEngineFactory @Inject constructor(
    @ApplicationContext private val context: Context,
    private val modelDownloadManager: ModelDownloadManager,
    private val modelPreferences: ModelPreferences,
    private val sherpaEngine: SherpaOnnxSttEngine
) {
    /**
     * Get the best available STT engine.
     * Returns SherpaOnnxSttEngine if a model is downloaded and initialized,
     * otherwise returns null (caller should use system SpeechRecognizer).
     */
    suspend fun getEngine(): SttEngine? {
        val selectedModelId = modelPreferences.selectedSttModelId.first()
        val sttLanguage = modelPreferences.sttLanguage.first()
        val accuracyMode = modelPreferences.sttAccuracyMode.first()
        val downloadedModels = modelDownloadManager.getDownloadedSttModels()
        val availableModels = downloadedModels.filter { it.isDownloaded }

        Log.d(TAG, "getEngine: selected=$selectedModelId, downloaded=${availableModels.map { it.id }}, mode=$accuracyMode")

        if (availableModels.isEmpty()) {
            Log.d(TAG, "No STT model downloaded")
            return null
        }

        // Try selected model first
        val selectedModel = availableModels.find { it.id == selectedModelId }
        if (selectedModel != null) {
            val modelDir = modelDownloadManager.getSttModelPath(selectedModel)
            Log.d(TAG, "Trying selected model: ${selectedModel.name} at $modelDir")
            if (sherpaEngine.initialize(modelDir, selectedModel, sttLanguage, accuracyMode)) {
                Log.d(TAG, "Using Sherpa-onnx with ${selectedModel.name}")
                return sherpaEngine
            }
            Log.w(TAG, "Failed to initialize selected model: ${selectedModel.name}")
        }

        // Fallback: try any other downloaded model (only auto-save selection if nothing was explicitly chosen)
        for (model in availableModels) {
            if (model.id == selectedModelId) continue // already tried
            val modelDir = modelDownloadManager.getSttModelPath(model)
            Log.d(TAG, "Trying fallback model: ${model.name} at $modelDir")
            if (sherpaEngine.initialize(modelDir, model, sttLanguage, accuracyMode)) {
                Log.d(TAG, "Using Sherpa-onnx with fallback: ${model.name}")
                // Only auto-select if user had no explicit selection (null = system/default)
                if (selectedModelId == null) {
                    modelPreferences.setSelectedSttModelId(model.id)
                }
                return sherpaEngine
            }
            Log.w(TAG, "Failed to initialize fallback model: ${model.name}")
        }

        Log.e(TAG, "All ${availableModels.size} downloaded models failed to initialize")
        return null
    }

    /**
     * Check if offline STT is available.
     */
    fun isOfflineSttAvailable(): Boolean {
        return modelDownloadManager.getDownloadedSttModels().any { it.isDownloaded }
    }

    /**
     * Check if the system SpeechRecognizer is available.
     */
    fun isSystemRecognizerAvailable(): Boolean {
        return SpeechRecognizer.isRecognitionAvailable(context)
    }
}
