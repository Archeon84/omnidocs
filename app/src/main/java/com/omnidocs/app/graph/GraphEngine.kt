package com.omnidocs.app.graph

import android.util.Log
import com.omnidocs.app.ai.AiOutputProcessor
import com.omnidocs.app.ai.LlamaCppService
import com.omnidocs.app.ai.ModelDownloadManager
import com.omnidocs.app.ai.ModelInfo
import com.omnidocs.app.ai.ModelPreferences
import com.omnidocs.app.ai.PromptBuilder
import com.omnidocs.app.ai.PromptFormat
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
        val selectedId = modelPreferences.selectedModelId.first()
        val downloaded = modelDownloadManager.getDownloadedModels()
        downloaded.find { it.id == selectedId && it.isDownloaded }
            ?: downloaded.firstOrNull { it.isDownloaded }
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
        val model = getActiveModel()

        if (model != null) {
            // Find candidate pairs by word overlap
            val candidates = findCandidatePairs(notes, maxPairs = minOf(notes.size * 2, 50))

            for ((noteA, noteB) in candidates) {
                val connections = analyzePair(model.promptFormat, noteA.plainText, noteA.title, noteB.plainText, noteB.title)
                for (conn in connections) {
                    val targetId = if (conn["targetTitle"] == noteB.title) noteB.id else noteA.id
                    edges.add(GraphEdge(
                        from = noteA.id,
                        to = targetId,
                        label = conn["label"] as? String ?: "related to",
                        strength = (conn["strength"] as? Number)?.toFloat() ?: 0.5f
                    ))
                }
            }
        }

        // Store relationships back to notes
        storeRelationships(notes, edges)

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
     * Find candidate pairs by word overlap (cheap pre-filter before LLM).
     */
    private fun findCandidatePairs(
        notes: List<Note>,
        maxPairs: Int
    ): List<Pair<Note, Note>> {
        val stopWords = setOf("the", "a", "an", "is", "are", "was", "were", "be", "been",
            "being", "have", "has", "had", "do", "does", "did", "will", "would", "could",
            "should", "may", "might", "shall", "can", "to", "of", "in", "for", "on", "with",
            "at", "by", "from", "as", "into", "through", "during", "before", "after", "and",
            "but", "or", "nor", "not", "so", "yet", "both", "either", "neither", "each",
            "every", "all", "any", "few", "more", "most", "other", "some", "such", "no",
            "only", "own", "same", "than", "too", "very", "just", "that", "this", "these",
            "those", "i", "me", "my", "we", "our", "you", "your", "he", "him", "his",
            "she", "her", "it", "its", "they", "them", "their", "what", "which", "who",
            "whom", "when", "where", "why", "how", "if", "then", "else", "because")

        fun getWords(text: String): Set<String> {
            return text.lowercase()
                .replace(Regex("[^a-z0-9\\s]"), "")
                .split("\\s+".toRegex())
                .filter { it.length > 2 && it !in stopWords }
                .toSet()
        }

        val noteWords = notes.map { it.id to getWords(it.plainText) }.toMap()
        val pairs = mutableListOf<Triple<String, String, Float>>()

        for (i in notes.indices) {
            for (j in i + 1 until notes.size) {
                val wordsA = noteWords[notes[i].id] ?: emptySet()
                val wordsB = noteWords[notes[j].id] ?: emptySet()
                if (wordsA.isEmpty() || wordsB.isEmpty()) continue
                val overlap = wordsA.intersect(wordsB).size.toFloat() /
                    minOf(wordsA.size, wordsB.size).coerceAtLeast(1)
                if (overlap > 0.1f) {
                    pairs.add(Triple(notes[i].id, notes[j].id, overlap))
                }
            }
        }

        return pairs.sortedByDescending { it.third }
            .take(maxPairs)
            .map { (idA, idB, _) ->
                notes.first { it.id == idA } to notes.first { it.id == idB }
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
            if (related.isNotEmpty()) {
                val updated = note.copy(relatedNotes = JSONArray(related).toString())
                repository.updateNote(updated)
            }
        }
    }
}
