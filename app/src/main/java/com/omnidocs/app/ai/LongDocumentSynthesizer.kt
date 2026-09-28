package com.omnidocs.app.ai

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "LongDocSynthesizer"

/**
 * Hierarchical Map-Reduce synthesizer for long documents (5,000+ words).
 *
 * Prevents mobile memory exhaustion (LMK kills) and long prefill latency by
 * decomposing multi-thousand-word notes into structured semantic batches,
 * running local extractions/summaries, and reducing them into a cohesive result.
 */
@Singleton
class LongDocumentSynthesizer @Inject constructor(
    private val liteRtLmService: LiteRtLmService,
    private val noteBlockAdapter: NoteBlockAdapter,
    private val modelPreferences: ModelPreferences,
    private val modelDownloadManager: ModelDownloadManager
) {

    companion object {
        /** Threshold in estimated words where Map-Reduce triggers. */
        const val LONG_DOC_WORD_THRESHOLD = 1500

        /** Token budget per map-batch to ensure fast mobile prefill (<2s). */
        const val MAP_BATCH_TOKEN_BUDGET = 1200
    }

    /**
     * Summarize a long document (supports 5,000+ words) using Hierarchical Map-Reduce.
     */
    suspend fun summarizeDocument(
        title: String,
        content: String,
        onProgress: ((Float) -> Unit)? = null
    ): String? = withContext(Dispatchers.IO) {
        if (content.isBlank()) return@withContext null

        val wordCount = content.split(Regex("\\s+")).count { it.isNotBlank() }
        val model = resolveActiveModel(modelPreferences, modelDownloadManager)

        // Fast path for short notes (<1500 words)
        if (wordCount < LONG_DOC_WORD_THRESHOLD) {
            val userPrompt = "Document Title: $title\n\nContent:\n$content\n\nTask: Provide a concise, well-structured summary with key takeaways."
            val fullPrompt = PromptBuilder.buildPrompt(
                format = model?.promptFormat ?: PromptFormat.GEMMA,
                systemPrompt = "You are an expert document summarizer. Output a clear executive summary with bullet points.",
                userPrompt = userPrompt,
                model = model
            )
            onProgress?.invoke(0.5f)
            val result = liteRtLmService.generate(fullPrompt, maxTokens = 600)
            onProgress?.invoke(1.0f)
            return@withContext result
        }

        // Long document path (5,000+ words): Hierarchical Chunking
        Log.d(TAG, "Summarizing long document ($wordCount words) using Hierarchical Map-Reduce")
        val segments = noteBlockAdapter.chunkText(noteId = "doc", noteTitle = title, text = content)
        if (segments.isEmpty()) return@withContext null

        // 1. Group segments into batches of ~1200 tokens
        val batches = mutableListOf<List<SourceSegment>>()
        var currentBatch = mutableListOf<SourceSegment>()
        var currentTokens = 0

        for (segment in segments) {
            if (currentTokens + segment.tokenEstimate > MAP_BATCH_TOKEN_BUDGET && currentBatch.isNotEmpty()) {
                batches.add(currentBatch.toList())
                currentBatch.clear()
                currentTokens = 0
            }
            currentBatch.add(segment)
            currentTokens += segment.tokenEstimate
        }
        if (currentBatch.isNotEmpty()) {
            batches.add(currentBatch)
        }

        Log.d(TAG, "Split document into ${batches.size} batches for map phase")

        // 2. Map Phase: Extract intermediate section summaries
        val intermediateSummaries = mutableListOf<String>()
        for ((idx, batch) in batches.withIndex()) {
            val batchText = batch.joinToString("\n\n") { seg ->
                val header = seg.headerContext?.let { "[$it]\n" } ?: ""
                "$header${seg.content}"
            }

            val mapUserPrompt = "Section of '$title':\n$batchText\n\nTask: Extract key facts, data points, decisions, and takeaways from this section concisely."
            val mapPrompt = PromptBuilder.buildPrompt(
                format = model?.promptFormat ?: PromptFormat.GEMMA,
                systemPrompt = "You are an analytical assistant. Summarize section facts without filler.",
                userPrompt = mapUserPrompt,
                model = model
            )

            val sectionSummary = liteRtLmService.generate(mapPrompt, maxTokens = 350)
            if (!sectionSummary.isNullOrBlank()) {
                intermediateSummaries.add(sectionSummary.trim())
            }

            val progress = ((idx + 1).toFloat() / (batches.size + 1).toFloat())
            onProgress?.invoke(progress)
        }

        if (intermediateSummaries.isEmpty()) return@withContext null

        // 3. Reduce Phase: Synthesize into global executive summary
        val aggregatedContext = intermediateSummaries.mapIndexed { idx, s ->
            "### Part ${idx + 1}\n$s"
        }.joinToString("\n\n")

        val reduceUserPrompt = """Document: $title (Total ~${wordCount} words)
Key points extracted from across the entire document:
$aggregatedContext

Task: Synthesize these parts into a comprehensive, coherent executive summary of the entire document.
Structure:
- **Overview**: 2-3 sentences capturing the core premise and conclusion.
- **Key Findings & Decisions**: Bulleted list with bold key terms.
- **Action Items / Next Steps** (if applicable)."""

        val reducePrompt = PromptBuilder.buildPrompt(
            format = model?.promptFormat ?: PromptFormat.GEMMA,
            systemPrompt = "You are an executive document intelligence assistant. Provide a polished, well-structured synthesis.",
            userPrompt = reduceUserPrompt,
            model = model
        )

        onProgress?.invoke(0.95f)
        val finalSummary = liteRtLmService.generate(reducePrompt, maxTokens = 800)
        onProgress?.invoke(1.0f)
        finalSummary
    }

    /**
     * Extract action items across a long document (5,000+ words).
     */
    suspend fun extractActionItems(
        title: String,
        content: String
    ): String? = withContext(Dispatchers.IO) {
        if (content.isBlank()) return@withContext null

        val wordCount = content.split(Regex("\\s+")).count { it.isNotBlank() }
        val model = resolveActiveModel(modelPreferences, modelDownloadManager)

        val segments = noteBlockAdapter.chunkText(noteId = "doc", noteTitle = title, text = content)
        if (segments.isEmpty()) return@withContext null

        val batches = mutableListOf<List<SourceSegment>>()
        var currentBatch = mutableListOf<SourceSegment>()
        var currentTokens = 0

        for (segment in segments) {
            if (currentTokens + segment.tokenEstimate > MAP_BATCH_TOKEN_BUDGET && currentBatch.isNotEmpty()) {
                batches.add(currentBatch.toList())
                currentBatch.clear()
                currentTokens = 0
            }
            currentBatch.add(segment)
            currentTokens += segment.tokenEstimate
        }
        if (currentBatch.isNotEmpty()) batches.add(currentBatch)

        val extractedTasks = mutableListOf<String>()
        for (batch in batches) {
            val batchText = batch.joinToString("\n\n") { it.content }
            val prompt = PromptBuilder.buildPrompt(
                format = model?.promptFormat ?: PromptFormat.GEMMA,
                systemPrompt = "Extract any actionable tasks, deadlines, or assignees from the text. Return as bullet points. If none, output NONE.",
                userPrompt = batchText,
                model = model
            )
            val res = liteRtLmService.generate(prompt, maxTokens = 250)
            if (!res.isNullOrBlank() && !res.contains("NONE", ignoreCase = true)) {
                extractedTasks.add(res.trim())
            }
        }

        if (extractedTasks.isEmpty()) return@withContext null

        // Consolidate into deduplicated task list
        val consolidatePrompt = PromptBuilder.buildPrompt(
            format = model?.promptFormat ?: PromptFormat.GEMMA,
            systemPrompt = "Consolidate and deduplicate this list of extracted tasks into a clean checklist with Markdown checkboxes (- [ ]).",
            userPrompt = extractedTasks.joinToString("\n"),
            model = model
        )
        liteRtLmService.generate(consolidatePrompt, maxTokens = 500)
    }
}
