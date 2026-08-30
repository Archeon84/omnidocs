package com.omnidocs.app.ai

import android.util.Log
import com.omnidocs.app.search.VectorSearch
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "SourceBackedQA"

// Strict token & character budgeting for on-device inference (n_ctx = 2048 ceiling)
private const val MAX_PASSAGE_CHARS = 400
private const val MAX_TOTAL_CONTEXT_CHARS = 2200
private const val MAX_OUTPUT_TOKENS = 400
private const val MAX_QUESTION_CHARS = 400

/**
 * Source-backed Q&A system. Retrieves relevant notes with hybrid (BM25 + semantic)
 * search, then uses LLM to answer questions with source links.
 */
@Singleton
class SourceBackedQA @Inject constructor(
    private val llamaCppService: LlamaCppService,
    private val modelDownloadManager: ModelDownloadManager,
    private val modelPreferences: ModelPreferences,
    private val vectorSearch: VectorSearch
) {
    data class QaResult(
        val answer: String,
        val sources: List<Source>,
        val confidence: Float
    )

    data class Source(
        val noteId: String,
        val noteTitle: String,
        val relevantText: String,
        val relevanceScore: Float
    )

    /**
     * Answer a question using notes as source context.
     */
    suspend fun answer(question: String, language: String = "en"): QaResult? {
        val model = resolveActiveModel(modelPreferences, modelDownloadManager) ?: return null
        val cleanQuestion = question.trim().take(MAX_QUESTION_CHARS)
        if (cleanQuestion.isBlank()) return null

        // Find relevant notes using hybrid (BM25 + semantic) search. Falls back
        // to BM25-only when no embedding model is downloaded.
        val searchResults = vectorSearch
            .hybridSearch(cleanQuestion, bm25Weight = 0.5f, semanticWeight = 0.5f, limit = 5)

        if (searchResults.isEmpty()) return null

        // Budget context passages to fit comfortably within on-device context window
        val budgetedSources = mutableListOf<Source>()
        val contextBuilder = StringBuilder()

        for (result in searchResults) {
            val note = result.note
            val snippet = extractSnippet(note.plainText, cleanQuestion, MAX_PASSAGE_CHARS)
            if (snippet.isBlank()) continue

            val formattedEntry = "[Source: ${note.title}]\n$snippet"
            if (contextBuilder.length + formattedEntry.length + 8 > MAX_TOTAL_CONTEXT_CHARS) {
                if (budgetedSources.isEmpty()) {
                    // Include at least a truncated portion of the top note
                    val remainingChars = (MAX_TOTAL_CONTEXT_CHARS - contextBuilder.length - 30).coerceAtLeast(100)
                    val truncatedSnippet = snippet.take(remainingChars)
                    contextBuilder.append("[Source: ${note.title}]\n$truncatedSnippet")
                    budgetedSources.add(
                        Source(
                            noteId = note.id,
                            noteTitle = note.title,
                            relevantText = truncatedSnippet.take(200),
                            relevanceScore = result.score
                        )
                    )
                }
                break
            }

            if (contextBuilder.isNotEmpty()) {
                contextBuilder.append("\n\n---\n\n")
            }
            contextBuilder.append(formattedEntry)
            budgetedSources.add(
                Source(
                    noteId = note.id,
                    noteTitle = note.title,
                    relevantText = snippet.take(200),
                    relevanceScore = result.score
                )
            )
        }

        if (budgetedSources.isEmpty()) return null

        val langInstruction = if (language.lowercase() != "en") "\nRespond in $language language." else ""
        val systemPrompt = """You are a knowledge assistant that answers questions based on the user's notes.

Rules:
- Answer ONLY based on the provided note sources
- If the notes don't contain enough information, say so directly
- Cite which note(s) you used for your answer (e.g. [Source: Title])
- If information conflicts between notes, mention both perspectives
- Be concise and direct$langInstruction"""

        val userPrompt = "Question: $cleanQuestion\n\nSources:\n$contextBuilder"

        return try {
            val prompt = PromptBuilder.buildPrompt(model.promptFormat, systemPrompt, userPrompt)
            val result = llamaCppService.generate(prompt, maxTokens = MAX_OUTPUT_TOKENS) ?: return null
            val answer = AiOutputProcessor.process(result).ifBlank { return null }

            QaResult(
                answer = answer,
                sources = budgetedSources,
                confidence = budgetedSources.firstOrNull()?.relevanceScore ?: 0f
            )
        } catch (e: Exception) {
            Log.e(TAG, "Q&A failed", e)
            null
        }
    }

    /**
     * Extract the most relevant snippet around query terms or the note beginning.
     */
    private fun extractSnippet(text: String, query: String, maxChars: Int): String {
        val cleanText = text.replace(Regex("\\s+"), " ").trim()
        if (cleanText.length <= maxChars) return cleanText

        val queryTerms = query.split(Regex("\\W+")).filter { it.length > 2 }
        var bestIndex = 0

        for (term in queryTerms) {
            val index = cleanText.indexOf(term, ignoreCase = true)
            if (index >= 0) {
                bestIndex = (index - 40).coerceAtLeast(0)
                break
            }
        }

        val snippet = cleanText.substring(bestIndex, (bestIndex + maxChars).coerceAtMost(cleanText.length)).trim()
        val prefix = if (bestIndex > 0) "... " else ""
        val suffix = if (bestIndex + maxChars < cleanText.length) " ..." else ""
        return "$prefix$snippet$suffix"
    }
}
