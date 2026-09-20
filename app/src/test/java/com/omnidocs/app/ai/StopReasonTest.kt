package com.omnidocs.app.ai

import org.junit.Assert.*
import org.junit.Test

class StopReasonTest {

    @Test
    fun names_matchNativeCodes() {
        assertEquals("unknown", StopReason.name(0))
        assertEquals("max_tokens", StopReason.name(StopReason.MAX_TOKENS))
        assertEquals("ctx_full", StopReason.name(StopReason.CTX_FULL))
        assertEquals("eos", StopReason.name(StopReason.EOS))
        assertEquals("user_stop", StopReason.name(StopReason.USER))
        assertEquals("unknown", StopReason.name(999))
    }

    @Test
    fun cutoffFlags_midSentenceEndingsOnly() {
        assertTrue(StopReason.isCutOff(StopReason.MAX_TOKENS))
        assertTrue(StopReason.isCutOff(StopReason.CTX_FULL))
        assertTrue(StopReason.isCutOff(StopReason.TIMEOUT))
        assertTrue(StopReason.isCutOff(StopReason.DECODE_ERROR))
        assertTrue(StopReason.isCutOff(StopReason.EXCEPTION))
        // Natural finish, intentional halt, and unknown are NOT cutoffs:
        // flagging EOS would warn on every complete answer.
        assertFalse(StopReason.isCutOff(StopReason.EOS))
        assertFalse(StopReason.isCutOff(StopReason.USER))
        assertFalse(StopReason.isCutOff(StopReason.UNKNOWN))
    }
}
