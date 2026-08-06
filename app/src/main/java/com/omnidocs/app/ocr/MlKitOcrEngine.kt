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
            try {
                Log.d(TAG, "recognizeText: uri=$uri language=$language")
                val recognizer = getOrCreateRecognizer(language)
                Log.d(TAG, "recognizeText: recognizer created, building InputImage")
                val image = if (uri.scheme == "file") {
                    val filePath = uri.path ?: throw IOException("File URI has no path: $uri")
                    val bitmap = BitmapFactory.decodeFile(filePath)
                        ?: throw IOException("Failed to decode bitmap from: $filePath")
                    InputImage.fromBitmap(bitmap, 0)
                } else {
                    InputImage.fromFilePath(context, uri)
                }
                Log.d(TAG, "recognizeText: InputImage created from URI scheme=${uri.scheme}, processing...")

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
                    Log.d(TAG, "Block: '${block.text.take(50)}...' rect=$blockRect")
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
            }
        }
    }

    /**
     * Recognize text directly from a Bitmap (avoids file I/O round-trip).
     * Used by live camera OCR to avoid JPEG corruption issues.
     */
    suspend fun recognizeBitmap(bitmap: Bitmap, rotationDegrees: Int, language: String): OcrResult? {
        return withContext(Dispatchers.IO) {
            try {
                Log.d(TAG, "recognizeBitmap: ${bitmap.width}x${bitmap.height} rotation=$rotationDegrees lang=$language")
                val recognizer = getOrCreateRecognizer(language)
                val image = InputImage.fromBitmap(bitmap, rotationDegrees)
                Log.d(TAG, "recognizeBitmap: InputImage created, processing...")

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
            }
        }
    }

    /**
     * Recognize text directly from a camera Image (YUV_420_888).
     * No bitmap/JPEG conversion needed — ML Kit handles YUV natively.
     */
    fun recognizeFromMediaImage(image: Image, rotationDegrees: Int, language: String): OcrResult? {
        try {
            Log.d(TAG, "recognizeFromMediaImage: ${image.width}x${image.height} rotation=$rotationDegrees lang=$language")
            val recognizer = getOrCreateRecognizer(language)
            val inputImage = InputImage.fromMediaImage(image, rotationDegrees)
            Log.d(TAG, "recognizeFromMediaImage: InputImage created, processing...")

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
