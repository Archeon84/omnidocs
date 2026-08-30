package com.omnidocs.app.agent

import android.content.Context
import android.net.Uri
import com.omnidocs.app.data.backup.LocalBackupService
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.security.MessageDigest
import java.util.zip.ZipInputStream
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Agent responsible for generating verifiable backup snapshots,
 * computing SHA-256 archive checksums, and validating restore archives in isolation.
 */
@Singleton
class BackupAgent @Inject constructor(
    @ApplicationContext private val appContext: Context,
    private val localBackupService: LocalBackupService
) : Agent {

    override val id: String = "agent_backup"

    override suspend fun execute(input: AgentInput, context: AgentContext): AgentResult {
        if (context.isCancelled()) {
            return AgentResult.PermanentFailure("Backup operation cancelled by user")
        }

        val operation = input.payload["operation"] ?: "VERIFY"
        val password = input.payload["password"]
        return when (operation) {
            "CREATE_BACKUP" -> {
                val folderUriStr = input.payload["folderUri"]
                    ?: return AgentResult.PermanentFailure("Missing folderUri for backup creation")
                val uri = Uri.parse(folderUriStr)
                val result = localBackupService.createBackup(uri, password)
                if (result.success) {
                    AgentResult.Success(
                        payload = mapOf(
                            "noteCount" to result.noteCount,
                            "recordingCount" to result.recordingCount,
                            "audioFileCount" to result.audioFileCount,
                            "message" to result.message
                        )
                    )
                } else {
                    AgentResult.PermanentFailure("Backup creation failed: ${result.message}")
                }
            }
            "VERIFY_ARCHIVE" -> {
                val fileUriStr = input.payload["fileUri"]
                    ?: return AgentResult.PermanentFailure("Missing fileUri for archive verification")
                val uri = Uri.parse(fileUriStr)
                val verification = verifyArchive(uri)
                if (verification.isValid) {
                    AgentResult.Success(
                        payload = mapOf(
                            "isValid" to true,
                            "schemaVersion" to verification.schemaVersion,
                            "checksum" to verification.checksum,
                            "fileSize" to verification.fileSize
                        )
                    )
                } else {
                    AgentResult.PermanentFailure("Archive verification failed: ${verification.error}")
                }
            }
            "RESTORE" -> {
                val fileUriStr = input.payload["fileUri"]
                    ?: return AgentResult.PermanentFailure("Missing fileUri for restore")
                val uri = Uri.parse(fileUriStr)
                val result = localBackupService.restoreBackup(uri, password)
                if (result.success) {
                    AgentResult.Success(
                        payload = mapOf(
                            "noteCount" to result.noteCount,
                            "recordingCount" to result.recordingCount,
                            "audioFileCount" to result.audioFileCount,
                            "message" to result.message
                        )
                    )
                } else {
                    AgentResult.PermanentFailure("Restore failed: ${result.message}")
                }
            }
            else -> AgentResult.PermanentFailure("Unknown backup operation: $operation")
        }
    }

    data class ArchiveValidation(
        val isValid: Boolean,
        val schemaVersion: Int = 0,
        val checksum: String = "",
        val fileSize: Long = 0L,
        val error: String? = null
    )

    private fun verifyArchive(uri: Uri): ArchiveValidation {
        return try {
            val digest = MessageDigest.getInstance("SHA-256")
            var totalBytes = 0L
            var hasMetadata = false
            var hasNotes = false

            appContext.contentResolver.openInputStream(uri)?.use { stream ->
                val buffer = ByteArray(8192)
                var bytesRead: Int
                while (stream.read(buffer).also { bytesRead = it } != -1) {
                    digest.update(buffer, 0, bytesRead)
                    totalBytes += bytesRead
                }
            } ?: return ArchiveValidation(false, error = "Cannot open input stream for $uri")

            val checksum = digest.digest().joinToString("") { "%02x".format(it) }

            if (localBackupService.isEncryptedBackup(uri)) {
                return ArchiveValidation(
                    isValid = true,
                    schemaVersion = 2,
                    checksum = checksum,
                    fileSize = totalBytes
                )
            }

            // Inspect ZIP entries for unencrypted archives
            appContext.contentResolver.openInputStream(uri)?.use { stream ->
                ZipInputStream(stream).use { zis ->
                    var entry = zis.nextEntry
                    while (entry != null) {
                        if (entry.name == "metadata.json") hasMetadata = true
                        if (entry.name == "notes.json") hasNotes = true
                        zis.closeEntry()
                        entry = zis.nextEntry
                    }
                }
            }

            if (hasMetadata && hasNotes) {
                ArchiveValidation(
                    isValid = true,
                    schemaVersion = 2,
                    checksum = checksum,
                    fileSize = totalBytes
                )
            } else {
                ArchiveValidation(
                    isValid = false,
                    error = "Archive missing essential metadata or notes components"
                )
            }
        } catch (e: Exception) {
            ArchiveValidation(false, error = e.message ?: "Archive verification threw exception")
        }
    }
}
