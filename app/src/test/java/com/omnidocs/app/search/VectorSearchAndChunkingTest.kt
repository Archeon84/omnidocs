package com.omnidocs.app.search

import org.junit.Assert.*
import org.junit.Test

class VectorSearchAndChunkingTest {

    @Test
    fun testSanitizeFtsQuery_basicWords() {
        val query = "quarterly financial report"
        val fts = sanitizeFtsQueryForTest(query)
        assertEquals("quarterly* OR financial* OR report*", fts)
    }

    @Test
    fun testSanitizeFtsQuery_specialCharacters() {
        val query = "budget & expense (2026) -> #Q3"
        val fts = sanitizeFtsQueryForTest(query)
        assertEquals("budget* OR expense* OR 2026* OR Q3*", fts)
    }

    @Test
    fun testSanitizeFtsQuery_emptyOrWhitespace() {
        val query = "   ---  ***  "
        val fts = sanitizeFtsQueryForTest(query)
        assertEquals("", fts)
    }

    @Test
    fun testChunkText_shortTextSingleChunk() {
        val text = "This is a short note containing only a few words."
        val chunks = chunkTextForTest(text, chunkSizeWords = 50, overlapWords = 10)
        assertEquals(1, chunks.size)
        assertEquals(text, chunks[0])
    }

    @Test
    fun testChunkText_longTextMultipleChunksWithOverlap() {
        val words = (1..300).map { "word$it" }
        val text = words.joinToString(" ")

        val chunks = chunkTextForTest(text, chunkSizeWords = 100, overlapWords = 20)

        // 300 words with step 80:
        // Chunk 0: 0..100 (words 1..100)
        // Chunk 1: 80..180 (words 81..180)
        // Chunk 2: 160..260 (words 161..260)
        // Chunk 3: 240..300 (words 241..300)
        assertEquals(4, chunks.size)

        // Check overlap between Chunk 0 and Chunk 1
        assertTrue(chunks[0].contains("word81"))
        assertTrue(chunks[0].contains("word100"))
        assertTrue(chunks[1].contains("word81"))
        assertTrue(chunks[1].contains("word100"))
    }

    private fun sanitizeFtsQueryForTest(query: String): String {
        val tokens = query.split(Regex("[^\\p{Alnum}_]+"))
            .filter { it.isNotBlank() }
        if (tokens.isEmpty()) return ""
        return tokens.joinToString(" OR ") { "$it*" }
    }

    private fun chunkTextForTest(text: String, chunkSizeWords: Int = 180, overlapWords: Int = 35): List<String> {
        val words = text.split(Regex("\\s+")).filter { it.isNotBlank() }
        if (words.isEmpty()) return emptyList()
        if (words.size <= chunkSizeWords) return listOf(text.trim())

        val chunks = mutableListOf<String>()
        var start = 0
        val step = (chunkSizeWords - overlapWords).coerceAtLeast(1)

        while (start < words.size) {
            val end = (start + chunkSizeWords).coerceAtMost(words.size)
            val chunk = words.subList(start, end).joinToString(" ")
            if (chunk.isNotBlank()) {
                chunks.add(chunk)
            }
            if (end >= words.size) break
            start += step
        }
        return chunks
    }
}
