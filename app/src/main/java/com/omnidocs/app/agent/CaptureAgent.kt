package com.omnidocs.app.agent

import android.content.Context
import android.net.Uri
import com.omnidocs.app.data.local.SourceDocumentDao
import com.omnidocs.app.data.local.entity.SourceDocumentEntity
import dagger.hilt.android.qualifiers.ApplicationContext
import java.security.MessageDigest
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Agent responsible for accepting source materials (files, URIs, camera scans),
 * calculating SHA-256 checksums, and registering durable [SourceDocumentEntity] records.
 */
@Singleton
class CaptureAgent @Inject constructor(
    @ApplicationContext private val appContext: Context,
    private val sourceDocumentDao: SourceDocumentDao
) : Agent {

    override val id: String = "agent_capture"

    override suspend fun execute(input: AgentInput, context: AgentContext): AgentResult {
        if (context.isCancelled()) {
            return AgentResult.PermanentFailure("Capture cancelled by user")
        }

        val uriString = input.payload["uri"]
            ?: return AgentResult.PermanentFailure("Missing 'uri' in capture payload")
        val mimeType = input.payload["mimeType"] ?: "application/octet-stream"
        val fileName = input.payload["fileName"] ?: "captured_file"

        val uri = try {
            Uri.parse(uriString)
        } catch (e: Exception) {
            return AgentResult.PermanentFailure("Invalid URI: $uriString", e)
        }

        val (checksum, fileSizeBytes) = try {
            calculateChecksumAndSize(uri)
        } catch (e: Exception) {
            // Fallback for non-resolvable stream (e.g. raw text inputs)
            val fallbackHash = MessageDigest.getInstance("SHA-256")
                .digest(uriString.toByteArray())
                .joinToString("") { "%02x".format(it) }
            Pair(fallbackHash, 0L)
        }

        // Check if duplicate source already exists
        val existing = sourceDocumentDao.getSourceDocumentByChecksum(checksum)
        val sourceDocId = existing?.id ?: UUID.randomUUID().toString()

        if (existing == null) {
            val entity = SourceDocumentEntity(
                id = sourceDocId,
                uri = uriString,
                mimeType = mimeType,
                fileName = fileName,
                checksum = checksum,
                fileSizeBytes = fileSizeBytes,
                pageCount = null,
                language = null,
                isEncrypted = false,
                importedAt = System.currentTimeMillis()
            )
            sourceDocumentDao.insertSourceDocument(entity)
        }

        return AgentResult.Success(
            payload = mapOf(
                "sourceDocumentId" to sourceDocId,
                "checksum" to checksum,
                "fileSizeBytes" to fileSizeBytes,
                "uri" to uriString,
                "mimeType" to mimeType,
                "fileName" to fileName,
                "isExisting" to (existing != null)
            )
        )
    }

    private fun calculateChecksumAndSize(uri: Uri): Pair<String, Long> {
        val digest = MessageDigest.getInstance("SHA-256")
        var totalBytes = 0L
        val buffer = ByteArray(8192)

        appContext.contentResolver.openInputStream(uri)?.use { stream ->
            var bytesRead: Int
            while (stream.read(buffer).also { bytesRead = it } != -1) {
                digest.update(buffer, 0, bytesRead)
                totalBytes += bytesRead
            }
        } ?: throw IllegalArgumentException("Cannot open input stream for $uri")

        val hash = digest.digest().joinToString("") { "%02x".format(it) }
        return Pair(hash, totalBytes)
    }
}
