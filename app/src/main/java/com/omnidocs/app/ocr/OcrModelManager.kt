package com.omnidocs.app.ocr

import android.content.Context
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.io.FileOutputStream
import java.net.URL
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "OcrModelManager"

/**
 * Manages PaddleOCR model files (download, extraction, storage).
 * Models are stored in app's internal storage under models/ocr/.
 */
@Singleton
class OcrModelManager @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private val modelsDir = File(context.filesDir, "models/ocr")

    /**
     * Language to model mapping.
     * Key: language code used in OcrViewModel
     * Value: pair of (detection model filename, recognition model filename)
     */
    private val languageModels = mapOf(
        "en" to Pair("ch_PP-OCRv4_det", "en_PP-OCRv4_rec"),
        "zh" to Pair("ch_PP-OCRv4_det", "ch_PP-OCRv4_rec"),
        "ja" to Pair("ch_PP-OCRv4_det", "ja_PP-OCRv4_rec"),
        "ko" to Pair("ch_PP-OCRv4_det", "ko_PP-OCRv4_rec"),
        "ar" to Pair("ch_PP-OCRv4_det", "arabic_PP-OCRv4_rec"),
        "hi" to Pair("ch_PP-OCRv4_det", "devanagari_PP-OCRv4_rec"),
        "ru" to Pair("ch_PP-OCRv4_det", "cyrillic_PP-OCRv4_rec")
    )

    init {
        modelsDir.mkdirs()
    }

    /**
     * Ensure models for the given language are available.
     * For now, models are bundled in assets and extracted on first use.
     * Future: download from remote server.
     *
     * @return Pair of (detModelPath, recModelPath) or null if models not available
     */
    suspend fun getModelPaths(language: String): Pair<String, String>? {
        val (detName, recName) = languageModels[language] ?: languageModels["en"]!!

        val detFile = File(modelsDir, "$detName.nb")
        val recFile = File(modelsDir, "$recName.nb")

        // Check if models already extracted
        if (detFile.exists() && recFile.exists()) {
            return Pair(detFile.absolutePath, recFile.absolutePath)
        }

        // Try to extract from assets
        return try {
            extractFromAssets(detName, detFile)
            extractFromAssets(recName, recFile)
            Pair(detFile.absolutePath, recFile.absolutePath)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to extract models from assets", e)
            null
        }
    }

    /**
     * Get the label file path for recognition.
     */
    fun getLabelPath(): String {
        val labelFile = File(modelsDir, "ppocr_keys_v1.txt")
        if (!labelFile.exists()) {
            extractFromAssets("ppocr_keys_v1", labelFile)
        }
        return labelFile.absolutePath
    }

    private fun extractFromAssets(assetName: String, targetFile: File) {
        if (targetFile.exists()) return

        context.assets.open("models/ocr/$assetName.nb").use { input ->
            FileOutputStream(targetFile).use { output ->
                input.copyTo(output)
            }
        }
        Log.d(TAG, "Extracted $assetName to ${targetFile.absolutePath}")
    }

    /**
     * Get all supported languages.
     */
    fun getSupportedLanguages(): Map<String, String> = mapOf(
        "en" to "English",
        "zh" to "Chinese",
        "ja" to "Japanese",
        "ko" to "Korean",
        "ar" to "Arabic",
        "hi" to "Hindi",
        "ru" to "Russian"
    )

    /**
     * Check if models are available for a language.
     */
    fun isLanguageAvailable(language: String): Boolean {
        return languageModels.containsKey(language)
    }
}