package com.omnidocs.app.ai

import android.content.Context
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject
import javax.inject.Provider
import javax.inject.Singleton

private const val TAG = "LlamaCppService"

/**
 * Callback interface for token streaming.
 */
interface TokenCallback {
    fun onToken(token: String)
    fun onEnd()
}

/**
 * StopReason codes preserved for backward compatibility.
 */
object StopReason {
    const val UNKNOWN = 0
    const val MAX_TOKENS = 1
    const val CTX_FULL = 2
    const val EOS = 3
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

    fun isCutOff(code: Int): Boolean = code == MAX_TOKENS || code == CTX_FULL ||
        code == DECODE_ERROR || code == TIMEOUT || code == EXCEPTION
}

/**
 * Facade preserving LlamaCppService interface while delegating all execution
 * to Google's official LiteRT-LM runtime ([LiteRtLmService]).
 */
@Singleton
class LlamaCppService @Inject constructor(
    private val liteRtLmService: LiteRtLmService
) {
    fun isNativeLibLoaded(): Boolean = liteRtLmService.isNativeLibLoaded()

    fun isReady(): Boolean = liteRtLmService.isReady()

    suspend fun loadModel(modelId: String? = null): Boolean =
        liteRtLmService.loadModel(modelId)

    suspend fun generate(
        prompt: String,
        maxTokens: Int = 128,
        applyThermalBudget: Boolean = true,
        modelId: String? = null
    ): String? = liteRtLmService.generate(prompt, maxTokens, applyThermalBudget, modelId)

    fun generateFlow(
        prompt: String,
        maxTokens: Int = 2048,
        modelId: String? = null
    ): Flow<String> = liteRtLmService.generateFlow(prompt, maxTokens, modelId)

    fun stopGeneration() {
        liteRtLmService.stopGeneration()
    }

    fun wasLastStreamCapped(): Boolean = liteRtLmService.wasLastStreamCapped()

    fun lastStopReason(): Int = liteRtLmService.lastStopReason()

    fun wasPromptTruncated(): Boolean = liteRtLmService.wasPromptTruncated()

    suspend fun unloadModel() {
        liteRtLmService.unloadModel()
    }
}
