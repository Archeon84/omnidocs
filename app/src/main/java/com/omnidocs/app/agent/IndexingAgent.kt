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
            // One malformed block must not discard the whole batch.
            val obj = try {
                jsonArray.getJSONObject(i)
            } catch (e: Exception) {
                android.util.Log.w("IndexingAgent", "Skipping malformed block at index $i", e)
                continue
            }
            val blockId = UUID.randomUUID().toString()
            val content = obj.optString("content", "")
            if (content.isBlank()) continue

            val block = ContentBlockEntity(
                id = blockId,
                sourceDocumentId = sourceDocumentId,
                noteId = noteId,
                blockIndex = obj.optIntLenient("blockIndex") ?: i,
                blockType = obj.optString("blockType", "paragraph"),
                content = content,
                pageNumber = obj.optIntLenient("pageNumber"),
                startOffset = obj.optIntLenient("startOffset"),
                endOffset = obj.optIntLenient("endOffset"),
                boundingBoxJson = obj.optString("boundingBoxJson").takeIf { it.isNotBlank() },
                confidence = obj.optDoubleLenient("confidence")?.toFloat(),
                createdAt = now
            )
            contentBlocks.add(block)
        }

        // 1. Insert content blocks into Room
        if (contentBlocks.isNotEmpty()) {
            contentBlockDao.insertBlocks(contentBlocks)
        }

        // 2. Embed note passages using NoteBlockAdapter via embedAndStoreNotePassages.
        // Legacy full-note vectors ("note:<id>:<model>") are superseded by passages.
        var embeddingCount = 0
        val activeModel = try {
            embeddingService.activeModelName()
        } catch (e: Exception) {
            android.util.Log.w("IndexingAgent", "Active model lookup failed, tagging n-gram", e)
            "ngram-hash-v1"
        }

        // Embed note passages
        if (fullText.isNotBlank()) {
            try {
                val stored = embeddingService.embedAndStoreNotePassages(
                    noteId = noteId,
                    title = "",
                    content = fullText,
                    modelName = activeModel
                )
                embeddingCount = stored.size
            } catch (e: Exception) {
                android.util.Log.w("IndexingAgent", "Note-level passage embedding failed for note $noteId", e)
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
