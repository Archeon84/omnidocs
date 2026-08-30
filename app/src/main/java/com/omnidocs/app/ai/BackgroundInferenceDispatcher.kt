package com.omnidocs.app.ai

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "InferenceDispatcher"

/**
 * Serializes non-interactive background AI tasks (passage embedding, auto-tagging,
 * evidence extraction) to prevent native mutex contention and excessive CPU/battery drain.
 */
@Singleton
class BackgroundInferenceDispatcher @Inject constructor() {

    private val dispatcherScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val taskChannel = Channel<suspend () -> Unit>(capacity = Channel.UNLIMITED)

    init {
        dispatcherScope.launch {
            for (task in taskChannel) {
                try {
                    task.invoke()
                } catch (e: Exception) {
                    Log.e(TAG, "Error executing background inference task", e)
                }
            }
        }
    }

    /**
     * Enqueue a background inference task to be executed sequentially.
     */
    fun enqueue(task: suspend () -> Unit) {
        val offered = taskChannel.trySend(task).isSuccess
        if (!offered) {
            dispatcherScope.launch {
                taskChannel.send(task)
            }
        }
    }
}
