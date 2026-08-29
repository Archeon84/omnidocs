package com.omnidocs.app.ai

import android.util.Log
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
 * Wraps the llama.cpp embedding natives (see llama_jni.cpp). Loads a separate
 * GGUF embedding model (e.g. multilingual-e5-small) and produces an L2-normalized
 * sentence vector per text via mean pooling.
 *
 * Mirrors [LlamaCppService]'s safety pattern: a Kotlin [Mutex], an [isLoading]
 * re-entrancy guard, and native calls bounded by [withTimeoutOrNull] on IO. The
 * embedding model and the generative model are distinct contexts in the same
 * process, so this engine is independent of [LlamaCppService].
 */
@Singleton
class EmbeddingEngine @Inject constructor() {

    @Volatile
    private var modelLoaded = false

    @Volatile
    private var isLoadingModel = false

    /**
     * Path of a model that failed GGUF validation. ggml aborts (GGML_ABORT) are an
     * unconditional process kill with no recovery, so once a model is rejected we
     * never hand it to the native loader again — we fail fast and let
     * [com.omnidocs.app.search.EmbeddingService] fall back to its n-gram path.
     */
    @Volatile
    private var rejectedModelPath: String? = null

    @Volatile
    private var rejectedModelLastModified: Long = 0L

    @Volatile
    private var rejectedModelSize: Long = 0L

    private val modelMutex = Mutex()

    @Volatile
    private var nativeLibLoaded = false

    init {
        // Idempotent — LlamaCppService already loads the same lib; loading again
        // is a no-op that just returns the already-loaded library.
        try {
            System.loadLibrary("llama-android")
            nativeLibLoaded = true
            Log.d(TAG, "Native library loaded")
        } catch (e: UnsatisfiedLinkError) {
            Log.e(TAG, "Failed to load native library", e)
        }
    }

    /** Returns true if the native library was loaded successfully. */
    fun isNativeLibLoaded(): Boolean = nativeLibLoaded

    suspend fun load(modelPath: String): Boolean {
        if (isLoadingModel) {
            Log.w(TAG, "load already in progress, skipping")
            return false
        }
        isLoadingModel = true
        return try {
            modelMutex.withLock {
                if (modelLoaded) {
                    // Verify the native context is still valid — dim() calls
                    // nativeEmbeddingDim() which returns 0 if the context was freed.
                    val currentDim = dim()
                    if (currentDim > 0) {
                        Log.d(TAG, "Embedding model already loaded (dim=$currentDim)")
                        return@withLock true
                    }
                    // Stale modelLoaded flag — native context was freed. Reset and reload.
                    Log.w(TAG, "modelLoaded was true but dim()=0; resetting and reloading")
                    modelLoaded = false
                }

                // Gate on GGUF tensor layout: a model with quantized 1-D bias/norm
                // tensors makes ggml abort() the process (no recovery), so reject it
                // before any native call. See GgufTensorValidator.
                // If the model file has changed since rejection, re-validate.
                if (modelPath == rejectedModelPath) {
                    val file = File(modelPath)
                    if (file.lastModified() == rejectedModelLastModified &&
                        file.length() == rejectedModelSize) {
                        Log.e(TAG, "Embedding model previously rejected by GGUF validation: $modelPath")
                        return@withLock false
                    }
                    // File changed — clear rejection and re-validate
                    Log.d(TAG, "Model file changed since rejection, re-validating: $modelPath")
                    rejectedModelPath = null
                }
                val validation = withContext(Dispatchers.IO) {
                    GgufTensorValidator.validate(File(modelPath), requireEmbeddingLayout = true)
                }
                if (!validation.valid) {
                    Log.e(TAG, "Rejecting embedding model (would crash native loader): ${validation.reason}")
                    rejectedModelPath = modelPath
                    val f = File(modelPath)
                    rejectedModelLastModified = f.lastModified()
                    rejectedModelSize = f.length()
                    return@withLock false
                }

                // llama.cpp's BERT loader used to reject this model outright
                // because the cstr conversion omits
                // tokenizer.ggml.token_type_count; the vendored loader now
                // defaults the token type count (see src/models/bert.cpp), so no
                // metadata surgery is needed here.

                Log.d(TAG, "Loading embedding model from $modelPath")
                val result = withContext(Dispatchers.IO) {
                    withTimeoutOrNull(LOAD_TIMEOUT_MS) {
                        nativeEmbeddingInit(modelPath)
                    }
                }

                if (result == null) {
                    Log.e(TAG, "Embedding model loading timed out")
                    return@withLock false
                }

                modelLoaded = result
                Log.d(TAG, "Embedding model load result: $result")
                result
            }
        } catch (e: Throwable) {
            Log.e(TAG, "Error loading embedding model", e)
            false
        } finally {
            isLoadingModel = false
        }
    }

    /**
     * Embed a single text into an L2-normalized [FloatArray] of [dim] elements,
     * or null on failure (not loaded, timed out, or native error).
     */
    suspend fun embed(text: String): FloatArray? {
        if (text.isBlank()) return null

        // Ensure loaded before acquiring the mutex (loadModel acquires it too).
        if (!modelLoaded) {
            Log.w(TAG, "Embedding model not loaded")
            return null
        }

        return modelMutex.withLock {
            if (!modelLoaded) {
                Log.w(TAG, "Model was unloaded before mutex acquired")
                return@withLock null
            }

            withContext(Dispatchers.IO) {
                withTimeoutOrNull(EMBED_TIMEOUT_MS) {
                    try {
                        nativeEmbed(text)
                    } catch (e: Throwable) {
                        Log.e(TAG, "Native embed error", e)
                        null
                    }
                }?.takeIf { it.isNotEmpty() }
            }
        }
    }

    /** Embedding dimension of the loaded model (0 if not loaded). */
    fun dim(): Int {
        return if (modelLoaded) {
            try {
                nativeEmbeddingDim()
            } catch (e: Throwable) {
                Log.e(TAG, "nativeEmbeddingDim error", e)
                0
            }
        } else 0
    }

    fun isLoaded(): Boolean = modelLoaded

    suspend fun release() = modelMutex.withLock {
        try {
            nativeEmbeddingFree()
            modelLoaded = false
            Log.d(TAG, "Embedding model freed")
        } catch (e: Exception) {
            Log.e(TAG, "Error freeing embedding model", e)
        }
    }

    private external fun nativeEmbeddingInit(modelPath: String): Boolean
    private external fun nativeEmbeddingDim(): Int
    private external fun nativeEmbed(text: String): FloatArray
    private external fun nativeEmbeddingFree()
}
