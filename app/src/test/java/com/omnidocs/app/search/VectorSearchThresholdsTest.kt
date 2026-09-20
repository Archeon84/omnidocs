package com.omnidocs.app.search

import org.junit.Assert.*
import org.junit.Test

class VectorSearchThresholdsTest {

    @Test
    fun effectiveMinScore_relaxesForShortQueries() {
        assertEquals(VectorSearch.SHORT_QUERY_MIN_SCORE, VectorSearch.effectiveMinScore(2))
        assertEquals(VectorSearch.SHORT_QUERY_MIN_SCORE, VectorSearch.effectiveMinScore(3))
        assertEquals(VectorSearch.MIN_COMBINED_SCORE, VectorSearch.effectiveMinScore(4))
    }

    @Test
    fun titleMatchesAllTerms_detectsTitleIntent() {
        // The reported bug: "rag system" vs a note titled around RAG.
        assertTrue(
            VectorSearch.titleMatchesAllTerms("RAG System Notes", listOf("rag", "system"))
        )
        assertFalse(
            VectorSearch.titleMatchesAllTerms("Cooking Recipes", listOf("rag", "system"))
        )
        // Partial title match is not enough.
        assertFalse(
            VectorSearch.titleMatchesAllTerms("System Administration", listOf("rag", "system"))
        )
        assertFalse(VectorSearch.titleMatchesAllTerms("RAG System", emptyList()))
        assertFalse(VectorSearch.titleMatchesAllTerms("", listOf("rag", "system")))
    }
}
