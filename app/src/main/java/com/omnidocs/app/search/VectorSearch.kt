package com.omnidocs.app.search

import android.util.Log
import com.omnidocs.app.data.local.EmbeddingDao
import com.omnidocs.app.data.local.NoteDao
import com.omnidocs.app.data.local.entity.NoteEntity
import com.omnidocs.app.graph.Bm25Scorer
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "VectorSearch"

/**
 * Performs hybrid search combining SQLite FTS4 full-text search with vector similarity.
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
     * Sanitize query string into a safe SQLite FTS4 query (e.g. "term1* OR term2*").
     */
    fun sanitizeFtsQuery(query: String): String {
        val tokens = query.split(Regex("[^\\p{Alnum}_]+"))
            .filter { it.isNotBlank() }
        if (tokens.isEmpty()) return ""
        return tokens.joinToString(" OR ") { "$it*" }
    }

    /**
     * Hybrid search: combine SQLite FTS4/BM25 and vector similarity results.
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
        val cleanQuery = query.trim()
        if (cleanQuery.isBlank()) return emptyList()

        val ftsQuery = sanitizeFtsQuery(cleanQuery)
        val ftsNotes = if (ftsQuery.isNotEmpty()) {
            try {
                noteDao.searchNotesFtsSync(ftsQuery)
            } catch (e: Exception) {
                Log.w(TAG, "FTS query failed, falling back to sync search", e)
                emptyList()
            }
        } else {
            emptyList()
        }

        // If FTS returned results, compute BM25 on the candidate set.
        // If FTS returned empty (e.g. rare tokens or non-indexed phrases), fallback to full corpus if small.
        val candidateNotes = if (ftsNotes.isNotEmpty()) {
            ftsNotes
        } else {
            val all = noteDao.getAllNotesSync()
            if (all.size <= 100) all else emptyList()
        }

        val bm25Scores = mutableMapOf<String, Float>()
        if (candidateNotes.isNotEmpty()) {
            val tokenizedDocs = candidateNotes.map { Bm25Scorer.tokenize("${it.title} ${it.plainText}") }
            val idf = Bm25Scorer.computeIdf(tokenizedDocs)
            val avgDocLength = tokenizedDocs.map { it.size.toFloat() }.average().toFloat().coerceAtLeast(1f)
            val queryTerms = Bm25Scorer.tokenize(cleanQuery)

            candidateNotes.forEachIndexed { i, note ->
                val freqs = tokenizedDocs[i].groupingBy { it }.eachCount()
                val score = Bm25Scorer.score(queryTerms, freqs, tokenizedDocs[i].size, avgDocLength, idf)
                if (score > 0f) bm25Scores[note.id] = score
            }
        }

        // Normalize BM25 scores
        val maxBm25 = bm25Scores.values.maxOrNull() ?: 1f
        if (maxBm25 > 0f) {
            for (key in bm25Scores.keys) {
                bm25Scores[key] = (bm25Scores[key] ?: 0f) / maxBm25
            }
        }

        // --- Semantic vector similarity ---
        val semanticScores = mutableMapOf<String, Float>()
        val activeModelName = embeddingService.activeModelName()
        val existingCount = embeddingDao.countEmbeddingsByTypeAndModel("note", activeModelName)

        if (existingCount == 0) {
            if (reindexing.compareAndSet(false, true)) {
                try {
                    val allNotes = noteDao.getAllNotesSync()
                    Log.d(TAG, "Lazy reindex triggered: reindexing ${allNotes.size} notes with model=$activeModelName")
                    if (activeModelName != NGRAM_MODEL_NAME) {
                        embeddingDao.deleteEmbeddingsByModel(NGRAM_MODEL_NAME)
                    }
                    for (note in allNotes) {
                        embeddingService.embedAndStoreNotePassages(note.id, note.title, note.plainText)
                    }
                    Log.d(TAG, "Lazy reindex completed successfully")
                } catch (e: Exception) {
                    Log.e(TAG, "Reindex failed, falling back to BM25-only", e)
                } finally {
                    reindexing.set(false)
                }
            }
        }

        val allEmbeddings = embeddingDao.getEmbeddingsByTypeAndModel("note", activeModelName)
        if (allEmbeddings.isNotEmpty()) {
            val queryEmbedding = embeddingService.generateEmbedding(cleanQuery)
            for (embedding in allEmbeddings) {
                val noteEmbedding = embeddingService.deserializeVector(embedding.embeddingVector)
                val score = embeddingService.cosineSimilarity(queryEmbedding, noteEmbedding)
                if (score > 0.05f) {
                    val current = semanticScores[embedding.sourceId] ?: 0f
                    semanticScores[embedding.sourceId] = maxOf(current, score)
                }
            }
            val maxSemantic = semanticScores.values.maxOrNull() ?: 1f
            if (maxSemantic > 0f) {
                for (key in semanticScores.keys) {
                    semanticScores[key] = (semanticScores[key] ?: 0f) / maxSemantic
                }
            }
        }

        // Fetch any semantic-only candidate notes that were not in candidateNotes
        val candidateMap = candidateNotes.associateBy { it.id }.toMutableMap()
        val missingIds = semanticScores.keys.filter { it !in candidateMap }
        if (missingIds.isNotEmpty()) {
            val fetched = noteDao.getNotesByIdsSync(missingIds)
            fetched.forEach { candidateMap[it.id] = it }
        }

        // --- Fuse both score sets ---
        val results = mutableListOf<SearchResult>()
        for (noteId in bm25Scores.keys + semanticScores.keys) {
            val note = candidateMap[noteId] ?: continue
            if (note.isDeleted) continue

            val bm25 = bm25Scores[noteId] ?: 0f
            val semantic = semanticScores[noteId] ?: 0f
            val combined = bm25 * bm25Weight + semantic * semanticWeight

            if (combined <= 0f) continue

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
