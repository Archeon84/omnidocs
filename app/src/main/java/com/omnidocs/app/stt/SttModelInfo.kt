package com.omnidocs.app.stt

/**
 * Metadata for an offline speech recognition model.
 */
data class SttModelInfo(
    val id: String,
    val name: String,
    val description: String,
    val size: String,
    val downloadUrl: String,
    val fileName: String,
    val extractedDirName: String,
    val sha256: String? = null,
    val languages: List<String>,
    val modelType: SttModelType,
    val isDownloaded: Boolean = false
)

enum class SttModelType {
    WHISPER,
    MOONSHINE,
    SENSE_VOICE
}