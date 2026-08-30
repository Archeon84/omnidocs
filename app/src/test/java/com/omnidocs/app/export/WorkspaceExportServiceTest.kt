package com.omnidocs.app.export

import com.omnidocs.app.calendar.CalendarExportService
import com.omnidocs.app.data.local.ActionItemDao
import com.omnidocs.app.data.local.ClaimDao
import com.omnidocs.app.data.local.NoteDao
import com.omnidocs.app.data.local.entity.ActionItemEntity
import com.omnidocs.app.data.local.entity.NoteEntity
import com.omnidocs.app.graph.GraphData
import com.omnidocs.app.graph.GraphEdge
import com.omnidocs.app.graph.GraphEngine
import com.omnidocs.app.graph.GraphExportService
import com.omnidocs.app.graph.GraphNode
import com.omnidocs.app.study.StudyExportService
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.mockito.Mockito.*
import java.io.File
import java.util.zip.ZipFile

class WorkspaceExportServiceTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private lateinit var noteDao: NoteDao
    private lateinit var claimDao: ClaimDao
    private lateinit var actionItemDao: ActionItemDao
    private lateinit var graphEngine: GraphEngine
    private lateinit var exportService: WorkspaceExportService

    @Before
    fun setUp() {
        noteDao = mock(NoteDao::class.java)
        claimDao = mock(ClaimDao::class.java)
        actionItemDao = mock(ActionItemDao::class.java)
        graphEngine = mock(GraphEngine::class.java)

        exportService = WorkspaceExportService(
            noteDao = noteDao,
            claimDao = claimDao,
            actionItemDao = actionItemDao,
            graphEngine = graphEngine,
            graphExportService = GraphExportService(),
            calendarExportService = CalendarExportService(),
            studyExportService = StudyExportService()
        )
    }

    @Test
    fun testBuildWorkspaceZip_createsStructuredArchive() = runBlocking {
        val dummyNotes = listOf(
            NoteEntity(
                id = "note_100",
                title = "Sprint Roadmap 2026",
                content = "<h2>Sprint Goals</h2><p>Deliver the full Evidence-First workspace.</p>",
                plainText = "Sprint Goals. Deliver the full Evidence-First workspace.",
                isPinned = true,
                language = "en",
                createdAt = 1000L,
                updatedAt = 2000L,
                imageUrl = null,
                tags = "[\"sprint\", \"roadmap\"]"
            )
        )

        val dummyActions = listOf(
            ActionItemEntity(
                id = "action_1",
                noteId = "note_100",
                title = "Deploy to Xiaomi 13 Ultra",
                description = "Verify APK on arm64-v8a hardware.",
                owner = "Dev",
                dueAt = 1756540800000L,
                status = "completed",
                priority = "urgent",
                createdAt = 1000L,
                updatedAt = 2000L
            )
        )

        val dummyGraph = GraphData(
            nodes = listOf(GraphNode(noteId = "note_100", title = "Sprint Roadmap 2026", wordCount = 10)),
            edges = listOf()
        )

        `when`(noteDao.getAllNotes()).thenReturn(flowOf(dummyNotes))
        `when`(actionItemDao.getAllActionItems()).thenReturn(flowOf(dummyActions))
        `when`(graphEngine.buildGraph()).thenReturn(dummyGraph)

        val zipFile = tempFolder.newFile("test_workspace_export.zip")
        val bytesWritten = exportService.buildWorkspaceZip(zipFile)

        assertTrue(bytesWritten > 0)
        assertTrue(zipFile.exists())

        // Inspect ZIP contents
        ZipFile(zipFile).use { zip ->
            val entryNames = zip.entries().asSequence().map { it.name }.toSet()

            assertTrue(entryNames.contains("manifest.json"))
            assertTrue(entryNames.contains("calendar/tasks_and_deadlines.ics"))
            assertTrue(entryNames.contains("graph/knowledge_graph.graphml"))
            assertTrue(entryNames.contains("graph/knowledge_graph.jsonld"))
            assertTrue(entryNames.contains("evidence/action_items.json"))

            // Verify manifest content
            val manifestEntry = zip.getEntry("manifest.json")
            val manifestText = zip.getInputStream(manifestEntry).bufferedReader().readText()
            val manifestJson = JSONObject(manifestText)
            assertEquals("OmniDocs Knowledge Workspace", manifestJson.getString("app"))
            assertEquals(1, manifestJson.getInt("noteCount"))
            assertEquals(1, manifestJson.getInt("actionItemCount"))

            // Verify notes entry
            val noteEntry = entryNames.find { it.startsWith("notes/") && it.endsWith(".md") }
            assertNotNull(noteEntry)
        }
    }
}
