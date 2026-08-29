package com.omnidocs.app.agent

import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

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
                quoteHash = "hash123"
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
        val answer = "Insufficient evidence in your workspace to answer this question."
        val citations = emptyList<Citation>()

        val report = verifier.verify(
            answer = answer,
            citations = citations,
            initialConfidence = "LOW"
        )

        assertTrue(report.isVerified)
        assertEquals("LOW", report.finalConfidence)
        assertTrue(report.unsupportedClaims.isEmpty())
    }
}
