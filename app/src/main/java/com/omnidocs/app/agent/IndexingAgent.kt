package com.omnidocs.app.agent

import com.omnidocs.app.data.local.ContentBlockDao
import com.omnidocs.app.data.local.entity.ContentBlockEntity
import com.omnidocs.app.search.EmbeddingService
import org.json.JSONArray
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Agent responsible for persisting structured [ContentBlockEntity] records with
 * character offsets, bounding-box provenance, and updating semantic vector embeddings.
 */
@Singleton
class IndexingAgent @Inject constructor(
    private val contentBlockDao: ContentBlockDao,
    private val embeddingService: EmbeddingService
) : Agent {

    override val id: String = "agent_indexing"

    override suspend fun execute(input: AgentInput, context: AgentContext): AgentResult {
        if (context.isCancelled()) {
            return AgentResult.PermanentFailure("Indexing cancelled by user")
        }

        val noteId = input.payload["noteId"]
            ?: return AgentResult.PermanentFailure("Missing 'noteId' in indexing payload")
        val sourceDocumentId = input.payload["sourceDocumentId"]
        val blocksJson = input.payload["blocksJson"] ?: "[]"
        val fullText = input.payload["fullText"] ?: ""

        val jsonArray = try {
            JSONArray(blocksJson)
        } catch (e: Exception) {
            JSONArray()
        }

        val now = System.currentTimeMillis()
        val contentBlocks = mutableListOf<ContentBlockEntity>()

        for (i in 0 until jsonArray.length()) {
            if (context.isCancelled()) {
                return AgentResult.PermanentFailure("Indexing cancelled during block processing")
            }
            val obj = jsonArray.getJSONObject(i)
            val blockId = UUID.randomUUID().toString()
            val content = obj.optString("content", "")
            if (content.isBlank()) continue

            val block = ContentBlockEntity(
                id = blockId,
                sourceDocumentId = sourceDocumentId,
                noteId = noteId,
                blockIndex = obj.optInt("blockIndex", i),
                blockType = obj.optString("blockType", "paragraph"),
                content = content,
                pageNumber = if (obj.has("pageNumber")) obj.getInt("pageNumber") else null,
                startOffset = if (obj.has("startOffset")) obj.getInt("startOffset") else null,
                endOffset = if (obj.has("endOffset")) obj.getInt("endOffset") else null,
                boundingBoxJson = obj.optString("boundingBoxJson").takeIf { it.isNotBlank() },
                confidence = if (obj.has("confidence")) obj.getDouble("confidence").toFloat() else null,
                createdAt = now
            )
            contentBlocks.add(block)
        }

        // 1. Insert content blocks into Room
        if (contentBlocks.isNotEmpty()) {
            contentBlockDao.insertBlocks(contentBlocks)
        }

        // 2. Compute embeddings for blocks & note
        var embeddingCount = 0
        val activeModel = try {
            embeddingService.activeModelName()
        } catch (e: Exception) {
            "ngram-hash-v1"
        }

        // Embed top structural content blocks
        for (block in contentBlocks.take(20)) {
            if (context.isCancelled()) break
            try {
                embeddingService.embedAndStore(
                    sourceType = "content_block",
                    sourceId = block.id,
                    text = block.content,
                    modelName = activeModel
                )
                embeddingCount++
            } catch (e: Exception) {
                // Log and continue gracefully
            }
        }

        // Embed overall note
        if (fullText.isNotBlank()) {
            try {
                embeddingService.embedAndStore(
                    sourceType = "note",
                    sourceId = noteId,
                    text = fullText,
                    modelName = activeModel
                )
                embeddingCount++
            } catch (e: Exception) {
                // Non-fatal
            }
        }

        return AgentResult.Success(
            payload = mapOf(
                "persistedBlocksCount" to contentBlocks.size,
                "indexedEmbeddingsCount" to embeddingCount,
                "activeModelName" to activeModel
            )
        )
    }
}
