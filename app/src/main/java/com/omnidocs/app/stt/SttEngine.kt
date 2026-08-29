package com.omnidocs.app.stt

data class SttResult(
    val text: String,
    val confidence: Float = 1.0f
)

fun interface SttPartialCallback {
    fun onPartialResult(text: String)
}

interface SttEngine {
    /**
     * @param onAudio raw 16-bit PCM mono bytes as captured. Delivered on the
     *   engine's recording thread; implementations must not reorder.
     */
    fun startListening(
        languageCode: String,
        partialCallback: SttPartialCallback? = null,
        onAudio: ((ByteArray) -> Unit)? = null
    )
    fun stopListening(): SttResult?
    fun cancelListening()
    fun isListening(): Boolean
    fun release()
}