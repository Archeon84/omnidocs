package com.omnidocs.app.agent

import com.omnidocs.app.calendar.CalendarExportService
import com.omnidocs.app.data.local.ActionItemDao
import com.omnidocs.app.data.local.ClaimDao
import com.omnidocs.app.data.local.RecordingDao
import com.omnidocs.app.data.local.TranscriptSegmentDao
import com.omnidocs.app.data.local.entity.ActionItemEntity
import com.omnidocs.app.data.repository.NotesRepository
import com.omnidocs.app.domain.model.Note
import com.omnidocs.app.graph.GraphEngine
import com.omnidocs.app.graph.GraphExportService
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.mockito.Mockito.*

class AgentToolRegistryTest {

    private lateinit var notesRepository: NotesRepository
    private lateinit var recordingDao: RecordingDao
    private lateinit var transcriptSegmentDao: TranscriptSegmentDao
    private lateinit var claimDao: ClaimDao
    private lateinit var calendarExportService: CalendarExportService
    private lateinit var graphExportService: GraphExportService
    private lateinit var graphEngine: GraphEngine
    private lateinit var fakeActionItemDao: FakeActionItemDao
    private lateinit var registry: AgentToolRegistry

    private val defaultContext = AgentContext(jobId = "test_job")

    class FakeActionItemDao : ActionItemDao {
        val inserted = mutableListOf<ActionItemEntity>()

        override fun getActionItemsByNoteId(noteId: String): Flow<List<ActionItemEntity>> = flowOf(inserted)
        override fun getActiveActionItems(): Flow<List<ActionItemEntity>> = flowOf(emptyList())
        override fun getAllActionItems(): Flow<List<ActionItemEntity>> = flowOf(emptyList())
        override fun getActiveCount(): Flow<Int> = flowOf(0)
        override fun getCompletedCount(): Flow<Int> = flowOf(0)
        override fun getOverdueCount(now: Long): Flow<Int> = flowOf(0)
        override suspend fun getActionItemById(id: String): ActionItemEntity? = inserted.find { it.id == id }
        override suspend fun insertActionItem(actionItem: ActionItemEntity) { inserted.add(actionItem) }
        override suspend fun insertActionItems(actionItems: List<ActionItemEntity>) { inserted.addAll(actionItems) }
        override suspend fun updateActionItem(actionItem: ActionItemEntity) {}
        override suspend fun completeActionItem(id: String, status: String, completedAt: Long, updatedAt: Long) {}
        override suspend fun deleteActionItemsByNoteId(noteId: String) { inserted.clear() }
        override suspend fun deletePendingActionItemsByNoteId(noteId: String) {}
    }

    @Before
    fun setUp() {
        notesRepository = mock(NotesRepository::class.java)
        recordingDao = mock(RecordingDao::class.java)
        transcriptSegmentDao = mock(TranscriptSegmentDao::class.java)
        claimDao = mock(ClaimDao::class.java)
        calendarExportService = CalendarExportService()
        graphExportService = GraphExportService()
        graphEngine = mock(GraphEngine::class.java)
        fakeActionItemDao = FakeActionItemDao()

        registry = AgentToolRegistry(
            notesRepository = notesRepository,
            recordingDao = recordingDao,
            transcriptSegmentDao = transcriptSegmentDao,
            actionItemDao = fakeActionItemDao,
            claimDao = claimDao,
            calendarExportService = calendarExportService,
            graphExportService = graphExportService,
            graphEngine = graphEngine
        )
    }

    @Test
    fun testRegistry_containsAll11MasterPlanTools() {
        val tools = registry.getAllTools()
        assertEquals(11, tools.size)

        val toolNames = tools.map { it.name }.toSet()
        val expectedTools = setOf(
            "search_notes",
            "get_note",
            "get_transcript_segment",
            "get_audio_timestamp",
            "create_draft_task",
            "update_note",
            "create_calendar_draft",
            "export_workspace",
            "request_delete_confirmation",
            "list_related_notes",
            "list_contradictions"
        )

        assertEquals(expectedTools, toolNames)
    }

    @Test
    fun testGetNoteTool_execution() = runBlocking {
        val dummyNote = Note(
            id = "note_alpha",
            title = "Security Architecture",
            content = "<p>SQLCipher AES-256 enabled</p>",
            plainText = "SQLCipher AES-256 enabled",
            isPinned = false,
            language = "en",
            createdAt = 1000L,
            updatedAt = 2000L
        )

        `when`(notesRepository.getNoteById("note_alpha")).thenReturn(dummyNote)

        val tool = registry.getTool("get_note")
        assertNotNull(tool)
        assertFalse(tool!!.isMutating)
        assertFalse(tool.requiresUserApproval)

        val result = tool.execute("""{"noteId": "note_alpha"}""", defaultContext)
        assertTrue(result is ToolExecutionResult.Success)

        val outputJson = (result as ToolExecutionResult.Success).outputJson
        val json = JSONObject(outputJson)
        assertEquals("note_alpha", json.getString("id"))
        assertEquals("Security Architecture", json.getString("title"))
        assertEquals("<p>SQLCipher AES-256 enabled</p>", json.getString("content"))
    }

    @Test
    fun testRequestDeleteConfirmationTool_requiresApproval() = runBlocking {
        val tool = registry.getTool("request_delete_confirmation")
        assertNotNull(tool)
        assertTrue(tool!!.isMutating)
        assertTrue(tool.requiresUserApproval)

        val result = tool.execute("""{"noteId": "note_target", "reason": "Cleanup"}""", defaultContext)
        assertTrue(result is ToolExecutionResult.ApprovalRequired)

        val approval = result as ToolExecutionResult.ApprovalRequired
        assertEquals("request_delete_confirmation", approval.toolName)
        assertTrue(approval.confirmationMessage.contains("note_target"))
    }

    @Test
    fun testCreateDraftTaskTool_execution() = runBlocking {
        val tool = registry.getTool("create_draft_task")
        assertNotNull(tool)
        assertTrue(tool!!.isMutating)
        assertTrue(tool.requiresUserApproval)

        val input = """{"noteId": "note_meeting", "title": "Audit Database Schema", "owner": "Alice", "priority": "high"}"""
        val result = tool.execute(input, defaultContext)
        assertTrue(result is ToolExecutionResult.Success)

        val json = JSONObject((result as ToolExecutionResult.Success).outputJson)
        assertTrue(json.has("taskId"))
        assertEquals("pending", json.getString("status"))

        assertEquals(1, fakeActionItemDao.inserted.size)
        val created = fakeActionItemDao.inserted.first()
        assertEquals("Audit Database Schema", created.title)
        assertEquals("note_meeting", created.noteId)
        assertEquals("Alice", created.owner)
        assertEquals("high", created.priority)
    }
}
