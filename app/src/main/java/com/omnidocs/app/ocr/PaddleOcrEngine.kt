package com.omnidocs.app.ocr

import android.content.Context
import android.graphics.RectF
import android.net.Uri
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "PaddleOcrEngine"

/**
 * PaddleOCR engine implementation using Paddle Lite via JNI.
 */
@Singleton
class PaddleOcrEngine @Inject constructor(
    @ApplicationContext private val context: Context,
    private val modelManager: OcrModelManager
) : OcrEngine {

    private val paddleNative = PaddleNative()
    private var isInitialized = false
    private var currentLanguage: String? = null

    override suspend fun recognizeText(uri: Uri, language: String): OcrResult? {
        return withContext(Dispatchers.IO) {
            try {
                // Initialize if needed or language changed
                if (!isInitialized || currentLanguage != language) {
                    initializeEngine(language)
                }

                // Copy URI to temp file if needed
                val imagePath = copyUriToTempFile(uri) ?: return@withContext null

                // Run recognition
                val results = paddleNative.recognize(imagePath, 0.5f)

                // Clean up temp file
                File(imagePath).delete()

                if (results.isNullOrEmpty()) {
                    Log.w(TAG, "No text detected")
                    return@withContext OcrResult("", "", emptyList())
                }

                // Convert results to OcrBlock list
                // Each result array: [text_index, confidence, x1, y1, x2, y2, x3, y3, x4, y4]
                // The JNI layer returns text as a separate string array; here we use a placeholder
                // that will be replaced with actual text extraction in the JNI implementation.
                val blocks = results.mapIndexed { index, result ->
                    OcrBlock(
                        text = "Block ${index + 1}", // Placeholder - JNI returns text separately
                        confidence = result[1],
                        boundingBox = RectF(
                            result[2], result[3],  // x1, y1 (top-left)
                            result[6], result[7]   // x3, y3 (bottom-right)
                        )
                    )
                }

                // Build HTML
                val html = OcrHtmlBuilder.fromBlocks(blocks)
                val fullText = blocks.joinToString("\n") { it.text }

                OcrResult(fullText, html, blocks)
            } catch (e: Exception) {
                Log.e(TAG, "Recognition failed", e)
                null
            }
        }
    }

    override fun close() {
        try {
            paddleNative.destroy()
            isInitialized = false
            currentLanguage = null
        } catch (e: Exception) {
            Log.e(TAG, "Error closing engine", e)
        }
    }

    private suspend fun initializeEngine(language: String) {
        val modelPaths = modelManager.getModelPaths(language)
        if (modelPaths == null) {
            throw IllegalStateException("Models not available for language: $language")
        }

        val (detPath, recPath) = modelPaths
        val labelPath = modelManager.getLabelPath()

        val success = paddleNative.init(detPath, recPath, labelPath, 4)
        if (!success) {
            throw IllegalStateException("Failed to initialize PaddleOCR engine")
        }

        isInitialized = true
        currentLanguage = language
        Log.d(TAG, "Engine initialized for language: $language")
    }

    private fun copyUriToTempFile(uri: Uri): String? {
        return try {
            val tempFile = File(context.cacheDir, "ocr_input_${System.currentTimeMillis()}.jpg")

            context.contentResolver.openInputStream(uri)?.use { input ->
                FileOutputStream(tempFile).use { output ->
                    input.copyTo(output)
                }
            }

            tempFile.absolutePath
        } catch (e: Exception) {
            Log.e(TAG, "Failed to copy URI to temp file", e)
            null
        }
    }
}
