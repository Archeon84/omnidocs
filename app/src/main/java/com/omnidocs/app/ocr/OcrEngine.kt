package com.omnidocs.app.ocr

import android.net.Uri
import android.graphics.RectF

/**
 * Result of OCR recognition on an image.
 */
data class OcrResult(
    val text: String,
    val html: String,
    val blocks: List<OcrBlock>
)

/**
 * A single recognized text block with bounding box and confidence.
 */
data class OcrBlock(
    val text: String,
    val confidence: Float,
    val boundingBox: RectF  // normalized 0-1 coordinates
)

/**
 * Interface for OCR engines.
 */
interface OcrEngine {
    /**
     * Recognize text in an image.
     *
     * @param uri Image URI (content:// or file://)
     * @param language Language code (e.g., "en", "zh", "ja", "ko", "ar", "hi", "ru")
     * @return OcrResult with text, HTML, and blocks, or null if recognition fails
     */
    suspend fun recognizeText(uri: Uri, language: String): OcrResult?

    /**
     * Release resources held by this engine.
     */
    fun close()
}
