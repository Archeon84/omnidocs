package com.omnidocs.app.data.backup

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import android.util.Log
import com.omnidocs.app.data.local.NoteDao
import com.omnidocs.app.data.local.NotesDatabase
import com.omnidocs.app.data.local.RecordingDao
import com.omnidocs.app.data.local.entity.NoteEntity
import com.omnidocs.app.data.local.entity.RecordingEntity
import com.omnidocs.app.voice.RecordingStorage
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStream
import java.security.SecureRandom
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import javax.crypto.AEADBadTagException
import javax.crypto.Cipher
import javax.crypto.CipherInputStream
import javax.crypto.CipherOutputStream
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec
import javax.inject.Inject
import javax.inject.Singleton
import androidx.room.withTransaction

private const val TAG = "LocalBackupService"
private const val SCHEMA_VERSION = 3

private val MAGIC_HEADER = "OMNI_ENC_V1".toByteArray(Charsets.UTF_8)
private const val SALT_LENGTH_BYTES = 16
private const val IV_LENGTH_BYTES = 12
private const val TAG_LENGTH_BITS = 128
private const val PBKDF2_ITERATIONS = 65536
private const val KEY_LENGTH_BITS = 256

// Restore hard limits: a crafted backup selected by the user must not be able to
// exhaust the heap (fully buffered JSON entries) or fill storage (audio/attachments).
private const val MAX_ZIP_ENTRIES = 10_000
private const val MAX_JSON_ENTRY_BYTES = 64L * 1024 * 1024      // 64 MB per JSON entry
private const val MAX_BINARY_ENTRY_BYTES = 512L * 1024 * 1024   // 512 MB per media entry
private const val MAX_TOTAL_RESTORE_BYTES = 2L * 1024 * 1024 * 1024 // 2 GB expanded total

// Knowledge-layer tables backed up generically (every column, one JSON array per
// table). notes/recordings have dedicated entity code (conflict resolution +
// deletedAt preservation); notes_fts is a virtual table rebuilt by the notes
// triggers. Adding a table here automatically includes it in backups.
// source_documents/content_blocks carry provenance; agent_jobs/agent_events/
// ai_artifacts carry agent history; ai_runs covers the rest of the AI pipeline.
private val KNOWLEDGE_TABLES = listOf(
    "transcript_segments", "speakers", "claims", "evidence_links",
    "action_items", "entities", "entity_mentions", "note_links",
    "embeddings", "ai_runs", "note_versions", "audit_events", "saved_searches",
    "source_documents", "content_blocks", "agent_jobs", "agent_events", "ai_artifacts"
)

/**
 * Result of a local backup or restore operation.
 */
data class BackupResult(
    val success: Boolean,
    val message: String,
    val noteCount: Int = 0,
    val recordingCount: Int = 0,
    val audioFileCount: Int = 0,
    val missingAudioCount: Int = 0
)

/**
 * Offline backup to the phone's local storage via SAF. Notes and voice recordings
 * are bundled into a single ZIP so the backup survives app uninstall (the SQLCipher
 * DB passphrase is app-private and regenerates on reinstall, so a raw DB copy would
 * be unreadable after reinstall -- JSON is the portable format).
 */
@Singleton
class LocalBackupService @Inject constructor(
    @ApplicationContext private val context: Context,
    private val noteDao: NoteDao,
    private val recordingDao: RecordingDao,
    private val recordingStorage: RecordingStorage,
    private val notesDatabase: NotesDatabase
) {

    /**
     * Check if a backup archive at the given URI is encrypted with AES-256-GCM.
     */
    fun isEncryptedBackup(sourceUri: Uri): Boolean {
        return try {
            context.contentResolver.openInputStream(sourceUri)?.use { input ->
                val magic = ByteArray(MAGIC_HEADER.size)
                var totalRead = 0
                while (totalRead < magic.size) {
                    val read = input.read(magic, totalRead, magic.size - totalRead)
                    if (read == -1) break
                    totalRead += read
                }
                totalRead == MAGIC_HEADER.size && magic.contentEquals(MAGIC_HEADER)
            } ?: false
        } catch (e: Exception) {
            false
        }
    }

    /**
     * Write a fresh backup ZIP into the user-picked folder, optionally encrypted with AES-256-GCM.
     */
    suspend fun createBackup(folderUri: Uri, password: String? = null): BackupResult = withContext(Dispatchers.IO) {
        var tempZipFile: File? = null
        try {
            val notes = noteDao.getAllNotesIncludeDeleted()
            val recordings = recordingDao.getAllRecordingsIncludeDeleted()

            val notesArray = JSONArray()
            notes.forEach { note ->
                notesArray.put(
                    JSONObject().apply {
                        put("id", note.id)
                        put("title", note.title)
                        put("content", note.content)
                        put("plainText", note.plainText)
                        put("isPinned", note.isPinned)
                        put("language", note.language)
                        put("createdAt", note.createdAt)
                        put("updatedAt", note.updatedAt)
                        put("imageUrl", note.imageUrl ?: "")
                        put("attachments", note.attachments)
                        put("isDeleted", note.isDeleted)
                        put("deletedAt", note.deletedAt ?: 0L)
                        put("tags", note.tags)
                        put("relatedNotes", note.relatedNotes)
                    }
                )
            }

            val recordingsArray = JSONArray()
            recordings.forEach { r ->
                recordingsArray.put(
                    JSONObject().apply {
                        put("id", r.id)
                        put("noteId", r.noteId)
                        put("filename", r.filename)
                        put("storageKey", r.storageKey)
                        put("durationMs", r.durationMs)
                        put("language", r.language)
                        put("processingMode", r.processingMode)
                        put("processingStatus", r.processingStatus)
                        put("createdAt", r.createdAt)
                        put("deletedAt", r.deletedAt ?: 0L)
                    }
                )
            }

            var audioFileCount = 0
            var missingAudioCount = 0
            val zipFileName = "OmniDocs-backup-" +
                SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date()) + ".zip"

            // OpenDocumentTree returns a *tree* URI, but createDocument requires a
            // *document* URI of the directory. Build it from the tree's document ID,
            // otherwise the provider throws "invalid uri".
            val treeDocumentId = DocumentsContract.getTreeDocumentId(folderUri)
            val directoryUri = DocumentsContract.buildDocumentUriUsingTree(folderUri, treeDocumentId)
            val documentUri = DocumentsContract.createDocument(
                context.contentResolver, directoryUri, "application/zip", zipFileName
            ) ?: throw Exception("Failed to create backup file")

            val isEncrypted = !password.isNullOrBlank()

            // If encrypted, build the ZIP in a cache file first, then encrypt to destination
            val zipTargetStream = if (isEncrypted) {
                tempZipFile = File(context.cacheDir, "backup_temp_${System.currentTimeMillis()}.zip")
                FileOutputStream(tempZipFile)
            } else {
                context.contentResolver.openOutputStream(documentUri, "wt")
                    ?: throw Exception("Failed to open backup output")
            }

            zipTargetStream.use { rawOs ->
                ZipOutputStream(rawOs.buffered()).use { zip ->
                    // manifest
                    val manifest = JSONObject().apply {
                        put("schemaVersion", SCHEMA_VERSION)
                        put("appVersion", appVersionName())
                        put("createdAt", System.currentTimeMillis())
                        put("noteCount", notes.size)
                        put("recordingCount", recordings.size)
                        put("isEncrypted", isEncrypted)
                    }
                    writeZipEntry(zip, "manifest.json", manifest.toString().toByteArray())

                    writeZipEntry(zip, "notes.json", notesArray.toString().toByteArray())
                    writeZipEntry(zip, "recordings.json", recordingsArray.toString().toByteArray())

                    // Knowledge tables: dump every column generically so the backup
                    // is self-contained across reinstalls and picks up future columns
                    // automatically. BLOB columns (embeddings.embeddingVector) are
                    // Base64-encoded to keep the JSON portable.
                    val db = notesDatabase.openHelper.readableDatabase
                    for (table in KNOWLEDGE_TABLES) {
                        val tableArray = JSONArray()
                        db.query("SELECT * FROM $table").use { cursor ->
                            while (cursor.moveToNext()) {
                                val row = JSONObject()
                                for (colIndex in 0 until cursor.columnCount) {
                                    val col = cursor.getColumnName(colIndex)
                                    when (cursor.getType(colIndex)) {
                                        android.database.Cursor.FIELD_TYPE_INTEGER -> row.put(col, cursor.getLong(colIndex))
                                        android.database.Cursor.FIELD_TYPE_FLOAT -> row.put(col, cursor.getDouble(colIndex))
                                        android.database.Cursor.FIELD_TYPE_STRING -> row.put(col, cursor.getString(colIndex))
                                        android.database.Cursor.FIELD_TYPE_BLOB -> row.put(
                                            col,
                                            android.util.Base64.encodeToString(cursor.getBlob(colIndex), android.util.Base64.NO_WRAP)
                                        )
                                        android.database.Cursor.FIELD_TYPE_NULL -> row.put(col, JSONObject.NULL)
                                    }
                                }
                                tableArray.put(row)
                            }
                        }
                        writeZipEntry(zip, "$table.json", tableArray.toString().toByteArray())
                    }

                    // audio files (skip missing; record but don't fail the backup)
                    recordings.forEach { r ->
                        val audio = recordingStorage.getRecordingFile(r.storageKey)
                        if (audio != null && audio.exists()) {
                            zip.putNextEntry(ZipEntry("recordings/${r.storageKey}"))
                            audio.inputStream().use { input ->
                                val buffer = ByteArray(8192)
                                var read: Int
                                while (input.read(buffer).also { read = it } != -1) {
                                    zip.write(buffer, 0, read)
                                }
                            }
                            zip.closeEntry()
                            audioFileCount++
                        } else {
                            missingAudioCount++
                            Log.w(TAG, "Missing audio for ${r.storageKey}")
                        }
                    }

                    // Editor attachments (images/audio embedded in notes live under
                    // filesDir/attachments). Without this the backup loses every
                    // attached media file even though the note rows restore fine.
                    val attachmentsDir = File(context.filesDir, "attachments")
                    if (attachmentsDir.isDirectory) {
                        attachmentsDir.listFiles()?.forEach { attachment ->
                            if (attachment.isFile) {
                                zip.putNextEntry(ZipEntry("attachments/${attachment.name}"))
                                attachment.inputStream().use { input ->
                                    val buffer = ByteArray(8192)
                                    var read: Int
                                    while (input.read(buffer).also { read = it } != -1) {
                                        zip.write(buffer, 0, read)
                                    }
                                }
                                zip.closeEntry()
                            }
                        }
                    }
                }
            }

            // If encryption is enabled, encrypt the temp zip to destination
            if (isEncrypted && tempZipFile != null) {
                val salt = ByteArray(SALT_LENGTH_BYTES).also { SecureRandom().nextBytes(it) }
                val iv = ByteArray(IV_LENGTH_BYTES).also { SecureRandom().nextBytes(it) }

                val keySpec = PBEKeySpec(password!!.toCharArray(), salt, PBKDF2_ITERATIONS, KEY_LENGTH_BITS)
                val factory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
                val secretKey = SecretKeySpec(factory.generateSecret(keySpec).encoded, "AES")

                val cipher = Cipher.getInstance("AES/GCM/NoPadding")
                cipher.init(Cipher.ENCRYPT_MODE, secretKey, GCMParameterSpec(TAG_LENGTH_BITS, iv))

                val destOs = context.contentResolver.openOutputStream(documentUri, "wt")
                    ?: throw Exception("Failed to open encrypted backup destination")

                destOs.use { out ->
                    out.write(MAGIC_HEADER)
                    out.write(salt)
                    out.write(iv)
                    CipherOutputStream(out, cipher).use { cipherOut ->
                        FileInputStream(tempZipFile).use { fileIn ->
                            val buffer = ByteArray(8192)
                            var read: Int
                            while (fileIn.read(buffer).also { read = it } != -1) {
                                cipherOut.write(buffer, 0, read)
                            }
                        }
                    }
                }
            }

            val encLabel = if (isEncrypted) " (AES-256 encrypted)" else ""
            val message = "Backed up ${notes.size} notes, ${recordings.size} recordings$encLabel" +
                if (missingAudioCount > 0) " ($missingAudioCount audio missing)" else ""
            BackupResult(true, message, notes.size, recordings.size, audioFileCount, missingAudioCount)
        } catch (e: Exception) {
            Log.e(TAG, "createBackup failed", e)
            BackupResult(false, "Backup failed: ${e.message}")
        } finally {
            tempZipFile?.delete()
        }
    }

    /**
     * Read a backup ZIP and merge notes/recordings back into local storage.
     * Supports both AES-256-GCM encrypted backups and legacy unencrypted ZIP backups.
     */
    suspend fun restoreBackup(sourceUri: Uri, password: String? = null): BackupResult = withContext(Dispatchers.IO) {
        var tempRestoreZip: File? = null
        try {
            val jsonEntries = mutableMapOf<String, ByteArray>()
            val restoredKeys = mutableSetOf<String>()
            var audioFileCount = 0

            // Determine if archive is encrypted
            val isEncrypted = isEncryptedBackup(sourceUri)

            if (isEncrypted) {
                if (password.isNullOrBlank()) {
                    return@withContext BackupResult(false, "Password required: this backup is encrypted with AES-256-GCM")
                }

                tempRestoreZip = File(context.cacheDir, "restore_temp_${System.currentTimeMillis()}.zip")
                try {
                    context.contentResolver.openInputStream(sourceUri).use { input ->
                        if (input == null) throw Exception("Failed to open backup file")

                        // Skip magic header
                        val magic = ByteArray(MAGIC_HEADER.size)
                        readFully(input, magic)

                        // Read salt and IV
                        val salt = ByteArray(SALT_LENGTH_BYTES)
                        readFully(input, salt)

                        val iv = ByteArray(IV_LENGTH_BYTES)
                        readFully(input, iv)

                        val keySpec = PBEKeySpec(password.toCharArray(), salt, PBKDF2_ITERATIONS, KEY_LENGTH_BITS)
                        val factory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
                        val secretKey = SecretKeySpec(factory.generateSecret(keySpec).encoded, "AES")

                        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
                        cipher.init(Cipher.DECRYPT_MODE, secretKey, GCMParameterSpec(TAG_LENGTH_BITS, iv))

                        FileOutputStream(tempRestoreZip).use { fos ->
                            CipherInputStream(input, cipher).use { cipherIn ->
                                val buffer = ByteArray(8192)
                                var read: Int
                                while (cipherIn.read(buffer).also { read = it } != -1) {
                                    fos.write(buffer, 0, read)
                                }
                            }
                        }
                    }
                } catch (e: Exception) {
                    val root = generateSequence(e as Throwable) { it.cause }.lastOrNull()
                    if (root is AEADBadTagException || e is AEADBadTagException ||
                        e.message?.contains("tag", ignoreCase = true) == true ||
                        e.message?.contains("pad", ignoreCase = true) == true) {
                        return@withContext BackupResult(false, "Incorrect password or corrupted backup file")
                    }
                    throw e
                }

                // Process decrypted zip
                FileInputStream(tempRestoreZip).use { fileIn ->
                    ZipInputStream(fileIn.buffered()).use { zip ->
                        readZipEntries(zip, jsonEntries, restoredKeys, { audioFileCount++ })
                    }
                }
            } else {
                // Legacy / Unencrypted ZIP
                context.contentResolver.openInputStream(sourceUri).use { input ->
                    if (input == null) throw Exception("Failed to open backup file")
                    ZipInputStream(input.buffered()).use { zip ->
                        readZipEntries(zip, jsonEntries, restoredKeys, { audioFileCount++ })
                    }
                }
            }

            val notesArray = jsonEntries["notes.json"]?.let { JSONArray(String(it)) }
            val recordingsArray = jsonEntries["recordings.json"]?.let { JSONArray(String(it)) }

            val noteCount = notesArray?.length() ?: 0
            val recordingCount = recordingsArray?.length() ?: 0

            notesDatabase.withTransaction {
                // Recordings: REPLACE by id; clear deletedAt so a deleted recording
                // is brought back visible. Missing audio is still restored as a row.
                if (recordingsArray != null) {
                    for (i in 0 until recordingsArray.length()) {
                        val o = recordingsArray.getJSONObject(i)
                        // Restore the deleted timestamp verbatim: a recording that was
                        // soft-deleted before this backup must stay deleted after restore.
                        // (Backup writes 0 for never-deleted recordings.)
                        val deletedAt = o.optLong("deletedAt", 0L).takeIf { it > 0L }
                        val rec = RecordingEntity(
                            id = o.getString("id"),
                            noteId = o.optString("noteId"),
                            filename = o.getString("filename"),
                            storageKey = o.getString("storageKey"),
                            durationMs = o.getLong("durationMs"),
                            language = o.optString("language"),
                            processingMode = o.optString("processingMode", "local"),
                            processingStatus = o.optString("processingStatus", "completed"),
                            createdAt = o.getLong("createdAt"),
                            deletedAt = deletedAt
                        )
                        recordingDao.insertRecording(rec)
                    }
                }

                // Notes: conflict resolution -- the backup copy wins when there is
                // no local note or the backup is at least as new; otherwise keep the
                // local note. The one exception: a note that was deleted locally
                // AFTER this backup was taken must NOT be resurrected (restore would
                // otherwise undo a deliberate, more-recent deletion). Soft-delete does
                // not bump updatedAt, so updatedAt cannot detect this -- deletedAt can:
                // a deletion timestamp newer than the backup's copy of the note means
                // the deletion happened after this backup was written.
                if (notesArray != null) {
                    for (i in 0 until notesArray.length()) {
                        val o = notesArray.getJSONObject(i)
                        val backupDeletedAt = o.optLong("deletedAt", 0L).takeIf { it > 0L }
                        val backupNote = NoteEntity(
                            id = o.getString("id"),
                            title = o.getString("title"),
                            content = o.getString("content"),
                            plainText = o.getString("plainText"),
                            isPinned = o.getBoolean("isPinned"),
                            language = o.getString("language"),
                            createdAt = o.getLong("createdAt"),
                            updatedAt = o.getLong("updatedAt"),
                            imageUrl = o.optString("imageUrl", "").ifEmpty { null },
                            attachments = o.optString("attachments", "[]"),
                            // Dirty on purpose: the cloud never acknowledged this
                            // row. Marking it synced would skip the next upload
                            // and strand restored data off-cloud until a later edit.
                            isSynced = false,
                            isDeleted = o.optBoolean("isDeleted", false),
                            deletedAt = backupDeletedAt,
                            tags = o.optString("tags", "[]"),
                            relatedNotes = o.optString("relatedNotes", "[]")
                        )
                        val local = noteDao.getNoteByIdIncludeDeleted(backupNote.id)
                        val backupWins = local == null || backupNote.updatedAt >= (local?.updatedAt ?: 0L)
                        // A local soft-delete AFTER the backup snapshot (deletion time
                        // newer than the backup's copy of the note) must survive restore.
                        // A note deleted BEFORE the backup has deletedAt <= backupNote.updatedAt,
                        // and the backup copy (itself deleted) is restored as deleted.
                        val localDeletedAfterBackup = local?.isDeleted == true &&
                            (local.deletedAt ?: 0L) > backupNote.updatedAt
                        if (backupWins && !localDeletedAfterBackup) {
                            noteDao.insertNote(backupNote)
                        }
                    }
                }

                // Knowledge tables: restore generically with INSERT OR REPLACE so the
                // backup is self-contained. Missing files (schema-v1 backups) are
                // tolerated; files for tables not in KNOWLEDGE_TABLES (future schema)
                // are simply never read, which is forward-compatible.
                val db = notesDatabase.openHelper.writableDatabase
                for (table in KNOWLEDGE_TABLES) {
                    val bytes = jsonEntries["$table.json"] ?: continue
                    val array = JSONArray(String(bytes))
                    // PRAGMA table_info exposes each column's declared type; BLOB
                    // columns (embeddings.embeddingVector) must be Base64-decoded
                    // back to bytes before binding.
                    //
                    // Column names from the backup file are validated against the
                    // live schema (validCols) before being interpolated into SQL --
                    // a crafted backup could otherwise inject SQL via a malicious
                    // column name. This also drops columns from a future schema the
                    // backup carries but this install doesn't have, which previously
                    // aborted the entire restore (SQLiteException: no such column).
                    val validCols = mutableSetOf<String>()
                    val blobCols = mutableSetOf<String>()
                    db.query("PRAGMA table_info($table)").use { c ->
                        while (c.moveToNext()) {
                            val name = c.getString(1)
                            validCols.add(name)
                            if (c.getString(2).uppercase().contains("BLOB")) blobCols.add(name)
                        }
                    }
                    for (i in 0 until array.length()) {
                        val row = array.getJSONObject(i)
                        val names = row.names() ?: continue
                        val cols = (0 until names.length())
                            .map { names.getString(it) }
                            .filter { it in validCols }
                        if (cols.isEmpty()) continue
                        val placeholders = cols.joinToString(",") { "?" }
                        val values = cols.map { col ->
                            val v = row.opt(col)
                            when {
                                v == JSONObject.NULL -> null
                                v is Boolean -> if (v) 1L else 0L
                                v is Int -> v.toLong()
                                v is Long -> v
                                v is Double -> v
                                v is String -> if (col in blobCols) android.util.Base64.decode(v, android.util.Base64.NO_WRAP) else v
                                else -> null
                            }
                        }
                        db.execSQL(
                            "INSERT OR REPLACE INTO $table (${cols.joinToString(",")}) VALUES ($placeholders)",
                            values.toTypedArray()
                        )
                    }
                }
            }

            val message = "Restored $noteCount notes, $recordingCount recordings, $audioFileCount audio files"
            BackupResult(true, message, noteCount, recordingCount, audioFileCount)
        } catch (e: Exception) {
            Log.e(TAG, "restoreBackup failed", e)
            BackupResult(false, "Restore failed: ${e.message}")
        } finally {
            tempRestoreZip?.delete()
        }
    }

    private fun readZipEntries(
        zip: ZipInputStream,
        jsonEntries: MutableMap<String, ByteArray>,
        restoredKeys: MutableSet<String>,
        onAudioRestored: () -> Unit
    ) {
        var entry: ZipEntry?
        var entryCount = 0
        var totalBytes = 0L
        while (zip.nextEntry.also { entry = it } != null) {
            val e = entry ?: continue
            // Enforce entry-count and cumulative expanded-size limits so a crafted
            // ZIP (zip bomb) cannot exhaust the heap or storage during restore.
            if (++entryCount > MAX_ZIP_ENTRIES) {
                throw IllegalStateException("Backup contains too many entries (limit $MAX_ZIP_ENTRIES)")
            }
            val name = e.name
            val isJson = name.endsWith(".json")
            val maxEntryBytes = if (isJson) MAX_JSON_ENTRY_BYTES else MAX_BINARY_ENTRY_BYTES
            val bytes = readAllBytes(zip, maxEntryBytes)
            totalBytes += bytes.size
            if (totalBytes > MAX_TOTAL_RESTORE_BYTES) {
                throw IllegalStateException("Backup exceeds total restore size limit ($MAX_TOTAL_RESTORE_BYTES bytes)")
            }
            when {
                isJson ->
                    jsonEntries[name] = bytes
                name.startsWith("recordings/") -> {
                    val storageKey = safeFileName(name.substringAfterLast('/'))
                    if (storageKey.isNotEmpty() && storageKey != "..") {
                        recordingStorage.saveAudioWithKey(bytes, storageKey)
                        restoredKeys.add(storageKey)
                        onAudioRestored()
                    }
                }
                name.startsWith("attachments/") -> {
                    val fileName = safeFileName(name.removePrefix("attachments/"))
                    if (fileName.isNotEmpty() && fileName != "..") {
                        val dir = File(context.filesDir, "attachments")
                        dir.mkdirs()
                        FileOutputStream(File(dir, fileName)).use { it.write(bytes) }
                    }
                }
            }
            zip.closeEntry()
        }
    }

    private fun readFully(input: InputStream, buffer: ByteArray) {
        var totalRead = 0
        while (totalRead < buffer.size) {
            val read = input.read(buffer, totalRead, buffer.size - totalRead)
            if (read == -1) throw java.io.EOFException("Unexpected end of stream while reading backup header")
            totalRead += read
        }
    }

    private fun writeZipEntry(zip: ZipOutputStream, name: String, data: ByteArray) {
        zip.putNextEntry(ZipEntry(name))
        zip.write(data)
        zip.closeEntry()
    }

    private fun readAllBytes(input: java.io.InputStream, maxBytes: Long = Long.MAX_VALUE): ByteArray {
        val out = ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        var read: Int
        while (input.read(buffer).also { read = it } != -1) {
            if (out.size() + read > maxBytes) {
                throw IllegalStateException("Backup entry exceeds maximum allowed size ($maxBytes bytes)")
            }
            out.write(buffer, 0, read)
        }
        return out.toByteArray()
    }

    /** Keep only the last path segment and reject anything that could escape the recordings dir. */
    private fun safeFileName(name: String): String {
        val clean = name.replace('\\', '/').substringAfterLast('/')
        return if (clean.contains("..") || clean.isEmpty()) "" else clean
    }

    private fun appVersionName(): String = try {
        context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "1.0"
    } catch (e: Exception) {
        "1.0"
    }
}
