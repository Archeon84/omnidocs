package com.omnidocs.app.voice

import android.content.Context
import android.media.MediaPlayer
import android.media.PlaybackParams
import android.os.Build
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
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
    private var tickerJob: Job? = null
    private var playbackSpeed = 1f

    private val controllerScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val _currentPlayingKey = MutableStateFlow<String?>(null)
    val currentPlayingKey: StateFlow<String?> = _currentPlayingKey.asStateFlow()

    private val _playbackPositionMs = MutableStateFlow(0L)
    val playbackPositionMs: StateFlow<Long> = _playbackPositionMs.asStateFlow()

    private val _playbackDurationMs = MutableStateFlow(0L)
    val playbackDurationMs: StateFlow<Long> = _playbackDurationMs.asStateFlow()

    private val _isPaused = MutableStateFlow(false)
    val isPaused: StateFlow<Boolean> = _isPaused.asStateFlow()

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
                stopTicker()
                _playbackPositionMs.value = 0L
                _isPaused.value = false
                _currentPlayingKey.value = null
                releasePlayer()
            }
            mp.prepare()
            applySpeedLocked(mp, playbackSpeed)
            mp.start()
            player = mp
            _currentPlayingKey.value = storageKey
            _isPaused.value = false
            try {
                _playbackDurationMs.value = mp.duration.toLong().coerceAtLeast(0L)
            } catch (e: Exception) {
                Log.w(TAG, "Could not read duration", e)
            }
            _playbackPositionMs.value = 0L
            startTicker()
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
        stopTicker()
        releasePlayer()
        _currentPlayingKey.value = null
        _playbackPositionMs.value = 0L
        _isPaused.value = false
    }

    /** Toggle playback of [storageKey]; stops it if it is already the active one. */
    fun toggle(storageKey: String): Boolean {
        if (_currentPlayingKey.value == storageKey) {
            stop()
            return true
        }
        return play(storageKey)
    }

    /**
     * Pause/resume toggle for the active [storageKey]. Unlike [toggle], pausing
     * keeps the position so playback resumes where it left off. Playing a
     * different key starts it from the beginning.
     */
    fun togglePause(storageKey: String): Boolean {
        if (_currentPlayingKey.value != storageKey) {
            return play(storageKey)
        }
        return if (_isPaused.value) resume() else pause()
    }

    /** Pause the active playback, keeping the current position. */
    fun pause(): Boolean {
        val mp = player ?: return false
        return try {
            if (mp.isPlaying) {
                mp.pause()
            }
            stopTicker()
            _isPaused.value = true
            try {
                _playbackPositionMs.value = mp.currentPosition.toLong()
            } catch (_: Exception) {
            }
            true
        } catch (e: Exception) {
            Log.e(TAG, "Pause failed", e)
            false
        }
    }

    /** Resume paused playback. */
    fun resume(): Boolean {
        val mp = player ?: return false
        return try {
            mp.start()
            _isPaused.value = false
            startTicker()
            true
        } catch (e: Exception) {
            Log.e(TAG, "Resume failed", e)
            false
        }
    }

    /** Seek the active playback to [positionMs]. */
    fun seekTo(positionMs: Long): Boolean {
        val mp = player ?: return false
        return try {
            val duration = try {
                mp.duration.toLong()
            } catch (_: Exception) {
                Long.MAX_VALUE
            }
            val clamped = positionMs.coerceIn(0L, duration)
            mp.seekTo(clamped.toInt())
            _playbackPositionMs.value = clamped
            true
        } catch (e: Exception) {
            Log.e(TAG, "Seek failed", e)
            false
        }
    }

    /** Set playback speed for new and active playback (1.0 = normal). */
    fun setSpeed(speed: Float) {
        playbackSpeed = speed.coerceIn(0.5f, 2.5f)
        player?.let { applySpeedLocked(it, playbackSpeed) }
    }

    fun getSpeed(): Float = playbackSpeed

    fun isPlaying(storageKey: String): Boolean = _currentPlayingKey.value == storageKey

    fun isPausedKey(storageKey: String): Boolean =
        _currentPlayingKey.value == storageKey && _isPaused.value

    private fun applySpeedLocked(mp: MediaPlayer, speed: Float) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return
        try {
            mp.playbackParams = (mp.playbackParams ?: PlaybackParams()).setSpeed(speed)
        } catch (e: Exception) {
            Log.w(TAG, "Could not set playback speed to $speed", e)
        }
    }

    private fun startTicker() {
        stopTicker()
        tickerJob = controllerScope.launch {
            while (isActive) {
                delay(250)
                try {
                    val mp = player
                    if (mp == null) {
                        _playbackPositionMs.value = 0L
                        break
                    }
                    _playbackPositionMs.value = mp.currentPosition.toLong().coerceAtLeast(0L)
                } catch (_: Exception) {
                    break
                }
            }
        }
    }

    private fun stopTicker() {
        tickerJob?.cancel()
        tickerJob = null
    }

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
