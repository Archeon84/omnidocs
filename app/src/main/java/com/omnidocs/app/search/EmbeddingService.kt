package com.omnidocs.app.search

import android.util.Log
import com.omnidocs.app.ai.EmbeddingEngine
import com.omnidocs.app.ai.ModelDownloadManager
import com.omnidocs.app.ai.ModelPreferences
import com.omnidocs.app.ai.NativeMemoryManager
import com.omnidocs.app.ai.NativeModelSlot
import com.omnidocs.app.ai.resolveActiveEmbeddingModel
import com.omnidocs.app.data.local.EmbeddingDao
import com.omnidocs.app.data.local.entity.EmbeddingEntity
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.sqrt

private const val TAG = "EmbeddingService"
private const val EMBEDDING_DIM = 128 // n-gram fallback embedding dimension
private const val NGRAM_SIZE = 3
const val NGRAM_MODEL_NAME = "ngram-hash-v1"

/** TTL for cached query embeddings (ms). Queries are short-lived per session. */
private const val QUERY_CACHE_TTL_MS = 60_000L
private const val QUERY_CACHE_MAX_ENTRIES = 32
/** Cap for cached deserialized row vectors (evicted wholesale when full). */
private const val VECTOR_CACHE_MAX_ENTRIES = 4000

/**
 * Generates and stores text embeddings for semantic search.
 *
 * When a real embedding model (e.g. multilingual-e5-small) is downloaded, this
 * delegates to [EmbeddingEngine] and stores the model's vectors. Otherwise it
 * falls back to a deterministic character n-gram hash so search still works
 * without a model. Vectors are tagged with their producing model's name so
 * consumers can filter by model and never mix dimensions.
 */
@Singleton
class EmbeddingService @Inject constructor(
    private val embeddingDao: EmbeddingDao,
    private val embeddingEngine: EmbeddingEngine,
    private val modelDownloadManager: ModelDownloadManager,
    private val modelPreferences: ModelPreferences,
    private val noteBlockAdapter: com.omnidocs.app.ai.NoteBlockAdapter,
    private val nativeMemoryManager: NativeMemoryManager
) {
    /** Cache key: (lowercase query, model name). Value: (vector, timestamp). */
    private data class CacheEntry(val vector: FloatArray, val createdAt: Long)

    private val queryCache = ConcurrentHashMap<String, CacheEntry>()
    /**
     * Deserialized row vectors keyed by row id. Entries self-invalidate: a
     * reindex REPLACEs the row with a newer createdAt, so a key match on
     * (id, createdAt) guarantees freshness. Callers must NOT mutate the
     * returned array (shared reference).
     */
    private data class VectorCacheEntry(val createdAt: Long, val vector: FloatArray)
    private val vectorCache = ConcurrentHashMap<String, VectorCacheEntry>()
    /**
     * Name of the embedding model that is currently active: the downloaded
     * embedding model's id, or [NGRAM_MODEL_NAME] when none is available.
     */
    suspend fun activeModelName(): String {
        val model = resolveActiveEmbeddingModel(modelPreferences, modelDownloadManager)
            ?: return NGRAM_MODEL_NAME
        // Include dimension so queries match the dim-suffixed stored rows.
        // Slot-serialized: loading embeddings while STT/LLM holds memory risks OOM.
        val loaded = embeddingEngine.isLoaded() || nativeMemoryManager.withSlot(
            NativeModelSlot.EMBEDDINGS,
            NativeMemoryManager.OWNER_EMBEDDINGS
        ) {
            embeddingEngine.load(modelDownloadManager.getModelPath(model))
        }
        val dim = if (loaded) embeddingEngine.dim() else 0
        return if (dim > 0) "${model.id}_$dim" else model.id
    }

    /**
     * Chunk a note into structure-aware passages using [NoteBlockAdapter] and store
     * embeddings for each passage.
     *
     * Incremental: per-chunk [chunkHash] comparison skips re-embedding unchanged
     * passages (autosave fires every few seconds), upserts changed ones, and
     * deletes tail rows when the note shrinks. Rows from other models are left
     * alone. An emptied note deletes its rows so stale vectors never linger.
     */
    suspend fun embedAndStoreNotePassages(
        noteId: String,
        title: String,
        content: String,
        modelName: String? = null
    ): List<EmbeddingEntity> {
        if (content.isBlank()) {
            embeddingDao.deleteEmbeddingsBySource("note", noteId)
            return emptyList()
        }

        // Use NoteBlockAdapter for Markdown-aware semantic chunking
        val segments = noteBlockAdapter.chunkText(noteId, title, content)
        if (segments.isEmpty()) {
            embeddingDao.deleteEmbeddingsBySource("note", noteId)
            return emptyList()
        }

        val resolvedModel = modelName ?: activeModelName()
        val existing = embeddingDao.getEmbeddingsBySource("note", noteId)
            .filter { it.modelName == resolvedModel && isPassageRow(it.id, noteId) }
            .associateBy({ it.id }, { it.chunkHash })
        val liveIds = mutableSetOf<String>()

        val entities = mutableListOf<EmbeddingEntity>()
        for ((index, segment) in segments.withIndex()) {
            val id = "note:$noteId:$index:$resolvedModel"
            liveIds.add(id)
            val hash = segment.content.hashCode().toString()
            if (existing[id] == hash) continue // unchanged passage: keep stored vector
            val (vector, _) = embedInternal(segment.content, isQuery = false)
            entities.add(
                EmbeddingEntity(
                    id = id,
                    sourceType = "note",
                    sourceId = noteId,
                    chunkHash = hash,
                    modelName = resolvedModel,
                    embeddingVector = serializeVector(vector),
                    createdAt = System.currentTimeMillis(),
                    chunkText = segment.content,
                    sectionHeader = segment.headerContext,
                    startOffset = segment.startOffset,
                    endOffset = segment.endOffset,
                    chunkIndex = index
                )
            )
        }

        if (entities.isNotEmpty()) {
            embeddingDao.insertEmbeddings(entities)
        }
        val staleIds = existing.keys.filter { it !in liveIds }
        // Purge legacy full-text rows ("note:<id>:<model>"): superseded by
        // passages now that replacements exist, and never invalidated on edit
        // — they rank notes on content the note no longer has.
        val legacyIds = embeddingDao.getEmbeddingsBySource("note", noteId)
            .filter { isLegacyRow(it.id, noteId) }
            .map { it.id }
        val deadIds = (staleIds + legacyIds).distinct()
        if (deadIds.isNotEmpty()) {
            embeddingDao.deleteEmbeddingsByIds(deadIds)
        }
        return embeddingDao.getEmbeddingsBySource("note", noteId)
            .filter { it.modelName == resolvedModel && isPassageRow(it.id, noteId) }
    }

    /** True for passage rows ("note:<id>:<index>:<model>"); excludes the legacy
     * full-text row ("note:<id>:<model>") written by other indexers. */
    private fun isPassageRow(rowId: String, noteId: String): Boolean {
        val prefix = "note:$noteId:"
        if (!rowId.startsWith(prefix)) return false
        val rest = rowId.removePrefix(prefix).split(":")
        return rest.size == 2 && rest[0].all { it.isDigit() }
    }

    /** True for legacy full-text rows ("note:<id>:<model>", single segment). */
    private fun isLegacyRow(rowId: String, noteId: String): Boolean {
        val prefix = "note:$noteId:"
        if (!rowId.startsWith(prefix)) return false
        return rowId.removePrefix(prefix).split(":").size == 1
    }

    /**
     * Generate and store an embedding for a text chunk (document context: uses
     * the "passage:" prefix required by e5-family models). Idempotent: the row id
     * is deterministic so re-saving a note upserts instead of accumulating rows.
     *
     * The row is ALWAYS tagged with the model that actually produced the
     * vector ([usedModelName]), never the caller's assumption: filing a
     * 128-dim n-gram fallback under a real-model tag silently poisons the
     * semantic leg (dim mismatch → cosine 0 → invisible recall loss).
     */
    suspend fun embedAndStore(
        sourceType: String,
        sourceId: String,
        text: String,
        modelName: String? = null
    ): EmbeddingEntity? {
        if (text.isBlank()) return null

        val (vector, usedModelName) = embedInternal(text, isQuery = false)
        if (modelName != null && modelName != usedModelName) {
            Log.w(TAG, "embedAndStore: caller assumed $modelName but vector is $usedModelName; tagging truthfully")
        }

        val entity = EmbeddingEntity(
            id = "$sourceType:$sourceId:$usedModelName",
            sourceType = sourceType,
            sourceId = sourceId,
            chunkHash = text.hashCode().toString(),
            modelName = usedModelName,
            embeddingVector = serializeVector(vector),
            createdAt = System.currentTimeMillis(),
            chunkText = text,
            sectionHeader = null,
            startOffset = 0,
            endOffset = text.length,
            chunkIndex = 0
        )

        embeddingDao.insertEmbedding(entity)
        return entity
    }

    /**
     * Embed a query string ("query:" prefix for e5 models). Returns a vector of
     * the active model's dimension, or the n-gram fallback dimension.
     * Results are cached for 60s to avoid redundant inference on repeated/similar queries.
     */
    suspend fun generateEmbedding(text: String): FloatArray {
        val modelName = activeModelName()
        val cacheKey = "${text.lowercase().trim()}|$modelName"
        val now = System.currentTimeMillis()

        queryCache[cacheKey]?.let { entry ->
            if (now - entry.createdAt < QUERY_CACHE_TTL_MS) {
                // Defensive copy: the cached array is shared state.
                return entry.vector.copyOf()
            }
        }

        val vector = embedInternal(text, isQuery = true).first

        // Evict oldest if over capacity
        if (queryCache.size >= QUERY_CACHE_MAX_ENTRIES) {
            val oldest = queryCache.entries.minByOrNull { it.value.createdAt }?.key
            oldest?.let { queryCache.remove(it) }
        }
        queryCache[cacheKey] = CacheEntry(vector, now)
        return vector
    }

    /**
     * Embed a document/passage string ("passage:" prefix). Mirrors
     * [generateEmbedding] but for document context.
     */
    suspend fun generateDocumentEmbedding(text: String): FloatArray {
        return embedInternal(text, isQuery = false).first
    }

    /**
     * Core embedding path. Returns the vector and the model name that produced it,
     * so callers store a name consistent with the actual vector dimension.
     */
    private suspend fun embedInternal(text: String, isQuery: Boolean): Pair<FloatArray, String> {
        val model = resolveActiveEmbeddingModel(modelPreferences, modelDownloadManager)
        Log.d(TAG, "embedInternal: resolved model=${model?.id ?: "null"}, isQuery=$isQuery")
        if (model == null) {
            Log.w(TAG, "No active embedding model found, falling back to n-gram")
            return ngramEmbedding(text) to NGRAM_MODEL_NAME
        }

        val modelPath = modelDownloadManager.getModelPath(model)
        Log.d(TAG, "embedInternal: loading model from $modelPath")
        val loaded = embeddingEngine.isLoaded() || nativeMemoryManager.withSlot(
            NativeModelSlot.EMBEDDINGS,
            NativeMemoryManager.OWNER_EMBEDDINGS
        ) {
            embeddingEngine.load(modelPath)
        }
        if (!loaded) {
            Log.w(TAG, "Embedding model failed to load from $modelPath, falling back to n-gram")
            return ngramEmbedding(text) to NGRAM_MODEL_NAME
        }
        Log.d(TAG, "embedInternal: model loaded OK, generating embedding")

        val prefixed = if (isQuery) "query: $text" else "passage: $text"
        val vector = embeddingEngine.embed(prefixed)
        return if (vector != null) {
            // Include dimension in the model name tag so old embeddings from a
            // different model/quantization (same id, different dim) are never
            // mixed in — cosineSimilarity would return 0f on size mismatch.
            val dim = embeddingEngine.dim()
            Log.d(TAG, "embedInternal: success, dim=$dim")
            vector to "${model.id}_$dim"
        } else {
            Log.w(TAG, "Embedding model returned null, falling back to n-gram")
            ngramEmbedding(text) to NGRAM_MODEL_NAME
        }
    }

    /**
     * Deterministic character n-gram hashing fallback (no model). Returns an
     * L2-normalized vector of size [EMBEDDING_DIM].
     */
    private fun ngramEmbedding(text: String): FloatArray {
        val vector = FloatArray(EMBEDDING_DIM)
        val normalized = text.lowercase().replace(Regex("[^a-z0-9\\s]"), "")

        for (i in 0..normalized.length - NGRAM_SIZE) {
            val ngram = normalized.substring(i, i + NGRAM_SIZE)
            val hash = ngram.hashCode()
            val idx = ((hash % EMBEDDING_DIM) + EMBEDDING_DIM) % EMBEDDING_DIM
            vector[idx] += 1.0f
        }

        val words = normalized.split(Regex("\\s+")).filter { it.length > 2 }
        for (word in words) {
            val hash = word.hashCode()
            val idx = ((hash % EMBEDDING_DIM) + EMBEDDING_DIM) % EMBEDDING_DIM
            vector[idx] += 2.0f
        }

        val norm = sqrt(vector.sumOf { (it * it).toDouble() }).toFloat()
        if (norm > 0f) {
            for (i in vector.indices) {
                vector[i] /= norm
            }
        }

        return vector
    }

    /**
     * Compute cosine similarity between two vectors.
     */
    fun cosineSimilarity(a: FloatArray, b: FloatArray): Float {
        if (a.size != b.size) return 0f
        var dot = 0f
        var normA = 0f
        var normB = 0f
        for (i in a.indices) {
            dot += a[i] * b[i]
            normA += a[i] * a[i]
            normB += b[i] * b[i]
        }
        val denominator = sqrt(normA) * sqrt(normB)
        return if (denominator > 0f) dot / denominator else 0f
    }

    /**
     * Serialize float array to byte array for storage.
     */
    private fun serializeVector(vector: FloatArray): ByteArray {
        val buffer = java.nio.ByteBuffer.allocate(vector.size * 4)
        buffer.order(java.nio.ByteOrder.LITTLE_ENDIAN)
        for (v in vector) {
            buffer.putFloat(v)
        }
        return buffer.array()
    }

    /**
     * Deserialize byte array back to float array, cached by row id.
     * Avoids re-deserializing the whole table on every keystroke search.
     * Do NOT mutate the returned array.
     */
    fun cachedVector(id: String, createdAt: Long, bytes: ByteArray): FloatArray {
        vectorCache[id]?.let { entry ->
            if (entry.createdAt == createdAt) return entry.vector
        }
        val vector = deserializeVector(bytes)
        if (vectorCache.size >= VECTOR_CACHE_MAX_ENTRIES) vectorCache.clear()
        vectorCache[id] = VectorCacheEntry(createdAt, vector)
        return vector
    }

    /**
     * Deserialize byte array back to float array.
     */
    fun deserializeVector(bytes: ByteArray): FloatArray {
        val buffer = java.nio.ByteBuffer.wrap(bytes)
        buffer.order(java.nio.ByteOrder.LITTLE_ENDIAN)
        val vector = FloatArray(bytes.size / 4)
        for (i in vector.indices) {
            vector[i] = buffer.float
        }
        return vector
    }
}
