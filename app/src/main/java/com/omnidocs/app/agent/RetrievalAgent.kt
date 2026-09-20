package com.omnidocs.app.agent

import com.omnidocs.app.ai.NoteBlockAdapter
import com.omnidocs.app.ai.SmartSnippetExtractor
import com.omnidocs.app.data.local.ContentBlockDao
import com.omnidocs.app.data.local.EmbeddingDao
import com.omnidocs.app.data.local.NoteDao
import com.omnidocs.app.data.local.NoteLinkDao
import com.omnidocs.app.data.local.entity.ContentBlockEntity
import com.omnidocs.app.data.repository.NotesRepository
import com.omnidocs.app.search.EmbeddingService
import com.omnidocs.app.search.Tokenizer
import com.omnidocs.app.search.VectorSearch
import com.omnidocs.app.search.VectorSearch.SearchResult
import com.omnidocs.app.vocabulary.VocabularyDictionaryService
import kotlinx.coroutines.flow.first
import org.json.JSONArray
import org.json.JSONObject
import javax.inject.Inject
import javax.inject.Singleton

data class EvidenceCandidate(
    val noteId: String,
    val noteTitle: String,
    val text: String,
    val blockId: String? = null,
    val startOffset: Int? = null,
    val endOffset: Int? = null,
    val score: Float
)

/**
 * Agent responsible for retrieving relevant notes and content blocks using
 * hybrid BM25 + vector retrieval with reciprocal rank fusion (see [VectorSearch]),
 * augmented with Graph RAG 1-hop link neighborhood expansion across the knowledge graph.
 */
@Singleton
class RetrievalAgent(
    private val vectorSearch: VectorSearch,
    private val noteDao: NoteDao,
    private val contentBlockDao: ContentBlockDao,
    private val smartSnippetExtractor: SmartSnippetExtractor,
    private val vocabularyDictionaryService: VocabularyDictionaryService,
    private val noteBlockAdapter: NoteBlockAdapter,
    private val noteLinkDao: NoteLinkDao,
    private val embeddingDao: EmbeddingDao?,
    private val embeddingService: EmbeddingService?,
    @Suppress("UNUSED_PARAMETER") dummy: Unit?
) : Agent {

    @Inject
    constructor(
        vectorSearch: VectorSearch,
        noteDao: NoteDao,
        contentBlockDao: ContentBlockDao,
        smartSnippetExtractor: SmartSnippetExtractor,
        vocabularyDictionaryService: VocabularyDictionaryService,
        noteBlockAdapter: NoteBlockAdapter,
        noteLinkDao: NoteLinkDao,
        embeddingDao: EmbeddingDao,
        embeddingService: EmbeddingService
    ) : this(
        vectorSearch, noteDao, contentBlockDao, smartSnippetExtractor,
        vocabularyDictionaryService, noteBlockAdapter, noteLinkDao,
        embeddingDao, embeddingService, null
    )

    /** Secondary constructor for tests without embeddingDao (7 parameters) */
    constructor(
        vectorSearch: VectorSearch,
        noteDao: NoteDao,
        contentBlockDao: ContentBlockDao,
        smartSnippetExtractor: SmartSnippetExtractor,
        vocabularyDictionaryService: VocabularyDictionaryService,
        noteBlockAdapter: NoteBlockAdapter,
        noteLinkDao: NoteLinkDao
    ) : this(
        vectorSearch, noteDao, contentBlockDao, smartSnippetExtractor,
        vocabularyDictionaryService, noteBlockAdapter, noteLinkDao,
        null, null, null
    )

    override val id: String = "agent_retrieval"

    override suspend fun execute(input: AgentInput, context: AgentContext): AgentResult {
        if (context.isCancelled()) {
            return AgentResult.PermanentFailure("Retrieval cancelled by user")
        }

        val query = input.payload["query"] ?: ""
        if (query.isBlank()) {
            return AgentResult.PermanentFailure("Query is blank in retrieval payload")
        }

        val topK = input.payload["topK"]?.toIntOrNull() ?: 10
        val bm25Weight = input.payload["bm25Weight"]?.toFloatOrNull() ?: 0.5f
        val semanticWeight = input.payload["semanticWeight"]?.toFloatOrNull() ?: 0.5f

        val candidates = retrieveEvidence(query, bm25Weight, semanticWeight, topK)

        if (candidates.isEmpty()) {
            return AgentResult.Success(
                payload = mapOf(
                    "candidateCount" to 0,
                    "candidatesJson" to "[]",
                    "insufficientEvidence" to true
                )
            )
        }

        val jsonArray = JSONArray()
        for (c in candidates) {
            val obj = JSONObject()
            obj.put("noteId", c.noteId)
            obj.put("noteTitle", c.noteTitle)
            obj.put("text", c.text)
            obj.put("blockId", c.blockId ?: "")
            obj.put("startOffset", c.startOffset ?: -1)
            obj.put("endOffset", c.endOffset ?: -1)
            obj.put("score", c.score.toDouble())
            jsonArray.put(obj)
        }

        return AgentResult.Success(
            payload = mapOf(
                "candidateCount" to candidates.size,
                "candidatesJson" to jsonArray.toString(),
                "insufficientEvidence" to false
            )
        )
    }

    /**
     * Executes hybrid retrieval with relevance gating and per-block relevance scoring.
     */
    suspend fun retrieveEvidence(
        query: String,
        bm25Weight: Float,
        semanticWeight: Float,
        topK: Int
    ): List<EvidenceCandidate> {
        // Normalize query: clean BM shorthand, standardize acronym casing
        val normalizedQuery = vocabularyDictionaryService.normalizeTranscript(query)

        val searchResults: List<SearchResult> = vectorSearch.hybridSearch(
            query = normalizedQuery,
            bm25Weight = bm25Weight,
            semanticWeight = semanticWeight,
            limit = topK * 2
        )

        // Single gate (VectorSearch already enforces its floor) plus
        // a drift guard for the semantic leg: at the old 0.05 floor every
        // note cleared as "semantic" and ranked via single-leg full scale,
        // so unrelated notes (e.g. "Smoking"/"ADHD" for "rag system")
        // appeared as top evidence. Genuine paraphrases routinely score
        // 0.50+ on e5; weak drift (floor-0.50) must also overlap lexically.
        val queryTerms = Tokenizer.tokenize(normalizedQuery)
        val queryTermSet = queryTerms.toSet()
        val strongThreshold = driftStrongThreshold(queryTerms.size)
        val qualifiedResults = searchResults.filter { r ->
            if (r.rawScore < VectorSearch.effectiveMinScore(queryTerms.size)) return@filter false
            passesDriftGate(r.matchType, r.passageScore ?: r.score, queryTermSet, r.note.title, r.note.plainText, strongThreshold)
        }.sortedByDescending { it.rawScore }

        val candidates = mutableListOf<EvidenceCandidate>()

        val topResults = qualifiedResults.take(topK)
        // Single batch fetch for all candidate notes instead of one query per note
        val blocksByNote: Map<String, List<ContentBlockEntity>> = if (topResults.isNotEmpty()) {
            contentBlockDao.getBlocksForNoteIds(topResults.map { it.note.id })
                .groupBy { it.noteId ?: "" }
        } else {
            emptyMap()
        }

        for (result in topResults) {
            val note = result.note
            val blocks = blocksByNote[note.id].orEmpty()

            var blockHits = 0
            if (blocks.isNotEmpty()) {
                // Score each block by query-term overlap, take top 3.
                // matchesToken uses word boundaries for Latin terms and
                // substring matching for CJK terms (no \b between CJK chars).
                val scoredBlocks = blocks.map { block ->
                    // Fold the text side: queryTerms came from Tokenizer.tokenize
                    // (folded), so raw block text would never match inflections.
                    val lowerContent = Tokenizer.normalizeForMatch(block.content.lowercase())
                    val blockScore = queryTerms.count { Tokenizer.matchesToken(it, lowerContent) }
                    block to blockScore
                }.sortedByDescending { it.second }

                for ((block, _) in scoredBlocks.take(3).filter { it.second > 0 }) {
                    blockHits++
                    candidates.add(
                        EvidenceCandidate(
                            noteId = note.id,
                            noteTitle = note.title,
                            text = block.content,
                            blockId = block.id,
                            startOffset = block.startOffset,
                            endOffset = block.endOffset,
                            score = result.rawScore
                        )
                    )
                }
            }
            if (blockHits == 0) {
                // Passage-level linking: extract qualifying passage chunks (up to 3 for long/5,000-word notes)
                val canonicalText = NotesRepository.canonicalChunkText(note)
                val passageIndices = if (result.topPassageIndices.isNotEmpty()) {
                    result.topPassageIndices.take(3)
                } else {
                    listOfNotNull(result.topPassageIndex)
                }

                var extractedPassageCount = 0
                if (passageIndices.isNotEmpty()) {
                    // Fast path: retrieve persisted chunk metadata directly without re-chunking
                    val activeModel = try { (embeddingService as EmbeddingService?)?.activeModelName() ?: "" } catch (e: Throwable) { "" }
                    val storedChunks = if (activeModel.isNotBlank()) {
                        try {
                            (embeddingDao as EmbeddingDao?)?.getNotePassageEmbeddings(note.id, activeModel) ?: emptyList()
                        } catch (e: Throwable) {
                            emptyList()
                        }
                    } else emptyList()

                    val hasValidStoredChunks = storedChunks.isNotEmpty() && storedChunks.any { it.chunkText.isNotBlank() }

                    if (hasValidStoredChunks) {
                        val chunksByIndex = storedChunks.associateBy { it.chunkIndex }
                        for (pIdx in passageIndices) {
                            val chunk = chunksByIndex[pIdx] ?: continue
                            if (chunk.chunkText.isNotBlank()) {
                                val titleWithSection = if (!chunk.sectionHeader.isNullOrBlank()) {
                                    "${note.title} > ${chunk.sectionHeader}"
                                } else {
                                    note.title
                                }
                                candidates.add(
                                    EvidenceCandidate(
                                        noteId = note.id,
                                        noteTitle = titleWithSection,
                                        text = chunk.chunkText,
                                        blockId = null,
                                        startOffset = chunk.startOffset,
                                        endOffset = chunk.endOffset,
                                        score = result.rawScore
                                    )
                                )
                                extractedPassageCount++
                            }
                        }
                    }

                    // Fallback to on-the-fly chunking if no stored chunk text available
                    if (extractedPassageCount == 0 && canonicalText.isNotBlank()) {
                        val segments = noteBlockAdapter.chunkText(note.id, note.title, canonicalText)
                        for (pIdx in passageIndices) {
                            if (pIdx in segments.indices) {
                                val matchedSegment = segments[pIdx]
                                if (matchedSegment.content.isNotBlank()) {
                                    val expandedText = noteBlockAdapter.expandToParentContext(matchedSegment, canonicalText, maxChars = 2400)
                                    val titleWithSection = if (!matchedSegment.headerContext.isNullOrBlank()) {
                                        "${note.title} > ${matchedSegment.headerContext}"
                                    } else {
                                        note.title
                                    }
                                    candidates.add(
                                        EvidenceCandidate(
                                            noteId = note.id,
                                            noteTitle = titleWithSection,
                                            text = expandedText,
                                            blockId = null,
                                            startOffset = matchedSegment.startOffset,
                                            endOffset = matchedSegment.endOffset,
                                            score = result.rawScore
                                        )
                                    )
                                    extractedPassageCount++
                                }
                            }
                        }
                    }
                }

                if (extractedPassageCount == 0) {
                    // Fall back to SmartSnippetExtractor if no passage match
                    val snippet = smartSnippetExtractor.extractSnippet(note.plainText, normalizedQuery)
                    if (snippet.isNotBlank()) {
                        val snippetStart = note.plainText.indexOf(snippet).coerceAtLeast(0)
                        candidates.add(
                            EvidenceCandidate(
                                noteId = note.id,
                                noteTitle = note.title,
                                text = snippet,
                                blockId = null,
                                startOffset = snippetStart,
                                endOffset = snippetStart + snippet.length,
                                score = result.rawScore
                            )
                        )
                    }
                }
            }
        }

        // ── 4. Graph RAG Multi-Hop Neighborhood Expansion ──────────────────
        // For the top retrieved seed notes, traverse explicit links, wikilinks,
        // and semantic connections in the note_links graph database.
        // If Note A links to Note B (e.g. [[Database Schema]]), Note B carries
        // essential contextual evidence even if it used different terminology!
        // For high-confidence links (>= 0.75), we traverse 2 hops with score decay.
        val seedNoteIds = topResults.map { it.note.id }.toSet()
        val graphExpandedCandidates = mutableListOf<EvidenceCandidate>()
        val seenGraphNoteIds = mutableSetOf<String>()

        val topSeedResults = topResults.take(3)
        for (seed in topSeedResults) {
            try {
                val links = noteLinkDao.getLinksForNote(seed.note.id).first()
                for (link in links) {
                    val targetId = if (link.sourceNoteId == seed.note.id) link.targetNoteId else link.sourceNoteId
                    if (targetId !in seedNoteIds && targetId !in seenGraphNoteIds) {
                        seenGraphNoteIds.add(targetId)
                        val neighbor = noteDao.getNoteById(targetId)
                        if (neighbor != null && neighbor.plainText.isNotBlank()) {
                            val relationLabel = when (link.linkType) {
                                "references" -> "linked from"
                                "supports" -> "supports"
                                "contradicts" -> "contradicts"
                                "builds_on" -> "builds on"
                                else -> "connected to"
                            }
                            val snippet = smartSnippetExtractor.extractSnippet(neighbor.plainText, normalizedQuery)
                                .ifBlank { neighbor.plainText.take(400).trim() }
                            if (snippet.isNotBlank()) {
                                // 1-hop graph-propagated score: scaled by seed score and link confidence
                                val graphScore = (seed.rawScore * link.confidence * 0.75f).coerceAtLeast(0.35f)
                                graphExpandedCandidates.add(
                                    EvidenceCandidate(
                                        noteId = neighbor.id,
                                        noteTitle = "${neighbor.title} ($relationLabel [[${seed.note.title}]])",
                                        text = snippet,
                                        blockId = null,
                                        startOffset = 0,
                                        endOffset = snippet.length,
                                        score = graphScore
                                    )
                                )

                                // 2-Hop Graph Traversal for high-confidence connections
                                if (link.confidence >= 0.75f) {
                                    try {
                                        val secondHopLinks = noteLinkDao.getLinksForNote(neighbor.id).first()
                                        for (secondLink in secondHopLinks.take(3)) {
                                            val twoHopTargetId = if (secondLink.sourceNoteId == neighbor.id) {
                                                secondLink.targetNoteId
                                            } else {
                                                secondLink.sourceNoteId
                                            }
                                            if (twoHopTargetId !in seedNoteIds && twoHopTargetId !in seenGraphNoteIds) {
                                                seenGraphNoteIds.add(twoHopTargetId)
                                                val twoHopNeighbor = noteDao.getNoteById(twoHopTargetId)
                                                if (twoHopNeighbor != null && twoHopNeighbor.plainText.isNotBlank()) {
                                                    val twoHopSnippet = smartSnippetExtractor.extractSnippet(twoHopNeighbor.plainText, normalizedQuery)
                                                        .ifBlank { twoHopNeighbor.plainText.take(400).trim() }
                                                    if (twoHopSnippet.isNotBlank()) {
                                                        // 2-hop score decay factor: 0.50x
                                                        val twoHopScore = (seed.rawScore * link.confidence * secondLink.confidence * 0.50f)
                                                            .coerceAtLeast(0.30f)
                                                        graphExpandedCandidates.add(
                                                            EvidenceCandidate(
                                                                noteId = twoHopNeighbor.id,
                                                                noteTitle = "${twoHopNeighbor.title} (connected via [[${neighbor.title}]] from [[${seed.note.title}]])",
                                                                text = twoHopSnippet,
                                                                blockId = null,
                                                                startOffset = 0,
                                                                endOffset = twoHopSnippet.length,
                                                                score = twoHopScore
                                                            )
                                                        )
                                                    }
                                                }
                                            }
                                        }
                                    } catch (e: Exception) {
                                        android.util.Log.w("RetrievalAgent", "2-hop graph expansion failed for note ${neighbor.id}", e)
                                    }
                                }
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                android.util.Log.w("RetrievalAgent", "Graph expansion failed for note ${seed.note.id}", e)
            }
        }

        candidates.addAll(graphExpandedCandidates)

        return candidates.distinctBy { "${it.noteId}:${it.text.hashCode()}" }.take(topK)
    }

    companion object {
        /** Strong-semantic cutoff: above this, no lexical overlap is required. */
        const val DRIFT_STRONG_THRESHOLD = 0.50f

        /**
         * Relaxed cutoff for short queries (<=3 terms): with little text to
         * match on, e5 paraphrase scores run lower, so the no-overlap bar
         * drops to 0.40. No-overlap drift below the bar is still rejected.
         */
        const val DRIFT_STRONG_THRESHOLD_SHORT_QUERY = 0.40f

        fun driftStrongThreshold(queryTermCount: Int): Float =
            if (queryTermCount <= VectorSearch.SHORT_QUERY_MAX_TERMS) DRIFT_STRONG_THRESHOLD_SHORT_QUERY
            else DRIFT_STRONG_THRESHOLD

        /**
         * Drift gate for one search hit: non-semantic legs always pass;
         * semantic-only hits pass when strong or lexically overlapping the
         * note (title or body). Extracted for unit tests.
         */
        fun passesDriftGate(
            matchType: String,
            score: Float,
            queryTermSet: Set<String>,
            noteTitle: String,
            notePlainText: String,
            strongThreshold: Float
        ): Boolean {
            if (matchType != "semantic") return true
            if (score >= strongThreshold) return true
            val foldedPlain = Tokenizer.normalizeForMatch(notePlainText.lowercase())
            val foldedTitle = Tokenizer.normalizeForMatch(noteTitle.lowercase())
            return queryTermSet.any {
                Tokenizer.matchesToken(it, foldedPlain) ||
                    Tokenizer.matchesToken(it, foldedTitle)
            }
        }
    }
}
