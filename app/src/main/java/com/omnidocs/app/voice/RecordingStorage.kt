package com.omnidocs.app.voice

import android.content.Context
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.io.FileInputStream
import java.security.MessageDigest
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "RecordingStorage"
private const val RECORDINGS_DIR = "recordings"

/**
 * Manages audio file storage for voice recordings.
 * Saves audio to app-private storage with SHA-256 integrity hashes.
 */
@Singleton
class RecordingStorage @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private val recordingsDir: File by lazy {
        File(context.filesDir, RECORDINGS_DIR).also { it.mkdirs() }
    }

    /**
     * Save raw PCM audio data to a file.
     * @param audioData raw PCM bytes
     * @param extension file extension (e.g., "pcm", "wav")
     * @return the saved file's storage key (UUID filename)
     */
    fun saveAudio(audioData: ByteArray, extension: String = "pcm"): String {
        val filename = "${UUID.randomUUID()}.$extension"
        val file = File(recordingsDir, filename)
        file.writeBytes(audioData)
        Log.d(TAG, "Saved audio: ${file.absolutePath} (${audioData.size} bytes)")
        return filename
    }

    /**
     * Write audio bytes to a specific storage key (used by local backup restore).
     * The key already embeds the extension (e.g. "xxx.pcm"), so it is used verbatim
     * as the filename to keep note attachment references valid after a reinstall.
     */
    fun saveAudioWithKey(audioData: ByteArray, storageKey: String): String {
        // storageKey comes from backup files (attacker-influenced): strip any
        // path components and verify containment, or "../../x" escapes recordings/.
        val safeName = File(storageKey).name
        require(safeName.isNotBlank() && safeName != "." && safeName != "..") {
            "Invalid storage key: $storageKey"
        }
        val dest = File(recordingsDir, safeName)
        require(dest.canonicalPath.startsWith(recordingsDir.canonicalPath + File.separator)) {
            "Storage key escapes recordings dir: $storageKey"
        }
        dest.writeBytes(audioData)
        Log.d(TAG, "Restored audio: $safeName (${audioData.size} bytes)")
        return safeName
    }

    /**
     * Get the File object for a stored recording.
     */
    fun getRecordingFile(storageKey: String): File? {
        val file = File(recordingsDir, storageKey)
        return if (file.exists()) file else null
    }

    /**
     * Delete a recording audio file.
     * @return true if deleted, false if not found
     */
    fun deleteRecording(storageKey: String): Boolean {
        val file = File(recordingsDir, storageKey)
        val deleted = file.delete()
        if (deleted) {
            Log.d(TAG, "Deleted recording: $storageKey")
        }
        return deleted
    }

    /**
     * Compute SHA-256 hash of a recording file for integrity verification.
     */
    fun computeHash(storageKey: String): String? {
        val file = File(recordingsDir, storageKey) ?: return null
        if (!file.exists()) return null

        val digest = MessageDigest.getInstance("SHA-256")
        FileInputStream(file).use { fis ->
            val buffer = ByteArray(8192)
            var bytesRead: Int
            while (fis.read(buffer).also { bytesRead = it } != -1) {
                digest.update(buffer, 0, bytesRead)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    /**
     * Get total storage used by recordings in bytes.
     */
    fun getStorageUsed(): Long {
        return recordingsDir.listFiles()?.sumOf { it.length() } ?: 0L
    }

    /**
     * List all stored recording files.
     */
    fun listRecordings(): List<File> {
        return recordingsDir.listFiles()?.toList() ?: emptyList()
    }
}
