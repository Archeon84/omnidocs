package com.omnidocs.app.agent

import com.omnidocs.app.ai.NoteBlockAdapter
import com.omnidocs.app.ai.SmartSnippetExtractor
import com.omnidocs.app.data.local.ContentBlockDao
import com.omnidocs.app.data.local.NoteDao
import com.omnidocs.app.data.local.NoteLinkDao
import com.omnidocs.app.data.local.entity.NoteEntity
import com.omnidocs.app.search.VectorSearch
import com.omnidocs.app.search.VectorSearch.SearchResult
import com.omnidocs.app.vocabulary.VocabularyDictionaryService
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test
import org.mockito.ArgumentMatchers.anyFloat
import org.mockito.ArgumentMatchers.anyInt
import org.mockito.ArgumentMatchers.anyList
import org.mockito.ArgumentMatchers.anyString
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`

class ResearchAgentsTest {

    @Test
    fun `query classifier identifies exact, task, date, and semantic query types`() = runTest {
        val classifier = QueryClassifierAgent()

        val exactStrategy = classifier.classify("\"exact phrase match\"")
        assertEquals(QueryType.EXACT, exactStrategy.queryType)
        assertEquals(0.9f, exactStrategy.bm25Weight, 0.01f)

        val taskStrategy = classifier.classify("What are my pending tasks and todo items?")
        assertEquals(QueryType.TASK, taskStrategy.queryType)

        val dateStrategy = classifier.classify("Notes from 2026-08-28")
        assertEquals(QueryType.DATE, dateStrategy.queryType)

        val semanticStrategy = classifier.classify("How do we implement offline speech recognition?")
        assertEquals(QueryType.SEMANTIC, semanticStrategy.queryType)
    }

    @Test
    fun `verification agent validates citations when answer is supported by evidence`() = runTest {
        val verifier = VerificationAgent()
        val answer = "Based on [Source 1], the database migration from version 10 to 11 adds source documents."
        val citations = listOf(
            Citation(
                noteId = "note_1",
                noteTitle = "Architecture Spec",
                quoteSnippet = "database migration from version 10 to 11",
                quoteHash = "hash123",
                sourceIndex = 1
            )
        )

        val report = verifier.verify(
            answer = answer,
            citations = citations,
            initialConfidence = "HIGH"
        )

        assertTrue(report.isVerified)
        assertEquals("HIGH", report.finalConfidence)
        assertEquals(1.0f, report.citationCoverage, 0.01f)
    }

    @Test
    fun `verification agent flags unsupported answers when citations are missing`() = runTest {
        val verifier = VerificationAgent()
        val answer = "This is an ungrounded hallucination."
        val citations = emptyList<Citation>()

        val report = verifier.verify(
            answer = answer,
            citations = citations,
            initialConfidence = "HIGH"
        )

        assertFalse(report.isVerified)
        assertEquals("LOW", report.finalConfidence)
        assertTrue(report.unsupportedClaims.isNotEmpty())
    }

    @Test
    fun `verification agent allows insufficient evidence markers as verified`() = runTest {
        val verifier = VerificationAgent()
        val variations = listOf(
            "Insufficient evidence in your workspace to answer this question.",
            "Insufficient data.",
            "Based on the provided notes, there is insufficient data to answer your question.",
            "Based on your notes, there is insufficient information.",
            "Your workspace does not contain relevant notes on this topic."
        )

        for (answer in variations) {
            val report = verifier.verify(
                answer = answer,
                citations = emptyList(),
                initialConfidence = "LOW"
            )

            assertTrue("Expected verified=true for '$answer'", report.isVerified)
            assertEquals("Expected confidence=LOW for '$answer'", "LOW", report.finalConfidence)
            assertTrue("Expected no unsupported claims for '$answer'", report.unsupportedClaims.isEmpty())
        }
    }

    @Test
    fun `verification agent fails dangling source references`() = runTest {
        val verifier = VerificationAgent()
        val answer = "Based on [Source 9], the database migration adds source documents."
        val citations = listOf(
            Citation(
                noteId = "note_1",
                noteTitle = "Architecture Spec",
                quoteSnippet = "database migration from version 10 to 11",
                quoteHash = "hash123",
                sourceIndex = 1
            )
        )

        val report = verifier.verify(
            answer = answer,
            citations = citations,
            initialConfidence = "HIGH"
        )

        assertFalse(report.isVerified)
        assertTrue(report.unsupportedClaims.any { it.contains("[Source 9]") })
    }

    @Test
    fun `verification agent fails abstention accompanied by extra claims`() = runTest {
        val verifier = VerificationAgent()
        val answer = "Insufficient evidence in your workspace. However the database migration " +
            "from version 10 to 11 definitely adds source documents and several other features."
        val citations = emptyList<Citation>()

        val report = verifier.verify(
            answer = answer,
            citations = citations,
            initialConfidence = "LOW"
        )

        assertFalse(report.isVerified)
    }

    @Test
    fun `verification agent fails emitted citations never referenced`() = runTest {
        val verifier = VerificationAgent()
        val answer = "Based on [Source 1], the database migration from version 10 to 11 adds source documents."
        val citations = listOf(
            Citation(
                noteId = "note_1",
                noteTitle = "Architecture Spec",
                quoteSnippet = "database migration from version 10 to 11",
                quoteHash = "hash123",
                sourceIndex = 1
            ),
            Citation(
                noteId = "note_2",
                noteTitle = "Unrelated Notes",
                quoteSnippet = "quarterly financial planning and budgeting",
                quoteHash = "hash456",
                sourceIndex = 2
            )
        )

        val report = verifier.verify(
            answer = answer,
            citations = citations,
            initialConfidence = "HIGH"
        )

        assertFalse(report.isVerified)
        assertTrue(report.unsupportedClaims.any { it.contains("[Source 2]") })
    }

    @Test
    fun `computeRequestedMaxTokens never exceeds context room`() {
        // Small prompt: full default ceiling (1500) under 8192 context.
        assertEquals(1500, computeRequestedMaxTokens(400))
        // High-budget prompt at 8192 context: 20000 chars ~ 6667 tokens: room is 8192 - 6667 - 128 = 1397.
        assertEquals(1397, computeRequestedMaxTokens(20000))
        // Saturated / pathological prompt: floored to safe minimum headroom (128).
        assertEquals(128, computeRequestedMaxTokens(25000))
        assertEquals(128, computeRequestedMaxTokens(35000))
    }

    @Test
    fun `isAbstentionOnly distinguishes abstention from claims across semantic variations`() {
        // Exact and phrasing variations
        assertTrue(isAbstentionOnly("Insufficient evidence in your workspace to answer this question."))
        assertTrue(isAbstentionOnly("Insufficient evidence"))
        assertTrue(isAbstentionOnly("Insufficient data"))
        assertTrue(isAbstentionOnly("Insufficient data to answer your question."))
        assertTrue(isAbstentionOnly("There is insufficient data in your workspace."))
        assertTrue(isAbstentionOnly("I found insufficient evidence to answer."))
        assertTrue(isAbstentionOnly("Not enough information in your notes."))
        assertTrue(isAbstentionOnly("No relevant information found in your workspace."))
        assertTrue(isAbstentionOnly("The workspace does not contain details about this topic."))
        assertTrue(isAbstentionOnly("Your workspace does not contain relevant notes."))
        assertTrue(isAbstentionOnly("Could not find any relevant evidence in your notes."))

        // Conversational preambles
        assertTrue(isAbstentionOnly("Based on the provided notes, there is insufficient data to answer."))
        assertTrue(isAbstentionOnly("Based on your notes, there is insufficient evidence."))
        assertTrue(isAbstentionOnly("Based on the workspace, not enough information is available."))

        // Truncated answers without trailing punctuation
        assertTrue(isAbstentionOnly("Based on the provided notes, there is insufficient data"))
        assertTrue(isAbstentionOnly("There is insufficient evidence in your workspace to"))

        // Claims after the marker are NOT an abstention: they must go to
        // the verifier, which fails them honestly if unsupported.
        assertFalse(
            isAbstentionOnly(
                "Insufficient evidence in your workspace. However the database migration " +
                    "from version 10 to 11 definitely adds source documents."
            )
        )
        assertFalse(
            isAbstentionOnly(
                "Based on the provided notes, there is insufficient data. In 2024 we migrated to Room."
            )
        )
        assertFalse(isAbstentionOnly("Based on [Source 1], the migration adds tables."))
        assertFalse(isAbstentionOnly(""))
    }

    @Test
    fun `drift gate relaxes for short queries but still rejects drift`() {
        // Weak semantic + literal overlap: passes at either threshold.
        assertTrue(
            RetrievalAgent.passesDriftGate(
                matchType = "semantic", score = 0.35f,
                queryTermSet = setOf("rag", "system"),
                noteTitle = "RAG System", notePlainText = "notes about pipelines",
                strongThreshold = RetrievalAgent.driftStrongThreshold(2)
            )
        )
        // Weak semantic, no overlap: rejected even at the relaxed bar.
        assertFalse(
            RetrievalAgent.passesDriftGate(
                matchType = "semantic", score = 0.35f,
                queryTermSet = setOf("rag", "system"),
                noteTitle = "Smoking", notePlainText = "quitting was hard",
                strongThreshold = RetrievalAgent.driftStrongThreshold(2)
            )
        )
        // Short-query bar is 0.40: 0.42 passes without overlap.
        assertEquals(0.40f, RetrievalAgent.driftStrongThreshold(2))
        assertTrue(
            RetrievalAgent.passesDriftGate(
                matchType = "semantic", score = 0.42f,
                queryTermSet = setOf("rag", "system"),
                noteTitle = "Smoking", notePlainText = "quitting was hard",
                strongThreshold = RetrievalAgent.driftStrongThreshold(2)
            )
        )
        // Long queries keep the strict 0.50 bar.
        assertEquals(0.50f, RetrievalAgent.driftStrongThreshold(5))
        // Non-semantic legs always pass.
        assertTrue(
            RetrievalAgent.passesDriftGate(
                matchType = "bm25", score = 0.10f,
                queryTermSet = setOf("rag", "system"),
                noteTitle = "Smoking", notePlainText = "quitting was hard",
                strongThreshold = 0.50f
            )
        )
    }

    @Test
    fun `parseReferencedSources resolves every citation shape`() {
        // The original bug: only "[Source N]" matched, so "[Sources 1-6]"
        // collapsed to the top-1 fallback citation.
        assertEquals(
            setOf(1, 2, 3, 4, 5, 6),
            parseReferencedSources("The migration spans services [Sources 1-6].", 6)
        )
        assertEquals(setOf(1), parseReferencedSources("Migration adds tables [Source 1].", 6))
        assertEquals(
            setOf(1, 3, 5),
            parseReferencedSources("Sources: 1, 3, 5 cover the schema.", 6)
        )
        assertEquals(setOf(2), parseReferencedSources("See [2] for details.", 6))
        assertEquals(setOf(1, 2), parseReferencedSources("Details in [1, 2].", 6))
        // Out-of-range refs are dropped for citation filtering but kept for
        // the verifier (called with Int.MAX_VALUE) to flag as dangling.
        assertEquals(setOf(9), parseReferencedSources("Based on [Source 9].", Int.MAX_VALUE))
        assertEquals(setOf<Int>(), parseReferencedSources("No citations here.", 6))
    }

    @Test
    fun `verification agent verifies numeric and ranged citation formats`() = runTest {
        val verifier = VerificationAgent()
        val answer = "The database migration from version 10 to 11 adds source documents [1, 2]."
        val citations = listOf(
            Citation(
                noteId = "note_1",
                noteTitle = "Architecture Spec",
                quoteSnippet = "database migration from version 10 to 11",
                quoteHash = "hash123",
                sourceIndex = 1
            ),
            Citation(
                noteId = "note_2",
                noteTitle = "Document Schema",
                quoteSnippet = "adds source documents and metadata tables",
                quoteHash = "hash456",
                sourceIndex = 2
            )
        )

        val report = verifier.verify(
            answer = answer,
            citations = citations,
            initialConfidence = "HIGH"
        )

        assertTrue("Expected verified=true for bracketed numeric citations [1, 2]", report.isVerified)
        assertEquals("HIGH", report.finalConfidence)
        assertEquals(1.0f, report.citationCoverage, 0.01f)
    }

    @Test
    fun `verification agent rejects hallucination that only mentions note title`() = runTest {
        val verifier = VerificationAgent()
        // Claim mentions note title "Architecture Spec" but the claim itself is completely false
        val answer = "Based on [Source 1], Architecture Spec says pigs can fly in outer space."
        val citations = listOf(
            Citation(
                noteId = "note_1",
                noteTitle = "Architecture Spec",
                quoteSnippet = "database migration from version 10 to 11 adds source documents and schema tables",
                quoteHash = "hash123",
                sourceIndex = 1
            )
        )

        val report = verifier.verify(
            answer = answer,
            citations = citations,
            initialConfidence = "HIGH"
        )

        assertFalse("Hallucination mentioning note title must be rejected", report.isVerified)
        assertEquals("LOW", report.finalConfidence)
        assertTrue(report.unsupportedClaims.isNotEmpty())
    }

    @Test
    fun `verification agent verifies claim supported in long snippet`() = runTest {
        val verifier = VerificationAgent()
        val answer = "Based on [Source 1], the backup service uses AES-256 GCM encryption."
        // Evidence is located after the initial 150 characters
        val longSnippet = "The system implements automatic cloud and local backup. ".repeat(4) +
            "For security, the backup service uses AES-256 GCM encryption on all database exports."
        val citations = listOf(
            Citation(
                noteId = "note_1",
                noteTitle = "Security Whitepaper",
                quoteSnippet = longSnippet,
                quoteHash = "hash456",
                sourceIndex = 1
            )
        )

        val report = verifier.verify(
            answer = answer,
            citations = citations,
            initialConfidence = "HIGH"
        )

        assertTrue("Supported claim in long snippet must be verified", report.isVerified)
        assertEquals("HIGH", report.finalConfidence)
        assertTrue(report.unsupportedClaims.isEmpty())
    }

    @Test
    fun `verification agent recovers verified answer via surgical self-correction`() = runTest {
        val verifier = VerificationAgent()
        // Sentence 1 is verified; Sentence 2 is a hallucination
        val answer = "Based on [Source 1], the backup service uses AES-256 GCM encryption. " +
            "Based on [Source 1], alien spaceships are built in the database."

        val citations = listOf(
            Citation(
                noteId = "note_1",
                noteTitle = "Security Whitepaper",
                quoteSnippet = "The backup service uses AES-256 GCM encryption on all database exports.",
                quoteHash = "hash123",
                sourceIndex = 1
            )
        )

        val report = verifier.verify(
            answer = answer,
            citations = citations,
            initialConfidence = "HIGH"
        )

        // Raw multi-sentence answer must fail due to Sentence 2
        assertFalse("Unmodified answer must be unverified", report.isVerified)
        assertNotNull("Must produce correctedAnswer", report.correctedAnswer)
        assertTrue(
            "Corrected answer must preserve supported sentence",
            report.correctedAnswer?.contains("AES-256 GCM encryption") == true
        )
        assertFalse(
            "Corrected answer must surgically redact unsupported sentence",
            report.correctedAnswer?.contains("alien spaceships") == true
        )
    }

    @Test
    fun `parsePassageIndex correctly parses passage embedding IDs`() {
        assertEquals(0, com.omnidocs.app.search.VectorSearch.parsePassageIndex("note:abc:0:e5_384", "abc"))
        assertEquals(5, com.omnidocs.app.search.VectorSearch.parsePassageIndex("note:abc:5:multilingual-e5-small_384", "abc"))
        // Legacy full note embedding has no passage index
        assertNull(com.omnidocs.app.search.VectorSearch.parsePassageIndex("note:abc:ngram-hash-v1", "abc"))
        assertNull(com.omnidocs.app.search.VectorSearch.parsePassageIndex("note:other:0:e5_384", "abc"))
    }

    @Test
    fun `retrieval agent extracts winning passage chunk directly when topPassageIndex is present`() = runTest {
        val noteDao = mock(NoteDao::class.java)
        val smartSnippetExtractor = SmartSnippetExtractor()
        val vocabService = VocabularyDictionaryService()
        val noteBlockAdapter = NoteBlockAdapter()

        val fakeContentBlockDao = object : ContentBlockDao {
            override fun getBlocksForNote(noteId: String) = kotlinx.coroutines.flow.emptyFlow<List<com.omnidocs.app.data.local.entity.ContentBlockEntity>>()
            override suspend fun getBlocksForNoteSync(noteId: String) = emptyList<com.omnidocs.app.data.local.entity.ContentBlockEntity>()
            override suspend fun getBlocksForNoteIds(noteIds: List<String>) = emptyList<com.omnidocs.app.data.local.entity.ContentBlockEntity>()
            override fun getBlocksForSourceDocument(sourceDocId: String) = kotlinx.coroutines.flow.emptyFlow<List<com.omnidocs.app.data.local.entity.ContentBlockEntity>>()
            override suspend fun insertBlocks(blocks: List<com.omnidocs.app.data.local.entity.ContentBlockEntity>) {}
            override suspend fun insertBlock(block: com.omnidocs.app.data.local.entity.ContentBlockEntity) {}
            override suspend fun deleteBlocksForNote(noteId: String) {}
            override suspend fun deleteBlocksForSourceDocument(sourceDocId: String) {}
        }

        val note = NoteEntity(
            id = "note_100",
            title = "Audio Transcription Spec",
            content = "# Introduction\n\nIntroductory metadata.\n\n# Speech Engine\n\nWhisper small onnx model runs offline on device.",
            plainText = "# Introduction\n\nIntroductory metadata.\n\n# Speech Engine\n\nWhisper small onnx model runs offline on device.",
            isPinned = false,
            language = "en",
            createdAt = 1000L,
            updatedAt = 2000L,
            imageUrl = null
        )

        // Simulate VectorSearch hitting passage index 1 ("# Speech Engine...")
        val searchResult = SearchResult(
            note = note,
            score = 0.85f,
            matchType = "semantic",
            explanation = "Related (score: 85%) by meaning",
            rawScore = 0.85f,
            topPassageIndex = 1,
            passageScore = 0.85f
        )

        val fakeVectorSearch = object : VectorSearch(
            mock(com.omnidocs.app.data.local.EmbeddingDao::class.java),
            noteDao,
            mock(com.omnidocs.app.search.EmbeddingService::class.java),
            mock(com.omnidocs.app.ai.BackgroundInferenceDispatcher::class.java)
        ) {
            override suspend fun hybridSearch(
                query: String,
                bm25Weight: Float,
                semanticWeight: Float,
                limit: Int
            ): List<SearchResult> = listOf(searchResult)
        }

        val noteLinkDao = mock(NoteLinkDao::class.java)
        `when`(noteLinkDao.getLinksForNote(anyString())).thenReturn(flowOf(emptyList()))

        val retrievalAgent = RetrievalAgent(
            vectorSearch = fakeVectorSearch,
            noteDao = noteDao,
            contentBlockDao = fakeContentBlockDao,
            smartSnippetExtractor = smartSnippetExtractor,
            vocabularyDictionaryService = vocabService,
            noteBlockAdapter = noteBlockAdapter,
            noteLinkDao = noteLinkDao
        )

        val candidates = retrievalAgent.retrieveEvidence(
            query = "offline speech recognition",
            bm25Weight = 0.3f,
            semanticWeight = 0.7f,
            topK = 5
        )

        assertEquals(1, candidates.size)
        val candidate = candidates.first()
        // Winning passage index 1 must be extracted, NOT the introductory paragraph at index 0!
        assertTrue(
            "Expected passage chunk about speech engine, got: '${candidate.text}'",
            candidate.text.contains("Whisper small onnx")
        )
    }
}
