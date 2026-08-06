package com.omnidocs.app.ocr

import javax.inject.Inject
import javax.inject.Singleton

/**
 * Factory for creating OCR engines.
 * Uses ML Kit Text Recognition as the primary engine.
 */
@Singleton
class OcrEngineFactory @Inject constructor(
    private val mlKitOcrEngine: MlKitOcrEngine
) {
    /**
     * Get the default OCR engine.
     *
     * @return OcrEngine instance
     */
    fun getEngine(): OcrEngine = mlKitOcrEngine

    /**
     * Get available languages from the default engine.
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
}
