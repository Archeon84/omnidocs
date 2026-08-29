package com.omnidocs.app.ai

import android.util.Log
import com.omnidocs.app.search.VectorSearch
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "SourceBackedQA"

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

        // Find relevant notes using hybrid (BM25 + semantic) search. Falls back
        // to BM25-only when no embedding model is downloaded.
        val relevantNotes = vectorSearch
            .hybridSearch(question, bm25Weight = 0.5f, semanticWeight = 0.5f, limit = 5)
            .map { it.note to it.score }
        if (relevantNotes.isEmpty()) return null

        // Build context from relevant notes
        val context = relevantNotes.joinToString("\n\n---\n\n") { (note, score) ->
            "[Source: ${note.title}]\n${note.plainText.take(1000)}"
        }

        val systemPrompt = """You are a knowledge assistant that answers questions based on the user's notes.

Rules:
- Answer ONLY based on the provided note sources
- If the notes don't contain enough information, say so
- Always cite which note(s) you used for your answer
- If information conflicts between notes, mention both perspectives
- Be concise and direct${if (language != "en") "\nRespond in $language language." else ""}"""

        val userPrompt = "Question: $question\n\nSources:\n$context"

        return try {
            val prompt = PromptBuilder.buildPrompt(model.promptFormat, systemPrompt, userPrompt)
            val result = llamaCppService.generate(prompt, maxTokens = 1000) ?: return null
            val answer = AiOutputProcessor.process(result) ?: return null

            QaResult(
                answer = answer,
                sources = relevantNotes.map { (note, score) ->
                    Source(
                        noteId = note.id,
                        noteTitle = note.title,
                        relevantText = note.plainText.take(200),
                        relevanceScore = score
                    )
                },
                confidence = relevantNotes.firstOrNull()?.second ?: 0f
            )
        } catch (e: Exception) {
            Log.e(TAG, "Q&A failed", e)
            null
        }
    }
}
