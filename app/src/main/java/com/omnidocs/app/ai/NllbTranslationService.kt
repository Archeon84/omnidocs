package com.omnidocs.app.ai

import android.content.Context
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "NllbTranslationService"

@Singleton
class NllbTranslationService @Inject constructor(
    @ApplicationContext private val context: Context,
    private val modelDownloadManager: ModelDownloadManager,
    private val llamaCppService: LlamaCppService,
    private val modelPreferences: ModelPreferences
) {
    private var modelLoaded = false

    private val supportedLanguages = mapOf(
        // East Asian
        "zho" to "Chinese",
        "jpn" to "Japanese",
        "kor" to "Korean",
        // Southeast Asian
        "msa" to "Malay",
        "tha" to "Thai",
        "vie" to "Vietnamese",
        "ind" to "Indonesian",
        "tgl" to "Filipino",
        "mya" to "Burmese",
        "khm" to "Khmer",
        "lao" to "Lao",
        // South Asian
        "hin" to "Hindi",
        "ben" to "Bengali",
        "tam" to "Tamil",
        "tel" to "Telugu",
        "urd" to "Urdu",
        "mar" to "Marathi",
        "guj" to "Gujarati",
        "kan" to "Kannada",
        "mal" to "Malayalam",
        "sin" to "Sinhala",
        "nep" to "Nepali",
        // Middle Eastern
        "ara" to "Arabic",
        "heb" to "Hebrew",
        "tur" to "Turkish",
        "fas" to "Persian",
        // European
        "eng" to "English",
        "fra" to "French",
        "deu" to "German",
        "spa" to "Spanish",
        "por" to "Portuguese",
        "ita" to "Italian",
        "rus" to "Russian",
        "pol" to "Polish",
        "nld" to "Dutch",
        "swe" to "Swedish",
        "dan" to "Danish",
        "nor" to "Norwegian",
        "fin" to "Finnish",
        "ces" to "Czech",
        "ron" to "Romanian",
        "ukr" to "Ukrainian",
        "ell" to "Greek",
        "hun" to "Hungarian",
        "srp" to "Serbian",
        "hrv" to "Croatian",
        "slk" to "Slovak",
        "slv" to "Slovenian",
        "est" to "Estonian",
        "lav" to "Latvian",
        "lit" to "Lithuanian",
        "cat" to "Catalan",
        "glg" to "Galician",
        "eus" to "Basque",
        "afr" to "Afrikaans"
    )

    fun getSupportedLanguages(): Map<String, String> = supportedLanguages

    /** Convert ISO 639-3 code to ISO 639-1 (2-letter), or null if unknown. */
    fun iso6393To2Code(iso3: String): String? = iso6393To2[iso3]

    fun isModelAvailable(): Boolean {
        // Any downloaded chat model can be used for translation via prompting
        return modelDownloadManager.getDownloadedModels().any { it.isDownloaded }
    }

    private val iso6393To2 = mapOf(
        "eng" to "en", "zho" to "zh", "jpn" to "ja", "kor" to "ko",
        "msa" to "ms", "tha" to "th", "vie" to "vi", "ind" to "id",
        "tgl" to "tl", "mya" to "my", "khm" to "km", "lao" to "lo",
        "hin" to "hi", "ben" to "bn", "tam" to "ta", "tel" to "te",
        "urd" to "ur", "mar" to "mr", "guj" to "gu", "kan" to "kn",
        "mal" to "ml", "sin" to "si", "nep" to "ne",
        "ara" to "ar", "heb" to "he", "tur" to "tr", "fas" to "fa",
        "fra" to "fr", "deu" to "de", "spa" to "es", "por" to "pt",
        "ita" to "it", "rus" to "ru", "pol" to "pl", "nld" to "nl",
        "swe" to "sv", "dan" to "da", "nor" to "no", "fin" to "fi",
        "ces" to "cs", "ron" to "ro", "ukr" to "uk", "ell" to "el",
        "hun" to "hu", "srp" to "sr", "hrv" to "hr", "slk" to "sk",
        "slv" to "sl", "est" to "et", "lav" to "lv", "lit" to "lt",
        "cat" to "ca", "glg" to "gl", "eus" to "eu", "afr" to "af"
    )

    // Max chars per translation chunk. ~800 chars ≈ 200 tokens input, leaving
    // room for the prompt template (~150 tokens) and output within the model's
    // context window. A 1.7B model on phone CPU handles this reliably.
    private val MAX_CHUNK_CHARS = 800

    suspend fun translate(text: String, targetLang: String): String? {
        if (!isModelAvailable()) {
            Log.d(TAG, "No model available for translation")
            return null
        }

        if (!modelLoaded) {
            val loaded = loadModel()
            if (!loaded) {
                Log.e(TAG, "Failed to load model for translation")
                return null
            }
        }

        if (text.isBlank()) return text

        return try {
            val lang = iso6393To2[targetLang] ?: targetLang
            Log.d(TAG, "Translating ${text.length} chars to $targetLang")

            val chunks = splitIntoChunks(text, MAX_CHUNK_CHARS)
            Log.d(TAG, "Split into ${chunks.size} chunk(s)")

            val translatedChunks = mutableListOf<String>()
            for ((index, chunk) in chunks.withIndex()) {
                Log.d(TAG, "Translating chunk ${index + 1}/${chunks.size} (${chunk.length} chars)")
                val prompt = Qwen3PromptBuilder.buildTranslatePrompt(chunk, lang)
                val raw = llamaCppService.generate(prompt, maxTokens = 2048)
                if (raw != null) {
                    val cleaned = AiOutputProcessor.process(raw).trim()
                    translatedChunks.add(cleaned)
                } else {
                    // If a chunk fails, keep the original text so nothing is lost
                    Log.w(TAG, "Chunk ${index + 1} returned null, keeping original")
                    translatedChunks.add(chunk)
                }
            }

            translatedChunks.joinToString("\n\n")
        } catch (e: Exception) {
            Log.e(TAG, "Translation error", e)
            null
        }
    }

    /**
     * Split text into chunks that fit within the model's context window.
     * Tries to split at paragraph boundaries first, then sentence boundaries,
     * and finally at word boundaries as a last resort.
     */
    internal fun splitIntoChunks(text: String, maxChars: Int): List<String> {
        if (text.length <= maxChars) return listOf(text)

        val chunks = mutableListOf<String>()
        var remaining = text

        while (remaining.isNotEmpty()) {
            if (remaining.length <= maxChars) {
                chunks.add(remaining)
                break
            }

            // Find the best split point within maxChars
            var splitAt = -1

            // 1. Try paragraph boundary (double newline)
            val paraBreak = remaining.lastIndexOf("\n\n", maxChars)
            if (paraBreak > maxChars / 2) {
                splitAt = paraBreak + 2
            }

            // 2. Try sentence boundary
            if (splitAt < 0) {
                val sentenceEnd = findLastSentenceEnd(remaining, maxChars)
                if (sentenceEnd > maxChars / 2) {
                    splitAt = sentenceEnd
                }
            }

            // 3. Try line break
            if (splitAt < 0) {
                val lineBreak = remaining.lastIndexOf('\n', maxChars)
                if (lineBreak > maxChars / 2) {
                    splitAt = lineBreak + 1
                }
            }

            // 4. Try word boundary (space)
            if (splitAt < 0) {
                val space = remaining.lastIndexOf(' ', maxChars)
                if (space > maxChars / 2) {
                    splitAt = space + 1
                }
            }

            // 5. Hard cut at maxChars
            if (splitAt < 0) {
                splitAt = maxChars
            }

            chunks.add(remaining.substring(0, splitAt).trim())
            remaining = remaining.substring(splitAt).trim()
        }

        return chunks
    }

    /** Find the index just past the last sentence-ending punctuation within limit. */
    private fun findLastSentenceEnd(text: String, limit: Int): Int {
        val end = minOf(limit, text.length)
        for (i in end - 1 downTo end / 2) {
            val c = text[i]
            if (c == '.' || c == '!' || c == '?' || c == '।' || c == '\n') {
                return i + 1
            }
        }
        return -1
    }

    private suspend fun loadModel(): Boolean = withContext(Dispatchers.IO) {
        try {
            // Use the active model for translation (same model as summarize/proofread/rewrite)
            val model = modelDownloadManager.getDownloadedModels().firstOrNull { it.isDownloaded }
                ?: return@withContext false

            Log.d(TAG, "Using ${model.name} for translation")
            modelLoaded = true
            true
        } catch (e: Exception) {
            Log.e(TAG, "Error loading model", e)
            false
        }
    }
}
