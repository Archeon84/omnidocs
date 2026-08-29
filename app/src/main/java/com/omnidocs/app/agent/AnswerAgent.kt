package com.omnidocs.app.agent

import com.omnidocs.app.ai.LlamaCppService
import com.omnidocs.app.ai.ModelDownloadManager
import com.omnidocs.app.ai.ModelPreferences
import com.omnidocs.app.ai.resolveActiveModel
import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest
import javax.inject.Inject
import javax.inject.Singleton

data class Citation(
    val noteId: String,
    val noteTitle: String,
    val blockId: String? = null,
    val startOffset: Int? = null,
    val endOffset: Int? = null,
    val quoteSnippet: String,
    val quoteHash: String
)

/**
 * Agent responsible for generating grounded answers strictly constrained
 * to supplied evidence candidates, outputting structured citations.
 */
@Singleton
class AnswerAgent @Inject constructor(
    private val llamaCppService: LlamaCppService,
    private val modelPreferences: ModelPreferences,
    private val modelDownloadManager: ModelDownloadManager
) : Agent {

    override val id: String = "agent_answer"

    override suspend fun execute(input: AgentInput, context: AgentContext): AgentResult {
        if (context.isCancelled()) {
            return AgentResult.PermanentFailure("Answer generation cancelled by user")
        }

        val query = input.payload["query"] ?: ""
        val candidatesJson = input.payload["candidatesJson"] ?: "[]"
        val language = input.payload["language"] ?: "en"

        val candidatesArray = try {
            JSONArray(candidatesJson)
        } catch (e: Exception) {
            JSONArray()
        }

        if (candidatesArray.length() == 0) {
            return AgentResult.Success(
                payload = mapOf(
                    "answer" to "No relevant notes or documents found to answer your question.",
                    "citationsJson" to "[]",
                    "confidence" to "LOW",
                    "insufficientEvidence" to true
                )
            )
        }

        // Build grounded evidence context
        val contextBuilder = StringBuilder()
        val citations = mutableListOf<Citation>()

        for (i in 0 until candidatesArray.length()) {
            val obj = candidatesArray.getJSONObject(i)
            val noteId = obj.optString("noteId", "")
            val noteTitle = obj.optString("noteTitle", "Untitled")
            val text = obj.optString("text", "")
            val blockId = obj.optString("blockId").takeIf { it.isNotBlank() }
            val startOffset = if (obj.has("startOffset")) obj.getInt("startOffset") else null
            val endOffset = if (obj.has("endOffset")) obj.getInt("endOffset") else null

            contextBuilder.append("[Source ${i + 1}: $noteTitle]\n$text\n\n")

            val quoteSnippet = text.take(150)
            val quoteHash = MessageDigest.getInstance("SHA-256")
                .digest(quoteSnippet.toByteArray())
                .joinToString("") { "%02x".format(it) }

            citations.add(
                Citation(
                    noteId = noteId,
                    noteTitle = noteTitle,
                    blockId = blockId,
                    startOffset = startOffset,
                    endOffset = endOffset,
                    quoteSnippet = quoteSnippet,
                    quoteHash = quoteHash
                )
            )
        }

        val model = resolveActiveModel(modelPreferences, modelDownloadManager)
        val answerText = if (model != null) {
            val prompt = """You are a grounded knowledge assistant.
Answer the user's question ONLY using the provided sources. If the sources do not contain enough facts to answer, state clearly: "Insufficient evidence in your workspace."
Always cite sources by their [Source X] labels.

Sources:
$contextBuilder

Question: $query
Answer:"""

            llamaCppService.generate(prompt, maxTokens = 512)
                ?: generateRuleBasedAnswer(query, citations)
        } else {
            generateRuleBasedAnswer(query, citations)
        }

        val isInsufficient = answerText.contains("Insufficient evidence", ignoreCase = true)
        val confidence = when {
            isInsufficient -> "LOW"
            candidatesArray.length() >= 3 -> "HIGH"
            else -> "MEDIUM"
        }

        val citationsJsonArray = JSONArray()
        for (c in citations) {
            val obj = JSONObject()
            obj.put("noteId", c.noteId)
            obj.put("noteTitle", c.noteTitle)
            obj.put("blockId", c.blockId ?: "")
            obj.put("startOffset", c.startOffset ?: -1)
            obj.put("endOffset", c.endOffset ?: -1)
            obj.put("quoteSnippet", c.quoteSnippet)
            obj.put("quoteHash", c.quoteHash)
            citationsJsonArray.put(obj)
        }

        return AgentResult.Success(
            payload = mapOf(
                "answer" to answerText,
                "citationsJson" to citationsJsonArray.toString(),
                "confidence" to confidence,
                "insufficientEvidence" to isInsufficient
            )
        )
    }

    private fun generateRuleBasedAnswer(query: String, citations: List<Citation>): String {
        val top = citations.firstOrNull() ?: return "No relevant notes found."
        return "Based on [Source 1: ${top.noteTitle}]:\n\"${top.quoteSnippet}...\""
    }
}
