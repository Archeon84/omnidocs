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
    private val repository: NotesRepository
) {
    private suspend fun getActiveModel(): ModelInfo? = withContext(Dispatchers.IO) {
        resolveActiveModel(modelPreferences, modelDownloadManager)
    }

    /**
     * Build a GraphData from all notes. Uses word overlap to find candidate
     * neighbors, then asks the LLM to confirm and label relationships.
     */
    suspend fun buildGraph(): GraphData = withContext(Dispatchers.IO) {
        val notes = repository.getAllNotesSync()
        if (notes.size < 2) {
            return@withContext GraphData(
                nodes = notes.map { note ->
                    val wordCount = note.plainText.split("\\s+".toRegex()).size
                    val tags = parseTags(note.tags)
                    GraphNode(note.id, note.title, wordCount, tags)
                },
                edges = emptyList()
            )
        }

        val nodes = notes.map { note ->
            val wordCount = note.plainText.split("\\s+".toRegex()).size
            val tags = parseTags(note.tags)
            GraphNode(note.id, note.title, wordCount, tags)
        }

        val edges = mutableListOf<GraphEdge>()

        // Only attempt LLM-based edge analysis if the native lib loaded successfully
        val model = if (llamaCppService.isNativeLibLoaded()) getActiveModel() else null

        if (model != null) {
            // Find candidate pairs by word overlap
            val candidates = findCandidatePairs(notes, maxPairs = minOf(notes.size * 2, 50))

            for ((noteA, noteB) in candidates) {
                val connections = analyzePair(model.promptFormat, noteA.plainText, noteA.title, noteB.plainText, noteB.title)
                for (conn in connections) {
                    val targetTitle = conn["targetTitle"] as? String ?: ""
                    val targetId = if (targetTitle.equals(noteB.title, ignoreCase = true)) noteB.id else noteA.id
                    edges.add(GraphEdge(
                        from = noteA.id,
                        to = targetId,
                        label = conn["label"] as? String ?: "related to",
                        strength = (conn["strength"] as? Number)?.toFloat() ?: 0.5f
                    ))
                }
            }

            // Store relationships back to notes. Must stay inside the model branch:
            // when no model is available `edges` is empty and storing it would wipe
            // every note's previously-saved relatedNotes.
            storeRelationships(notes, edges)
        }

        GraphData(nodes = nodes, edges = edges)
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
     * For each note, extracts top terms and scores all other notes using BM25.
     * Deduplicates pairs and keeps the highest-scoring direction.
     */
    private fun findCandidatePairs(
        notes: List<Note>,
        maxPairs: Int
    ): List<Pair<Note, Note>> {
        val tokenized = notes.map { Bm25Scorer.tokenize(it.plainText) }
        val idf = Bm25Scorer.computeIdf(tokenized)
        val avgDocLength = tokenized.map { it.size }.average().toFloat().coerceAtLeast(1f)
        val notesById = notes.associateBy { it.id }

        // Precompute term frequencies and top terms for each note
        val noteTopTerms = tokenized.map { tokens ->
            Bm25Scorer.topTerms(tokens, n = 10)
        }
        val noteTermFreqs = tokenized.map { tokens ->
            tokens.groupBy { it }.mapValues { it.value.size }
        }

        val seen = mutableSetOf<Pair<String, String>>()
        val pairs = mutableListOf<Triple<String, String, Float>>()

        for (i in notes.indices) {
            val queryTerms = noteTopTerms[i]
            if (queryTerms.isEmpty()) continue

            for (j in notes.indices) {
                if (i == j) continue
                val pairKey = if (notes[i].id < notes[j].id)
                    notes[i].id to notes[j].id else notes[j].id to notes[i].id
                if (pairKey in seen) continue

                val score = Bm25Scorer.score(
                    queryTerms = queryTerms,
                    docTermFreqs = noteTermFreqs[j],
                    docLength = tokenized[j].size,
                    avgDocLength = avgDocLength,
                    idf = idf
                )
                if (score > 0.5f) {
                    pairs.add(Triple(pairKey.first, pairKey.second, score))
                    seen.add(pairKey)
                }
            }
        }

        return pairs.sortedByDescending { it.third }
            .take(maxPairs)
            .mapNotNull { (idA, idB, _) ->
                val a = notesById[idA]
                val b = notesById[idB]
                if (a != null && b != null) a to b else null
            }
    }

    /**
     * Ask the LLM to find connections between two notes.
     */
    private suspend fun analyzePair(
        format: PromptFormat,
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

        val prompt = PromptBuilder.buildPrompt(format, systemPrompt, userPrompt)
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
                repository.updateNote(updated)
            }
        }
    }
}
