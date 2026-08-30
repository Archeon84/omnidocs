package com.omnidocs.app.ai

import android.content.ComponentCallbacks2
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "NativeMemoryManager"

enum class NativeModelSlot {
    GENERATIVE_LLM,
    SPEECH_TO_TEXT,
    EMBEDDINGS
}

/**
 * Coordinates native memory allocations, model loading mutual exclusion,
 * and memory pressure trimming across llama.cpp (LLM + Embeddings) and Sherpa-onnx (STT).
 */
@Singleton
class NativeMemoryManager @Inject constructor(
    private val llamaCppService: LlamaCppService,
    private val embeddingEngine: EmbeddingEngine
) {
    private val managerScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val slotMutex = Mutex()

    @Volatile
    private var activeSlot: NativeModelSlot? = null

    /**
     * Request an exclusive or prioritized slot for a native model.
     * Unloads non-essential inactive models if memory contention would occur.
     */
    suspend fun acquireSlot(slot: NativeModelSlot): Boolean = slotMutex.withLock {
        Log.d(TAG, "Requesting native slot: $slot (current active: $activeSlot)")

        when (slot) {
            NativeModelSlot.SPEECH_TO_TEXT -> {
                // STT requires low latency and high resident memory (Whisper Large/Small).
                // Free idle generative LLM context if loaded.
                if (llamaCppService.isReady()) {
                    Log.d(TAG, "Unloading idle generative LLM to free RAM for STT")
                    llamaCppService.unloadModel()
                }
            }
            NativeModelSlot.GENERATIVE_LLM -> {
                // Generative LLM requires 1-2GB RAM.
                // Free embedding engine if loaded to prevent OOM.
                if (embeddingEngine.isLoaded()) {
                    Log.d(TAG, "Unloading embedding engine to free RAM for Generative LLM")
                    embeddingEngine.release()
                }
            }
            NativeModelSlot.EMBEDDINGS -> {
                // Background embeddings can run if LLM is not actively generating.
            }
        }

        activeSlot = slot
        true
    }

    /**
     * Release the active slot when the native operation completes.
     */
    suspend fun releaseSlot(slot: NativeModelSlot) = slotMutex.withLock {
        if (activeSlot == slot) {
            activeSlot = null
            Log.d(TAG, "Released native slot: $slot")
        }
    }

    /**
     * Handle Android system memory trim callbacks.
     * Releases idle native contexts under memory pressure.
     */
    fun onTrimMemory(level: Int) {
        Log.w(TAG, "onTrimMemory received with level=$level")
        when (level) {
            ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL,
            ComponentCallbacks2.TRIM_MEMORY_COMPLETE,
            ComponentCallbacks2.TRIM_MEMORY_BACKGROUND -> {
                Log.w(TAG, "Critical memory pressure: releasing all idle native contexts")
                managerScope.launch {
                    try {
                        if (embeddingEngine.isLoaded()) {
                            embeddingEngine.release()
                        }
                        if (llamaCppService.isReady()) {
                            llamaCppService.unloadModel()
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "Error trimming native memory", e)
                    }
                }
            }
            ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW,
            ComponentCallbacks2.TRIM_MEMORY_MODERATE -> {
                Log.w(TAG, "Moderate memory pressure: releasing embedding engine")
                managerScope.launch {
                    try {
                        if (embeddingEngine.isLoaded()) {
                            embeddingEngine.release()
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "Error trimming embedding memory", e)
                    }
                }
            }
        }
    }
}
