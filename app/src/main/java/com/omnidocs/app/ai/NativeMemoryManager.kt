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
 *
 * Semantics are load-time eviction: acquiring a slot unloads conflicting
 * residents BEFORE the caller loads its model, so two giants are never
 * resident at once. Ownership is tracked per (slot, owner) with refcounts;
 * [withSlot] guarantees release in `finally`.
 *
 * Lock discipline: [slotMutex] is only ever held for fast, non-suspending
 * bookkeeping. Eviction (which takes other services' mutexes) always runs
 * OUTSIDE the mutex, so lock ordering is strictly slotMutex -> others and
 * can never deadlock against a holder of another mutex.
 */
@Singleton
class NativeMemoryManager @Inject constructor(
    private val llamaCppService: LlamaCppService,
    private val embeddingEngine: EmbeddingEngine
) {
    companion object {
        const val OWNER_LLM = "generative-llm"
        const val OWNER_STT = "speech-to-text"
        const val OWNER_EMBEDDINGS = "embeddings"
        const val OWNER_APP_PRELOAD = "app-preload"
    }

    private val managerScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val slotMutex = Mutex()

    /** slot -> (owner -> refcount) */
    private val holders = mutableMapOf<NativeModelSlot, MutableMap<String, Int>>()

    /**
     * Acquire [slot] for [owner], run [block], always release afterwards.
     * Preferred over manual acquire/release pairs.
     */
    suspend fun <T> withSlot(
        slot: NativeModelSlot,
        owner: String,
        block: suspend () -> T
    ): T {
        acquireSlot(slot, owner)
        try {
            return block()
        } finally {
            releaseSlot(slot, owner)
        }
    }

    /**
     * Request an exclusive or prioritized slot for a native model.
     * Unloads non-essential conflicting residents if memory contention would occur.
     * Re-entrant for the same (slot, owner): refcount is incremented.
     */
    suspend fun acquireSlot(slot: NativeModelSlot, owner: String = "unknown"): Boolean {
        Log.d(TAG, "Requesting native slot: $slot by $owner")

        // Evict conflicting residents OUTSIDE the mutex (suspending calls).
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

        // Fast, non-suspending bookkeeping only.
        slotMutex.withLock {
            holders.getOrPut(slot) { mutableMapOf() }.merge(owner, 1, Int::plus)
        }
        return true
    }

    /**
     * Release a previously acquired slot. Only the matching [owner]'s refcount
     * is decremented; other owners are unaffected.
     */
    suspend fun releaseSlot(slot: NativeModelSlot, owner: String = "unknown") {
        slotMutex.withLock {
            val owners = holders[slot] ?: return@withLock
            val remaining = (owners[owner] ?: 0) - 1
            if (remaining <= 0) {
                owners.remove(owner)
            } else {
                owners[owner] = remaining
            }
            if (owners.isEmpty()) {
                holders.remove(slot)
            }
            Log.d(TAG, "Released native slot: $slot by $owner")
        }
    }

    /** Returns true if [slot] is currently held by any owner. */
    suspend fun isSlotHeld(slot: NativeModelSlot): Boolean = slotMutex.withLock {
        holders[slot]?.isNotEmpty() == true
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
