package com.omnidocs.app.ai

/**
 * Single choke-point for fitting RAG prompts into the model's context window
 * (n_ctx = 4096). The prompt budget is [MAX_PROMPT_TOKENS]; the remainder is
 * the generation reserve ([MAX_GENERATION_RESERVE]). Callers must additionally
 * cap their requested maxTokens to the room the prompt leaves
 * (see AnswerAgent.computeRequestedMaxTokens): the native loop breaks at the
 * context ceiling mid-sentence.
 *
 * Invariant: the QUESTION tail is never sacrificed for context. Evidence is
 * dropped (worst-first) or truncated so the question + instructions always
 * survive. This exists because the native layer truncates over-long prompts
 * by keeping the token PREFIX — which silently discards a trailing question.
 */
object PromptBudget {

    /** Must stay in sync with the native guard in llama_jni.cpp (n_ctx 8192):
     * the prompt may use at most 8192 - [MAX_GENERATION_RESERVE] tokens.
     * The reserve is the ANSWER's room to breathe; reserve enlarged to 1280
     * so grounded multi-citation answers have ample room to complete. */
    const val MAX_GENERATION_RESERVE = 1280

    const val MAX_PROMPT_TOKENS = 8192 - MAX_GENERATION_RESERVE

    /** Conservative chars-per-token estimate for English/markup-heavy text
     * (Markdown headers, XML <source> tags, ChatML wrappers, citations). */
    private const val CHARS_PER_TOKEN = 3

    /** Per-source ceiling so one huge source cannot crowd out the rest.
     * Raised to 2,400 chars (~400-500 words per source) to enable deep context
     * capture across multi-page/5,000-word documents. */
    const val MAX_SOURCE_CHARS = 2400

    /** Max sources forwarded to generation: prefill cost grows per source,
     * and the model cites only a handful. Worst-first dropping applies. */
    const val MAX_SOURCES = 8

    /** Fixed overhead estimate for template wrappers (ChatML/Phi-4 headers). */
    private const val TEMPLATE_OVERHEAD_TOKENS = 120

    fun estimateTokens(chars: Int): Int = (chars / CHARS_PER_TOKEN) + 1

    data class EvidenceSource(
        val title: String,
        val text: String,
        val score: Double = 0.0,
        val sourceIndex: Int = 0
    )

    data class BudgetedPrompt(
        val userPrompt: String,
        val includedSources: Int,
        val droppedSources: Int,
        val truncated: Boolean
    )

    /**
     * Truncate text at the nearest natural sentence boundary (. ! ? \n)
     * to avoid injecting broken sentence fragments into the context window.
     */
    fun truncateAtSentenceBoundary(text: String, maxChars: Int): String {
        if (text.length <= maxChars) return text
        val candidate = text.take(maxChars)
        val lastSentenceEnd = candidate.lastIndexOfAny(charArrayOf('.', '!', '?', '\n'))
        return if (lastSentenceEnd > maxChars / 2) {
            candidate.substring(0, lastSentenceEnd + 1).trim()
        } else {
            candidate.trim()
        }
    }

    /**
     * Reorders sources following the "Lost in the Middle" gold standard (Liu et al.):
     * places top-scoring candidates at the context window extremes (beginning and right
     * before the query) where attention and recall are strongest, placing lower-ranked
     * candidates in the middle.
     */
    fun <T> reorderLostInTheMiddle(items: List<T>): List<T> {
        if (items.size <= 2) return items
        val reordered = arrayOfNulls<Any?>(items.size)
        var left = 0
        var right = items.size - 1
        for ((idx, item) in items.withIndex()) {
            if (idx % 2 == 0) {
                reordered[left] = item
                left++
            } else {
                reordered[right] = item
                right--
            }
        }
        @Suppress("UNCHECKED_CAST")
        return reordered.filterNotNull() as List<T>
    }

    /**
     * Build a grounded "Sources: ... Question: ..." user prompt that is
     * guaranteed (by estimate) to fit [MAX_PROMPT_TOKENS] including the
     * system prompt. [sources] must be pre-ordered best-first; overflow is
     * dropped from the end, and an over-long single source is truncated at
     * natural sentence boundaries.
     *
     * Included sources are arranged using [reorderLostInTheMiddle] to prevent
     * attention dropout on the top evidence. Source text is wrapped in <source>
     * tags so retrieved note content is structurally separated from the question.
     */
    fun buildGroundedUserPrompt(
        query: String,
        sources: List<EvidenceSource>,
        systemPrompt: String,
        conversationHistory: List<Pair<String, String>> = emptyList()
    ): BudgetedPrompt {
        val historyStr = if (conversationHistory.isNotEmpty()) {
            val recent = conversationHistory.takeLast(2)
            val formatted = recent.joinToString("\n") { (role, text) ->
                "${if (role == "user") "User" else "Assistant"}: ${truncateAtSentenceBoundary(text, 400)}"
            }
            "Prior conversation:\n$formatted\n\n"
        } else ""

        val tail = "\n\nQuestion: $query"
        val overhead = estimateTokens(systemPrompt.length) +
            estimateTokens(historyStr.length) +
            estimateTokens("Sources:\n".length) +
            estimateTokens(tail.length) +
            TEMPLATE_OVERHEAD_TOKENS
        var remaining = MAX_PROMPT_TOKENS - overhead

        // 1. Budget and truncate sources in best-first priority order
        val selectedSources = mutableListOf<EvidenceSource>()
        var truncated = false

        for ((index, source) in sources.withIndex()) {
            if (selectedSources.size >= MAX_SOURCES) {
                truncated = true
                break
            }
            val effectiveIndex = if (source.sourceIndex > 0) source.sourceIndex else index + 1
            val label = "[Source $effectiveIndex: ${source.title}]\n<source>\n"
            val suffix = "\n</source>"
            val labelTokens = estimateTokens(label.length + suffix.length)
            if (remaining <= labelTokens + 1) {
                truncated = true
                break
            }
            var text = if (source.text.length > MAX_SOURCE_CHARS) {
                truncated = true
                truncateAtSentenceBoundary(source.text, MAX_SOURCE_CHARS)
            } else {
                source.text
            }
            var textTokens = estimateTokens(text.length)
            if (labelTokens + textTokens > remaining) {
                // Fit what we can of this source at sentence boundary, then stop.
                val allowedChars = (remaining - labelTokens) * CHARS_PER_TOKEN
                if (allowedChars <= 0) {
                    truncated = true
                    break
                }
                text = truncateAtSentenceBoundary(text, allowedChars)
                textTokens = estimateTokens(text.length)
                truncated = true
                selectedSources.add(source.copy(text = text, sourceIndex = effectiveIndex))
                remaining = 0
                break
            }
            selectedSources.add(source.copy(text = text, sourceIndex = effectiveIndex))
            remaining -= (labelTokens + textTokens)
        }
        if (selectedSources.size < sources.size) truncated = true

        // 2. Reorder included sources with Lost-in-the-Middle layout
        val reorderedSources = reorderLostInTheMiddle(selectedSources)

        // 3. Assemble prompt
        val kept = StringBuilder()
        if (historyStr.isNotEmpty()) {
            kept.append(historyStr)
        }
        kept.append("Sources:\n")
        for (source in reorderedSources) {
            val label = "[Source ${source.sourceIndex}: ${source.title}]\n<source>\n"
            val suffix = "\n</source>"
            kept.append(label).append(source.text).append(suffix).append("\n\n")
        }
        kept.append(tail)

        return BudgetedPrompt(
            userPrompt = kept.toString(),
            includedSources = selectedSources.size,
            droppedSources = sources.size - selectedSources.size,
            truncated = truncated
        )
    }

    /**
     * Build a single-note "Note content: ... Question: ..." user prompt.
     * The note is truncated from the end so the question always survives.
     * Note text is wrapped in <note> tags to separate it from instructions.
     *
     * Default cap (~6k chars ≈ 1.5k tokens) balances answer coverage
     * against on-device prefill time: a 12k-char context on a warm,
     * throttled phone prefills for minutes, and users stop the spinner
     * before the first token — surfacing as "no answer". The question tail
     * still always fits the [MAX_PROMPT_TOKENS] budget.
     */
    fun buildNoteUserPrompt(
        noteContent: String,
        question: String,
        systemPrompt: String,
        maxNoteChars: Int = 24000
    ): String {
        val tail = "\n\nQuestion: $question"
        val overhead = estimateTokens(systemPrompt.length) +
            estimateTokens("Note content:\n".length) +
            estimateTokens(tail.length) +
            TEMPLATE_OVERHEAD_TOKENS
        // Note prose averages ~3.8-4.0 chars/token; a 3.5 multiplier guarantees
        // ~24,000 characters (~5,000 words) comfortably fits the 6,912 token prompt budget.
        val allowedNoteChars = (((MAX_PROMPT_TOKENS - overhead) * 3.5f).toInt())
            .coerceAtLeast(0)
            .coerceAtMost(maxNoteChars)
        val note = if (noteContent.length > allowedNoteChars) {
            truncateAtSentenceBoundary(noteContent, allowedNoteChars)
        } else {
            noteContent
        }
        return "Note content:\n<note>\n$note\n</note>$tail"
    }
}
