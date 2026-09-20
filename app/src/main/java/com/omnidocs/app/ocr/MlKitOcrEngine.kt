package com.omnidocs.app.ocr

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.RectF
import android.media.Image
import android.net.Uri
import android.util.Log
import com.google.mlkit.vision.common.InputImage
import java.io.IOException
import com.google.mlkit.vision.text.Text
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.TextRecognizer
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions
import com.google.mlkit.vision.text.japanese.JapaneseTextRecognizerOptions
import com.google.mlkit.vision.text.korean.KoreanTextRecognizerOptions
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "MlKitOcrEngine"

/**
 * OCR engine implementation using Google ML Kit Text Recognition.
 * Supports Latin, Chinese, Japanese, and Korean scripts.
 */
@Singleton
class MlKitOcrEngine @Inject constructor(
    @ApplicationContext private val context: Context
) : OcrEngine {

    private var currentRecognizer: TextRecognizer? = null
    private var currentLanguage: String? = null

    override suspend fun recognizeText(uri: Uri, language: String): OcrResult? {
        return withContext(Dispatchers.IO) {
            // Bitmaps WE decode here are owned by us and must be recycled after
            // ML Kit finishes with them; caller-supplied bitmaps are never recycled.
            var ownedBitmap: Bitmap? = null
            try {
                val recognizer = getOrCreateRecognizer(language)
                val image = if (uri.scheme == "file") {
                    val filePath = uri.path ?: throw IOException("File URI has no path: $uri")
                    val bitmap = decodeSampledBitmap(filePath)
                        ?: throw IOException("Failed to decode bitmap from: $filePath")
                    ownedBitmap = bitmap
                    InputImage.fromBitmap(bitmap, 0)
                } else {
                    InputImage.fromFilePath(context, uri)
                }

                val result = try {
                    recognizer.process(image).await()
                } catch (e: Exception) {
                    Log.e(TAG, "Recognition failed with exception", e)
                    return@withContext null
                }

                Log.d(TAG, "recognizeText: ML Kit returned, text length=${result.text.length}, blocks=${result.textBlocks.size}")
                if (result.text.isBlank()) {
                    Log.w(TAG, "No text detected by ML Kit")
                    return@withContext OcrResult("", "", emptyList())
                }

                val blocks = result.textBlocks.map { block ->
                    val blockRect = block.boundingBox ?: android.graphics.Rect(0, 0, 0, 0)
                    OcrBlock(
                        text = block.text,
                        confidence = 1.0f,
                        boundingBox = RectF(
                            blockRect.left.toFloat(),
                            blockRect.top.toFloat(),
                            blockRect.right.toFloat(),
                            blockRect.bottom.toFloat()
                        )
                    )
                }

                val html = OcrHtmlBuilder.fromBlocks(blocks)
                val fullText = blocks.joinToString("\n") { ocrBlock -> ocrBlock.text }

                Log.d(TAG, "Recognized ${blocks.size} blocks, ${fullText.length} chars")
                OcrResult(fullText, html, blocks)
            } catch (e: Throwable) {
                Log.e(TAG, "Recognition failed with throwable", e)
                null
            } finally {
                try {
                    ownedBitmap?.takeIf { !it.isRecycled }?.recycle()
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to recycle decoded bitmap", e)
                }
            }
        }
    }

    /**
     * Two-pass decode: read bounds first, then decode with a power-of-2
     * `inSampleSize` so the longest edge lands at or under [MAX_OCR_DIMENSION_PX],
     * with a precise scale pass if the power-of-2 step still overshoots.
     * A 12MP photo would otherwise decode to ~48MB ARGB_8888 plus ML Kit's copy.
     */
    private fun decodeSampledBitmap(filePath: String): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(filePath, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
            Log.w(TAG, "Could not read image bounds: $filePath")
            return null
        }
        val longest = maxOf(bounds.outWidth, bounds.outHeight)
        if (longest <= MAX_OCR_DIMENSION_PX) {
            return BitmapFactory.decodeFile(filePath)
        }
        var sampleSize = 1
        while (longest / sampleSize > MAX_OCR_DIMENSION_PX) {
            sampleSize *= 2
        }
        val opts = BitmapFactory.Options().apply {
            inSampleSize = sampleSize
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        val decoded = BitmapFactory.decodeFile(filePath, opts)
            ?: return BitmapFactory.decodeFile(filePath)
        if (maxOf(decoded.width, decoded.height) <= MAX_OCR_DIMENSION_PX) {
            return decoded
        }
        val scaled = downscaleForOcr(decoded)
        if (scaled !== decoded) {
            decoded.recycle()
        }
        return scaled
    }

    /**
     * Recognize text directly from a Bitmap (avoids file I/O round-trip).
     * Used by live camera OCR to avoid JPEG corruption issues.
     */
    override suspend fun recognizeBitmap(bitmap: Bitmap, rotationDegrees: Int, language: String): OcrResult? {
        return withContext(Dispatchers.IO) {
            // Downscale defensively: the caller may hand us a full-res camera frame.
            // The copy is ours to recycle; the caller's bitmap is never touched.
            val scaled = downscaleForOcr(bitmap)
            try {
                val recognizer = getOrCreateRecognizer(language)
                val image = InputImage.fromBitmap(scaled, rotationDegrees)

                val result = try {
                    recognizer.process(image).await()
                } catch (e: Exception) {
                    Log.e(TAG, "recognizeBitmap: ML Kit failed", e)
                    return@withContext null
                }

                Log.d(TAG, "recognizeBitmap: ML Kit returned, text length=${result.text.length}, blocks=${result.textBlocks.size}")
                if (result.text.isBlank()) {
                    Log.w(TAG, "recognizeBitmap: No text detected")
                    return@withContext OcrResult("", "", emptyList())
                }

                val blocks = result.textBlocks.map { block ->
                    val blockRect = block.boundingBox ?: android.graphics.Rect(0, 0, 0, 0)
                    OcrBlock(
                        text = block.text,
                        confidence = 1.0f,
                        boundingBox = RectF(
                            blockRect.left.toFloat(),
                            blockRect.top.toFloat(),
                            blockRect.right.toFloat(),
                            blockRect.bottom.toFloat()
                        )
                    )
                }

                val html = OcrHtmlBuilder.fromBlocks(blocks)
                val fullText = blocks.joinToString("\n") { it.text }
                Log.d(TAG, "recognizeBitmap: Recognized ${blocks.size} blocks, ${fullText.length} chars")
                OcrResult(fullText, html, blocks)
            } catch (e: Throwable) {
                Log.e(TAG, "recognizeBitmap: failed", e)
                null
            } finally {
                if (scaled !== bitmap) {
                    try {
                        scaled.takeIf { !it.isRecycled }?.recycle()
                    } catch (e: Exception) {
                        Log.w(TAG, "Failed to recycle scaled bitmap", e)
                    }
                }
            }
        }
    }

    /**
     * Recognize text directly from a camera Image (YUV_420_888).
     * No bitmap/JPEG conversion needed — ML Kit handles YUV natively.
     */
    fun recognizeFromMediaImage(image: Image, rotationDegrees: Int, language: String): OcrResult? {
        try {
            val recognizer = getOrCreateRecognizer(language)
            val inputImage = InputImage.fromMediaImage(image, rotationDegrees)

            // Block on ML Kit result (called from background thread)
            val result = com.google.android.gms.tasks.Tasks.await(recognizer.process(inputImage))

            Log.d(TAG, "recognizeFromMediaImage: ML Kit returned, text length=${result.text.length}, blocks=${result.textBlocks.size}")
            if (result.text.isBlank()) {
                Log.w(TAG, "recognizeFromMediaImage: No text detected")
                return OcrResult("", "", emptyList())
            }

            val blocks = result.textBlocks.map { block ->
                val blockRect = block.boundingBox ?: android.graphics.Rect(0, 0, 0, 0)
                OcrBlock(
                    text = block.text,
                    confidence = 1.0f,
                    boundingBox = RectF(
                        blockRect.left.toFloat(),
                        blockRect.top.toFloat(),
                        blockRect.right.toFloat(),
                        blockRect.bottom.toFloat()
                    )
                )
            }

            val html = OcrHtmlBuilder.fromBlocks(blocks)
            val fullText = blocks.joinToString("\n") { it.text }
            Log.d(TAG, "recognizeFromMediaImage: Recognized ${blocks.size} blocks, ${fullText.length} chars")
            return OcrResult(fullText, html, blocks)
        } catch (e: Exception) {
            Log.e(TAG, "recognizeFromMediaImage: failed", e)
            return null
        }
    }

    override fun close() {
        currentRecognizer?.close()
        currentRecognizer = null
        currentLanguage = null
    }

    private fun getOrCreateRecognizer(language: String): TextRecognizer {
        if (currentRecognizer != null && currentLanguage == language) {
            return currentRecognizer!!
        }

        currentRecognizer?.close()

        val options = when (language) {
            "zh" -> ChineseTextRecognizerOptions.Builder().build()
            "ja" -> JapaneseTextRecognizerOptions.Builder().build()
            "ko" -> KoreanTextRecognizerOptions.Builder().build()
            else -> TextRecognizerOptions.DEFAULT_OPTIONS
        }

        currentRecognizer = TextRecognition.getClient(options)
        currentLanguage = language
        Log.d(TAG, "Created recognizer for language: $language")
        return currentRecognizer!!
    }
}
