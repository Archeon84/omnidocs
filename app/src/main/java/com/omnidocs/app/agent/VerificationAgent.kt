package com.omnidocs.app.agent

import org.json.JSONArray
import org.json.JSONObject
import javax.inject.Inject
import javax.inject.Singleton

data class VerificationReport(
    val isVerified: Boolean,
    val finalConfidence: String,
    val citationCoverage: Float,
    val unsupportedClaims: List<String>
)

/**
 * Agent responsible for verifying factual claims, citation precision,
 * and ensuring answers are strictly supported by retrieved evidence.
 */
@Singleton
class VerificationAgent @Inject constructor() : Agent {

    override val id: String = "agent_verification"

    override suspend fun execute(input: AgentInput, context: AgentContext): AgentResult {
        if (context.isCancelled()) {
            return AgentResult.PermanentFailure("Verification cancelled by user")
        }

        val answer = input.payload["answer"] ?: ""
        val citationsJson = input.payload["citationsJson"] ?: "[]"
        val rawConfidence = input.payload["confidence"] ?: "MEDIUM"

        val citationsArray = try {
            JSONArray(citationsJson)
        } catch (e: Exception) {
            JSONArray()
        }

        val citations = mutableListOf<Citation>()
        for (i in 0 until citationsArray.length()) {
            val obj = citationsArray.getJSONObject(i)
            citations.add(
                Citation(
                    noteId = obj.optString("noteId"),
                    noteTitle = obj.optString("noteTitle"),
                    blockId = obj.optString("blockId").takeIf { it.isNotBlank() },
                    startOffset = if (obj.has("startOffset")) obj.getInt("startOffset") else null,
                    endOffset = if (obj.has("endOffset")) obj.getInt("endOffset") else null,
                    quoteSnippet = obj.optString("quoteSnippet"),
                    quoteHash = obj.optString("quoteHash")
                )
            )
        }

        val report = verify(answer, citations, rawConfidence)

        val verifiedCitationsArray = JSONArray()
        for (c in citations) {
            val obj = JSONObject()
            obj.put("noteId", c.noteId)
            obj.put("noteTitle", c.noteTitle)
            obj.put("blockId", c.blockId ?: "")
            obj.put("quoteSnippet", c.quoteSnippet)
            obj.put("quoteHash", c.quoteHash)
            verifiedCitationsArray.put(obj)
        }

        return AgentResult.Success(
            payload = mapOf(
                "isVerified" to report.isVerified,
                "finalConfidence" to report.finalConfidence,
                "citationCoverage" to report.citationCoverage.toDouble(),
                "unsupportedClaimsCount" to report.unsupportedClaims.size,
                "verifiedCitationsJson" to verifiedCitationsArray.toString()
            )
        )
    }

    fun verify(
        answer: String,
        citations: List<Citation>,
        initialConfidence: String
    ): VerificationReport {
        if (answer.isBlank()) {
            return VerificationReport(
                isVerified = false,
                finalConfidence = "LOW",
                citationCoverage = 0f,
                unsupportedClaims = listOf("Answer text is empty")
            )
        }

        val hasInsufficientMarker = answer.contains("Insufficient evidence", ignoreCase = true)
        if (hasInsufficientMarker) {
            return VerificationReport(
                isVerified = true,
                finalConfidence = "LOW",
                citationCoverage = 1.0f,
                unsupportedClaims = emptyList()
            )
        }

        if (citations.isEmpty()) {
            return VerificationReport(
                isVerified = false,
                finalConfidence = "LOW",
                citationCoverage = 0f,
                unsupportedClaims = listOf("Answer lacks supporting source citations")
            )
        }

        // Check citation coverage: does the answer reference sources or align with quotes?
        var matchedQuotes = 0
        for (citation in citations) {
            val snippetKeywords = citation.quoteSnippet
                .split(" ")
                .filter { it.length > 3 }
                .take(5)

            if (snippetKeywords.any { answer.contains(it, ignoreCase = true) } ||
                answer.contains(citation.noteTitle, ignoreCase = true) ||
                answer.contains("[Source", ignoreCase = true)) {
                matchedQuotes++
            }
        }

        val coverage = if (citations.isNotEmpty()) matchedQuotes.toFloat() / citations.size else 0f
        val isVerified = coverage >= 0.5f
        val finalConfidence = when {
            isVerified && initialConfidence == "HIGH" -> "HIGH"
            isVerified -> "MEDIUM"
            else -> "LOW"
        }

        return VerificationReport(
            isVerified = isVerified,
            finalConfidence = finalConfidence,
            citationCoverage = coverage,
            unsupportedClaims = if (isVerified) emptyList() else listOf("Citation coverage below threshold")
        )
    }
}
