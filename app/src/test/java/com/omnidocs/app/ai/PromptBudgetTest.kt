package com.omnidocs.app.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PromptBudgetTest {

    @Test
    fun groundedPrompt_questionSurvivesHugeContext() {
        val bigText = "lorem ipsum dolor sit amet ".repeat(2000) // ~54k chars
        val sources = List(15) { i ->
            PromptBudget.EvidenceSource(title = "Note $i", text = bigText, score = 1.0 - i * 0.05)
        }
        val question = "What is the capital allocation for Q3?"
        val budgeted = PromptBudget.buildGroundedUserPrompt(
            query = question,
            sources = sources,
            systemPrompt = "You are a grounded assistant."
        )
        // The question tail must always survive, within budget.
        assertTrue(budgeted.userPrompt.endsWith("Question: $question"))
        val systemPrompt = "You are a grounded assistant."
        val totalEstimate = PromptBudget.estimateTokens(budgeted.userPrompt.length) +
            PromptBudget.estimateTokens(systemPrompt.length) + 120 // template overhead
        assertTrue(
            "Estimated $totalEstimate tokens exceeds ${PromptBudget.MAX_PROMPT_TOKENS}",
            totalEstimate <= PromptBudget.MAX_PROMPT_TOKENS + 10 // rounding slack
        )
        assertTrue(budgeted.truncated)
        // Best-first: source 1 kept, tail sources dropped.
        assertTrue(budgeted.userPrompt.contains("[Source 1: Note 0]"))
        assertTrue(budgeted.includedSources in 1 until sources.size)
    }

    @Test
    fun groundedPrompt_smallContextUnchanged() {
        val sources = listOf(
            PromptBudget.EvidenceSource("A", "alpha beta gamma", 0.9),
            PromptBudget.EvidenceSource("B", "delta epsilon", 0.5)
        )
        val budgeted = PromptBudget.buildGroundedUserPrompt(
            query = "q",
            sources = sources,
            systemPrompt = "sys"
        )
        assertEquals(2, budgeted.includedSources)
        assertEquals(0, budgeted.droppedSources)
        assertTrue(!budgeted.truncated)
        assertTrue(budgeted.userPrompt.contains("[Source 1: A]"))
        assertTrue(budgeted.userPrompt.contains("[Source 2: B]"))
    }

    @Test
    fun notePrompt_questionSurvivesLongNote() {
        val longNote = "word ".repeat(5000) // 25k chars
        val question = "Summarize the risks."
        val prompt = PromptBudget.buildNoteUserPrompt(
            noteContent = longNote,
            question = question,
            systemPrompt = "sys"
        )
        assertTrue(prompt.endsWith("Question: $question"))
        // Clean truncation without synthetic [Truncated...] tags polluting model context
        org.junit.Assert.assertFalse(prompt.contains("[Truncated"))
        assertTrue(prompt.contains("<note>"))
        assertTrue(prompt.contains("</note>"))
    }

    @Test
    fun reorderLostInTheMiddle_placesBestAtExtremes() {
        val items = listOf("1", "2", "3", "4", "5")
        val reordered = PromptBudget.reorderLostInTheMiddle(items)
        // Expected: [1, 3, 5, 4, 2] -> 1 is at start, 2 is at end
        assertEquals(listOf("1", "3", "5", "4", "2"), reordered)

        val pairs = listOf("A", "B")
        assertEquals(pairs, PromptBudget.reorderLostInTheMiddle(pairs))
    }

    @Test
    fun truncateAtSentenceBoundary_preservesCompleteSentence() {
        val text = "First sentence. Second sentence. Third sentence."
        // Slicing at 35 chars falls in the middle of "Third sentence"
        val truncated = PromptBudget.truncateAtSentenceBoundary(text, 35)
        assertEquals("First sentence. Second sentence.", truncated)
    }

    @Test
    fun groundedPrompt_includesConversationHistory() {
        val sources = listOf(
            PromptBudget.EvidenceSource("A", "Encryption uses AES-256.", 0.9)
        )
        val history = listOf(
            "user" to "What encryption is used?",
            "assistant" to "The database uses SQLCipher and AES-256."
        )
        val budgeted = PromptBudget.buildGroundedUserPrompt(
            query = "Where is the key stored?",
            sources = sources,
            systemPrompt = "sys",
            conversationHistory = history
        )
        assertTrue("Must include prior conversation", budgeted.userPrompt.contains("Prior conversation:"))
        assertTrue("Must include user turn", budgeted.userPrompt.contains("User: What encryption is used?"))
        assertTrue("Must include assistant turn", budgeted.userPrompt.contains("Assistant: The database uses SQLCipher"))
        assertTrue("Must preserve sources", budgeted.userPrompt.contains("[Source 1: A]"))
        assertTrue("Must end with question", budgeted.userPrompt.endsWith("Question: Where is the key stored?"))
    }
}
