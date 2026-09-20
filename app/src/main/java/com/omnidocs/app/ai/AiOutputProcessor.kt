package com.omnidocs.app.ai

/**
 * Post-processes raw model output to clean up artifacts
 * such as thinking tokens, internal reasoning blocks, excessive whitespace, etc.
 *
 * Handles Qwen (<think>...</think>), generic (<thinking>...</thinking>, <thought>, <reasoning>),
 * markdown-styled thinking headers, and unclosed thinking streams.
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
        "\n<|im_start|>",
        "\n<|start_header_id|>",
        "\n<|im_end|>",
        "\n<|eot_id|>"
    )

    /**
     * Simulated "Q:" or "Question:" follow-up turn. Deliberately stricter than role tag cutoffs:
     * a bare "\n\nQuestion:" or "\n\nQ:" also matches legitimate content (e.g. a "Question:"
     * line in quoted notes or FAQ), amputating the rest of the answer. Only cut
     * when the line reads like a simulated follow-up question (ends with "?") or transitions
     * into a simulated answer header.
     */
    private val SIMULATED_Q_TURN = Regex("(?i)\\n\\n(?:Q|Question):[^\\n]{0,200}(?:\\?|\\n+(?:\\*\\*|##)?(?:Answer|A):)")

    private val THINKING_BLOCK_REGEX = Regex("(?si)<(think|thinking|thought|reasoning)\\b[^>]*>.*?</\\1>")
    private val THINKING_SELF_CLOSING_REGEX = Regex("(?i)<(think|thinking|thought|reasoning)\\b[^>]*/>")
    private val THINKING_HEADER_REGEX = Regex("(?si)^[*_#\\[(]*(?:thinking|thought|reasoning)(?:\\s+process)?[:*_#\\])]*\\s*\\n.*?(?=\\n\\n(?:[*_#\\[(]*(?:answer|response|summary|conclusion|key|here)|[A-Z0-9]))")

    /**
     * Clean up raw LLM output for display to the user, stripping all thinking mode artifacts.
     */
    fun process(output: String): String {
        if (output.isBlank()) return output.trim()

        var result = output.trim()

        // ── 1. Strip complete XML/HTML style thinking blocks ───────────────
        result = result.replace(THINKING_BLOCK_REGEX, "").trim()

        // ── 2. Strip self-closing thinking tags ───────────────────────────
        result = result.replace(THINKING_SELF_CLOSING_REGEX, "").trim()

        // ── 3. Handle orphaned opening thinking tags ──────────────────────
        // If the model output begins with <think...> and hasn't closed it before cutoff:
        val orphanedOpenMatch = Regex("(?i)^<(think|thinking|thought|reasoning)\\b[^>]*>").find(result)
        if (orphanedOpenMatch != null) {
            val closeTag = "</${orphanedOpenMatch.groupValues[1]}>"
            val closeIdx = result.indexOf(closeTag, ignoreCase = true)
            if (closeIdx >= 0) {
                result = result.substring(closeIdx + closeTag.length).trim()
            } else {
                // If there is no closing tag, check if there is an explicit Answer transition:
                val answerTransition = Regex("(?i)\\n\\n+(?:(?:\\*\\*|##|###)?\\s*(?:Answer|Response|Conclusion|Key Takeaways?|Summary)[:\\*\\s]*\\n*|[A-Z])").find(result)
                if (answerTransition != null && answerTransition.range.first > 0) {
                    result = result.substring(answerTransition.range.first).trim()
                } else {
                    // Strip the opening tag so substantive generated content is retained
                    result = result.replaceFirst(Regex("(?i)^<(think|thinking|thought|reasoning)\\b[^>]*>"), "").trim()
                }
            }
        }

        // ── 4. Strip orphaned closing thinking tags anywhere ──────────────
        result = result.replace(Regex("(?i)</(think|thinking|thought|reasoning)>"), "").trim()

        // ── 5. Strip "Thinking Process:" markdown header blocks ────────────
        result = result.replace(THINKING_HEADER_REGEX, "").trim()

        // ── 6. Cut off simulated subsequent conversation turns ────────────
        for (cutoff in TURN_CUTOFFS) {
            val idx = result.indexOf(cutoff, ignoreCase = true)
            if (idx > 0) {
                result = result.substring(0, idx).trim()
            }
        }
        SIMULATED_Q_TURN.find(result)?.let {
            if (it.range.first > 0) result = result.substring(0, it.range.first).trim()
        }

        // ── 7. Qwen3 xxx tracking markers ─────────────────────────────────
        result = stripBetween(result, "xxx\n", "\nxxx")

        // ── 8. Qwen3 bracket thinking markers ─────────────────────────────
        result = result.replace(Regex("(?s)【.*?】"), "").trim()

        // ── 9. Strip markdown code fence wrappers if output is enclosed in ```
        if (result.startsWith("```") && result.endsWith("```")) {
            val lines = result.lines()
            if (lines.size >= 3) {
                result = lines.subList(1, lines.size - 1).joinToString("\n").trim()
            }
        }

        // ── 10. Strip conversational preambles and "Answer:" prefixes ─────
        for (regex in PREAMBLE_REGEXES) {
            result = result.replaceFirst(regex, "").trim()
        }

        // ── 11. Deduplicate exact repeated paragraphs / answer sections ───
        result = deduplicateRepeatedParagraphs(result)

        // ── 12. Excessive blank lines ─────────────────────────────────────
        result = result.replace(Regex("\n{3,}"), "\n\n").trim()

        return result
    }

    /**
     * Gracefully trims dangling trailing artifacts when generation is prematurely cut off
     * (e.g. by token exhaustion or context ceiling).
     *
     * Strips:
     * - Incomplete trailing citations (e.g. "[Source 1", "[Sources")
     * - Trailing empty markdown list markers (e.g. "\n- ", "\n* ", "\n12. ")
     * - Unclosed formatting like uneven markdown code fences
     */
    fun trimDanglingIncompleteText(text: String): String {
        if (text.isBlank()) return text
        var result = text.trimEnd()

        // 1. Strip dangling unclosed citation like "[Source" or "[Source 2" at the very end
        result = result.replace(Regex("""\[(?:Sources?|\d+)[^\]]*$""", RegexOption.IGNORE_CASE), "").trimEnd()

        // 2. Strip dangling empty list markers at the end, e.g. "\n1. ", "\n- ", "\n* "
        result = result.replace(Regex("""\n(?:\d+\.|\*|-)\s*$"""), "").trimEnd()

        // 3. Strip dangling cut-off punctuation or unclosed opening delimiters at the very end
        result = result.replace(Regex("""[,;:\-–—\(\[\{]\s*$"""), "").trimEnd()

        // 4. Balance unclosed code blocks if odd number of ```
        val codeFenceCount = Regex("""```""").findAll(result).count()
        if (codeFenceCount % 2 != 0) {
            result = "$result\n```"
        }

        return result
    }

    /**
     * Cleaner variant for full-text transforms (proofread/rewrite) where
     * content-preserving behavior matters more than aggressive chat cleanup.
     */
    fun processForTextTransform(output: String): String {
        if (output.isBlank()) return output.trim()

        var result = output.trim()
        result = result.replace(THINKING_BLOCK_REGEX, "").trim()
        result = result.replace(THINKING_SELF_CLOSING_REGEX, "").trim()
        result = result.replace(Regex("(?i)</(think|thinking|thought|reasoning)>"), "").trim()

        val orphanedOpenMatch = Regex("(?i)^<(think|thinking|thought|reasoning)\\b[^>]*>").find(result)
        if (orphanedOpenMatch != null) {
            result = result.replaceFirst(Regex("(?i)^<(think|thinking|thought|reasoning)\\b[^>]*>"), "").trim()
        }

        for (regex in PREAMBLE_REGEXES) {
            result = result.replaceFirst(regex, "").trim()
        }

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
