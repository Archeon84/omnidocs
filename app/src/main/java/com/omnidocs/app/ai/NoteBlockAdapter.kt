package com.omnidocs.app.ai

import com.omnidocs.app.domain.model.Note
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/**
 * A semantic segment extracted from a note for RAG indexing and retrieval.
 */
data class SourceSegment(
    val segmentId: String = UUID.randomUUID().toString(),
    val noteId: String,
    val noteTitle: String,
    val content: String,
    val startOffset: Int,
    val endOffset: Int,
    val tokenEstimate: Int,
    val isListBlock: Boolean = false,
    val headerContext: String? = null
)

/**
 * Semantic chunking adapter for notes.
 *
 * Upgrades chunking from naive double-newline splitting to semantic Markdown-aware chunking:
 * - Respects Markdown headers (#, ##, ###) as semantic topic boundaries.
 * - Merges short consecutive paragraphs and contiguous list items into unified [SourceSegment]s
 *   up to [MAX_CHUNK_TOKENS] (default 512 tokens / ~2,000 chars).
 * - Prevents list items separated by \n\n (e.g. 40 dates) from being fragmented into isolated pieces.
 */
@Singleton
class NoteBlockAdapter @Inject constructor() {

    companion object {
        const val MAX_CHUNK_TOKENS = 512
        const val CHARS_PER_TOKEN_ESTIMATE = 4 // ~4 characters per token heuristic
        const val MAX_CHUNK_CHARS = MAX_CHUNK_TOKENS * CHARS_PER_TOKEN_ESTIMATE // 2048 chars

        /**
         * Overlap carried across SIZE-driven chunk splits so a topic line at
         * the boundary is embedded with context on both sides. Header splits
         * stay hard boundaries (no carry): a new header is a new topic.
         */
        const val CHUNK_OVERLAP_CHARS = 256

        private val HEADER_REGEX = Regex("""^(#{1,6})\s+(.+)$""")
        private val LIST_ITEM_REGEX = Regex("""^\s*([-*+•]|\d+[.)]|\[[ xX]])\s+""")
    }

    /**
     * Chunk a note into semantic [SourceSegment]s.
     */
    fun chunkNote(note: Note): List<SourceSegment> {
        val text = note.plainText.ifBlank { note.content }
        return chunkText(note.id, note.title, text)
    }

    /**
     * Chunk raw text into semantic [SourceSegment]s.
     */
    fun chunkText(noteId: String, noteTitle: String, text: String): List<SourceSegment> {
        if (text.isBlank()) return emptyList()

        val normalizedText = text.replace("\r\n", "\n")
        val lines = normalizedText.lines()
        val segments = mutableListOf<SourceSegment>()

        var currentHeader: String? = null
        val headerStack = mutableListOf<Pair<Int, String>>()
        val currentBlockLines = mutableListOf<String>()
        var blockStartOffset = 0
        var currentOffset = 0
        var listLineCount = 0
        var totalLineCount = 0
        // Overlap tail waiting to seed the next block after a size split.
        var pendingOverlap = ""
        var pendingOverlapOffset = 0

        fun seedOverlapIfAny() {
            if (pendingOverlap.isEmpty()) return
            blockStartOffset = pendingOverlapOffset
            currentBlockLines.add(pendingOverlap)
            totalLineCount++
            pendingOverlap = ""
        }

        fun flushCurrentBlock(carryOverlap: Boolean = false) {
            if (currentBlockLines.isEmpty()) return
            val blockContent = currentBlockLines.joinToString("\n").trim()
            if (blockContent.isNotBlank()) {
                // End offset from the source cursor, not the trimmed buffer
                // length (trimming/blank-collapsing would otherwise drift it).
                val blockEndOffset = currentOffset.coerceAtMost(normalizedText.length)
                val tokenEst = estimateTokens(blockContent)
                segments.add(
                    SourceSegment(
                        noteId = noteId,
                        noteTitle = noteTitle,
                        content = blockContent,
                        startOffset = blockStartOffset,
                        endOffset = blockEndOffset,
                        tokenEstimate = tokenEst,
                        isListBlock = listLineCount * 2 >= totalLineCount.coerceAtLeast(1),
                        headerContext = currentHeader
                    )
                )
                if (carryOverlap && blockContent.length > CHUNK_OVERLAP_CHARS) {
                    // Tail becomes the next chunk's head: content and offsets
                    // stay consistent (overlap starts at its true offset).
                    pendingOverlap = blockContent.takeLast(CHUNK_OVERLAP_CHARS)
                    pendingOverlapOffset = blockEndOffset - pendingOverlap.length
                }
            }
            currentBlockLines.clear()
            listLineCount = 0
            totalLineCount = 0
        }

        var inCodeFence = false

        for (line in lines) {
            val lineLength = line.length + 1 // +1 for the newline
            val trimmedLine = line.trim()

            val isFenceLine = trimmedLine.startsWith("```") || trimmedLine.startsWith("~~~")
            // Track Markdown table row
            val isTableRow = trimmedLine.startsWith("|") && trimmedLine.endsWith("|") && trimmedLine.length > 2

            // Check for Markdown Header (only outside code fences)
            val headerMatch = if (!inCodeFence && !isFenceLine) HEADER_REGEX.find(trimmedLine) else null
            if (headerMatch != null) {
                // Header is a hard topic boundary: flush existing block first.
                // The header line itself opens the next chunk so header-only
                // keywords remain embedded and searchable.
                flushCurrentBlock()
                val level = headerMatch.groupValues[1].length
                val headerText = headerMatch.groupValues[2].trim()
                while (headerStack.isNotEmpty() && headerStack.last().first >= level) {
                    headerStack.removeAt(headerStack.lastIndex)
                }
                headerStack.add(level to headerText)
                currentHeader = headerStack.joinToString(" > ") { it.second }
                blockStartOffset = currentOffset
                currentBlockLines.add(line)
                totalLineCount++
                currentOffset += lineLength
                continue
            }

            val isLineList = LIST_ITEM_REGEX.containsMatchIn(trimmedLine)

            // If empty line, we check if we should keep merging or flush
            if (trimmedLine.isEmpty()) {
                // Peek ahead or treat double newline as soft break unless chunk is already full
                val currentLength = currentBlockLines.sumOf { it.length + 1 }
                if (currentLength >= MAX_CHUNK_CHARS && !inCodeFence) {
                    flushCurrentBlock(carryOverlap = true)
                    blockStartOffset = currentOffset + lineLength
                    seedOverlapIfAny()
                } else if (currentBlockLines.isNotEmpty()) {
                    currentBlockLines.add("")
                }
                currentOffset += lineLength
                continue
            }

            if (currentBlockLines.isEmpty()) {
                blockStartOffset = currentOffset
            }

            val projectedLength = currentBlockLines.sumOf { it.length + 1 } + line.length
            // Protect code blocks and tables from premature mid-block splits
            val isProtectedBlock = (inCodeFence || isFenceLine || isTableRow) && projectedLength < (MAX_CHUNK_CHARS * 2)
            if (projectedLength > MAX_CHUNK_CHARS && !isProtectedBlock) {
                // Current chunk is full: flush and start new chunk
                flushCurrentBlock(carryOverlap = true)
                blockStartOffset = currentOffset
                seedOverlapIfAny()
            }

            if (isFenceLine) {
                inCodeFence = !inCodeFence
            }

            if (isLineList) listLineCount++
            totalLineCount++
            currentBlockLines.add(line)
            currentOffset += lineLength
        }

        flushCurrentBlock()
        return segments
    }

    /**
     * Parent-Child Context Expansion:
     * Expands a child passage segment to include surrounding paragraph and sentence context
     * from the parent document, restoring complete narrative coherence without cutting
     * mid-paragraph.
     */
    fun expandToParentContext(
        segment: SourceSegment,
        fullText: String,
        maxChars: Int = 2400
    ): String {
        if (fullText.isBlank() || segment.content.length >= maxChars) {
            return segment.content
        }

        val start = segment.startOffset.coerceIn(0, fullText.length)
        val end = segment.endOffset.coerceIn(start, fullText.length)

        // Expand backward to previous paragraph boundary or double-newline
        val lookbackLimit = (start - 400).coerceAtLeast(0)
        val prevParagraph = fullText.lastIndexOf("\n\n", start - 1)
        val expandedStart = if (prevParagraph in lookbackLimit until start) {
            prevParagraph + 2
        } else {
            val prevSentence = fullText.lastIndexOfAny(charArrayOf('.', '!', '?', '\n'), start - 1)
            if (prevSentence in lookbackLimit until start) prevSentence + 1 else start
        }

        // Expand forward to next paragraph boundary or sentence end
        val lookaheadLimit = (end + 400).coerceAtMost(fullText.length)
        val nextParagraph = fullText.indexOf("\n\n", end)
        val expandedEnd = if (nextParagraph in end..lookaheadLimit) {
            nextParagraph
        } else {
            val nextSentence = fullText.indexOfAny(charArrayOf('.', '!', '?', '\n'), end)
            if (nextSentence in end..lookaheadLimit) nextSentence + 1 else end
        }

        val expanded = fullText.substring(expandedStart, expandedEnd).trim()
        return if (expanded.length in segment.content.length..maxChars) {
            expanded
        } else {
            segment.content
        }
    }

    /**
     * Estimate token count for a text snippet using on-device ~4 chars/token heuristic.
     */
    fun estimateTokens(text: String): Int {
        if (text.isBlank()) return 0
        return (text.length / CHARS_PER_TOKEN_ESTIMATE).coerceAtLeast(1)
    }
}
