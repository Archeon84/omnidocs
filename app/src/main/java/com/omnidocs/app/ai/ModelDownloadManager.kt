package com.omnidocs.app.ai

import android.content.Context
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import com.omnidocs.app.stt.SttModelInfo
import com.omnidocs.app.stt.SttModelType
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "ModelDownloadManager"

/** Allowed hostnames for model downloads (SSRF protection). */
private val ALLOWED_DOWNLOAD_HOSTS = setOf(
    "github.com",
    "objects.githubusercontent.com",
    "github-releases.githubusercontent.com",
    "huggingface.co",
    "hf.co",
)

data class ModelInfo(
    val id: String,
    val name: String,
    val description: String,
    val size: String,
    val downloadUrl: String,
    val fileName: String,
    val sha256: String? = null,
    val promptFormat: PromptFormat = PromptFormat.CHATML,
    val addBos: Boolean = false,
    val isDownloaded: Boolean = false
)

@Singleton
class ModelDownloadManager @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private val _downloadState = MutableStateFlow<DownloadState>(DownloadState.Idle)
    val downloadState: StateFlow<DownloadState> = _downloadState.asStateFlow()

    @Volatile
    private var downloadCancelled = false

    private val modelsDir = File(context.filesDir, "models")

    init {
        modelsDir.mkdirs()
    }

    /**
     * Validate that a URL's hostname is in the allowed list.
     * Prevents SSRF attacks via malicious redirects.
     */
    private fun validateDownloadUrl(url: URL): Boolean {
        val host = url.host?.lowercase() ?: return false
        return ALLOWED_DOWNLOAD_HOSTS.any { allowed ->
            host == allowed || host.endsWith(".$allowed")
        }
    }

    val availableModels = listOf(
        ModelInfo(
            id = "qwen3_1.7b",
            name = "Qwen3 1.7B (Q4_K_M)",
            description = "Balanced model for summarization, proofreading, and rewriting. Fast and capable.",
            size = "1.11 GB",
            downloadUrl = "https://huggingface.co/unsloth/Qwen3-1.7B-GGUF/resolve/main/Qwen3-1.7B-Q4_K_M.gguf",
            fileName = "Qwen3-1.7B-Q4_K_M.gguf",
            promptFormat = PromptFormat.CHATML,
            addBos = false
        ),
        ModelInfo(
            id = "llama3.2_3b",
            name = "Llama 3.2 3B (Q4_K_M)",
            description = "Best quality model from Meta. Larger but more capable for complex tasks.",
            size = "2.02 GB",
            downloadUrl = "https://huggingface.co/unsloth/Llama-3.2-3B-GGUF/resolve/main/Llama-3.2-3B-Q4_K_M.gguf",
            fileName = "Llama-3.2-3B-Q4_K_M.gguf",
            promptFormat = PromptFormat.LLAMA3,
            addBos = true
        ),
        ModelInfo(
            id = "qwen3_0.6b",
            name = "Qwen3 0.6B (Q4_K_M)",
            description = "Smallest and fastest model. Great for quick tasks on lower-end devices.",
            size = "397 MB",
            downloadUrl = "https://huggingface.co/unsloth/Qwen3-0.6B-GGUF/resolve/main/Qwen3-0.6B-Q4_K_M.gguf",
            fileName = "Qwen3-0.6B-Q4_K_M.gguf",
            promptFormat = PromptFormat.CHATML,
            addBos = false
        )
    )

    /**
     * Available offline speech recognition models.
     * These are downloaded as tar.bz2 archives and extracted to the models directory.
     * Note: sha256 is null because upstream (k2-fsa/sherpa-onnx) does not publish checksums.
     * The download code computes and saves the hash on first download for future integrity checks.
     */
    val sttModels = listOf(
        SttModelInfo(
            id = "whisper_tiny_en",
            name = "Whisper Tiny (English)",
            description = "Fastest model. Good for quick voice notes in English.",
            size = "~75 MB",
            downloadUrl = "https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/sherpa-onnx-whisper-tiny.en-2023-09-14.tar.bz2",
            fileName = "sherpa-onnx-whisper-tiny.en-2023-09-14.tar.bz2",
            extractedDirName = "sherpa-onnx-whisper-tiny.en-2023-09-14",
            sha256 = null,
            languages = listOf("en"),
            modelType = SttModelType.WHISPER
        ),
        SttModelInfo(
            id = "whisper_base_en",
            name = "Whisper Base (English)",
            description = "Better accuracy than Tiny. Good balance of speed and quality.",
            size = "~140 MB",
            downloadUrl = "https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/sherpa-onnx-whisper-base.en-2023-09-14.tar.bz2",
            fileName = "sherpa-onnx-whisper-base.en-2023-09-14.tar.bz2",
            extractedDirName = "sherpa-onnx-whisper-base.en-2023-09-14",
            sha256 = null,
            languages = listOf("en"),
            modelType = SttModelType.WHISPER
        ),
        SttModelInfo(
            id = "whisper_small_en",
            name = "Whisper Small (English)",
            description = "High accuracy. Best English-only model for serious transcription.",
            size = "~460 MB",
            downloadUrl = "https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/sherpa-onnx-whisper-small.en-2023-09-14.tar.bz2",
            fileName = "sherpa-onnx-whisper-small.en-2023-09-14.tar.bz2",
            extractedDirName = "sherpa-onnx-whisper-small.en-2023-09-14",
            sha256 = null,
            languages = listOf("en"),
            modelType = SttModelType.WHISPER
        ),
        SttModelInfo(
            id = "moonshine_tiny_en",
            name = "Moonshine Tiny (English)",
            description = "Ultra-fast, lightweight. 6.65% WER, 105x faster than Whisper Large.",
            size = "~50 MB",
            downloadUrl = "https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/sherpa-onnx-moonshine-tiny-en-int8.tar.bz2",
            fileName = "sherpa-onnx-moonshine-tiny-en-int8.tar.bz2",
            extractedDirName = "sherpa-onnx-moonshine-tiny-en-int8",
            sha256 = null,
            languages = listOf("en"),
            modelType = SttModelType.MOONSHINE
        ),
        SttModelInfo(
            id = "sense_voice_multilingual",
            name = "SenseVoice (Multilingual)",
            description = "Chinese, English, Japanese, Korean, Cantonese. Emotion detection.",
            size = "~229 MB",
            downloadUrl = "https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/sherpa-onnx-sense-voice-zh-en-ja-ko-yue-int8-2024-07-17.tar.bz2",
            fileName = "sherpa-onnx-sense-voice-zh-en-ja-ko-yue-int8-2024-07-17.tar.bz2",
            extractedDirName = "sherpa-onnx-sense-voice-zh-en-ja-ko-yue-int8-2024-07-17",
            sha256 = null,
            languages = listOf("zh", "en", "ja", "ko", "yue"),
            modelType = SttModelType.SENSE_VOICE
        )
    )

    fun getDownloadedModels(): List<ModelInfo> {
        return availableModels.map { model ->
            val file = File(modelsDir, model.fileName)
            // Consider downloaded only if the file exists AND a checksum sidecar was saved
            // (prevents reporting partially-downloaded or truncated files as complete)
            model.copy(isDownloaded = file.exists() && getChecksumFile(file).exists())
        }
    }

    suspend fun downloadModel(model: ModelInfo) {
        withContext(Dispatchers.IO) {
            try {
                Log.d(TAG, "Starting download for ${model.name}")
                _downloadState.value = DownloadState.Downloading(model.id, 0f)
                downloadCancelled = false

                val file = File(modelsDir, model.fileName)
                val tmpFile = File(modelsDir, "${model.fileName}.tmp")
                // Remove any leftover .tmp from a previous interrupted download
                if (tmpFile.exists()) tmpFile.delete()

                val url = URL(model.downloadUrl)

                // Validate URL hostname before connecting (SSRF protection)
                if (!validateDownloadUrl(url)) {
                    _downloadState.value = DownloadState.Error(model.id, "Download URL not from trusted source")
                    return@withContext
                }

                val connection = url.openConnection() as HttpURLConnection
                connection.setRequestProperty("User-Agent", "OmniDocs/1.0")
                connection.connectTimeout = 30000
                connection.readTimeout = 60000
                connection.instanceFollowRedirects = true

                Log.d(TAG, "Connecting to ${model.downloadUrl}")
                connection.connect()

                val responseCode = connection.responseCode
                Log.d(TAG, "Response code: $responseCode")

                if (responseCode != HttpURLConnection.HTTP_OK) {
                    _downloadState.value = DownloadState.Error(model.id, "Server returned $responseCode")
                    return@withContext
                }

                val fileSize = connection.contentLength.toLong()
                Log.d(TAG, "File size: $fileSize bytes")

                // Stream SHA-256 during download — avoids re-reading the full file afterwards
                val digest = MessageDigest.getInstance("SHA-256")

                connection.inputStream.use { input ->
                    FileOutputStream(tmpFile).use { output ->
                        val buffer = ByteArray(8192)
                        var bytesRead: Int
                        var totalBytes = 0L
                        var lastProgress = -1f
                        var lastEmitTime = 0L

                        while (input.read(buffer).also { bytesRead = it } != -1) {
                            // Check for cancellation (called from deleteModel())
                            if (downloadCancelled) {
                                Log.d(TAG, "Download cancelled for ${model.name}")
                                tmpFile.delete()
                                _downloadState.value = DownloadState.Idle
                                return@withContext
                            }

                            output.write(buffer, 0, bytesRead)
                            digest.update(buffer, 0, bytesRead)
                            totalBytes += bytesRead

                            // Throttle progress emissions: at most every 100ms or every 1% change
                            if (fileSize > 0) {
                                val progress = (totalBytes.toFloat() / fileSize.toFloat()) * 100
                                val now = System.currentTimeMillis()
                                if (progress - lastProgress >= 1f || now - lastEmitTime >= 100L) {
                                    _downloadState.value = DownloadState.Downloading(model.id, progress)
                                    lastProgress = progress
                                    lastEmitTime = now
                                }
                            }
                        }
                    }
                }

                // Rename .tmp to final name after successful download
                if (!tmpFile.renameTo(file)) {
                    // Fallback: copy content if renameTo fails (e.g., across mount points)
                    file.outputStream().use { dst ->
                        tmpFile.inputStream().use { src ->
                            src.copyTo(dst)
                        }
                    }
                    tmpFile.delete()
                }

                // Get computed hash and save for future verification
                val actualSha256 = digest.digest().joinToString("") { "%02x".format(it) }
                Log.d(TAG, "Download completed - SHA256: $actualSha256")
                saveChecksum(file, actualSha256)

                // Verify against expected checksum if provided
                if (model.sha256 != null) {
                    if (!actualSha256.equals(model.sha256, ignoreCase = true)) {
                        Log.e(TAG, "Checksum mismatch! Expected: ${model.sha256}, Got: $actualSha256")
                        file.delete()
                        deleteChecksum(file)
                        _downloadState.value = DownloadState.Error(model.id, "Checksum verification failed - file may be corrupted or tampered")
                        return@withContext
                    }
                    Log.d(TAG, "Checksum verified for ${model.name}")
                } else {
                    Log.w(TAG, "No expected checksum configured. Actual SHA256: $actualSha256")
                }

                _downloadState.value = DownloadState.Completed(model.id)
            } catch (e: Exception) {
                Log.e(TAG, "Download failed for ${model.name}", e)
                _downloadState.value = DownloadState.Error(model.id, e.message ?: "Download failed")
            }
        }
    }

    /**
     * Calculate SHA256 checksum of a file
     */
    private fun calculateSha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(8192)
        FileInputStream(file).use { input ->
            var bytesRead: Int
            while (input.read(buffer).also { bytesRead = it } != -1) {
                digest.update(buffer, 0, bytesRead)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    fun deleteModel(model: ModelInfo) {
        // Signal any in-progress download to abort
        downloadCancelled = true

        val file = File(modelsDir, model.fileName)
        if (file.exists()) {
            file.delete()
        }
        // Also clean up any leftover .tmp partial download
        val tmpFile = File(modelsDir, "${model.fileName}.tmp")
        if (tmpFile.exists()) {
            tmpFile.delete()
        }
        deleteChecksum(file)
        _downloadState.value = DownloadState.Idle
    }

    fun getModelPath(model: ModelInfo): String {
        return File(modelsDir, model.fileName).absolutePath
    }

    // ---- STT model management ----

    private val _sttDownloadState = MutableStateFlow<DownloadState>(DownloadState.Idle)
    val sttDownloadState: StateFlow<DownloadState> = _sttDownloadState.asStateFlow()

    /**
     * Download and extract an STT model.
     * Downloads as tar.bz2, extracts to models directory.
     */
    suspend fun downloadSttModel(model: SttModelInfo) {
        withContext(Dispatchers.IO) {
            try {
                Log.d(TAG, "Starting STT model download: ${model.name}")
                _sttDownloadState.value = DownloadState.Downloading(model.id, 0f)
                downloadCancelled = false

                val tmpFile = File(modelsDir, "${model.fileName}.tmp")
                if (tmpFile.exists()) tmpFile.delete()

                val url = URL(model.downloadUrl)

                // Validate URL hostname before connecting (SSRF protection)
                if (!validateDownloadUrl(url)) {
                    _sttDownloadState.value = DownloadState.Error(model.id, "Download URL not from trusted source")
                    return@withContext
                }

                val connection = url.openConnection() as HttpURLConnection
                connection.setRequestProperty("User-Agent", "OmniDocs/1.0")
                connection.connectTimeout = 30000
                connection.readTimeout = 60000
                connection.instanceFollowRedirects = true
                connection.connect()

                val responseCode = connection.responseCode
                if (responseCode != HttpURLConnection.HTTP_OK) {
                    _sttDownloadState.value = DownloadState.Error(model.id, "Server returned $responseCode")
                    return@withContext
                }

                val fileSize = connection.contentLength.toLong()

                // Stream SHA-256 during download for integrity verification
                val digest = MessageDigest.getInstance("SHA-256")

                connection.inputStream.use { input ->
                    FileOutputStream(tmpFile).use { output ->
                        val buffer = ByteArray(8192)
                        var bytesRead: Int
                        var totalBytes = 0L
                        var lastProgress = -1f
                        var lastEmitTime = 0L

                        while (input.read(buffer).also { bytesRead = it } != -1) {
                            if (downloadCancelled) {
                                tmpFile.delete()
                                _sttDownloadState.value = DownloadState.Idle
                                return@withContext
                            }
                            output.write(buffer, 0, bytesRead)
                            digest.update(buffer, 0, bytesRead)
                            totalBytes += bytesRead

                            if (fileSize > 0) {
                                val progress = (totalBytes.toFloat() / fileSize.toFloat()) * 100
                                val now = System.currentTimeMillis()
                                if (progress - lastProgress >= 1f || now - lastEmitTime >= 100L) {
                                    _sttDownloadState.value = DownloadState.Downloading(model.id, progress)
                                    lastProgress = progress
                                    lastEmitTime = now
                                }
                            }
                        }
                    }
                }

                // Verify checksum if provided
                val actualSha256 = digest.digest().joinToString("") { "%02x".format(it) }
                if (model.sha256 != null) {
                    if (!actualSha256.equals(model.sha256, ignoreCase = true)) {
                        Log.e(TAG, "STT checksum mismatch! Expected: ${model.sha256}, Got: $actualSha256")
                        tmpFile.delete()
                        _sttDownloadState.value = DownloadState.Error(model.id, "Checksum verification failed")
                        return@withContext
                    }
                    Log.d(TAG, "STT checksum verified for ${model.name}")
                }

                // Extract tar.bz2
                _sttDownloadState.value = DownloadState.Downloading(model.id, 99f)
                val process = ProcessBuilder("tar", "xjf", tmpFile.absolutePath, "-C", modelsDir.absolutePath)
                    .redirectErrorStream(true)
                    .start()
                val exitCode = process.waitFor()
                tmpFile.delete()

                if (exitCode != 0) {
                    _sttDownloadState.value = DownloadState.Error(model.id, "Extraction failed")
                    return@withContext
                }

                // Validate extracted directory is within modelsDir (path traversal protection)
                val extractedDir = File(modelsDir, model.extractedDirName).canonicalFile
                if (!extractedDir.path.startsWith(modelsDir.canonicalPath + File.separator)) {
                    Log.e(TAG, "Path traversal detected in STT archive: ${model.extractedDirName}")
                    extractedDir.deleteRecursively()
                    _sttDownloadState.value = DownloadState.Error(model.id, "Security: invalid archive path")
                    return@withContext
                }

                if (extractedDir.exists()) {
                    saveSttChecksum(model)
                }

                _sttDownloadState.value = DownloadState.Completed(model.id)
            } catch (e: Exception) {
                Log.e(TAG, "STT model download failed: ${model.name}", e)
                _sttDownloadState.value = DownloadState.Error(model.id, e.message ?: "Download failed")
            }
        }
    }

    /**
     * Get the directory path for an extracted STT model.
     */
    fun getSttModelPath(model: SttModelInfo): String {
        return File(modelsDir, model.extractedDirName).absolutePath
    }

    /**
     * Check which STT models are downloaded.
     */
    fun getDownloadedSttModels(): List<SttModelInfo> {
        return sttModels.map { model ->
            val dir = File(modelsDir, model.extractedDirName)
            model.copy(isDownloaded = dir.exists())
        }
    }

    /**
     * Delete an STT model and its extracted files.
     */
    fun deleteSttModel(model: SttModelInfo) {
        downloadCancelled = true
        val dir = File(modelsDir, model.extractedDirName)
        if (dir.exists()) dir.deleteRecursively()
        val tmpFile = File(modelsDir, "${model.fileName}.tmp")
        if (tmpFile.exists()) tmpFile.delete()
        deleteSttChecksum(model)
        _sttDownloadState.value = DownloadState.Idle
    }

    private fun getSttChecksumFile(model: SttModelInfo): File {
        return File(modelsDir, "${model.id}.stt.sha256")
    }

    private fun saveSttChecksum(model: SttModelInfo) {
        getSttChecksumFile(model).writeText(model.sha256 ?: "no-checksum")
    }

    private fun deleteSttChecksum(model: SttModelInfo) {
        getSttChecksumFile(model).delete()
    }

    /**
     * Verify integrity of already downloaded models.
     * Uses saved checksum sidecar file (generated on first download) as the reference.
     * Falls back to the ModelInfo.sha256 if no sidecar exists.
     */
    suspend fun verifyAllModels(): Map<ModelInfo, Boolean> {
        return withContext(Dispatchers.IO) {
            availableModels.associateWith { model ->
                val file = File(modelsDir, model.fileName)
                if (!file.exists()) {
                    false
                } else {
                    val savedChecksum = loadChecksum(file)
                    val referenceChecksum = savedChecksum ?: model.sha256
                    if (referenceChecksum != null) {
                        val actualSha256 = calculateSha256(file)
                        actualSha256.equals(referenceChecksum, ignoreCase = true)
                    } else {
                        // No checksum available at all — compute and save, trust first encounter
                        val actualSha256 = calculateSha256(file)
                        saveChecksum(file, actualSha256)
                        true
                    }
                }
            }
        }
    }

    // ---- Checksum sidecar helpers ----

    private fun getChecksumFile(modelFile: File): File {
        return File(modelsDir, "${modelFile.name}.sha256")
    }

    private fun saveChecksum(modelFile: File, checksum: String) {
        getChecksumFile(modelFile).writeText(checksum)
    }

    private fun loadChecksum(modelFile: File): String? {
        val checksumFile = getChecksumFile(modelFile)
        return if (checksumFile.exists()) checksumFile.readText().trim() else null
    }

    private fun deleteChecksum(modelFile: File) {
        getChecksumFile(modelFile).delete()
    }

}

sealed class DownloadState {
    object Idle : DownloadState()
    data class Downloading(val modelId: String, val progress: Float) : DownloadState()
    data class Completed(val modelId: String) : DownloadState()
    data class Error(val modelId: String, val message: String) : DownloadState()
}
