package com.omnidocs.app.voice

import android.content.Context
import android.media.MediaPlayer
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "AudioPlaybackController"

// All app recordings are recorded by SherpaOnnxSttEngine as raw 16-bit PCM
// mono @ 16 kHz, so the WAV wrapper header is constant.
private const val SAMPLE_RATE = 16000
private const val CHANNELS = 1
private const val BITS_PER_SAMPLE = 16

/**
 * Plays voice-note recordings from app storage. Recordings are stored as raw
 * 16-bit PCM mono @ 16 kHz ([RecordingStorage]), which [MediaPlayer] cannot
 * decode directly, so each play wraps the bytes in an in-memory WAV container.
 *
 * A single controller instance is shared app-wide, so only one recording is
 * audible at a time; [currentPlayingKey] lets multiple chips reflect the same
 * state.
 */
@Singleton
class AudioPlaybackController @Inject constructor(
    @ApplicationContext private val context: Context,
    private val recordingStorage: RecordingStorage
) {
    private var player: MediaPlayer? = null

    private val _currentPlayingKey = MutableStateFlow<String?>(null)
    val currentPlayingKey: StateFlow<String?> = _currentPlayingKey.asStateFlow()

    /**
     * Start playing the recording identified by [storageKey].
     * Stops whatever is playing first. Returns false if the audio file is missing.
     */
    fun play(storageKey: String): Boolean {
        val file = recordingStorage.getRecordingFile(storageKey)
            ?: run {
                Log.w(TAG, "Audio file missing for $storageKey")
                return false
            }

        stop()

        val wavFile = wrapInWav(file, storageKey)
        if (wavFile == null) {
            Log.e(TAG, "Failed to wrap $storageKey in WAV")
            return false
        }

        return try {
            val mp = MediaPlayer()
            mp.setDataSource(wavFile.absolutePath)
            mp.setOnCompletionListener {
                _currentPlayingKey.value = null
                releasePlayer()
            }
            mp.prepare()
            mp.start()
            player = mp
            _currentPlayingKey.value = storageKey
            Log.d(TAG, "Playing $storageKey")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Playback failed for $storageKey", e)
            releasePlayer()
            false
        }
    }

    /** Stop and release the currently playing recording, if any. */
    fun stop() {
        releasePlayer()
        _currentPlayingKey.value = null
    }

    /** Toggle playback of [storageKey]; stops it if it is already the active one. */
    fun toggle(storageKey: String): Boolean {
        if (_currentPlayingKey.value == storageKey) {
            stop()
            return true
        }
        return play(storageKey)
    }

    fun isPlaying(storageKey: String): Boolean = _currentPlayingKey.value == storageKey

    private fun releasePlayer() {
        try {
            player?.release()
        } catch (e: Exception) {
            Log.w(TAG, "Error releasing player", e)
        }
        player = null
    }

    /**
     * Read the raw PCM file, prepend a 44-byte RIFF/WAVE header, and write the
     * result to a cache file MediaPlayer can consume. Cache files are cleaned by
     * the OS; the recordings themselves are never modified.
     */
    private fun wrapInWav(pcmFile: File, storageKey: String): File? {
        val pcmBytes = pcmFile.readBytes()
        if (pcmBytes.isEmpty()) return null

        val wavBytes = buildWav(pcmBytes)
        val safeName = storageKey.substringBeforeLast('.', storageKey).replace(Regex("[^a-zA-Z0-9_-]"), "") + ".wav"
        val wavFile = File(context.cacheDir, safeName)
        wavFile.writeBytes(wavBytes)
        return wavFile
    }

    /** Prepend the standard 44-byte RIFF/WAVE header to raw PCM16 mono audio. */
    private fun buildWav(pcm: ByteArray): ByteArray {
        val dataSize = pcm.size
        val byteRate = SAMPLE_RATE * CHANNELS * (BITS_PER_SAMPLE / 8)
        val blockAlign = CHANNELS * (BITS_PER_SAMPLE / 8)

        val header = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)
        header.put("RIFF".toByteArray(Charsets.US_ASCII))
        header.putInt(36 + dataSize)
        header.put("WAVE".toByteArray(Charsets.US_ASCII))
        header.put("fmt ".toByteArray(Charsets.US_ASCII))
        header.putInt(16)                 // fmt chunk size
        header.putShort(1)                // PCM
        header.putShort(CHANNELS.toShort())
        header.putInt(SAMPLE_RATE)
        header.putInt(byteRate)
        header.putShort(blockAlign.toShort())
        header.putShort(BITS_PER_SAMPLE.toShort())
        header.put("data".toByteArray(Charsets.US_ASCII))
        header.putInt(dataSize)

        return header.array() + pcm
    }
}
