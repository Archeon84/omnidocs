package com.omnidocs.app.email

import com.omnidocs.app.data.local.entity.ActionItemEntity
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class EmailDraftServiceTest {

    private lateinit var emailService: EmailDraftService

    @Before
    fun setUp() {
        emailService = EmailDraftService()
    }

    @Test
    fun testGenerateMeetingSummaryDraft_formatsStructuredSectionsProperly() {
        val actionItems = listOf(
            ActionItemEntity(
                id = "task_1",
                noteId = "note_123",
                title = "Finalize Q3 Budget Allocation",
                description = "Review departmental caps with finance team.",
                owner = "Alice Chen",
                dueAt = 1756540800000L,
                status = "pending",
                priority = "urgent",
                createdAt = 1756454400000L,
                updatedAt = 1756454400000L
            ),
            ActionItemEntity(
                id = "task_2",
                noteId = "note_123",
                title = "Prepare Migration Plan",
                description = "Outline SQLite to SQLCipher migration steps.",
                owner = "Bob Smith",
                dueAt = null,
                status = "in_progress",
                priority = "medium",
                createdAt = 1756454400000L,
                updatedAt = 1756454400000L
            )
        )

        val draft = emailService.generateMeetingSummaryDraft(
            noteTitle = "Sprint Planning Sync",
            summary = "The team discussed sprint priorities and finalized resource allocation for the upcoming quarter.",
            decisions = listOf(
                "Approved $50k cloud budget for H2",
                "Adopted RFC 5545 calendar export standard"
            ),
            actionItems = actionItems,
            openQuestions = listOf(
                "Who will lead the security penetration audit?",
                "Do we need multi-region replication by Q4?"
            ),
            meetingDateMs = 1756540800000L
        )

        assertNotNull(draft)
        assertTrue(draft.subject.contains("[Meeting Summary] Sprint Planning Sync"))

        // Body text checks
        val text = draft.bodyText
        assertTrue(text.contains("--- EXECUTIVE SUMMARY ---"))
        assertTrue(text.contains("The team discussed sprint priorities"))
        assertTrue(text.contains("--- KEY DECISIONS ---"))
        assertTrue(text.contains("• Approved $50k cloud budget for H2"))
        assertTrue(text.contains("• Adopted RFC 5545 calendar export standard"))
        assertTrue(text.contains("--- ACTION ITEMS & NEXT STEPS ---"))
        assertTrue(text.contains("1. Finalize Q3 Budget Allocation [URGENT] [Owner: Alice Chen]"))
        assertTrue(text.contains("2. Prepare Migration Plan [Owner: Bob Smith]"))
        assertTrue(text.contains("--- OPEN QUESTIONS ---"))
        assertTrue(text.contains("? Who will lead the security penetration audit?"))

        // Body HTML checks
        val html = draft.bodyHtml
        assertTrue(html.contains("<h3>Meeting Summary: Sprint Planning Sync</h3>"))
        assertTrue(html.contains("<h4>Key Decisions</h4>"))
        assertTrue(html.contains("<h4>Action Items</h4>"))
        assertTrue(html.contains("<strong>Finalize Q3 Budget Allocation</strong>"))
        assertTrue(html.contains("<span style='color:red;'>[URGENT]</span>"))
        assertTrue(html.contains("<strong>(Owner: Alice Chen)</strong>"))
    }
}
