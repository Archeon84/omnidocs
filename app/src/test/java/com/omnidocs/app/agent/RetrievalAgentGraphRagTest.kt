package com.omnidocs.app.agent

import com.omnidocs.app.ai.NoteBlockAdapter
import com.omnidocs.app.ai.SmartSnippetExtractor
import com.omnidocs.app.data.local.ContentBlockDao
import com.omnidocs.app.data.local.NoteDao
import com.omnidocs.app.data.local.NoteLinkDao
import com.omnidocs.app.data.local.entity.NoteEntity
import com.omnidocs.app.data.local.entity.NoteLinkEntity
import com.omnidocs.app.search.VectorSearch
import com.omnidocs.app.search.VectorSearch.SearchResult
import com.omnidocs.app.vocabulary.VocabularyDictionaryService
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.mockito.ArgumentMatchers.anyFloat
import org.mockito.ArgumentMatchers.anyInt
import org.mockito.ArgumentMatchers.anyString
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`

class RetrievalAgentGraphRagTest {

    private val vectorSearch = mock(VectorSearch::class.java)
    private val noteDao = mock(NoteDao::class.java)
    private val contentBlockDao = mock(ContentBlockDao::class.java)
    private val smartSnippetExtractor = SmartSnippetExtractor()
    private val vocabularyDictionaryService = VocabularyDictionaryService()
    private val noteBlockAdapter = NoteBlockAdapter()
    private val noteLinkDao = mock(NoteLinkDao::class.java)

    private lateinit var retrievalAgent: RetrievalAgent

    @Before
    fun setUp() {
        retrievalAgent = RetrievalAgent(
            vectorSearch = vectorSearch,
            noteDao = noteDao,
            contentBlockDao = contentBlockDao,
            smartSnippetExtractor = smartSnippetExtractor,
            vocabularyDictionaryService = vocabularyDictionaryService,
            noteBlockAdapter = noteBlockAdapter,
            noteLinkDao = noteLinkDao
        )
    }

    @Test
    fun `retrieveEvidence performs 1-hop graph expansion for linked notes`() = runTest {
        val query = "system architecture design"

        // Seed Note A matches query
        val noteA = NoteEntity(
            id = "note_a",
            title = "Architecture Spec",
            content = "High-level overview of our architecture and design specifications.",
            plainText = "High-level overview of our architecture and design specifications.",
            isPinned = false,
            language = "en",
            createdAt = 1000L,
            updatedAt = 1000L,
            imageUrl = null
        )

        // Linked Note B contains key database details with NO query keyword overlap
        val noteB = NoteEntity(
            id = "note_b",
            title = "Database Storage Engine",
            content = "Encrypted SQLite persistence using SQLCipher and hardware keyStore.",
            plainText = "Encrypted SQLite persistence using SQLCipher and hardware keyStore.",
            isPinned = false,
            language = "en",
            createdAt = 2000L,
            updatedAt = 2000L,
            imageUrl = null
        )

        val searchResultA = SearchResult(
            note = noteA,
            score = 0.88f,
            matchType = "bm25",
            explanation = "BM25 match",
            rawScore = 0.88f
        )

        `when`(vectorSearch.hybridSearch(anyString(), anyFloat(), anyFloat(), anyInt()))
            .thenReturn(listOf(searchResultA))

        `when`(contentBlockDao.getBlocksForNoteIds(listOf("note_a"))).thenReturn(emptyList())

        // Graph link from Note A -> Note B
        val linkAB = NoteLinkEntity(
            id = "note_a_note_b",
            sourceNoteId = "note_a",
            targetNoteId = "note_b",
            linkType = "references",
            confidence = 1.0f,
            createdBy = "user",
            createdAt = 1500L
        )

        `when`(noteLinkDao.getLinksForNote("note_a")).thenReturn(flowOf(listOf(linkAB)))
        `when`(noteDao.getNoteById("note_b")).thenReturn(noteB)

        val candidates = retrievalAgent.retrieveEvidence(
            query = query,
            bm25Weight = 0.5f,
            semanticWeight = 0.5f,
            topK = 5
        )

        // Must return candidates for both seed Note A and Graph-expanded Note B
        assertTrue("Must retrieve multiple candidates", candidates.size >= 2)

        val candidateA = candidates.find { it.noteId == "note_a" }
        assertNotNull("Must contain seed candidate Note A", candidateA)
        assertEquals("Architecture Spec", candidateA?.noteTitle)

        val candidateB = candidates.find { it.noteId == "note_b" }
        assertNotNull("Graph RAG must expand to 1-hop neighbor Note B", candidateB)
        assertTrue(
            "Graph candidate must carry provenance in title",
            candidateB?.noteTitle?.contains("linked from [[Architecture Spec]]") == true
        )
        assertTrue(
            "Graph candidate text must contain neighbor content",
            candidateB?.text?.contains("SQLCipher") == true
        )
    }

    @Test
    fun `retrieveEvidence avoids duplicate expansion if neighbor is already a seed candidate`() = runTest {
        val query = "architecture database"

        val noteA = NoteEntity(
            id = "n_alpha",
            title = "Architecture",
            content = "System architecture overview.",
            plainText = "System architecture overview.",
            isPinned = false,
            language = "en",
            createdAt = 1000L,
            updatedAt = 1000L,
            imageUrl = null
        )

        val noteB = NoteEntity(
            id = "n_beta",
            title = "Database",
            content = "Database storage schema.",
            plainText = "Database storage schema.",
            isPinned = false,
            language = "en",
            createdAt = 2000L,
            updatedAt = 2000L,
            imageUrl = null
        )

        val resA = SearchResult(note = noteA, score = 0.9f, matchType = "bm25", explanation = "BM25 match", rawScore = 0.9f)
        val resB = SearchResult(note = noteB, score = 0.8f, matchType = "bm25", explanation = "BM25 match", rawScore = 0.8f)

        // Both A and B matched query as seeds
        `when`(vectorSearch.hybridSearch(anyString(), anyFloat(), anyFloat(), anyInt()))
            .thenReturn(listOf(resA, resB))

        `when`(contentBlockDao.getBlocksForNoteIds(listOf("n_alpha", "n_beta"))).thenReturn(emptyList())

        val linkAB = NoteLinkEntity(
            id = "alpha_beta",
            sourceNoteId = "n_alpha",
            targetNoteId = "n_beta",
            linkType = "references",
            confidence = 1.0f,
            createdBy = "user",
            createdAt = 1500L
        )

        `when`(noteLinkDao.getLinksForNote("n_alpha")).thenReturn(flowOf(listOf(linkAB)))
        `when`(noteLinkDao.getLinksForNote("n_beta")).thenReturn(flowOf(listOf(linkAB)))

        val candidates = retrievalAgent.retrieveEvidence(
            query = query,
            bm25Weight = 0.5f,
            semanticWeight = 0.5f,
            topK = 5
        )

        // Since noteB was already a seed candidate, it should not be redundantly re-expanded
        val noteBMatches = candidates.filter { it.noteId == "n_beta" }
        assertEquals("Note B should not be duplicated", 1, noteBMatches.size)
    }

    @Test
    fun `retrieveEvidence performs 2-hop graph expansion for high-confidence link paths`() = runTest {
        val query = "system architecture"

        val noteA = NoteEntity(
            id = "note_a",
            title = "Architecture Spec",
            content = "High-level overview of our architecture.",
            plainText = "High-level overview of our architecture.",
            isPinned = false,
            language = "en",
            createdAt = 1000L,
            updatedAt = 1000L,
            imageUrl = null
        )

        // 1-hop neighbor
        val noteB = NoteEntity(
            id = "note_b",
            title = "Database Engine",
            content = "SQLite persistence using SQLCipher layer.",
            plainText = "SQLite persistence using SQLCipher layer.",
            isPinned = false,
            language = "en",
            createdAt = 2000L,
            updatedAt = 2000L,
            imageUrl = null
        )

        // 2-hop neighbor (no direct link to Note A, zero query overlap)
        val noteC = NoteEntity(
            id = "note_c",
            title = "KeyStore Security",
            content = "Hardware enclave key derivation using StrongBox.",
            plainText = "Hardware enclave key derivation using StrongBox.",
            isPinned = false,
            language = "en",
            createdAt = 3000L,
            updatedAt = 3000L,
            imageUrl = null
        )

        val searchResultA = SearchResult(
            note = noteA,
            score = 0.95f,
            matchType = "bm25",
            explanation = "BM25 match",
            rawScore = 0.95f
        )

        `when`(vectorSearch.hybridSearch(anyString(), anyFloat(), anyFloat(), anyInt()))
            .thenReturn(listOf(searchResultA))

        `when`(contentBlockDao.getBlocksForNoteIds(listOf("note_a"))).thenReturn(emptyList())

        val linkAB = NoteLinkEntity(
            id = "link_ab",
            sourceNoteId = "note_a",
            targetNoteId = "note_b",
            linkType = "references",
            confidence = 0.9f,
            createdBy = "user",
            createdAt = 1500L
        )

        val linkBC = NoteLinkEntity(
            id = "link_bc",
            sourceNoteId = "note_b",
            targetNoteId = "note_c",
            linkType = "references",
            confidence = 0.9f,
            createdBy = "user",
            createdAt = 2500L
        )

        `when`(noteLinkDao.getLinksForNote("note_a")).thenReturn(flowOf(listOf(linkAB)))
        `when`(noteLinkDao.getLinksForNote("note_b")).thenReturn(flowOf(listOf(linkBC)))
        `when`(noteLinkDao.getLinksForNote("note_c")).thenReturn(flowOf(emptyList()))
        `when`(noteDao.getNoteById("note_b")).thenReturn(noteB)
        `when`(noteDao.getNoteById("note_c")).thenReturn(noteC)

        val candidates = retrievalAgent.retrieveEvidence(
            query = query,
            bm25Weight = 0.5f,
            semanticWeight = 0.5f,
            topK = 5
        )

        val candidateC = candidates.find { it.noteId == "note_c" }
        assertNotNull("Must expand 2 hops to Note C", candidateC)
        assertTrue(
            "2-hop candidate must carry multi-hop provenance in title",
            candidateC?.noteTitle?.contains("connected via [[Database Engine]] from [[Architecture Spec]]") == true
        )
        assertTrue(
            "2-hop candidate text must contain target content",
            candidateC?.text?.contains("StrongBox") == true
        )
    }
}
