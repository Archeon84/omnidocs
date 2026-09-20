package com.omnidocs.app.agent

import com.omnidocs.app.data.local.ActionItemDao
import com.omnidocs.app.data.local.NoteDao
import com.omnidocs.app.data.local.RecordingDao
import com.omnidocs.app.data.local.TranscriptSegmentDao
import com.omnidocs.app.data.local.entity.ActionItemEntity
import com.omnidocs.app.data.local.entity.NoteEntity
import com.omnidocs.app.data.local.entity.RecordingEntity
import com.omnidocs.app.data.local.entity.TranscriptSegmentEntity
import com.omnidocs.app.util.sanitizeForHtml
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
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
            inputRefsJson = "{\"storageKey\":\"${escapeJsonString(storageKey)}\"," +
                "\"language\":\"${escapeJsonString(language)}\"}"
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

            // Escape user/LLM text before embedding in stored HTML: a title
            // with '<' or unbalanced LLM tags used to corrupt note rendering.
            val combinedHtml = "<h2>${sanitizeForHtml(meetingTitle)}</h2>" +
                "<p>$summaryText</p><hr/><h3>Transcript</h3>" +
                "<p>${sanitizeForHtml(fullNormalizedText).replace("\n", "<br/>")}</p>"
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
                android.util.Log.w("MeetingPipeline", "Transcript segments dropped (unindexed)", e)
            }

            // Save Extracted Action Items
            try {
                val taskArray = JSONArray(tasksJson)
                val actionEntities = mutableListOf<ActionItemEntity>()
                for (i in 0 until taskArray.length()) {
                    // One bad task must not wipe the whole action list.
                    val obj = try {
                        taskArray.getJSONObject(i)
                    } catch (e: Exception) {
                        android.util.Log.w("MeetingPipeline", "Skipping malformed task at index $i", e)
                        continue
                    }
                    // dueAt is LLM JSON: often "tomorrow" or a date string, not
                    // a Long — coerce, never throw.
                    val dueAtRaw = obj.optLongLenient("dueAt")?.takeIf { it > 0 }
                    actionEntities.add(
                        ActionItemEntity(
                            id = UUID.randomUUID().toString(),
                            noteId = noteId,
                            claimId = null,
                            title = obj.optString("title", "Action Item"),
                            description = obj.optString("description", ""),
                            owner = obj.optString("owner").takeIf { it.isNotBlank() },
                            dueAt = dueAtRaw,
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
                android.util.Log.w("MeetingPipeline", "Action items dropped", e)
            }

            // Step 6: Indexing — same block granularity as imports: the summary
            // plus one block per transcript segment, with note-relative offsets
            // so block-level retrieval and citations work for meeting notes.
            agentCoordinator.updateProgress(jobId, 95)
            val meetingBlocksJson = JSONArray()
            var blockCursor = 0
            fun addMeetingBlock(content: String, blockType: String) {
                if (content.isBlank()) return
                val start = noteEntity.plainText.indexOf(content, blockCursor)
                    .coerceAtLeast(0)
                val end = (start + content.length).coerceAtMost(noteEntity.plainText.length)
                blockCursor = end
                meetingBlocksJson.put(
                    JSONObject()
                        .put("content", content)
                        .put("blockType", blockType)
                        .put("startOffset", start)
                        .put("endOffset", end)
                )
            }
            addMeetingBlock(summaryText, "summary")
            try {
                val segArray = JSONArray(normalizedSegmentsJson)
                for (i in 0 until segArray.length()) {
                    val obj = segArray.optJSONObject(i) ?: continue
                    val text = obj.optString("correctedText").ifBlank { obj.optString("rawText") }
                    addMeetingBlock(text, "transcript")
                }
            } catch (e: Exception) {
                android.util.Log.w("MeetingPipeline", "Transcript blocks skipped", e)
            }
            val indexingInput = AgentInput(
                type = "INDEXING_INPUT",
                payload = mapOf(
                    "noteId" to noteId,
                    "fullText" to noteEntity.plainText,
                    "blocksJson" to meetingBlocksJson.toString()
                )
            )
            val indexingResult = agentCoordinator.runAgent(jobId, indexingAgent, indexingInput, privacyMode)
            if (indexingResult !is AgentResult.Success) {
                android.util.Log.w("MeetingPipeline", "Indexing failed: note saved but unindexed")
            }

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
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            val message = e.message ?: e.javaClass.simpleName
            failure(jobId, "Meeting pipeline exception: $message")
        } finally {
            agentCoordinator.clearJobCancellation(jobId)
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
