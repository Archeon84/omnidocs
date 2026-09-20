package com.omnidocs.app

import android.app.Application
import android.content.ComponentCallbacks2
import android.util.Log
import com.omnidocs.app.agent.WorkManagerCoordinator
import com.omnidocs.app.ai.LlamaCppService
import com.omnidocs.app.ai.ModelDownloadManager
import com.omnidocs.app.ai.NativeMemoryManager
import com.omnidocs.app.ai.NativeModelSlot
import com.omnidocs.app.ai.isAnyModelDownloaded
import dagger.hilt.android.HiltAndroidApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltAndroidApp
class NotesApplication : Application() {

    @Inject
    lateinit var workManagerCoordinator: WorkManagerCoordinator

    @Inject
    lateinit var nativeMemoryManager: NativeMemoryManager

    @Inject
    lateinit var llamaCppService: LlamaCppService

    @Inject
    lateinit var modelDownloadManager: ModelDownloadManager

    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onCreate() {
        super.onCreate()
        workManagerCoordinator.schedulePeriodicDailyHealthScan()
        preloadGenerativeModel()
    }

    /**
     * Warm up the on-device LLM in the background at app start so the first
     * "Ask" request doesn't block on loading the (multi-GB) GGUF. Native
     * memory pressure callbacks ([NativeMemoryManager.onTrimMemory]) will unload
     * it again if the system needs the RAM back.
     */
    private fun preloadGenerativeModel() {
        appScope.launch {
            if (!llamaCppService.isNativeLibLoaded()) {
                Log.d("NotesApplication", "Native lib not loaded; skipping model preload")
                return@launch
            }
            if (!isAnyModelDownloaded(modelDownloadManager)) {
                Log.d("NotesApplication", "No model downloaded; skipping preload")
                return@launch
            }
            try {
                // loadModel() serializes via the generative slot internally
                // (evicting embeddings first); withSlot here is best-effort armor
                // so the warm-load never co-resides with a concurrent STT init.
                val loaded = nativeMemoryManager.withSlot(
                    NativeModelSlot.GENERATIVE_LLM,
                    NativeMemoryManager.OWNER_APP_PRELOAD
                ) {
                    llamaCppService.loadModel()
                }
                Log.d("NotesApplication", "Model preload at app start: $loaded")
            } catch (e: Exception) {
                Log.w("NotesApplication", "Model preload failed", e)
            }
        }
    }

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        nativeMemoryManager.onTrimMemory(level)
    }

    override fun onLowMemory() {
        super.onLowMemory()
        nativeMemoryManager.onTrimMemory(ComponentCallbacks2.TRIM_MEMORY_COMPLETE)
    }
}
