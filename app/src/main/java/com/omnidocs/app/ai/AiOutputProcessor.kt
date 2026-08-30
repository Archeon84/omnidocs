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

    private val PREAMBLE_REGEXES = listOf(
        // "Here is the proofread / corrected / rewritten / summary / answer version/text:"
        Regex("(?i)^here(?:'s|\\s+is)\\s+(?:the\\s+)?(?:proofread|corrected|rewritten|revised|summarized|summary|structured\\s+summary|answer|response)(?:\\s+(?:version|text))?[:\\s*]*\n*"),
        // "Sure! Here is the corrected text:" or "Certainly, here is the rewritten text:"
        Regex("(?i)^(?:sure|certainly|of\\s+course)[!.,\\s]+(?:here(?:'s|\\s+is)\\s+(?:the\\s+)?(?:proofread|corrected|rewritten|revised|summarized|summary|answer|response)(?:\\s+(?:version|text))?[:\\s*]*)?\n*"),
        // "Proofread version:" or "Corrected text:" or "Rewritten text:" or "Answer:" or "**Answer:**"
        Regex("(?i)^[*_#\\[(]*(?:proofread|corrected|rewritten|revised|answer|response)(?:\\s+(?:version|text))?[*_#\\])]*[:\\s*]*\n*")
    )

    private val TURN_CUTOFFS = listOf(
        "\n\nUser:",
        "\n\nHuman:",
        "\n\nQuestion:",
        "\n\nQ:",
        "\n<|im_start|>",
        "\n<|start_header_id|>",
        "\n<|im_end|>",
        "\n<|eot_id|>"
    )

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
        //    If the model started with <think> but didn't close it before token cutoff:
        val thinkIdx = when {
            result.contains("<think>") -> result.indexOf("<think>")
            result.contains("<thinking>") -> result.indexOf("<thinking>")
            else -> -1
        }

        if (thinkIdx >= 0) {
            val before = result.substring(0, thinkIdx).trim()
            if (before.isNotEmpty()) {
                result = before
            } else {
                // If the whole response started with <think> and didn't close,
                // strip only the opening tag so substantive generated content
                // is retained rather than wiped to an empty string.
                result = result.replaceFirst(Regex("^<think(?:ing)?>"), "").trim()
            }
        }

        // ── 4b. Strip orphaned closing tag if any ─────────────────────────
        result = result.replace(Regex("^</think(?:ing)?>"), "").trim()

        // ── 5. Cut off simulated subsequent conversation turns ────────────
        for (cutoff in TURN_CUTOFFS) {
            val idx = result.indexOf(cutoff, ignoreCase = true)
            if (idx > 0) {
                result = result.substring(0, idx).trim()
            }
        }

        // ── 6. Qwen3 xxx tracking markers ─────────────────────────────────
        result = stripBetween(result, "xxx\n", "\nxxx")

        // ── 7. Qwen3 bracket thinking markers ─────────────────────────────
        result = result.replace(Regex("(?s)【.*?】"), "").trim()

        // ── 8. Strip markdown code fence wrappers if output is enclosed in ```
        if (result.startsWith("```") && result.endsWith("```")) {
            val lines = result.lines()
            if (lines.size >= 3) {
                result = lines.subList(1, lines.size - 1).joinToString("\n").trim()
            }
        }

        // ── 9. Strip conversational preambles and "Answer:" prefixes ──────
        for (regex in PREAMBLE_REGEXES) {
            result = result.replaceFirst(regex, "").trim()
        }

        // ── 10. Deduplicate exact repeated paragraphs / answer sections ────
        result = deduplicateRepeatedParagraphs(result)

        // ── 11. Excessive blank lines ─────────────────────────────────────
        result = result.replace(Regex("\n{3,}"), "\n\n").trim()

        return result
    }

    /**
     * Deduplicate identical or duplicated paragraphs produced by small model self-repetition.
     */
    private fun deduplicateRepeatedParagraphs(text: String): String {
        val paragraphs = text.split("\n\n").map { it.trim() }.filter { it.isNotBlank() }
        if (paragraphs.size <= 1) return text

        val seen = mutableSetOf<String>()
        val unique = mutableListOf<String>()

        for (p in paragraphs) {
            val normalized = p.lowercase().replace(Regex("\\W+"), " ").trim()
            if (normalized !in seen) {
                seen.add(normalized)
                unique.add(p)
            }
        }

        return unique.joinToString("\n\n")
    }

    /**
     * Strip text between start and end markers (inclusive).
     * Only strips if BOTH start and end markers are present.
     */
    private fun stripBetween(text: String, start: String, end: String): String {
        val startIdx = text.indexOf(start)
        if (startIdx < 0) return text
        val endIdx = text.indexOf(end, startIdx + start.length)
        if (endIdx < 0) return text
        return (text.substring(0, startIdx) + text.substring(endIdx + end.length)).trim()
    }
}
