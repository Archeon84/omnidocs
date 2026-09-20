package com.omnidocs.app.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VerificationAgentTest {

    private val agent = VerificationAgent()

    @Test
    fun testVerify_englishSupportedBySharedTerms() {
        val citations = listOf(
            Citation(
                noteId = "n1",
                noteTitle = "Architecture Design",
                quoteSnippet = "The database cluster uses PostgreSQL 16 with streaming replication.",
                quoteHash = "hash1",
                sourceIndex = 1
            )
        )
        val answer = "Based on [Source 1], the database cluster uses PostgreSQL 16."
        val report = agent.verify(answer, citations, "HIGH")
        assertTrue("Verification should pass when carrier sentence shares words with citation", report.isVerified)
        assertEquals("HIGH", report.finalConfidence)
    }

    @Test
    fun testVerify_cjkSupportedByNgramsAndFullwidthPunctuation() {
        val citations = listOf(
            Citation(
                noteId = "n2",
                noteTitle = "财务预算",
                quoteSnippet = "第四季度企业财务预算总计两千万元人民币。",
                quoteHash = "hash2",
                sourceIndex = 1
            )
        )
        // Chinese answer with fullwidth punctuation "。" and [Source 1]
        val answer = "根据[Source 1]，第四季度企业财务预算总额已定。"
        val report = agent.verify(answer, citations, "HIGH")
        assertTrue("Verification should pass for CJK text using character n-gram entailment", report.isVerified)
        assertEquals("HIGH", report.finalConfidence)
    }

    @Test
    fun testVerify_failsOnHallucinatedDanglingSource() {
        val citations = listOf(
            Citation(
                noteId = "n1",
                noteTitle = "Title",
                quoteSnippet = "Some content",
                quoteHash = "hash1",
                sourceIndex = 1
            )
        )
        val answer = "According to [Source 2], the project is delayed."
        val report = agent.verify(answer, citations, "HIGH")
        assertFalse("Verification must fail when citation source index is missing", report.isVerified)
        assertEquals("LOW", report.finalConfidence)
    }

    @Test
    fun testVerify_abstentionPassesWithLowConfidence() {
        val citations = emptyList<Citation>()
        val answer = "Insufficient evidence in your workspace to answer this question."
        val report = agent.verify(answer, citations, "HIGH")
        assertTrue("Abstention should verify as valid abstention", report.isVerified)
        assertEquals("LOW", report.finalConfidence)
    }
}
