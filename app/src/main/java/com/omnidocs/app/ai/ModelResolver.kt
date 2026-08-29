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

/**
 * Resolve the active embedding model: the selected one if downloaded, otherwise
 * any downloaded embedding model, or null (caller falls back to n-gram hash).
 */
suspend fun resolveActiveEmbeddingModel(
    modelPreferences: ModelPreferences,
    modelDownloadManager: ModelDownloadManager
): ModelInfo? {
    val selectedId = modelPreferences.selectedEmbeddingModelId.first()
    val downloaded = modelDownloadManager.getDownloadedEmbeddingModels()
    return downloaded.find { it.id == selectedId && it.isDownloaded }
        ?: downloaded.firstOrNull { it.isDownloaded }
}

/**
 * Check if any embedding model is downloaded (gates real-embedding paths).
 */
fun isAnyEmbeddingModelDownloaded(modelDownloadManager: ModelDownloadManager): Boolean {
    return modelDownloadManager.getDownloadedEmbeddingModels().any { it.isDownloaded }
}
