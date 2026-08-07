package com.omnidocs.app.ai

import kotlinx.coroutines.flow.first

/**
 * Shared model resolution logic. Returns the currently selected model
 * if downloaded, otherwise falls back to any downloaded model, or null.
 */
suspend fun resolveActiveModel(
    modelPreferences: ModelPreferences,
    modelDownloadManager: ModelDownloadManager
): ModelInfo? {
    val selectedId = modelPreferences.selectedModelId.first()
    val downloaded = modelDownloadManager.getDownloadedModels()
    return downloaded.find { it.id == selectedId && it.isDownloaded }
        ?: downloaded.firstOrNull { it.isDownloaded }
}

/**
 * Check if any model is downloaded and ready for inference.
 */
fun isAnyModelDownloaded(modelDownloadManager: ModelDownloadManager): Boolean {
    return modelDownloadManager.getDownloadedModels().any { it.isDownloaded }
}
