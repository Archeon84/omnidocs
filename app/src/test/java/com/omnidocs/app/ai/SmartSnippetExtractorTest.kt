package com.omnidocs.app.ai

import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class SmartSnippetExtractorTest {

    private lateinit var extractor: SmartSnippetExtractor

    @Before
    fun setUp() {
        extractor = SmartSnippetExtractor()
    }

    @Test
    fun testIsComprehensiveQuery_detectsListKeywords() {
        assertTrue(extractor.isComprehensiveQuery("list all action items"))
        assertTrue(extractor.isComprehensiveQuery("what are all the dates?"))
        assertTrue(extractor.isComprehensiveQuery("give me every decision made"))
        assertTrue(extractor.isComprehensiveQuery("summarize all requirements"))
        assertTrue(extractor.isComprehensiveQuery("what are the tasks?"))
        assertTrue(extractor.isComprehensiveQuery("show me the steps"))

        assertFalse(extractor.isComprehensiveQuery("who was the speaker?"))
        assertFalse(extractor.isComprehensiveQuery("when was John hired?"))
    }

    @Test
    fun testExtractSnippet_expandsContiguousListBlock() {
        val markdownList = buildString {
            append("Meeting notes from Monday:\n\n")
            append("Here is the list of key milestones:\n")
            append("- 2026-01-15: Initial project kickoff and architecture design\n")
            append("- 2026-02-01: Alpha version prototype completed\n")
            append("- 2026-03-15: Beta testing with pilot enterprise customers\n")
            append("- 2026-04-30: Security audit and penetration testing completed\n")
            append("- 2026-05-15: Production deployment and rollout\n\n")
            append("Conclusion and next steps for the engineering team.")
        }

        val snippet = extractor.extractSnippet(
            text = markdownList,
            query = "Beta testing",
            maxCharsOverride = 1000
        )

        // Verifies the entire list block is extracted rather than cutting off at single sentence / 300 chars
        assertTrue(snippet.contains("- 2026-01-15: Initial project kickoff"))
        assertTrue(snippet.contains("- 2026-02-01: Alpha version"))
        assertTrue(snippet.contains("- 2026-03-15: Beta testing"))
        assertTrue(snippet.contains("- 2026-04-30: Security audit"))
        assertTrue(snippet.contains("- 2026-05-15: Production deployment"))
    }

    @Test
    fun testExtractSnippet_numberedListsAndCheckboxes() {
        val numberedTasks = buildString {
            append("Sprint Backlog:\n")
            append("1. Fix SQLCipher database migration issue\n")
            append("2. Implement SmartSnippetExtractor with dynamic ceiling\n")
            append("3. Upgrade NoteBlockAdapter with semantic chunking\n")
            append("4. Implement PromptAssembler with full-note retrieval\n")
            append("5. Run evaluation benchmark suite\n")
        }

        val snippet = extractor.extractSnippet(
            text = numberedTasks,
            query = "SmartSnippetExtractor",
            maxCharsOverride = 1000
        )

        assertTrue(snippet.contains("1. Fix SQLCipher"))
        assertTrue(snippet.contains("2. Implement SmartSnippetExtractor"))
        assertTrue(snippet.contains("3. Upgrade NoteBlockAdapter"))
        assertTrue(snippet.contains("4. Implement PromptAssembler"))
        assertTrue(snippet.contains("5. Run evaluation benchmark"))
    }

    @Test
    fun testExtractSnippet_comprehensiveQueryScalesCeiling() {
        val longContent = (1..30).joinToString("\n") { "- Item $it: Detailed specification description for feature item $it" }
        val query = "list all items"

        val snippet = extractor.extractSnippet(longContent, query)

        // Comprehensive queries automatically bump maxChars to 1500 chars instead of standard 400
        assertTrue("Snippet should be expanded for comprehensive queries", snippet.length > 500)
        assertTrue(snippet.contains("- Item 1:"))
        assertTrue(snippet.contains("- Item 15:"))
    }

    @Test
    fun testExtractSnippet_proseNaturalParagraphBoundary() {
        val prose = "Paragraph one with context.\n\n" +
            "Paragraph two contains the primary target keyword about artificial intelligence and on-device models.\n\n" +
            "Paragraph three with concluding remarks."

        val snippet = extractor.extractSnippet(prose, "artificial intelligence", 300)

        assertTrue(snippet.contains("artificial intelligence and on-device models"))
    }

    @Test
    fun testExtractSnippet_noMatchReturnsHeadFallback() {
        val unrelatedNote = "This note is about cooking recipes and kitchen equipment.\n\n" +
            "Pasta sauce needs tomatoes, garlic, and basil."

        val snippet = extractor.extractSnippet(unrelatedNote, "quantum physics simulation")

        // The retrieval gate already judged relevance (e.g. paraphrase hit):
        // return the head rather than dropping the candidate.
        assertTrue("Snippet should fall back to head-of-note", snippet.isNotBlank())
        assertTrue(snippet.contains("cooking recipes"))
    }

    @Test
    fun testExtractSnippet_noMatchWithComprehensiveQueryReturnsHead() {
        val longNote = (1..20).joinToString("\n\n") { "Section $it: Some unrelated content about topic $it." }

        val snippet = extractor.extractSnippet(longNote, "list all items", 1000)

        assertTrue("Comprehensive query should return head-of-note fallback", snippet.isNotBlank())
    }
}
