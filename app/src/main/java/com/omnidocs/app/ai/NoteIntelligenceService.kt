package com.omnidocs.app.ai

import com.omnidocs.app.agent.optIntLenient
import com.omnidocs.app.data.repository.NotesRepository
import com.omnidocs.app.study.Flashcard
import com.omnidocs.app.study.StudyCardType
import com.omnidocs.app.study.StudyDeck
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.flow
import org.json.JSONArray
import org.json.JSONException
import java.util.UUID
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
    private val repository: NotesRepository,
    private val noteBlockAdapter: NoteBlockAdapter? = null
) {

    /**
     * Check if any model is downloaded and ready for inference.
     */
    private fun isAnyModelDownloaded(): Boolean {
        return isAnyModelDownloaded(modelDownloadManager)
    }

    /**
     * Get the currently selected model info, or fall back to any downloaded model.
     */
    private suspend fun getActiveModel(): ModelInfo? {
        return resolveActiveModel(modelPreferences, modelDownloadManager)
    }

    /**
     * Build a prompt using the active model's prompt format.
     */
    private suspend fun buildPrompt(
        systemPrompt: String,
        userPrompt: String
    ): String? {
        val model = getActiveModel() ?: return null
        return PromptBuilder.buildPrompt(model.promptFormat, systemPrompt, userPrompt, model)
    }

    /**
     * Truncate input text to a reasonable length for on-device inference cleanly
     * at a sentence or line boundary without injecting synthetic warning tags into prompt text.
     */
    /**
     * Truncate input text to a reasonable length for on-device inference cleanly
     * at a sentence or line boundary without injecting synthetic warning tags into prompt text.
     */
    private fun truncateText(text: String, maxChars: Int = 24000): String {
        if (text.length <= maxChars) return text
        val sub = text.take(maxChars)
        val lastBoundary = sub.lastIndexOfAny(charArrayOf('\n', '.', '!', '?'))
        return if (lastBoundary > maxChars / 2) {
            sub.substring(0, lastBoundary + 1).trim()
        } else {
            sub.trim()
        }
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
            val result = llamaCppService.generate(prompt, maxTokens)
            val processed = result?.let { AiOutputProcessor.process(it) }
            processed
        } catch (e: Exception) {
            android.util.Log.e(TAG, "LLM generation error", e)
            null
        }
    }

    /**
     * Select the most relevant passages from the note when it exceeds maxChars,
     * ensuring long documents (> 24k chars / ~5,000 words) have their relevant sections included
     * rather than blindly truncating the head of the note.
     */
    fun selectRelevantContext(noteContent: String, question: String, maxChars: Int = 24000): String {
        if (noteContent.length <= maxChars) return noteContent
        val adapter = noteBlockAdapter ?: return truncateText(noteContent, maxChars)
        val segments = adapter.chunkText("note", "Note", noteContent)
        if (segments.isEmpty()) return truncateText(noteContent, maxChars)

        val queryTerms = com.omnidocs.app.search.Tokenizer.tokenize(question).toSet()
        if (queryTerms.isEmpty()) return truncateText(noteContent, maxChars)

        // Score each chunk by query term overlap
        val scored = segments.map { seg ->
            val segTerms = com.omnidocs.app.search.Tokenizer.tokenize(seg.content).toSet()
            val score = queryTerms.intersect(segTerms).size
            seg to score
        }.sortedByDescending { it.second }

        val matchingSegments = scored.filter { it.second > 0 }.map { it.first }
        if (matchingSegments.isEmpty()) {
            return truncateText(noteContent, maxChars)
        }

        // Restore original document order for narrative coherence and pack up to maxChars
        val ordered = matchingSegments.sortedBy { it.startOffset }
        val sb = StringBuilder()
        var currentChars = 0
        for (seg in ordered) {
            val snippet = if (seg.content.length > 2000) seg.content.take(2000) else seg.content
            if (currentChars + snippet.length > maxChars) break
            if (sb.isNotEmpty()) sb.append("\n\n---\n\n")
            seg.headerContext?.let { header ->
                sb.append("### Section: $header\n")
            }
            sb.append(snippet)
            currentChars += snippet.length
        }
        return sb.toString().ifBlank { truncateText(noteContent, maxChars) }
    }

    /**
     * Rule-based extraction when no model is downloaded. Extracts the top matching
     * sentence directly from the note so users get an immediate answer instead of a dead failure.
     */
    fun generateRuleBasedNoteAnswer(noteContent: String, question: String): String {
        val queryTerms = com.omnidocs.app.search.Tokenizer.tokenize(question).toSet()
        if (queryTerms.isEmpty()) {
            return "No AI model is downloaded. Download an on-device AI model in Settings to ask questions about your notes."
        }

        val sentences = noteContent.split(Regex("(?<=[.!?\n])\\s+")).filter { it.isNotBlank() }
        val scored = sentences.map { sentence ->
            val terms = com.omnidocs.app.search.Tokenizer.tokenize(sentence).toSet()
            val score = queryTerms.intersect(terms).size
            sentence to score
        }.sortedByDescending { it.second }

        val best = scored.firstOrNull { it.second >= 2 } ?: scored.firstOrNull { it.second >= 1 }
        return if (best != null) {
            "\"${best.first.trim()}\"\n\n(Quoted directly from note — download an on-device AI model in Settings for synthesis)"
        } else {
            "The note does not appear to contain keywords matching your question. Download an AI model in Settings to enable semantic analysis."
        }
    }

    /**
     * Answer a question about a note's content.
     */
    suspend fun askAboutNote(
        noteContent: String,
        question: String,
        language: String,
        conversationHistory: List<Pair<String, String>> = emptyList()
    ): String {
        if (!isAnyModelDownloaded()) {
            return generateRuleBasedNoteAnswer(noteContent, question)
        }
        val langInstruction = if (language != "en") "\nEnsure your answer is written in $language language." else ""

        val systemPrompt = "You are a helpful assistant that answers questions about a note's content.\n\n" +
            "Rules:\n" +
            "• Base your answer ONLY on the information provided in the note\n" +
            "• Output a single direct, concise answer\n" +
            "• Do NOT include thinking tags (think...), internal chain of thought, reasoning steps, or internal monologue\n" +
            "• Do NOT repeat the question, generate multiple answer versions, or simulate conversation turns\n" +
            "• If the note only partially covers the question, answer what is present and say what is missing\n" +
            "• Only when the note contains nothing relevant, state clearly: \"The note does not contain information to answer this question.\"$langInstruction"

        val relevantContent = selectRelevantContext(noteContent, question)
        val historyContext = if (conversationHistory.isNotEmpty()) {
            val recent = conversationHistory.takeLast(2)
            val histStr = recent.joinToString("\n") { (role, text) ->
                "${if (role == "user") "User" else "Assistant"}: $text"
            }
            "Prior conversation:\n$histStr\n\n"
        } else ""

        val rawUserPrompt = PromptBudget.buildNoteUserPrompt(relevantContent, question, systemPrompt)
        val userPrompt = if (historyContext.isNotEmpty()) {
            historyContext + rawUserPrompt
        } else {
            rawUserPrompt
        }

        val result = generateResponse(systemPrompt, userPrompt, maxTokens = 1500)
        return result ?: "Unable to generate answer. Please ensure a model is downloaded."
    }

    /**
     * Streaming variant of [askAboutNote]. Emits raw tokens as they are generated
     * so the UI can render a live answer instead of an indefinite spinner.
     * Emits a rule-based extract when no model is downloaded.
     */
    fun askAboutNoteStream(
        noteContent: String,
        question: String,
        language: String,
        conversationHistory: List<Pair<String, String>> = emptyList()
    ): Flow<String> = flow {
        if (!isAnyModelDownloaded()) {
            val fallback = generateRuleBasedNoteAnswer(noteContent, question)
            emit(fallback)
            return@flow
        }
        val langInstruction = if (language != "en") "\nEnsure your answer is written in $language language." else ""

        val systemPrompt = "You are a helpful assistant that answers questions about a note's content.\n\n" +
            "Rules:\n" +
            "• Base your answer ONLY on the information provided in the note\n" +
            "• Output a single direct, concise answer\n" +
            "• Do NOT include thinking tags (think...), internal chain of thought, reasoning steps, or internal monologue\n" +
            "• Do NOT repeat the question, generate multiple answer versions, or simulate conversation turns\n" +
            "• If the note only partially covers the question, answer what is present and say what is missing\n" +
            "• Only when the note contains nothing relevant, state clearly: \"The note does not contain information to answer this question.\"$langInstruction"

        val relevantContent = selectRelevantContext(noteContent, question)
        val historyContext = if (conversationHistory.isNotEmpty()) {
            val recent = conversationHistory.takeLast(2)
            val histStr = recent.joinToString("\n") { (role, text) ->
                "${if (role == "user") "User" else "Assistant"}: $text"
            }
            "Prior conversation:\n$histStr\n\n"
        } else ""

        val rawUserPrompt = PromptBudget.buildNoteUserPrompt(relevantContent, question, systemPrompt)
        val userPrompt = if (historyContext.isNotEmpty()) {
            historyContext + rawUserPrompt
        } else {
            rawUserPrompt
        }

        val model = getActiveModel() ?: return@flow
        // Build from the captured model (not buildPrompt's re-resolution) so
        // template and pinned weights cannot disagree. The thinking-model
        // prefill (empty think block) is applied inside buildPrompt.
        val prompt = PromptBuilder.buildPrompt(
            model.promptFormat,
            systemPrompt,
            userPrompt,
            model
        )
        // Suppress thinking spans live: /no_think is only a hint and the raw
        // stream would otherwise show the model's reasoning before the answer.
        // StopStringFilter halts turn-continuation loops without burning
        // maxTokens; the held-back window is flushed at natural end.
        val filter = ThinkingStreamFilter()
        val stopFilter = StopStringFilter()
        // Pin the model so a Settings change mid-stream cannot swap weights
        // under the already-built template.
        llamaCppService.generateFlow(prompt, 1500, modelId = model.id).collect { token ->
            val visible = filter.feed(token)
            if (visible.isNotEmpty()) {
                val releasable = stopFilter.feed(visible)
                if (releasable.isNotEmpty()) emit(releasable)
                if (stopFilter.stopped) llamaCppService.stopGeneration()
            }
        }
        val tail = filter.flush()
        if (tail.isNotEmpty() && !stopFilter.stopped) {
            val releasable = stopFilter.feed(tail) + stopFilter.flush()
            if (releasable.isNotEmpty()) emit(releasable)
        } else if (!stopFilter.stopped) {
            val flushed = stopFilter.flush()
            if (flushed.isNotEmpty()) emit(flushed)
        }
    }

    /**
     * Explain selected text in the context of the full note.
     */
    suspend fun explainText(fullNote: String, selectedText: String, language: String): String {
        val langInstruction = if (language != "en") "\nRespond in $language language." else ""

        val systemPrompt = "You are a helpful assistant that explains text in context. " +
            "Explain the meaning, significance, and implications of the selected text " +
            "given the full note content. Be clear and concise.\n" +
            "• Do NOT output thinking tags, reasoning processes, or internal monologue.\n" +
            "• Output ONLY the direct explanation.$langInstruction"

        val userPrompt = "Full note:\n${truncateText(fullNote)}\n\n" +
            "Selected text: \"$selectedText\"\n\n" +
            "Explain this selected text in the context of the full note."

        val result = generateResponse(systemPrompt, userPrompt, maxTokens = 1500)
        return result ?: "Unable to generate explanation. Please ensure a model is downloaded."
    }

    /**
     * Streaming variant of [explainText]. Emits raw tokens as generated.
     */
    fun explainTextStream(fullNote: String, selectedText: String, language: String): Flow<String> = flow {
        if (!isAnyModelDownloaded()) return@flow
        val langInstruction = if (language != "en") "\nRespond in $language language." else ""

        val systemPrompt = "You are a helpful assistant that explains text in context. " +
            "Explain the meaning, significance, and implications of the selected text " +
            "given the full note content. Be clear and concise.\n" +
            "• Do NOT output thinking tags, reasoning processes, or internal monologue.\n" +
            "• Output ONLY the direct explanation.$langInstruction"

        val userPrompt = "Full note:\n${truncateText(fullNote)}\n\n" +
            "Selected text: \"$selectedText\"\n\n" +
            "Explain this selected text in the context of the full note."
        val prompt = buildPrompt(systemPrompt, userPrompt) ?: return@flow
        val filter = ThinkingStreamFilter()
        llamaCppService.generateFlow(prompt, 1500).collect { token ->
            val visible = filter.feed(token)
            if (visible.isNotEmpty()) emit(visible)
        }
        val tail = filter.flush()
        if (tail.isNotEmpty()) emit(tail)
    }

    /**
     * Extract key concepts from a note, with deduplication against existing notes.
     */
    suspend fun extractConcepts(noteContent: String, language: String): List<ExtractedConcept> {
        val langInstruction = if (language != "en") "\nRespond in $language language." else ""

        val systemPrompt = "You are a knowledge extraction assistant. Extract key concepts from the given note. " +
            "Return ONLY a JSON array of objects with 'name' and 'description' fields. " +
            "Do not include any thinking tags, explanatory text, markdown, or code fences. " +
            "Each concept should be a significant topic, entity, or idea from the note. " +
            "Limit to 10 concepts maximum.$langInstruction"

        val userPrompt = "Extract key concepts from this note:\n${truncateText(noteContent)}\n\n" +
            "Return JSON: [{\"name\": \"...\", \"description\": \"...\"}]"

        val result = generateResponse(systemPrompt, userPrompt, maxTokens = 1500)

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

    /**
     * Generate an interactive study deck with flashcards, cloze deletions, and MCQs from a note.
     */
    suspend fun generateStudyDeck(
        noteId: String,
        noteTitle: String,
        noteContent: String,
        language: String
    ): StudyDeck {
        val langInstruction = if (language != "en") "\nEnsure prompts and answers are in $language language." else ""

        val systemPrompt = "You are an educational study assistant. Convert the given note into study flashcards and quiz questions.\n" +
            "Return ONLY a valid JSON array of objects with the following schema:\n" +
            "[{\n" +
            "  \"type\": \"QA\" | \"CLOZE\" | \"MULTIPLE_CHOICE\" | \"DEFINITION\",\n" +
            "  \"prompt\": \"...\",\n" +
            "  \"answer\": \"...\",\n" +
            "  \"options\": [\"optA\", \"optB\", \"optC\", \"optD\"],\n" +
            "  \"correctOptionIndex\": 0,\n" +
            "  \"explanation\": \"...\",\n" +
            "  \"sourceSnippet\": \"...\",\n" +
            "  \"isUncertain\": false,\n" +
            "  \"tags\": [\"tag1\"]\n" +
            "}]\n" +
            "Rules:\n" +
            "• Generate 4 to 8 high-yield cards.\n" +
            "• Every card MUST link to a direct verbatim sourceSnippet from the note.\n" +
            "• Do not invent unsupported facts. If uncertain, set isUncertain=true.\n" +
            "• No code fences or conversational markdown.$langInstruction"

        val userPrompt = "Note Title: $noteTitle\n\nContent:\n${truncateText(noteContent)}\n\nGenerate study deck JSON:"

        val rawResult = generateResponse(systemPrompt, userPrompt, maxTokens = 1500)
        val cards = mutableListOf<Flashcard>()

        if (!rawResult.isNullOrBlank()) {
            try {
                val jsonArray = JSONArray(rawResult)
                for (i in 0 until jsonArray.length()) {
                    val obj = jsonArray.getJSONObject(i)
                    val typeStr = obj.optString("type", "QA").uppercase()
                    val cardType = when (typeStr) {
                        "CLOZE" -> StudyCardType.CLOZE
                        "MULTIPLE_CHOICE", "MCQ" -> StudyCardType.MULTIPLE_CHOICE
                        "DEFINITION" -> StudyCardType.DEFINITION
                        else -> StudyCardType.QA
                    }

                    val prompt = obj.optString("prompt", "").trim()
                    val answer = obj.optString("answer", "").trim()

                    if (prompt.isNotBlank() && answer.isNotBlank()) {
                        val optionsList = mutableListOf<String>()
                        val optsArray = obj.optJSONArray("options")
                        if (optsArray != null) {
                            for (j in 0 until optsArray.length()) {
                                optionsList.add(optsArray.optString(j))
                            }
                        }

                        val tagsList = mutableListOf<String>()
                        val tagsArray = obj.optJSONArray("tags")
                        if (tagsArray != null) {
                            for (k in 0 until tagsArray.length()) {
                                tagsList.add(tagsArray.optString(k))
                            }
                        }

                        val rawSnippet = obj.optString("sourceSnippet").takeIf { it.isNotBlank() }
                        val cleanSnippet = if (rawSnippet != null && (
                                rawSnippet.contains("[Truncated", ignoreCase = true) ||
                                rawSnippet.contains("Truncated text", ignoreCase = true) ||
                                rawSnippet.contains("showing first", ignoreCase = true)
                            )) {
                            null
                        } else {
                            rawSnippet
                        }

                        cards.add(
                            Flashcard(
                                id = UUID.randomUUID().toString(),
                                noteId = noteId,
                                type = cardType,
                                prompt = prompt,
                                answer = answer,
                                options = optionsList,
                                correctOptionIndex = obj.optIntLenient("correctOptionIndex"),
                                explanation = obj.optString("explanation").takeIf { it.isNotBlank() },
                                sourceSnippet = cleanSnippet,
                                sourceTitle = noteTitle.ifBlank { "Untitled Note" },
                                isUncertain = obj.optBoolean("isUncertain", false),
                                tags = tagsList
                            )
                        )
                    }
                }
            } catch (e: Exception) {
                android.util.Log.w(TAG, "Failed to parse study deck JSON: $rawResult", e)
            }
        }

        // Fallback: if model generation is empty or parsing failed, generate rule-based cards
        if (cards.isEmpty()) {
            cards.addAll(generateRuleBasedCards(noteId, noteTitle, noteContent))
        }

        return StudyDeck(
            title = noteTitle.ifBlank { "Untitled Note" },
            noteId = noteId,
            cards = cards
        )
    }

    /**
     * Deterministic rule-based flashcard generator for fallback.
     */
    private fun generateRuleBasedCards(
        noteId: String,
        noteTitle: String,
        noteContent: String
    ): List<Flashcard> {
        val title = noteTitle.ifBlank { "Untitled Note" }
        val cards = mutableListOf<Flashcard>()
        val paragraphs = noteContent.split("\n\n")
            .map { it.trim() }
            .filter { it.length >= 20 && !it.contains("[Truncated", ignoreCase = true) }

        if (title.isNotBlank() && paragraphs.isNotEmpty()) {
            cards.add(
                Flashcard(
                    id = UUID.randomUUID().toString(),
                    noteId = noteId,
                    type = StudyCardType.QA,
                    prompt = "What are the core ideas covered in \"$title\"?",
                    answer = paragraphs.first().take(200),
                    sourceSnippet = paragraphs.first().take(150),
                    sourceTitle = title,
                    tags = listOf("overview")
                )
            )
        }

        for ((idx, p) in paragraphs.take(4).withIndex()) {
            if (p.contains(":") && !p.startsWith("http")) {
                val parts = p.split(":", limit = 2)
                if (parts.size == 2 && parts[0].length < 40 && parts[1].length > 10) {
                    cards.add(
                        Flashcard(
                            id = UUID.randomUUID().toString(),
                            noteId = noteId,
                            type = StudyCardType.DEFINITION,
                            prompt = parts[0].trim().removePrefix("#").trim(),
                            answer = parts[1].trim(),
                            sourceSnippet = p.take(150),
                            sourceTitle = title,
                            tags = listOf("concept")
                        )
                    )
                }
            }
        }

        return cards
    }

    /**
     * Stop any active intelligence generation (LLM inference).
     */
    fun stopIntelligence() {
        llamaCppService.stopGeneration()
    }
}
