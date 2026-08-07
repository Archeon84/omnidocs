package com.omnidocs.app.ai

import com.omnidocs.app.data.repository.NotesRepository
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.tasks.await
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "NoteIntelligenceService"

data class ExtractedConcept(
    val name: String,
    val description: String,
    val existingNoteId: String?
)

/**
 * AI-powered note intelligence service providing Q&A, text explanation,
 * and concept extraction using on-device LLM inference.
 */
@Singleton
class NoteIntelligenceService @Inject constructor(
    private val llamaCppService: LlamaCppService,
    private val modelDownloadManager: ModelDownloadManager,
    private val modelPreferences: ModelPreferences,
    private val repository: NotesRepository
) {

    /**
     * Check if any model is downloaded and ready for inference.
     */
    private fun isAnyModelDownloaded(): Boolean {
        return modelDownloadManager.getDownloadedModels().any { it.isDownloaded }
    }

    /**
     * Get the currently selected model info, or fall back to any downloaded model.
     */
    private suspend fun getActiveModel(): ModelInfo? {
        val selectedId = modelPreferences.selectedModelId.first()
        val downloaded = modelDownloadManager.getDownloadedModels()
        // Try selected model first, fall back to any downloaded model
        return downloaded.find { it.id == selectedId && it.isDownloaded }
            ?: downloaded.firstOrNull { it.isDownloaded }
    }

    /**
     * Build a prompt using the active model's prompt format.
     */
    private suspend fun buildPrompt(
        systemPrompt: String,
        userPrompt: String
    ): String? {
        val model = getActiveModel() ?: return null
        return PromptBuilder.buildPrompt(model.promptFormat, systemPrompt, userPrompt)
    }

    /**
     * Truncate input text to a reasonable length for on-device inference.
     */
    private fun truncateText(text: String, maxChars: Int = 4000): String {
        if (text.length <= maxChars) return text
        return text.take(maxChars) + "\n\n[Truncated — showing first $maxChars chars]"
    }

    /**
     * Generate a response from the LLM.
     */
    private suspend fun generateResponse(
        systemPrompt: String,
        userPrompt: String,
        maxTokens: Int
    ): String? {
        val downloaded = isAnyModelDownloaded()
        if (!downloaded) {
            return null
        }
        return try {
            val prompt = buildPrompt(systemPrompt, userPrompt) ?: return null
            val model = getActiveModel()
            val result = llamaCppService.generate(prompt, maxTokens)
            val processed = result?.let { AiOutputProcessor.process(it) }
            processed
        } catch (e: Exception) {
            android.util.Log.e(TAG, "LLM generation error", e)
            null
        }
    }

    /**
     * Answer a question about a note's content.
     */
    suspend fun askAboutNote(noteContent: String, question: String, language: String): String {
        val langInstruction = if (language != "en") "\nRespond in $language language." else ""

        val systemPrompt = "You are a helpful assistant that answers questions about a note's content. " +
            "Base your answer only on the information provided in the note. " +
            "If the note doesn't contain the answer, say so clearly.$langInstruction"

        val userPrompt = "Note content:\n${truncateText(noteContent)}\n\nQuestion: $question"

        val result = generateResponse(systemPrompt, userPrompt, maxTokens = 1000)
        return result ?: "Unable to generate answer. Please ensure a model is downloaded."
    }

    /**
     * Explain selected text in the context of the full note.
     */
    suspend fun explainText(fullNote: String, selectedText: String, language: String): String {
        val langInstruction = if (language != "en") "\nRespond in $language language." else ""

        val systemPrompt = "You are a helpful assistant that explains text in context. " +
            "Explain the meaning, significance, and implications of the selected text " +
            "given the full note content. Be clear and concise.$langInstruction"

        val userPrompt = "Full note:\n${truncateText(fullNote)}\n\n" +
            "Selected text: \"$selectedText\"\n\n" +
            "Explain this selected text in the context of the full note."

        val result = generateResponse(systemPrompt, userPrompt, maxTokens = 800)
        return result ?: "Unable to generate explanation. Please ensure a model is downloaded."
    }

    /**
     * Extract key concepts from a note, with deduplication against existing notes.
     */
    suspend fun extractConcepts(noteContent: String, language: String): List<ExtractedConcept> {
        val langInstruction = if (language != "en") "\nRespond in $language language." else ""

        val systemPrompt = "You are a knowledge extraction assistant. Extract key concepts from the given note. " +
            "Return ONLY a JSON array of objects with 'name' and 'description' fields. " +
            "Do not include any explanatory text, markdown, or code fences. " +
            "Each concept should be a significant topic, entity, or idea from the note. " +
            "Limit to 10 concepts maximum.$langInstruction"

        val userPrompt = "Extract key concepts from this note:\n${truncateText(noteContent)}\n\n" +
            "Return JSON: [{\"name\": \"...\", \"description\": \"...\"}]"

        val result = generateResponse(systemPrompt, userPrompt, maxTokens = 800)

        if (result == null || result.isBlank()) {
            return emptyList()
        }

        // Parse JSON response
        val concepts = mutableListOf<ExtractedConcept>()
        try {
            val jsonArray = JSONArray(result)
            for (i in 0 until jsonArray.length()) {
                val obj = jsonArray.getJSONObject(i)
                val name = obj.optString("name", "").trim()
                val description = obj.optString("description", "").trim()
                if (name.isNotBlank() && description.isNotBlank()) {
                    concepts.add(ExtractedConcept(name, description, null))
                }
            }
        } catch (e: JSONException) {
            android.util.Log.w(TAG, "Failed to parse concepts JSON: $result", e)
            // Fallback: try to extract from plain text
            return emptyList()
        }

        // Deduplicate against existing notes
        return deduplicateConcepts(concepts)
    }

    /**
     * Check if extracted concepts match existing notes.
     * Returns concepts with existingNoteId populated if a match is found.
     */
    private suspend fun deduplicateConcepts(concepts: List<ExtractedConcept>): List<ExtractedConcept> {
        if (concepts.isEmpty()) return emptyList()

        val conceptNames = concepts.map { it.name.lowercase(Locale.ROOT) }
        val deduplicated = mutableListOf<ExtractedConcept>()

        // Search for each concept in existing notes
        for (concept in concepts) {
            try {
                val searchResults = repository.searchNotes(concept.name).first()
                val existingNoteId = searchResults.firstOrNull()?.id
                deduplicated.add(concept.copy(existingNoteId = existingNoteId))
            } catch (e: Exception) {
                android.util.Log.w(TAG, "Search failed for concept: ${concept.name}", e)
                deduplicated.add(concept.copy(existingNoteId = null))
            }
        }

        return deduplicated
    }
}