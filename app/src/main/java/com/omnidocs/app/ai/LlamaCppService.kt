package com.omnidocs.app.ai

import android.content.Context
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.util.concurrent.atomic.AtomicLong
import javax.inject.Inject
import javax.inject.Provider
import javax.inject.Singleton

private const val TAG = "LlamaCppService"
private const val LOAD_TIMEOUT_MS = 60_000L
private const val GENERATE_TIMEOUT_MS = 240_000L
/** No token for this long between tokens counts as a stall. */
private const val STREAM_STALL_TIMEOUT_MS = 120_000L
/**
 * Grace before the FIRST token: on-device prefill of a long note's context
 * can take minutes on phone CPU. The old single 120s timer fired mid-prefill,
 * closing healthy streams (empty/partial answers on long notes).
 */
private const val STREAM_PREFILL_TIMEOUT_MS = 600_000L
private const val STREAM_WATCHDOG_INTERVAL_MS = 15_000L

/**
 * Callback interface for native token streaming during LLM inference.
 */
interface TokenCallback {
    fun onToken(token: String)
    fun onEnd()
}

/**
 * Why the most recent native generation loop ended. Mirrors the
 * g_last_stop_reason codes in llama_jni.cpp — keep the two in sync.
 */
object StopReason {
    const val UNKNOWN = 0
    /** Loop bound reached: the model wanted to keep writing. */
    const val MAX_TOKENS = 1
    /** n_ctx ceiling hit: prompt + generated tokens filled the window. */
    const val CTX_FULL = 2
    /** Natural end-of-sequence: the model finished its answer. */
    const val EOS = 3
    /** stopGeneration() (user Stop, turn-continuation halt, think-retry). */
    const val USER = 4
    const val DECODE_ERROR = 5
    const val TIMEOUT = 6
    const val EXCEPTION = 7

    fun name(code: Int): String = when (code) {
        MAX_TOKENS -> "max_tokens"
        CTX_FULL -> "ctx_full"
        EOS -> "eos"
        USER -> "user_stop"
        DECODE_ERROR -> "decode_error"
        TIMEOUT -> "timeout"
        EXCEPTION -> "exception"
        else -> "unknown"
    }

    /** True when the answer was very likely cut mid-sentence. */
    fun isCutOff(code: Int): Boolean = code == MAX_TOKENS || code == CTX_FULL ||
        code == DECODE_ERROR || code == TIMEOUT || code == EXCEPTION
}

@Singleton
class LlamaCppService @Inject constructor(
    @ApplicationContext private val context: Context,
    private val modelDownloadManager: ModelDownloadManager,
    private val modelPreferences: ModelPreferences,
    private val thermalBudgetManager: ThermalBudgetManager? = null,
    // Provider (not direct) to break the Hilt cycle:
    // NativeMemoryManager depends on LlamaCppService.
    private val nativeMemoryManager: Provider<NativeMemoryManager>? = null
) {
    @Volatile
    private var modelLoaded = false

    @Volatile
    private var loadedModelId: String? = null

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
        // Fast path only when the RESIDENT model is the requested one.
        // Otherwise the prompt template (built for the selected model) would
        // execute on the wrong weights with a success log.
        if (modelLoaded) {
            val requestedId = modelId
                ?: resolveActiveModel(modelPreferences, modelDownloadManager)?.id
            if (requestedId == null || requestedId == loadedModelId) {
                Log.d(TAG, "Model already loaded")
                return true
            }
            Log.d(TAG, "Switching resident model $loadedModelId -> $requestedId")
            unloadModel()
        }
        isLoadingModel = true
        return try {
            val slotManager = nativeMemoryManager?.get()
            val loadBlock: suspend () -> Boolean = {
                modelMutex.withLock {
                    if (modelLoaded) {
                        Log.d(TAG, "Model already loaded")
                        return@withLock true
                    }

                // If a specific modelId is requested, find that one. Otherwise load
                // the user's SELECTED model -- the same resolver the prompt builders
                // use -- so the template format and the executed model always agree.
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
                loadedModelId = if (result) model.id else loadedModelId
                Log.d(TAG, "Model load result: $result")
                result
                }
            }
            // Serialize against STT/embeddings: acquiring the generative slot
            // evicts conflicting residents BEFORE the multi-GB load proceeds.
            if (slotManager != null) {
                slotManager.withSlot(NativeModelSlot.GENERATIVE_LLM, NativeMemoryManager.OWNER_LLM) {
                    loadBlock()
                }
            } else {
                loadBlock()
            }
        } catch (e: Throwable) {
            Log.e(TAG, "Error loading model", e)
            false
        } finally {
            isLoadingModel = false
        }
    }

    suspend fun generate(
        prompt: String,
        maxTokens: Int = 128,
        applyThermalBudget: Boolean = true,
        modelId: String? = null
    ): String? {
        if (!nativeLibLoaded) {
            Log.e(TAG, "Cannot generate: native library not loaded")
            return null
        }

        if (thermalBudgetManager?.isSafeToInfer() == false) {
            Log.w(TAG, "Thermal status is critical/emergency; skipping generation to prevent overheating")
            return null
        }

        val effectiveMaxTokens = if (applyThermalBudget) {
            thermalBudgetManager?.getBudgetedMaxTokens(maxTokens) ?: maxTokens
        } else {
            maxTokens
        }
        if (effectiveMaxTokens <= 0) {
            Log.w(TAG, "Effective thermal token budget is 0; skipping generation")
            return null
        }

        if (effectiveMaxTokens < maxTokens) {
            Log.d(TAG, "Thermal budget reduced tokens from $maxTokens to $effectiveMaxTokens")
        }

        if (!modelLoaded && isLoadingModel) {
            Log.w(TAG, "Model loading already in progress, skipping generate")
            return null
        }

        if (modelId != null && modelLoaded && loadedModelId != modelId) {
            // Caller pinned a model (template built for it): switch residents
            // so weights always match the prompt template (no TOCTOU gap from
            // a Settings change between prompt-building and generation).
            Log.d(TAG, "Switching resident model $loadedModelId -> $modelId for generation")
            val switched = loadModel(modelId)
            if (!switched) {
                Log.e(TAG, "Failed to switch model for generation")
                return null
            }
        }

        if (!modelLoaded) {
            Log.d(TAG, "Model not loaded, attempting to load...")
            val loaded = loadModel(modelId)
            if (!loaded) {
                Log.e(TAG, "Failed to load model for generation")
                return null
            }
        }

        return modelMutex.withLock {
            if (!modelLoaded) {
                Log.w(TAG, "Model was unloaded before mutex acquired, returning null")
                return@withLock null
            }

            withContext(Dispatchers.IO) {
                val startTime = System.currentTimeMillis()
                val result = withTimeoutOrNull(GENERATE_TIMEOUT_MS) {
                    try {
                        nativeGenerate(prompt, effectiveMaxTokens)
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

    /**
     * Stream generated tokens in real time via Coroutine [Flow].
     * @param modelId pins the resident model (template built for it); null
     * resolves the selected model inside, as before.
     */
    fun generateFlow(prompt: String, maxTokens: Int = 2048, modelId: String? = null): Flow<String> = callbackFlow {
        lastStreamThermalCapped = false
        if (!nativeLibLoaded) {
            close(IllegalStateException("Native library not loaded"))
            return@callbackFlow
        }

        if (thermalBudgetManager?.isSafeToInfer() == false) {
            Log.w(TAG, "Thermal status is critical/emergency; skipping streaming generation")
            lastStreamThermalCapped = true
            close()
            return@callbackFlow
        }

        val effectiveMaxTokens = thermalBudgetManager?.getBudgetedMaxTokens(maxTokens) ?: maxTokens
        if (effectiveMaxTokens <= 0) {
            close()
            return@callbackFlow
        }
        // Thermal/power-save shrink: remember it so callers can tell the user
        // the answer was capped instead of presenting a partial as complete.
        lastStreamThermalCapped = effectiveMaxTokens < maxTokens
        if (lastStreamThermalCapped) {
            Log.w(TAG, "Thermal budget cut maxTokens $maxTokens -> $effectiveMaxTokens")
        }

        val lastTokenAt = AtomicLong(System.currentTimeMillis())
        val firstTokenSeen = java.util.concurrent.atomic.AtomicBoolean(false)
        val callback = object : TokenCallback {
            override fun onToken(token: String) {
                lastTokenAt.set(System.currentTimeMillis())
                firstTokenSeen.set(true)
                trySend(token)
            }

            override fun onEnd() {
                close()
            }
        }

        val job = launch(Dispatchers.IO) {
            if (!modelLoaded || (modelId != null && loadedModelId != modelId)) {
                val loaded = loadModel(modelId)
                if (!loaded) {
                    close(IllegalStateException("Failed to load model"))
                    return@launch
                }
            }

            modelMutex.withLock {
                if (!modelLoaded) {
                    close()
                    return@withLock
                }
                // Watchdog: unlike generate(), the native stream has no timeout.
                // A stalled JNI call would otherwise hold modelMutex forever and
                // wedge every future inference until process restart.
                // Two-phase: generous prefill grace before the first token
                // (long-note context processing takes minutes on device),
                // then a tighter inter-token stall limit.
                val watchdog = launch {
                    while (true) {
                        kotlinx.coroutines.delay(STREAM_WATCHDOG_INTERVAL_MS)
                        val limit = if (firstTokenSeen.get()) {
                            STREAM_STALL_TIMEOUT_MS
                        } else {
                            STREAM_PREFILL_TIMEOUT_MS
                        }
                        if (System.currentTimeMillis() - lastTokenAt.get() > limit) {
                            Log.e(TAG, "Streaming stall detected; stopping generation")
                            stopGeneration()
                            close(IllegalStateException("Streaming timed out"))
                            break
                        }
                    }
                }
                try {
                    nativeGenerateStream(prompt, effectiveMaxTokens, callback)
                } catch (e: Throwable) {
                    Log.e(TAG, "Streaming generation failed", e)
                    close(e)
                } finally {
                    watchdog.cancel()
                }
            }
        }

        awaitClose {
            stopGeneration()
            job.cancel()
        }
    }

    /**
     * Stop active generation immediately.
     */
    fun stopGeneration() {
        if (nativeLibLoaded) {
            try {
                nativeStop()
            } catch (e: Exception) {
                Log.e(TAG, "Error stopping generation", e)
            }
        }
    }

    fun isReady(): Boolean = modelLoaded

    @Volatile
    private var lastStreamThermalCapped = false

    /**
     * True if the most recent [generateFlow] stream ran with a thermally
     * reduced token budget. Read immediately after collection; each new
     * stream resets it.
     */
    fun wasLastStreamCapped(): Boolean = lastStreamThermalCapped

    /**
     * Why the most recent native generation loop ended ([StopReason]).
     * Read immediately after collection; each new generation resets it.
     */
    fun lastStopReason(): Int =
        if (nativeLibLoaded) nativeLastStopReason() else StopReason.UNKNOWN

    /**
     * True if the most recent generate/stream prompt exceeded the native
     * context guard and was truncated (question tail at risk). Kotlin-side
     * [PromptBudget] should normally prevent this; the flag is a backstop.
     */
    fun wasPromptTruncated(): Boolean =
        nativeLibLoaded && nativeWasPromptTruncated()

    suspend fun unloadModel() {
        try {
            modelMutex.withLock {
                try {
                    nativeFree()
                    modelLoaded = false
                    loadedModelId = null
                    Log.d(TAG, "Model freed")
                } catch (e: Exception) {
                    Log.e(TAG, "Error freeing model", e)
                }
            }
        } finally {
            // Release OUTSIDE modelMutex: slotMutex -> modelMutex is the only
            // allowed lock order, so never take slotMutex while holding modelMutex.
            try {
                nativeMemoryManager?.get()?.releaseSlot(
                    NativeModelSlot.GENERATIVE_LLM,
                    NativeMemoryManager.OWNER_LLM
                )
            } catch (e: Exception) {
                Log.w(TAG, "Error releasing native slot", e)
            }
        }
    }

    private external fun nativeInit(modelPath: String, addBos: Boolean): Boolean
    private external fun nativeGenerate(prompt: String, maxTokens: Int): String
    private external fun nativeGenerateStream(prompt: String, maxTokens: Int, callback: TokenCallback)
    private external fun nativeWasPromptTruncated(): Boolean
    private external fun nativeLastStopReason(): Int
    private external fun nativeStop()
    private external fun nativeFree()
}
