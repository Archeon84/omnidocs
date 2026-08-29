package com.omnidocs.app.agent

import com.omnidocs.app.data.local.ContentBlockDao
import com.omnidocs.app.data.local.NoteDao
import com.omnidocs.app.search.VectorSearch
import com.omnidocs.app.search.VectorSearch.SearchResult
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
 * parallel BM25 and vector retrieval combined with Reciprocal Rank Fusion (RRF).
 */
@Singleton
class RetrievalAgent @Inject constructor(
    private val vectorSearch: VectorSearch,
    private val noteDao: NoteDao,
    private val contentBlockDao: ContentBlockDao
) : Agent {

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
     * Executes hybrid retrieval and applies Reciprocal Rank Fusion.
     */
    suspend fun retrieveEvidence(
        query: String,
        bm25Weight: Float,
        semanticWeight: Float,
        topK: Int
    ): List<EvidenceCandidate> {
        val searchResults: List<SearchResult> = vectorSearch.hybridSearch(
            query = query,
            bm25Weight = bm25Weight,
            semanticWeight = semanticWeight,
            limit = topK * 2
        )

        val candidates = mutableListOf<EvidenceCandidate>()

        for (result in searchResults.take(topK)) {
            val note = result.note
            // Retrieve fine-grained content blocks if available
            val blocks = contentBlockDao.getBlocksForNoteSync(note.id)

            if (blocks.isNotEmpty()) {
                // Find most relevant content blocks within the note
                for (block in blocks.take(3)) {
                    candidates.add(
                        EvidenceCandidate(
                            noteId = note.id,
                            noteTitle = note.title,
                            text = block.content,
                            blockId = block.id,
                            startOffset = block.startOffset,
                            endOffset = block.endOffset,
                            score = result.score
                        )
                    )
                }
            } else {
                // Fallback to note text slice
                candidates.add(
                    EvidenceCandidate(
                        noteId = note.id,
                        noteTitle = note.title,
                        text = note.plainText.take(600),
                        blockId = null,
                        startOffset = 0,
                        endOffset = note.plainText.length.coerceAtMost(600),
                        score = result.score
                    )
                )
            }
        }

        return candidates.distinctBy { "${it.noteId}:${it.text.hashCode()}" }.take(topK)
    }
}
