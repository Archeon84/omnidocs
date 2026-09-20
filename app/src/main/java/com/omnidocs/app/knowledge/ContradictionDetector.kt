package com.omnidocs.app.knowledge

import android.util.Log
import com.omnidocs.app.ai.AiOutputProcessor
import com.omnidocs.app.ai.LlamaCppService
import com.omnidocs.app.ai.ModelDownloadManager
import com.omnidocs.app.ai.ModelPreferences
import com.omnidocs.app.ai.PromptBuilder
import com.omnidocs.app.ai.resolveActiveModel
import com.omnidocs.app.data.local.ClaimDao
import com.omnidocs.app.data.local.entity.ClaimEntity
import kotlinx.coroutines.flow.first
import org.json.JSONArray
import org.json.JSONObject
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "ContradictionDetector"

/**
 * Detects potential contradictions between claims across different notes.
 * Uses LLM to compare claim pairs and identify conflicts.
 */
@Singleton
class ContradictionDetector @Inject constructor(
    private val llamaCppService: LlamaCppService,
    private val modelDownloadManager: ModelDownloadManager,
    private val modelPreferences: ModelPreferences,
    private val claimDao: ClaimDao
) {
    data class Contradiction(
        val claimA: ClaimEntity,
        val claimB: ClaimEntity,
        val explanation: String,
        val confidence: Float
    )

    /**
     * Find potential contradictions among all approved claims.
     * Compares claim pairs using LLM.
     */
    suspend fun findContradictions(maxPairs: Int = 20): List<Contradiction> {
        val model = resolveActiveModel(modelPreferences, modelDownloadManager) ?: return emptyList()

        // Get all approved claims. .first() -- a DAO Flow is infinite and
        // collect {} here never returns, so this function used to hang forever.
        val allClaims = buildList {
            addAll(claimDao.getClaimsByType("decision").first())
            addAll(claimDao.getClaimsByType("fact").first())
        }

        if (allClaims.size < 2) return emptyList()

        val contradictions = mutableListOf<Contradiction>()

        // Compare pairs of claims (limit to avoid excessive LLM calls)
        val pairs = mutableListOf<Pair<ClaimEntity, ClaimEntity>>()
        for (i in allClaims.indices) {
            for (j in i + 1 until minOf(allClaims.size, i + 10)) {
                if (allClaims[i].noteId != allClaims[j].noteId) { // Only cross-note
                    pairs.add(allClaims[i] to allClaims[j])
                }
            }
        }

        for ((claimA, claimB) in pairs.take(maxPairs)) {
            val result = compareClaims(model, claimA, claimB)
            if (result != null) {
                contradictions.add(result)
            }
        }

        Log.d(TAG, "Found ${contradictions.size} contradictions out of ${pairs.size} pairs")
        return contradictions
    }

    private suspend fun compareClaims(
        model: com.omnidocs.app.ai.ModelInfo,
        claimA: ClaimEntity,
        claimB: ClaimEntity
    ): Contradiction? {
        val systemPrompt = """You are a contradiction detection system. Compare two claims and determine if they contradict each other.

Return a JSON object:
{
  "contradicts": true/false,
  "explanation": "why they contradict or don't contradict",
  "confidence": 0.0-1.0
}

Rules:
- Only mark as contradictory if they make genuinely incompatible assertions about the same topic
- Different opinions about different topics are NOT contradictions
- Variations in wording that mean the same thing are NOT contradictions
- Return ONLY valid JSON"""

        val userPrompt = "Claim A: \"${claimA.text}\"\n(From: ${claimA.claimType}, confidence: ${claimA.confidence})\n\n" +
            "Claim B: \"${claimB.text}\"\n(From: ${claimB.claimType}, confidence: ${claimB.confidence})\n\n" +
            "Do these claims contradict each other?"

        return try {
            val prompt = PromptBuilder.buildPrompt(model.promptFormat, systemPrompt, userPrompt, model)
            val result = llamaCppService.generate(prompt, maxTokens = 300) ?: return null
            val cleaned = AiOutputProcessor.process(result)
            val jsonStr = cleaned.replace(Regex("```json\\s*"), "").replace(Regex("```\\s*"), "").trim()
            val obj = JSONObject(jsonStr)

            if (obj.optBoolean("contradicts", false)) {
                Contradiction(
                    claimA = claimA,
                    claimB = claimB,
                    explanation = obj.optString("explanation", ""),
                    confidence = obj.optDouble("confidence", 0.5).toFloat()
                )
            } else null
        } catch (e: Exception) {
            Log.e(TAG, "Failed to compare claims", e)
            null
        }
    }
}
