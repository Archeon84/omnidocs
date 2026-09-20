package com.omnidocs.app.ai

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "InferenceDispatcher"

/** Max queued background tasks; beyond this new tasks are dropped, never piled up. */
private const val MAX_QUEUE_SIZE = 32

/**
 * Serializes non-interactive background AI tasks (passage embedding, auto-tagging,
 * evidence extraction) to prevent native mutex contention and excessive CPU/battery drain.
 *
 * The queue is BOUNDED ([MAX_QUEUE_SIZE]): overload drops new work instead of
 * growing without limit (the old UNLIMITED channel could OOM on rapid edits).
 * Keyed tasks coalesce: while a task with the same key is still queued, further
 * enqueues with that key are skipped (e.g. repeated saves of the same note
 * collapse into a single reindex).
 */
@Singleton
class BackgroundInferenceDispatcher @Inject constructor() {

    private data class KeyedTask(val key: String, val action: suspend () -> Unit)

    private val dispatcherScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val taskChannel = Channel<KeyedTask>(capacity = MAX_QUEUE_SIZE)
    private val pendingKeys = ConcurrentHashMap.newKeySet<String>()

    init {
        dispatcherScope.launch {
            for (task in taskChannel) {
                try {
                    task.action()
                } catch (e: Exception) {
                    Log.e(TAG, "Error executing background inference task", e)
                } finally {
                    pendingKeys.remove(task.key)
                }
            }
        }
    }

    /**
     * Enqueue a background inference task to be executed sequentially.
     * @return true if queued, false if the queue is full (task dropped).
     */
    fun enqueue(task: suspend () -> Unit): Boolean {
        return enqueue(UUID.randomUUID().toString(), task)
    }

    /**
     * Enqueue a keyed task; a no-op returning false if the same key is already
     * queued, or if the queue is full.
     */
    fun enqueue(key: String, task: suspend () -> Unit): Boolean {
        if (!pendingKeys.add(key)) {
            Log.d(TAG, "Coalesced duplicate background task: $key")
            return false
        }
        val offered = taskChannel.trySend(KeyedTask(key, task)).isSuccess
        if (!offered) {
            pendingKeys.remove(key)
            Log.w(TAG, "Background task queue full ($MAX_QUEUE_SIZE); dropping task: $key")
        }
        return offered
    }
}
