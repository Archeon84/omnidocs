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
        val downloadedModels = modelDownloadManager.getDownloadedSttModels()

        // Try selected model first
        val selectedModel = downloadedModels.find { it.id == selectedModelId && it.isDownloaded }
        if (selectedModel != null) {
            val modelDir = modelDownloadManager.getSttModelPath(selectedModel)
            if (sherpaEngine.initialize(modelDir, selectedModel)) {
                Log.d(TAG, "Using Sherpa-onnx with ${selectedModel.name}")
                return sherpaEngine
            }
        }

        // Fall back to any downloaded model
        val anyModel = downloadedModels.firstOrNull { it.isDownloaded }
        if (anyModel != null) {
            val modelDir = modelDownloadManager.getSttModelPath(anyModel)
            if (sherpaEngine.initialize(modelDir, anyModel)) {
                Log.d(TAG, "Using Sherpa-onnx with fallback: ${anyModel.name}")
                return sherpaEngine
            }
        }

        // No STT model available
        Log.d(TAG, "No STT model downloaded, system SpeechRecognizer will be used")
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
