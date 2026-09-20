package com.omnidocs.app.search.ann

import android.content.Context
import android.util.Log
import com.omnidocs.app.data.local.EmbeddingDao
import com.omnidocs.app.data.local.entity.EmbeddingEntity
import com.omnidocs.app.search.EmbeddingService
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.sqrt

data class AnnSearchResult(
    val key: Long,
    val similarity: Float
)

data class PassageSearchResult(
    val noteId: String,
    val chunkIndex: Int,
    val similarity: Float,
    val embeddingId: String
)

data class PassageMetadata(
    val noteId: String,
    val chunkIndex: Int,
    val embeddingId: String
)

interface AnnIndex {
    fun add(key: Long, vector: FloatArray): Boolean
    fun search(query: FloatArray, wanted: Int, cosineFloor: Float): List<AnnSearchResult>
    fun save(path: String): Boolean
    fun load(path: String): Boolean
    fun view(path: String): Boolean
    fun size(): Int
    fun contains(key: Long): Boolean
    fun clear()
    fun close()
}

/**
 * Native USearch HNSW index wrapper using JNI.
 */
class NativeUSearchIndex(
    val dimensions: Int,
    connectivity: Int = 16,
    expansionAdd: Int = 64,
    expansionSearch: Int = 32
) : AnnIndex {
    private var handle: Long = 0L

    init {
        handle = USearchNative.nativeInit(dimensions, connectivity, expansionAdd, expansionSearch)
        if (handle == 0L) {
            throw IllegalStateException("Failed to initialize native USearch index")
        }
    }

    override fun add(key: Long, vector: FloatArray): Boolean {
        if (handle == 0L) return false
        return USearchNative.nativeAdd(handle, key, vector)
    }

    override fun search(query: FloatArray, wanted: Int, cosineFloor: Float): List<AnnSearchResult> {
        if (handle == 0L || wanted <= 0) return emptyList()
        val outKeys = LongArray(wanted)
        val outDistances = FloatArray(wanted)
        val count = USearchNative.nativeSearch(handle, query, wanted, outKeys, outDistances)
        if (count <= 0) return emptyList()

        val results = ArrayList<AnnSearchResult>(count)
        for (i in 0 until count) {
            val sim = 1.0f - outDistances[i]
            if (sim >= cosineFloor) {
                results.add(AnnSearchResult(outKeys[i], sim))
            }
        }
        return results
    }

    override fun save(path: String): Boolean {
        if (handle == 0L) return false
        return USearchNative.nativeSave(handle, path)
    }

    override fun load(path: String): Boolean {
        if (handle == 0L) return false
        return USearchNative.nativeLoad(handle, path)
    }

    override fun view(path: String): Boolean {
        if (handle == 0L) return false
        return USearchNative.nativeView(handle, path)
    }

    override fun size(): Int {
        if (handle == 0L) return 0
        return USearchNative.nativeSize(handle)
    }

    override fun contains(key: Long): Boolean {
        if (handle == 0L) return false
        return USearchNative.nativeContains(handle, key)
    }

    override fun clear() {
        // Re-init with same dimensions
        if (handle != 0L) {
            USearchNative.nativeClose(handle)
            handle = USearchNative.nativeInit(dimensions, 16, 64, 32)
        }
    }

    override fun close() {
        if (handle != 0L) {
            USearchNative.nativeClose(handle)
            handle = 0L
        }
    }
}

/**
 * Pure-Kotlin in-memory fallback index for JVM unit tests or environments
 * without native library binaries.
 */
class KotlinMemoryIndex(val dimensions: Int) : AnnIndex {
    private val vectors = ConcurrentHashMap<Long, FloatArray>()

    override fun add(key: Long, vector: FloatArray): Boolean {
        vectors[key] = vector.copyOf()
        return true
    }

    override fun search(query: FloatArray, wanted: Int, cosineFloor: Float): List<AnnSearchResult> {
        if (vectors.isEmpty() || wanted <= 0) return emptyList()

        val scored = ArrayList<AnnSearchResult>(vectors.size)
        for ((key, vec) in vectors) {
            val sim = cosineSimilarity(query, vec)
            if (sim >= cosineFloor) {
                scored.add(AnnSearchResult(key, sim))
            }
        }
        scored.sortByDescending { it.similarity }
        return if (scored.size > wanted) scored.subList(0, wanted) else scored
    }

    override fun save(path: String): Boolean = true
    override fun load(path: String): Boolean = true
    override fun view(path: String): Boolean = true
    override fun size(): Int = vectors.size
    override fun contains(key: Long): Boolean = vectors.containsKey(key)
    override fun clear() { vectors.clear() }
    override fun close() { vectors.clear() }

    private fun cosineSimilarity(a: FloatArray, b: FloatArray): Float {
        var dot = 0.0f
        var normA = 0.0f
        var normB = 0.0f
        val len = minOf(a.size, b.size)
        for (i in 0 until len) {
            dot += a[i] * b[i]
            normA += a[i] * a[i]
            normB += b[i] * b[i]
        }
        if (normA <= 0.0f || normB <= 0.0f) return 0.0f
        val sim = dot / (sqrt(normA) * sqrt(normB))
        return sim.coerceIn(-1.0f, 1.0f)
    }
}

/**
 * Central manager for ANN vector indexing on Android.
 * Integrates USearch native HNSW index with disk persistence (mmap)
 * and seamless fallback to pure Kotlin indexing in unit tests.
 */
@Singleton
class AnnIndexManager(
    private val context: Context?,
    @Suppress("UNUSED_PARAMETER") dummy: Unit?
) {
    @Inject
    constructor(@ApplicationContext context: Context) : this(context, null)

    /** Secondary constructor for testing in JVM without Android Context */
    constructor() : this(null, null)

    companion object {
        private const val TAG = "AnnIndexManager"

        /**
         * Deterministic 64-bit key generated from row ID using SHA-256 prefix.
         */
        fun computeKey(rowId: String): Long {
            val md = MessageDigest.getInstance("SHA-256")
            val bytes = md.digest(rowId.toByteArray(Charsets.UTF_8))
            var key = 0L
            for (i in 0..7) {
                key = (key shl 8) or (bytes[i].toLong() and 0xFF)
            }
            return if (key == 0L) 1L else key
        }
    }

    @Volatile
    private var currentIndex: AnnIndex? = null
    @Volatile
    private var activeModel: String = ""
    @Volatile
    private var activeDimensions: Int = 0

    private val keyToMetadata = ConcurrentHashMap<Long, PassageMetadata>()

    /**
     * Creates an ANN index matching the requested dimensions, preferring native USearch.
     */
    private fun createIndex(dimensions: Int): AnnIndex {
        return if (USearchNative.isNativeLoaded) {
            try {
                NativeUSearchIndex(dimensions)
            } catch (e: Throwable) {
                Log.w(TAG, "Native USearch init failed: . Using Kotlin fallback.", e)
                KotlinMemoryIndex(dimensions)
            }
        } else {
            KotlinMemoryIndex(dimensions)
        }
    }

    /**
     * Ensures the ANN index is initialized for [modelName] with given [dimensions].
     * Loads existing index from disk via mmap if available.
     */
    @Synchronized
    fun ensureInitialized(modelName: String, dimensions: Int): AnnIndex {
        if (currentIndex != null && activeModel == modelName && activeDimensions == dimensions) {
            return currentIndex!!
        }

        currentIndex?.close()
        val index = createIndex(dimensions)
        currentIndex = index
        activeModel = modelName
        activeDimensions = dimensions

        // Try loading/viewing on-disk index
        val indexPath = getIndexPath(modelName)
        if (indexPath != null) {
            val graphFile = File(".usearch")
            val vecFile = File(".usearch.vec")
            if (graphFile.exists() && vecFile.exists()) {
                val loaded = index.view(indexPath) || index.load(indexPath)
                if (loaded) {
                    Log.i(TAG, "Successfully loaded ANN index from disk (), size=")
                }
            }
        }

        return index
    }

    /**
     * Adds an embedding to the ANN index.
     */
    fun add(embedding: EmbeddingEntity, vector: FloatArray) {
        val index = ensureInitialized(embedding.modelName, vector.size)
        val key = computeKey(embedding.id)
        val metadata = PassageMetadata(
            noteId = embedding.sourceId,
            chunkIndex = embedding.chunkIndex,
            embeddingId = embedding.id
        )
        keyToMetadata[key] = metadata
        index.add(key, vector)
    }

    /**
     * Search nearest neighbors for query embedding.
     */
    fun search(
        query: FloatArray,
        wanted: Int = 50,
        cosineFloor: Float = 0.15f,
        modelName: String? = null
    ): List<PassageSearchResult> {
        val resolvedModel = modelName ?: activeModel
        val index = currentIndex ?: run {
            if (resolvedModel.isNotBlank() && query.isNotEmpty()) {
                ensureInitialized(resolvedModel, query.size)
            } else return emptyList()
        }

        val rawResults = index.search(query, wanted, cosineFloor)
        if (rawResults.isEmpty()) return emptyList()

        val results = ArrayList<PassageSearchResult>(rawResults.size)
        for (hit in rawResults) {
            val meta = keyToMetadata[hit.key]
            if (meta != null) {
                results.add(
                    PassageSearchResult(
                        noteId = meta.noteId,
                        chunkIndex = meta.chunkIndex,
                        similarity = hit.similarity,
                        embeddingId = meta.embeddingId
                    )
                )
            }
        }
        return results
    }

    /**
     * Registers passage metadata without re-indexing if key is already indexed.
     */
    fun registerMetadata(embedding: EmbeddingEntity) {
        val key = computeKey(embedding.id)
        keyToMetadata[key] = PassageMetadata(
            noteId = embedding.sourceId,
            chunkIndex = embedding.chunkIndex,
            embeddingId = embedding.id
        )
    }

    /**
     * Populates index and metadata map from stored DB embeddings.
     */
    fun syncCorpus(embeddings: List<EmbeddingEntity>, getVector: (EmbeddingEntity) -> FloatArray?) {
        if (embeddings.isEmpty()) return
        for (entity in embeddings) {
            val vec = getVector(entity) ?: continue
            val index = ensureInitialized(entity.modelName, vec.size)
            val key = computeKey(entity.id)
            registerMetadata(entity)
            if (!index.contains(key)) {
                index.add(key, vec)
            }
        }
        persist()
    }

    /**
     * Saves current index to disk.
     */
    fun persist(): Boolean {
        val index = currentIndex ?: return false
        val indexPath = getIndexPath(activeModel) ?: return false
        val dir = File(indexPath).parentFile
        if (dir != null && !dir.exists()) {
            dir.mkdirs()
        }
        return index.save(indexPath)
    }

    private fun getIndexPath(modelName: String): String? {
        val ctx = context ?: return null
        val safeModel = modelName.replace(Regex("[^a-zA-Z0-9_-]"), "_")
        val dir = File(ctx.filesDir, "ann_indices")
        return File(dir, "notes_$safeModel").absolutePath
    }

    fun size(): Int = currentIndex?.size() ?: 0

    fun clear() {
        currentIndex?.clear()
        keyToMetadata.clear()
    }
}
