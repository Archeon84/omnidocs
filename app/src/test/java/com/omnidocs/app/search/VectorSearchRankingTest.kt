package com.omnidocs.app.search

import com.omnidocs.app.ai.BackgroundInferenceDispatcher
import com.omnidocs.app.data.local.EmbeddingDao
import com.omnidocs.app.data.local.NoteDao
import com.omnidocs.app.data.local.entity.EmbeddingEntity
import com.omnidocs.app.data.local.entity.NoteEntity
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.mockito.ArgumentMatchers.any
import org.mockito.ArgumentMatchers.anyLong
import org.mockito.ArgumentMatchers.anyString
import org.mockito.Mockito.`when`
import org.mockito.Mockito.mock

/**
 * Ranking regression tests for hybrid search:
 * - corpus (not candidate-local) IDF: discriminative gate terms keep weight
 * - title boost: title-exact beats body-incidental at equal/shorter length
 */
class VectorSearchRankingTest {

    private lateinit var noteDao: NoteDao
    private lateinit var embeddingDao: EmbeddingDao
    private lateinit var embeddingService: EmbeddingService
    private lateinit var dispatcher: BackgroundInferenceDispatcher
    private lateinit var search: VectorSearch

    private fun note(id: String, title: String, body: String): NoteEntity {
        return NoteEntity(
            id = id,
            title = title,
            content = body,
            plainText = body,
            isPinned = false,
            language = "en",
            createdAt = 1000L,
            updatedAt = 2000L,
            imageUrl = null
        )
    }

    @Before
    fun setUp() {
        noteDao = mock(NoteDao::class.java)
        embeddingDao = mock(EmbeddingDao::class.java)
        embeddingService = mock(EmbeddingService::class.java)
        dispatcher = mock(BackgroundInferenceDispatcher::class.java)
        search = VectorSearch(embeddingDao, noteDao, embeddingService, dispatcher)
    }

    private suspend fun noSemantic() {
        `when`(embeddingService.activeModelName()).thenReturn("ngram-hash-v1")
        `when`(embeddingDao.countEmbeddingsByTypeAndModel("note", "ngram-hash-v1")).thenReturn(1)
        `when`(embeddingDao.getEmbeddingsByTypeAndModel("note", "ngram-hash-v1")).thenReturn(emptyList())
    }

    @Test
    fun discriminativeTerm_keepsWeightAgainstCommonTerm() = runBlocking {
        // Corpus: "setup" is everywhere (low true IDF), "rag" is rare.
        val right = note(
            "right", "RAG System Guide",
            "rag system " + "architecture patterns deployment pipelines review notes ".repeat(20)
        )
        val wrong = note("wrong", "Setup", "setup")
        val corpus = listOf(
            right, wrong,
            note("c3", "Setup Notes", "setup checklist for onboarding"),
            note("c4", "System Setup", "system setup instructions"),
            note("c5", "Deploy Setup", "setup steps for release"),
            note("c6", "Other", "unrelated content here")
        )
        `when`(noteDao.searchNotesFtsSync(anyString())).thenReturn(listOf(right, wrong))
        `when`(noteDao.getAllNotesSync()).thenReturn(corpus)
        noSemantic()

        val results = search.hybridSearch("rag system setup")

        assertTrue("expected results, got none", results.isNotEmpty())
        assertEquals(
            "discriminative 'rag' must outrank incidental 'setup', got ${results.map { it.note.id }}",
            "right",
            results.first().note.id
        )
    }

    @Test
    fun titleExact_beatsBodyIncidental() = runBlocking {
        val titleNote = note(
            "title", "Skateboard maintenance",
            "general notes about weekend plans and household chores ".repeat(3)
        )
        val bodyNote = note(
            "body", "Weekend plans",
            "skateboard " + "general notes about weekend plans and household chores ".repeat(3)
        )
        // Wider corpus so IDF discriminates (a 2-note corpus flattens IDF
        // and both notes sit near the floor regardless of title boost).
        val corpus = listOf(
            titleNote, bodyNote,
            note("f1", "Groceries", "milk eggs bread butter cheese"),
            note("f2", "Rent", "monthly payment due first week"),
            note("f3", "Standup", "daily sync blockers progress update"),
            note("f4", "Ideas", "random thoughts and observations")
        )
        `when`(noteDao.searchNotesFtsSync(anyString())).thenReturn(listOf(titleNote, bodyNote))
        `when`(noteDao.getAllNotesSync()).thenReturn(corpus)
        noSemantic()

        val results = search.hybridSearch("skateboard")

        assertTrue("expected results, got none", results.isNotEmpty())
        assertEquals("title", results.first().note.id)
    }

    @Test
    fun unrelatedNoteRejectedWhenQueryMissing_ngramModelNeverEmitsFakeSemantic() = runBlocking {
        // When n-gram hash model is used, a note with NO query terms must NEVER be admitted
        // as a "semantic" match due to random character 3-gram hash collisions.
        val unrelatedNote = note("unrelated", "Grocery Shopping", "milk eggs bread butter cheese")
        val corpus = listOf(unrelatedNote)

        `when`(noteDao.searchNotesFtsSync(anyString())).thenReturn(emptyList())
        `when`(noteDao.getAllNotesSync()).thenReturn(corpus)

        // Simulate n-gram embedding returning pseudo-similarity 0.18 (> 0.12 floor)
        `when`(embeddingService.activeModelName()).thenReturn("ngram-hash-v1")
        `when`(embeddingDao.countEmbeddingsByTypeAndModel("note", "ngram-hash-v1")).thenReturn(1)
        val fakeNgramEmbedding = EmbeddingEntity(
            id = "note:unrelated:ngram-hash-v1",
            sourceType = "note",
            sourceId = "unrelated",
            chunkHash = "hash1",
            modelName = "ngram-hash-v1",
            embeddingVector = ByteArray(10),
            createdAt = 1000L
        )
        `when`(embeddingDao.getEmbeddingsByTypeAndModel("note", "ngram-hash-v1")).thenReturn(listOf(fakeNgramEmbedding))
        val queryVector = FloatArray(128) { 0.1f }
        val noteVector = FloatArray(128) { 0.1f }
        `when`(embeddingService.generateEmbedding("astronomy")).thenReturn(queryVector)
        `when`(embeddingService.cachedVector(fakeNgramEmbedding.id, fakeNgramEmbedding.createdAt, fakeNgramEmbedding.embeddingVector)).thenReturn(noteVector)
        `when`(embeddingService.cosineSimilarity(queryVector, noteVector)).thenReturn(0.18f)

        // Query: "astronomy" (does not exist in "Grocery Shopping")
        val results = search.hybridSearch("astronomy")

        // Must be completely empty! Zero irrelevant notes shown.
        assertTrue("Expected empty results for unrelated query on n-gram fallback, but got ${results.map { it.note.title }}", results.isEmpty())
    }

    @Test
    fun neuralModelHighConfidenceSemanticAllowed_lowConfidenceRejected() = runBlocking {
        // When real neural model (e5) is active:
        // High confidence (>= 0.55) semantic matches without keyword overlap are allowed.
        // Low confidence (< 0.55) semantic matches without keyword overlap are rejected as drift.
        val strongParaphrase = note("strong", "Doctor Consultation", "The clinical specialist reviewed the symptoms.")
        val weakNoise = note("weak", "Car Maintenance", "Check engine oil and tire pressure.")
        val corpus = listOf(strongParaphrase, weakNoise)

        `when`(noteDao.searchNotesFtsSync(anyString())).thenReturn(emptyList())
        `when`(noteDao.getAllNotesSync()).thenReturn(corpus)
        `when`(noteDao.getNotesByIdsSync(listOf("strong"))).thenReturn(listOf(strongParaphrase))

        val modelName = "multilingual-e5-small_384"
        `when`(embeddingService.activeModelName()).thenReturn(modelName)
        `when`(embeddingDao.countEmbeddingsByTypeAndModel("note", modelName)).thenReturn(2)

        val strongEmbedding = EmbeddingEntity(
            id = "note:strong:0:$modelName",
            sourceType = "note",
            sourceId = "strong",
            chunkHash = "hash1",
            modelName = modelName,
            embeddingVector = ByteArray(10),
            createdAt = 1000L
        )
        val weakEmbedding = EmbeddingEntity(
            id = "note:weak:0:$modelName",
            sourceType = "note",
            sourceId = "weak",
            chunkHash = "hash2",
            modelName = modelName,
            embeddingVector = ByteArray(10),
            createdAt = 1000L
        )
        `when`(embeddingDao.getEmbeddingsByTypeAndModel("note", modelName)).thenReturn(listOf(strongEmbedding, weakEmbedding))

        val queryVec = FloatArray(384) { 0.1f }
        val strongVec = FloatArray(384) { 0.2f }
        val weakVec = FloatArray(384) { 0.05f }

        `when`(embeddingService.generateEmbedding("physician appointment")).thenReturn(queryVec)
        `when`(embeddingService.cachedVector(strongEmbedding.id, strongEmbedding.createdAt, strongEmbedding.embeddingVector)).thenReturn(strongVec)
        `when`(embeddingService.cachedVector(weakEmbedding.id, weakEmbedding.createdAt, weakEmbedding.embeddingVector)).thenReturn(weakVec)

        // Strong paraphrase: cosine 0.65 (>= 0.55 floor)
        `when`(embeddingService.cosineSimilarity(queryVec, strongVec)).thenReturn(0.65f)
        // Weak noise: cosine 0.35 (> 0.30 floor, but < 0.55 standalone floor)
        `when`(embeddingService.cosineSimilarity(queryVec, weakVec)).thenReturn(0.35f)

        val results = search.hybridSearch("physician appointment")

        assertEquals("Only the high-confidence semantic match should be returned", 1, results.size)
        assertEquals("strong", results.first().note.id)
        assertEquals("semantic", results.first().matchType)
    }

    @Test
    fun `stage 2 reranking boosts exact phrase matches and term proximity`() {
        // Candidate 1 has terms scattered far apart with a slightly higher initial score
        val scatteredNote = note(
            "scattered", "System Architecture",
            "encryption protocols are defined here. ".repeat(10) +
                "In a distant section we mention the database storage."
        )
        val scatteredCandidate = VectorSearch.SearchResult(
            note = scatteredNote,
            score = 0.80f,
            matchType = "bm25",
            explanation = "BM25 match",
            rawScore = 0.80f
        )

        // Candidate 2 has an exact phrase match with a slightly lower initial score
        val exactPhraseNote = note(
            "exact", "Database Engine",
            "Hardware-backed database encryption using SQLCipher."
        )
        val exactCandidate = VectorSearch.SearchResult(
            note = exactPhraseNote,
            score = 0.78f,
            matchType = "bm25",
            explanation = "BM25 match",
            rawScore = 0.78f
        )

        val query = "database encryption"
        val candidates = listOf(scatteredCandidate, exactCandidate)

        val reranked = VectorSearch.rerank(query, candidates)

        assertEquals("Stage 2 reranker must promote exact phrase candidate to #1", "exact", reranked.first().note.id)
        assertTrue("Exact phrase score should be boosted", reranked.first().score > 0.78f)
    }
}
