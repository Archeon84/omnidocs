package com.omnidocs.app.ui.screens.localchat

import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import android.util.Log
import com.omnidocs.app.ai.PromptBudget
import com.omnidocs.app.docimport.ConversionOutcome
import com.omnidocs.app.docimport.DocumentConverterFactory
import com.omnidocs.app.ocr.OcrEngineFactory
import com.omnidocs.app.stt.SherpaOnnxSttEngine
import com.omnidocs.app.stt.SttEngineFactory
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "ChatAttachmentProcessor"
private const val MAX_ATTACHMENT_CHARS = 8000
private const val SAMPLE_RATE_TARGET = 16000
private const val MAX_AUDIO_DURATION_SECONDS = 180 // 3 minutes max for memory safety

/**
 * Handles offline on-device processing and text extraction for chat attachments:
 * - Documents: Extracted via [DocumentConverterFactory] (PDF, DOCX, TXT, MD, CSV, etc.)
 * - Images: Extracted via [OcrEngineFactory] on-device ML Kit OCR
 * - Audio: Decoded to 16kHz mono PCM and transcribed via [SttEngineFactory] (Sherpa-ONNX Whisper/Moonshine)
 */
@Singleton
class ChatAttachmentProcessor @Inject constructor(
    @ApplicationContext private val context: Context,
    private val documentConverterFactory: DocumentConverterFactory,
    private val ocrEngineFactory: OcrEngineFactory,
    private val sttEngineFactory: SttEngineFactory
) {

    /**
     * Determine the canonical attachment type: "image", "audio", or "document".
     */
    fun determineAttachmentType(mimeType: String, fileName: String): String {
        val ext = fileName.substringAfterLast('.', "").lowercase()
        return when {
            mimeType.startsWith("image/") || ext in listOf("jpg", "jpeg", "png", "webp", "gif", "bmp") -> "image"
            mimeType.startsWith("audio/") || ext in listOf("wav", "mp3", "m4a", "aac", "ogg", "flac", "opus") -> "audio"
            else -> "document"
        }
    }

    /**
     * Copies the picked attachment file into private app storage (`filesDir/chat_attachments`)
     * so that it remains permanently accessible across process lifecycles without SAF permission revocation.
     */
    suspend fun copyAttachmentToInternalStorage(context: Context, sourceUri: Uri, originalFileName: String): File =
        withContext(Dispatchers.IO) {
            val dir = File(context.filesDir, "chat_attachments").apply { mkdirs() }
            val cleanName = originalFileName.replace("[^a-zA-Z0-9._-]".toRegex(), "_")
            val destFile = File(dir, "${UUID.randomUUID()}_$cleanName")
            context.contentResolver.openInputStream(sourceUri)?.use { input ->
                destFile.outputStream().use { output ->
                    input.copyTo(output)
                }
            }
            destFile
        }

    /**
     * Extracts readable text on-device from the saved attachment file.
     */
    suspend fun extractText(
        context: Context,
        file: File,
        fileName: String,
        mimeType: String,
        type: String
    ): String = withContext(Dispatchers.IO) {
        val rawText = try {
            when (type) {
                "image" -> extractImageText(file)
                "audio" -> extractAudioText(context, file, fileName)
                else -> extractDocumentText(context, file, fileName, mimeType)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error extracting content from $fileName: ${e.message}", e)
            "Note: Failed to extract attachment content: ${e.message}"
        }

        budgetAndTruncate(rawText)
    }

    private suspend fun extractDocumentText(
        context: Context,
        file: File,
        fileName: String,
        mimeType: String
    ): String {
        val uri = Uri.fromFile(file)
        val converter = documentConverterFactory.getConverter(mimeType, fileName)
        return when (val outcome = converter.convert(context, uri, fileName)) {
            is ConversionOutcome.Success -> {
                val plainText = outcome.result.plainText.trim()
                if (plainText.isNotEmpty()) {
                    plainText
                } else {
                    "Note: Document was converted, but contained no readable text."
                }
            }
            is ConversionOutcome.Failure -> {
                "Note: Could not extract document text (${outcome.message})."
            }
        }
    }

    private suspend fun extractImageText(file: File): String {
        val uri = Uri.fromFile(file)
        val ocrEngine = ocrEngineFactory.getEngine()
        val ocrResult = ocrEngine.recognizeText(uri, "en")
        val recognizedText = ocrResult?.text?.trim()
        return if (!recognizedText.isNullOrBlank()) {
            recognizedText
        } else {
            "Note: On-device OCR scanned this image, but detected no readable text."
        }
    }

    private suspend fun extractAudioText(context: Context, file: File, fileName: String): String {
        val sttEngine = sttEngineFactory.getEngine()
        if (sttEngine !is SherpaOnnxSttEngine) {
            return "[Attached Audio: $fileName]\nNote: No offline speech recognition model is loaded. To transcribe audio offline, please download Whisper or Moonshine in Settings -> AI Models."
        }

        val pcmBytes = decodeAudioFileToPcm16k(file)
        if (pcmBytes == null || pcmBytes.isEmpty()) {
            return "[Attached Audio: $fileName]\nNote: Audio could not be decoded to PCM for speech recognition."
        }

        val sttResult = sttEngine.transcribePcm(pcmBytes)
        val text = sttResult?.text?.trim()
        return if (!text.isNullOrBlank()) {
            text
        } else {
            "Note: Audio recording was processed, but no clear speech was recognized."
        }
    }

    /**
     * Decodes an audio file to 16kHz 16-bit Mono PCM bytes.
     * Tries WAV header parser first for uncompressed audio, then falls back to MediaExtractor + MediaCodec.
     */
    fun decodeAudioFileToPcm16k(file: File): ByteArray? {
        val wavPcm = tryParseWavFile(file)
        if (wavPcm != null && wavPcm.isNotEmpty()) {
            return wavPcm
        }

        return decodeViaMediaCodec(file)
    }

    private fun tryParseWavFile(file: File): ByteArray? {
        if (!file.exists() || file.length() < 44) return null
        return try {
            FileInputStream(file).use { fis ->
                val header = ByteArray(44)
                if (fis.read(header) < 44) return null
                val riff = String(header, 0, 4)
                val wave = String(header, 8, 4)
                if (riff != "RIFF" || wave != "WAVE") return null

                val buffer = ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN)
                val audioFormat = buffer.getShort(20).toInt()
                val channels = buffer.getShort(22).toInt()
                val sampleRate = buffer.getInt(24)
                val bitsPerSample = buffer.getShort(34).toInt()

                if (audioFormat != 1 || bitsPerSample != 16) return null // Only PCM 16-bit

                val rawData = fis.readBytes()
                resampleAndDownmixTo16kMono(rawData, sampleRate, channels)
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun decodeViaMediaCodec(file: File): ByteArray? {
        val extractor = MediaExtractor()
        var codec: MediaCodec? = null
        try {
            extractor.setDataSource(file.absolutePath)
            var audioTrackIndex = -1
            var format: MediaFormat? = null
            for (i in 0 until extractor.trackCount) {
                val trackFormat = extractor.getTrackFormat(i)
                val mime = trackFormat.getString(MediaFormat.KEY_MIME) ?: ""
                if (mime.startsWith("audio/")) {
                    audioTrackIndex = i
                    format = trackFormat
                    break
                }
            }
            if (audioTrackIndex < 0 || format == null) {
                return null
            }
            extractor.selectTrack(audioTrackIndex)

            val mime = format.getString(MediaFormat.KEY_MIME) ?: return null
            val sampleRate = if (format.containsKey(MediaFormat.KEY_SAMPLE_RATE)) format.getInteger(MediaFormat.KEY_SAMPLE_RATE) else 44100
            val channels = if (format.containsKey(MediaFormat.KEY_CHANNEL_COUNT)) format.getInteger(MediaFormat.KEY_CHANNEL_COUNT) else 1

            codec = MediaCodec.createDecoderByType(mime)
            codec.configure(format, null, null, 0)
            codec.start()

            val outStream = ByteArrayOutputStream()
            val bufferInfo = MediaCodec.BufferInfo()
            var sawInputEos = false
            var sawOutputEos = false
            val timeoutUs = 5000L
            var totalDecodedBytes = 0
            val maxRawBytes = sampleRate * channels * 2 * MAX_AUDIO_DURATION_SECONDS

            while (!sawOutputEos && totalDecodedBytes < maxRawBytes) {
                if (!sawInputEos) {
                    val inputIndex = codec.dequeueInputBuffer(timeoutUs)
                    if (inputIndex >= 0) {
                        val inputBuffer = codec.getInputBuffer(inputIndex)
                        if (inputBuffer != null) {
                            val sampleSize = extractor.readSampleData(inputBuffer, 0)
                            if (sampleSize < 0) {
                                sawInputEos = true
                                codec.queueInputBuffer(inputIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            } else {
                                codec.queueInputBuffer(inputIndex, 0, sampleSize, extractor.sampleTime, 0)
                                extractor.advance()
                            }
                        }
                    }
                }

                var outputIndex = codec.dequeueOutputBuffer(bufferInfo, timeoutUs)
                while (outputIndex >= 0) {
                    val outputBuffer = codec.getOutputBuffer(outputIndex)
                    if (outputBuffer != null && bufferInfo.size > 0) {
                        val chunk = ByteArray(bufferInfo.size)
                        outputBuffer.position(bufferInfo.offset)
                        outputBuffer.get(chunk)
                        outStream.write(chunk)
                        totalDecodedBytes += bufferInfo.size
                    }
                    codec.releaseOutputBuffer(outputIndex, false)
                    if ((bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                        sawOutputEos = true
                        break
                    }
                    outputIndex = codec.dequeueOutputBuffer(bufferInfo, 0)
                }
            }

            val rawBytes = outStream.toByteArray()
            if (rawBytes.isEmpty()) return null
            return resampleAndDownmixTo16kMono(rawBytes, sampleRate, channels)
        } catch (e: Exception) {
            Log.e(TAG, "decodeViaMediaCodec failed: ${e.message}", e)
            return null
        } finally {
            try { codec?.stop() } catch (_: Exception) {}
            try { codec?.release() } catch (_: Exception) {}
            try { extractor.release() } catch (_: Exception) {}
        }
    }

    private fun resampleAndDownmixTo16kMono(rawBytes: ByteArray, srcSampleRate: Int, channels: Int): ByteArray {
        val totalSamples = rawBytes.size / 2
        if (totalSamples == 0) return ByteArray(0)
        val shortBuf = ByteBuffer.wrap(rawBytes).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
        val allSamples = ShortArray(totalSamples)
        shortBuf.get(allSamples)

        val monoSamples = if (channels > 1) {
            val monoLength = totalSamples / channels
            val mono = ShortArray(monoLength)
            for (i in 0 until monoLength) {
                var sum = 0
                for (c in 0 until channels) {
                    sum += allSamples[i * channels + c]
                }
                mono[i] = (sum / channels).toShort()
            }
            mono
        } else {
            allSamples
        }

        val target16kSamples = if (srcSampleRate == SAMPLE_RATE_TARGET) {
            monoSamples
        } else {
            val ratio = srcSampleRate.toDouble() / SAMPLE_RATE_TARGET.toDouble()
            val outLen = (monoSamples.size / ratio).toInt().coerceAtLeast(1)
            val resampled = ShortArray(outLen)
            for (i in 0 until outLen) {
                val srcIdx = i * ratio
                val i0 = srcIdx.toInt().coerceIn(0, monoSamples.size - 1)
                val i1 = (i0 + 1).coerceIn(0, monoSamples.size - 1)
                val frac = (srcIdx - i0).toFloat()
                resampled[i] = ((1f - frac) * monoSamples[i0] + frac * monoSamples[i1]).toInt().toShort()
            }
            resampled
        }

        val outByteBuffer = ByteBuffer.allocate(target16kSamples.size * 2).order(ByteOrder.LITTLE_ENDIAN)
        for (s in target16kSamples) {
            outByteBuffer.putShort(s)
        }
        return outByteBuffer.array()
    }

    private fun budgetAndTruncate(text: String): String {
        val budgeted = PromptBudget.truncateAtSentenceBoundary(text, MAX_ATTACHMENT_CHARS)
        return if (budgeted.length < text.length) {
            "$budgeted\n\n[... Remaining content truncated to fit on-device LLM context window ...]"
        } else {
            budgeted
        }
    }
}
