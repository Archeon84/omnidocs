package com.omnidocs.app.agent

import com.omnidocs.app.data.local.ActionItemDao
import com.omnidocs.app.data.local.NoteDao
import com.omnidocs.app.data.local.RecordingDao
import com.omnidocs.app.data.local.TranscriptSegmentDao
import com.omnidocs.app.data.local.entity.ActionItemEntity
import com.omnidocs.app.data.local.entity.NoteEntity
import com.omnidocs.app.data.local.entity.RecordingEntity
import com.omnidocs.app.data.local.entity.TranscriptSegmentEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

data class MeetingPipelineResult(
    val jobId: String,
    val isSuccess: Boolean,
    val noteId: String? = null,
    val noteTitle: String? = null,
    val recordingId: String? = null,
    val summary: String? = null,
    val segmentCount: Int = 0,
    val taskCount: Int = 0,
    val errorMessage: String? = null
)

/**
 * Coordinator implementing Workflow 2: "Meeting Recording & Speech Intelligence".
 * Orchestrates: Capture -> STT -> Transcript Normalizer -> Task Agent -> Summary Agent -> Indexing Agent.
 */
@Singleton
class MeetingPipelineCoordinator @Inject constructor(
    private val agentCoordinator: AgentCoordinator,
    private val sttAgent: SttAgent,
    private val transcriptNormalizerAgent: TranscriptNormalizerAgent,
    private val taskAgent: TaskAgent,
    private val summaryAgent: SummaryAgent,
    private val indexingAgent: IndexingAgent,
    private val noteDao: NoteDao,
    private val recordingDao: RecordingDao,
    private val transcriptSegmentDao: TranscriptSegmentDao,
    private val actionItemDao: ActionItemDao
) {
    /**
     * Executes the end-to-end meeting recording speech intelligence workflow.
     */
    suspend fun runMeetingPipeline(
        storageKey: String,
        rawTranscript: String? = null,
        meetingTitle: String = "Meeting Recording",
        language: String = "en",
        privacyMode: PrivacyMode = PrivacyMode.LOCAL_ONLY
    ): MeetingPipelineResult = withContext(Dispatchers.IO) {
        val job = agentCoordinator.createJob(
            jobType = "WORKFLOW_MEETING_RECORDING",
            inputRefsJson = "{\"storageKey\":\"$storageKey\",\"language\":\"$language\"}"
        )
        val jobId = job.id

        try {
            // Step 1: STT Transcription (0% -> 30%)
            agentCoordinator.updateProgress(jobId, 15)
            val sttInput = AgentInput(
                type = "STT_TRANSCRIPTION",
                payload = mapOf(
                    "storageKey" to storageKey,
                    "rawText" to (rawTranscript ?: ""),
                    "language" to language
                )
            )
            val sttResult = agentCoordinator.runAgent(jobId, sttAgent, sttInput, privacyMode)
            if (sttResult !is AgentResult.Success) {
                return@withContext failure(jobId, "STT recognition failed: ${(sttResult as? AgentResult.PermanentFailure)?.reason ?: "error"}")
            }
            val rawText = sttResult.payload["rawTranscript"] as? String ?: ""
            val rawSegmentsJson = sttResult.payload["segmentsJson"] as? String ?: "[]"
            val durationMs = (sttResult.payload["durationMs"] as? Number)?.toLong() ?: 60000L

            // Step 2: Transcript Normalization (30% -> 50%)
            agentCoordinator.updateProgress(jobId, 40)
            val normInput = AgentInput(
                type = "NORMALIZE_TRANSCRIPT",
                payload = mapOf("rawTranscript" to rawText, "segmentsJson" to rawSegmentsJson)
            )
            val normResult = agentCoordinator.runAgent(jobId, transcriptNormalizerAgent, normInput, privacyMode)
            val normalizedSegmentsJson = (normResult as? AgentResult.Success)?.payload?.get("normalizedSegmentsJson") as? String ?: "[]"
            val fullNormalizedText = (normResult as? AgentResult.Success)?.payload?.get("fullNormalizedText") as? String ?: rawText
            val segmentCount = (normResult as? AgentResult.Success)?.payload?.get("segmentCount") as? Int ?: 0

            // Step 3: Task & Action Item Extraction (50% -> 70%)
            agentCoordinator.updateProgress(jobId, 60)
            val taskInput = AgentInput(
                type = "EXTRACT_TASKS",
                payload = mapOf("text" to fullNormalizedText)
            )
            val taskResult = agentCoordinator.runAgent(jobId, taskAgent, taskInput, privacyMode)
            val tasksJson = (taskResult as? AgentResult.Success)?.payload?.get("tasksJson") as? String ?: "[]"
            val taskCount = (taskResult as? AgentResult.Success)?.payload?.get("taskCount") as? Int ?: 0

            // Step 4: Summary Generation (70% -> 85%)
            agentCoordinator.updateProgress(jobId, 75)
            val summaryInput = AgentInput(
                type = "GENERATE_SUMMARY",
                payload = mapOf("title" to meetingTitle, "text" to fullNormalizedText)
            )
            val summaryResult = agentCoordinator.runAgent(jobId, summaryAgent, summaryInput, privacyMode)
            val summaryText = (summaryResult as? AgentResult.Success)?.payload?.get("summary") as? String
                ?: "### Meeting Summary\n$fullNormalizedText"

            // Step 5: Save Note, Recording, Segments, Action Items & Index (85% -> 100%)
            agentCoordinator.updateProgress(jobId, 85)
            val now = System.currentTimeMillis()
            val noteId = UUID.randomUUID().toString()
            val recordingId = UUID.randomUUID().toString()

            val combinedHtml = "<h2>$meetingTitle</h2><p>$summaryText</p><hr/><h3>Transcript</h3><p>${fullNormalizedText.replace("\n", "<br/>")}</p>"
            val noteEntity = NoteEntity(
                id = noteId,
                title = meetingTitle,
                content = combinedHtml,
                plainText = "$summaryText\n\n$fullNormalizedText",
                isPinned = false,
                language = language,
                tags = "[\"meeting\", \"recording\"]",
                createdAt = now,
                updatedAt = now,
                imageUrl = null
            )
            noteDao.insertNote(noteEntity)

            val recordingEntity = RecordingEntity(
                id = recordingId,
                noteId = noteId,
                filename = storageKey,
                storageKey = storageKey,
                durationMs = durationMs,
                language = language,
                processingMode = "agentic_offline",
                processingStatus = "completed",
                createdAt = now
            )
            recordingDao.insertRecording(recordingEntity)

            // Save Transcript Segments
            try {
                val segArray = JSONArray(normalizedSegmentsJson)
                val segmentEntities = mutableListOf<TranscriptSegmentEntity>()
                for (i in 0 until segArray.length()) {
                    val obj = segArray.getJSONObject(i)
                    segmentEntities.add(
                        TranscriptSegmentEntity(
                            id = UUID.randomUUID().toString(),
                            recordingId = recordingId,
                            noteId = noteId,
                            speakerId = obj.optString("speakerId").takeIf { it.isNotBlank() },
                            startMs = obj.optLong("startMs", 0L),
                            endMs = obj.optLong("endMs", 0L),
                            rawText = obj.optString("rawText", ""),
                            correctedText = obj.optString("correctedText", ""),
                            language = language,
                            confidence = obj.optDouble("confidence", 0.9).toFloat(),
                            createdAt = now,
                            updatedAt = now
                        )
                    )
                }
                if (segmentEntities.isNotEmpty()) {
                    transcriptSegmentDao.insertSegments(segmentEntities)
                }
            } catch (e: Exception) {
                // Non-fatal
            }

            // Save Extracted Action Items
            try {
                val taskArray = JSONArray(tasksJson)
                val actionEntities = mutableListOf<ActionItemEntity>()
                for (i in 0 until taskArray.length()) {
                    val obj = taskArray.getJSONObject(i)
                    actionEntities.add(
                        ActionItemEntity(
                            id = UUID.randomUUID().toString(),
                            noteId = noteId,
                            claimId = null,
                            title = obj.optString("title", "Action Item"),
                            description = obj.optString("description", ""),
                            owner = obj.optString("owner").takeIf { it.isNotBlank() },
                            dueAt = if (obj.has("dueAt") && obj.getLong("dueAt") > 0) obj.getLong("dueAt") else null,
                            status = "OPEN",
                            priority = obj.optString("priority", "MEDIUM"),
                            createdAt = now,
                            updatedAt = now
                        )
                    )
                }
                if (actionEntities.isNotEmpty()) {
                    actionItemDao.insertActionItems(actionEntities)
                }
            } catch (e: Exception) {
                // Non-fatal
            }

            // Step 6: Indexing
            agentCoordinator.updateProgress(jobId, 95)
            val indexingInput = AgentInput(
                type = "INDEXING_INPUT",
                payload = mapOf("noteId" to noteId, "fullText" to noteEntity.plainText)
            )
            agentCoordinator.runAgent(jobId, indexingAgent, indexingInput, privacyMode)

            agentCoordinator.updateProgress(jobId, 100)
            agentCoordinator.recordEvent(
                jobId = jobId,
                agentId = "meeting_coordinator",
                eventType = "MEETING_PROCESSED",
                safeMetadata = "{\"noteId\":\"$noteId\",\"segments\":$segmentCount,\"tasks\":$taskCount}"
            )

            MeetingPipelineResult(
                jobId = jobId,
                isSuccess = true,
                noteId = noteId,
                noteTitle = meetingTitle,
                recordingId = recordingId,
                summary = summaryText,
                segmentCount = segmentCount,
                taskCount = taskCount
            )
        } catch (e: Exception) {
            val message = e.message ?: e.javaClass.simpleName
            failure(jobId, "Meeting pipeline exception: $message")
        }
    }

    private fun failure(jobId: String, reason: String): MeetingPipelineResult {
        return MeetingPipelineResult(
            jobId = jobId,
            isSuccess = false,
            errorMessage = reason
        )
    }
}
