package com.omnidocs.app.ai

import android.app.ActivityManager
import android.content.ComponentCallbacks2
import android.content.Context
import android.content.res.Configuration
import android.util.Log
import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.Content
import com.google.ai.edge.litertlm.Conversation
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Provider
import javax.inject.Singleton

private const val TAG = "LiteRtLmService"
private const val LOAD_TIMEOUT_MS = 60_000L
private const val GENERATE_TIMEOUT_MS = 240_000L

@Singleton
class LiteRtLmService @Inject constructor(
    @ApplicationContext private val context: Context,
    private val modelDownloadManager: ModelDownloadManager,
    private val modelPreferences: ModelPreferences,
    private val thermalBudgetManager: ThermalBudgetManager? = null,
    private val nativeMemoryManager: Provider<NativeMemoryManager>? = null,
    private val deviceCapabilityManager: DeviceCapabilityManager? = null
) : ComponentCallbacks2 {

    private var engine: Engine? = null

    @Volatile
    private var modelLoaded = false

    @Volatile
    private var loadedModelId: String? = null

    @Volatile
    private var isLoadingModel = false

    private val modelMutex = Mutex()

    @Volatile
    private var activeConversation: Conversation? = null

    @Volatile
    private var activeJob: Job? = null

    @Volatile
    private var lastStreamThermalCapped = false

    @Volatile
    private var lastPromptTruncated = false

    @Volatile
    private var lastStopReasonCode: Int = StopReason.UNKNOWN

    init {
        context.registerComponentCallbacks(this)
        Log.d(TAG, "LiteRtLmService initialized with Google LiteRT-LM runtime")
    }

    fun isNativeLibLoaded(): Boolean = true

    fun isReady(): Boolean = modelLoaded && engine?.isInitialized() == true

    fun wasLastStreamCapped(): Boolean = lastStreamThermalCapped

    fun wasPromptTruncated(): Boolean = lastPromptTruncated

    fun lastStopReason(): Int = lastStopReasonCode

    /**
     * Compute maximum safe context tokens based on device RAM.
     * Flagship devices (>=10GB RAM) allow 8,192 tokens.
     * Mid-tier devices (6-8GB RAM) use 6,144 tokens.
     * Budget devices (<6GB RAM) clamp to 4,096 tokens to avoid Low Memory Killer (LMK).
     * Hardware-constrained devices further clamp to 2,048 tokens.
     */
    fun computeMaxContextTokens(model: ModelInfo? = null): Int {
        val baseTokens = try {
            val am = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
            val memInfo = ActivityManager.MemoryInfo()
            am?.getMemoryInfo(memInfo)
            val totalRamGb = memInfo.totalMem / (1024L * 1024L * 1024L)
            when {
                totalRamGb >= 10 -> 8192
                totalRamGb >= 6 -> 6144
                else -> 4096
            }
        } catch (_: Exception) {
            4096
        }

        if (model != null && deviceCapabilityManager != null) {
            if (!deviceCapabilityManager.isModelSafeForHardware(model)) {
                Log.w(TAG, "Hardware constraint detected for ${model.name}; clamping context to 2048")
                return minOf(baseTokens, 2048)
            }
        }
        return baseTokens
    }

    suspend fun loadModel(modelId: String? = null): Boolean {
        if (isLoadingModel) {
            Log.w(TAG, "loadModel already in progress, skipping")
            return false
        }

        if (modelLoaded) {
            val requestedId = modelId ?: resolveActiveModel(modelPreferences, modelDownloadManager)?.id
            if (requestedId == null || requestedId == loadedModelId) {
                Log.d(TAG, "Model $loadedModelId already resident in LiteRT-LM")
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
                    if (modelLoaded) return@withLock true

                    val model = if (modelId != null) {
                        modelDownloadManager.getDownloadedModels().find {
                            it.id == modelId && it.isDownloaded
                        }
                    } else {
                        resolveActiveModel(modelPreferences, modelDownloadManager)
                    } ?: run {
                        Log.e(TAG, "No downloaded LiteRT model found (requested: $modelId)")
                        return@withLock false
                    }

                    val modelPath = modelDownloadManager.getModelPath(model)
                    val file = File(modelPath)
                    if (!file.exists() || file.length() == 0L) {
                        Log.e(TAG, "LiteRT model file missing or empty: $modelPath")
                        return@withLock false
                    }

                    Log.d(TAG, "Loading LiteRT model: ${model.name} from $modelPath (${file.length() / 1024 / 1024}MB)")

                    val maxTokens = computeMaxContextTokens(model)
                    val cacheDir = File(context.cacheDir, "litertlm").apply { mkdirs() }.absolutePath

                    val initialized = withContext(Dispatchers.IO) {
                        withTimeoutOrNull(LOAD_TIMEOUT_MS) {
                            var eng: Engine? = null
                            try {
                                // 1. Attempt GPU acceleration first for maximum mobile performance
                                Log.d(TAG, "Attempting LiteRT-LM initialization with GPU backend (maxTokens=$maxTokens)...")
                                val gpuConfig = EngineConfig(
                                    modelPath = modelPath,
                                    backend = Backend.GPU(),
                                    maxNumTokens = maxTokens,
                                    cacheDir = cacheDir
                                )
                                val gpuEng = Engine(gpuConfig)
                                eng = gpuEng
                                gpuEng.initialize()

                                // Probe GPU execution with a test conversation to verify OpenCL runtime compatibility
                                val probeConv = gpuEng.createConversation()
                                try {
                                    probeConv.sendMessageAsync("hi", maxOutputToken = 1).collect { }
                                } finally {
                                    try { probeConv.close() } catch (_: Exception) {}
                                }

                                engine = gpuEng
                                Log.d(TAG, "GPU backend initialized and verified successfully")
                                true
                            } catch (gpuError: Throwable) {
                                try { eng?.close() } catch (_: Exception) {}
                                eng = null
                                Log.w(TAG, "GPU backend failed verification: ${gpuError.message}. Falling back to CPU backend...", gpuError)
                                try {
                                    val cpuConfig = EngineConfig(
                                        modelPath = modelPath,
                                        backend = Backend.CPU(),
                                        maxNumTokens = maxTokens,
                                        cacheDir = cacheDir
                                    )
                                    val cpuEng = Engine(cpuConfig)
                                    cpuEng.initialize()
                                    engine = cpuEng
                                    Log.d(TAG, "CPU backend initialized successfully")
                                    true
                                } catch (cpuError: Throwable) {
                                    Log.e(TAG, "LiteRT-LM CPU initialization also failed: ${cpuError.message}", cpuError)
                                    false
                                }
                            }
                        }
                    } ?: false

                    modelLoaded = initialized
                    loadedModelId = if (initialized) model.id else null
                    Log.d(TAG, "LiteRT-LM model load result for ${model.id}: $initialized")
                    initialized
                }
            }

            if (slotManager != null) {
                slotManager.withSlot(NativeModelSlot.GENERATIVE_LLM, NativeMemoryManager.OWNER_LLM) {
                    loadBlock()
                }
            } else {
                loadBlock()
            }
        } catch (e: Throwable) {
            Log.e(TAG, "Error loading LiteRT-LM model", e)
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
        val effectiveMaxTokens = if (applyThermalBudget) {
            thermalBudgetManager?.getBudgetedMaxTokens(maxTokens) ?: maxTokens
        } else {
            maxTokens
        }

        if (effectiveMaxTokens <= 0) {
            Log.w(TAG, "Thermal budget is 0, skipping generation")
            return null
        }

        if (modelId != null && modelLoaded && loadedModelId != modelId) {
            val switched = loadModel(modelId)
            if (!switched) return null
        }

        if (!modelLoaded) {
            val loaded = loadModel(modelId)
            if (!loaded) return null
        }

        return withContext(Dispatchers.IO) {
            withTimeoutOrNull(GENERATE_TIMEOUT_MS) {
                val sb = StringBuilder()
                try {
                    val currentEngine = engine ?: return@withTimeoutOrNull null
                    val conversation = currentEngine.createConversation()
                    activeConversation = conversation

                    conversation.sendMessageAsync(
                        text = prompt,
                        maxOutputToken = effectiveMaxTokens
                    ).collect { msg ->
                        val text = msg.contents.contents
                            .filterIsInstance<Content.Text>()
                            .joinToString("") { it.text }
                        sb.append(text)
                    }
                    conversation.close()
                    activeConversation = null
                    sb.toString().ifBlank { null }
                } catch (e: CancellationException) {
                    Log.d(TAG, "Generation cancelled")
                    null
                } catch (e: Throwable) {
                    Log.e(TAG, "Generation error in LiteRT-LM", e)
                    null
                } finally {
                    activeConversation = null
                }
            }
        }
    }

    fun generateFlow(
        prompt: String,
        maxTokens: Int = 2048,
        modelId: String? = null
    ): Flow<String> = callbackFlow<String> {
        lastStreamThermalCapped = false
        lastPromptTruncated = false
        lastStopReasonCode = StopReason.UNKNOWN

        val effectiveMaxTokens = thermalBudgetManager?.getBudgetedMaxTokens(maxTokens) ?: maxTokens
        if (effectiveMaxTokens < maxTokens) {
            lastStreamThermalCapped = true
        }

        val job = launch(Dispatchers.IO) {
            if (modelId != null && modelLoaded && loadedModelId != modelId) {
                val switched = loadModel(modelId)
                if (!switched) {
                    close(IllegalStateException("Failed to switch model to $modelId"))
                    return@launch
                }
            }

            if (!modelLoaded) {
                val loaded = loadModel(modelId)
                if (!loaded) {
                    close(IllegalStateException("Failed to load model for generation"))
                    return@launch
                }
            }

            val currentEngine = engine
            if (currentEngine == null) {
                close(IllegalStateException("Engine is null"))
                return@launch
            }

            val conversation = currentEngine.createConversation()
            activeConversation = conversation

            try {
                conversation.sendMessageAsync(
                    text = prompt,
                    maxOutputToken = effectiveMaxTokens
                ).collect { msg ->
                    val text = msg.contents.contents
                        .filterIsInstance<Content.Text>()
                        .joinToString("") { it.text }
                    if (text.isNotEmpty()) {
                        trySend(text)
                    }
                }
                lastStopReasonCode = StopReason.EOS
            } catch (e: CancellationException) {
                lastStopReasonCode = StopReason.USER
                Log.d(TAG, "Streaming cancelled by caller")
            } catch (e: Throwable) {
                lastStopReasonCode = StopReason.EXCEPTION
                Log.e(TAG, "Streaming error", e)
            } finally {
                try {
                    conversation.close()
                } catch (_: Exception) {}
                if (activeConversation === conversation) {
                    activeConversation = null
                }
                close()
            }
        }

        activeJob = job

        awaitClose {
            job.cancel()
            try {
                activeConversation?.close()
            } catch (_: Exception) {}
            activeConversation = null
            activeJob = null
        }
    }.flowOn(Dispatchers.IO)

    fun stopGeneration() {
        try {
            activeJob?.cancel(CancellationException("Stopped by user"))
            activeJob = null
            activeConversation?.close()
            activeConversation = null
            lastStopReasonCode = StopReason.USER
            Log.d(TAG, "stopGeneration invoked: conversation and active job terminated")
        } catch (e: Exception) {
            Log.w(TAG, "Error stopping generation", e)
        }
    }

    suspend fun unloadModel() {
        try {
            modelMutex.withLock {
                stopGeneration()
                try {
                    engine?.close()
                    engine = null
                    modelLoaded = false
                    loadedModelId = null
                    Log.d(TAG, "LiteRT-LM engine closed and freed")
                } catch (e: Exception) {
                    Log.e(TAG, "Error closing LiteRT-LM engine", e)
                }
            }
        } finally {
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

    override fun onTrimMemory(level: Int) {
        if (level >= ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL ||
            level >= ComponentCallbacks2.TRIM_MEMORY_MODERATE) {
            Log.w(TAG, "System memory pressure level $level. Evicting model to prevent LMK kill.")
            CoroutineScope(Dispatchers.IO).launch {
                unloadModel()
            }
        }
    }

    override fun onConfigurationChanged(newConfig: Configuration) {}

    override fun onLowMemory() {
        Log.w(TAG, "onLowMemory triggered. Forcibly releasing model resources.")
        CoroutineScope(Dispatchers.IO).launch {
            unloadModel()
        }
    }
}
