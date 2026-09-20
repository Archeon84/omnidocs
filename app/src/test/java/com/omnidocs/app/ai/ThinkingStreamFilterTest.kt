package com.omnidocs.app.ai

import org.junit.Assert.*
import org.junit.Test

class ThinkingStreamFilterTest {

    private fun runThrough(tokens: List<String>): String {
        val filter = ThinkingStreamFilter()
        val out = StringBuilder()
        for (t in tokens) {
            out.append(filter.feed(t))
        }
        out.append(filter.flush())
        return out.toString()
    }

    @Test
    fun plainText_passthroughByteIdentical() {
        val text = "The meeting is on Monday at 10am in room 4."
        // Feed char-by-char to stress the holdback logic
        assertEquals(text, runThrough(text.map { it.toString() }))
    }

    @Test
    fun singleThinkBlock_droppedSilently() {
        val result = runThrough(
            listOf("<think>", "Let me consider...", " the options.", "</think>", "The answer is 42.")
        )
        assertEquals("The answer is 42.", result)
    }

    @Test
    fun splitTagsAcrossTokens_neverLeak() {
        val result = runThrough(
            listOf("Hel", "lo <th", "ink>", "secret reason", "ing</th", "ink>World")
        )
        assertEquals("Hello World", result)
    }

    @Test
    fun unclosedBlockAtCutoff_staysSuppressed() {
        val result = runThrough(
            listOf("Answer: ", "<think>hmm, let me see")
        )
        assertEquals("Answer: ", result)
    }

    @Test
    fun multipleBlocks_allDropped() {
        val result = runThrough(
            listOf("<think>a</think>", "First. ", "<thinking>b</thinking>", "Second.")
        )
        assertEquals("First. Second.", result)
    }

    @Test
    fun thinkOpen_recordsVisibleOffset() {
        val filter = ThinkingStreamFilter()
        filter.feed("Hello ")
        filter.feed("<think>")
        // "Hello " (6 chars) was visible before the block opened.
        assertEquals(6, filter.thinkOpenedAtVisibleChars)
    }

    @Test
    fun thinkOpenAtStreamStart_recordsZero() {
        val filter = ThinkingStreamFilter()
        filter.feed("<think>reasoning")
        assertEquals(0, filter.thinkOpenedAtVisibleChars)
    }

    @Test
    fun noThinkBlock_recordsMinusOne() {
        val filter = ThinkingStreamFilter()
        filter.feed("Just a plain answer. ")
        filter.flush()
        assertEquals(-1, filter.thinkOpenedAtVisibleChars)
    }

    @Test
    fun mixedCaseAndAttributes_handled() {
        val result = runThrough(
            listOf("<THINK lang=\"en\">x</Think>", "Done.")
        )
        assertEquals("Done.", result)
    }

    @Test
    fun angleBracketsWithoutTag_preserved() {
        assertEquals("a < b and c > d", runThrough(listOf("a < b and c > d")))
    }

    @Test
    fun emptyStream_flushEmpty() {
        val filter = ThinkingStreamFilter()
        assertEquals("", filter.flush())
    }

    @Test
    fun proseWithThoughtWordAndNoTerminator_preserved() {
        val text = "I <thought about it yesterday and decided to proceed with the plan."
        assertEquals(text, runThrough(listOf(text)))
    }

    @Test
    fun overlongUnclosedBlock_forceClosesAndKeepsAnswer() {
        val filter = ThinkingStreamFilter()
        val out = StringBuilder()
        out.append(filter.feed("<think>"))
        // 7000 chars of unclosed thinking, fed in chunks.
        var remaining = 7000
        while (remaining > 0) {
            val chunk = "x".repeat(minOf(500, remaining))
            remaining -= chunk.length
            out.append(filter.feed(chunk))
        }
        out.append(filter.feed("Answer!"))
        out.append(filter.flush())
        assertTrue(out.toString().endsWith("Answer!"))
    }
}
