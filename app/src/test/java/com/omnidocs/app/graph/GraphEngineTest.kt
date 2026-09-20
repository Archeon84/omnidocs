package com.omnidocs.app.graph

import com.omnidocs.app.ai.LlamaCppService
import com.omnidocs.app.ai.ModelDownloadManager
import com.omnidocs.app.ai.ModelPreferences
import com.omnidocs.app.data.local.NoteLinkDao
import com.omnidocs.app.data.local.entity.NoteLinkEntity
import com.omnidocs.app.data.repository.NotesRepository
import com.omnidocs.app.domain.model.Note
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`

class GraphEngineTest {

    private val llamaCppService = mock(LlamaCppService::class.java)
    private val modelDownloadManager = mock(ModelDownloadManager::class.java)
    private val modelPreferences = mock(ModelPreferences::class.java)
    private val repository = mock(NotesRepository::class.java)
    private val fakeNoteLinkDao = FakeNoteLinkDao()

    private lateinit var graphEngine: GraphEngine

    class FakeNoteLinkDao : NoteLinkDao {
        val insertedLinks = mutableListOf<NoteLinkEntity>()
        override fun getLinksForNote(noteId: String): Flow<List<NoteLinkEntity>> = flowOf(emptyList())
        override fun getLinksByType(noteId: String, linkType: String): Flow<List<NoteLinkEntity>> = flowOf(emptyList())
        override suspend fun insertLink(link: NoteLinkEntity) { insertedLinks.add(link) }
        override suspend fun insertLinks(links: List<NoteLinkEntity>) { insertedLinks.addAll(links) }
        override suspend fun deleteLinkBetween(noteId: String, targetNoteId: String) {}
        override suspend fun deleteAllLinksForNote(noteId: String) {}
    }

    @Before
    fun setUp() {
        `when`(llamaCppService.isNativeLibLoaded()).thenReturn(false) // Test offline/no-LLM mode
        graphEngine = GraphEngine(
            llamaCppService = llamaCppService,
            modelDownloadManager = modelDownloadManager,
            modelPreferences = modelPreferences,
            repository = repository,
            noteLinkDao = fakeNoteLinkDao
        )
    }

    @Test
    fun testBuildGraph_extractsExplicitWikilinks() = runBlocking {
        val note1 = Note(
            id = "note_alpha",
            title = "Architecture Spec",
            content = "This document describes system design. See [[Database Schema]] for tables.",
            plainText = "This document describes system design. See [[Database Schema]] for tables.",
            tags = "[]",
            createdAt = 1000L,
            updatedAt = 1000L
        )
        val note2 = Note(
            id = "note_beta",
            title = "Database Schema",
            content = "SQLite Room schema tables and migrations.",
            plainText = "SQLite Room schema tables and migrations.",
            tags = "[]",
            createdAt = 2000L,
            updatedAt = 2000L
        )

        `when`(repository.getAllNotesSync()).thenReturn(listOf(note1, note2))

        val graphData = graphEngine.buildGraph()
        assertEquals(2, graphData.nodes.size)
        assertTrue("Must extract at least 1 edge", graphData.edges.isNotEmpty())

        val wikilinkEdge = graphData.edges.find { it.from == "note_alpha" && it.to == "note_beta" }
        assertNotNull("Must find wikilink edge between Architecture Spec and Database Schema", wikilinkEdge)
        assertEquals("links to", wikilinkEdge?.label)
        assertEquals(1.0f, wikilinkEdge?.strength ?: 0f, 0.001f)

        // Verify that links were persisted to NoteLinkDao
        assertTrue("Must insert links to Room note_links table", fakeNoteLinkDao.insertedLinks.isNotEmpty())
        assertEquals("references", fakeNoteLinkDao.insertedLinks.first().linkType)
    }

    @Test
    fun testBuildGraph_extractsTagCoOccurrenceEdges() = runBlocking {
        val note1 = Note(
            id = "n1",
            title = "Onboarding Guide",
            content = "Instructions for new engineers joining the team.",
            plainText = "Instructions for new engineers joining the team.",
            tags = "[\"engineering\",\"team\"]",
            createdAt = 1000L,
            updatedAt = 1000L
        )
        val note2 = Note(
            id = "n2",
            title = "Coding Standards",
            content = "Kotlin style guide and clean architecture patterns.",
            plainText = "Kotlin style guide and clean architecture patterns.",
            tags = "[\"engineering\",\"guidelines\"]",
            createdAt = 2000L,
            updatedAt = 2000L
        )

        `when`(repository.getAllNotesSync()).thenReturn(listOf(note1, note2))

        val graphData = graphEngine.buildGraph()
        val tagEdge = graphData.edges.find { it.label.contains("shares #engineering") }
        assertNotNull("Notes sharing #engineering must have a tag co-occurrence edge", tagEdge)
        assertTrue("Tag edge strength should be at least 0.5", (tagEdge?.strength ?: 0f) >= 0.5f)
    }

    @Test
    fun testBuildGraph_extractsBm25RelevanceEdgesWhenNoModelDownloaded() = runBlocking {
        val noteA = Note(
            id = "qa1",
            title = "Automated Testing Strategy",
            content = "Exhaustive unit test coverage with Mockito, JUnit, and continuous integration pipeline testing.",
            plainText = "Exhaustive unit test coverage with Mockito, JUnit, and continuous integration pipeline testing.",
            tags = "[]",
            createdAt = 1000L,
            updatedAt = 1000L
        )
        val noteB = Note(
            id = "qa2",
            title = "CI Test Automation Setup",
            content = "Continuous integration pipeline running automated unit test coverage with Gradle.",
            plainText = "Continuous integration pipeline running automated unit test coverage with Gradle.",
            tags = "[]",
            createdAt = 2000L,
            updatedAt = 2000L
        )

        `when`(repository.getAllNotesSync()).thenReturn(listOf(noteA, noteB))

        val graphData = graphEngine.buildGraph()
        assertTrue("Even without an LLM model, BM25 overlap creates edges", graphData.edges.isNotEmpty())
        val bm25Edge = graphData.edges.first()
        assertEquals("related to", bm25Edge.label)
        assertTrue("Strength should be positive", bm25Edge.strength > 0f)
    }
}
