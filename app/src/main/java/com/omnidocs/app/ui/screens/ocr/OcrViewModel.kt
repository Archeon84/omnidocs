package com.omnidocs.app.ui.screens.ocr

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.Text
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import com.omnidocs.app.ai.NllbTranslationService
import com.omnidocs.app.ocr.ImagePreprocessor
import com.omnidocs.app.ocr.PaddleOcrService
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import javax.inject.Inject
import kotlin.coroutines.resume

private const val TAG = "OcrViewModel"

@HiltViewModel
class OcrViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val paddleOcrService: PaddleOcrService,
    private val imagePreprocessor: ImagePreprocessor,
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

    private val _ocrEngine = MutableStateFlow("mlkit")
    val ocrEngine: StateFlow<String> = _ocrEngine.asStateFlow()

    private val _ocrLanguage = MutableStateFlow("auto")
    val ocrLanguage: StateFlow<String> = _ocrLanguage.asStateFlow()

    private val _translationTarget = MutableStateFlow<String?>(null)
    val translationTarget: StateFlow<String?> = _translationTarget.asStateFlow()

    private val _translatedText = MutableStateFlow("")
    val translatedText: StateFlow<String> = _translatedText.asStateFlow()

    private val _ocrError = MutableStateFlow<String?>(null)
    val ocrError: StateFlow<String?> = _ocrError.asStateFlow()

    /** Available translation target languages: ISO 639-1 code → display name */
    val supportedLanguages: Map<String, String> = translationService.getSupportedLanguages()
        .mapKeys { (iso3, _) -> translationService.iso6393To2Code(iso3) ?: iso3 }

    private val latinRecognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
    private val chineseRecognizer = TextRecognition.getClient(ChineseTextRecognizerOptions.Builder().build())

    override fun onCleared() {
        super.onCleared()
        latinRecognizer.close()
        chineseRecognizer.close()
    }

    fun setOcrEngine(engine: String) {
        _ocrEngine.value = engine
        Log.d(TAG, "OCR engine set to: $engine")
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
                val result = when (_ocrEngine.value) {
                    "paddleocr" -> recognizeWithPaddleOcr(uri)
                    else -> recognizeWithMlKit(uri)
                }
                if (result != null) {
                    _recognizedText.value = result.first
                    _recognizedHtml.value = result.second

                    // Auto-translate if target is set
                    _translationTarget.value?.let { targetLang ->
                        translateText(result.first, targetLang)
                    }
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
                val file = withContext(Dispatchers.IO) {
                    val tempFile = File(context.cacheDir, "temp_ocr_image.jpg")
                    FileOutputStream(tempFile).use { out ->
                        bitmap.compress(Bitmap.CompressFormat.JPEG, 95, out)
                    }
                    tempFile
                }

                val uri = Uri.fromFile(file)
                _selectedImageUri.value = uri

                val result = when (_ocrEngine.value) {
                    "paddleocr" -> recognizeWithPaddleOcr(uri)
                    else -> recognizeWithMlKit(uri)
                }
                if (result != null) {
                    _recognizedText.value = result.first
                    _recognizedHtml.value = result.second

                    // Auto-translate if target is set
                    _translationTarget.value?.let { targetLang ->
                        translateText(result.first, targetLang)
                    }
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

    private suspend fun recognizeWithMlKit(uri: Uri): Pair<String, String>? {
        val recognizer = when (_ocrLanguage.value) {
            "zh", "ja", "ko" -> chineseRecognizer
            else -> latinRecognizer
        }

        // Preprocess image for better OCR accuracy: downsample + grayscale + contrast enhance
        val processedUri = withContext(Dispatchers.IO) {
            try {
                val inputStream = context.contentResolver.openInputStream(uri)
                val bitmap = inputStream?.use { android.graphics.BitmapFactory.decodeStream(it) }
                if (bitmap != null) {
                    val processed = imagePreprocessor.preprocessForOcr(bitmap)
                    // Ensure onDestroy is called when VM clears — but for a temp file
                    // in cacheDir, it will be cleaned up by the OS if needed.
                    val file = imagePreprocessor.saveBitmapToFile(processed, "ocr_processed_${System.currentTimeMillis()}.jpg")
                    bitmap.recycle()
                    processed.recycle()
                    Uri.fromFile(file)
                } else uri
            } catch (e: Exception) {
                Log.w(TAG, "Image preprocessing failed, falling back to original", e)
                uri
            }
        }

        val image = InputImage.fromFilePath(context, processedUri)
        val result = withContext(Dispatchers.IO) {
            suspendCancellableCoroutine<Text?> { cont ->
                recognizer.process(image)
                    .addOnSuccessListener { text -> cont.resume(text) }
                    .addOnFailureListener { cont.resume(null) }
            }
        }
        return if (result != null) {
            val html = result.textBlocks
                .map { block ->
                    block.lines.map { it.text.trim() }
                        .filter { it.isNotEmpty() }
                        .joinToString(" ")
                }
                .filter { it.isNotEmpty() }
                .joinToString("\n") { "<p>$it</p>" }
            Pair(result.text, html)
        } else null
    }

    private suspend fun recognizeWithPaddleOcr(uri: Uri): Pair<String, String>? {
        val text = paddleOcrService.recognizeText(uri) ?: return null
        // Group consecutive non-blank lines into paragraphs separated by blank lines
        val paragraphs = mutableListOf<MutableList<String>>()
        var current = mutableListOf<String>()
        for (line in text.lines()) {
            if (line.isBlank()) {
                if (current.isNotEmpty()) {
                    paragraphs.add(current)
                    current = mutableListOf()
                }
            } else {
                current.add(line.trim())
            }
        }
        if (current.isNotEmpty()) paragraphs.add(current)
        val html = paragraphs.joinToString("\n") { "<p>${it.joinToString(" ")}</p>" }
        return Pair(text, html)
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
