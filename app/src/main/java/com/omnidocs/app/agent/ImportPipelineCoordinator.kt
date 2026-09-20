package com.omnidocs.app.agent

import com.omnidocs.app.data.local.NoteDao
import com.omnidocs.app.data.local.entity.NoteEntity
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Result data class returned upon completion of Workflow 1 (Import to Knowledge).
 */
data class ImportPipelineResult(
    val jobId: String,
    val isSuccess: Boolean,
    val noteId: String? = null,
    val noteTitle: String? = null,
    val sourceDocumentId: String? = null,
    val checksum: String? = null,
    val language: String? = null,
    val blockCount: Int = 0,
    val taskCount: Int = 0,
    val errorMessage: String? = null
)

/**
 * High-level coordinator implementing Workflow 1: "Import to Knowledge Pipeline".
 * Orchestrates: Capture -> Extraction -> Normalization -> Organization -> Indexing.
 */
@Singleton
class ImportPipelineCoordinator @Inject constructor(
    private val agentCoordinator: AgentCoordinator,
    private val captureAgent: CaptureAgent,
    private val extractionAgent: ExtractionAgent,
    private val normalizationAgent: NormalizationAgent,
    private val organizationAgent: OrganizationAgent,
    private val indexingAgent: IndexingAgent,
    private val noteDao: NoteDao
) {
    private val _activeJobId = MutableStateFlow<String?>(null)
    /** Job ID of the currently running import, null when idle. Drives cancel UI. */
    val activeJobId: StateFlow<String?> = _activeJobId.asStateFlow()

    /** Note row saved by the active import (if it reached step 5). For cancel cleanup. */
    private var createdNoteId: String? = null

    /** Flag the active import as cancelled (takes effect at step boundaries). */
    suspend fun cancelActiveImport() {
        _activeJobId.value?.let { agentCoordinator.cancelJob(it) }
    }

    /** Take (and clear) the note ID saved by the active import, if any. */
    fun takeCreatedNoteId(): String? {
        val id = createdNoteId
        createdNoteId = null
        return id
    }

    /**
     * Executes the end-to-end import to knowledge pipeline.
     */
    suspend fun runImportPipeline(
        uri: String,
        mimeType: String,
        fileName: String,
        privacyMode: PrivacyMode = PrivacyMode.LOCAL_ONLY
    ): ImportPipelineResult = withContext(Dispatchers.IO) {
        // Create parent job
        val job = agentCoordinator.createJob(
            jobType = "WORKFLOW_IMPORT_TO_KNOWLEDGE",
            inputRefsJson = "{\"uri\":\"${escapeJsonString(uri)}\"," +
                "\"mimeType\":\"${escapeJsonString(mimeType)}\"," +
                "\"fileName\":\"${escapeJsonString(fileName)}\"}"
        )
        val jobId = job.id
        _activeJobId.value = jobId
        createdNoteId = null

        try {
            // Step 1: Capture (0% -> 20%)
            agentCoordinator.updateProgress(jobId, 10)
            val captureInput = AgentInput(
                type = "CAPTURE_INPUT",
                payload = mapOf("uri" to uri, "mimeType" to mimeType, "fileName" to fileName)
            )
            val captureResult = agentCoordinator.runAgent(jobId, captureAgent, captureInput, privacyMode)
            if (captureResult !is AgentResult.Success) {
                return@withContext failure(jobId, "Capture phase failed: ${(captureResult as? AgentResult.PermanentFailure)?.reason ?: "error"}")
            }
            val sourceDocId = captureResult.payload["sourceDocumentId"] as? String ?: ""
            val checksum = captureResult.payload["checksum"] as? String ?: ""

            // Step 2: Extraction (20% -> 40%)
            agentCoordinator.updateProgress(jobId, 30)
            val extractionInput = AgentInput(
                type = "EXTRACTION_INPUT",
                payload = mapOf("uri" to uri, "mimeType" to mimeType, "fileName" to fileName)
            )
            val extractionResult = agentCoordinator.runAgent(jobId, extractionAgent, extractionInput, privacyMode)
            if (extractionResult !is AgentResult.Success) {
                return@withContext failure(jobId, "Extraction phase failed: ${(extractionResult as? AgentResult.PermanentFailure)?.reason ?: "error"}")
            }
            val rawTitle = extractionResult.payload["title"] as? String ?: fileName
            val rawHtml = extractionResult.payload["htmlContent"] as? String ?: ""
            val rawPlainText = extractionResult.payload["plainText"] as? String ?: ""

            // Step 3: Normalization (40% -> 60%)
            agentCoordinator.updateProgress(jobId, 50)
            val normalizationInput = AgentInput(
                type = "NORMALIZATION_INPUT",
                payload = mapOf("plainText" to rawPlainText, "htmlContent" to rawHtml)
            )
            val normalizationResult = agentCoordinator.runAgent(jobId, normalizationAgent, normalizationInput, privacyMode)
            if (normalizationResult !is AgentResult.Success) {
                return@withContext failure(jobId, "Normalization phase failed: ${(normalizationResult as? AgentResult.PermanentFailure)?.reason ?: "error"}")
            }
            val language = normalizationResult.payload["language"] as? String ?: "en"
            val blocksJson = normalizationResult.payload["blocksJson"] as? String ?: "[]"
            val blockCount = normalizationResult.payload["blockCount"] as? Int ?: 0
            val normalizedText = normalizationResult.payload["normalizedText"] as? String ?: rawPlainText
            val normalizedHtml = normalizationResult.payload["normalizedHtml"] as? String ?: rawHtml

            // Step 4: Organization (60% -> 80%)
            agentCoordinator.updateProgress(jobId, 70)
            val organizationInput = AgentInput(
                type = "ORGANIZATION_INPUT",
                payload = mapOf("title" to rawTitle, "normalizedText" to normalizedText)
            )
            val organizationResult = agentCoordinator.runAgent(jobId, organizationAgent, organizationInput, privacyMode)
            val suggestedTitle = if (organizationResult is AgentResult.Success) {
                organizationResult.payload["suggestedTitle"] as? String ?: rawTitle
            } else rawTitle
            val tagsJson = if (organizationResult is AgentResult.Success) {
                organizationResult.payload["suggestedTags"] as? String ?: "[]"
            } else "[]"
            val taskCount = if (organizationResult is AgentResult.Success) {
                organizationResult.payload["taskCount"] as? Int ?: 0
            } else 0

            // Step 5: Save Note & Indexing (80% -> 100%)
            agentCoordinator.updateProgress(jobId, 85)
            val noteId = UUID.randomUUID().toString()
            val now = System.currentTimeMillis()
            val noteEntity = NoteEntity(
                id = noteId,
                title = suggestedTitle,
                content = normalizedHtml,
                plainText = normalizedText,
                isPinned = false,
                language = language,
                tags = tagsJson,
                createdAt = now,
                updatedAt = now,
                imageUrl = null
            )
            noteDao.insertNote(noteEntity)
            createdNoteId = noteId

            val indexingInput = AgentInput(
                type = "INDEXING_INPUT",
                payload = mapOf(
                    "noteId" to noteId,
                    "sourceDocumentId" to sourceDocId,
                    "blocksJson" to blocksJson,
                    "fullText" to normalizedText
                )
            )
            val indexingResult = agentCoordinator.runAgent(jobId, indexingAgent, indexingInput, privacyMode)
            if (indexingResult !is AgentResult.Success) {
                android.util.Log.w("ImportPipeline", "Indexing failed: note saved but unindexed")
            }

            agentCoordinator.updateProgress(jobId, 100)
            agentCoordinator.recordEvent(
                jobId = jobId,
                agentId = "import_pipeline",
                eventType = "IMPORT_COMPLETED",
                safeMetadata = "{\"noteId\":\"$noteId\",\"blocks\":$blockCount,\"language\":\"$language\"}"
            )

            ImportPipelineResult(
                jobId = jobId,
                isSuccess = true,
                noteId = noteId,
                noteTitle = suggestedTitle,
                sourceDocumentId = sourceDocId,
                checksum = checksum,
                language = language,
                blockCount = blockCount,
                taskCount = taskCount
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            val message = e.message ?: e.javaClass.simpleName
            failure(jobId, "Import pipeline exception: $message")
        } finally {
            agentCoordinator.clearJobCancellation(jobId)
            if (_activeJobId.value == jobId) {
                _activeJobId.value = null
            }
        }
    }

    private fun failure(jobId: String, reason: String): ImportPipelineResult {
        return ImportPipelineResult(
            jobId = jobId,
            isSuccess = false,
            errorMessage = reason
        )
    }
}
