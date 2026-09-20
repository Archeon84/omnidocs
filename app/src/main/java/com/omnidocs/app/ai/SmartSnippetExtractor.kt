package com.omnidocs.app.ai

import com.omnidocs.app.search.Tokenizer
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Intelligent snippet extraction for RAG pipelines.
 *
 * Expands extraction windows dynamically around matching query terms:
 * - Detects Markdown lists (-, *, +, 1., numbered, checkboxes) and extracts the entire contiguous list block.
 * - Respects natural sentence and paragraph boundaries.
 * - Dynamically scales the character ceiling (1,000–1,500 chars) for comprehensive/list queries.
 */
@Singleton
class SmartSnippetExtractor @Inject constructor() {

    companion object {
        const val DEFAULT_EXCERPT_MAX_CHARS = 400
        const val EXPANDED_EXCERPT_MAX_CHARS = 1500

        private val LIST_MARKER_REGEX = Regex("""^\s*([-*+•]|\d+[.)]|\[[ xX]])\s+""")
        private val COMPREHENSIVE_QUERY_TERMS = listOf(
            "all", "list", "every", "what are the", "give me all", "give me every",
            "summarize all", "all of the", "bullet", "steps", "tasks", "dates",
            "items", "events", "requirements", "decisions", "points",
            // Malay
            "semua", "senarai", "senaraikan", "ringkasan", "ringkaskan",
            // Chinese
            "所有", "全部", "列出", "总结", "摘要"
        )
    }

    /**
     * Check if a query implies retrieving comprehensive or complete list information.
     */
    fun isComprehensiveQuery(query: String): Boolean {
        val lower = query.lowercase().trim()
        return COMPREHENSIVE_QUERY_TERMS.any { term ->
            if (term.contains(" ")) lower.contains(term)
            else Regex("""\b$term\b""").containsMatchIn(lower)
        }
    }

    /**
     * Extract the most relevant snippet from [text] given a [query].
     *
     * @param text Raw or markdown note text.
     * @param query User's search query.
     * @param maxCharsOverride Optional custom character ceiling. If null, dynamically
     *                         selected based on [isComprehensiveQuery].
     */
    fun extractSnippet(
        text: String,
        query: String,
        maxCharsOverride: Int? = null
    ): String {
        if (text.isBlank()) return ""
        val normalizedText = text.replace("\r\n", "\n")

        val maxChars = maxCharsOverride ?: if (isComprehensiveQuery(query)) {
            EXPANDED_EXCERPT_MAX_CHARS
        } else {
            DEFAULT_EXCERPT_MAX_CHARS
        }

        if (normalizedText.length <= maxChars) {
            // Even for short notes, check if any query term actually appears.
            // Word-boundary matching (same as block scoring) so "cat" does not
            // match "category" here while failing to match there. The text
            // side is folded: query terms came from Tokenizer.tokenize.
            val queryTerms = extractQueryTerms(query)
            if (queryTerms.isNotEmpty()) {
                val lowerText = Tokenizer.normalizeForMatch(normalizedText.lowercase())
                val hasMatch = queryTerms.any { Tokenizer.matchesToken(it, lowerText) }
                if (!hasMatch && maxCharsOverride == null && !isComprehensiveQuery(query)) {
                    // No literal overlap (paraphrase hit): still return the head
                    // rather than blank — the caller already judged relevance.
                    return truncateAtNaturalBoundary(normalizedText, maxChars, 0)
                }
            }
            return normalizedText.trim()
        }

        val lines = normalizedText.lines()
        val queryTerms = extractQueryTerms(query)

        // Find the best matching line index
        val targetLineIndex = findBestMatchingLine(lines, queryTerms)
        if (targetLineIndex < 0) {
            // No literal overlap: head-of-note fallback. The retrieval gate
            // already judged this note relevant (e.g. paraphrase/semantic).
            return truncateAtNaturalBoundary(normalizedText, maxChars, 0)
        }

        // Check if the target line is part of a list
        val targetLine = lines[targetLineIndex]
        if (isListLine(targetLine)) {
            val listSnippet = extractContiguousListBlock(lines, targetLineIndex, maxChars)
            if (listSnippet.isNotBlank()) {
                return listSnippet
            }
        }

        // Otherwise, extract sentence/paragraph bounded snippet around target
        return extractParagraphSnippet(lines, targetLineIndex, maxChars)
    }

    private fun isListLine(line: String): Boolean {
        return LIST_MARKER_REGEX.containsMatchIn(line)
    }

    private fun extractQueryTerms(query: String): List<String> {
        return Tokenizer.tokenize(query)
    }

    private fun findBestMatchingLine(lines: List<String>, queryTerms: List<String>): Int {
        if (queryTerms.isEmpty()) return -1
        var bestIndex = -1
        var maxMatches = 0

        for ((index, line) in lines.withIndex()) {
            val lowerLine = Tokenizer.normalizeForMatch(line.lowercase())
            var matches = 0
            for (term in queryTerms) {
                if (Tokenizer.matchesToken(term, lowerLine)) {
                    matches++
                }
            }
            if (matches > maxMatches) {
                maxMatches = matches
                bestIndex = index
            }
        }
        return if (bestIndex >= 0 && maxMatches > 0) bestIndex else -1
    }

    /**
     * Expands up and down from [startIndex] to capture the entire contiguous list block.
     */
    private fun extractContiguousListBlock(lines: List<String>, startIndex: Int, maxChars: Int): String {
        var start = startIndex
        var end = startIndex

        // Walk upwards to find start of list
        while (start > 0) {
            val prevLine = lines[start - 1]
            if (isListLine(prevLine) || (prevLine.startsWith("  ") && prevLine.isNotBlank())) {
                start--
            } else {
                break
            }
        }

        // Walk downwards to find end of list
        while (end < lines.size - 1) {
            val nextLine = lines[end + 1]
            if (isListLine(nextLine) || (nextLine.startsWith("  ") && nextLine.isNotBlank())) {
                end++
            } else {
                break
            }
        }

        val listLines = lines.subList(start, end + 1)
        val fullListText = listLines.joinToString("\n").trim()

        if (fullListText.length <= maxChars) {
            val prefix = if (start > 0) "... \n" else ""
            val suffix = if (end < lines.size - 1) "\n ..." else ""
            return "$prefix$fullListText$suffix"
        }

        // If list exceeds maxChars, include as many contiguous list items around startIndex as fit
        var currentSnippet = lines[startIndex]
        var up = startIndex - 1
        var down = startIndex + 1

        while ((up >= start || down <= end) && currentSnippet.length < maxChars) {
            if (down <= end) {
                val nextCandidate = currentSnippet + "\n" + lines[down]
                if (nextCandidate.length <= maxChars) {
                    currentSnippet = nextCandidate
                    down++
                } else break
            }
            if (up >= start) {
                val prevCandidate = lines[up] + "\n" + currentSnippet
                if (prevCandidate.length <= maxChars) {
                    currentSnippet = prevCandidate
                    up--
                } else break
            }
            if (down > end && up < start) break
        }

        val prefix = if (up >= 0) "... \n" else ""
        val suffix = if (down < lines.size) "\n ..." else ""
        return "$prefix$currentSnippet$suffix"
    }

    /**
     * Extracts surrounding text around [targetLineIndex] bounded by paragraphs and sentence limits.
     */
    private fun extractParagraphSnippet(lines: List<String>, targetLineIndex: Int, maxChars: Int): String {
        var start = targetLineIndex
        var end = targetLineIndex
        var currentText = lines[targetLineIndex]

        // Expand outwards line by line
        var up = targetLineIndex - 1
        var down = targetLineIndex + 1

        while (currentText.length < maxChars && (up >= 0 || down < lines.size)) {
            var expanded = false
            if (down < lines.size) {
                val next = lines[down]
                if (currentText.length + next.length + 1 <= maxChars) {
                    currentText = "$currentText\n$next"
                    down++
                    expanded = true
                }
            }
            if (up >= 0) {
                val prev = lines[up]
                if (currentText.length + prev.length + 1 <= maxChars) {
                    currentText = "$prev\n$currentText"
                    up--
                    expanded = true
                }
            }
            if (!expanded) break
        }

        val prefix = if (up >= 0) "... " else ""
        val suffix = if (down < lines.size) " ..." else ""
        return "$prefix${currentText.trim()}$suffix"
    }

    private fun truncateAtNaturalBoundary(text: String, maxChars: Int, offset: Int): String {
        val raw = text.drop(offset).take(maxChars)
        val lastSentenceEnd = raw.lastIndexOfAny(charArrayOf('.', '!', '?', '\n'))
        val clean = if (lastSentenceEnd > maxChars / 2) {
            raw.substring(0, lastSentenceEnd + 1).trim()
        } else {
            raw.trim()
        }
        val prefix = if (offset > 0) "... " else ""
        val suffix = if (offset + clean.length < text.length) " ..." else ""
        return "$prefix$clean$suffix"
    }
}
