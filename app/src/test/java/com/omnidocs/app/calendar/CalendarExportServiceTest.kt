package com.omnidocs.app.calendar

import com.omnidocs.app.data.local.entity.ActionItemEntity
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class CalendarExportServiceTest {

    private lateinit var calendarService: CalendarExportService

    @Before
    fun setUp() {
        calendarService = CalendarExportService()
    }

    @Test
    fun testExportToIcs_producesValidRfc5545CalendarStructure() {
        val items = listOf(
            ActionItemEntity(
                id = "action_101",
                noteId = "note_meeting",
                title = "Deliver Q3 Financial Audit",
                description = "Review all budget allocations with the finance committee.",
                owner = "Alice Chen",
                dueAt = 1756540800000L, // 2025-08-30T08:00:00Z
                status = "in_progress",
                priority = "urgent",
                createdAt = 1756454400000L,
                updatedAt = 1756454400000L
            ),
            ActionItemEntity(
                id = "action_102",
                noteId = "note_meeting",
                title = "Update Architecture Documentation",
                description = "Document Room migrations and SQLCipher KeyStore setup.",
                owner = "Bob Smith",
                dueAt = 1756627200000L,
                status = "completed",
                priority = "medium",
                createdAt = 1756454400000L,
                updatedAt = 1756540800000L
            )
        )

        val ics = calendarService.exportToIcs(items, "Project Alpha Tasks")

        assertNotNull(ics)
        assertTrue(ics.contains("BEGIN:VCALENDAR"))
        assertTrue(ics.contains("VERSION:2.0"))
        assertTrue(ics.contains("PRODID:-//OmniDocs//OmniDocs Knowledge Workspace//EN"))
        assertTrue(ics.contains("X-WR-CALNAME:Project Alpha Tasks"))

        // Item 1 verification
        assertTrue(ics.contains("BEGIN:VEVENT"))
        assertTrue(ics.contains("UID:action_101@omnidocs.app"))
        assertTrue(ics.contains("SUMMARY:Deliver Q3 Financial Audit"))
        assertTrue(ics.contains("PRIORITY:1")) // Urgent -> 1
        assertTrue(ics.contains("STATUS:IN-PROCESS"))
        assertTrue(ics.contains("Owner: Alice Chen"))
        assertTrue(ics.contains("BEGIN:VALARM"))
        assertTrue(ics.contains("TRIGGER:-PT15M"))

        // Item 2 verification
        assertTrue(ics.contains("UID:action_102@omnidocs.app"))
        assertTrue(ics.contains("SUMMARY:Update Architecture Documentation"))
        assertTrue(ics.contains("PRIORITY:5")) // Medium -> 5
        assertTrue(ics.contains("STATUS:COMPLETED"))

        assertTrue(ics.contains("END:VCALENDAR"))
    }

    @Test
    fun testExportToIcs_escapesSpecialCharactersProperly() {
        val items = listOf(
            ActionItemEntity(
                id = "action_special",
                noteId = "note_special",
                title = "Meeting: Discuss budget; check notes, diagrams",
                description = "Line 1\nLine 2 with \\ backslash, comma, and semicolon;",
                owner = "Dr. Strange, PhD",
                dueAt = 1756540800000L,
                status = "pending",
                priority = "high",
                createdAt = 1756454400000L,
                updatedAt = 1756454400000L
            )
        )

        val ics = calendarService.exportToIcs(items)

        // Verifies commas, semicolons, and newlines are RFC 5545 escaped
        assertTrue(ics.contains("SUMMARY:Meeting: Discuss budget\\; check notes\\, diagrams"))
        assertTrue(ics.contains("STATUS:NEEDS-ACTION"))
        assertTrue(ics.contains("PRIORITY:3")) // High -> 3
        assertTrue(ics.contains("Owner: Dr. Strange\\, PhD"))
        assertTrue(ics.contains("Line 1\\nLine 2 with \\\\ backslash\\, comma\\, and semicolon\\;"))
    }
}
