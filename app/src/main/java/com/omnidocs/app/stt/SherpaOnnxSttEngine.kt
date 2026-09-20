package com.omnidocs.app.stt

import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Log
import com.k2fsa.sherpa.onnx.FeatureConfig
import com.k2fsa.sherpa.onnx.OfflineModelConfig
import com.k2fsa.sherpa.onnx.OfflineMoonshineModelConfig
import com.k2fsa.sherpa.onnx.OfflineRecognizer
import com.k2fsa.sherpa.onnx.OfflineRecognizerConfig
import com.k2fsa.sherpa.onnx.OfflineWhisperModelConfig
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "SherpaOnnxSttEngine"
private const val SAMPLE_RATE = 16000
private const val CHANNEL_CONFIG = AudioFormat.CHANNEL_IN_MONO
private const val AUDIO_FORMAT = AudioFormat.ENCODING_PCM_16BIT
// Minimum audio before attempting a decode. Whisper's NormalizeWhisperFeatures runs
// Eigen maxCoeff() over the frames OUTSIDE sherpa-onnx's try/catch -- with 0 frames
// that's undefined behavior → SIGSEGV null deref, uncatchable in Java. The decode
// can fire before the mic delivers its first chunk, so never decode an
// empty/near-empty stream. 1 second = 100 mel frames. Moonshine tolerates empty
// streams (its impl catches exceptions); Whisper does not.
private const val MIN_DECODE_SAMPLES = SAMPLE_RATE
// Final-decode floor. MIN_DECODE_SAMPLES (1s) is right for mid-stream partials,
// but it also discards a ~0.5s utterance at end-of-recording. The final decode
// relaxes to this tiny floor -- frames exist, and the 0-frame SIGSEGV risk only
// applies to mid-stream partials.
private const val MIN_FINAL_DECODE_SAMPLES = 256
// Silence-aware segmentation: a segment is cut at a pause once it is at least
// SEGMENT_MIN_SAMPLES long and the trailing SILENCE_CUT_SAMPLES are near-silent.
// Pauses are natural word boundaries, so cutting there avoids splitting a word
// across two independent decodes (each decode has no context from the previous).
// A chunk is "silent" when its RMS is below SILENCE_RATIO of the segment's peak
// RMS (with an absolute floor for quiet recordings). Whisper tolerates the small
// trailing silence it sees at a cut.
private const val SEGMENT_MIN_SAMPLES = SAMPLE_RATE * 2   // don't cut before 2s of audio
private const val SILENCE_CUT_SAMPLES = SAMPLE_RATE / 3   // ~333ms consecutive silence
private const val SILENCE_RMS_FLOOR = 0.008f              // absolute silence floor
private const val SILENCE_RATIO = 0.15f                   // <15% of segment peak = silent
// Silent chunks are buffered (not committed to the stream) so a segment ends exactly
// at the last speech sample -- trailing silence is what Whisper hallucinates on.
// If a gap has no speech yet, only keep the most recent bit so a long silent lead-in
// never gets committed and never bloats memory.
private const val MAX_LEADING_SILENCE_SAMPLES = SAMPLE_RATE
// Audio read buffer: 4096 samples = 256ms at 16kHz
private const val AUDIO_BUFFER_SIZE = 4096

@Singleton
class SherpaOnnxSttEngine @Inject constructor(
    @ApplicationContext private val context: Context
) : SttEngine {

    @Volatile private var recognizer: OfflineRecognizer? = null
    private var audioRecord: AudioRecord? = null
    private var recordingThread: Thread? = null
    @Volatile private var decodeThreadRef: Thread? = null
    private val isRecording = AtomicBoolean(false)
    private var currentModelId: String? = null
    // Raw PCM sink -- forwards mic bytes to the caller (recording persistence).
    // Written from the recording thread only.
    @Volatile private var audioCallback: ((ByteArray) -> Unit)? = null
    // Final accumulated transcript, set by the decode thread before it exits.
    // stopListening() returns this synchronously instead of racing an async
    // partial emit, which could arrive after the caller already read the result.
    @Volatile private var lastResultText: String? = null
    // Max decode segment (samples) set from the model type in initialize(). Each
    // segment's stream is decoded once then released, so ONNX memory stays flat.
    // Re-decoding one ever-growing stream every 200ms ballooned Whisper Small to
    // ~2GB RSS → the system OOM-killed the app. This is a ceiling -- silence-aware
    // cuts (see acceptChunk) normally fire earlier at pauses; the max only caps how
    // long a single unbroken stretch of speech runs before it streams partials.
    private var segmentSamples = SAMPLE_RATE

    companion object {
        /**
         * Cap on queued (not yet decoded) audio chunks. The old unbounded queue
         * grew without limit during slow decodes; at 16kHz mono even a few
         * minutes of backlog is tens of MB of FloatArrays. When full, the
         * recording thread drops the oldest chunk (live audio matters more
         * than stale backlog) and counts the drop for diagnostics.
         */
        private const val MAX_QUEUED_CHUNKS = 512
    }

    private val audioQueue = LinkedBlockingQueue<FloatArray>(MAX_QUEUED_CHUNKS)

    private val droppedChunkCount = java.util.concurrent.atomic.AtomicInteger(0)

    /**
     * @param accuracyMode "fast" (default) or "accurate". Controls the Whisper max
     *   decode segment length: "fast" caps at ~3s of audio per segment for snappier
     *   partials, "accurate" at ~10s so the model sees more context per decode (better
     *   word accuracy, slower partials). Both cut early at pauses (silence-aware),
     *   so these are ceilings, not fixed sizes. Moonshine is unaffected.
     */
    fun initialize(modelDir: String, model: SttModelInfo, language: String = "", accuracyMode: String = "fast"): Boolean {
        try {
            release()

            // Validate required model files exist before attempting ONNX init
            // (prevents native crash from missing files in partial downloads)
            val dir = java.io.File(modelDir)
            val allFiles = dir.listFiles()
            val dirFiles = allFiles?.map { it.name }?.toSet() ?: emptySet()
            Log.d(TAG, "Validating model dir: $modelDir, exists=${dir.exists()}, isDir=${dir.isDirectory}, fileCount=${allFiles?.size}, files=${dirFiles.sorted()}")
            val valid = when (model.modelType) {
                SttModelType.WHISPER -> {
                    // Whisper turbo uses turbo-encoder.int8.onnx / turbo-decoder.int8.onnx / turbo-tokens.txt
                    // Standard whisper uses encoder.onnx / decoder.onnx / tokens.txt
                    // Validate ONNX file integrity (protobuf magic bytes) to prevent native SIGABRT
                    // from corrupted files ("Protobuf parsing failed" → uncatchable in Java).
                    // INT8 Whisper models crash on decode on this device (null deref in
                    // OfflineRecognizer_decode → SIGSEGV, uncatchable in Java), so a valid
                    // FP32 encoder AND decoder must both be present. Accepting a dir whose
                    // only encoder/decoder are INT8 would pass magic-byte validation and then
                    // crash the native decode. The standard packages ship FP32 encoder.onnx /
                    // decoder.onnx (INT8 variants are extra), so legitimately-downloaded models
                    // still validate; only INT8-only dirs are rejected.
                    val allOnnx = dir.listFiles()?.filter { it.name.endsWith(".onnx") } ?: emptyList()
                    val hasFp32Encoder = allOnnx.any { "encoder" in it.name && !it.name.contains("int8") && isValidOnnxFile(it) }
                    val hasFp32Decoder = allOnnx.any { "decoder" in it.name && !it.name.contains("int8") && isValidOnnxFile(it) }
                    val hasTokens = dirFiles.any { "tokens" in it && it.endsWith(".txt") }
                    Log.d(TAG, "Whisper validation: hasFp32Encoder=$hasFp32Encoder, hasFp32Decoder=$hasFp32Decoder, hasTokens=$hasTokens")
                    hasFp32Encoder && hasFp32Decoder && hasTokens
                }
                SttModelType.MOONSHINE -> {
                    val required = listOf("preprocess.onnx", "encode.int8.onnx", "uncached_decode.int8.onnx", "cached_decode.int8.onnx", "tokens.txt")
                    required.all { it in dirFiles }
                }
            }
            if (!valid) {
                Log.e(TAG, "Model incomplete in $modelDir — files present: ${dirFiles.sorted()}")
                return false
            }

            val config = buildConfig(modelDir, model, language, accuracyMode)
            recognizer = OfflineRecognizer(config = config)
            currentModelId = model.id
            segmentSamples = when (model.modelType) {
                SttModelType.WHISPER -> if (accuracyMode == "accurate") SAMPLE_RATE * 10 else SAMPLE_RATE * 3
                SttModelType.MOONSHINE -> SAMPLE_RATE
            }
            Log.d(TAG, "Segment size: ${segmentSamples / SAMPLE_RATE}s (mode=$accuracyMode)")
            Log.d(TAG, "Initialized recognizer with model: ${model.name}, language: '${language.ifEmpty { "auto" }}'")
            return true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize recognizer: ${e.message}", e)
            return false
        }
    }

    /**
     * Check if a file looks like a valid ONNX model by reading the protobuf magic bytes.
     * ONNX files start with protobuf field 1 (wire type 2): byte 0x08 followed by a varint length.
     * This catches truncated/corrupted files that would crash the native ONNX runtime with
     * "Protobuf parsing failed" → SIGABRT (uncatchable in Java).
     */
    private fun isValidOnnxFile(file: java.io.File?): Boolean {
        if (file == null || !file.exists() || file.length() < 16) return false
        return try {
            java.io.FileInputStream(file).use { fis ->
                val buf = ByteArray(4)
                if (fis.read(buf) < 2) return false
                // Protobuf field 1, wire type 2 (length-delimited): 0x08 followed by varint
                buf[0] == 0x08.toByte() && buf[1].toInt() > 0
            }
        } catch (e: Exception) {
            false
        }
    }

    private fun buildConfig(modelDir: String, model: SttModelInfo, language: String = "", accuracyMode: String = "fast"): OfflineRecognizerConfig {
        // Whisper Small's 12-layer encoder parallelizes well across cores, so 4 threads
        // gives a ~2-3x decode speedup on Snapdragon 8 Gen 2 (memory stays safe now that
        // decode is bounded per-segment). Moonshine keeps 1 thread -- it is tiny, and
        // extra threads only add contention without speed benefit (matches sherpa-onnx
        // Moonshine examples).
        val numThreads = when (model.modelType) {
            SttModelType.WHISPER -> 4
            SttModelType.MOONSHINE -> 1
        }

        val whisperLanguage = when {
            // User explicitly set a language -- use it for multilingual models
            language.isNotEmpty() && model.languages.contains("multilingual") -> language
            // English-only model
            !model.languages.contains("multilingual") -> "en"
            // Auto-detect
            else -> ""
        }

        // Resolve actual filenames -- prefer FP32 (non-INT8) files. INT8 Whisper
        // models crash on decode on this device (null deref in OfflineRecognizer_decode),
        // so INT8 is only a last-resort fallback. Validate ONNX files before use --
        // corrupted files cause native SIGABRT that cannot be caught in Java.
        val dirFile = java.io.File(modelDir)
        val allOnnx = dirFile.listFiles()?.filter { it.name.endsWith(".onnx") } ?: emptyList()

        val encoderFile = allOnnx.filter { "encoder" in it.name && !it.name.contains("int8") && isValidOnnxFile(it) }.firstOrNull()
            ?: allOnnx.filter { "encoder" in it.name && isValidOnnxFile(it) }.firstOrNull()
        val decoderFile = allOnnx.filter { "decoder" in it.name && !it.name.contains("int8") && isValidOnnxFile(it) }.firstOrNull()
            ?: allOnnx.filter { "decoder" in it.name && isValidOnnxFile(it) }.firstOrNull()
        val tokensFile = dirFile.listFiles()?.firstOrNull { "tokens" in it.name && it.name.endsWith(".txt") }

        Log.d(TAG, "Resolved files: encoder=${encoderFile?.absolutePath}, decoder=${decoderFile?.absolutePath}, tokens=${tokensFile?.absolutePath}")

        val modelConfig = when (model.modelType) {
            SttModelType.WHISPER -> OfflineModelConfig(
                whisper = OfflineWhisperModelConfig(
                    encoder = encoderFile?.absolutePath ?: "$modelDir/encoder.onnx",
                    decoder = decoderFile?.absolutePath ?: "$modelDir/decoder.onnx",
                    language = whisperLanguage,
                    task = "transcribe",
                ),
                tokens = tokensFile?.absolutePath ?: "$modelDir/tokens.txt",
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
        }

        return OfflineRecognizerConfig(
            featConfig = FeatureConfig(
                sampleRate = SAMPLE_RATE,
                featureDim = 80,
                dither = 0.0f,
            ),
            modelConfig = modelConfig,
            decodingMethod = "greedy_search",
        )
    }

    override fun startListening(
        languageCode: String,
        partialCallback: SttPartialCallback?,
        onAudio: ((ByteArray) -> Unit)?
    ) {
        if (recognizer == null) {
            Log.e(TAG, "Recognizer not initialized. Call initialize() first.")
            return
        }

        if (isRecording.get()) {
            Log.w(TAG, "Already recording")
            return
        }

        audioCallback = onAudio
        lastResultText = null

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
                val buffer = ShortArray(AUDIO_BUFFER_SIZE)
                val floatBuffer = FloatArray(AUDIO_BUFFER_SIZE)
                val byteBuffer = ByteArray(AUDIO_BUFFER_SIZE * 2)
                val audioSink = audioCallback

                while (isRecording.get()) {
                    val shortsRead = audioRecord?.read(buffer, 0, buffer.size) ?: 0
                    if (shortsRead > 0) {
                        for (i in 0 until shortsRead) {
                            floatBuffer[i] = buffer[i].toFloat() / 32768f
                            // Little-endian PCM16, same layout saveAudio() expects.
                            byteBuffer[i * 2] = (buffer[i].toInt() and 0xFF).toByte()
                            byteBuffer[i * 2 + 1] = ((buffer[i].toInt() shr 8) and 0xFF).toByte()
                        }
                        if (!audioQueue.offer(floatBuffer.copyOf(shortsRead))) {
                            // Decode is falling behind: drop oldest to stay bounded,
                            // preferring live audio over stale backlog.
                            audioQueue.poll()
                            audioQueue.offer(floatBuffer.copyOf(shortsRead))
                            val dropped = droppedChunkCount.incrementAndGet()
                            if (dropped == 1 || dropped % 100 == 0) {
                                Log.w(TAG, "Audio decode falling behind; dropped $dropped chunks total")
                            }
                        }
                        audioSink?.invoke(byteBuffer.copyOf(shortsRead * 2))
                    }
                }
                Log.d(TAG, "Recording thread finished")
            }, "STT-Recording").also { it.start() }

            // Decode thread: accumulates audio into a stream, decodes it in bounded
            // segments, then releases the stream and starts a fresh one. Decoding one
            // ever-growing stream (the old design) made ONNX memory balloon to ~2GB and
            // got the app OOM-killed; bounded segments keep the decoded input flat.
            // Chunks that arrive during a slow decode simply wait in the queue.
            decodeThreadRef = Thread({
                val initStream = recognizer?.createStream()
                if (initStream == null) {
                    Log.e(TAG, "Failed to create stream")
                    isRecording.set(false)
                    return@Thread
                }

                var stream: com.k2fsa.sherpa.onnx.OfflineStream = initStream
                var samplesInStream = 0
                var accumulatedText = ""  // Persists across segments
                var streamValid = true
                // Trailing-silence gating state (see acceptChunk). Silent chunks are
                // buffered here, NOT committed to the stream, so a decoded segment ends
                // at the last speech sample instead of trailing silence that Whisper
                // hallucinates on.
                var pendingSilence = mutableListOf<FloatArray>()
                var pendingSilenceSamples = 0
                var segmentPeakRms = 0f

                // Decode the current stream, append to accumulatedText, then release it
                // and create the next one. Guarded by MIN_DECODE_SAMPLES -- Whisper's
                // NormalizeWhisperFeatures crashes (SIGSEGV, uncatchable in Java) when
                // decode() runs with 0 frames.
                fun decodeAndReset(
                    emitPartial: Boolean,
                    cutReason: String = "",
                    minSamples: Int = MIN_DECODE_SAMPLES,
                    commitTrailingSilence: Boolean = false
                ) {
                    // The FINAL decode commits the buffered trailing silence first so a
                    // short utterance that ended right before a pause is not dropped.
                    // Pure-silence segments (nothing committed yet) stay undecoded --
                    // silence is exactly what Whisper hallucinates on.
                    if (commitTrailingSilence && streamValid && samplesInStream > 0) {
                        for (p in pendingSilence) {
                            stream.acceptWaveform(p, sampleRate = SAMPLE_RATE)
                            samplesInStream += p.size
                        }
                    }
                    // Any buffered silence is this segment's tail -- never carry it over.
                    pendingSilence.clear()
                    pendingSilenceSamples = 0
                    if (!streamValid || samplesInStream < minSamples) return
                    val decodedSamples = samplesInStream
                    val segmentText = try {
                        val startNs = System.nanoTime()
                        recognizer?.decode(stream)
                        val elapsedMs = (System.nanoTime() - startNs) / 1_000_000
                        val rtf = elapsedMs / 1000f / (decodedSamples / 16000f)
                        val why = if (cutReason.isNotEmpty()) " [$cutReason]" else ""
                        Log.d(TAG, "Segment decode: ${decodedSamples} samples, ${elapsedMs}ms, RTF=%.2f%s".format(rtf, why))
                        recognizer?.getResult(stream)?.text?.trim() ?: ""
                    } catch (e: Exception) {
                        Log.e(TAG, "Segment decode error: ${e.message}")
                        ""
                    }
                    if (segmentText.isNotEmpty()) {
                        accumulatedText = if (accumulatedText.isEmpty()) segmentText
                            else "$accumulatedText $segmentText"
                        if (emitPartial) partialCallback?.onPartialResult(accumulatedText)
                    }
                    // Release the native stream before creating the next one -- frees the
                    // ONNX allocations this segment used.
                    try { stream.release() } catch (_: Exception) {}
                    val next = recognizer?.createStream()
                    if (next != null) {
                        stream = next
                        samplesInStream = 0
                        streamValid = true
                    } else {
                        streamValid = false
                    }
                }

                // RMS of a chunk (full-scale 1.0). Used to detect silence for
                // segmentation -- cutting at pauses instead of fixed boundaries keeps
                // Whisper from splitting words across two independent decodes.
                fun chunkRms(chunk: FloatArray): Float {
                    var sum = 0.0
                    for (s in chunk) sum += s * s
                    return kotlin.math.sqrt(sum / chunk.size).toFloat()
                }

                // Feed one audio chunk into the current stream and decide whether to
                // cut here. Silent chunks are buffered, not committed, so a segment
                // ends at the last speech sample (no trailing silence = no Whisper
                // end-of-segment hallucination). Cuts at a pause once the segment is
                // long enough, or forcibly at the max segment size so an unbroken
                // utterance still streams partials and never grows unbounded (the old
                // OOM). Returns false if the stream can't continue.
                fun acceptChunk(chunk: FloatArray, emitPartial: Boolean): Boolean {
                    if (!streamValid) return false
                    val rms = chunkRms(chunk)
                    if (rms > segmentPeakRms) segmentPeakRms = rms
                    val silent = rms < maxOf(SILENCE_RMS_FLOOR, SILENCE_RATIO * segmentPeakRms)

                    if (silent) {
                        // Buffer the silence instead of committing it. If no speech has
                        // been committed yet, keep only the most recent silence so a
                        // long silent gap can't bloat the buffer or feed Whisper a
                        // huge silent lead-in.
                        pendingSilence += chunk
                        pendingSilenceSamples += chunk.size
                        if (samplesInStream == 0) {
                            while (pendingSilenceSamples > MAX_LEADING_SILENCE_SAMPLES && pendingSilence.isNotEmpty()) {
                                val dropped = pendingSilence.removeAt(0)
                                pendingSilenceSamples -= dropped.size
                            }
                        }
                        // A pause long enough ends the segment right at the last speech.
                        if (samplesInStream >= SEGMENT_MIN_SAMPLES && pendingSilenceSamples >= SILENCE_CUT_SAMPLES) {
                            decodeAndReset(emitPartial, cutReason = "silence")
                            segmentPeakRms = 0f
                        }
                        return true
                    }

                    // Speech resumed: commit the buffered silence (short pauses stay in
                    // the segment so the model hears the full utterance), then this
                    // speech chunk.
                    for (p in pendingSilence) {
                        stream.acceptWaveform(p, sampleRate = SAMPLE_RATE)
                        samplesInStream += p.size
                    }
                    pendingSilence.clear()
                    pendingSilenceSamples = 0
                    stream.acceptWaveform(chunk, sampleRate = SAMPLE_RATE)
                    samplesInStream += chunk.size
                    if (samplesInStream >= segmentSamples) {
                        decodeAndReset(emitPartial, cutReason = "max")
                        segmentPeakRms = 0f
                    }
                    return true
                }

                try {
                    while (isRecording.get()) {
                        val chunk = audioQueue.poll()
                        if (chunk != null) {
                            if (!acceptChunk(chunk, emitPartial = true)) break
                        } else {
                            Thread.sleep(10)
                        }
                    }

                    // Drain the queue on stop with the same silence-aware segmentation
                    // so a long backlog never becomes one giant final decode.
                    while (true) {
                        val chunk = audioQueue.poll() ?: break
                        if (!acceptChunk(chunk, emitPartial = false)) break
                    }
                    decodeAndReset(
                        emitPartial = false,
                        cutReason = "final",
                        minSamples = MIN_FINAL_DECODE_SAMPLES,
                        commitTrailingSilence = true
                    )

                    // Publish the final accumulated text so stopListening() can return
                    // it synchronously. Do NOT fire another partial callback here --
                    // that was the source of a race where the async emit landed after
                    // the caller already consumed the result and clobbered it.
                    if (accumulatedText.isNotEmpty()) {
                        lastResultText = accumulatedText
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Decode thread error: ${e.message}", e)
                } finally {
                    if (streamValid) {
                        try { stream.release() } catch (_: Exception) {}
                    }
                }

                Log.d(TAG, "Decode thread finished")
            }, "STT-Decode").also { decodeThreadRef = it; it.start() }

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

        // stop() throws IllegalStateException unless actively recording
        // (init failure, double-stop, stop racing cancel) — never let it
        // escape and strand the decode thread below.
        try {
            if (audioRecord?.recordingState == AudioRecord.RECORDSTATE_RECORDING) {
                audioRecord?.stop()
            }
        } catch (e: Exception) {
            Log.w(TAG, "AudioRecord.stop failed", e)
        }
        try {
            audioRecord?.release()
        } catch (e: Exception) {
            Log.w(TAG, "AudioRecord.release failed", e)
        }
        audioRecord = null

        // Wait for the decode thread to finish: it drains the queue, does the
        // final decode, publishes lastResultText, then releases its streams in its
        // own finally block. Must complete before touching recognizer (native
        // objects are not thread-safe). A fixed 15s join() would block the caller
        // (usually the main thread) for that whole period and risk an ANR, so poll
        // the published result with a bounded wait and only then do a short join
        // to guarantee streams are released before the fallback touches recognizer.
        val decodeThread = decodeThreadRef
        if (decodeThread != null) {
            val deadline = System.currentTimeMillis() + 3000
            while (System.currentTimeMillis() < deadline && lastResultText == null && decodeThread.isAlive) {
                Thread.sleep(50)
            }
            decodeThread.join(1000)
        }
        decodeThreadRef = null
        recordingThread?.join(3000)
        recordingThread = null

        // The decode thread accumulated the full transcript across segments and
        // published it to lastResultText before exiting. Return it synchronously --
        // the queue is normally empty here because the decode thread already
        // drained it, so the old fallback path almost always returned null and the
        // caller lost the transcription.
        val finalText = lastResultText
        if (!finalText.isNullOrBlank()) {
            return SttResult(text = finalText)
        }

        // Fallback: decode anything left in the queue.
        // Safe to use recognizer now -- decode thread is confirmed done.
        val remaining = mutableListOf<FloatArray>()
        audioQueue.drainTo(remaining)
        audioQueue.clear()

        if (remaining.isEmpty()) return null

        return try {
            val stream = recognizer?.createStream() ?: return null
            for (chunk in remaining) {
                stream.acceptWaveform(chunk, sampleRate = SAMPLE_RATE)
            }
            // Same minimum guard as the decode thread's final decode (see
            // MIN_FINAL_DECODE_SAMPLES) -- the fallback only ever runs for the tail
            // at end-of-recording, not for mid-stream partials.
            if (remaining.sumOf { it.size } < MIN_FINAL_DECODE_SAMPLES) {
                stream.release()
                return null
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

        // Stop mic first so decode thread drains remaining queue quickly.
        // Guarded: stop() throws unless actively recording.
        try {
            if (audioRecord?.recordingState == AudioRecord.RECORDSTATE_RECORDING) {
                audioRecord?.stop()
            }
        } catch (e: Exception) {
            Log.w(TAG, "AudioRecord.stop failed", e)
        }
        try {
            audioRecord?.release()
        } catch (e: Exception) {
            Log.w(TAG, "AudioRecord.release failed", e)
        }
        audioRecord = null

        // Wait for decode thread to exit its loop and release its native streams.
        // Bounded poll instead of a fixed 15s join so the caller thread (often the
        // main thread) is never blocked that long.
        val decodeThread = decodeThreadRef
        if (decodeThread != null) {
            val deadline = System.currentTimeMillis() + 3000
            while (System.currentTimeMillis() < deadline && decodeThread.isAlive) {
                Thread.sleep(50)
            }
            decodeThread.join(1000)
        }
        decodeThreadRef = null
        recordingThread?.join(3000)
        recordingThread = null

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
