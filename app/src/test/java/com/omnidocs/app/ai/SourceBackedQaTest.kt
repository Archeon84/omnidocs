package com.omnidocs.app.ai

import org.junit.Assert.*
import org.junit.Test

class SourceBackedQaTest {

    @Test
    fun testSnippetExtraction_shortText() {
        val text = "Short note about Kotlin multiplatform and on-device inference."
        val query = "Kotlin"
        val snippet = extractSnippetForTest(text, query, 400)
        assertEquals(text, snippet)
    }

    @Test
    fun testSnippetExtraction_longTextLocatesQuery() {
        val prefix = "A".repeat(500)
        val target = "Important Budget Meeting with CEO"
        val suffix = "B".repeat(500)
        val text = "$prefix $target $suffix"

        val snippet = extractSnippetForTest(text, "Budget Meeting", 200)

        assertTrue("Snippet should contain the query match", snippet.contains("Budget Meeting"))
        assertTrue("Snippet should not exceed bounded length + ellipsis", snippet.length <= 220)
        assertTrue("Snippet from middle should have prefix indicator", snippet.startsWith("..."))
        assertTrue("Snippet from middle should have suffix indicator", snippet.endsWith("..."))
    }

    @Test
    fun testSnippetExtraction_noMatchDefaultsToStart() {
        val longText = "Beginning of document. " + "Content ".repeat(100)
        val snippet = extractSnippetForTest(longText, "NonExistentKeyword", 100)

        assertTrue("Snippet should start with beginning of text", snippet.startsWith("Beginning of document."))
        assertTrue("Snippet should be bounded", snippet.length <= 110)
        assertTrue("Snippet should have suffix ellipsis", snippet.endsWith("..."))
    }

    private fun extractSnippetForTest(text: String, query: String, maxChars: Int): String {
        val cleanText = text.replace(Regex("\\s+"), " ").trim()
        if (cleanText.length <= maxChars) return cleanText

        val queryTerms = query.split(Regex("\\W+")).filter { it.length > 2 }
        var bestIndex = 0

        for (term in queryTerms) {
            val index = cleanText.indexOf(term, ignoreCase = true)
            if (index >= 0) {
                bestIndex = (index - 40).coerceAtLeast(0)
                break
            }
        }

        val snippet = cleanText.substring(bestIndex, (bestIndex + maxChars).coerceAtMost(cleanText.length)).trim()
        val prefix = if (bestIndex > 0) "... " else ""
        val suffix = if (bestIndex + maxChars < cleanText.length) " ..." else ""
        return "$prefix$snippet$suffix"
    }
}
