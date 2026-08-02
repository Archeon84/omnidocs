package com.omnidocs.app.ai

/**
 * Post-processes raw model output to clean up artifacts
 * such as thinking tokens, excessive whitespace, etc.
 *
 * Qwen3 outputs <think>...</think> (note: closing tag has a slash).
 * The old code stripped <thinking>...</thinking> which never matched,
 * causing raw thinking content to leak into the UI.
 */
object AiOutputProcessor {

    /**
     * Clean up raw LLM output for display to the user.
     */
    fun process(output: String): String {
        if (output.isBlank()) return output.trim()

        var result = output.trim()

        // ── 1. Qwen3 primary thinking format ──────────────────────────────
        //    Qwen3 uses <think> (no slash) and </think> (with slash).
        result = stripBetween(result, "<think>", "</think>")

        // ── 2. Generic <thinking>...</thinking> (other model families) ─────
        result = stripBetween(result, "<thinking>", "</thinking>")

        // ── 3. Self-closing tags: <think>/> or <thinking/> ──────────────────────
        result = result.replace(Regex("<think(?:ing)?\\s*/>"), "").trim()

        // ── 4. Orphaned opening tags (no closing tag) ─────────────────────
        //    Discard everything from the orphaned tag onward.
        val thinkIdx = maxOf(result.indexOf("<think>"), result.indexOf("<thinking>"))
        if (thinkIdx > 0) {
            result = result.substring(0, thinkIdx).trim()
        } else if (thinkIdx == 0) {
            // Tag is at the very start with no answer after it — all thinking
            result = ""
        }

        // ── 5. Qwen3 xxx tracking markers ─────────────────────────────────
        result = stripBetween(result, "xxx\n", "\nxxx")

        // ── 6. Qwen3 bracket thinking markers ─────────────────────────────
        result = result.replace(Regex("(?s)【.*?】"), "").trim()

        // ── 7. Excessive blank lines ──────────────────────────────────────
        result = result.replace(Regex("\n{3,}"), "\n\n").trim()

        return result
    }

    /**
     * Strip text between start and end markers (inclusive).
     * Uses indexOf for reliability across all Kotlin platforms.
     *
     * If start is found but end is not, everything from start onward is removed.
     * If neither is found, the original text is returned unchanged.
     */
    private fun stripBetween(text: String, start: String, end: String): String {
        val startIdx = text.indexOf(start)
        if (startIdx < 0) return text
        val endIdx = text.indexOf(end, startIdx + start.length)
        if (endIdx < 0) return text.substring(0, startIdx).trim()
        return (text.substring(0, startIdx) + text.substring(endIdx + end.length)).trim()
    }
}
