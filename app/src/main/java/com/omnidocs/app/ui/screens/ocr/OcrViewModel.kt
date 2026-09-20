package com.omnidocs.app.ui.screens.ocr

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.omnidocs.app.ai.NllbTranslationService
import com.omnidocs.app.ocr.OCR_JPEG_QUALITY
import com.omnidocs.app.ocr.OcrEngineFactory
import com.omnidocs.app.ocr.OcrHtmlBuilder
import com.omnidocs.app.ocr.downscaleForOcr
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import javax.inject.Inject

private const val TAG = "OcrViewModel"

@HiltViewModel
class OcrViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val engineFactory: OcrEngineFactory,
    private val translationService: NllbTranslationService
) : ViewModel() {

    private val _recognizedText = MutableStateFlow("")
    val recognizedText: StateFlow<String> = _recognizedText.asStateFlow()

    private val _recognizedHtml = MutableStateFlow("")
    val recognizedHtml: StateFlow<String> = _recognizedHtml.asStateFlow()

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    private val _selectedImageUri = MutableStateFlow<Uri?>(null)
    val selectedImageUri: StateFlow<Uri?> = _selectedImageUri.asStateFlow()

    private val _ocrLanguage = MutableStateFlow("en")
    val ocrLanguage: StateFlow<String> = _ocrLanguage.asStateFlow()

    private val _translationTarget = MutableStateFlow<String?>(null)
    val translationTarget: StateFlow<String?> = _translationTarget.asStateFlow()

    private val _translatedText = MutableStateFlow("")
    val translatedText: StateFlow<String> = _translatedText.asStateFlow()

    private val _ocrError = MutableStateFlow<String?>(null)
    val ocrError: StateFlow<String?> = _ocrError.asStateFlow()

    private val _originalText = MutableStateFlow("")
    val originalText: StateFlow<String> = _originalText.asStateFlow()

    private val _batchProgress = MutableStateFlow<Pair<Int, Int>?>(null)
    val batchProgress: StateFlow<Pair<Int, Int>?> = _batchProgress.asStateFlow()

    val supportedLanguages: Map<String, String> = engineFactory.getSupportedLanguages()

    override fun onCleared() {
        super.onCleared()
        engineFactory.getEngine().close()
    }

    fun setOcrLanguage(lang: String) {
        _ocrLanguage.value = lang
        Log.d(TAG, "OCR language set to: $lang")
    }

    fun setTranslationTarget(lang: String?) {
        _translationTarget.value = lang
        Log.d(TAG, "Translation target set to: $lang")
    }

    fun setImageUri(uri: Uri) {
        _selectedImageUri.value = uri
    }

    fun updateRecognizedText(text: String) {
        _recognizedText.value = text
    }

    fun recognizeTextFromImage(uri: Uri) {
        viewModelScope.launch {
            _isLoading.value = true
            _ocrError.value = null
            try {
                val engine = engineFactory.getEngine()
                val result = engine.recognizeText(uri, _ocrLanguage.value)

                if (result != null) {
                    _originalText.value = result.text
                    _recognizedText.value = result.text
                    _recognizedHtml.value = result.html

                    // Auto-translate if target is set
                    _translationTarget.value?.let { targetLang ->
                        translateText(result.text, targetLang)
                    }
                } else {
                    _ocrError.value = "Recognition failed: no result returned"
                }
            } catch (e: Exception) {
                Log.e(TAG, "OCR error", e)
                _ocrError.value = "Recognition failed: ${e.message ?: "Unknown error"}"
                _recognizedText.value = ""
                _recognizedHtml.value = ""
            } finally {
                _isLoading.value = false
            }
        }
    }

    fun recognizeTextFromBitmap(bitmap: Bitmap) {
        viewModelScope.launch {
            _isLoading.value = true
            _ocrError.value = null
            var scaledForOcr: Bitmap? = null
            try {
                // Downscale off the main thread first: a 12MP camera frame is
                // ~48MB, and neither the cache file nor ML Kit needs full res.
                scaledForOcr = withContext(Dispatchers.Default) {
                    downscaleForOcr(bitmap)
                }
                val scaled = scaledForOcr ?: bitmap

                // Persist a compact preview for the UI gallery strip
                val file = withContext(Dispatchers.IO) {
                    val tempFile = File(context.cacheDir, "temp_ocr_image.jpg")
                    FileOutputStream(tempFile).use { out ->
                        scaled.compress(Bitmap.CompressFormat.JPEG, OCR_JPEG_QUALITY, out)
                    }
                    tempFile
                }

                val uri = Uri.fromFile(file)
                _selectedImageUri.value = uri

                // Recognize directly from the bitmap: no full-res JPEG
                // compress -> write -> re-decode round-trip.
                val engine = engineFactory.getEngine()
                val result = engine.recognizeBitmap(scaled, 0, _ocrLanguage.value)

                if (result != null) {
                    _originalText.value = result.text
                    _recognizedText.value = result.text
                    _recognizedHtml.value = result.html

                    // Auto-translate if target is set
                    _translationTarget.value?.let { targetLang ->
                        translateText(result.text, targetLang)
                    }
                } else {
                    _ocrError.value = "Recognition failed: no result returned"
                }
            } catch (e: Exception) {
                Log.e(TAG, "OCR error", e)
                _ocrError.value = "Recognition failed: ${e.message ?: "Unknown error"}"
                _recognizedText.value = ""
                _recognizedHtml.value = ""
            } finally {
                val owned = scaledForOcr
                if (owned != null && owned !== bitmap && !owned.isRecycled) {
                    owned.recycle()
                }
                _isLoading.value = false
            }
        }
    }

    fun batchRecognizeText(uris: List<Uri>) {
        viewModelScope.launch {
            _isLoading.value = true
            _ocrError.value = null
            _batchProgress.value = 0 to uris.size
            val allText = mutableListOf<String>()
            val allHtml = mutableListOf<String>()

            for ((index, uri) in uris.withIndex()) {
                _batchProgress.value = index + 1 to uris.size
                try {
                    val engine = engineFactory.getEngine()
                    val result = engine.recognizeText(uri, _ocrLanguage.value)
                    result?.let {
                        allText.add(it.text)
                        allHtml.add(it.html)
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Batch OCR error for image $index", e)
                }
            }

            val combinedText = allText.joinToString("\n\n---\n\n")
            val combinedHtml = allHtml.joinToString("\n\n<hr>\n\n")
            _originalText.value = combinedText
            _recognizedText.value = combinedText
            _recognizedHtml.value = combinedHtml
            _batchProgress.value = null
            _isLoading.value = false

            // Auto-translate if target is set
            if (combinedText.isNotEmpty()) {
                _translationTarget.value?.let { targetLang ->
                    translateText(combinedText, targetLang)
                }
            }
        }
    }

    fun resetToOriginal() {
        _recognizedText.value = _originalText.value
    }

    /**
     * Recognize text from a file path (used by live camera OCR).
     * Returns Pair(text, html) or null on failure.
     */
    suspend fun recognizeFromFile(filePath: String): Pair<String, String>? {
        return withContext(Dispatchers.IO) {
            try {
                val file = java.io.File(filePath)
                val engine = engineFactory.getEngine()
                val uri = Uri.fromFile(file)
                val result = engine.recognizeText(uri, _ocrLanguage.value)
                Log.d(TAG, "recognizeFromFile: success, ${result?.text?.length ?: 0} chars")
                result?.let { Pair(it.text, it.html) }
            } catch (e: Throwable) {
                Log.e(TAG, "File recognition error", e)
                null
            }
        }
    }

    /**
     * Recognize text directly from a camera Image (YUV_420_888).
     * No bitmap/JPEG conversion — ML Kit handles YUV natively.
     */
    fun recognizeFromMediaImage(image: android.media.Image, rotationDegrees: Int): Pair<String, String>? {
        try {
            val engine = engineFactory.getEngine()
            if (engine is com.omnidocs.app.ocr.MlKitOcrEngine) {
                val result = engine.recognizeFromMediaImage(image, rotationDegrees, _ocrLanguage.value)
                Log.d(TAG, "recognizeFromMediaImage: success, ${result?.text?.length ?: 0} chars")
                return result?.let { Pair(it.text, it.html) }
            }
            return null
        } catch (e: Throwable) {
            Log.e(TAG, "recognizeFromMediaImage error", e)
            return null
        }
    }

    fun translateText(text: String, targetLang: String) {
        viewModelScope.launch {
            try {
                val translated = translationService.translate(text, targetLang)
                _translatedText.value = translated ?: ""
            } catch (e: Exception) {
                Log.e(TAG, "Translation error", e)
                _ocrError.value = "Translation failed: ${e.message ?: "Unknown error"}"
            }
        }
    }
}
