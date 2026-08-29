package com.omnidocs.app.agent

import org.json.JSONArray
import org.json.JSONObject
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Agent responsible for cleaning text, normalizing formatting,
 * detecting language (English, Malay, Chinese, Japanese, Korean, mixed),
 * and segmenting content into traceable blocks with exact character offsets.
 */
@Singleton
class NormalizationAgent @Inject constructor() : Agent {

    override val id: String = "agent_normalization"

    override suspend fun execute(input: AgentInput, context: AgentContext): AgentResult {
        if (context.isCancelled()) {
            return AgentResult.PermanentFailure("Normalization cancelled by user")
        }

        val rawPlainText = input.payload["plainText"] ?: ""
        val rawHtml = input.payload["htmlContent"] ?: ""

        if (rawPlainText.isBlank() && rawHtml.isBlank()) {
            return AgentResult.PermanentFailure("No text content provided for normalization")
        }

        // 1. Clean and normalize whitespace
        val normalizedText = rawPlainText
            .replace("\r\n", "\n")
            .replace("\r", "\n")
            .replace(Regex("[ \\t]+"), " ")
            .trim()

        // 2. Language Detection
        val language = detectLanguage(normalizedText)

        // 3. Segment into structural blocks with offsets
        val paragraphs = normalizedText.split(Regex("\n{2,}"))
        val blocks = mutableListOf<Map<String, Any>>()
        var currentOffset = 0

        for ((index, para) in paragraphs.withIndex()) {
            val trimmed = para.trim()
            if (trimmed.isEmpty()) continue

            val startOffset = normalizedText.indexOf(trimmed, currentOffset).coerceAtLeast(currentOffset)
            val endOffset = startOffset + trimmed.length
            currentOffset = endOffset

            val blockType = when {
                trimmed.startsWith("#") || (trimmed.length < 80 && !trimmed.endsWith(".")) -> "heading"
                trimmed.startsWith("- ") || trimmed.startsWith("* ") || trimmed.matches(Regex("^\\d+\\..*")) -> "list_item"
                trimmed.contains("|") && trimmed.lines().size > 1 -> "table"
                else -> "paragraph"
            }

            blocks.add(
                mapOf(
                    "blockIndex" to index,
                    "blockType" to blockType,
                    "content" to trimmed,
                    "startOffset" to startOffset,
                    "endOffset" to endOffset
                )
            )
        }

        val blocksJsonArray = JSONArray()
        for (b in blocks) {
            val json = JSONObject()
            b.forEach { (k, v) -> json.put(k, v) }
            blocksJsonArray.put(json)
        }

        return AgentResult.Success(
            payload = mapOf(
                "language" to language,
                "blockCount" to blocks.size,
                "blocksJson" to blocksJsonArray.toString(),
                "normalizedText" to normalizedText,
                "normalizedHtml" to rawHtml
            )
        )
    }

    /**
     * Heuristic multi-language detector supporting English, Malay, Chinese, Japanese, and Korean.
     */
    private fun detectLanguage(text: String): String {
        if (text.isBlank()) return "en"

        var cjkCount = 0
        var hangulCount = 0
        var hiraganaKatakanaCount = 0

        for (char in text) {
            val code = char.code
            when {
                code in 0x4E00..0x9FFF -> cjkCount++
                code in 0xAC00..0xD7AF -> hangulCount++
                code in 0x3040..0x309F || code in 0x30A0..0x30FF -> hiraganaKatakanaCount++
            }
        }

        val totalLength = text.length
        if (hangulCount > totalLength * 0.1) return "ko"
        if (hiraganaKatakanaCount > totalLength * 0.05) return "ja"
        if (cjkCount > totalLength * 0.1) return "zh"

        // Malay / Indonesian keyword heuristic
        val lower = text.lowercase()
        val malayIndicators = listOf(" dan ", " yang ", " untuk ", " ini ", " itu ", " dengan ", " pada ", " adalah ", " tidak ", " dari ", " saya ")
        val malayMatches = malayIndicators.count { lower.contains(it) }

        return if (malayMatches >= 2) "ms" else "en"
    }
}
