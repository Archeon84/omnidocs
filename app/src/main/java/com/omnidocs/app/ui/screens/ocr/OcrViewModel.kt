package com.omnidocs.app.ui.screens.ocr

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.omnidocs.app.ai.NllbTranslationService
import com.omnidocs.app.ocr.OcrEngineFactory
import com.omnidocs.app.ocr.OcrHtmlBuilder
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
            try {
                // Save bitmap to temp file
                val file = withContext(Dispatchers.IO) {
                    val tempFile = File(context.cacheDir, "temp_ocr_image.jpg")
                    FileOutputStream(tempFile).use { out ->
                        bitmap.compress(Bitmap.CompressFormat.JPEG, 95, out)
                    }
                    tempFile
                }

                val uri = Uri.fromFile(file)
                _selectedImageUri.value = uri

                // Recognize text
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
