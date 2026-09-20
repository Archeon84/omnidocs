package com.omnidocs.app.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class StopStringFilterTest {

    @Test
    fun normalTextPassesThroughUnchanged() {
        val filter = StopStringFilter()
        val tokens = listOf("The ", "quick ", "brown ", "fox ", "jumps.")
        val out = StringBuilder()
        tokens.forEach { out.append(filter.feed(it)) }
        out.append(filter.flush())
        assertTrue(!filter.stopped)
        assertEquals("The quick brown fox jumps.", out.toString())
    }

    @Test
    fun stopSequenceSplitAcrossTokensIsStripped() {
        val filter = StopStringFilter()
        val out = StringBuilder()
        out.append(filter.feed("Here is the answer."))
        out.append(filter.feed("\n"))
        out.append(filter.feed("\nUser:"))
        assertTrue(filter.stopped)
        out.append(filter.flush())
        assertEquals("Here is the answer.", out.toString())
        // Post-stop tokens yield nothing.
        assertEquals("", filter.feed(" more text"))
    }

    @Test
    fun stopSpanningReleaseBoundaryKeepsPrefix() {
        // Force early release with a tiny hold-back, then a stop that starts
        // in released text and ends in pending text.
        val filter = StopStringFilter(listOf("abcXYZ"))
        val out = StringBuilder()
        out.append(filter.feed("0123456789abc")) // releases "0123456789" + holds "abc"
        assertTrue(!filter.stopped)
        out.append(filter.feed("XYZ")) // stop complete: keep pending prefix before it
        assertTrue(filter.stopped)
        out.append(filter.flush())
        // "abc" is the head of the stop sequence, so it is held back and then
        // consumed with the stop; only pre-stop text is emitted.
        assertEquals("0123456789", out.toString())
    }

    @Test
    fun imEndTagStops() {
        val filter = StopStringFilter()
        val out = StringBuilder()
        out.append(filter.feed("Answer done."))
        out.append(filter.feed("<|im_"))
        out.append(filter.feed("end|>"))
        assertTrue(filter.stopped)
        out.append(filter.flush())
        assertEquals("Answer done.", out.toString())
    }
}
