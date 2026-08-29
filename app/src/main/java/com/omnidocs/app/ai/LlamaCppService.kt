package com.omnidocs.app.ai

import android.content.Context
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "LlamaCppService"
private const val LOAD_TIMEOUT_MS = 60_000L
private const val GENERATE_TIMEOUT_MS = 240_000L

@Singleton
class LlamaCppService @Inject constructor(
    @ApplicationContext private val context: Context,
    private val modelDownloadManager: ModelDownloadManager,
    private val modelPreferences: ModelPreferences
) {
    @Volatile
    private var modelLoaded = false

    @Volatile
    private var isLoadingModel = false

    private val modelMutex = Mutex()

    @Volatile
    private var nativeLibLoaded = false

    init {
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

    suspend fun loadModel(modelId: String? = null): Boolean {
        if (!nativeLibLoaded) {
            Log.e(TAG, "Cannot load model: native library not loaded")
            return false
        }
        // Prevent re-entrant loads: if a previous loadModel() call is still in
        // flight (e.g. blocked in JNI), return immediately rather than stacking
        // another IO thread that will deadlock on the C++ timed_mutex.
        if (isLoadingModel) {
            Log.w(TAG, "loadModel already in progress, skipping")
            return false
        }
        isLoadingModel = true
        return try {
            modelMutex.withLock {
                if (modelLoaded) {
                    Log.d(TAG, "Model already loaded")
                    return@withLock true
                }

                // If a specific modelId is requested, find that one. Otherwise load
                // the user's SELECTED model -- the same resolver the prompt builders
                // use -- so the template format and the executed model always agree.
                // Loading the first downloaded model here diverged from what
                // resolveActiveModel() picked for prompt building.
                val model = if (modelId != null) {
                    modelDownloadManager.getDownloadedModels().find {
                        it.id == modelId && it.isDownloaded
                    }
                } else {
                    resolveActiveModel(modelPreferences, modelDownloadManager)
                } ?: run {
                    Log.e(TAG, "No downloadable model found (requested: $modelId)")
                    return@withLock false
                }

                val modelPath = modelDownloadManager.getModelPath(model)
                val file = File(modelPath)

                if (!file.exists()) {
                    Log.e(TAG, "Model file not found: $modelPath")
                    return@withLock false
                }

                Log.d(TAG, "Loading model: ${model.name} from $modelPath (${file.length() / 1024 / 1024}MB)")

                // Use Dispatchers.IO. We rely on the C++ timed_mutex (30s) to
                // prevent indefinite blocking when a previous JNI call is leaked.
                // The isLoadingModel guard ensures only ONE loadModel() call
                // proceeds at a time, so at most one IO thread can be leaked.
                val result = withContext(Dispatchers.IO) {
                    withTimeoutOrNull(LOAD_TIMEOUT_MS) {
                        nativeInit(modelPath, model.addBos)
                    }
                }

                if (result == null) {
                    Log.e(TAG, "Model loading timed out")
                    return@withLock false
                }

                modelLoaded = result
                Log.d(TAG, "Model load result: $result")
                result
            }
        } catch (e: Throwable) {
            Log.e(TAG, "Error loading model", e)
            false
        } finally {
            isLoadingModel = false
        }
    }

    suspend fun generate(prompt: String, maxTokens: Int = 128): String? {
        if (!nativeLibLoaded) {
            Log.e(TAG, "Cannot generate: native library not loaded")
            return null
        }
        // If a model load is already in progress (from a previous generate call
        // that timed out), don't stack another call — return null immediately.
        // The loading thread may be stuck in JNI and holding the C++ timed_mutex.
        if (!modelLoaded && isLoadingModel) {
            Log.w(TAG, "Model loading already in progress, skipping generate")
            return null
        }

        // Ensure model is loaded BEFORE acquiring modelMutex to avoid deadlock.
        // loadModel() acquires modelMutex internally, so calling it while holding
        // the mutex would cause a reentrant-lock hang (Kotlin Mutex is not reentrant).
        if (!modelLoaded) {
            Log.d(TAG, "Model not loaded, attempting to load...")
            val loaded = loadModel()
            if (!loaded) {
                Log.e(TAG, "Failed to load model for generation")
                return null
            }
        }

        return modelMutex.withLock {
            // Guard against TOCTOU: unloadModel() may have run between the
            // modelLoaded check above and our acquiring modelMutex.
            if (!modelLoaded) {
                Log.w(TAG, "Model was unloaded before mutex acquired, returning null")
                return@withLock null
            }

            withContext(Dispatchers.IO) {
                val startTime = System.currentTimeMillis()
                val result = withTimeoutOrNull(GENERATE_TIMEOUT_MS) {
                    try {
                        nativeGenerate(prompt, maxTokens)
                    } catch (e: Throwable) {
                        Log.e(TAG, "Native generate error", e)
                        null
                    }
                }
                val elapsed = System.currentTimeMillis() - startTime
                Log.d(TAG, "nativeGenerate took ${elapsed}ms, result length=${result?.length ?: 0}")

                if (result == null) {
                    Log.e(TAG, "Generation timed out after ${GENERATE_TIMEOUT_MS}ms")
                } else if (result.isEmpty()) {
                    Log.e(TAG, "Generation returned empty string")
                } else {
                    Log.d(TAG, "First 100 chars: ${result.take(100).replace('\n', ' ')}")
                }

                result?.ifEmpty { null }
            }
        }
    }

    fun isReady(): Boolean = modelLoaded

    suspend fun unloadModel() = modelMutex.withLock {
        try {
            nativeFree()
            modelLoaded = false
            Log.d(TAG, "Model freed")
        } catch (e: Exception) {
            Log.e(TAG, "Error freeing model", e)
        }
    }

    private external fun nativeInit(modelPath: String, addBos: Boolean): Boolean
    private external fun nativeGenerate(prompt: String, maxTokens: Int): String
    private external fun nativeFree()
}
