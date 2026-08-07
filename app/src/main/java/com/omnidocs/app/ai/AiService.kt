package com.omnidocs.app.ai

import android.content.Context
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.first
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "AiService"

/**
 * Main AI orchestrator. Routes summarization, proofreading, and rewriting
 * requests through the on-device llama.cpp engine ([LlamaCppService]).
 *
 * Model priority: selected local model → first downloaded local model → none.
 * The active model is displayed in the editor's AI status indicator.
 */
@Singleton
class AiService @Inject constructor(
    @ApplicationContext private val context: Context,
    private val modelDownloadManager: ModelDownloadManager,
    private val llamaCppService: LlamaCppService,
    private val modelPreferences: ModelPreferences
) {
    /**
     * Check if any chat-compatible model is downloaded and ready for inference.
     */
    private fun isAnyModelDownloaded(): Boolean {
        return isAnyModelDownloaded(modelDownloadManager)
    }

    /**
     * Get the currently selected model info, or null if none downloaded.
     */
    private suspend fun getActiveModel(): ModelInfo? {
        return resolveActiveModel(modelPreferences, modelDownloadManager)
    }

    /**
     * Build a prompt using the active model's prompt format.
     */
    private suspend fun buildPrompt(
        text: String,
        language: String,
        task: String
    ): String? {
        val model = getActiveModel() ?: return null
        val langInstruction = if (language != "en") "\nRespond in $language language." else ""

        val (systemPrompt, userPrompt) = when (task) {
            "summarize" -> Pair(
                "You are an expert summarizer. Your task is to create a well-structured, bullet-point summary of the given text.\n\n" +
                "Rules:\n" +
                "- Use clear, concise bullet points (start each line with •)\n" +
                "- Group related ideas under descriptive sub-headings (bold text with **heading**)\n" +
                "- Capture ALL key facts, figures, names, dates, and conclusions\n" +
                "- Preserve technical accuracy — do not generalize or add information not in the original\n" +
                "- Use 5–15 bullet points depending on text length and complexity\n" +
                "- End with a one-line **Key Takeaway** summarizing the single most important point\n" +
                "- Output only the structured summary, nothing else$langInstruction",
                "Create a structured bullet-point summary of the following text:\n\n${truncateText(text)}"
            )
            "proofread" -> Pair(
                "You are a professional proofreader and editor. Correct ALL grammar, spelling, punctuation, and improve clarity. Keep the original meaning intact. You MUST output the COMPLETE corrected text — do not summarize, abbreviate, or skip any part. Output the full corrected text only, nothing else.$langInstruction",
                "Proofread and correct the ENTIRE following text. Output every sentence, do not skip or shorten anything:\n\n${truncateText(text)}"
            )
            "rewrite" -> Pair(
                "You are a professional writer. Rewrite the ENTIRE text to be clearer, more eloquent, and better structured while preserving the original meaning. You MUST output the COMPLETE rewritten text — do not summarize, abbreviate, or skip any part. Output the full rewritten text only, nothing else.$langInstruction",
                "Rewrite the ENTIRE following text. Output every sentence, do not skip or shorten anything:\n\n${truncateText(text)}"
            )
            else -> return null
        }

        return PromptBuilder.buildPrompt(model.promptFormat, systemPrompt, userPrompt)
    }

    /**
     * Truncate input text to a reasonable length for on-device inference.
     */
    private fun truncateText(text: String): String {
        val maxChars = 4000
        if (text.length <= maxChars) return text
        return text.take(maxChars) + "\n\n[Text truncated — showing first $maxChars characters]"
    }

    suspend fun summarize(text: String, language: String): String? {
        val downloaded = isAnyModelDownloaded()
        Log.d(TAG, "Summarize called. Any model downloaded: $downloaded")
        if (!downloaded) {
            Log.d(TAG, "No model downloaded - AI features unavailable")
            return null
        }
        return try {
            val prompt = buildPrompt(text, language, "summarize") ?: return null
            val model = getActiveModel()
            Log.d(TAG, "Running summarize with ${model?.name ?: "unknown"} (lang=$language)")
            val result = llamaCppService.generate(prompt, maxTokens = 1500)
            val processed = result?.let { AiOutputProcessor.process(it) }
            Log.d(TAG, "Summarize result: ${processed?.take(50)}...")
            processed
        } catch (e: Exception) {
            Log.e(TAG, "Summarize error", e)
            null
        }
    }

    suspend fun proofread(text: String, language: String): String? {
        val downloaded = isAnyModelDownloaded()
        Log.d(TAG, "Proofread called. Any model downloaded: $downloaded")
        if (!downloaded) {
            Log.d(TAG, "No model downloaded - AI features unavailable")
            return null
        }
        return try {
            val prompt = buildPrompt(text, language, "proofread") ?: return null
            val model = getActiveModel()
            Log.d(TAG, "Running proofread with ${model?.name ?: "unknown"} (lang=$language)")
            val result = llamaCppService.generate(prompt, maxTokens = 1500)
            val processed = result?.let { AiOutputProcessor.process(it) }
            Log.d(TAG, "Proofread result: ${processed?.take(50)}...")
            processed
        } catch (e: Exception) {
            Log.e(TAG, "Proofread error", e)
            null
        }
    }

    suspend fun rewrite(text: String, language: String): String? {
        val downloaded = isAnyModelDownloaded()
        Log.d(TAG, "Rewrite called. Any model downloaded: $downloaded")
        if (!downloaded) {
            Log.d(TAG, "No model downloaded - AI features unavailable")
            return null
        }
        return try {
            val prompt = buildPrompt(text, language, "rewrite") ?: return null
            val model = getActiveModel()
            Log.d(TAG, "Running rewrite with ${model?.name ?: "unknown"} (lang=$language)")
            val result = llamaCppService.generate(prompt, maxTokens = 1500)
            val processed = result?.let { AiOutputProcessor.process(it) }
            Log.d(TAG, "Rewrite result: ${processed?.take(50)}...")
            processed
        } catch (e: Exception) {
            Log.e(TAG, "Rewrite error", e)
            null
        }
    }

    fun isSupportedLanguage(language: String): Boolean {
        return language == "en" || isAnyModelDownloaded()
    }

    /**
     * Returns a display string describing the current AI model status.
     * Shows the active local model name, not "Gemini Nano".
     */
    fun getAiStatus(): String {
        return if (isAnyModelDownloaded()) {
            val models = modelDownloadManager.getDownloadedModels().filter { it.isDownloaded }
            if (models.isNotEmpty()) "${models.first().name} (Offline)"
            else "Models available (not downloaded)"
        } else {
            "No AI model — download one in Settings"
        }
    }
}
