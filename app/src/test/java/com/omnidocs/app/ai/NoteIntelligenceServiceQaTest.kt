package com.omnidocs.app.ai

import com.omnidocs.app.data.repository.NotesRepository
import com.omnidocs.app.ui.screens.ask.AskNotesViewModel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.Mockito.mock

class NoteIntelligenceServiceQaTest {

    private val llamaCppService = mock(LlamaCppService::class.java)
    private val modelDownloadManager = mock(ModelDownloadManager::class.java)
    private val modelPreferences = mock(ModelPreferences::class.java)
    private val repository = mock(NotesRepository::class.java)
    private val noteBlockAdapter = NoteBlockAdapter()

    private val service = NoteIntelligenceService(
        llamaCppService = llamaCppService,
        modelDownloadManager = modelDownloadManager,
        modelPreferences = modelPreferences,
        repository = repository,
        noteBlockAdapter = noteBlockAdapter
    )

    @Test
    fun testSelectRelevantContext_shortNotePreserved() {
        val shortNote = "This is a short note with less than 6000 characters."
        val result = service.selectRelevantContext(shortNote, "What is this?")
        assertEquals(shortNote, result)
    }

    @Test
    fun testSelectRelevantContext_5000WordNotePreservedWithoutTruncation() {
        // ~5,000 words / ~22,000 characters
        val wordBlock = "The quick brown fox jumps over the lazy dog. " // 46 chars, 9 words
        val noteContent = "## Start of Document\n\n" + wordBlock.repeat(450) + "\n\n## Conclusion\nImportant strategic insight: alpha beta gamma."
        assertTrue("Note should be around 5000 words / 20k+ chars", noteContent.length in 20000..24000)

        // Under 24k chars, the entire 5,000-word note must be preserved with 0 truncation!
        val selected = service.selectRelevantContext(noteContent, "What is the strategic insight?")
        assertEquals("Complete note should be preserved without truncation", noteContent, selected)
        assertTrue(selected.contains("alpha beta gamma"))
    }

    @Test
    fun testSelectRelevantContext_longNotePicksRelevantPassageAtEnd() {
        // Build a 30,000 character note (> 24k char limit) where the answer is at the very end
        val headPadding = "## Section 1: Overview\n" + "Introductory filler content.\n\n".repeat(300) // ~9000 chars
        val middlePadding = "## Section 2: Details\n" + "More general filler content.\n\n".repeat(500) // ~15000 chars
        val tailTarget = "## Section 3: Budget Secrets\nThe secret server passcode is 99887766."

        val longNote = "$headPadding\n\n$middlePadding\n\n$tailTarget"
        assertTrue("Note must exceed 24,000 characters", longNote.length > 24000)

        val selected = service.selectRelevantContext(longNote, "What is the secret server passcode?")
        // The passage at the end of the 30,000 char note MUST be extracted!
        assertTrue("Selected context should contain the tail target section", selected.contains("secret server passcode is 99887766"))
        assertFalse("Should not inject synthetic truncated string", selected.contains("[Truncated"))
    }

    @Test
    fun testGenerateRuleBasedNoteAnswer_matchesRelevantSentence() {
        val note = """
            Quarterly Planning 2026.
            The target launch date for Apollo is October 15.
            We will review budget allocations on Friday.
        """.trimIndent()

        val answer = service.generateRuleBasedNoteAnswer(note, "When is the launch date for Apollo?")
        assertTrue("Answer should contain quoted sentence", answer.contains("target launch date for Apollo is October 15"))
        assertTrue("Answer should indicate quoted directly", answer.contains("Quoted directly from note"))
    }

    @Test
    fun testDeriveEffectiveSearchQuery_expandsFollowUpQuestions() {
        val prevQuestion = "What were the quarterly earnings for Project Apollo?"
        val followUp = "When was it launched?"

        val effective = AskNotesViewModel.deriveEffectiveSearchQuery(followUp, prevQuestion)
        // Terms from previous question should be prepended
        assertTrue("Effective query should contain terms from previous question", effective.contains("quarterly") || effective.contains("apollo") || effective.contains("earnings"))
        assertTrue("Effective query should contain the follow-up text", effective.contains("When was it launched?"))

        // An independent standalone question should not be altered
        val independent = "How do I reset my account password?"
        val standalone = AskNotesViewModel.deriveEffectiveSearchQuery(independent, prevQuestion)
        assertEquals(independent, standalone)
    }
}
