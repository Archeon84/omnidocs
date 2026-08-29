package com.omnidocs.app.search

import android.util.Log
import com.omnidocs.app.data.local.EmbeddingDao
import com.omnidocs.app.data.local.NoteDao
import com.omnidocs.app.data.local.entity.NoteEntity
import com.omnidocs.app.graph.Bm25Scorer
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Performs hybrid search combining BM25 full-text search with vector similarity.
 * Falls back to BM25-only if no embeddings are available.
 */
@Singleton
class VectorSearch @Inject constructor(
    private val embeddingDao: EmbeddingDao,
    private val noteDao: NoteDao,
    private val embeddingService: EmbeddingService
) {
    // Guards lazy first-search reindexing so concurrent searches don't duplicate it.
    private val reindexing = AtomicBoolean(false)
    /**
     * Hybrid search: combine BM25 and vector similarity results.
     *
     * @param query the search query
     * @param bm25Weight weight for BM25 results (0.0-1.0)
     * @param semanticWeight weight for semantic results (0.0-1.0)
     * @param limit max results
     */
    suspend fun hybridSearch(
        query: String,
        bm25Weight: Float = 0.6f,
        semanticWeight: Float = 0.4f,
        limit: Int = 20
    ): List<SearchResult> {
        if (query.isBlank()) return emptyList()

        val allNotes = noteDao.getAllNotesSync()
        if (allNotes.isEmpty()) return emptyList()

        // --- BM25 scoring over the full (non-deleted) note corpus ---
        val tokenizedDocs = allNotes.map { Bm25Scorer.tokenize("${it.title} ${it.plainText}") }
        val idf = Bm25Scorer.computeIdf(tokenizedDocs)
        val avgDocLength = if (tokenizedDocs.isNotEmpty()) {
            tokenizedDocs.map { it.size.toFloat() }.average().toFloat()
        } else 1f
        val queryTerms = Bm25Scorer.tokenize(query)

        val bm25Scores = mutableMapOf<String, Float>()
        allNotes.forEachIndexed { i, note ->
            val freqs = tokenizedDocs[i].groupingBy { it }.eachCount()
            val score = Bm25Scorer.score(queryTerms, freqs, tokenizedDocs[i].size, avgDocLength, idf)
            if (score > 0f) bm25Scores[note.id] = score
        }

        // --- Semantic scores. Uses only the active embedding model's rows so the
        // real model's vectors never mix with the n-gram fallback's (different
        // dimensions would otherwise make cosineSimilarity return 0f for half the
        // corpus). Populate lazily on the first search if the active model has no
        // embeddings yet. Re-index on note save keeps these fresh thereafter.
        val semanticScores = mutableMapOf<String, Float>()
        val activeModelName = embeddingService.activeModelName()
        val existingCount = embeddingDao.countEmbeddingsByTypeAndModel("note", activeModelName)
        Log.d("VectorSearch", "hybridSearch: activeModel=$activeModelName, existingEmbeddings=$existingCount, notes=${allNotes.size}")
        if (existingCount == 0) {
            if (reindexing.compareAndSet(false, true)) {
                try {
                    Log.d("VectorSearch", "Lazy reindex triggered: reindexing ${allNotes.size} notes with model=$activeModelName")
                    // Drop stale n-gram rows when a real model is now active so
                    // they don't linger and get mixed in by other consumers.
                    if (activeModelName != NGRAM_MODEL_NAME) {
                        embeddingDao.deleteEmbeddingsByModel(NGRAM_MODEL_NAME)
                    }
                    for (note in allNotes) {
                        embeddingService.embedAndStore("note", note.id, "${note.title} ${note.plainText}")
                    }
                    Log.d("VectorSearch", "Lazy reindex completed successfully")
                } catch (e: Exception) {
                    Log.e("VectorSearch", "Reindex failed, falling back to BM25-only", e)
                } finally {
                    reindexing.set(false)
                }
            } else {
                Log.d("VectorSearch", "Reindex already in progress, skipping")
            }
        }
        val allEmbeddings = embeddingDao.getEmbeddingsByTypeAndModel("note", activeModelName)
        if (allEmbeddings.isNotEmpty()) {
            val queryEmbedding = embeddingService.generateEmbedding(query)
            for (embedding in allEmbeddings) {
                val noteEmbedding = embeddingService.deserializeVector(embedding.embeddingVector)
                val score = embeddingService.cosineSimilarity(queryEmbedding, noteEmbedding)
                val current = semanticScores[embedding.sourceId] ?: 0f
                semanticScores[embedding.sourceId] = maxOf(current, score)
            }
            val maxSemantic = semanticScores.values.maxOrNull() ?: 1f
            if (maxSemantic > 0f) {
                for (key in semanticScores.keys) {
                    semanticScores[key] = semanticScores[key]!! / maxSemantic
                }
            }
        }

        // --- Normalize BM25 scores to 0-1 so the weights are comparable. ---
        val maxBm25 = bm25Scores.values.maxOrNull() ?: 1f
        if (maxBm25 > 0f) {
            for (key in bm25Scores.keys) {
                bm25Scores[key] = bm25Scores[key]!! / maxBm25
            }
        }

        // --- Fuse both score sets; a note matched by either one is a candidate. ---
        val noteById = allNotes.associateBy { it.id }
        val results = mutableListOf<SearchResult>()
        for (noteId in bm25Scores.keys + semanticScores.keys) {
            val note = noteById[noteId] ?: continue
            if (note.isDeleted) continue

            val bm25 = bm25Scores[noteId] ?: 0f
            val semantic = semanticScores[noteId] ?: 0f
            val combined = bm25 * bm25Weight + semantic * semanticWeight

            val matchType = when {
                bm25 > 0f && semantic > 0f -> "hybrid"
                semantic > 0f -> "semantic"
                else -> "bm25"
            }
            val explanation = buildString {
                append("Related (score: ${(combined * 100).toInt()}%)")
                when {
                    bm25 > 0f && semantic > 0f ->
                        append(" keyword ${(bm25 * 100).toInt()}% + meaning ${(semantic * 100).toInt()}%")
                    bm25 > 0f -> append(" keyword match")
                    else -> append(" by meaning")
                }
            }

            results.add(SearchResult(note, combined, matchType, explanation))
        }

        return results.sortedByDescending { it.score }.take(limit)
    }

    /**
     * Search result with explanation.
     */
    data class SearchResult(
        val note: NoteEntity,
        val score: Float,
        val matchType: String, // bm25, semantic, hybrid
        val explanation: String
    )
}
