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
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "SherpaOnnxSttEngine"
private const val SAMPLE_RATE = 16000
private const val CHANNEL_CONFIG = AudioFormat.CHANNEL_IN_MONO
private const val AUDIO_FORMAT = AudioFormat.ENCODING_PCM_16BIT
private const val DECODE_INTERVAL_MS = 1500L
// Flush stream every ~30s to prevent ONNX crash from oversized internal state
private const val STREAM_MAX_SECONDS = 30
private const val STREAM_MAX_SAMPLES = SAMPLE_RATE * STREAM_MAX_SECONDS

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

    private val audioQueue = LinkedBlockingQueue<FloatArray>()

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

            audioQueue.clear()
            isRecording.set(true)
            audioRecord?.startRecording()

            // Recording thread: reads mic, puts audio into queue. Never blocks.
            recordingThread = Thread({
                val buffer = ShortArray(1024)
                val floatBuffer = FloatArray(1024)

                while (isRecording.get()) {
                    val shortsRead = audioRecord?.read(buffer, 0, buffer.size) ?: 0
                    if (shortsRead > 0) {
                        for (i in 0 until shortsRead) {
                            floatBuffer[i] = buffer[i].toFloat() / 32768f
                        }
                        audioQueue.offer(floatBuffer.copyOf(shortsRead))
                    }
                }
                Log.d(TAG, "Recording thread finished")
            }, "STT-Recording").also { it.start() }

            // Decode thread: owns the stream, drains queue, decodes, flushes when full.
            decodeThread = Thread({
                val initStream = recognizer?.createStream()
                if (initStream == null) {
                    Log.e(TAG, "Failed to create stream")
                    isRecording.set(false)
                    return@Thread
                }

                var stream: com.k2fsa.sherpa.onnx.OfflineStream = initStream
                var lastDecodeTime = System.currentTimeMillis()
                var samplesInStream = 0
                var lastPartialText = ""

                try {
                    while (isRecording.get()) {
                        val chunk = audioQueue.poll()
                        if (chunk != null) {
                            stream.acceptWaveform(chunk, sampleRate = SAMPLE_RATE)
                            samplesInStream += chunk.size

                            // Flush stream if it's getting too large (prevents ONNX crash)
                            if (samplesInStream >= STREAM_MAX_SAMPLES) {
                                recognizer?.decode(stream)
                                val text = recognizer?.getResult(stream)?.text?.trim() ?: ""
                                if (text.isNotEmpty()) {
                                    lastPartialText = text
                                    partialCallback?.onPartialResult(text)
                                }
                                stream.release()
                                val newStream = recognizer?.createStream() ?: break
                                stream = newStream
                                samplesInStream = 0
                                lastDecodeTime = System.currentTimeMillis()
                                Log.d(TAG, "Stream flushed, started new segment")
                            }
                        }

                        val now = System.currentTimeMillis()
                        if (now - lastDecodeTime >= DECODE_INTERVAL_MS) {
                            lastDecodeTime = now
                            recognizer?.decode(stream)
                            val text = recognizer?.getResult(stream)?.text?.trim() ?: ""
                            if (text.isNotEmpty() && text != lastPartialText) {
                                lastPartialText = text
                                partialCallback?.onPartialResult(text)
                            }
                        }

                        if (chunk == null) {
                            Thread.sleep(50)
                        }
                    }

                    // Drain remaining audio
                    while (true) {
                        val chunk = audioQueue.poll() ?: break
                        stream.acceptWaveform(chunk, sampleRate = SAMPLE_RATE)
                        samplesInStream += chunk.size
                    }

                    // Final decode
                    if (samplesInStream > 0) {
                        recognizer?.decode(stream)
                        val finalText = recognizer?.getResult(stream)?.text?.trim() ?: ""
                        if (finalText.isNotEmpty()) {
                            partialCallback?.onPartialResult(finalText)
                        }
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Decode thread error: ${e.message}", e)
                } finally {
                    try { stream.release() } catch (_: Exception) {}
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

        // Wait for decode thread to finish (it drains queue + does final decode)
        decodeThread?.join(10000)
        decodeThread = null
        recordingThread?.join(3000)
        recordingThread = null

        // The decode thread already did the final decode via callback.
        // Drain any remaining audio for a fallback decode.
        val remaining = mutableListOf<FloatArray>()
        audioQueue.drainTo(remaining)
        audioQueue.clear()

        if (remaining.isEmpty()) return null

        return try {
            val stream = recognizer?.createStream() ?: return null
            for (chunk in remaining) {
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
    }

    override fun cancelListening() {
        isRecording.set(false)

        recordingThread?.join(2000)
        recordingThread = null
        decodeThread?.join(5000)
        decodeThread = null

        audioRecord?.stop()
        audioRecord?.release()
        audioRecord = null

        audioQueue.clear()
    }

    override fun isListening(): Boolean = isRecording.get()

    override fun release() {
        cancelListening()
        recognizer?.release()
        recognizer = null
        currentModelId = null
    }
}
