package com.omnidocs.app.agent

import com.omnidocs.app.data.local.AiArtifactDao
import com.omnidocs.app.data.local.entity.AiArtifactEntity
import com.omnidocs.app.knowledge.ContradictionDetector
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Agent responsible for scanning approved claims across workspace notes,
 * identifying conflicting assertions via LLM reasoning, and persisting contradiction artifacts.
 */
@Singleton
class ContradictionDetectionAgent @Inject constructor(
    private val contradictionDetector: ContradictionDetector,
    private val aiArtifactDao: AiArtifactDao
) : Agent {

    override val id: String = "agent_contradiction_detection"

    override suspend fun execute(input: AgentInput, context: AgentContext): AgentResult {
        if (context.isCancelled()) {
            return AgentResult.PermanentFailure("Contradiction detection cancelled by user")
        }

        val maxPairs = input.payload["maxPairs"]?.toIntOrNull() ?: 20
        val contradictions = contradictionDetector.findContradictions(maxPairs)

        val now = System.currentTimeMillis()
        val artifacts = mutableListOf<AiArtifactEntity>()
        val jsonArray = JSONArray()

        for (c in contradictions) {
            val obj = JSONObject()
            obj.put("noteIdA", c.claimA.noteId)
            obj.put("claimA", c.claimA.text)
            obj.put("noteIdB", c.claimB.noteId)
            obj.put("claimB", c.claimB.text)
            obj.put("explanation", c.explanation)
            obj.put("confidence", c.confidence.toDouble())
            jsonArray.put(obj)

            artifacts.add(
                AiArtifactEntity(
                    id = UUID.randomUUID().toString(),
                    noteId = c.claimA.noteId,
                    artifactType = AiArtifactEntity.TYPE_CONTRADICTION,
                    content = "${c.claimA.text} vs ${c.claimB.text}: ${c.explanation}",
                    approvalState = AiArtifactEntity.APPROVAL_PENDING,
                    verificationStatus = AiArtifactEntity.STATUS_VERIFIED,
                    createdAt = now,
                    updatedAt = now
                )
            )
        }

        if (artifacts.isNotEmpty()) {
            aiArtifactDao.insertArtifacts(artifacts)
        }

        return AgentResult.Success(
            payload = mapOf(
                "contradictionCount" to contradictions.size,
                "contradictionsJson" to jsonArray.toString()
            )
        )
    }
}
