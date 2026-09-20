package com.omnidocs.app.ocr

import android.graphics.Bitmap
import android.net.Uri
import android.graphics.RectF

/**
 * Longest edge (px) for OCR input. A 12MP photo decoded at full size is ~48MB
 * ARGB_8888; ML Kit gains nothing beyond ~2048px for document text.
 */
const val MAX_OCR_DIMENSION_PX = 2048

/** JPEG quality for cached OCR preview files (was 95; 80 is visually identical for text). */
const val OCR_JPEG_QUALITY = 80

/**
 * Downscale [bitmap] so its longest edge is at most [maxDimension], preserving
 * aspect ratio. Returns the original bitmap when already small enough.
 */
fun downscaleForOcr(bitmap: Bitmap, maxDimension: Int = MAX_OCR_DIMENSION_PX): Bitmap {
    val longest = maxOf(bitmap.width, bitmap.height)
    if (longest <= maxDimension) return bitmap
    val scale = maxDimension.toFloat() / longest
    return Bitmap.createScaledBitmap(
        bitmap,
        (bitmap.width * scale).toInt().coerceAtLeast(1),
        (bitmap.height * scale).toInt().coerceAtLeast(1),
        true
    )
}

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
     * Recognize text directly from a Bitmap (avoids file I/O round-trip).
     * Used by live camera OCR to avoid JPEG corruption issues.
     */
    suspend fun recognizeBitmap(bitmap: Bitmap, rotationDegrees: Int, language: String): OcrResult?

    /**
     * Release resources held by this engine.
     */
    fun close()
}
