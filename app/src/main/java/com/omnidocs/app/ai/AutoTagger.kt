package com.omnidocs.app.ai

import android.util.Log
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "AutoTagger"

@Singleton
class AutoTagger @Inject constructor(
    private val llamaCppService: LlamaCppService
) {
    suspend fun generateTags(title: String, content: String): String {
        if (content.length < 50) return "[]"

        return try {
            val plainText = android.text.Html.fromHtml(content, android.text.Html.FROM_HTML_MODE_LEGACY).toString()
            val truncated = plainText.take(500)

            val prompt = """Analyze this note and generate 2-5 relevant tags.
Return ONLY a JSON array of lowercase strings, nothing else.
Example: ["programming", "tutorial", "javascript"]

Title: $title
Content: $truncated"""

            val result = llamaCppService.generate(prompt, maxTokens = 100) ?: return "[]"
            val tags = parseTags(result)
            Log.d(TAG, "Generated ${tags.size} tags")
            org.json.JSONArray(tags).toString()
        } catch (e: Exception) {
            Log.e(TAG, "Tag generation failed", e)
            "[]"
        }
    }

    private fun parseTags(raw: String): List<String> {
        return try {
            val cleaned = raw.trim()
                .removePrefix("```json")
                .removePrefix("```")
                .removeSuffix("```")
                .trim()
            val array = org.json.JSONArray(cleaned)
            (0 until array.length()).map { array.getString(it).lowercase() }
        } catch (e: Exception) {
            emptyList()
        }
    }
}