package com.omnidocs.app.search

import android.util.Log
import com.omnidocs.app.ai.BackgroundInferenceDispatcher
import com.omnidocs.app.data.local.EmbeddingDao
import com.omnidocs.app.data.local.NoteDao
import com.omnidocs.app.data.local.entity.NoteEntity
import com.omnidocs.app.data.repository.NotesRepository
import com.omnidocs.app.graph.Bm25Scorer
import com.omnidocs.app.search.ann.AnnIndexManager
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "VectorSearch"

/**
 * Performs hybrid search combining SQLite FTS4 full-text search with vector similarity.
 * Falls back to BM25-only if no embeddings are available.
 */
@Singleton
open class VectorSearch(
    private val embeddingDao: EmbeddingDao,
    private val noteDao: NoteDao,
    private val embeddingService: EmbeddingService,
    private val inferenceDispatcher: BackgroundInferenceDispatcher,
    private val annIndexManager: AnnIndexManager?,
    @Suppress("UNUSED_PARAMETER") dummy: Unit?
) {
    @Inject
    constructor(
        embeddingDao: EmbeddingDao,
        noteDao: NoteDao,
        embeddingService: EmbeddingService,
        inferenceDispatcher: BackgroundInferenceDispatcher,
        annIndexManager: AnnIndexManager
    ) : this(embeddingDao, noteDao, embeddingService, inferenceDispatcher, annIndexManager, null)

    /** Secondary constructor for tests without ANN index injected */
    constructor(
        embeddingDao: EmbeddingDao,
        noteDao: NoteDao,
        embeddingService: EmbeddingService,
        inferenceDispatcher: BackgroundInferenceDispatcher
    ) : this(embeddingDao, noteDao, embeddingService, inferenceDispatcher, null, null)

    // Guards lazy first-search reindexing so concurrent searches don't duplicate it.
    private val reindexing = AtomicBoolean(false)

    private data class CorpusIdfSnapshot(
        val lastModifiedMax: Long,
        val noteCount: Int,
        val tokensByNote: Map<String, List<String>>,
        val idf: Map<String, Float>,
        val avgDocLength: Float
    )

    @Volatile
    private var cachedIdfSnapshot: CorpusIdfSnapshot? = null

    companion object {
        /** Minimum fused score for a note to be returned. Weak matches are dropped. */
        const val MIN_COMBINED_SCORE = 0.15f

        /** Cosine floor when using the weak 128-dim n-gram fallback embeddings. */
        const val NGRAM_COSINE_FLOOR = 0.12f

        /** Cosine floor when using a real embedding model (e5).
         *  Unrelated e5 pairs sit ~0.15-0.25; genuine topical matches are
         *  0.45+. The old 0.05 let every note through as semantic-only noise
         *  that then ranked via the single-leg full-scale fusion. */
        const val MODEL_COSINE_FLOOR = 0.30f

        /**
         * Strict cosine floor for standalone semantic matches that have ZERO lexical overlap.
         * In e5-small, unrelated pairs often reach 0.30-0.42 due to embedding anisotropy.
         * Only true conceptual paraphrases/synonyms (0.55+) are admitted without keyword overlap.
         */
        const val SEMANTIC_STANDALONE_FLOOR = 0.55f

        /**
         * Checks whether a candidate note is genuinely relevant to the query terms.
         * Enforces strict precision:
         * 1. If the note has lexical overlap (contains query terms in title or body), it passes.
         * 2. If the note has NO lexical overlap:
         *    - N-gram hash fallback is strictly rejected (128-dim character hashing has zero
         *      semantic understanding; collisions are false-positive noise).
         *    - Real neural embeddings (e5) are only admitted if similarity clears [SEMANTIC_STANDALONE_FLOOR] (0.55+).
         */
        fun passesRelevanceGate(
            note: NoteEntity,
            hasLexicalMatch: Boolean,
            semanticScore: Float,
            isNgramModel: Boolean,
            queryTerms: List<String>
        ): Boolean {
            if (queryTerms.isEmpty()) return false

            val foldedPlain = Tokenizer.normalizeForMatch(note.plainText.lowercase())
            val foldedTitle = Tokenizer.normalizeForMatch(note.title.lowercase())
            val hasTokenOverlap = queryTerms.any {
                Tokenizer.matchesToken(it, foldedPlain) || Tokenizer.matchesToken(it, foldedTitle)
            }
            if (hasTokenOverlap || hasLexicalMatch) return true

            // Zero lexical overlap:
            if (isNgramModel) return false
            return semanticScore >= SEMANTIC_STANDALONE_FLOOR
        }

        /**
         * Minimum fused score for SHORT queries (<= [SHORT_QUERY_MAX_TERMS]
         * content terms). Absolute-scale floors structurally punish short
         * queries: two terms can never produce the multi-term raw scores the
         * 0.15 floor was calibrated on, so "rag system" died despite matching
         * notes. The semantic drift guard still applies on top.
         */
        const val SHORT_QUERY_MIN_SCORE = 0.08f
        const val SHORT_QUERY_MAX_TERMS = 3

        /**
         * Fused-score floor when EVERY query term matches the note title.
         * A title match is strong intent ("RAG System Notes" for "rag
         * system") and must never score ~0 because the body is long.
         */
        const val TITLE_MATCH_FLOOR = 0.35f

        /** Effective retrieval floor for a query with [termCount] content terms. */
        fun effectiveMinScore(termCount: Int): Float =
            if (termCount <= SHORT_QUERY_MAX_TERMS) SHORT_QUERY_MIN_SCORE
            else MIN_COMBINED_SCORE

        /**
         * True when every query term matches [title] (word-boundary for
         * Latin, substring for CJK — same semantics as block scoring).
         */
        fun titleMatchesAllTerms(title: String, queryTerms: List<String>): Boolean {
            if (queryTerms.isEmpty() || title.isBlank()) return false
            val folded = Tokenizer.normalizeForMatch(title.lowercase())
            return queryTerms.all { Tokenizer.matchesToken(it, folded) }
        }

        /** BM25 saturation constant for absolute-scale normalization: raw/(raw+K). */
        const val BM25_SATURATION_K = 1.5f

        /** Max content terms in the FTS candidate gate. */
        const val MAX_FTS_TERMS = 5

        /** Reciprocal Rank Fusion constant (standard TREC / IR default: 60). */
        const val RRF_K = 60

        /**
         * Stage 2 High-Precision Reranker:
         * Re-scores Stage-1 fused candidates using exact phrase matching, query term proximity,
         * and title-topic anchor weighting.
         */
        fun rerank(
            query: String,
            candidates: List<SearchResult>
        ): List<SearchResult> {
            if (candidates.size <= 1 || query.isBlank()) return candidates
            val queryTerms = Tokenizer.tokenize(query)
            if (queryTerms.isEmpty()) return candidates
            val foldedQuery = Tokenizer.normalizeForMatch(query.lowercase()).trim()

            return candidates.map { result ->
                var multiplier = 1.0f
                val note = result.note
                val foldedTitle = Tokenizer.normalizeForMatch(note.title.lowercase())
                val foldedBody = Tokenizer.normalizeForMatch(note.plainText.lowercase())

                // 1. Exact phrase match bonus (1.25x for title, 1.15x for body)
                if (foldedTitle.contains(foldedQuery)) {
                    multiplier *= 1.25f
                } else if (foldedBody.contains(foldedQuery)) {
                    multiplier *= 1.15f
                }

                // 2. Term Proximity / Kernel Density bonus:
                // When 2+ query terms appear in the same sentence or clause
                if (queryTerms.size >= 2) {
                    val sentences = note.plainText.split(Regex("(?<=[.!?\n])\\s+"))
                    val hasCoOccurringSentence = sentences.any { s ->
                        val lowerS = Tokenizer.normalizeForMatch(s.lowercase())
                        queryTerms.count { Tokenizer.matchesToken(it, lowerS) } >= 2
                    }
                    if (hasCoOccurringSentence) {
                        multiplier *= 1.10f
                    }
                }

                val rerankedScore = (result.score * multiplier).coerceIn(0f, 1f)
                result.copy(score = rerankedScore)
            }.sortedByDescending { it.score }
        }

        /**
         * Parse passage index from passage embedding ID ("note:$noteId:$index:$model").
         * Returns null for legacy full-text embedding IDs ("note:$noteId:$model").
         */
        fun parsePassageIndex(embeddingId: String, noteId: String): Int? {
            val prefix = "note:$noteId:"
            if (!embeddingId.startsWith(prefix)) return null
            val rest = embeddingId.removePrefix(prefix).split(":")
            return if (rest.size >= 2) rest[0].toIntOrNull() else null
        }

        /** Corpus size cap for full-corpus IDF (fallback: candidate-local IDF). */
        const val CORPUS_IDF_CAP = 2000

        /** FTS boolean operators must never reach the query as terms. */
        private val FTS_OPERATORS = setOf("and", "or", "not", "near")

        /**
         * Sanitize query string into a safe SQLite FTS4 query.
         * Filters stop words and FTS operators, then ORs up to 5 content terms
         * for recall-oriented candidate gating (BM25 rescoring restores
         * precision). Terms are quoted so note content can never inject FTS
         * syntax; single-term queries need no operator.
         */
        fun sanitizeFtsQuery(query: String): String {
            val tokens = query.split(Regex("[^\\p{L}\\p{N}_]+"))
                .filter {
                    it.isNotBlank() && (it.length > 1 || Tokenizer.isCjk(it)) &&
                        !Tokenizer.isStopWord(it) && it.lowercase() !in FTS_OPERATORS
                }
                .distinct()
            if (tokens.isEmpty()) return ""

            // Prefix wildcard '*' is only safe for tokens of length >= 3.
            // Short 2-character tokens (like "ai", "db", "ui") or CJK characters must use exact
            // match ("\"ai\"") so "ai" never matches "air", "aid", "aim", "aircraft", etc.
            val selected = tokens.take(MAX_FTS_TERMS).map {
                if (it.length >= 3 && !Tokenizer.isCjk(it)) "\"$it\"*" else "\"$it\""
            }
            return if (selected.size >= 2) selected.joinToString(" OR ") else selected[0]
        }
    }

    /**
     * Hybrid search: combine SQLite FTS4/BM25 and vector similarity results.
     *
     * @param query the search query
     * @param bm25Weight weight for BM25 results (0.0-1.0)
     * @param semanticWeight weight for semantic results (0.0-1.0)
     * @param limit max results
     */
    open suspend fun hybridSearch(
        query: String,
        bm25Weight: Float = 0.6f,
        semanticWeight: Float = 0.4f,
        limit: Int = 20
    ): List<SearchResult> {
        val cleanQuery = query.trim()
        if (cleanQuery.isBlank()) return emptyList()

        // Weights must sum to 1: the fused score is an absolute-scale blend
        // in [0,1] and MIN_COMBINED_SCORE is calibrated against that scale.
        val weightSum = bm25Weight + semanticWeight
        val wBm25: Float
        val wSemantic: Float
        if (weightSum > 0f) {
            wBm25 = bm25Weight / weightSum
            wSemantic = semanticWeight / weightSum
        } else {
            wBm25 = 0.5f
            wSemantic = 0.5f
        }

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
        // If FTS returned empty (rare tokens, CJK segmentation, operator
        // edge cases), score BM25 over the full corpus — an empty gate must
        // never silently mean "no lexical candidates".
        val candidateNotes = if (ftsNotes.isNotEmpty()) {
            ftsNotes
        } else {
            noteDao.getAllNotesSync()
        }

        val bm25Scores = mutableMapOf<String, Float>()
        if (candidateNotes.isNotEmpty()) {
            // Title counts double: a title-exact note must beat a note with
            // the same term incidentally in a long body.
            fun docTokens(note: NoteEntity): List<String> =
                Bm25Scorer.tokenize("${note.title} ${note.title} ${note.plainText}")

            // IDF over the FULL live corpus, cached by (noteCount, maxUpdatedAt)
            // to avoid re-tokenizing the whole database on every keystroke.
            val liveNotes = noteDao.getAllNotesSync().filter { !it.isDeleted }
            val noteCount = liveNotes.size
            val maxModified = liveNotes.maxOfOrNull { it.updatedAt } ?: 0L

            val snapshot = cachedIdfSnapshot
            val (tokensByNote, idf, avgDocLength) = if (
                snapshot != null && snapshot.noteCount == noteCount && snapshot.lastModifiedMax == maxModified
            ) {
                Triple(snapshot.tokensByNote, snapshot.idf, snapshot.avgDocLength)
            } else {
                val computedTokens: Map<String, List<String>>
                val computedIdf: Map<String, Float>
                if (liveNotes.size <= CORPUS_IDF_CAP) {
                    computedTokens = liveNotes.associate { it.id to docTokens(it) }
                    computedIdf = Bm25Scorer.computeIdf(computedTokens.values.toList())
                } else {
                    Log.w(TAG, "Corpus exceeds IDF cap, using candidate-local IDF")
                    computedTokens = candidateNotes.associate { it.id to docTokens(it) }
                    computedIdf = Bm25Scorer.computeIdf(computedTokens.values.toList())
                }
                val computedAvgDocLength = computedTokens.values.map { it.size.toFloat() }
                    .average().toFloat().coerceAtLeast(1f)
                val newSnapshot = CorpusIdfSnapshot(
                    lastModifiedMax = maxModified,
                    noteCount = noteCount,
                    tokensByNote = computedTokens,
                    idf = computedIdf,
                    avgDocLength = computedAvgDocLength
                )
                cachedIdfSnapshot = newSnapshot
                Triple(computedTokens, computedIdf, computedAvgDocLength)
            }
            val queryTerms = Bm25Scorer.tokenize(cleanQuery)

            for (note in candidateNotes) {
                val tokens = tokensByNote[note.id] ?: docTokens(note)
                val freqs = tokens.groupingBy { it }.eachCount()
                // Dampen document length penalty for long notes (e.g. 5,000 words ~ 6,500 tokens).
                // Without dampening, standard BM25 normalizer (1 - b + b * (docLen / avgLen)) explodes,
                // crushing the relevance score of sections within long documents.
                val effectiveDocLength = if (tokens.size > avgDocLength * 3f) {
                    (avgDocLength * 3f + (tokens.size - avgDocLength * 3f) * 0.25f).toInt()
                } else {
                    tokens.size
                }
                val score = Bm25Scorer.score(queryTerms, freqs, effectiveDocLength, avgDocLength, idf)
                if (score > 0f) bm25Scores[note.id] = score
            }
        }

        // Absolute-scale BM25: saturating normalization instead of per-query
        // max. A lone weak hit (raw ~0.3) maps to ~0.17 and cannot masquerade
        // as a confident 100%; genuine multi-term hits (raw 2+) exceed 0.5.
        // Kept keyed by note for the fusion below.
        val bm25Norm = mutableMapOf<String, Float>()
        for ((noteId, raw) in bm25Scores) {
            bm25Norm[noteId] = raw / (raw + BM25_SATURATION_K)
        }

        // --- Semantic vector similarity ---
        val semanticScores = mutableMapOf<String, Float>()
        val activeModelName = embeddingService.activeModelName()
        val existingCount = embeddingDao.countEmbeddingsByTypeAndModel("note", activeModelName)

        if (existingCount == 0) {
            if (reindexing.compareAndSet(false, true)) {
                // Reindex in the BACKGROUND: the search path must never block on a
                // full-corpus embed (it can take many seconds for large workspaces).
                // Answer with BM25/FTS now; the semantic index becomes available on
                // subsequent searches once the background job finishes.
                inferenceDispatcher.enqueue("full-reindex:$activeModelName") {
                    try {
                        val allNotes = noteDao.getAllNotesSync()
                        Log.d(TAG, "Background reindex started: ${allNotes.size} notes with model=$activeModelName")
                        if (activeModelName != NGRAM_MODEL_NAME) {
                            embeddingDao.deleteEmbeddingsByModel(NGRAM_MODEL_NAME)
                        }
                        for (note in allNotes) {
                            val chunkText = NotesRepository.canonicalChunkText(note)
                            embeddingService.embedAndStoreNotePassages(note.id, note.title, chunkText)
                        }
                        Log.d(TAG, "Background reindex completed successfully")
                    } catch (e: Exception) {
                        Log.e(TAG, "Background reindex failed, search falls back to BM25-only", e)
                    } finally {
                        reindexing.set(false)
                    }
                }
                Log.d(TAG, "No embeddings yet — answered with BM25, reindex enqueued in background")
            }
        }

        val allEmbeddings = embeddingDao.getEmbeddingsByTypeAndModel("note", activeModelName)
        val isNgramModel = activeModelName.startsWith(NGRAM_MODEL_NAME)
        val cosineFloor = if (isNgramModel) NGRAM_COSINE_FLOOR else MODEL_COSINE_FLOOR

        val topPassageIndexByNote = mutableMapOf<String, Int>()
        val topPassageScoreByNote = mutableMapOf<String, Float>()
        val topPassageIndicesByNote = mutableMapOf<String, MutableList<Pair<Int, Float>>>()

        if (allEmbeddings.isNotEmpty()) {
            val queryEmbedding = embeddingService.generateEmbedding(cleanQuery)
            if (annIndexManager != null) {
                // Accelerate via ANN index (USearch HNSW on disk / memory)
                if (annIndexManager.size() == 0) {
                    annIndexManager.syncCorpus(allEmbeddings) { entity ->
                        embeddingService.cachedVector(entity.id, entity.createdAt, entity.embeddingVector)
                    }
                }
                val annMatches = annIndexManager.search(
                    query = queryEmbedding,
                    wanted = 100,
                    cosineFloor = cosineFloor,
                    modelName = activeModelName
                )
                for (hit in annMatches) {
                    topPassageIndicesByNote.getOrPut(hit.noteId) { mutableListOf() }
                        .add(hit.chunkIndex to hit.similarity)
                    val currentMax = topPassageScoreByNote[hit.noteId] ?: 0f
                    if (hit.similarity > currentMax) {
                        topPassageScoreByNote[hit.noteId] = hit.similarity
                        topPassageIndexByNote[hit.noteId] = hit.chunkIndex
                    }
                }
            } else {
                // Fallback: Passage-level retrieval with MaxP scoring
                for (embedding in allEmbeddings) {
                    val noteEmbedding = embeddingService.cachedVector(
                        embedding.id, embedding.createdAt, embedding.embeddingVector
                    )
                    val score = embeddingService.cosineSimilarity(queryEmbedding, noteEmbedding)
                    if (score > cosineFloor) {
                        val passageIdx = parsePassageIndex(embedding.id, embedding.sourceId)
                        if (passageIdx != null) {
                            topPassageIndicesByNote.getOrPut(embedding.sourceId) { mutableListOf() }
                                .add(passageIdx to score)
                        }
                        val currentMax = topPassageScoreByNote[embedding.sourceId] ?: 0f
                        if (score > currentMax) {
                            topPassageScoreByNote[embedding.sourceId] = score
                            passageIdx?.let { idx ->
                                topPassageIndexByNote[embedding.sourceId] = idx
                            }
                        }
                    }
                }
            }
            for ((noteId, score) in topPassageScoreByNote) {
                semanticScores[noteId] = score
            }
        }

        // Clean zero-hit return: if no note matched any query term in BM25,
        // and either n-gram fallback is active (zero semantic capacity) or
        // no real embedding cleared the standalone floor (0.55+), return empty immediately.
        // Prevents low-confidence background noise from showing when no notes match.
        val hasNeuralStandaloneMatch = !isNgramModel && semanticScores.any { it.value >= SEMANTIC_STANDALONE_FLOOR }
        if (bm25Scores.isEmpty() && !hasNeuralStandaloneMatch) {
            return emptyList()
        }

        // Fetch any semantic-only candidate notes that were not in candidateNotes
        val candidateMap = candidateNotes.associateBy { it.id }.toMutableMap()
        val missingIds = semanticScores.keys.filter { it !in candidateMap }
        if (missingIds.isNotEmpty()) {
            val fetched = noteDao.getNotesByIdsSync(missingIds)
            fetched.forEach { candidateMap[it.id] = it }
        }

        // --- Reciprocal Rank Fusion (RRF, k=60) with normalized scale ---
        val rankedBm25 = bm25Scores.entries
            .sortedByDescending { it.value }
            .mapIndexed { index, entry -> entry.key to (index + 1) }
            .toMap()

        val rankedSemantic = semanticScores.entries
            .sortedByDescending { it.value }
            .mapIndexed { index, entry -> entry.key to (index + 1) }
            .toMap()

        val results = mutableListOf<SearchResult>()
        val titleCheckTerms = Tokenizer.tokenize(cleanQuery)
        val minScore = effectiveMinScore(titleCheckTerms.size)
        val maxRrf = 1.0f / (RRF_K + 1)

        for (noteId in bm25Scores.keys + semanticScores.keys) {
            val note = candidateMap[noteId] ?: continue
            if (note.isDeleted) continue

            val bm25Raw = bm25Norm[noteId] ?: 0f
            val semanticRaw = semanticScores[noteId] ?: 0f

            // 1. Strict relevance gate: reject notes with zero lexical match unless
            // a real neural embedding model demonstrates high confidence (>= 0.55).
            if (!passesRelevanceGate(note, bm25Raw > 0f, semanticRaw, isNgramModel, titleCheckTerms)) {
                continue
            }

            val rBm25 = rankedBm25[noteId]
            val rSemantic = rankedSemantic[noteId]

            // 2. Score-Aware RRF with Query Term Coordination:
            // Scale rank reciprocal by the normalized score magnitude of each leg, and factor in
            // how many of the query terms were matched in the document.
            // Notes matching all terms ("ai" AND "agent") receive full weight; notes matching only 1 of 3+ terms are moderated.
            val foldedPlain = Tokenizer.normalizeForMatch(note.plainText.lowercase())
            val foldedTitle = Tokenizer.normalizeForMatch(note.title.lowercase())
            val matchedTermsCount = titleCheckTerms.count {
                Tokenizer.matchesToken(it, foldedPlain) || Tokenizer.matchesToken(it, foldedTitle)
            }
            val coordFactor = if (titleCheckTerms.size >= 2) {
                (matchedTermsCount.toFloat() / titleCheckTerms.size).coerceIn(0.5f, 1.0f)
            } else 1.0f

            val rrfBm25 = if (rBm25 != null && bm25Raw > 0f) {
                (bm25Raw * coordFactor) * (wBm25 / (RRF_K + rBm25))
            } else 0f

            val rrfSemantic = if (rSemantic != null && semanticRaw > 0f) {
                val normSemantic = ((semanticRaw - cosineFloor) / (1f - cosineFloor)).coerceIn(0f, 1f)
                normSemantic * (wSemantic / (RRF_K + rSemantic))
            } else 0f

            val rrf = rrfBm25 + rrfSemantic

            // Normalized to [0,1] scale against theoretical max (rank 1 on both legs)
            val fusedRrf = if (maxRrf > 0f) rrf / maxRrf else 0f

            // Title match floors the score: the note is ABOUT the query even
            // when a long body dilutes the lexical/semantic legs.
            val titleFloor =
                if (titleMatchesAllTerms(note.title, titleCheckTerms)) TITLE_MATCH_FLOOR else 0f
            val rawCombined = maxOf(fusedRrf, titleFloor)

            if (rawCombined <= 0f) continue

            val matchType = when {
                bm25Raw > 0f && semanticRaw > 0f -> "hybrid"
                semanticRaw > 0f -> "semantic"
                else -> "bm25"
            }
            val explanation = buildString {
                append("Related (score: ${(rawCombined * 100).toInt()}%)")
                when {
                    bm25Raw > 0f && semanticRaw > 0f ->
                        append(" keyword ${(bm25Raw * 100).toInt()}% + meaning ${(semanticRaw * 100).toInt()}%")
                    bm25Raw > 0f -> append(" keyword match")
                    else -> append(" by meaning")
                }
            }

            val sortedPassages = topPassageIndicesByNote[noteId]
                ?.sortedByDescending { it.second }
                ?.map { it.first }
                ?: topPassageIndexByNote[noteId]?.let { listOf(it) }
                ?: emptyList()

            results.add(
                SearchResult(
                    note = note,
                    score = rawCombined,
                    matchType = matchType,
                    explanation = explanation,
                    rawScore = rawCombined,
                    topPassageIndex = topPassageIndexByNote[noteId],
                    passageScore = topPassageScoreByNote[noteId],
                    topPassageIndices = sortedPassages
                )
            )
        }

        val qualified = results.filter { it.rawScore >= minScore }
        return rerank(cleanQuery, qualified).take(limit)
    }

    /**
     * Search result with explanation.
     */
    data class SearchResult(
        val note: NoteEntity,
        val score: Float,
        val matchType: String, // bm25, semantic, hybrid
        val explanation: String,
        /** Absolute fused score on [0,1] (saturating BM25 + raw cosine or RRF). */
        val rawScore: Float = score,
        /** Winning passage chunk index from vector search (if semantic match). */
        val topPassageIndex: Int? = null,
        /** Highest passage cosine similarity score. */
        val passageScore: Float? = null,
        /** All qualifying passage chunk indices for multi-passage documents (sorted by score descending). */
        val topPassageIndices: List<Int> = topPassageIndex?.let { listOf(it) } ?: emptyList()
    )
}
