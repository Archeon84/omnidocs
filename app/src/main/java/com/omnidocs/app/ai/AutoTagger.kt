package com.omnidocs.app.ai

import android.util.Log
import com.omnidocs.app.data.repository.NotesRepository
import com.omnidocs.app.domain.model.Note
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "AutoTagger"

@Singleton
class AutoTagger @Inject constructor(
    private val llamaCppService: LlamaCppService,
    private val repository: NotesRepository
) {
    // Fire-and-forget scope for post-save auto-tagging. Outlives the ViewModel so
    // navigation doesn't cancel the slow offline-LLM call.
    private val taggingScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /**
     * Fire-and-forget tagging for a newly created note. Runs off the ViewModel
     * scope so a back-navigation never cancels it, and the slow offline-LLM call
     * never blocks save/navigation.
     */
    fun tagNoteAsync(note: Note) {
        taggingScope.launch {
            try {
                val tags = generateTags(note.title, note.content)
                repository.updateNote(note.copy(tags = tags))
            } catch (e: Exception) {
                Log.e(TAG, "Tag generation failed", e)
            }
        }
    }

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