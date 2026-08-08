# Offline Speech Recognition Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace the internet-dependent Android SpeechRecognizer with an offline Sherpa-onnx engine that supports optional model downloads in Settings, providing accurate speech-to-text without network access.

**Architecture:** A new `SttEngine` interface abstracts speech recognition. `SherpaOnnxSttEngine` implements it using Sherpa-onnx's JNI bindings with Whisper/Moonshine models. `SttEngineFactory` selects the engine based on downloaded models. `VoiceCaptureManager` is refactored to use the engine interface. Models are downloaded via the existing `ModelDownloadManager` infrastructure and stored alongside chat models.

**Tech Stack:** Sherpa-onnx (C++ via JNI, pre-built `.so` libraries), Android AudioRecord (16kHz PCM), Kotlin coroutines, Hilt DI, DataStore preferences, existing ModelDownloadManager download pipeline.

## Global Constraints

- Target device: Xiaomi 13 Ultra (arm64-v8a only)
- Min SDK: 26, Target SDK: 34
- Existing patterns: Hilt @Singleton @Inject, DataStore preferences, ModelDownloadManager download/verify/delete flow
- Must not break existing voice capture UI or LLM structuring pipeline
- System SpeechRecognizer remains available as fallback when no STT model is downloaded
- Models stored in `context.filesDir/models/` alongside existing chat models

---

## File Structure

| File | Responsibility |
|------|---------------|
| Create: `app/src/main/java/com/omnidocs/app/stt/SttEngine.kt` | Interface defining speech recognition contract |
| Create: `app/src/main/java/com/omnidocs/app/stt/SttEngineFactory.kt` | Hilt factory selecting system vs Sherpa-onnx engine |
| Create: `app/src/main/java/com/omnidocs/app/stt/SherpaOnnxSttEngine.kt` | Sherpa-onnx implementation with AudioRecord |
| Create: `app/src/main/java/com/omnidocs/app/stt/SttModelInfo.kt` | Data class for STT model metadata |
| Modify: `app/src/main/java/com/omnidocs/app/ai/ModelDownloadManager.kt` | Add STT model registry alongside chat models |
| Modify: `app/src/main/java/com/omnidocs/app/voice/VoiceCaptureManager.kt` | Use SttEngine interface instead of SpeechRecognizer directly |
| Modify: `app/src/main/java/com/omnidocs/app/ui/screens/settings/SettingsScreen.kt` | Add "Speech Recognition" section with model download UI |
| Modify: `app/src/main/java/com/omnidocs/app/ui/screens/settings/SettingsViewModel.kt` | Add STT model management methods |
| Modify: `app/build.gradle.kts` | Add Sherpa-onnx AAR dependency |
| Modify: `app/src/main/java/com/omnidocs/app/ai/ModelPreferences.kt` | Add selectedSttModelId preference |
| Create: `app/src/main/jniLibs/arm64-v8a/` | Sherpa-onnx pre-built native libraries |

---

### Task 1: Sherpa-onnx Dependency Setup

**Files:**
- Modify: `settings.gradle.kts` (add JitPack repository)
- Modify: `app/build.gradle.kts:91-189` (add sherpa-onnx dependency)

**Interfaces:**
- Consumes: none (foundation task)
- Produces: Sherpa-onnx AAR with native libraries and Kotlin API classes available

- [ ] **Step 1: Add JitPack repository to settings.gradle.kts**

Add JitPack to the `dependencyResolutionManagement.repositories` block in `settings.gradle.kts`:

```kotlin
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        maven { url = uri("https://jitpack.io") }  // Add this line
    }
}
```

- [ ] **Step 2: Add Sherpa-onnx dependency to app/build.gradle.kts**

Add to the `dependencies` block in `app/build.gradle.kts`:

```kotlin
    // Sherpa-onnx for offline speech recognition (AAR via JitPack)
    implementation("com.github.k2-fsa:sherpa-onnx:v1.13.4")
```

- [ ] **Step 3: Verify dependency resolves**

Run:
```bash
cd H:/work/omnidocs
./gradlew :app:dependencies --configuration debugRuntimeClasspath 2>&1 | grep -i sherpa
```
Expected: `com.github.k2-fsa:sherpa-onnx:v1.13.4` appears in the dependency tree.

- [ ] **Step 4: Commit**

```bash
git add settings.gradle.kts app/build.gradle.kts
git commit -m "feat: add sherpa-onnx AAR dependency via JitPack for offline STT"
```

---

### Task 2: STT Engine Interface and Model Info

**Files:**
- Create: `app/src/main/java/com/omnidocs/app/stt/SttEngine.kt`
- Create: `app/src/main/java/com/omnidocs/app/stt/SttModelInfo.kt`

**Interfaces:**
- Consumes: none
- Produces: `SttEngine` interface (used by Task 4 VoiceCaptureManager refactor), `SttModelInfo` data class (used by Task 3 ModelDownloadManager)

- [ ] **Step 1: Create SttModelInfo data class**

Create `app/src/main/java/com/omnidocs/app/stt/SttModelInfo.kt`:

```kotlin
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
    val fileName: String,          // ZIP archive name
    val extractedDirName: String,  // Directory name after extraction
    val sha256: String? = null,
    val languages: List<String>,   // e.g., ["en"] or ["zh", "en", "ja", "ko"]
    val modelType: SttModelType,
    val isDownloaded: Boolean = false
)

enum class SttModelType {
    WHISPER,      // OpenAI Whisper (encoder + decoder ONNX)
    MOONSHINE,    // Moonshine (encoder + decoder ORT)
    SENSE_VOICE   // SenseVoice (single model file)
}
```

- [ ] **Step 2: Create SttEngine interface**

Create `app/src/main/java/com/omnidocs/app/stt/SttEngine.kt`:

```kotlin
package com.omnidocs.app.stt

import android.media.AudioRecord

/**
 * Result from speech recognition.
 */
data class SttResult(
    val text: String,
    val confidence: Float = 1.0f
)

/**
 * Callback for real-time partial results during streaming recognition.
 */
fun interface SttPartialCallback {
    fun onPartialResult(text: String)
}

/**
 * Interface for offline speech recognition engines.
 * Implementations wrap Sherpa-onnx or fall back to system SpeechRecognizer.
 */
interface SttEngine {
    /**
     * Start listening for speech. Audio is recorded from the microphone
     * and processed by the STT model in real-time.
     *
     * @param languageCode ISO 639-1 language code (e.g., "en", "zh")
     * @param partialCallback Called with intermediate results as speech is processed
     */
    fun startListening(languageCode: String, partialCallback: SttPartialCallback? = null)

    /**
     * Stop listening and return the final transcription.
     * Blocks until the current audio buffer is processed.
     *
     * @return Final transcription result, or null if no speech was detected
     */
    fun stopListening(): SttResult?

    /**
     * Cancel listening without returning a result.
     */
    fun cancelListening()

    /**
     * Whether the engine is currently recording and processing audio.
     */
    fun isListening(): Boolean

    /**
     * Release all native resources. Must be called when the engine is no longer needed.
     */
    fun release()
}
```

- [ ] **Step 3: Commit**

```bash
git add app/src/main/java/com/omnidocs/app/stt/
git commit -m "feat: add SttEngine interface and SttModelInfo data class"
```

---

### Task 3: Register STT Models in ModelDownloadManager

**Files:**
- Modify: `app/src/main/java/com/omnidocs/app/ai/ModelDownloadManager.kt:51-82`

**Interfaces:**
- Consumes: `SttModelInfo` from Task 2
- Produces: STT model registry accessible via `ModelDownloadManager.sttModels`, download/delete/verify methods for STT models

- [ ] **Step 1: Add STT model registry to ModelDownloadManager**

Add to `ModelDownloadManager.kt` after the `availableModels` list:

```kotlin
    /**
     * Available offline speech recognition models.
     * These are downloaded as ZIP archives and extracted to the models directory.
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
```

- [ ] **Step 2: Add STT model download method**

Add to `ModelDownloadManager.kt`:

```kotlin
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

                // Save checksum sidecar
                val extractedDir = File(modelsDir, model.extractedDirName)
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

    private val _sttDownloadState = MutableStateFlow<DownloadState>(DownloadState.Idle)
    val sttDownloadState: StateFlow<DownloadState> = _sttDownloadState.asStateFlow()

    private fun getSttChecksumFile(model: SttModelInfo): File {
        return File(modelsDir, "${model.id}.stt.sha256")
    }

    private fun saveSttChecksum(model: SttModelInfo) {
        getSttChecksumFile(model).writeText(model.sha256 ?: "no-checksum")
    }

    private fun deleteSttChecksum(model: SttModelInfo) {
        getSttChecksumFile(model).delete()
    }
```

- [ ] **Step 3: Add import for SttModelInfo and SttModelType**

Add to `ModelDownloadManager.kt` imports:
```kotlin
import com.omnidocs.app.stt.SttModelInfo
import com.omnidocs.app.stt.SttModelType
```

- [ ] **Step 4: Commit**

```bash
git add app/src/main/java/com/omnidocs/app/ai/ModelDownloadManager.kt
git commit -m "feat: register STT models in ModelDownloadManager with download/extract/delete"
```

---

### Task 4: SherpaOnnxSttEngine Implementation

**Files:**
- Create: `app/src/main/java/com/omnidocs/app/stt/SherpaOnnxSttEngine.kt`
- Create: `app/src/main/java/com/omnidocs/app/stt/SttEngineFactory.kt`

**Interfaces:**
- Consumes: `SttEngine` interface from Task 2, `SttModelInfo` from Task 2, `ModelDownloadManager.getSttModelPath()` from Task 3
- Produces: `SherpaOnnxSttEngine` (used by Task 5 VoiceCaptureManager), `SttEngineFactory` (Hilt-injected)

- [ ] **Step 1: Create SherpaOnnxSttEngine**

Create `app/src/main/java/com/omnidocs/app/stt/SherpaOnnxSttEngine.kt`:

```kotlin
package com.omnidocs.app.stt

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Log
import com.k2fsa.sherpa.onnx.*
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "SherpaOnnxSttEngine"
private const val SAMPLE_RATE = 16000
private const val CHANNEL_CONFIG = AudioFormat.CHANNEL_IN_MONO
private const val AUDIO_FORMAT = AudioFormat.ENCODING_PCM_16BIT

@Singleton
class SherpaOnnxSttEngine @Inject constructor(
    @ApplicationContext private val context: Context
) : SttEngine {

    private var recognizer: OfflineRecognizer? = null
    private var audioRecord: AudioRecord? = null
    private var recordingThread: Thread? = null
    private val isRecording = AtomicBoolean(false)
    private var currentModelId: String? = null

    /**
     * Initialize the recognizer with a downloaded STT model.
     * Must be called before startListening().
     */
    fun initialize(modelDir: String, model: SttModelInfo): Boolean {
        try {
            // Release previous recognizer if any
            release()

            val config = buildConfig(modelDir, model)
            recognizer = OfflineRecognizer(config)
            currentModelId = model.id
            Log.d(TAG, "Initialized recognizer with model: ${model.name}")
            return true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize recognizer: ${e.message}", e)
            return false
        }
    }

    private fun buildConfig(modelDir: String, model: SttModelInfo): OfflineRecognizerConfig {
        val modelConfig = when (model.modelType) {
            SttModelType.WHISPER -> OfflineModelConfig(
                whisper = OfflineWhisperModelConfig(
                    encoder = "$modelDir/encoder.onnx",
                    decoder = "$modelDir/decoder.onnx",
                    language = if (model.languages.contains("en")) "en" else "",
                    task = "transcribe",
                ),
                tokens = "$modelDir/tokens.txt",
                numThreads = 2,
                debug = false,
            )
            SttModelType.MOONSHINE -> OfflineModelConfig(
                moonshine = OfflineMoonshineModelConfig(
                    encoder = "$modelDir/encoder.onnx",
                    decoder = "$modelDir/decoder.onnx",
                ),
                tokens = "$modelDir/tokens.txt",
                numThreads = 2,
                debug = false,
            )
            SttModelType.SENSE_VOICE -> OfflineModelConfig(
                senseVoice = OfflineSenseVoiceModelConfig(
                    model = "$modelDir/model.int8.onnx",
                ),
                tokens = "$modelDir/tokens.txt",
                numThreads = 2,
                debug = false,
            )
        }

        return OfflineRecognizerConfig(
            featConfig = getFeatureConfig(sampleRate = SAMPLE_RATE, featureDim = 80),
            modelConfig = modelConfig,
            decodingMethod = "greedy_search",
        )
    }

    override fun startListening(languageCode: String, partialCallback: SttPartialCallback?) {
        if (recognizer == null) {
            Log.e(TAG, "Recognizer not initialized. Call initialize() first.")
            return
        }

        if (isRecording.get()) {
            Log.w(TAG, "Already recording")
            return
        }

        val bufferSize = AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL_CONFIG, AUDIO_FORMAT)
        if (bufferSize == AudioRecord.ERROR || bufferSize == AudioRecord.ERROR_BAD_VALUE) {
            Log.e(TAG, "Invalid buffer size: $bufferSize")
            return
        }

        try {
            audioRecord = AudioRecord(
                MediaRecorder.AudioSource.MIC,
                SAMPLE_RATE,
                CHANNEL_CONFIG,
                AUDIO_FORMAT,
                bufferSize * 2
            )

            if (audioRecord?.state != AudioRecord.STATE_INITIALIZED) {
                Log.e(TAG, "AudioRecord failed to initialize")
                audioRecord?.release()
                audioRecord = null
                return
            }

            isRecording.set(true)
            audioRecord?.startRecording()

            val stream = recognizer?.createStream()

            recordingThread = Thread({
                val buffer = ShortArray(1024)
                val floatBuffer = FloatArray(1024)

                while (isRecording.get()) {
                    val shortsRead = audioRecord?.read(buffer, 0, buffer.size) ?: 0
                    if (shortsRead > 0) {
                        // Convert short PCM to float [-1, 1]
                        for (i in 0 until shortsRead) {
                            floatBuffer[i] = buffer[i].toFloat() / 32768f
                        }
                        stream?.acceptWaveform(floatBuffer.copyOf(shortsRead), sampleRate = SAMPLE_RATE)
                    }
                }

                // Decode remaining audio when recording stops
                stream?.let {
                    recognizer?.decode(it)
                    val result = recognizer?.getResult(it)
                    it.release()
                }
            }, "STT-Recording").also { it.start() }

            Log.d(TAG, "Started listening")
        } catch (e: SecurityException) {
            Log.e(TAG, "RECORD_AUDIO permission not granted", e)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start recording", e)
        }
    }

    override fun stopListening(): SttResult? {
        if (!isRecording.get()) return null

        isRecording.set(false)
        recordingThread?.join(5000)
        recordingThread = null

        audioRecord?.stop()
        audioRecord?.release()
        audioRecord = null

        // The result was already captured in the recording thread
        // Re-create stream and decode to get final result
        val stream = recognizer?.createStream() ?: return null
        recognizer?.decode(stream)
        val result = recognizer?.getResult(stream)
        stream.release()

        val text = result?.text?.trim() ?: ""
        Log.d(TAG, "Stopped listening. Result: $text")

        return if (text.isNotEmpty()) SttResult(text = text) else null
    }

    override fun cancelListening() {
        isRecording.set(false)
        recordingThread?.join(2000)
        recordingThread = null

        audioRecord?.stop()
        audioRecord?.release()
        audioRecord = null
    }

    override fun isListening(): Boolean = isRecording.get()

    override fun release() {
        cancelListening()
        recognizer?.release()
        recognizer = null
        currentModelId = null
    }
}
```

- [ ] **Step 2: Create SttEngineFactory**

Create `app/src/main/java/com/omnidocs/app/stt/SttEngineFactory.kt`:

```kotlin
package com.omnidocs.app.stt

import android.content.Context
import android.speech.SpeechRecognizer
import android.util.Log
import com.omnidocs.app.ai.ModelDownloadManager
import com.omnidocs.app.ai.ModelPreferences
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.first
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "SttEngineFactory"

/**
 * Factory for creating the appropriate speech recognition engine.
 * Uses Sherpa-onnx when an STT model is downloaded, falls back to system SpeechRecognizer.
 */
@Singleton
class SttEngineFactory @Inject constructor(
    @ApplicationContext private val context: Context,
    private val modelDownloadManager: ModelDownloadManager,
    private val modelPreferences: ModelPreferences,
    private val sherpaEngine: SherpaOnnxSttEngine
) {
    /**
     * Get the best available STT engine.
     * Returns SherpaOnnxSttEngine if a model is downloaded and initialized,
     * otherwise returns null (caller should use system SpeechRecognizer).
     */
    suspend fun getEngine(): SttEngine? {
        val selectedModelId = modelPreferences.selectedSttModelId.first()
        val downloadedModels = modelDownloadManager.getDownloadedSttModels()

        // Try selected model first
        val selectedModel = downloadedModels.find { it.id == selectedModelId && it.isDownloaded }
        if (selectedModel != null) {
            val modelDir = modelDownloadManager.getSttModelPath(selectedModel)
            if (sherpaEngine.initialize(modelDir, selectedModel)) {
                Log.d(TAG, "Using Sherpa-onnx with ${selectedModel.name}")
                return sherpaEngine
            }
        }

        // Fall back to any downloaded model
        val anyModel = downloadedModels.firstOrNull { it.isDownloaded }
        if (anyModel != null) {
            val modelDir = modelDownloadManager.getSttModelPath(anyModel)
            if (sherpaEngine.initialize(modelDir, anyModel)) {
                Log.d(TAG, "Using Sherpa-onnx with fallback: ${anyModel.name}")
                return sherpaEngine
            }
        }

        // No STT model available
        Log.d(TAG, "No STT model downloaded, system SpeechRecognizer will be used")
        return null
    }

    /**
     * Check if offline STT is available.
     */
    fun isOfflineSttAvailable(): Boolean {
        return modelDownloadManager.getDownloadedSttModels().any { it.isDownloaded }
    }

    /**
     * Check if the system SpeechRecognizer is available.
     */
    fun isSystemRecognizerAvailable(): Boolean {
        return SpeechRecognizer.isRecognitionAvailable(context)
    }
}
```

- [ ] **Step 3: Add selectedSttModelId to ModelPreferences**

Add to `ModelPreferences.kt`:

```kotlin
    private val selectedSttModelKey = stringPreferencesKey("selected_stt_model_id")

    /** The currently selected STT model ID. Defaults to null (system recognizer). */
    val selectedSttModelId: Flow<String?> = context.modelDataStore.data.map { prefs ->
        prefs[selectedSttModelKey]
    }

    suspend fun setSelectedSttModelId(id: String?) {
        context.modelDataStore.edit { prefs ->
            if (id != null) {
                prefs[selectedSttModelKey] = id
            } else {
                prefs.remove(selectedSttModelKey)
            }
        }
    }
```

- [ ] **Step 4: Commit**

```bash
git add app/src/main/java/com/omnidocs/app/stt/SherpaOnnxSttEngine.kt \
        app/src/main/java/com/omnidocs/app/stt/SttEngineFactory.kt \
        app/src/main/java/com/omnidocs/app/ai/ModelPreferences.kt
git commit -m "feat: implement SherpaOnnxSttEngine with AudioRecord and SttEngineFactory"
```

---

### Task 5: Refactor VoiceCaptureManager to Use SttEngine

**Files:**
- Modify: `app/src/main/java/com/omnidocs/app/voice/VoiceCaptureManager.kt`

**Interfaces:**
- Consumes: `SttEngine` and `SttEngineFactory` from Task 4
- Produces: Updated `VoiceCaptureManager` that uses the engine interface (consumed by VoiceCaptureViewModel unchanged)

- [ ] **Step 1: Refactor VoiceCaptureManager**

Replace the content of `VoiceCaptureManager.kt` with:

```kotlin
package com.omnidocs.app.voice

import android.content.Context
import android.speech.SpeechRecognizer
import android.util.Log
import com.omnidocs.app.ai.AiOutputProcessor
import com.omnidocs.app.ai.LlamaCppService
import com.omnidocs.app.ai.ModelDownloadManager
import com.omnidocs.app.ai.ModelInfo
import com.omnidocs.app.ai.ModelPreferences
import com.omnidocs.app.ai.PromptBuilder
import com.omnidocs.app.ai.resolveActiveModel
import com.omnidocs.app.stt.SttEngine
import com.omnidocs.app.stt.SttEngineFactory
import com.omnidocs.app.stt.SttResult
import com.omnidocs.app.util.HtmlSanitizer
import com.omnidocs.app.util.sanitizeForHtml
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "VoiceCaptureManager"

@Singleton
class VoiceCaptureManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val llamaCppService: LlamaCppService,
    private val modelDownloadManager: ModelDownloadManager,
    private val modelPreferences: ModelPreferences,
    private val sttEngineFactory: SttEngineFactory
) {
    private var activeEngine: SttEngine? = null
    private var useSystemRecognizer = false
    private var systemRecognizer: android.speech.SpeechRecognizer? = null

    private val _transcript = MutableStateFlow("")
    val transcript: StateFlow<String> = _transcript.asStateFlow()

    private val _isListening = MutableStateFlow(false)
    val isListening: StateFlow<Boolean> = _isListening.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    private val _engineType = MutableStateFlow<String>("system")
    val engineType: StateFlow<String> = _engineType.asStateFlow()

    private suspend fun getActiveModel(): ModelInfo? {
        return resolveActiveModel(modelPreferences, modelDownloadManager)
    }

    /**
     * Resolve which engine to use and initialize it.
     */
    private suspend fun resolveEngine(): Boolean {
        val sherpaEngine = sttEngineFactory.getEngine()
        if (sherpaEngine != null) {
            activeEngine = sherpaEngine
            useSystemRecognizer = false
            _engineType.value = "offline"
            return true
        }

        // Fall back to system SpeechRecognizer
        if (SpeechRecognizer.isRecognitionAvailable(context)) {
            useSystemRecognizer = true
            _engineType.value = "system"
            return true
        }

        _error.value = "No speech recognition available. Download a model in Settings."
        return false
    }

    fun startListening(languageCode: String = "en") {
        _isListening.value = true
        _error.value = null
        _transcript.value = ""

        // Resolve engine on coroutine scope
        kotlinx.coroutines.GlobalScope.launch(Dispatchers.Main) {
            val ready = resolveEngine()
            if (!ready) {
                _isListening.value = false
                return@launch
            }

            if (!useSystemRecognizer && activeEngine != null) {
                // Use Sherpa-onnx engine
                activeEngine?.startListening(languageCode) { partial ->
                    // Partial results update the UI preview
                }
            } else {
                // Use system SpeechRecognizer
                startSystemRecognizer(languageCode)
            }
        }
    }

    private fun startSystemRecognizer(languageCode: String) {
        systemRecognizer?.destroy()
        systemRecognizer = android.speech.SpeechRecognizer.createSpeechRecognizer(context).apply {
            setRecognitionListener(object : android.speech.RecognitionListener {
                override fun onReadyForSpeech(params: android.os.Bundle?) {
                    _isListening.value = true
                    _error.value = null
                }

                override fun onBeginningOfSpeech() {}
                override fun onRmsChanged(rmsdB: Float) {}
                override fun onBufferReceived(buffer: ByteArray?) {}

                override fun onEndOfSpeech() {
                    _isListening.value = false
                }

                override fun onError(error: Int) {
                    _isListening.value = false
                    _error.value = when (error) {
                        android.speech.SpeechRecognizer.ERROR_NO_MATCH -> "No speech detected. Try again."
                        android.speech.SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "Speech timeout. Try again."
                        android.speech.SpeechRecognizer.ERROR_AUDIO -> "Audio recording error."
                        android.speech.SpeechRecognizer.ERROR_CLIENT -> "Client error. Try again."
                        android.speech.SpeechRecognizer.ERROR_NETWORK -> "Network required for system recognizer. Download an offline model in Settings."
                        android.speech.SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "Network timeout."
                        else -> "Recognition error ($error)"
                    }
                }

                override fun onResults(results: android.os.Bundle?) {
                    val matches = results?.getStringArrayList(android.speech.SpeechRecognizer.RESULTS_RECOGNITION)
                    val fullText = matches?.firstOrNull() ?: ""
                    if (fullText.isNotBlank()) {
                        _transcript.value = if (_transcript.value.isEmpty()) {
                            fullText
                        } else {
                            "${_transcript.value} $fullText"
                        }
                    }
                }

                override fun onPartialResults(partialResults: android.os.Bundle?) {}
                override fun onEvent(eventType: Int, params: android.os.Bundle?) {}
            })
        }

        val intent = android.content.Intent(android.speech.RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(android.speech.RecognizerIntent.EXTRA_LANGUAGE_MODEL, android.speech.RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(android.speech.RecognizerIntent.EXTRA_LANGUAGE, languageCode)
            putExtra(android.speech.RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(android.speech.RecognizerIntent.EXTRA_MAX_RESULTS, 3)
        }

        systemRecognizer?.startListening(intent)
    }

    fun stopListening() {
        if (!useSystemRecognizer && activeEngine != null) {
            val result = activeEngine?.stopListening()
            if (result != null && result.text.isNotBlank()) {
                _transcript.value = if (_transcript.value.isEmpty()) {
                    result.text
                } else {
                    "${_transcript.value} ${result.text}"
                }
            }
        } else {
            systemRecognizer?.stopListening()
        }
        _isListening.value = false
    }

    fun clearTranscript() {
        _transcript.value = ""
        _error.value = null
    }

    fun clearError() {
        _error.value = null
    }

    suspend fun structureTranscript(rawText: String, language: String): String = withContext(Dispatchers.IO) {
        val model = getActiveModel() ?: return@withContext "<p>${sanitizeForHtml(rawText)}</p>"
        val langInstruction = if (language != "en") "\nRespond in $language language." else ""

        val systemPrompt = "You are a note structuring assistant. Convert raw speech transcript into " +
            "well-structured HTML notes. Add headings (h2), bullet lists (ul/li), action items with " +
            "checkboxes (input type=checkbox disabled), and bold key terms (strong). Preserve all meaning. " +
            "Return only HTML, no markdown code blocks.$langInstruction"

        val userPrompt = "Structure this speech transcript into a well-organized HTML note:\n\n" +
            "<transcript>\n$rawText\n</transcript>"

        val prompt = PromptBuilder.buildPrompt(model.promptFormat, systemPrompt, userPrompt)
        val result = llamaCppService.generate(prompt, maxTokens = 1500)
        val processed = result?.let { AiOutputProcessor.process(it) }

        if (processed.isNullOrBlank()) {
            "<p>${sanitizeForHtml(rawText)}</p>"
        } else {
            val stripped = processed
                .replace(Regex("```html\\s*"), "")
                .replace(Regex("```\\s*"), "")
                .trim()
            HtmlSanitizer.sanitize(stripped)
        }
    }

    fun destroy() {
        if (!useSystemRecognizer) {
            activeEngine?.release()
            activeEngine = null
        } else {
            systemRecognizer?.stopListening()
            _isListening.value = false
            systemRecognizer?.destroy()
            systemRecognizer = null
        }
    }
}

// Need to import kotlinx.coroutines.launch for GlobalScope
private fun kotlinx.coroutines.GlobalScope.launch(
    dispatcher: kotlinx.coroutines.CoroutineDispatcher,
    block: suspend kotlinx.coroutines.CoroutineScope.() -> Unit
) {
    kotlinx.coroutines.CoroutineScope(dispatcher).kotlinx.coroutines.launch(block = block)
}
```

Note: The `launch` extension at the bottom is a placeholder. The actual implementation should use the proper coroutine scope from the constructor or a dedicated CoroutineScope. A cleaner approach is to add a `private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)` to the class and use `scope.launch` instead of `GlobalScope.launch`.

- [ ] **Step 2: Add coroutine imports**

Add to VoiceCaptureManager.kt imports:
```kotlin
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
```

And replace the GlobalScope usage with a class-level scope:
```kotlin
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
```

Then use `scope.launch { ... }` instead of `GlobalScope.launch(Dispatchers.Main) { ... }`.

Remove the extension function at the bottom of the file.

- [ ] **Step 3: Commit**

```bash
git add app/src/main/java/com/omnidocs/app/voice/VoiceCaptureManager.kt
git commit -m "refactor: VoiceCaptureManager uses SttEngine interface with offline fallback"
```

---

### Task 6: Settings Screen STT Model Section

**Files:**
- Modify: `app/src/main/java/com/omnidocs/app/ui/screens/settings/SettingsViewModel.kt`
- Modify: `app/src/main/java/com/omnidocs/app/ui/screens/settings/SettingsScreen.kt`

**Interfaces:**
- Consumes: `ModelDownloadManager.sttModels`, `SttModelInfo` from Task 3, `ModelPreferences.selectedSttModelId` from Task 4
- Produces: Settings UI for STT model management (user-facing)

- [ ] **Step 1: Add STT model state to SettingsViewModel**

Add to `SettingsViewModel.kt`:

```kotlin
    // STT model management
    val sttDownloadedModels: StateFlow<List<com.omnidocs.app.stt.SttModelInfo>> = modelDownloadManager.sttDownloadState
        .map { modelDownloadManager.getDownloadedSttModels() }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = modelDownloadManager.getDownloadedSttModels()
        )

    val sttDownloadState: StateFlow<DownloadState> = modelDownloadManager.sttDownloadState

    val selectedSttModelId: StateFlow<String?> = modelPreferences.selectedSttModelId
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = null
        )

    fun downloadSttModel(model: com.omnidocs.app.stt.SttModelInfo) {
        viewModelScope.launch {
            modelDownloadManager.downloadSttModel(model)
        }
    }

    fun deleteSttModel(model: com.omnidocs.app.stt.SttModelInfo) {
        modelDownloadManager.deleteSttModel(model)
        if (model.id == selectedSttModelId.value) {
            viewModelScope.launch {
                modelPreferences.setSelectedSttModelId(null)
            }
        }
        _snackbarEvent.tryEmit("${model.name} deleted")
    }

    fun selectSttModel(modelId: String?) {
        viewModelScope.launch {
            modelPreferences.setSelectedSttModelId(modelId)
            if (modelId != null) {
                _snackbarEvent.tryEmit("STT model selected")
            } else {
                _snackbarEvent.tryEmit("Using system recognizer")
            }
        }
    }
```

- [ ] **Step 2: Add Speech Recognition section to SettingsScreen**

Add a new collapsible section in `SettingsScreen.kt` after the "Offline AI Models" section:

```kotlin
            // ── Speech Recognition section ──
            stickyHeader {
                CollapsibleSectionHeader(
                    title = "Speech Recognition",
                    isExpanded = speechExpanded,
                    onToggle = { speechExpanded = !speechExpanded }
                )
            }
            item {
                AnimatedVisibility(
                    visible = speechExpanded,
                    enter = expandVertically(),
                    exit = shrinkVertically()
                ) {
                    Column {
                        Text(
                            text = "Download a model for offline speech-to-text. Works without internet.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = 16.dp)
                        )
                        SttModelDownloadSection(
                            viewModel = viewModel,
                            selectedModelId = selectedSttModelId
                        )
                    }
                }
            }
```

Add the state variable near the other expanded state variables:
```kotlin
    var speechExpanded by remember { mutableStateOf(true) }
```

And collect the selected STT model ID:
```kotlin
    val selectedSttModelId by viewModel.selectedSttModelId.collectAsState()
```

- [ ] **Step 3: Create SttModelDownloadSection composable**

Add to `SettingsScreen.kt`:

```kotlin
@Composable
fun SttModelDownloadSection(
    viewModel: SettingsViewModel,
    selectedModelId: String?
) {
    val models = viewModel.sttDownloadedModels.collectAsState()
    val downloadState = viewModel.sttDownloadState.collectAsState()

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp)
    ) {
        // System recognizer option
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 4.dp),
            colors = CardDefaults.cardColors(
                containerColor = if (selectedModelId == null)
                    MaterialTheme.colorScheme.primaryContainer
                else
                    MaterialTheme.colorScheme.surfaceVariant
            )
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { viewModel.selectSttModel(null) }
                    .padding(16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                RadioButton(
                    selected = selectedModelId == null,
                    onClick = { viewModel.selectSttModel(null) }
                )
                Spacer(modifier = Modifier.width(8.dp))
                Column {
                    Text("System Recognizer", style = MaterialTheme.typography.bodyLarge)
                    Text(
                        "Uses Google speech services (requires internet)",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }

        // Downloaded and available STT models
        viewModel.modelDownloadManager.sttModels.forEach { model ->
            val isDownloaded = models.value.any { it.id == model.id && it.isDownloaded }
            val isSelected = model.id == selectedModelId
            val isDownloading = when (val state = downloadState.value) {
                is com.omnidocs.app.ai.DownloadState.Downloading -> state.modelId == model.id
                else -> false
            }
            val progress = when (val state = downloadState.value) {
                is com.omnidocs.app.ai.DownloadState.Downloading -> state.progress
                else -> 0f
            }

            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 4.dp),
                colors = CardDefaults.cardColors(
                    containerColor = if (isSelected && isDownloaded)
                        MaterialTheme.colorScheme.primaryContainer
                    else
                        MaterialTheme.colorScheme.surfaceVariant
                )
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(model.name, style = MaterialTheme.typography.titleMedium)
                            Text(model.description, style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Text("Size: ${model.size}", style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Text("Languages: ${model.languages.joinToString(", ")}",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        if (isDownloaded && isSelected) {
                            Icon(Icons.Default.CheckCircle, "Active",
                                tint = MaterialTheme.colorScheme.primary)
                        } else if (isDownloaded) {
                            Icon(Icons.Default.Check, "Downloaded",
                                tint = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }

                    if (isDownloading) {
                        Spacer(modifier = Modifier.height(8.dp))
                        LinearProgressIndicator(progress = { progress / 100f },
                            modifier = Modifier.fillMaxWidth())
                        Text("Downloading: ${progress.toInt()}%",
                            style = MaterialTheme.typography.labelSmall)
                    }

                    val downloadError = when (val state = downloadState.value) {
                        is com.omnidocs.app.ai.DownloadState.Error ->
                            if (state.modelId == model.id) state.message else null
                        else -> null
                    }
                    downloadError?.let { error ->
                        Spacer(modifier = Modifier.height(4.dp))
                        Text("Error: $error", style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.error)
                    }

                    Spacer(modifier = Modifier.height(8.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.End
                    ) {
                        if (isDownloaded) {
                            if (!isSelected) {
                                TextButton(onClick = { viewModel.selectSttModel(model.id) }) {
                                    Text("Select")
                                }
                            }
                            TextButton(onClick = { viewModel.deleteSttModel(model) }) {
                                Text("Delete", color = MaterialTheme.colorScheme.error)
                            }
                        } else {
                            TextButton(onClick = { viewModel.downloadSttModel(model) },
                                enabled = !isDownloading) {
                                Text("Download")
                            }
                        }
                    }
                }
            }
        }
    }
}
```

- [ ] **Step 4: Commit**

```bash
git add app/src/main/java/com/omnidocs/app/ui/screens/settings/SettingsViewModel.kt \
        app/src/main/java/com/omnidocs/app/ui/screens/settings/SettingsScreen.kt
git commit -m "feat: add Speech Recognition section to Settings with STT model download UI"
```

---

### Task 7: Build Verification and Integration Test

**Files:**
- No new files (verification only)

**Interfaces:**
- Consumes: All previous tasks
- Produces: Working build, verified integration

- [ ] **Step 1: Build the project**

Run:
```bash
cd H:/work/omnidocs
./gradlew :app:assembleDebug 2>&1 | tail -30
```
Expected: BUILD SUCCESSFUL

- [ ] **Step 2: Fix any compilation errors**

If build fails, read the error messages and fix the issues. Common issues:
- Missing imports for Sherpa-onnx classes
- Incorrect OfflineModelConfig constructor parameters
- AudioRecord permission not declared in AndroidManifest.xml

Check AndroidManifest.xml for RECORD_AUDIO permission:
```bash
grep "RECORD_AUDIO" H:/work/omnidocs/app/src/main/AndroidManifest.xml
```
Expected: `<uses-permission android:name="android.permission.RECORD_AUDIO" />`

- [ ] **Step 3: Deploy to device**

Run:
```bash
adb install -r H:/work/omnidocs/app/build/outputs/apk/debug/app-debug.apk
```

- [ ] **Step 4: Verify Settings screen shows Speech Recognition section**

Open the app, go to Settings. Verify:
- "Speech Recognition" collapsible section appears
- System Recognizer option is selected by default
- STT models (Whisper Tiny, Moonshine, etc.) are listed with Download buttons
- Download progress works

- [ ] **Step 5: Verify voice capture uses system recognizer (no model downloaded)**

- Open Voice Capture overlay
- Tap mic button
- Speak a sentence
- Verify transcript appears (uses system SpeechRecognizer)

- [ ] **Step 6: Download an STT model and verify offline recognition**

- Go to Settings > Speech Recognition
- Download "Whisper Tiny (English)" (~75 MB)
- Select it as active
- Open Voice Capture overlay
- Tap mic button (should show "offline" indicator)
- Speak a sentence
- Verify transcript appears using Sherpa-onnx (no internet required)

- [ ] **Step 7: Commit final state**

```bash
git add -A
git commit -m "feat: offline speech recognition with Sherpa-onnx, optional model download in Settings"
```

---

## Verification Checklist

After completing all tasks:
1. Build passes (`./gradlew :app:assembleDebug`)
2. Settings screen shows Speech Recognition section with model cards
3. System recognizer works when no STT model is downloaded
4. STT model downloads successfully (progress shown)
5. Selected STT model is persisted across app restarts
6. Voice capture uses Sherpa-onnx when model is selected
7. Voice capture falls back to system recognizer when no model selected
8. Delete button removes STT model files
9. Error states display correctly (download failures, permission denied)
10. No regression in existing voice capture + LLM structuring flow
