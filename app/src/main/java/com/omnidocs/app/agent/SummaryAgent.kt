package com.omnidocs.app.agent

import com.omnidocs.app.ai.LlamaCppService
import com.omnidocs.app.ai.LongDocumentSynthesizer
import com.omnidocs.app.ai.ModelDownloadManager
import com.omnidocs.app.ai.ModelPreferences
import com.omnidocs.app.ai.resolveActiveModel
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Agent responsible for generating structured summaries, key decisions,
 * and highlights from meetings, notes, and imported documents.
 * Seamlessly handles 5,000+ word notes via Hierarchical Map-Reduce.
 */
@Singleton
class SummaryAgent @Inject constructor(
    private val llamaCppService: LlamaCppService,
    private val modelPreferences: ModelPreferences,
    private val modelDownloadManager: ModelDownloadManager,
    private val longDocumentSynthesizer: LongDocumentSynthesizer? = null
) : Agent {

    override val id: String = "agent_summary"

    override suspend fun execute(input: AgentInput, context: AgentContext): AgentResult {
        if (context.isCancelled()) {
            return AgentResult.PermanentFailure("Summary generation cancelled by user")
        }

        val text = input.payload["text"] ?: ""
        val title = input.payload["title"] ?: "Meeting Notes"

        if (text.isBlank()) {
            return AgentResult.PermanentFailure("Text is blank for summary generation")
        }

        val summary = generateSummary(title, text)

        return AgentResult.Success(
            payload = mapOf(
                "summary" to summary,
                "length" to summary.length
            )
        )
    }

    suspend fun generateSummary(title: String, text: String): String {
        if (text.length >= 50) {
            val model = resolveActiveModel(modelPreferences, modelDownloadManager)
            if (model != null && longDocumentSynthesizer != null) {
                try {
                    val synthesized = longDocumentSynthesizer.summarizeDocument(title, text)
                    if (!synthesized.isNullOrBlank()) {
                        return synthesized.trim()
                    }
                } catch (e: Exception) {
                    // Fallback to extractive summary
                }
            }
        }
        return generateExtractiveSummary(text)
    }

    private fun generateExtractiveSummary(text: String): String {
        val lines = text.lines().map { it.trim() }.filter { it.length > 20 }
        val topLines = lines.take(5)
        return buildString {
            append("### Summary\n\n")
            topLines.forEach { append("- ").append(it).append("\n") }
        }
    }
}
