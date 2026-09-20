package com.omnidocs.app.agent

import com.omnidocs.app.ai.NoteBlockAdapter
import com.omnidocs.app.ai.SmartSnippetExtractor
import com.omnidocs.app.data.local.ContentBlockDao
import com.omnidocs.app.data.local.EmbeddingDao
import com.omnidocs.app.data.local.NoteDao
import com.omnidocs.app.data.local.NoteLinkDao
import com.omnidocs.app.data.local.entity.EmbeddingEntity
import com.omnidocs.app.data.local.entity.NoteEntity
import com.omnidocs.app.search.EmbeddingService
import com.omnidocs.app.search.VectorSearch
import com.omnidocs.app.search.VectorSearch.SearchResult
import com.omnidocs.app.vocabulary.VocabularyDictionaryService
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.mockito.Mockito.*

class RetrievalAgentPassageMetadataTest {

    private val vectorSearch = mock(VectorSearch::class.java)
    private val noteDao = mock(NoteDao::class.java)
    private val contentBlockDao = mock(ContentBlockDao::class.java)
    private val smartSnippetExtractor = mock(SmartSnippetExtractor::class.java)
    private val vocabularyDictionaryService = VocabularyDictionaryService()
    private val noteBlockAdapter = mock(NoteBlockAdapter::class.java)
    private val noteLinkDao = mock(NoteLinkDao::class.java)
    private val embeddingDao = mock(EmbeddingDao::class.java)
    private val embeddingService = mock(EmbeddingService::class.java)

    private lateinit var agent: RetrievalAgent

    @Before
    fun setUp() {
        agent = RetrievalAgent(
            vectorSearch = vectorSearch,
            noteDao = noteDao,
            contentBlockDao = contentBlockDao,
            smartSnippetExtractor = smartSnippetExtractor,
            vocabularyDictionaryService = vocabularyDictionaryService,
            noteBlockAdapter = noteBlockAdapter,
            noteLinkDao = noteLinkDao,
            embeddingDao = embeddingDao,
            embeddingService = embeddingService
        )
    }

    @Test
    fun retrieveEvidence_usesPersistedChunkMetadata_withoutCallingChunkText() = runTest {
        val query = "machine learning architectures"
        val model = "multilingual-e5-small_384"
        `when`(embeddingService.activeModelName()).thenReturn(model)

        val note = NoteEntity(
            id = "note_long_doc",
            title = "Transformer Notes",
            content = "Extensive 5,000 word note on deep neural networks...",
            plainText = "Extensive 5,000 word note on deep neural networks...",
            isPinned = false,
            language = "en",
            createdAt = 1000L,
            updatedAt = 1000L,
            imageUrl = null
        )

        val searchResult = SearchResult(
            note = note,
            score = 0.85f,
            matchType = "hybrid",
            explanation = "Hybrid match",
            rawScore = 0.85f,
            passageScore = 0.88f,
            topPassageIndex = 2,
            topPassageIndices = listOf(2)
        )

        `when`(vectorSearch.hybridSearch(anyString(), anyFloat(), anyFloat(), anyInt()))
            .thenReturn(listOf(searchResult))
        `when`(contentBlockDao.getBlocksForNoteIds(anyList())).thenReturn(emptyList())

        // Stored chunks in database with persisted metadata
        val storedChunk = EmbeddingEntity(
            id = "note:note_long_doc:2:$model",
            sourceType = "note",
            sourceId = "note_long_doc",
            chunkHash = "hash2",
            modelName = model,
            embeddingVector = ByteArray(10),
            createdAt = 1000L,
            chunkText = "This is the exact persisted passage text about Transformer self-attention.",
            sectionHeader = "Attention Mechanisms",
            startOffset = 2500,
            endOffset = 3100,
            chunkIndex = 2
        )

        `when`(embeddingDao.getNotePassageEmbeddings("note_long_doc", model))
            .thenReturn(listOf(storedChunk))

        val candidates = agent.retrieveEvidence(query, bm25Weight = 0.5f, semanticWeight = 0.5f, topK = 5)

        // 1. Evidence was extracted from stored chunk
        assertEquals(1, candidates.size)
        val candidate = candidates[0]
        assertEquals("note_long_doc", candidate.noteId)
        assertEquals("Transformer Notes > Attention Mechanisms", candidate.noteTitle)
        assertEquals("This is the exact persisted passage text about Transformer self-attention.", candidate.text)
        assertEquals(2500, candidate.startOffset)
        assertEquals(3100, candidate.endOffset)

        // 2. noteBlockAdapter.chunkText was NEVER called because persisted metadata was used!
        verify(noteBlockAdapter, never()).chunkText(anyString(), anyString(), anyString())
    }
}
