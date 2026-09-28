package com.omnidocs.app.ai

import android.util.Log
import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.EmbeddingEngineConfig
import com.google.ai.edge.litertlm.InputData
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "EmbeddingEngine"
private const val LOAD_TIMEOUT_MS = 60_000L
private const val EMBED_TIMEOUT_MS = 30_000L

/**
 * Wraps LiteRT-LM's official on-device Embedding Engine ([com.google.ai.edge.litertlm.EmbeddingEngine]).
 * Produces dense vector representations for semantic search and RAG candidate retrieval.
 */
@Singleton
class EmbeddingEngine @Inject constructor() {

    private var engine: com.google.ai.edge.litertlm.EmbeddingEngine? = null

    @Volatile
    private var modelLoaded = false

    @Volatile
    private var isLoadingModel = false

    @Volatile
    private var cachedDim = 0

    private val modelMutex = Mutex()

    fun isNativeLibLoaded(): Boolean = true

    suspend fun load(modelPath: String): Boolean {
        if (isLoadingModel) {
            Log.w(TAG, "load already in progress, skipping")
            return false
        }
        isLoadingModel = true
        return try {
            modelMutex.withLock {
                if (modelLoaded && engine?.isInitialized() == true) {
                    return@withLock true
                }

                val file = File(modelPath)
                if (!file.exists() || file.length() == 0L) {
                    Log.e(TAG, "Embedding model file not found: $modelPath")
                    return@withLock false
                }

                Log.d(TAG, "Loading LiteRT embedding model from $modelPath (${file.length() / 1024 / 1024}MB)")

                val initialized = withContext(Dispatchers.IO) {
                    withTimeoutOrNull(LOAD_TIMEOUT_MS) {
                        try {
                            val config = EmbeddingEngineConfig(
                                modelPath = modelPath,
                                backend = Backend.CPU()
                            )
                            val eng = com.google.ai.edge.litertlm.EmbeddingEngine(config)
                            eng.initialize()
                            engine = eng
                            val testResp = eng.computeEmbedding(listOf(InputData.Text("probe")))
                            cachedDim = testResp.embedding.size
                            Log.d(TAG, "LiteRT Embedding Engine initialized with dim=$cachedDim")
                            true
                        } catch (e: Throwable) {
                            Log.e(TAG, "Failed to initialize LiteRT embedding engine", e)
                            false
                        }
                    }
                } ?: false

                modelLoaded = initialized
                initialized
            }
        } catch (e: Throwable) {
            Log.e(TAG, "Error loading embedding model", e)
            false
        } finally {
            isLoadingModel = false
        }
    }

    /**
     * Embed a single text into a dense vector FloatArray of [dim] elements,
     * or null on failure.
     */
    suspend fun embed(text: String): FloatArray? {
        if (text.isBlank() || !modelLoaded) return null

        return modelMutex.withLock {
            val currentEngine = engine ?: return@withLock null
            withContext(Dispatchers.IO) {
                withTimeoutOrNull(EMBED_TIMEOUT_MS) {
                    try {
                        val resp = currentEngine.computeEmbedding(listOf(InputData.Text(text)))
                        resp.embedding
                    } catch (e: Throwable) {
                        Log.e(TAG, "LiteRT embed error", e)
                        null
                    }
                }?.takeIf { it.isNotEmpty() }
            }
        }
    }

    /** Embedding dimension of the loaded model (0 if not loaded). */
    fun dim(): Int = cachedDim

    fun isLoaded(): Boolean = modelLoaded && engine?.isInitialized() == true

    suspend fun release() = modelMutex.withLock {
        try {
            engine?.close()
            engine = null
            modelLoaded = false
            cachedDim = 0
            Log.d(TAG, "LiteRT embedding engine freed")
        } catch (e: Exception) {
            Log.e(TAG, "Error freeing embedding model", e)
        }
    }
}
