package com.omnidocs.app.graph

import android.util.Log
import com.omnidocs.app.ai.AiOutputProcessor
import com.omnidocs.app.ai.LlamaCppService
import com.omnidocs.app.ai.ModelDownloadManager
import com.omnidocs.app.ai.ModelInfo
import com.omnidocs.app.ai.ModelPreferences
import com.omnidocs.app.ai.PromptBuilder
import com.omnidocs.app.ai.PromptFormat
import com.omnidocs.app.ai.resolveActiveModel
import com.omnidocs.app.data.local.NoteLinkDao
import com.omnidocs.app.data.local.entity.NoteLinkEntity
import com.omnidocs.app.data.repository.NotesRepository
import com.omnidocs.app.domain.model.Note
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "GraphEngine"

@Singleton
class GraphEngine @Inject constructor(
    private val llamaCppService: LlamaCppService,
    private val modelDownloadManager: ModelDownloadManager,
    private val modelPreferences: ModelPreferences,
    private val repository: NotesRepository,
    private val noteLinkDao: NoteLinkDao
) {
    companion object {
        /**
         * Generic stop-tags that represent broad organizational metadata or status,
         * rather than specific semantic concepts. Sharing only a generic stop-tag
         * must NEVER create a direct knowledge graph connection.
         */
        val GENERIC_STOP_TAGS = setOf(
            "note", "notes", "todo", "task", "tasks", "general", "misc", "draft",
            "work", "personal", "temp", "archive", "meeting", "daily", "ideas",
            "idea", "untitled", "document", "doc", "docs", "test", "demo", "sample",
            "uncategorized", "default"
        )
    }

    private suspend fun getActiveModel(): ModelInfo? = withContext(Dispatchers.IO) {
        resolveActiveModel(modelPreferences, modelDownloadManager)
    }

    /**
     * Build a GraphData from all notes using an instant, high-performance approach:
     * 1. Deterministic explicit wikilinks [[Note Title]] and markdown links [Title](id)
     * 2. Tag co-occurrence clustering with stop-tag filtering and Jaccard similarity gating
     * 3. BM25 lexical relevance overlap with TF-IDF signature terms and calibrated thresholds
     * Executes in under 30 milliseconds on Dispatchers.IO.
     * All discovered connections are synchronized into the encrypted note_links Room table.
     */
    suspend fun buildGraph(includeAiEnrichment: Boolean = false): GraphData = withContext(Dispatchers.IO) {
        val notes = repository.getAllNotesSync()
        val nodeTags = notes.associate { it.id to parseTags(it.tags) }
        val nodes = notes.map { note ->
            val wordCount = note.plainText.split("\\s+".toRegex()).size
            val tags = nodeTags[note.id] ?: emptyList()
            GraphNode(note.id, note.title, wordCount, tags)
        }

        if (notes.size < 2) {
            return@withContext GraphData(nodes = nodes, edges = emptyList())
        }

        val edgesMap = mutableMapOf<Pair<String, String>, GraphEdge>()

        fun addEdgeIfAbsent(fromId: String, toId: String, label: String, strength: Float) {
            if (fromId == toId) return
            val key = if (fromId < toId) fromId to toId else toId to fromId
            val existing = edgesMap[key]
            if (existing == null || existing.strength < strength) {
                edgesMap[key] = GraphEdge(from = fromId, to = toId, label = label, strength = strength)
            }
        }

        // ── Tier 1: Explicit Wikilinks & Markdown Links ──────────────────────
        val notesByTitle = notes.associateBy { it.title.trim().lowercase() }
        val notesById = notes.associateBy { it.id }

        for (note in notes) {
            val contentToScan = note.content + "\n" + note.plainText
            // a) Wikilinks: [[Target Title]] or [[Target Title|Display Text]]
            val wikilinkRegex = Regex("""\[\[(.*?)\]\]""")
            wikilinkRegex.findAll(contentToScan).forEach { match ->
                val rawTarget = match.groupValues[1].split("|").firstOrNull()?.trim()?.lowercase() ?: ""
                val targetNote = notesByTitle[rawTarget] ?: notesById[rawTarget]
                if (targetNote != null && targetNote.id != note.id) {
                    addEdgeIfAbsent(note.id, targetNote.id, "links to", 1.0f)
                }
            }
            // b) Markdown links: [Display](targetId) or [Display](target_title)
            val mdLinkRegex = Regex("""\[.*?\]\((.*?)\)""")
            mdLinkRegex.findAll(contentToScan).forEach { match ->
                val target = match.groupValues[1].trim().lowercase()
                val targetNote = notesById[target] ?: notesByTitle[target]
                if (targetNote != null && targetNote.id != note.id) {
                    addEdgeIfAbsent(note.id, targetNote.id, "links to", 1.0f)
                }
            }
        }

        // ── Tier 2: Shared Tag Co-Occurrence (Stop-Tag Filtered & Jaccard Gated) ──
        // Compute corpus document frequency for every tag
        val tagDocFrequency = mutableMapOf<String, Int>()
        nodeTags.values.forEach { tagList ->
            tagList.map { it.lowercase().trim() }.distinct().forEach { t ->
                tagDocFrequency[t] = (tagDocFrequency[t] ?: 0) + 1
            }
        }

        for (i in notes.indices) {
            val tagsA = nodeTags[notes[i].id].orEmpty()
                .map { it.lowercase().trim() }
                .filter { it.isNotBlank() && it !in GENERIC_STOP_TAGS }
                .toSet()
            if (tagsA.isEmpty()) continue

            for (j in i + 1 until notes.size) {
                val tagsB = nodeTags[notes[j].id].orEmpty()
                    .map { it.lowercase().trim() }
                    .filter { it.isNotBlank() && it !in GENERIC_STOP_TAGS }
                    .toSet()
                if (tagsB.isEmpty()) continue

                val shared = tagsA.intersect(tagsB)
                if (shared.isEmpty()) continue

                val union = tagsA.union(tagsB)
                val jaccard = shared.size.toFloat() / union.size.toFloat()

                // Gating logic:
                // 1. Two or more specific non-stop tags shared -> strong connection
                // 2. Exactly one non-stop tag shared -> connect only if tag is not a high-frequency hub
                //    (present in > 40% of notes when notes >= 5) AND tag sets are focused (Jaccard >= 0.33)
                val isBroadTag = notes.size >= 5 && shared.all { (tagDocFrequency[it] ?: 0).toFloat() / notes.size > 0.40f }

                if (shared.size >= 2) {
                    val label = "shares #${shared.first()}" + if (shared.size > 1) " (+${shared.size - 1})" else ""
                    val strength = (0.55f + 0.20f * jaccard + (shared.size - 1) * 0.05f).coerceAtMost(0.85f)
                    addEdgeIfAbsent(notes[i].id, notes[j].id, label, strength)
                } else if (shared.size == 1 && !isBroadTag && (jaccard >= 0.33f || (tagsA.size <= 2 && tagsB.size <= 2))) {
                    val sharedTag = shared.first()
                    val strength = (0.50f + 0.20f * jaccard).coerceIn(0.50f, 0.70f)
                    addEdgeIfAbsent(notes[i].id, notes[j].id, "shares #$sharedTag", strength)
                }
            }
        }

        // ── Tier 3: BM25 Relevance Overlap (Calibrated & Multi-Term Guarded) ──
        val candidatePairs = findCandidatePairs(notes, maxPairs = minOf(notes.size * 2, 40))
        for ((noteA, noteB, score) in candidatePairs) {
            val normalizedStrength = (score / 6.0f).coerceIn(0.40f, 0.85f)
            addEdgeIfAbsent(noteA.id, noteB.id, "related to", normalizedStrength)
        }

        // ── Tier 4: Optional LLM Semantic Enrichment ─────────────────────────
        if (includeAiEnrichment) {
            val model = if (llamaCppService.isNativeLibLoaded()) getActiveModel() else null
            if (model != null && candidatePairs.isNotEmpty()) {
                val topPairs = candidatePairs.take(6)
                for ((noteA, noteB, _) in topPairs) {
                    val connections = analyzePair(model, noteA.plainText, noteA.title, noteB.plainText, noteB.title)
                    for (conn in connections) {
                        val label = conn["label"] as? String ?: "related to"
                        val strength = (conn["strength"] as? Number)?.toFloat() ?: 0.6f
                        val targetTitle = conn["targetTitle"] as? String ?: ""
                        val targetId = if (targetTitle.equals(noteB.title, ignoreCase = true)) noteB.id else noteA.id
                        val sourceId = if (targetId == noteB.id) noteA.id else noteB.id
                        val key = if (sourceId < targetId) sourceId to targetId else targetId to sourceId
                        edgesMap[key] = GraphEdge(from = sourceId, to = targetId, label = label, strength = strength)
                    }
                }
            }
        }

        val allEdges = edgesMap.values.toList()

        // Synchronize relationships to note_links Room table and note.relatedNotes
        storeRelationships(notes, allEdges)

        GraphData(nodes = nodes, edges = allEdges)
    }

    /**
     * Enriches existing graph connections using on-device LLM reasoning in the background.
     * Progressively classifies relationships as 'supports', 'contradicts', or 'builds on'.
     */
    suspend fun enrichGraphWithAi(
        currentData: GraphData,
        onProgress: ((current: Int, total: Int) -> Unit)? = null
    ): GraphData = withContext(Dispatchers.IO) {
        val model = if (llamaCppService.isNativeLibLoaded()) getActiveModel() else null
        if (model == null) return@withContext currentData

        val notes = repository.getAllNotesSync()
        if (notes.size < 2) return@withContext currentData

        val candidatePairs = findCandidatePairs(notes, maxPairs = 6)
        if (candidatePairs.isEmpty()) return@withContext currentData

        val edgesMap = currentData.edges.associateBy {
            if (it.from < it.to) it.from to it.to else it.to to it.from
        }.toMutableMap()

        val total = candidatePairs.size
        candidatePairs.forEachIndexed { index, (noteA, noteB, _) ->
            onProgress?.invoke(index + 1, total)
            val connections = analyzePair(model, noteA.plainText, noteA.title, noteB.plainText, noteB.title)
            for (conn in connections) {
                val label = conn["label"] as? String ?: "related to"
                val strength = (conn["strength"] as? Number)?.toFloat() ?: 0.6f
                val targetTitle = conn["targetTitle"] as? String ?: ""
                val targetId = if (targetTitle.equals(noteB.title, ignoreCase = true)) noteB.id else noteA.id
                val sourceId = if (targetId == noteB.id) noteA.id else noteB.id
                val key = if (sourceId < targetId) sourceId to targetId else targetId to sourceId
                edgesMap[key] = GraphEdge(from = sourceId, to = targetId, label = label, strength = strength)
            }
        }

        val enrichedEdges = edgesMap.values.toList()
        storeRelationships(notes, enrichedEdges)
        GraphData(nodes = currentData.nodes, edges = enrichedEdges)
    }

    /**
     * Parse tags JSON string into a list.
     */
    private fun parseTags(tagsJson: String): List<String> {
        return try {
            val arr = JSONArray(tagsJson)
            (0 until arr.length()).map { arr.getString(it) }
        } catch (_: Exception) {
            emptyList()
        }
    }

    /**
     * Find candidate pairs by BM25 relevance scoring.
     * For each note, extracts signature terms scored by TF-IDF and scores all other notes.
     * Enforces strict topical gates: rejects spurious single-word overlaps and noise.
     */
    private fun findCandidatePairs(
        notes: List<Note>,
        maxPairs: Int
    ): List<Triple<Note, Note, Float>> {
        val tokenized = notes.map { Bm25Scorer.tokenize(it.plainText) }
        val idf = Bm25Scorer.computeIdf(tokenized)
        val avgDocLength = tokenized.map { it.size }.average().toFloat().coerceAtLeast(1f)
        val notesById = notes.associateBy { it.id }

        // Score terms by TF-IDF if corpus is large enough (> 3 notes), or by frequency for small corpora
        val noteTopTerms = tokenized.map { tokens ->
            if (notes.size <= 3) {
                Bm25Scorer.topTerms(tokens, n = 8)
            } else {
                Bm25Scorer.topTermsByIdf(tokens, idf, n = 8).ifEmpty {
                    Bm25Scorer.topTerms(tokens, n = 8)
                }
            }
        }
        val noteTermFreqs = tokenized.map { tokens ->
            tokens.groupBy { it }.mapValues { it.value.size }
        }

        val seen = mutableSetOf<Pair<String, String>>()
        val pairs = mutableListOf<Triple<String, String, Float>>()

        // Calibrated BM25 floor: single common words score ~0.5 - 1.2 in small corpora.
        // True semantic relevance in normal corpora requires multiple term co-occurrences or score >= 2.2.
        val minScoreThreshold = if (notes.size <= 3) 0.5f else 2.2f

        for (i in notes.indices) {
            val queryTerms = noteTopTerms[i]
            if (queryTerms.isEmpty()) continue

            for (j in notes.indices) {
                if (i == j) continue
                val pairKey = if (notes[i].id < notes[j].id)
                    notes[i].id to notes[j].id else notes[j].id to notes[i].id
                if (pairKey in seen) continue

                val docFreqs = noteTermFreqs[j]
                val matchedTerms = queryTerms.count { it in docFreqs }
                if (matchedTerms == 0) continue

                val score = Bm25Scorer.score(
                    queryTerms = queryTerms,
                    docTermFreqs = docFreqs,
                    docLength = tokenized[j].size,
                    avgDocLength = avgDocLength,
                    idf = idf
                )

                // Precision gate:
                // For small corpora (<= 3 notes), score >= 0.5 with at least 1 matched term
                // For realistic corpora (> 3 notes), score >= 2.2 with at least 2 distinct matched terms, OR score >= 3.5
                val qualifies = if (notes.size <= 3) {
                    score >= minScoreThreshold && matchedTerms >= 1
                } else {
                    (score >= minScoreThreshold && matchedTerms >= 2) || (score >= 3.5f)
                }

                if (qualifies) {
                    pairs.add(Triple(pairKey.first, pairKey.second, score))
                    seen.add(pairKey)
                }
            }
        }

        return pairs.sortedByDescending { it.third }
            .take(maxPairs)
            .mapNotNull { (idA, idB, score) ->
                val a = notesById[idA]
                val b = notesById[idB]
                if (a != null && b != null) Triple(a, b, score) else null
            }
    }

    /**
     * Ask the LLM to find connections between two notes.
     */
    private suspend fun analyzePair(
        model: ModelInfo,
        textA: String, titleA: String,
        textB: String, titleB: String
    ): List<Map<String, Any?>> {
        val systemPrompt = "You are a knowledge graph builder. Analyze whether two notes are related. " +
            "If they are, return a JSON array of connections. Each connection has: " +
            "\"label\" (one of: 'related to', 'references', 'supports', 'contradicts', 'builds on'), " +
            "\"strength\" (0.3-1.0), and \"targetTitle\" (the title of the related note). " +
            "If no meaningful connection exists, return an empty array []. " +
            "Return ONLY the JSON array, no other text."

        val userPrompt = "Note A: \"$titleA\"\n${textA.take(1500)}\n\n" +
            "Note B: \"$titleB\"\n${textB.take(1500)}\n\n" +
            "Are these notes related? Return JSON array."

        val prompt = PromptBuilder.buildPrompt(model.promptFormat, systemPrompt, userPrompt, model)
        val result = llamaCppService.generate(prompt, maxTokens = 500) ?: return emptyList()
        val cleaned = AiOutputProcessor.process(result)

        return parseConnections(cleaned)
    }

    private fun parseConnections(json: String): List<Map<String, Any?>> {
        return try {
            val jsonStr = json.replace(Regex("```json\\s*"), "").replace(Regex("```\\s*"), "").trim()
            val arr = JSONArray(jsonStr)
            (0 until arr.length()).map { i ->
                val obj = arr.getJSONObject(i)
                mapOf(
                    "label" to obj.optString("label", "related to"),
                    "strength" to obj.optDouble("strength", 0.5),
                    "targetTitle" to obj.optString("targetTitle", "")
                )
            }
        } catch (_: Exception) {
            emptyList()
        }
    }

    private suspend fun storeRelationships(
        notes: List<Note>,
        edges: List<GraphEdge>
    ) {
        val notesById = notes.associateBy { it.id }
        for (note in notes) {
            val related = edges.filter { it.from == note.id || it.to == note.id }
                .map { edge ->
                    val targetId = if (edge.from == note.id) edge.to else edge.from
                    val targetTitle = notesById[targetId]?.title ?: ""
                    JSONObject().apply {
                        put("noteId", targetId)
                        put("title", targetTitle)
                        put("label", edge.label)
                        put("strength", edge.strength)
                    }
                }
            val updated = note.copy(relatedNotes = JSONArray(related).toString())
            if (updated.relatedNotes != note.relatedNotes) {
                repository.updateNoteMetadataOnly(updated)
            }
        }

        // Persist all unique edges to the encrypted note_links Room table,
        // and reconcile stale rule-generated links so removed connections don't linger forever
        val now = System.currentTimeMillis()
        val linkEntities = edges.map { edge ->
            val linkType = when {
                edge.label.contains("contradict", ignoreCase = true) -> "contradicts"
                edge.label.contains("support", ignoreCase = true) -> "supports"
                edge.label.contains("build", ignoreCase = true) -> "builds_on"
                edge.label.contains("link", ignoreCase = true) -> "references"
                else -> "related"
            }
            NoteLinkEntity(
                id = "${edge.from}_${edge.to}",
                sourceNoteId = edge.from,
                targetNoteId = edge.to,
                linkType = linkType,
                confidence = edge.strength,
                createdBy = if (edge.label == "links to") "user" else "rule",
                createdAt = now
            )
        }
        val activeRuleIds = linkEntities.filter { it.createdBy == "rule" }.map { it.id }
        try {
            if (activeRuleIds.isNotEmpty()) {
                noteLinkDao.deleteStaleRuleLinks(activeRuleIds)
            } else {
                noteLinkDao.deleteAllRuleLinks()
            }
            noteLinkDao.insertLinks(linkEntities)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to persist note links to Room", e)
        }
    }
}
