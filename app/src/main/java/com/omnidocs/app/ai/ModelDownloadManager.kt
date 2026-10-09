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
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

private const val TAG = "ModelDownloadManager"

/** Allowed hostnames for model downloads (SSRF protection). */
private val ALLOWED_DOWNLOAD_HOSTS = setOf(
    "github.com",
    "objects.githubusercontent.com",
    "github-releases.githubusercontent.com",
    "release-assets.githubusercontent.com",
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
    val isDownloaded: Boolean = false,
    /** True for reasoning models that emit <think> blocks unless told otherwise. */
    val isThinkingModel: Boolean = false,
    /** Minimum recommended RAM in GB to prevent Low Memory Killer (LMK) kills. */
    val minRamGb: Int = 4
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

    /**
     * Open a connection with manual redirect handling.
     * Re-validates hostname at each redirect hop against ALLOWED_DOWNLOAD_HOSTS.
     * Max 5 hops to prevent infinite redirect loops.
     */
    private fun openConnectionWithRedirectValidation(
        initialUrl: URL,
        extraHeaders: Map<String, String> = emptyMap()
    ): HttpURLConnection {
        var currentUrl = initialUrl
        val maxRedirects = 5

        for (hop in 0..maxRedirects) {
            if (!validateDownloadUrl(currentUrl)) {
                throw SecurityException("Redirect to disallowed host: ${currentUrl.host}")
            }

            val conn = currentUrl.openConnection() as HttpURLConnection
            conn.instanceFollowRedirects = false
            conn.setRequestProperty("User-Agent", "OmniDocs/1.0")
            for ((key, value) in extraHeaders) {
                conn.setRequestProperty(key, value)
            }
            conn.connectTimeout = 30000
            conn.readTimeout = 60000
            conn.connect()

            val code = conn.responseCode
            if (code in 301..308) {
                val location = conn.getHeaderField("Location") ?: throw SecurityException("Redirect without Location header")
                conn.disconnect()
                currentUrl = URL(currentUrl, location)
                continue
            }

            return conn
        }

        throw SecurityException("Too many redirects (>$maxRedirects)")
    }

    val availableModels = listOf(
        ModelInfo(
            id = "qwen_2_5_0_5b",
            name = "Qwen 2.5 0.5B (Ultra-Light)",
            description = "High-efficiency sub-1B model for budget devices (<4GB RAM). Fast summarization, multilingual, minimal battery drain.",
            size = "~546 MB",
            downloadUrl = "https://huggingface.co/litert-community/Qwen2.5-0.5B-Instruct/resolve/main/Qwen2.5-0.5B-Instruct_multi-prefill-seq_q8_ekv1280.task",
            fileName = "Qwen2.5-0.5B-Instruct_multi-prefill-seq_q8_ekv1280.task",
            promptFormat = PromptFormat.CHATML,
            addBos = false,
            minRamGb = 3
        ),
        ModelInfo(
            id = "gemma_4_e2b",
            name = "Gemma 4 E2B (Default)",
            description = "Google's lightweight on-device model for text note intelligence, fast RAG Q&A, and auto-tagging.",
            size = "~1.1 GB",
            downloadUrl = "https://huggingface.co/litert-community/gemma-4-E2B-it-litert-lm/resolve/main/gemma-4-E2B-it.litertlm",
            fileName = "gemma-4-E2B-it.litertlm",
            promptFormat = PromptFormat.GEMMA,
            addBos = false,
            minRamGb = 6
        ),
        ModelInfo(
            id = "gemma_4_e4b",
            name = "Gemma 4 E4B (Pro)",
            description = "High-capacity reasoning model from Google for complex syntheses, multi-document research, and deep analysis.",
            size = "~3.2 GB",
            downloadUrl = "https://huggingface.co/litert-community/gemma-4-E4B-it-litert-lm/resolve/main/gemma-4-E4B-it.litertlm",
            fileName = "gemma-4-E4B-it.litertlm",
            promptFormat = PromptFormat.GEMMA,
            addBos = false,
            minRamGb = 8
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
            id = "whisper_small",
            name = "Whisper Small (Multilingual)",
            description = "Multilingual model supporting 99 languages including Malay. Good accuracy, moderate size.",
            size = "~609 MB",
            downloadUrl = "https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/sherpa-onnx-whisper-small.tar.bz2",
            fileName = "sherpa-onnx-whisper-small.tar.bz2",
            extractedDirName = "sherpa-onnx-whisper-small",
            sha256 = "486a46afbb7ba798507190ffe02fea2dd726049af212e774537efac6afb210a6",
            languages = listOf("multilingual"),
            modelType = SttModelType.WHISPER
        ),
        SttModelInfo(
            id = "whisper_large_v3",
            name = "Whisper Large V3 (Multilingual)",
            description = "Best accuracy. Supports 99 languages including Malay. Large download (~3GB).",
            size = "~3 GB",
            downloadUrl = "https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/sherpa-onnx-whisper-large-v3.tar.bz2",
            fileName = "sherpa-onnx-whisper-large-v3.tar.bz2",
            extractedDirName = "sherpa-onnx-whisper-large-v3",
            sha256 = "2d0e134b3b5fc4a0533baf24a0c9d473b629aa47f030af0a165a05f461df7a03",
            languages = listOf("multilingual"),
            modelType = SttModelType.WHISPER
        ),
        SttModelInfo(
            id = "moonshine_tiny_en",
            name = "Moonshine Tiny (English)",
            description = "Ultra-fast, lightweight. 105x faster than Whisper Large. English only.",
            size = "~50 MB",
            downloadUrl = "https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/sherpa-onnx-moonshine-tiny-en-int8.tar.bz2",
            fileName = "sherpa-onnx-moonshine-tiny-en-int8.tar.bz2",
            extractedDirName = "sherpa-onnx-moonshine-tiny-en-int8",
            sha256 = null,
            languages = listOf("en"),
            modelType = SttModelType.MOONSHINE
        )
    )

    /**
     * Available offline embedding models (separate from `availableModels`, which
     * feed `resolveActiveModel` for generation — an embedding GGUF must never be
     * selected as a generative model). multilingual-e5-small produces 384-dim
     * vectors and needs "query:"/"passage:" prefixes (applied in EmbeddingService).
     *
     * Model source: cstr/multilingual-e5-small-GGUF (standard llama.cpp conversion).
     * This repo keeps all 1-D norm/bias tensors at F32 and quantizes only the 2-D
     * weight matrices. Do NOT swap to fully-quantized GGUFs (e.g. the old
     * milimyname Q8_0 build where even token_types and every bias were Q8_0): ggml's
     * scalar binary ops only support f32/f16/bf16, so such models abort on every
     * embed with "binary_op: unsupported types".
     */
    val embeddingModels = listOf(
        ModelInfo(
            id = "multilingual_e5_small",
            name = "Multilingual E5 Small (Q8_0)",
            description = "384-dim multilingual embedding model for semantic search. Covers Malay and 99+ languages.",
            size = "~126 MB",
            downloadUrl = "https://huggingface.co/cstr/multilingual-e5-small-GGUF/resolve/main/multilingual-e5-small-q8_0.gguf",
            fileName = "multilingual-e5-small-q8_0.gguf",
            // Verified 2026-09-07: upstream republished the file under resolve/main
            // (131,624,960 bytes); the previous hash looped every install into a
            // download → mismatch → delete cycle with no UI signal.
            sha256 = "0a34067a40f25d3149b36885faa62bee0e5284d0f9edc102acfc00e115d953e8"
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

    suspend fun downloadModel(model: ModelInfo) = downloadSingleGguf(model, _downloadState)

    suspend fun downloadEmbeddingModel(model: ModelInfo) = downloadSingleGguf(model, _embeddingDownloadState)

    /**
     * Shared single-GGUF download: streams to a .tmp file, hashes SHA-256 during
     * download, verifies against the expected checksum (or saves the computed one
     * when none is configured), and publishes progress to [state]. Used by both
     * the generative and embedding model registries.
     */
    /** One mutex per destination file: two concurrent downloads of the same
     * model used to interleave into one .tmp and poison it (then trusted). */
    private val downloadMutexes = ConcurrentHashMap<String, Mutex>()

    private suspend fun downloadSingleGguf(model: ModelInfo, state: MutableStateFlow<DownloadState>) {
        val fileMutex = downloadMutexes.getOrPut(model.fileName) { Mutex() }
        fileMutex.withLock {
            downloadSingleGgufLocked(model, state)
        }
    }

    private suspend fun downloadSingleGgufLocked(model: ModelInfo, state: MutableStateFlow<DownloadState>) {
        withContext(Dispatchers.IO) {
            var connection: HttpURLConnection? = null
            try {
                Log.d(TAG, "Starting download for ${model.name}")
                state.value = DownloadState.Downloading(model.id, 0f)
                downloadCancelled = false
                ModelDownloadService.start(context, model.name)

                val file = File(modelsDir, model.fileName)
                val tmpFile = File(modelsDir, "${model.fileName}.tmp")

                // Skip when a verified complete file is already in place.
                if (file.exists() && isCompleteAndVerified(file, model.sha256)) {
                    Log.d(TAG, "File already downloaded and verified for ${model.name}")
                    state.value = DownloadState.Completed(model.id)
                    if (tmpFile.exists()) tmpFile.delete()
                    return@withContext
                }

                // Recognize pre-existing files without a (valid) checksum sidecar
                // — older builds, sideloads, healed sidecars. Hash once instead
                // of re-downloading hundreds of MB; without this the UI reports
                // "not downloaded" forever despite the file sitting on disk.
                // Skipped when a .tmp resume candidate exists (interrupted
                // download takes the resume path below instead).
                if (file.exists() && file.length() > 0L && !tmpFile.exists()) {
                    try {
                        Log.d(TAG, "Verifying pre-existing file for ${model.name}")
                        val actual = calculateSha256(file)
                        if (model.sha256 == null || actual.equals(model.sha256, ignoreCase = true)) {
                            Log.d(TAG, "Pre-existing file verified for ${model.name}, saving sidecar")
                            saveChecksum(file, actual)
                            state.value = DownloadState.Completed(model.id)
                            return@withContext
                        }
                        Log.w(TAG, "Pre-existing file corrupt for ${model.name}, re-downloading")
                        file.delete()
                        deleteChecksum(file)
                    } catch (e: Exception) {
                        Log.w(TAG, "Could not verify pre-existing file for ${model.name}", e)
                    }
                }

                // Resume: keep a leftover .tmp and continue where it stopped
                // instead of re-downloading gigabytes from byte 0.
                val resumeOffset = if (tmpFile.exists()) tmpFile.length() else 0L

                val url = URL(model.downloadUrl)

                Log.d(TAG, "Connecting to ${model.downloadUrl} (resume from $resumeOffset)")
                connection = try {
                    openConnectionWithRedirectValidation(
                        url,
                        if (resumeOffset > 0) mapOf("Range" to "bytes=$resumeOffset-") else emptyMap()
                    )
                } catch (e: SecurityException) {
                    state.value = DownloadState.Error(model.id, e.message ?: "URL validation failed")
                    return@withContext
                }

                val responseCode = connection.responseCode
                Log.d(TAG, "Response code: $responseCode")

                val resumed = responseCode == HttpURLConnection.HTTP_PARTIAL
                if (!resumed && resumeOffset > 0) {
                    // Server ignored Range: stale prefix is useless, restart clean.
                    Log.w(TAG, "Server does not support resume; restarting download from byte 0")
                    tmpFile.delete()
                }
                if (responseCode != HttpURLConnection.HTTP_OK && !resumed) {
                    state.value = DownloadState.Error(model.id, "Server returned $responseCode")
                    return@withContext
                }

                val startOffset = if (resumed) resumeOffset else 0L
                val remaining = connection.contentLength.toLong()
                val fileSize = if (resumed && remaining > 0) startOffset + remaining else remaining
                Log.d(TAG, "File size: $fileSize bytes (resumed=$resumed)")

                // Stream SHA-256 during download — avoids re-reading the full file afterwards.
                // When resuming, hash the existing prefix first so the final digest
                // covers the whole file.
                val digest = MessageDigest.getInstance("SHA-256")
                if (resumed) {
                    FileInputStream(tmpFile).use { prefix ->
                        val buffer = ByteArray(8192)
                        var bytesRead: Int
                        while (prefix.read(buffer).also { bytesRead = it } != -1) {
                            digest.update(buffer, 0, bytesRead)
                        }
                    }
                }

                connection.inputStream.use { input ->
                    FileOutputStream(tmpFile, resumed).use { output ->
                        val buffer = ByteArray(8192)
                        var bytesRead: Int
                        var totalBytes = startOffset
                        var lastProgress = -1f
                        var lastEmitTime = 0L

                        while (input.read(buffer).also { bytesRead = it } != -1) {
                            // Check for cancellation (called from deleteModel())
                            if (downloadCancelled) {
                                Log.d(TAG, "Download cancelled for ${model.name}")
                                tmpFile.delete()
                                state.value = DownloadState.Idle
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
                                    state.value = DownloadState.Downloading(model.id, progress)
                                    lastProgress = progress
                                    lastEmitTime = now
                                }
                            }
                        }

                        // Truncated stream: keep the .tmp for resume, do NOT promote.
                        if (fileSize > 0 && totalBytes != fileSize) {
                            Log.e(TAG, "Incomplete download for ${model.name}: $totalBytes/$fileSize bytes")
                            state.value = DownloadState.Error(
                                model.id,
                                "Download incomplete ($totalBytes of $fileSize bytes) — retry to resume"
                            )
                            return@withContext
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
                        state.value = DownloadState.Error(model.id, "Checksum verification failed - file may be corrupted or tampered")
                        return@withContext
                    }
                    Log.d(TAG, "Checksum verified for ${model.name}")
                } else {
                    Log.w(TAG, "No expected checksum configured. Actual SHA256: $actualSha256")
                }

                state.value = DownloadState.Completed(model.id)
            } catch (e: Exception) {
                Log.e(TAG, "Download failed for ${model.name}", e)
                state.value = DownloadState.Error(model.id, e.message ?: "Download failed")
            } finally {
                try {
                    connection?.disconnect()
                } catch (_: Exception) {
                }
            }
        }
    }

    /**
     * True when [file] exists and its saved sidecar matches [expectedSha256]
     * (or any sidecar exists when no checksum is configured). Lets repeat
     * taps complete instantly instead of re-downloading gigabytes.
     */
    private fun isCompleteAndVerified(file: File, expectedSha256: String?): Boolean {
        if (!file.exists() || file.length() == 0L) return false
        val saved = loadChecksum(file) ?: return false
        if (expectedSha256 != null) {
            return saved.equals(expectedSha256, ignoreCase = true)
        }
        return true
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
        ModelDownloadService.stop(context)

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
        // Serialize same-file downloads (see downloadSingleGguf).
        downloadMutexes.getOrPut("stt:${model.fileName}") { Mutex() }.withLock {
            downloadSttModelLocked(model)
        }
    }

    private suspend fun downloadSttModelLocked(model: SttModelInfo) {
        withContext(Dispatchers.IO) {
            var connection: HttpURLConnection? = null
            try {
                Log.d(TAG, "Starting STT model download: ${model.name}")
                _sttDownloadState.value = DownloadState.Downloading(model.id, 0f)
                downloadCancelled = false
                ModelDownloadService.start(context, model.name)

                val tmpFile = File(modelsDir, "${model.fileName}.tmp")
                // Resume interrupted downloads instead of restarting from byte 0.
                val resumeOffset = if (tmpFile.exists()) tmpFile.length() else 0L

                val url = URL(model.downloadUrl)

                connection = try {
                    openConnectionWithRedirectValidation(
                        url,
                        if (resumeOffset > 0) mapOf("Range" to "bytes=$resumeOffset-") else emptyMap()
                    )
                } catch (e: SecurityException) {
                    _sttDownloadState.value = DownloadState.Error(model.id, e.message ?: "URL validation failed")
                    return@withContext
                }

                val responseCode = connection.responseCode
                val resumed = responseCode == HttpURLConnection.HTTP_PARTIAL
                if (!resumed && resumeOffset > 0) {
                    Log.w(TAG, "Server does not support resume; restarting STT download from byte 0")
                    tmpFile.delete()
                }
                if (responseCode != HttpURLConnection.HTTP_OK && !resumed) {
                    _sttDownloadState.value = DownloadState.Error(model.id, "Server returned $responseCode")
                    return@withContext
                }

                val startOffset = if (resumed) resumeOffset else 0L
                val remaining = connection.contentLength.toLong()
                val fileSize = if (resumed && remaining > 0) startOffset + remaining else remaining

                // Stream SHA-256 during download for integrity verification.
                // On resume, hash the existing prefix first.
                val digest = MessageDigest.getInstance("SHA-256")
                if (resumed) {
                    FileInputStream(tmpFile).use { prefix ->
                        val buffer = ByteArray(8192)
                        var bytesRead: Int
                        while (prefix.read(buffer).also { bytesRead = it } != -1) {
                            digest.update(buffer, 0, bytesRead)
                        }
                    }
                }

                connection.inputStream.use { input ->
                    FileOutputStream(tmpFile, resumed).use { output ->
                        val buffer = ByteArray(8192)
                        var bytesRead: Int
                        var totalBytes = startOffset
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

                        // Truncated stream: keep .tmp for resume, do NOT extract.
                        if (fileSize > 0 && totalBytes != fileSize) {
                            Log.e(TAG, "Incomplete STT download for ${model.name}: $totalBytes/$fileSize bytes")
                            _sttDownloadState.value = DownloadState.Error(
                                model.id,
                                "Download incomplete ($totalBytes of $fileSize bytes) — retry to resume"
                            )
                            return@withContext
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
            } finally {
                try {
                    connection?.disconnect()
                } catch (_: Exception) {
                }
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

    // ---- Embedding model management ----

    private val _embeddingDownloadState = MutableStateFlow<DownloadState>(DownloadState.Idle)
    val embeddingDownloadState: StateFlow<DownloadState> = _embeddingDownloadState.asStateFlow()

    /**
     * Check which embedding models are downloaded.
     */
    fun getDownloadedEmbeddingModels(): List<ModelInfo> {
        return embeddingModels.map { model ->
            val file = File(modelsDir, model.fileName)
            model.copy(isDownloaded = file.exists() && getChecksumFile(file).exists())
        }
    }

    /**
     * Delete an embedding model and its checksum sidecar.
     */
    fun deleteEmbeddingModel(model: ModelInfo) {
        downloadCancelled = true
        val file = File(modelsDir, model.fileName)
        if (file.exists()) {
            file.delete()
        }
        val tmpFile = File(modelsDir, "${model.fileName}.tmp")
        if (tmpFile.exists()) {
            tmpFile.delete()
        }
        deleteChecksum(file)
        _embeddingDownloadState.value = DownloadState.Idle
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
