package com.omnidocs.app.agent

import com.omnidocs.app.calendar.CalendarExportService
import com.omnidocs.app.data.local.ActionItemDao
import com.omnidocs.app.data.local.ClaimDao
import com.omnidocs.app.data.local.RecordingDao
import com.omnidocs.app.data.local.TranscriptSegmentDao
import com.omnidocs.app.data.local.entity.ActionItemEntity
import com.omnidocs.app.data.repository.NotesRepository
import com.omnidocs.app.graph.GraphEngine
import com.omnidocs.app.graph.GraphExportFormat
import com.omnidocs.app.graph.GraphExportService
import kotlinx.coroutines.flow.first
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Registry containing all 11 narrowly scoped agent tools defined in the
 * Evidence-First AI Master Plan.
 */
@Singleton
class AgentToolRegistry @Inject constructor(
    private val notesRepository: NotesRepository,
    private val recordingDao: RecordingDao,
    private val transcriptSegmentDao: TranscriptSegmentDao,
    private val actionItemDao: ActionItemDao,
    private val claimDao: ClaimDao,
    private val calendarExportService: CalendarExportService,
    private val graphExportService: GraphExportService,
    private val graphEngine: GraphEngine
) {

    private val tools = mutableMapOf<String, AgentTool>()

    init {
        registerBuiltinTools()
    }

    private fun registerBuiltinTools() {
        // 1. search_notes
        registerTool(object : AgentTool {
            override val name = "search_notes"
            override val description = "Searches notes using FTS4 BM25 and lexical keyword matching."
            override val inputSchemaJson = """{"type": "object", "properties": {"query": {"type": "string"}}, "required": ["query"]}"""
            override val outputSchemaJson = """{"type": "object", "properties": {"results": {"type": "array"}}}"""
            override val isMutating = false
            override val requiresUserApproval = false
            override val auditEventType = "AGENT_TOOL_SEARCH_NOTES"

            override suspend fun execute(inputJson: String, context: AgentContext): ToolExecutionResult {
                val query = try {
                    JSONObject(inputJson).getString("query")
                } catch (e: Exception) {
                    return ToolExecutionResult.Failure("Invalid input: 'query' string required")
                }
                val notes = notesRepository.searchNotes(query).first()
                val array = JSONArray()
                notes.take(10).forEach { n ->
                    array.put(JSONObject().apply {
                        put("id", n.id)
                        put("title", n.title)
                        put("snippet", n.plainText.take(200))
                    })
                }
                return ToolExecutionResult.Success(JSONObject().put("results", array).toString())
            }
        })

        // 2. get_note
        registerTool(object : AgentTool {
            override val name = "get_note"
            override val description = "Retrieves the full content and metadata of a specific note by ID."
            override val inputSchemaJson = """{"type": "object", "properties": {"noteId": {"type": "string"}}, "required": ["noteId"]}"""
            override val outputSchemaJson = """{"type": "object", "properties": {"id": {"type": "string"}, "title": {"type": "string"}, "content": {"type": "string"}}}"""
            override val isMutating = false
            override val requiresUserApproval = false
            override val auditEventType = "AGENT_TOOL_GET_NOTE"

            override suspend fun execute(inputJson: String, context: AgentContext): ToolExecutionResult {
                val noteId = try {
                    JSONObject(inputJson).getString("noteId")
                } catch (e: Exception) {
                    return ToolExecutionResult.Failure("Invalid input: 'noteId' required")
                }
                val note = notesRepository.getNoteById(noteId)
                    ?: return ToolExecutionResult.Failure("Note not found: $noteId")

                val out = JSONObject().apply {
                    put("id", note.id)
                    put("title", note.title)
                    put("content", note.content)
                    put("plainText", note.plainText)
                    put("tags", note.tags)
                }
                return ToolExecutionResult.Success(out.toString())
            }
        })

        // 3. get_transcript_segment
        registerTool(object : AgentTool {
            override val name = "get_transcript_segment"
            override val description = "Retrieves timestamped transcript segments for a recording."
            override val inputSchemaJson = """{"type": "object", "properties": {"recordingId": {"type": "string"}}, "required": ["recordingId"]}"""
            override val outputSchemaJson = """{"type": "object", "properties": {"segments": {"type": "array"}}}"""
            override val isMutating = false
            override val requiresUserApproval = false
            override val auditEventType = "AGENT_TOOL_GET_TRANSCRIPT_SEGMENT"

            override suspend fun execute(inputJson: String, context: AgentContext): ToolExecutionResult {
                val recordingId = try {
                    JSONObject(inputJson).getString("recordingId")
                } catch (e: Exception) {
                    return ToolExecutionResult.Failure("Invalid input: 'recordingId' required")
                }
                val segments = transcriptSegmentDao.getSegmentsByRecording(recordingId).first()
                val array = JSONArray()
                segments.forEach { s ->
                    array.put(JSONObject().apply {
                        put("id", s.id)
                        put("startMs", s.startMs)
                        put("endMs", s.endMs)
                        put("text", s.rawText)
                        put("confidence", s.confidence)
                    })
                }
                return ToolExecutionResult.Success(JSONObject().put("segments", array).toString())
            }
        })

        // 4. get_audio_timestamp
        registerTool(object : AgentTool {
            override val name = "get_audio_timestamp"
            override val description = "Resolves the local storage key and seek offset for an audio timestamp."
            override val inputSchemaJson = """{"type": "object", "properties": {"recordingId": {"type": "string"}, "timestampMs": {"type": "number"}}, "required": ["recordingId", "timestampMs"]}"""
            override val outputSchemaJson = """{"type": "object", "properties": {"storageKey": {"type": "string"}, "seekOffsetMs": {"type": "number"}}}"""
            override val isMutating = false
            override val requiresUserApproval = false
            override val auditEventType = "AGENT_TOOL_GET_AUDIO_TIMESTAMP"

            override suspend fun execute(inputJson: String, context: AgentContext): ToolExecutionResult {
                val obj = try { JSONObject(inputJson) } catch (e: Exception) { return ToolExecutionResult.Failure("Invalid JSON") }
                val recordingId = obj.optString("recordingId")
                val timestampMs = obj.optLong("timestampMs", 0L)
                val rec = recordingDao.getRecordingById(recordingId)
                    ?: return ToolExecutionResult.Failure("Recording not found: $recordingId")

                val out = JSONObject().apply {
                    put("storageKey", rec.storageKey)
                    put("seekOffsetMs", timestampMs)
                    put("durationMs", rec.durationMs)
                }
                return ToolExecutionResult.Success(out.toString())
            }
        })

        // 5. create_draft_task
        registerTool(object : AgentTool {
            override val name = "create_draft_task"
            override val description = "Creates a draft action item with owner and deadline requiring user confirmation."
            override val inputSchemaJson = """{"type": "object", "properties": {"noteId": {"type": "string"}, "title": {"type": "string"}, "owner": {"type": "string"}, "dueAt": {"type": "number"}, "priority": {"type": "string"}}, "required": ["noteId", "title"]}"""
            override val outputSchemaJson = """{"type": "object", "properties": {"taskId": {"type": "string"}, "status": {"type": "string"}}}"""
            override val isMutating = true
            override val requiresUserApproval = true
            override val auditEventType = "AGENT_TOOL_CREATE_DRAFT_TASK"

            override suspend fun execute(inputJson: String, context: AgentContext): ToolExecutionResult {
                val obj = try { JSONObject(inputJson) } catch (e: Exception) { return ToolExecutionResult.Failure("Invalid JSON") }
                val noteId = obj.optString("noteId")
                val title = obj.optString("title")
                val owner = obj.optString("owner").takeIf { it.isNotBlank() }
                val dueAt = if (obj.has("dueAt")) obj.getLong("dueAt") else null
                val priority = obj.optString("priority", "medium")

                if (title.isBlank()) return ToolExecutionResult.Failure("Title is required")

                val entity = ActionItemEntity(
                    id = UUID.randomUUID().toString(),
                    noteId = noteId,
                    title = title,
                    description = obj.optString("description", ""),
                    owner = owner,
                    dueAt = dueAt,
                    status = "pending",
                    priority = priority,
                    createdAt = System.currentTimeMillis(),
                    updatedAt = System.currentTimeMillis()
                )

                // If executing within an automated agent context, gate on approval
                actionItemDao.insertActionItem(entity)
                return ToolExecutionResult.Success(JSONObject().put("taskId", entity.id).put("status", "pending").toString())
            }
        })

        // 6. update_note
        registerTool(object : AgentTool {
            override val name = "update_note"
            override val description = "Applies verified changes to a note with user confirmation gate."
            override val inputSchemaJson = """{"type": "object", "properties": {"noteId": {"type": "string"}, "title": {"type": "string"}, "content": {"type": "string"}}, "required": ["noteId"]}"""
            override val outputSchemaJson = """{"type": "object", "properties": {"success": {"type": "boolean"}}}"""
            override val isMutating = true
            override val requiresUserApproval = true
            override val auditEventType = "AGENT_TOOL_UPDATE_NOTE"

            override suspend fun execute(inputJson: String, context: AgentContext): ToolExecutionResult {
                val obj = try { JSONObject(inputJson) } catch (e: Exception) { return ToolExecutionResult.Failure("Invalid JSON") }
                val noteId = obj.optString("noteId")
                val note = notesRepository.getNoteById(noteId)
                    ?: return ToolExecutionResult.Failure("Note not found: $noteId")

                val newTitle = obj.optString("title", note.title)
                val newContent = obj.optString("content", note.content)

                notesRepository.updateNote(
                    note.copy(
                        title = newTitle,
                        content = newContent,
                        updatedAt = System.currentTimeMillis()
                    )
                )
                return ToolExecutionResult.Success(JSONObject().put("success", true).toString())
            }
        })

        // 7. create_calendar_draft
        registerTool(object : AgentTool {
            override val name = "create_calendar_draft"
            override val description = "Generates an RFC 5545 iCalendar string for meeting action items."
            override val inputSchemaJson = """{"type": "object", "properties": {"noteId": {"type": "string"}}, "required": ["noteId"]}"""
            override val outputSchemaJson = """{"type": "object", "properties": {"ics": {"type": "string"}}}"""
            override val isMutating = false
            override val requiresUserApproval = false
            override val auditEventType = "AGENT_TOOL_CREATE_CALENDAR_DRAFT"

            override suspend fun execute(inputJson: String, context: AgentContext): ToolExecutionResult {
                val noteId = try { JSONObject(inputJson).getString("noteId") } catch (e: Exception) { return ToolExecutionResult.Failure("noteId required") }
                val items = actionItemDao.getActionItemsByNoteId(noteId).first()
                val ics = calendarExportService.exportToIcs(items)
                return ToolExecutionResult.Success(JSONObject().put("ics", ics).toString())
            }
        })

        // 8. export_workspace
        registerTool(object : AgentTool {
            override val name = "export_workspace"
            override val description = "Exports graph knowledge to GraphML XML or JSON-LD format."
            override val inputSchemaJson = """{"type": "object", "properties": {"format": {"type": "string", "enum": ["GRAPHML", "JSON_LD"]}}, "required": ["format"]}"""
            override val outputSchemaJson = """{"type": "object", "properties": {"content": {"type": "string"}}}"""
            override val isMutating = false
            override val requiresUserApproval = false
            override val auditEventType = "AGENT_TOOL_EXPORT_WORKSPACE"

            override suspend fun execute(inputJson: String, context: AgentContext): ToolExecutionResult {
                val fmtStr = try { JSONObject(inputJson).getString("format").uppercase() } catch (e: Exception) { "GRAPHML" }
                val graphData = graphEngine.buildGraph()
                val outStr = if (fmtStr == "JSON_LD") {
                    graphExportService.exportToJsonLd(graphData)
                } else {
                    graphExportService.exportToGraphML(graphData)
                }
                return ToolExecutionResult.Success(JSONObject().put("content", outStr).toString())
            }
        })

        // 9. request_delete_confirmation
        registerTool(object : AgentTool {
            override val name = "request_delete_confirmation"
            override val description = "Requests user confirmation before soft-deleting a note."
            override val inputSchemaJson = """{"type": "object", "properties": {"noteId": {"type": "string"}, "reason": {"type": "string"}}, "required": ["noteId"]}"""
            override val outputSchemaJson = """{"type": "object", "properties": {"confirmationRequired": {"type": "boolean"}}}"""
            override val isMutating = true
            override val requiresUserApproval = true
            override val auditEventType = "AGENT_TOOL_REQUEST_DELETE_CONFIRMATION"

            override suspend fun execute(inputJson: String, context: AgentContext): ToolExecutionResult {
                val obj = try { JSONObject(inputJson) } catch (e: Exception) { return ToolExecutionResult.Failure("Invalid JSON") }
                val noteId = obj.optString("noteId")
                val reason = obj.optString("reason", "Requested by agent")
                return ToolExecutionResult.ApprovalRequired(
                    toolName = "request_delete_confirmation",
                    confirmationMessage = "Are you sure you want to delete note $noteId? ($reason)",
                    payloadJson = inputJson
                )
            }
        })

        // 10. list_related_notes
        registerTool(object : AgentTool {
            override val name = "list_related_notes"
            override val description = "Lists related note connections from the knowledge graph."
            override val inputSchemaJson = """{"type": "object", "properties": {"noteId": {"type": "string"}}, "required": ["noteId"]}"""
            override val outputSchemaJson = """{"type": "object", "properties": {"related": {"type": "array"}}}"""
            override val isMutating = false
            override val requiresUserApproval = false
            override val auditEventType = "AGENT_TOOL_LIST_RELATED_NOTES"

            override suspend fun execute(inputJson: String, context: AgentContext): ToolExecutionResult {
                val noteId = try { JSONObject(inputJson).getString("noteId") } catch (e: Exception) { return ToolExecutionResult.Failure("noteId required") }
                val graphData = graphEngine.buildGraph()
                val relatedEdgeTargets = graphData.edges.filter { it.from == noteId }.map { it.to }
                val array = JSONArray()
                relatedEdgeTargets.forEach { tid -> array.put(tid) }
                return ToolExecutionResult.Success(JSONObject().put("related", array).toString())
            }
        })

        // 11. list_contradictions
        registerTool(object : AgentTool {
            override val name = "list_contradictions"
            override val description = "Retrieves all contradictory claims identified in the workspace."
            override val inputSchemaJson = """{"type": "object"}"""
            override val outputSchemaJson = """{"type": "object", "properties": {"contradictions": {"type": "array"}}}"""
            override val isMutating = false
            override val requiresUserApproval = false
            override val auditEventType = "AGENT_TOOL_LIST_CONTRADICTIONS"

            override suspend fun execute(inputJson: String, context: AgentContext): ToolExecutionResult {
                val claims = claimDao.getClaimsByType("contradiction").first()
                val array = JSONArray()
                claims.forEach { c ->
                    array.put(JSONObject().apply {
                        put("id", c.id)
                        put("noteId", c.noteId)
                        put("text", c.text)
                        put("confidence", c.confidence)
                    })
                }
                return ToolExecutionResult.Success(JSONObject().put("contradictions", array).toString())
            }
        })
    }

    fun registerTool(tool: AgentTool) {
        tools[tool.name] = tool
    }

    fun getTool(name: String): AgentTool? = tools[name]

    fun getAllTools(): List<AgentTool> = tools.values.toList()
}
