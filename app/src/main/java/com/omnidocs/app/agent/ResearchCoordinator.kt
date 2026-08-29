package com.omnidocs.app.agent

import kotlinx.coroutines.Dispatchers
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
    val durationMs: Long
)

/**
 * Coordinator implementing Workflow 3: "Evidence-backed Ask".
 * Orchestrates: Query Classifier -> Hybrid Retrieval (RRF) -> Answer Generation -> Verification.
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
        privacyMode: PrivacyMode = PrivacyMode.LOCAL_ONLY
    ): GroundedResearchResult = withContext(Dispatchers.IO) {
        val startTime = System.currentTimeMillis()
        val job = agentCoordinator.createJob(
            jobType = "WORKFLOW_EVIDENCE_BACKED_ASK",
            inputRefsJson = "{\"question\":\"$question\"}"
        )
        val jobId = job.id

        try {
            // 1. Query Classification (0% -> 20%)
            agentCoordinator.updateProgress(jobId, 15)
            val classifyInput = AgentInput(
                type = "CLASSIFY_QUERY",
                payload = mapOf("query" to question)
            )
            val classifyResult = agentCoordinator.runAgent(jobId, queryClassifierAgent, classifyInput, privacyMode)
            val queryType = if (classifyResult is AgentResult.Success) {
                classifyResult.payload["queryType"] as? String ?: "MIXED"
            } else "MIXED"
            val bm25Weight = (classifyResult as? AgentResult.Success)?.payload?.get("bm25Weight")?.toString() ?: "0.5"
            val semanticWeight = (classifyResult as? AgentResult.Success)?.payload?.get("semanticWeight")?.toString() ?: "0.5"

            // 2. Retrieval & RRF Fusion (20% -> 50%)
            agentCoordinator.updateProgress(jobId, 40)
            val retrievalInput = AgentInput(
                type = "RETRIEVE_EVIDENCE",
                payload = mapOf(
                    "query" to question,
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

            // 3. Grounded Answer Generation (50% -> 80%)
            agentCoordinator.updateProgress(jobId, 70)
            val answerInput = AgentInput(
                type = "GENERATE_ANSWER",
                payload = mapOf(
                    "query" to question,
                    "candidatesJson" to candidatesJson,
                    "language" to language
                )
            )
            val answerResult = agentCoordinator.runAgent(jobId, answerAgent, answerInput, privacyMode)
            val answerText = (answerResult as? AgentResult.Success)?.payload?.get("answer") as? String ?: "No answer could be generated."
            val citationsJson = (answerResult as? AgentResult.Success)?.payload?.get("citationsJson") as? String ?: "[]"
            val rawConfidence = (answerResult as? AgentResult.Success)?.payload?.get("confidence") as? String ?: "MEDIUM"
            val insufficientEvidence = (answerResult as? AgentResult.Success)?.payload?.get("insufficientEvidence") as? Boolean ?: false

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
            val isVerified = (verifyResult as? AgentResult.Success)?.payload?.get("isVerified") as? Boolean ?: false
            val finalConfidence = (verifyResult as? AgentResult.Success)?.payload?.get("finalConfidence") as? String ?: rawConfidence

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
                            quoteHash = obj.optString("quoteHash")
                        )
                    )
                }
            } catch (e: Exception) {
                // Ignore parse errors
            }

            agentCoordinator.updateProgress(jobId, 100)
            agentCoordinator.recordEvent(
                jobId = jobId,
                agentId = "research_coordinator",
                eventType = "RESEARCH_COMPLETED",
                safeMetadata = "{\"citationsCount\":${citations.size},\"isVerified\":$isVerified,\"confidence\":\"$finalConfidence\"}"
            )

            GroundedResearchResult(
                jobId = jobId,
                question = question,
                answer = answerText,
                citations = citations,
                confidence = finalConfidence,
                isVerified = isVerified,
                insufficientEvidence = insufficientEvidence,
                queryType = queryType,
                durationMs = System.currentTimeMillis() - startTime
            )
        } catch (e: Exception) {
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
        }
    }
}
