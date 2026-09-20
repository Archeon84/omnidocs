package com.omnidocs.app.search

import com.omnidocs.app.data.local.entity.NoteEntity
import com.omnidocs.app.data.repository.NotesRepository
import com.omnidocs.app.ui.screens.home.extractSearchSnippet
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class VectorSearchAndChunkingTest {

    @Test
    fun testSanitizeFtsQuery_basicWords() {
        val fts = VectorSearch.sanitizeFtsQuery("quarterly financial report")
        assertEquals("\"quarterly\"* OR \"financial\"* OR \"report\"*", fts)
    }

    @Test
    fun testSanitizeFtsQuery_specialCharacters() {
        val fts = VectorSearch.sanitizeFtsQuery("budget & expense (2026) -> #Q3")
        assertEquals("\"budget\"* OR \"expense\"* OR \"2026\"* OR \"Q3\"", fts)
    }

    @Test
    fun testSanitizeFtsQuery_emptyOrWhitespace() {
        assertEquals("", VectorSearch.sanitizeFtsQuery("   ---  ***  "))
    }

    @Test
    fun testSanitizeFtsQuery_stopwordsAndOperatorsStripped() {
        // "how do I fix" loses stopwords; AND/OR/NOT/NEAR never reach FTS.
        assertEquals("", VectorSearch.sanitizeFtsQuery("how do I"))
        val fts = VectorSearch.sanitizeFtsQuery("fix printer AND scanner OR near")
        assertEquals("\"fix\"* OR \"printer\"* OR \"scanner\"*", fts)
    }

    @Test
    fun testSanitizeFtsQuery_capsTermsAtFive() {
        val fts = VectorSearch.sanitizeFtsQuery("alpha beta gamma delta epsilon zeta eta")
        assertEquals(
            "\"alpha\"* OR \"beta\"* OR \"gamma\"* OR \"delta\"* OR \"epsilon\"*",
            fts
        )
    }

    @Test
    fun testBm25SaturationBoundsWeakHits() {
        // A lone weak BM25 hit scores on its full absolute scale (single-leg
        // support is not diluted by question-type weights): raw 0.3 clears it.
        val weak = 0.3f / (0.3f + VectorSearch.BM25_SATURATION_K)
        assertTrue(weak >= VectorSearch.MIN_COMBINED_SCORE)
        // Genuine multi-term hits score higher.
        val strong = 3.0f / (3.0f + VectorSearch.BM25_SATURATION_K)
        assertTrue(strong > weak)
        // Pure noise stays under the floor.
        val noise = 0.05f / (0.05f + VectorSearch.BM25_SATURATION_K)
        assertTrue(noise < VectorSearch.MIN_COMBINED_SCORE)
    }

    @Test
    fun testSanitizeFtsQuery_cjkSingleAndMultiCharacters() {
        val ftsSingle = VectorSearch.sanitizeFtsQuery("税")
        assertEquals("\"税\"", ftsSingle)

        val ftsMulti = VectorSearch.sanitizeFtsQuery("财务 报告")
        assertEquals("\"财务\" OR \"报告\"", ftsMulti)
    }

    @Test
    fun testSanitizeFtsQuery_shortTokensNoWildcard() {
        val ftsAiAgent = VectorSearch.sanitizeFtsQuery("ai agent")
        assertEquals("\"ai\" OR \"agent\"*", ftsAiAgent)

        val ftsDb = VectorSearch.sanitizeFtsQuery("db sql")
        assertEquals("\"db\" OR \"sql\"*", ftsDb)
    }

    @Test
    fun testExtractSearchSnippet_wholeWordMatchOnly() {
        // Text containing words with "ai" inside ("said", "daily", "failures", "availability")
        val textWithoutAi = "He said that daily failures and availability issues were being monitored."
        val snippetNoMatch = extractSearchSnippet(textWithoutAi, "ai agent", maxChars = 80)
        // Since "ai" is only inside "said"/"daily" (not a whole word), and "agent" is absent,
        // it must NOT treat "said" or "daily" as a match!
        val matchesSaid = com.omnidocs.app.ui.screens.home.findTokenWordMatches(textWithoutAi, "ai")
        assertTrue("No whole-word match for 'ai' in 'said/daily'", matchesSaid.isEmpty())

        // Text with actual whole-word "AI" and "agent"
        val textWithAi = "The team deployed an autonomous AI agent to production."
        val snippetMatch = extractSearchSnippet(textWithAi, "ai agent", maxChars = 80)
        assertTrue("Snippet must center around the actual match", snippetMatch.contains("AI agent") || snippetMatch.contains("agent"))
    }

    @Test
    fun testCanonicalChunkText_parityBetweenMarkdownAndPlain() {
        val htmlNote = NoteEntity(
            id = "n1",
            title = "Architecture",
            content = "<h2>Overview</h2><p>System architecture and design.</p>",
            plainText = "Overview\nSystem architecture and design.",
            isPinned = false,
            language = "en",
            createdAt = 1000L,
            updatedAt = 1000L,
            imageUrl = null
        )
        val canonical = NotesRepository.canonicalChunkText(htmlNote)
        assertTrue("Canonical chunk text should contain markdown heading", canonical.contains("## Overview"))
        assertTrue("Canonical chunk text should contain paragraph", canonical.contains("System architecture"))

        val plainNote = NoteEntity(
            id = "n2",
            title = "Plain",
            content = "Just plain text without tags",
            plainText = "Just plain text without tags",
            isPinned = false,
            language = "en",
            createdAt = 1000L,
            updatedAt = 1000L,
            imageUrl = null
        )
        assertEquals("Just plain text without tags", NotesRepository.canonicalChunkText(plainNote))
    }

    @Test
    fun testExtractSearchSnippet_centersAroundQuery() {
        val text = "Lorem ipsum dolor sit amet. Here is the critical secret key for database authentication. More text follows."
        val snippet = extractSearchSnippet(text, "secret key", maxChars = 80)
        assertTrue("Snippet should contain matched query terms", snippet.contains("secret key"))
        assertTrue("Snippet should be compact", snippet.length <= 100)
    }
}
