package com.omnidocs.app.stt

import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Log
import com.k2fsa.sherpa.onnx.OfflineModelConfig
import com.k2fsa.sherpa.onnx.OfflineMoonshineModelConfig
import com.k2fsa.sherpa.onnx.OfflineRecognizer
import com.k2fsa.sherpa.onnx.OfflineRecognizerConfig
import com.k2fsa.sherpa.onnx.OfflineSenseVoiceModelConfig
import com.k2fsa.sherpa.onnx.OfflineWhisperModelConfig
import com.k2fsa.sherpa.onnx.getFeatureConfig
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "SherpaOnnxSttEngine"
private const val SAMPLE_RATE = 16000
private const val CHANNEL_CONFIG = AudioFormat.CHANNEL_IN_MONO
private const val AUDIO_FORMAT = AudioFormat.ENCODING_PCM_16BIT
private const val DECODE_INTERVAL_MS = 1000L  // Decode every 1s of new audio

@Singleton
class SherpaOnnxSttEngine @Inject constructor(
    @ApplicationContext private val context: Context
) : SttEngine {

    private var recognizer: OfflineRecognizer? = null
    private var audioRecord: AudioRecord? = null
    private var recordingThread: Thread? = null
    private var decodeThread: Thread? = null
    private val isRecording = AtomicBoolean(false)
    private var currentModelId: String? = null

    // Audio buffer - recording thread appends, decode thread reads snapshots
    private val audioChunks = CopyOnWriteArrayList<FloatArray>()
    @Volatile private var hasNewAudio = false

    /**
     * Initialize the recognizer with a downloaded STT model.
     * Must be called before startListening().
     */
    fun initialize(modelDir: String, model: SttModelInfo): Boolean {
        try {
            release()

            val config = buildConfig(modelDir, model)
            recognizer = OfflineRecognizer(config = config)
            currentModelId = model.id
            Log.d(TAG, "Initialized recognizer with model: ${model.name}")
            return true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize recognizer: ${e.message}", e)
            return false
        }
    }

    private fun buildConfig(modelDir: String, model: SttModelInfo): OfflineRecognizerConfig {
        val numThreads = Runtime.getRuntime().availableProcessors().coerceIn(2, 4)

        val modelConfig = when (model.modelType) {
            SttModelType.WHISPER -> OfflineModelConfig(
                whisper = OfflineWhisperModelConfig(
                    encoder = "$modelDir/encoder.onnx",
                    decoder = "$modelDir/decoder.onnx",
                    language = if (model.languages.contains("en")) "en" else "",
                    task = "transcribe",
                ),
                tokens = "$modelDir/tokens.txt",
                numThreads = numThreads,
                debug = false,
            )
            SttModelType.MOONSHINE -> OfflineModelConfig(
                moonshine = OfflineMoonshineModelConfig(
                    preprocessor = "$modelDir/preprocess.onnx",
                    encoder = "$modelDir/encode.int8.onnx",
                    uncachedDecoder = "$modelDir/uncached_decode.int8.onnx",
                    cachedDecoder = "$modelDir/cached_decode.int8.onnx",
                    mergedDecoder = "",
                ),
                tokens = "$modelDir/tokens.txt",
                numThreads = numThreads,
                debug = false,
            )
            SttModelType.SENSE_VOICE -> OfflineModelConfig(
                senseVoice = OfflineSenseVoiceModelConfig(
                    model = "$modelDir/model.int8.onnx",
                ),
                tokens = "$modelDir/tokens.txt",
                numThreads = numThreads,
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

            // Reset state
            audioChunks.clear()
            hasNewAudio = false

            isRecording.set(true)
            audioRecord?.startRecording()

            // --- Recording thread: only reads mic, never blocks ---
            recordingThread = Thread({
                val buffer = ShortArray(1024)
                val floatBuffer = FloatArray(1024)

                while (isRecording.get()) {
                    val shortsRead = audioRecord?.read(buffer, 0, buffer.size) ?: 0
                    if (shortsRead > 0) {
                        for (i in 0 until shortsRead) {
                            floatBuffer[i] = buffer[i].toFloat() / 32768f
                        }
                        // Append a copy to the shared buffer - never blocks
                        audioChunks.add(floatBuffer.copyOf(shortsRead))
                        hasNewAudio = true
                    }
                }
                Log.d(TAG, "Recording thread finished")
            }, "STT-Recording").also { it.start() }

            // --- Decode thread: creates its own stream, decodes snapshots ---
            decodeThread = Thread({
                var lastDecodeTime = System.currentTimeMillis()

                while (isRecording.get()) {
                    val now = System.currentTimeMillis()
                    val elapsed = now - lastDecodeTime

                    if (hasNewAudio && elapsed >= DECODE_INTERVAL_MS) {
                        hasNewAudio = false
                        lastDecodeTime = now

                        // Take a snapshot of all accumulated audio
                        val snapshot = audioChunks.toList()
                        if (snapshot.isEmpty()) continue

                        // Decode the full snapshot with a fresh stream
                        try {
                            val stream = recognizer?.createStream() ?: continue
                            for (chunk in snapshot) {
                                stream.acceptWaveform(chunk, sampleRate = SAMPLE_RATE)
                            }
                            recognizer?.decode(stream)
                            val text = recognizer?.getResult(stream)?.text?.trim() ?: ""
                            stream.release()

                            if (text.isNotEmpty()) {
                                partialCallback?.onPartialResult(text)
                            }
                        } catch (e: Exception) {
                            Log.e(TAG, "Decode error: ${e.message}")
                        }
                    }

                    // Small sleep to avoid busy-wait
                    Thread.sleep(100)
                }
                Log.d(TAG, "Decode thread finished")
            }, "STT-Decode").also { it.start() }

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

        audioRecord?.stop()
        audioRecord?.release()
        audioRecord = null

        recordingThread?.join(3000)
        recordingThread = null
        decodeThread?.join(5000)
        decodeThread = null

        // Final decode of all audio
        val snapshot = audioChunks.toList()
        audioChunks.clear()

        if (snapshot.isEmpty()) return null

        val result = try {
            val stream = recognizer?.createStream() ?: return null
            for (chunk in snapshot) {
                stream.acceptWaveform(chunk, sampleRate = SAMPLE_RATE)
            }
            recognizer?.decode(stream)
            val text = recognizer?.getResult(stream)?.text?.trim() ?: ""
            stream.release()
            if (text.isNotEmpty()) SttResult(text = text) else null
        } catch (e: Exception) {
            Log.e(TAG, "Final decode error: ${e.message}")
            null
        }

        Log.d(TAG, "Stopped listening. Result: ${result?.text ?: "(empty)"}")
        return result
    }

    override fun cancelListening() {
        isRecording.set(false)

        recordingThread?.join(2000)
        recordingThread = null
        decodeThread?.join(3000)
        decodeThread = null

        audioRecord?.stop()
        audioRecord?.release()
        audioRecord = null

        audioChunks.clear()
    }

    override fun isListening(): Boolean = isRecording.get()

    override fun release() {
        cancelListening()
        recognizer?.release()
        recognizer = null
        currentModelId = null
    }
}
