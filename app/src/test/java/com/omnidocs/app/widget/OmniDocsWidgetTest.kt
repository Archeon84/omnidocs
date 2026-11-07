package com.omnidocs.app.widget

import java.net.URI
import org.junit.Assert.*
import org.junit.Test

class OmniDocsWidgetTest {

    @Test
    fun testQuickActionUriPatterns() {
        val actions = listOf("new_note", "voice_capture", "ocr_scan", "ask", "study", "tasks")
        for (action in actions) {
            val uriString = "omnidocs://quick_action/$action"
            val uri = URI.create(uriString)
            assertEquals("omnidocs", uri.scheme)
            assertEquals("quick_action", uri.authority)
            assertEquals("/$action", uri.path)
            assertEquals(action, uri.path.removePrefix("/"))
        }
    }

    @Test
    fun testDirectNoteDeepLinkPattern() {
        val testNoteId = "note-uuid-550e8400-e29b-41d4-a716-446655440000"
        val uriString = "omnidocs://note/$testNoteId"
        val uri = URI.create(uriString)
        assertEquals("omnidocs", uri.scheme)
        assertEquals("note", uri.host)
        assertEquals("/$testNoteId", uri.path)
        assertEquals(testNoteId, uri.path.removePrefix("/"))
    }

    @Test
    fun testNoteSnippetTruncation() {
        val rawText = "   This is a multi-line note content.\n\nWith multiple   spaces and newlines.   "
        val snippet = rawText.trim().replace("\\s+".toRegex(), " ")
        assertEquals("This is a multi-line note content. With multiple spaces and newlines.", snippet)
        assertTrue(snippet.length <= 140)
    }
}
