package com.omnidocs.app.stt

data class SttResult(
    val text: String,
    val confidence: Float = 1.0f
)

fun interface SttPartialCallback {
    fun onPartialResult(text: String)
}

interface SttEngine {
    fun startListening(languageCode: String, partialCallback: SttPartialCallback? = null)
    fun stopListening(): SttResult?
    fun cancelListening()
    fun isListening(): Boolean
    fun release()
}