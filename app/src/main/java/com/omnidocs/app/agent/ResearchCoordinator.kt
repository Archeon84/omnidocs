package com.omnidocs.app.agent

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import org.json.JSONArray
import javax.inject.Inject
import javax.inject.Singleton

data class GroundedResearchResult(
    val jobId: String,
    val question: String,
    val answer: String,
    val citations: List<Citation>,
    val confidence: String,
    val isVerified: Boolean,
    val insufficientEvidence: Boolean,
    val queryType: String,
    val durationMs: Long,
    val ruleBased: Boolean = false,
    /** True when generation was cut short (token/context/thermal/stall). */
    val answerIncomplete: Boolean = false,
    /** Native stop reason name (StopReason.name): "eos", "max_tokens", ... */
    val stopReason: String = "unknown"
)

/**
 * Coordinator implementing Workflow 3: "Evidence-backed Ask".
 * Orchestrates: Query Classifier -> Hybrid Retrieval (weighted-sum fusion)
 * -> Answer Generation -> Verification.
 */
@Singleton
class ResearchCoordinator @Inject constructor(
    private val agentCoordinator: AgentCoordinator,
    private val queryClassifierAgent: QueryClassifierAgent,
    private val retrievalAgent: RetrievalAgent,
    private val answerAgent: AnswerAgent,
    private val verificationAgent: VerificationAgent
) {
    /**
     * Executes evidence-backed research Q&A with full provenance and verification.
     */
    suspend fun executeGroundedAsk(
        question: String,
        language: String = "en",
        privacyMode: PrivacyMode = PrivacyMode.LOCAL_ONLY,
        searchQuery: String = question,
        conversationHistory: List<Pair<String, String>> = emptyList(),
        onAnswerToken: (String) -> Unit = {}
    ): GroundedResearchResult = withContext(Dispatchers.IO) {
        val startTime = System.currentTimeMillis()
        val job = agentCoordinator.createJob(
            jobType = "WORKFLOW_EVIDENCE_BACKED_ASK",
            inputRefsJson = "{\"question\":\"${escapeJsonString(question)}\"}"
        )
        val jobId = job.id

        try {
            // 1. Query Classification (0% -> 20%)
            agentCoordinator.updateProgress(jobId, 15)
            val classifyInput = AgentInput(
                type = "CLASSIFY_QUERY",
                payload = mapOf("query" to searchQuery)
            )
            val classifyResult = agentCoordinator.runAgent(jobId, queryClassifierAgent, classifyInput, privacyMode)
            val queryType = if (classifyResult is AgentResult.Success) {
                classifyResult.payload["queryType"] as? String ?: "MIXED"
            } else "MIXED"
            val bm25Weight = (classifyResult as? AgentResult.Success)?.payload?.get("bm25Weight")?.toString() ?: "0.5"
            val semanticWeight = (classifyResult as? AgentResult.Success)?.payload?.get("semanticWeight")?.toString() ?: "0.5"

            ensureActive()

            // 2. Retrieval & RRF Fusion (20% -> 50%)
            agentCoordinator.updateProgress(jobId, 40)
            val retrievalInput = AgentInput(
                type = "RETRIEVE_EVIDENCE",
                payload = mapOf(
                    "query" to searchQuery,
                    "queryType" to queryType,
                    "bm25Weight" to bm25Weight,
                    "semanticWeight" to semanticWeight,
                    "topK" to "10"
                )
            )
            val retrievalResult = agentCoordinator.runAgent(jobId, retrievalAgent, retrievalInput, privacyMode)
            val candidatesJson = if (retrievalResult is AgentResult.Success) {
                retrievalResult.payload["candidatesJson"] as? String ?: "[]"
            } else "[]"
            val insufficientEvidenceInitial = (retrievalResult as? AgentResult.Success)?.payload?.get("insufficientEvidence") as? Boolean ?: false

            if (insufficientEvidenceInitial || candidatesJson == "[]") {
                agentCoordinator.updateProgress(jobId, 100)
                return@withContext GroundedResearchResult(
                    jobId = jobId,
                    question = question,
                    answer = "Insufficient evidence in your workspace to answer this question.",
                    citations = emptyList(),
                    confidence = "LOW",
                    isVerified = true,
                    insufficientEvidence = true,
                    queryType = queryType,
                    durationMs = System.currentTimeMillis() - startTime
                )
            }

            ensureActive()

            // 3. Grounded Answer Generation (50% -> 80%)
            agentCoordinator.updateProgress(jobId, 70)
            val historyJson = JSONArray()
            for ((role, text) in conversationHistory) {
                val obj = org.json.JSONObject()
                obj.put("role", role)
                obj.put("text", text)
                historyJson.put(obj)
            }
            val answerInput = AgentInput(
                type = "GENERATE_ANSWER",
                payload = mapOf(
                    "query" to question,
                    "candidatesJson" to candidatesJson,
                    "language" to language,
                    "conversationHistoryJson" to historyJson.toString()
                )
            )
            val answerResult = agentCoordinator.runAgent(jobId, answerAgent, answerInput, privacyMode, onAnswerToken = onAnswerToken)
            val answerText = (answerResult as? AgentResult.Success)?.payload?.get("answer") as? String ?: "No answer could be generated."
            val citationsJson = (answerResult as? AgentResult.Success)?.payload?.get("citationsJson") as? String ?: "[]"
            val rawConfidence = (answerResult as? AgentResult.Success)?.payload?.get("confidence") as? String ?: "MEDIUM"
            val insufficientEvidence = (answerResult as? AgentResult.Success)?.payload?.get("insufficientEvidence") as? Boolean ?: false
            val ruleBased = (answerResult as? AgentResult.Success)?.payload?.get("ruleBased") as? Boolean ?: false
            val answerIncomplete = (answerResult as? AgentResult.Success)?.payload?.get("answerIncomplete") as? Boolean ?: false
            val stopReason = (answerResult as? AgentResult.Success)?.payload?.get("stopReason") as? String ?: "unknown"

            ensureActive()

            // 4. Verification Pass (80% -> 100%)
            agentCoordinator.updateProgress(jobId, 90)
            val verificationInput = AgentInput(
                type = "VERIFY_ANSWER",
                payload = mapOf(
                    "answer" to answerText,
                    "citationsJson" to citationsJson,
                    "confidence" to rawConfidence
                )
            )
            val verifyResult = agentCoordinator.runAgent(jobId, verificationAgent, verificationInput, privacyMode)
            val initialVerified = (verifyResult as? AgentResult.Success)?.payload?.get("isVerified") as? Boolean ?: false
            val correctedAnswer = (verifyResult as? AgentResult.Success)?.payload?.get("correctedAnswer") as? String
            val rawFinalConfidence = (verifyResult as? AgentResult.Success)?.payload?.get("finalConfidence") as? String ?: rawConfidence

            val (finalAnswer, finalConfidence, isVerified) = if (!initialVerified && !correctedAnswer.isNullOrBlank()) {
                // Surgical self-correction loop recovered a fully-verified answer!
                android.util.Log.i("ResearchCoordinator", "Surgical self-correction loop recovered verified answer")
                Triple(correctedAnswer, "MEDIUM", true)
            } else {
                Triple(answerText, rawFinalConfidence, initialVerified)
            }

            // Parse final citations
            val citations = mutableListOf<Citation>()
            try {
                val array = JSONArray(citationsJson)
                for (i in 0 until array.length()) {
                    val obj = array.getJSONObject(i)
                    citations.add(
                        Citation(
                            noteId = obj.optString("noteId"),
                            noteTitle = obj.optString("noteTitle"),
                            blockId = obj.optString("blockId").takeIf { it.isNotBlank() },
                            startOffset = if (obj.has("startOffset")) obj.getInt("startOffset") else null,
                            endOffset = if (obj.has("endOffset")) obj.getInt("endOffset") else null,
                            quoteSnippet = obj.optString("quoteSnippet"),
                            quoteHash = obj.optString("quoteHash"),
                            score = obj.optDouble("score", 0.0),
                            sourceIndex = obj.optInt("sourceIndex", 0)
                        )
                    )
                }
            } catch (e: Exception) {
                android.util.Log.w("ResearchCoordinator", "Failed to parse citationsJson", e)
            }

            val referencedIndices = parseReferencedSources(finalAnswer, Int.MAX_VALUE)
            val filteredCitations = if (isVerified && referencedIndices.isNotEmpty()) {
                citations.filter { it.sourceIndex in referencedIndices }
            } else citations

            agentCoordinator.updateProgress(jobId, 100)
            agentCoordinator.recordEvent(
                jobId = jobId,
                agentId = "research_coordinator",
                eventType = "RESEARCH_COMPLETED",
                safeMetadata = "{\"citationsCount\":${filteredCitations.size},\"isVerified\":$isVerified,\"confidence\":\"$finalConfidence\"}"
            )

            GroundedResearchResult(
                jobId = jobId,
                question = question,
                answer = finalAnswer,
                citations = filteredCitations,
                confidence = finalConfidence,
                isVerified = isVerified,
                insufficientEvidence = insufficientEvidence,
                queryType = queryType,
                durationMs = System.currentTimeMillis() - startTime,
                ruleBased = ruleBased,
                answerIncomplete = answerIncomplete,
                stopReason = stopReason
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            android.util.Log.e("ResearchCoordinator", "Grounded ask failed", e)
            val message = e.message ?: e.javaClass.simpleName
            GroundedResearchResult(
                jobId = jobId,
                question = question,
                answer = "Error during research: $message",
                citations = emptyList(),
                confidence = "LOW",
                isVerified = false,
                insufficientEvidence = true,
                queryType = "ERROR",
                durationMs = System.currentTimeMillis() - startTime
            )
        } finally {
            agentCoordinator.clearJobCancellation(jobId)
        }
    }
}
