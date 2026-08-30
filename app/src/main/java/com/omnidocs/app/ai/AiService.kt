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
     * Map language code to full readable language name for prompt clarity.
     */
    private fun getLanguageName(code: String): String {
        return when (code.lowercase()) {
            "en" -> "English"
            "ms" -> "Bahasa Melayu"
            "zh" -> "Chinese"
            "ja" -> "Japanese"
            "ko" -> "Korean"
            "ar" -> "Arabic"
            "hi" -> "Hindi"
            "fr" -> "French"
            "de" -> "German"
            "es" -> "Spanish"
            "ru" -> "Russian"
            "id" -> "Indonesian"
            "th" -> "Thai"
            "vi" -> "Vietnamese"
            else -> "English"
        }
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
        val langName = getLanguageName(language)
        val langInstruction = if (language != "en") "\nEnsure the output is written in $langName." else ""

        val (systemPrompt, userPrompt) = when (task) {
            "summarize" -> Pair(
                "You are an expert summarizer. Your task is to produce a clean, structured summary of the text.\n\n" +
                "Format rules:\n" +
                "• Start each point with '• '\n" +
                "• Group points under bold headers (e.g. **Key Insights**, **Action Items**)\n" +
                "• Capture core facts, names, figures, and conclusions accurately\n" +
                "• End with a one-line '**Key Takeaway**'\n" +
                "• Output only the structured summary with no preamble or conversational filler$langInstruction",
                "Summarize the following text in $langName:\n\n${truncateText(text)}"
            )
            "proofread" -> Pair(
                "You are an expert proofreader. Correct all spelling, grammar, punctuation, and typographical mistakes in the text while strictly preserving the original meaning, tone, and paragraph structure.\n\n" +
                "Rules:\n" +
                "• Output ONLY the complete corrected text directly\n" +
                "• Do NOT list errors or explain what you changed\n" +
                "• Do NOT include introductory greetings or conversational preamble$langInstruction",
                "Correct all mistakes in the text below. Output only the corrected text in $langName directly:\n\n${truncateText(text)}"
            )
            "rewrite" -> Pair(
                "You are a professional writer. Rewrite the text to improve clarity, flow, vocabulary, and conciseness while strictly preserving all core facts, meaning, and paragraph structure.\n\n" +
                "Rules:\n" +
                "• Output ONLY the rewritten text directly\n" +
                "• Do NOT explain your edits\n" +
                "• Do NOT include introductory greetings or conversational preamble$langInstruction",
                "Rewrite the following text for improved clarity and flow in $langName. Output only the rewritten text directly:\n\n${truncateText(text)}"
            )
            else -> return null
        }

        return PromptBuilder.buildPrompt(model.promptFormat, systemPrompt, userPrompt)
    }

    /**
     * Truncate input text to a reasonable length for on-device inference (fits 2048 token context).
     */
    private fun truncateText(text: String): String {
        val maxChars = 2000
        if (text.length <= maxChars) return text
        return text.take(maxChars) + "\n\n[Text truncated for on-device processing]"
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
            val result = llamaCppService.generate(prompt, maxTokens = 600)
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
            val result = llamaCppService.generate(prompt, maxTokens = 800)
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
            val result = llamaCppService.generate(prompt, maxTokens = 800)
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
