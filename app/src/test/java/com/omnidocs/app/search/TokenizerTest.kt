package com.omnidocs.app.search

import org.junit.Assert.*
import org.junit.Test

class TokenizerTest {

    @Test
    fun tokenize_filtersStopWordsAndShortTokens() {
        val tokens = Tokenizer.tokenize("What is the project deadline?")
        assertTrue(tokens.contains("project"))
        assertTrue(tokens.contains("deadline"))
        assertFalse(tokens.contains("what"))
        assertFalse(tokens.contains("is"))
        assertFalse(tokens.contains("the"))
    }

    @Test
    fun tokenize_extractsCjkUnigramsAndBigrams() {
        val tokens = Tokenizer.tokenize("会议记录")
        assertTrue(tokens.contains("会"))
        assertTrue(tokens.contains("会议"))
        assertTrue(tokens.contains("议记"))
    }

    @Test
    fun isStopWord_matchesCaseInsensitively() {
        assertTrue(Tokenizer.isStopWord("The"))
        assertFalse(Tokenizer.isStopWord("project"))
    }

    @Test
    fun matchesToken_usesWordBoundariesForLatin() {
        assertTrue(Tokenizer.matchesToken("cat", "the cat sat"))
        assertFalse(Tokenizer.matchesToken("cat", "category theory"))
        assertTrue(Tokenizer.matchesToken("deadline", "project deadline extended"))
    }

    @Test
    fun matchesToken_usesSubstringForCjk() {
        assertTrue(Tokenizer.matchesToken("会议", "项目会议记录"))
        assertFalse(Tokenizer.matchesToken("会议", "项目记录"))
    }

    @Test
    fun stemTerm_foldsInflectionFamilies() {
        assertEquals("econom", Tokenizer.stemTerm("economic"))
        assertEquals("econom", Tokenizer.stemTerm("economy"))
        assertEquals("econom", Tokenizer.stemTerm("economics"))
        assertEquals("econom", Tokenizer.stemTerm("economical"))
        assertEquals("skateboard", Tokenizer.stemTerm("skateboarding"))
        assertEquals("note", Tokenizer.stemTerm("notes"))
        assertEquals("index", Tokenizer.stemTerm("indexed"))
        // Short words pass through untouched.
        assertEquals("day", Tokenizer.stemTerm("day"))
        assertEquals("bus", Tokenizer.stemTerm("bus"))
        assertEquals("class", Tokenizer.stemTerm("class"))
        assertEquals("project", Tokenizer.stemTerm("project"))
        assertEquals("deadline", Tokenizer.stemTerm("deadline"))
    }

    @Test
    fun tokenize_foldsQueryAndDocumentSymmetrically() {
        assertEquals(listOf("econom"), Tokenizer.tokenize("economic"))
        assertEquals(listOf("econom", "grow", "fast"), Tokenizer.tokenize("economy growing fast"))
        assertEquals(listOf("skateboard", "trick"), Tokenizer.tokenize("skateboarding tricks"))
    }

    @Test
    fun tokenize_preservesTwoLetterAcronymsAndFiltersStopWords() {
        val tokens = Tokenizer.tokenize("AI agent with ML and UI tools on an Android OS")
        assertTrue("Contains 'ai'", tokens.contains("ai"))
        assertTrue("Contains 'agent'", tokens.contains("agent"))
        assertTrue("Contains 'ml'", tokens.contains("ml"))
        assertTrue("Contains 'ui'", tokens.contains("ui"))
        assertTrue("Contains 'os'", tokens.contains("os"))
        assertFalse("Filters 2-letter stopword 'on'", tokens.contains("on"))
        assertFalse("Filters 2-letter stopword 'an'", tokens.contains("an"))
    }

    @Test
    fun foldedTerm_matchesInflectionInNormalizedText() {
        val text = Tokenizer.normalizeForMatch("the economy is growing fast")
        assertTrue(Tokenizer.matchesToken("econom", text))
        val boarding = Tokenizer.normalizeForMatch("he loves skateboarding daily")
        assertTrue(Tokenizer.matchesToken("skateboard", boarding))
    }
}
