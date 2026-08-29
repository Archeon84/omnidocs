package com.omnidocs.app.search

import android.util.Log
import com.omnidocs.app.ai.EmbeddingEngine
import com.omnidocs.app.ai.ModelDownloadManager
import com.omnidocs.app.ai.ModelPreferences
import com.omnidocs.app.ai.resolveActiveEmbeddingModel
import com.omnidocs.app.data.local.EmbeddingDao
import com.omnidocs.app.data.local.entity.EmbeddingEntity
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.sqrt

private const val TAG = "EmbeddingService"
private const val EMBEDDING_DIM = 128 // n-gram fallback embedding dimension
private const val NGRAM_SIZE = 3
const val NGRAM_MODEL_NAME = "ngram-hash-v1"

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
    private val modelPreferences: ModelPreferences
) {
    /**
     * Name of the embedding model that is currently active: the downloaded
     * embedding model's id, or [NGRAM_MODEL_NAME] when none is available.
     */
    suspend fun activeModelName(): String {
        val model = resolveActiveEmbeddingModel(modelPreferences, modelDownloadManager)
            ?: return NGRAM_MODEL_NAME
        // Include dimension so queries match the dim-suffixed stored rows.
        val loaded = embeddingEngine.isLoaded() || embeddingEngine.load(
            modelDownloadManager.getModelPath(model)
        )
        val dim = if (loaded) embeddingEngine.dim() else 0
        return if (dim > 0) "${model.id}_$dim" else model.id
    }

    /**
     * Generate and store an embedding for a text chunk (document context: uses
     * the "passage:" prefix required by e5-family models). Idempotent: the row id
     * is deterministic so re-saving a note upserts instead of accumulating rows.
     */
    suspend fun embedAndStore(
        sourceType: String,
        sourceId: String,
        text: String,
        modelName: String? = null
    ): EmbeddingEntity? {
        if (text.isBlank()) return null

        val (vector, usedModelName) = embedInternal(text, isQuery = false)

        val entity = EmbeddingEntity(
            id = "$sourceType:$sourceId:${modelName ?: usedModelName}",
            sourceType = sourceType,
            sourceId = sourceId,
            chunkHash = text.hashCode().toString(),
            modelName = modelName ?: usedModelName,
            embeddingVector = serializeVector(vector),
            createdAt = System.currentTimeMillis()
        )

        embeddingDao.insertEmbedding(entity)
        return entity
    }

    /**
     * Embed a query string ("query:" prefix for e5 models). Returns a vector of
     * the active model's dimension, or the n-gram fallback dimension.
     */
    suspend fun generateEmbedding(text: String): FloatArray {
        return embedInternal(text, isQuery = true).first
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
        val loaded = embeddingEngine.isLoaded() || embeddingEngine.load(modelPath)
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
